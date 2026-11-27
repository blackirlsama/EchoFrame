"""Run summary: classification, batch grouping, reused trace accounting, derived CSV view."""
import csv
import importlib
import json
import sys
from decimal import Decimal
from pathlib import Path

from .delivery import check_delivery
from .ledger import load_trials
from .util import atomic_write_json, ensure_within, read_jsonl_tolerant, utc_now_iso

CSV_COLUMNS = ["trial_id", "case_id", "experiment_id", "material_class", "semantic_eligible",
               "final_status", "first_attempt_outcome", "recovered", "attempts", "delivery_passed",
               "report_id", "trace_state", "input_tokens", "output_tokens", "cost_low", "cost_high",
               "unpriced_calls", "usage_complete", "evidence_level"]


def _evidence_level(meta):
    snapshot = (meta or {}).get("case_snapshot")
    if isinstance(snapshot, dict) and isinstance(snapshot.get("product_capture"), dict):
        return "product_http_mockmvc"
    return "offline_simulated"


def _trace_module():
    try:
        return importlib.import_module("summarize_model_trace")
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
        return importlib.import_module("summarize_model_trace")


def _corrupt_accounting():
    return {"trace_state": "CORRUPT", "input_tokens": None, "output_tokens": None,
            "cost_low": None, "cost_high": None, "unpriced_calls": None, "usage_complete": None}


_TIMING_KEYS = ("execution.queue_ms", "analysis.retry_wait_ms", "io.feedback_object_ms",
                "io.feedback_database_ms")


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _span_structure_problem(spans):
    """Validate exactly the structures the reused public summarizer consumes (identifiers used as
    keys, attributes/lineage types, metering/timing), so a bad span is diagnosed per-trial instead
    of raising AttributeError/TypeError/KeyError into the batch. Optional fields it never requires
    (missing attributes/parentSpanId) and unknown token usage stay compatible."""
    for i, span in enumerate(spans):
        if not isinstance(span, dict):
            return f"span[{i}]_not_object"
        for field in ("traceId", "spanId", "name"):
            if not isinstance(span.get(field), str):
                return f"span[{i}]_missing_{field}"
        if "parentSpanId" in span and span["parentSpanId"] is not None \
                and not isinstance(span["parentSpanId"], str):
            return f"span[{i}]_parentSpanId"
        if "attributes" in span:
            attrs = span["attributes"]
            if not isinstance(attrs, dict):
                return f"span[{i}]_attributes"
        else:
            attrs = {}
        if "run.id" in attrs and attrs["run.id"] is not None and not isinstance(attrs["run.id"], str):
            return f"span[{i}]_run_id"
        if "model.response_ms" in attrs and attrs["model.response_ms"] is not None \
                and not _is_number(attrs["model.response_ms"]):
            return f"span[{i}]_response_ms"
        for key in _TIMING_KEYS:
            if key in attrs and not _is_number(attrs[key]):
                return f"span[{i}]_timing"
        if span["name"] == "agent.run":
            for field in ("startTimeUnixNano", "endTimeUnixNano"):
                if not _is_number(span.get(field)):
                    return f"span[{i}]_{field}"
    return None


def _product_trace_binding(trial_dir, meta):
    """A product trial's billing copy must be bound to its preserved original AND to the identity
    recorded at import, before any accounting is allowed to read the spans."""
    if (meta or {}).get("product_capture_rejected") is True:
        return ["capture_rejected"]
    from .product_bridge import PRODUCT_DIR, verify_trace_copy
    manifest_path = Path(trial_dir).joinpath(*PRODUCT_DIR) / "bundle.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return ["preserved_manifest_unreadable"]
    if not isinstance(manifest, dict):
        return ["preserved_manifest_invalid"]
    return verify_trace_copy(trial_dir, manifest, stored=(meta or {}).get("capture_identity"))


def _trace_accounting(trial_dir, prices, meta=None):
    snapshot = (meta or {}).get("case_snapshot")
    if isinstance(snapshot, dict) and isinstance(snapshot.get("product_capture"), dict):
        binding = _product_trace_binding(trial_dir, meta)
        if binding:
            return _corrupt_accounting(), ["product_trace_unbound:" + ",".join(binding)], None
    trace_file = Path(trial_dir) / "traces" / "trace.jsonl"
    if not trace_file.exists():
        return {"trace_state": "MISSING", "input_tokens": None, "output_tokens": None,
                "cost_low": None, "cost_high": None, "unpriced_calls": None, "usage_complete": None}, [], None
    if not trace_file.is_file():
        return _corrupt_accounting(), ["trace_path_not_file"], None
    spans, bad_lines = read_jsonl_tolerant(trace_file)
    if bad_lines:
        diag = "unreadable_trace" if any(str(b.get("error", "")).startswith("read_failed") for b in bad_lines) \
            else "corrupt_trace_lines"
        return _corrupt_accounting(), [diag], None
    problem = _span_structure_problem(spans)
    if problem is not None:
        return _corrupt_accounting(), ["invalid_trace_structure:" + problem], None
    mod = _trace_module()
    try:
        acct = mod.summarize(spans, prices)
    except (ValueError, TypeError, KeyError) as e:
        return _corrupt_accounting(), ["trace_accounting_failed:" + type(e).__name__], e
    runs = acct["runs"]
    if not runs:
        return {"trace_state": "PRESENT_NO_REQUEST_SPANS", "input_tokens": None, "output_tokens": None,
                "cost_low": None, "cost_high": None, "unpriced_calls": None, "usage_complete": None}, [], None
    reported_runs = [r for r in runs if r["reported_calls"] > 0]
    priced = [r["estimated_cost_known_calls_range"] for r in runs if r["estimated_cost_known_calls_range"]]
    if priced:
        cost_low = str(sum(Decimal(p[0]) for p in priced))
        cost_high = str(sum(Decimal(p[1]) for p in priced))
    else:
        cost_low = cost_high = None
    return {
        "trace_state": "PRESENT",
        "input_tokens": sum(r["tokens_reported"]["input_tokens"] for r in reported_runs) if reported_runs else None,
        "output_tokens": sum(r["tokens_reported"]["output_tokens"] for r in reported_runs) if reported_runs else None,
        "cost_low": cost_low,
        "cost_high": cost_high,
        "unpriced_calls": sum(r["unpriced_calls"] for r in runs),
        "usage_complete": all(r["usage_complete"] for r in runs),
    }, [], None


def _classify(summary, delivery):
    if summary is None:
        return "interrupted"
    if summary.get("delivery_violation"):
        return "delivery_violation"
    status = summary.get("final_status")
    if status == "SUCCEEDED":
        return "success_delivered" if delivery["passed"] else "success_delivery_failed"
    if status == "FAILED":
        return "failed"
    if status == "NEEDS_INPUT":
        return "needs_input"
    return "other"


def summarize_run(root, prices_path=None, strict=True):
    root = Path(root)
    if not (root / "trials").is_dir():
        raise FileNotFoundError(f"no trials directory under {root}")
    anchor = root.resolve()
    prices = json.loads(Path(prices_path).read_text(encoding="utf-8")) if prices_path else {}
    trials = load_trials(root)
    rows = []
    classifications = {}
    attribution_invalid = set()
    for t in trials:
        meta, summary = t["meta"], t["summary"]
        accounting, trace_diag, trace_error = _trace_accounting(t["dir"], prices, meta)
        if strict and trace_error is not None:
            raise trace_error
        diagnostics = list(t["diagnostics"]) + trace_diag
        if meta is None:
            cls = "corrupted"
            row = {
                "trial_id": t["dir"].name, "case_id": None, "experiment_id": None,
                "material_class": None, "semantic_eligible": False,
                "final_status": None, "first_attempt_outcome": None, "recovered": None,
                "attempts": len(t["attempts"]),
                "delivery_passed": False, "delivery_reasons": ["trial_metadata_unavailable"],
                "report_id": None, "classification": cls, "semantic_quality": "NOT_ASSESSED",
                "diagnostics": diagnostics, "evidence_level": _evidence_level(meta), **accounting,
            }
        else:
            delivery = check_delivery(t["dir"], meta["case_snapshot"])
            attribution = list(delivery.get("attribution_diagnostics") or [])
            diagnostics += list(delivery.get("trace_diagnostics") or []) + attribution
            cls = _classify(summary, delivery)
            if any(d in diagnostics for d in ("corrupt_attempt_lines", "unreadable_attempts",
                                              "unreadable_trial_summary", "invalid_trial_summary",
                                              "invalid_trial_metadata", "unverified_product_capture")) \
                    or any(d.startswith("invalid_terminal_") for d in diagnostics):
                cls = "corrupted"
            if attribution:
                # Attribution is self-contradictory, so this trial cannot enter a clean score. The
                # delivery facts above stay as independently measured; corruption does not erase them.
                cls = "corrupted"
                attribution_invalid.add(meta["trial_id"])
            row = {
                "trial_id": meta["trial_id"], "case_id": meta["case_id"],
                "experiment_id": meta["experiment_id"], "material_class": meta["material_class"],
                "semantic_eligible": meta["semantic_eligibility"]["eligible"],
                "final_status": summary.get("final_status") if summary else None,
                "first_attempt_outcome": summary.get("first_attempt_outcome") if summary else None,
                "recovered": summary.get("recovered") if summary else None,
                "attempts": len(t["attempts"]),
                "delivery_passed": delivery["passed"], "delivery_reasons": delivery["reasons"],
                "report_id": delivery["report_id"], "classification": cls,
                "semantic_quality": "NOT_ASSESSED", "diagnostics": diagnostics,
                "evidence_level": _evidence_level(meta),
                **accounting,
            }
        classifications[cls] = classifications.get(cls, 0) + 1
        rows.append(row)
    batches = {}
    for row in rows:
        b = batches.setdefault(row["experiment_id"], {"experiment_id": row["experiment_id"],
                                                      "trial_count": 0, "case_ids": set(),
                                                      "classifications": {},
                                                      "attribution_invalid_trial_count": 0})
        b["trial_count"] += 1
        b["case_ids"].add(row["case_id"])
        b["classifications"][row["classification"]] = b["classifications"].get(row["classification"], 0) + 1
        if row["trial_id"] in attribution_invalid:
            b["attribution_invalid_trial_count"] += 1
    case_experiments = {}
    for row in rows:
        case_experiments.setdefault(row["case_id"], set()).add(row["experiment_id"])
    batch_split = sorted(cid for cid, exps in case_experiments.items() if len(exps) > 1)
    batch_list = [{"experiment_id": b["experiment_id"], "trial_count": b["trial_count"],
                   "case_ids": sorted(b["case_ids"]), "classifications": b["classifications"],
                   "attribution_invalid_trial_count": b["attribution_invalid_trial_count"]}
                  for b in batches.values()]
    summary = {
        "schema_version": 1, "generated_at": utc_now_iso(), "input_root": str(root),
        "trial_count": len(rows), "classifications": classifications,
        "corrupted_trial_count": sum(1 for r in rows if r["diagnostics"]),
        "attribution_invalid_trial_count": len(attribution_invalid),
        "batches": batch_list, "batch_split_case_ids": batch_split,
        "billing_status": "ESTIMATE_NOT_PROVIDER_INVOICE",
        "notes": ["Report delivery, process-evidence completeness and semantic scoring are separate axes.",
                  "A delivered report with a missing trace is success_delivered (delivery passed) but NOT process-evidence complete; trace_state=MISSING.",
                  "Only a missing or invalid report fails delivery; a missing trace alone does not.",
                  "Missing trace/usage stays unknown, never zero.",
                  "Unpriced calls are excluded from cost ranges, not treated as free.",
                  "Cost ranges are estimates, not a provider invoice.",
                  "Semantic quality is NOT_ASSESSED in this offline stage.",
                  "Trials with different experiment fingerprints are separate batches.",
                  "A trial whose ledgered fingerprint, experiment_id or summary identity disagree is attribution-invalid: it is isolated from clean scores, while its independently measured delivery facts stay on the row.",
                  "The total fingerprint spans both axes; a Trace-only drift is attributed to the Trace axis and never fails report delivery.",
                  "evidence_level separates offline_simulated from product_http_mockmvc captures; the latter starts no HTTP server and uses no real model.",
                  "A rejected product capture keeps its bytes but asserts no terminal status; it is reported as corrupted.",
                  "Corrupt or incomplete trial records are preserved and flagged, never guessed as success."],
        "rows": rows,
    }
    atomic_write_json(root / "summary.json", summary, anchor=anchor)
    csv_path = ensure_within(anchor, root / "summary.csv")
    with open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(CSV_COLUMNS)
        for row in rows:
            writer.writerow(["" if row.get(c) is None else row.get(c) for c in CSV_COLUMNS])
    return summary