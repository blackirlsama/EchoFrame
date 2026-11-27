"""Import a verified product capture into the existing trial ledger, preserving the originals.

The bundle is copied verbatim under artifacts/product/ so delivery can re-run the same strict
validation against the preserved bytes instead of trusting anything the adapter wrote.
report.md is a derived reading view, explicitly marked, and is never used to judge delivery.
"""
import json
from pathlib import Path

from . import cases, ledger
from .product_bundle import (ADAPTER_VERSION, EVIDENCE_LEVEL, BundleError, TraceBundleError,
                             _span_identity_problems, bind_case, capture_identity,
                             fingerprint_payload, load_bundle, trace_identity)
from .util import (CaseError, OutOfBoundsError, atomic_write_json, canonical_json, ensure_real_path,
                   sha256_file)

PRODUCT_DIR = ("artifacts", "product")
DERIVED_MARKER = "<!-- adapter_derived_view: product-adapter-v1; not a product original -->"


def _load_verified(root):
    """Load a bundle, keeping a verified report when only the Trace axis failed."""
    try:
        return load_bundle(root)
    except TraceBundleError as e:
        return e.bundle


def _prepare(case):
    errors = cases.validate_case(case, mode="product")
    if errors:
        raise CaseError("; ".join(errors))


def _copy_originals(trial, bundle):
    """Byte-for-byte copy of the capture, keeping the bundle's own relative layout."""
    written = {}
    files = [("bundle.json", bundle.manifest_bytes)]
    responses = bundle.manifest.get("responses") or {}
    for where, data in (("json", bundle.json_bytes), ("html", bundle.html_bytes)):
        record = responses.get(where)
        if data is not None and isinstance(record, dict):
            files.append((record["path"], data))
    trace = bundle.manifest.get("trace")
    if bundle.trace_bytes is not None and isinstance(trace, dict):
        files.append((trace["path"], bundle.trace_bytes))
    for rel, data in files:
        target = trial.write_artifact("/".join(PRODUCT_DIR + (rel,)), data)
        written[rel] = {"bytes": len(data), "sha256": sha256_file(target)}
    return written


def _derived_view(bundle):
    """Human reading aid only. Every line comes from the product JSON; nothing is scored here."""
    report = bundle.report or {}
    scope = report.get("scope") or {}
    lines = [DERIVED_MARKER, "", f"# {report.get('title', '')}", "", report.get("summary", ""), "",
             f"- video_id: {scope.get('video_id', '')}",
             f"- window: {scope.get('start', '')} .. {scope.get('end', '')} ({scope.get('timezone', '')})",
             f"- sources: {', '.join(scope.get('sources') or [])}",
             f"- grounding: {scope.get('grounding', '')}", ""]
    for heading, key in (("Topics", "topics"), ("Other topics", "otherTopics")):
        lines += [f"## {heading}", ""]
        topics = report.get(key) or []
        if not topics:
            lines += ["(none)", ""]
        for topic in topics:
            lines += [f"### {topic.get('title', '')}", "", topic.get("observation", ""),
                      f"comments={topic.get('commentCount')} danmaku={topic.get('danmakuCount')}", ""]
    return ("\n".join(lines) + "\n").encode("utf-8")


def import_bundle(output, case, bundle):
    """Create a brand-new trial from an already validated bundle. Never overwrites an existing trial."""
    _prepare(case)
    binding = bind_case(bundle, case)
    capture_fp = fingerprint_payload(bundle)
    fp = cases.case_fingerprint(case, capture=capture_fp)
    trial = ledger.create_trial(output, case, fp, cases.experiment_id(fp))
    trial.meta["capture_identity"] = capture_identity(bundle)
    trial.meta["capture_fingerprint_payload"] = capture_fp
    atomic_write_json(trial.dir / "trial.json", {"trial_id": trial.trial_id, **trial.meta},
                      anchor=trial.anchor)

    written = _copy_originals(trial, bundle)
    if bundle.trace_bytes is not None:
        # The reused public accounting reads this frozen path; the bytes are the captured spans.
        trial.write_artifact("traces/trace.jsonl", bundle.trace_bytes)
    if bundle.report is not None:
        trial.write_artifact("artifacts/report.md", _derived_view(bundle))

    run = bundle.manifest["run"]
    provenance = {
        "evidence_level": EVIDENCE_LEVEL,
        "adapter_version": ADAPTER_VERSION,
        "bundle_id": bundle.bundle_id,
        "source_bundle_dir": str(bundle.anchor),
        "producer": bundle.manifest.get("producer"),
        "run_id": run.get("run_id"),
        "final_status": bundle.final_status,
        "artifact_id": run.get("artifact_id"),
        "preserved_files": written,
        "binding_problems": binding,
        "capture_delivery_problems": bundle.delivery_problems,
        "capture_trace_problems": bundle.trace_problems,
        "semantic_quality": "NOT_ASSESSED",
        "notes": ["Originals are byte-preserved; delivery re-validates them, not this record.",
                  "artifacts/report.md is an adapter-derived view, not a product original.",
                  "A mocked ModelPort produces no dispatched model.request spans; usage stays unknown.",
                  "Trace faults are recorded on the Trace axis and never change report delivery."],
    }
    trial.write_artifact("artifacts/product/capture.json", canonical_json(provenance).encode("utf-8"))
    trial.append_attempt({"kind": "product_capture", "outcome": bundle.final_status,
                          "bundle_id": bundle.bundle_id, "run_id": run.get("run_id"),
                          "binding_problems": binding,
                          "capture_delivery_problems": bundle.delivery_problems,
                          "capture_trace_problems": bundle.trace_problems})
    trial.finish({"final_status": bundle.final_status, "report_id": run.get("artifact_id"),
                  "first_attempt_outcome": bundle.final_status, "recovered": False,
                  "evidence_level": EVIDENCE_LEVEL, "adapter_version": ADAPTER_VERSION,
                  "bundle_id": bundle.bundle_id, "binding_problems": binding})
    return trial


def _preserve_readable_originals(trial, source):
    """Copy whatever the rejected bundle actually declared, so the ledger can be re-examined without
    the external source. Path protection is never relaxed to save a file: an absolute, traversing or
    reparse-pointed declaration is recorded as a diagnosis only."""
    preserved, refused = {}, []
    try:
        manifest_path = ensure_real_path(source, "bundle.json")
    except OutOfBoundsError as e:
        return preserved, ["bundle.json:" + str(e)]
    declarations = []
    if manifest_path.is_file():
        data = manifest_path.read_bytes()
        trial.write_artifact("artifacts/product/bundle.json", data)
        preserved["bundle.json"] = {"bytes": len(data), "sha256": sha256_file(manifest_path)}
        try:
            manifest = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            manifest = None
        if isinstance(manifest, dict):
            responses = manifest.get("responses")
            if isinstance(responses, dict):
                for where in ("json", "html"):
                    record = responses.get(where)
                    if isinstance(record, dict) and isinstance(record.get("path"), str):
                        declarations.append(record["path"])
            trace = manifest.get("trace")
            if isinstance(trace, dict) and isinstance(trace.get("path"), str):
                declarations.append(trace["path"])
    for rel in declarations:
        try:
            path = ensure_real_path(source, rel)
        except OutOfBoundsError as e:
            refused.append(f"{rel}:{e}")
            continue
        if not path.is_file():
            refused.append(f"{rel}:not_a_readable_file")
            continue
        try:
            data = path.read_bytes()
        except OSError as e:
            refused.append(f"{rel}:read_failed:{type(e).__name__}")
            continue
        target = trial.write_artifact("/".join(PRODUCT_DIR + (rel,)), data)
        preserved[rel] = {"bytes": len(data), "sha256": sha256_file(target)}
    return preserved, refused


def import_unverifiable(output, case, root, error):
    """Preserve a rejected capture as evidence without inventing a terminal status.

    No trial_summary.json is written: the product outcome could not be verified, so the ledger
    records an unverified capture and the batch summary classifies the trial as corrupted.
    """
    capture = case.get("product_capture") if isinstance(case.get("product_capture"), dict) else {}
    capture_fp = {"evidence_level": EVIDENCE_LEVEL, "adapter_version": ADAPTER_VERSION,
                  "bundle_id": capture.get("bundle_id"), "bundle_rejected": str(error)}
    fp = cases.case_fingerprint(case, capture=capture_fp)
    anchor = Path(output).resolve()
    trial = ledger.create_trial(anchor, case, fp, cases.experiment_id(fp))
    trial.meta["product_capture_rejected"] = True
    atomic_write_json(trial.dir / "trial.json", {"trial_id": trial.trial_id, **trial.meta},
                      anchor=trial.anchor)

    source = Path(root).resolve()
    preserved, refused = _preserve_readable_originals(trial, source)
    trial.write_artifact("artifacts/product/rejected.json", canonical_json({
        "evidence_level": EVIDENCE_LEVEL, "adapter_version": ADAPTER_VERSION,
        "source_bundle_dir": str(source), "bundle_id": capture.get("bundle_id"),
        "rejection": str(error), "preserved_files": preserved, "unpreserved_paths": refused,
        "terminal_status": None,
        "note": "Capture failed strict validation; no product terminal status is asserted.",
        "preservation_note": "Readable declared originals are copied with their actual hash/length; "
                             "unsafe or unreadable paths are diagnosed, never forced.",
    }).encode("utf-8"))
    trial.append_attempt({"kind": "product_capture", "outcome": "unverified",
                          "bundle_id": capture.get("bundle_id"), "rejection": str(error),
                          "preserved_files": sorted(preserved), "unpreserved_paths": refused})
    return trial


def verify_trace_copy(trial_dir, manifest, stored=None):
    """Bind the consumed billing copy to the preserved original, the run identity and the ledger.

    The public summarizer is read-only and trusts whatever spans it is handed, so the binding has to
    happen here first. Comparing the current original against the current copy is not enough: an
    edit that rewrites both and resyncs the manifest stays self-consistent. The identity recorded at
    import time is the authority, and it is consulted before any accounting runs. No trace
    declaration at all means genuinely MISSING, not corrupt.
    """
    trace = manifest.get("trace")
    if not isinstance(trace, dict):
        return []
    trial_dir = Path(trial_dir)
    problems = []
    run_id = (manifest.get("run") or {}).get("run_id")
    if trace.get("run_id") != run_id:
        problems.append("trace_run_mismatch")
    try:
        original = ensure_real_path(trial_dir.joinpath(*PRODUCT_DIR), trace.get("path"))
    except (OutOfBoundsError, TypeError):
        return problems + ["trace_original_path_unsafe"]
    if not original.is_file():
        return problems + ["trace_original_missing"]
    data = original.read_bytes()
    if sha256_file(original) != trace.get("sha256"):
        problems.append("trace_original_hash_mismatch")
    if len(data) != trace.get("bytes"):
        problems.append("trace_original_bytes_mismatch")
    copy = trial_dir / "traces" / "trace.jsonl"
    if not copy.is_file():
        problems.append("trace_copy_missing")
    elif copy.read_bytes() != data:
        problems.append("trace_copy_differs_from_original")
    problems.extend(_span_identity_problems(data, run_id))
    if stored is not None:
        was = stored.get("trace") if isinstance(stored, dict) else None
        if not isinstance(was, dict):
            problems.append("capture_identity_trace_unrecorded")
        else:
            for key, value in trace_identity(manifest, data).items():
                if was.get(key) != value:
                    problems.append(f"stale_trace_identity:{key}")
    return problems


def _read_meta(trial_dir):
    try:
        meta = json.loads((Path(trial_dir) / "trial.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return meta if isinstance(meta, dict) else None


def _axis_drift(stored, bundle):
    if not isinstance(stored, dict):
        return ["capture_identity_unrecorded"], []
    actual = capture_identity(bundle)
    report_drift, trace_drift = [], []
    for axis, sink in (("report", report_drift), ("trace", trace_drift)):
        was, now = stored.get(axis), actual[axis]
        if not isinstance(was, dict):
            sink.append(f"capture_identity_{axis}_unrecorded")
            continue
        for key, value in now.items():
            if was.get(key) != value:
                sink.append(f"stale_{axis}_identity:{key}")
    return report_drift, trace_drift


def _identity_drift(trial_dir, bundle):
    """Recompute the recorded identity from the preserved originals, split by axis.

    A post-import edit that resyncs the manifest declaration still changes the actual bytes, so new
    bytes cannot keep claiming the old fingerprint. History is never rewritten to make it match.
    """
    meta = _read_meta(trial_dir)
    if meta is None:
        return ["capture_identity_unreadable"], []
    return _axis_drift(meta.get("capture_identity"), bundle)


# The total fingerprint hashes the whole manifest, so a Trace-only edit moves it too. These are the
# only payload keys such an edit can move; anything else means the report axis changed as well.
_TRACE_ONLY_PAYLOAD_KEYS = {"trace_sha256", "bundle_manifest_sha256"}


def capture_payload_drift(trial_dir, bundle):
    """Attribute a total-fingerprint change to an axis before it is allowed to judge delivery.

    Returns (report_extra, trace_extra). A Trace-only drift must not convict a report whose own
    ledgered identity still matches the preserved bytes.
    """
    meta = _read_meta(trial_dir)
    if meta is None:
        return [], []
    stored = meta.get("capture_fingerprint_payload")
    if not isinstance(stored, dict):
        return ["capture_fingerprint_payload_unrecorded"], []
    actual = fingerprint_payload(bundle)
    changed = sorted(key for key in actual if stored.get(key) != actual[key])
    if not changed:
        return [], []
    report_drift, trace_drift = _axis_drift(meta.get("capture_identity"), bundle)
    detail = ",".join(changed)
    if set(changed) <= _TRACE_ONLY_PAYLOAD_KEYS and not report_drift and trace_drift:
        return [], ["capture_fingerprint_trace_drift:" + detail]
    return ["capture_fingerprint_payload_mismatch:" + detail], []


def _preserved_final_status(trial_dir):
    try:
        manifest = json.loads((Path(trial_dir).joinpath(*PRODUCT_DIR) / "bundle.json")
                              .read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(manifest, dict):
        return None
    run = manifest.get("run")
    return run.get("final_status") if isinstance(run, dict) else None


def ledger_attribution_problems(trial_dir):
    """Close the loop inside the ledger: total fingerprint -> experiment_id -> summary record.

    These are attribution faults, not delivery faults. They are recomputed from what was ledgered at
    import, never from the derived capture.json, and history is never rewritten to make them agree.
    A trial that never claimed a capture (interrupted before import) has nothing to attribute; its
    incompleteness is already reported on the delivery axis.
    """
    trial_dir = Path(trial_dir)
    summary_path = trial_dir / "trial_summary.json"
    if not summary_path.is_file():
        return []
    meta = _read_meta(trial_dir)
    if meta is None:
        return ["trial_metadata_unreadable"]
    problems = []
    fingerprint = meta.get("fingerprint")
    payload = meta.get("capture_fingerprint_payload")
    snapshot = meta.get("case_snapshot")
    if not isinstance(payload, dict):
        problems.append("capture_fingerprint_payload_unrecorded")
    elif not isinstance(snapshot, dict):
        problems.append("case_snapshot_unrecorded")
    else:
        try:
            expected = cases.case_fingerprint(snapshot, capture=payload)
        except (CaseError, KeyError, TypeError, ValueError):
            problems.append("capture_fingerprint_unrecomputable")
        else:
            if not isinstance(fingerprint, str) or fingerprint != expected:
                problems.append("capture_fingerprint_invalid")
    if isinstance(fingerprint, str) and meta.get("experiment_id") != cases.experiment_id(fingerprint):
        problems.append("capture_experiment_mismatch")
    try:
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        summary = None
    if isinstance(summary, dict):
        for key in ("case_id", "case_version", "experiment_id", "fingerprint"):
            if key in summary and summary[key] != meta.get(key):
                problems.append(f"trial_summary_identity_mismatch:{key}")
        preserved = _preserved_final_status(trial_dir)
        if preserved is not None and summary.get("final_status") != preserved:
            problems.append("trial_summary_final_status_mismatch")
    return problems


def check_product_delivery(trial_dir, case):
    """Re-validate the preserved originals; the adapter's own record is never the authority.

    Delivery, Trace and attribution are returned on separate channels so a fault on one axis cannot
    erase an independently verifiable fact on another. final_status/report_id report what the trial
    summary declared; passed/reasons are re-derived from the preserved capture, so a corrupted
    declaration convicts attribution without cancelling a delivery the originals still prove.
    """
    trial_dir = Path(trial_dir)
    result = {"passed": False, "reasons": [], "final_status": None, "report_id": None,
              "semantic_quality": "NOT_ASSESSED", "trace_diagnostics": [],
              "attribution_diagnostics": ledger_attribution_problems(trial_dir)}
    summary_path = trial_dir / "trial_summary.json"
    if not summary_path.is_file():
        result["reasons"] = ["incomplete_trial"]
        return result
    try:
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        result["reasons"] = ["trial_summary_unreadable"]
        return result
    if not isinstance(summary, dict):
        result["reasons"] = ["trial_summary_invalid"]
        return result
    result["final_status"] = summary.get("final_status")
    result["report_id"] = summary.get("report_id")

    product_dir = trial_dir.joinpath(*PRODUCT_DIR)
    try:
        bundle = _load_verified(product_dir)
    except BundleError as e:
        result["reasons"] = ["product_capture_invalid:" + str(e)]
        return result
    if bundle.final_status != "SUCCEEDED":
        result["reasons"] = [f"status_{bundle.final_status}"]
        return result
    reasons = list(bundle.delivery_problems)
    reasons.extend("product_" + p if not p.startswith("product_") else p for p in bind_case(bundle, case))
    if bundle.json_bytes is None:
        reasons.append("missing_artifact:report.json")
    if bundle.html_bytes is None:
        reasons.append("missing_artifact:report.html")
    if bundle.delivered and summary.get("report_id") != bundle.manifest["run"].get("artifact_id"):
        reasons.append("report_id_mismatch")
    report_drift, _trace_drift = _identity_drift(trial_dir, bundle)
    reasons.extend(report_drift)
    payload_report, payload_trace = capture_payload_drift(trial_dir, bundle)
    reasons.extend(payload_report)
    result["trace_diagnostics"] = payload_trace
    result["reasons"] = reasons
    result["passed"] = not reasons
    return result


def import_capture(output, case, root):
    """CLI entry: load a bundle strictly, then import it as delivered or as an unverified capture."""
    try:
        bundle = _load_verified(root)
    except BundleError as e:
        return import_unverifiable(output, case, root, e), False
    return import_bundle(output, case, bundle), True


__all__ = ["ADAPTER_VERSION", "EVIDENCE_LEVEL", "import_bundle", "import_unverifiable",
           "import_capture", "check_product_delivery", "verify_trace_copy",
           "capture_payload_drift", "ledger_attribution_problems"]