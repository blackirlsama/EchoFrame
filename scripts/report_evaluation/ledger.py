"""Trial ledger: exclusive trial creation, append-only attempts, atomic end summary."""
import json
from pathlib import Path

from . import timebase
from .util import CaseError, append_jsonl, atomic_write_json, ensure_within, new_id, read_jsonl_tolerant, utc_now_iso

# Defined terminal statuses (matches the canonical eval_pipeline.TERMINAL_STATUSES).
TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_INPUT"}
# Material kinds the writer (cases.validate_case) and delivery actually support.
MATERIAL_KINDS = {"synthetic_inline", "dataset_ref"}
# delivery_violation is an optional bool flag; the writer's one frozen string reason marker stays
# legal (test_out_of_bounds_refused_and_recorded). Any other non-bool value (e.g. 'false') is corrupt.
DELIVERY_VIOLATION_REASONS = {"out_of_bounds"}


class Trial:
    def __init__(self, root, trial_id, meta):
        self.anchor = Path(root).resolve()
        self.dir = self.anchor / "trials" / trial_id
        self.trial_id = trial_id
        self.meta = meta
        self._attempt_no = 0

    def append_attempt(self, record):
        self._attempt_no += 1
        rec = {"attempt_no": self._attempt_no, "recorded_at": utc_now_iso(), **record}
        append_jsonl(self.dir / "attempts.jsonl", rec, anchor=self.anchor)
        return rec

    def write_artifact(self, relpath, data):
        target = ensure_within(self.dir, self.dir / relpath)
        target = ensure_within(self.anchor, target)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return target

    def finish(self, fields):
        summary = {
            "trial_id": self.trial_id, "case_id": self.meta["case_id"],
            "experiment_id": self.meta["experiment_id"], "fingerprint": self.meta["fingerprint"],
            "finished_at": utc_now_iso(), "attempt_count": self._attempt_no, **fields,
        }
        atomic_write_json(self.dir / "trial_summary.json", summary, anchor=self.anchor)
        return summary

    def simulate_interrupt(self):
        self.append_attempt({"kind": "request", "outcome": "interrupted",
                             "note": "simulated crash before completion; no summary written"})


def create_trial(root, case, fp, exp_id):
    from .cases import semantic_eligibility
    anchor = Path(root).resolve()
    trials_dir = ensure_within(anchor, anchor / "trials")
    trials_dir.mkdir(parents=True, exist_ok=True)
    meta = {
        "case_id": case["case_id"], "case_version": case["case_version"],
        "experiment_id": exp_id, "fingerprint": fp,
        "status": "running", "started_at": utc_now_iso(),
        "material_class": case["material_class"],
        "semantic_eligibility": semantic_eligibility(case),
        "case_snapshot": case,
    }
    for _ in range(10):
        trial_id = new_id("trial-")
        trial_dir = ensure_within(anchor, trials_dir / trial_id)
        try:
            trial_dir.mkdir(exist_ok=False)
        except FileExistsError:
            continue
        atomic_write_json(trial_dir / "trial.json", {"trial_id": trial_id, **meta}, anchor=anchor)
        return Trial(anchor, trial_id, meta)
    raise RuntimeError("could not allocate a unique trial directory")


def _read_json_object(path, missing_diag, invalid_diag, unreadable_diag, diagnostics):
    if not path.exists():
        if missing_diag:
            diagnostics.append(missing_diag)
        return None
    if not path.is_file():
        diagnostics.append(unreadable_diag)
        return None
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        diagnostics.append(unreadable_diag)
        return None
    if not isinstance(obj, dict):
        diagnostics.append(invalid_diag)
        return None
    return obj


def _window_structure_problem(snapshot):
    """Validate exactly what timebase.resolve_window consumes when a task_window is present."""
    clock = snapshot.get("task_clock")
    if not isinstance(clock, dict):
        return "task_clock"
    if not isinstance(clock.get("timezone"), str) or not isinstance(clock.get("reference_time"), str):
        return "task_clock_fields"
    spec = snapshot.get("task_window")
    if not isinstance(spec, dict):
        return "task_window"
    kind = spec.get("kind")
    if kind == "absolute":
        if not isinstance(spec.get("start"), str) or not isinstance(spec.get("end"), str):
            return "task_window_absolute"
    elif kind == "relative":
        days = spec.get("days")
        if not isinstance(days, int) or isinstance(days, bool) or days <= 0:
            return "task_window_days"
        if "anchor" in spec and not isinstance(spec["anchor"], str):
            return "task_window_anchor"
    else:
        return "task_window_kind"
    # Type-correct is not enough: the window must actually compute. A positive-int days that overflows
    # timedelta/datetime raises OverflowError (not CaseError); isolate it here so a bad time context is
    # corrupted metadata, never a batch crash nor a clean report masquerading as a delivery failure.
    try:
        timebase.resolve_window(clock, spec)
    except (CaseError, OverflowError):
        return "task_window_uncomputable"
    return None


def _incomplete_meta_fields(meta):
    """Required metadata fields and nested types, driven by what check_delivery / resolve_window /
    batch grouping actually consume; a trial missing any is not usable. Optional fields that the
    summarize path never reads (e.g. material.content) are not validated."""
    problems = []
    for field in ("trial_id", "case_id", "experiment_id", "material_class"):
        if not isinstance(meta.get(field), str):
            problems.append(field)
    eligibility = meta.get("semantic_eligibility")
    if not isinstance(eligibility, dict) or not isinstance(eligibility.get("eligible"), bool):
        problems.append("semantic_eligibility")
    snapshot = meta.get("case_snapshot")
    if not isinstance(snapshot, dict):
        problems.append("case_snapshot")
        return problems
    material = snapshot.get("material")
    if not isinstance(material, dict):
        problems.append("case_snapshot")
        return problems
    kind = material.get("kind")
    if not isinstance(kind, str) or kind not in MATERIAL_KINDS:
        problems.append("case_snapshot.material.kind")
    elif kind == "dataset_ref":
        keys = material.get("keys")
        if not isinstance(keys, list) or not all(isinstance(k, str) for k in keys):
            problems.append("case_snapshot.material.keys")
    if "task_window" in snapshot and _window_structure_problem(snapshot) is not None:
        problems.append("case_snapshot.task_window")
    if "product_capture" in snapshot and _product_capture_problem(snapshot["product_capture"]) is not None:
        problems.append("case_snapshot.product_capture")
    return problems


def _product_capture_problem(capture):
    """Validate exactly what the product delivery branch consumes from the case snapshot."""
    if not isinstance(capture, dict):
        return "not_object"
    for field in ("bundle_id", "evidence_level", "adapter_version"):
        if not isinstance(capture.get(field), str) or not capture[field].strip():
            return field
    request = capture.get("request")
    if not isinstance(request, dict):
        return "request"
    for field in ("prompt", "owner_token", "video_id", "grounding"):
        if not isinstance(request.get(field), str):
            return "request_" + field
    sources = request.get("sources")
    if not isinstance(sources, list) or not all(isinstance(s, str) for s in sources):
        return "request_sources"
    return None


def _attempt_structure_problem(record):
    """A valid attempt matches the ledger.append_attempt output contract; parseable JSON alone
    (null/scalar/array/missing fields) is not a usable attempt."""
    if not isinstance(record, dict):
        return "attempt_not_object"
    attempt_no = record.get("attempt_no")
    if not isinstance(attempt_no, int) or isinstance(attempt_no, bool) or attempt_no < 1:
        return "attempt_attempt_no"
    if not isinstance(record.get("recorded_at"), str):
        return "attempt_recorded_at"
    if not isinstance(record.get("kind"), str):
        return "attempt_kind"
    return None


def _invalid_terminal_fields(summary):
    """Optional terminal fields the summary row/CSV and _classify consume. When present they must match
    the writer contract: recovered a bool, first_attempt_outcome a defined status, delivery_violation a
    bool or the frozen reason marker. A present-but-invalid value is corruption, never coerced to clean."""
    bad = []
    if "recovered" in summary and not isinstance(summary["recovered"], bool):
        bad.append("recovered")
    if "first_attempt_outcome" in summary:
        value = summary["first_attempt_outcome"]
        if not isinstance(value, str) or value not in TERMINAL_STATUSES:
            bad.append("first_attempt_outcome")
    if "delivery_violation" in summary:
        value = summary["delivery_violation"]
        if not isinstance(value, bool) and not (isinstance(value, str) and value in DELIVERY_VIOLATION_REASONS):
            bad.append("delivery_violation")
    return bad


def load_trials(root):
    root = Path(root)
    trials_dir = root / "trials"
    if not trials_dir.is_dir():
        return []
    out = []
    for trial_dir in sorted(p for p in trials_dir.iterdir() if p.is_dir()):
        diagnostics = []
        meta = _read_json_object(trial_dir / "trial.json", "missing_trial_metadata",
                                 "invalid_trial_metadata", "unreadable_trial_metadata", diagnostics)
        if meta is not None:
            missing_fields = _incomplete_meta_fields(meta)
            if missing_fields:
                diagnostics.append("incomplete_trial_metadata:" + ",".join(missing_fields))
                meta = None
            elif meta.get("product_capture_rejected") is True:
                # The capture failed strict validation, so no product terminal status was asserted.
                diagnostics.append("unverified_product_capture")
        summary = _read_json_object(trial_dir / "trial_summary.json", None,
                                    "invalid_trial_summary", "unreadable_trial_summary", diagnostics)
        if summary is not None:
            status = summary.get("final_status")
            if not isinstance(status, str) or status not in TERMINAL_STATUSES:
                diagnostics.append("invalid_terminal_status:" + ("missing" if status is None else "unknown"))
            for field in _invalid_terminal_fields(summary):
                diagnostics.append("invalid_terminal_" + field)
                summary[field] = None
        attempts, bad_attempt_lines = read_jsonl_tolerant(trial_dir / "attempts.jsonl",
                                                          validate=_attempt_structure_problem)
        if bad_attempt_lines:
            if any(str(b.get("error", "")).startswith("read_failed") for b in bad_attempt_lines):
                diagnostics.append("unreadable_attempts")
            else:
                diagnostics.append("corrupt_attempt_lines")
        out.append({"dir": trial_dir, "meta": meta, "summary": summary,
                    "attempts": attempts, "diagnostics": diagnostics,
                    "bad_attempt_lines": bad_attempt_lines})
    return out