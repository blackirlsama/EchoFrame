"""Delivery checks: status, report identity, structure, source identity, time scope, readability.

Report delivery, process-evidence completeness (trace) and semantic scoring are separate concerns.
A delivered report with a missing trace still passes delivery; semantic quality stays NOT_ASSESSED.
Every read/parse/structure failure is diagnosed on the single trial, never raised into the batch.
"""
import hashlib
import json
from pathlib import Path

from . import timebase
from .util import CaseError, OutOfBoundsError, ensure_within


def _allowed_keys(case):
    material = case["material"]
    if material["kind"] == "dataset_ref":
        return set(material["keys"])
    return {"synthetic"}


def check_delivery(trial_dir, case):
    # A product capture is judged on the preserved product bytes, never on the offline report format.
    if isinstance(case.get("product_capture"), dict):
        from .product_bridge import check_product_delivery
        return check_product_delivery(trial_dir, case)
    trial_dir = Path(trial_dir)
    result = {"passed": False, "reasons": [], "final_status": None, "report_id": None,
              "semantic_quality": "NOT_ASSESSED"}
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
    status = summary.get("final_status")
    report_id = summary.get("report_id")
    result["final_status"] = status
    result["report_id"] = report_id
    if status != "SUCCEEDED":
        result["reasons"] = [f"status_{status}"]
        return result

    reasons = []
    if not report_id:
        reasons.append("missing_report_id")
    rj = trial_dir / "artifacts" / "report.json"
    rm = trial_dir / "artifacts" / "report.md"
    for p in (rj, rm):
        if not p.is_file():
            reasons.append(f"missing_artifact:{p.name}")
    if reasons:
        result["reasons"] = reasons
        return result
    try:
        ensure_within(trial_dir, rj)
        ensure_within(trial_dir, rm)
    except OutOfBoundsError:
        reasons.append("artifact_out_of_bounds")

    report = None
    report_parsed = False
    try:
        report = json.loads(rj.read_text(encoding="utf-8"))
        report_parsed = True
    except (OSError, ValueError):
        reasons.append("report_json_unreadable")
    # A report that parses to null/scalar/array is structurally invalid; the identity/source/hash
    # checks must not be skipped just because the parsed value happens to be None.
    if report_parsed and not isinstance(report, dict):
        reasons.append("report_structure_invalid")

    md_bytes = None
    try:
        md_bytes = rm.read_bytes()
    except OSError:
        reasons.append("markdown_read_failed")
    if md_bytes is not None:
        try:
            md_text = md_bytes.decode("utf-8")
        except UnicodeDecodeError:
            reasons.append("markdown_undecodable")
            md_text = None
        if md_text is not None and not md_text.strip():
            reasons.append("markdown_empty")

    if isinstance(report, dict):
        rid = report.get("report_id")
        if not isinstance(rid, str) or rid != report_id:
            reasons.append("report_id_mismatch")
        content_hash = report.get("content_sha256")
        if md_bytes is not None and (not isinstance(content_hash, str)
                                     or hashlib.sha256(md_bytes).hexdigest() != content_hash):
            reasons.append("content_hash_mismatch")
        sources = report.get("sources")
        if not isinstance(sources, dict):
            reasons.append("report_structure_invalid:sources")
        else:
            keys = sources.get("dataset_keys")
            if not isinstance(keys, list) or not keys:
                reasons.append("missing_source_identity")
            elif not all(isinstance(k, str) for k in keys):
                reasons.append("source_identity_invalid")
            elif not set(keys) <= _allowed_keys(case):
                reasons.append("source_scope_violation")
            if "task_window" in case:
                window = sources.get("window")
                if window is None:
                    reasons.append("missing_time_window")
                else:
                    try:
                        expected = timebase.resolve_window(case["task_clock"], case["task_window"])
                    except CaseError:
                        reasons.append("window_unresolvable")
                    else:
                        if window != expected:
                            reasons.append("window_out_of_scope")
    result["reasons"] = reasons
    result["passed"] = not reasons
    return result