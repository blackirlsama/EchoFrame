"""Per-run identity binding validation for the 4B-1 evaluation entry.

The Java entry test writes one manifest per run binding that run to its trial / pair / controller
identity, the ORIGINAL report bytes and run-scoped trace, permission checks, the formal video
fingerprint (source version read from EvaluationMaterialBindings at the video boundary) and the
fee applicability note. This module re-verifies such manifests INDEPENDENTLY: required fields,
digest recomputation when artifacts are present, trace containment, trial derivation, video
fingerprint binding (never the fixed c64 placeholder) and duplicate-trial refusal. The pair id is
NOT treated as proof by itself — manifests missing any required field or carrying a fingerprint
that contradicts the formal bindings are refused.
"""
import hashlib
import json
from pathlib import Path

REQUIRED_FIELDS = (
    "trial_id", "run_id", "session_id", "pair_id", "controller", "controller_invoked",
    "repeat_no", "conditions", "video_id", "video_grounding", "final_status",
    "receipt_artifact_id", "permission_principal", "permission_read_status",
    "permission_cross_account_status",
    "material_video_sha256", "material_comment_sha256", "material_danmaku_sha256",
    "video_source_version", "task_clock_zone", "task_clock_reference",
    "provider_dispatches", "fee_note", "purpose",
)
PURPOSES = {"pair_member", "frozen_replay_control", "clock_rule_normal_control",
            "clock_rule_refusal_control"}


def _is_hex64(value):
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def validate_run_record(record):
    """Batch-level record validation: the identity/purpose fields a batch index entry carries.
    Delivered runs must carry 64-hex report/trace digests; refused records carry an error code."""
    if not isinstance(record, dict):
        raise ValueError("run record: must be an object")
    for field in ("run_id", "trial_id", "pair_id", "controller"):
        if not isinstance(record.get(field), str) or not record[field].strip():
            raise ValueError(f"run record.{field}: must be a non-empty string")
    purpose = record.get("purpose")
    if purpose not in PURPOSES:
        raise ValueError(f"run record.purpose: must be one of {', '.join(sorted(PURPOSES))}")
    grounding = record.get("grounding")
    if grounding not in ("VIDEO_GROUNDED", "FEEDBACK_ONLY"):
        raise ValueError(f"run record.grounding: must be VIDEO_GROUNDED or FEEDBACK_ONLY")
    if purpose == "clock_rule_refusal_control":
        if not isinstance(record.get("error_code"), str) or not record["error_code"]:
            raise ValueError("refusal record.error_code: must be a non-empty string")
        return
    status = record.get("final_status")
    if status not in ("SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_INPUT"):
        raise ValueError(f"run record.final_status: unknown status")
    if status != "SUCCEEDED":
        if not isinstance(record.get("error_code"), str) or not record["error_code"]:
            raise ValueError("non-SUCCEEDED record.error_code: must be a non-empty string")
        return
    for field in ("report_json_sha256", "report_html_sha256", "trace_sha256"):
        if not _is_hex64(record.get(field)):
            raise ValueError(f"delivered run record.{field}: must be a 64-hex digest")


def validate_pair_purpose(records, pair_id):
    """Counts the formal pair members (purpose=pair_member) and the control records that
    reference the pair. Controls never inflate the member count; the pair must have exactly
    two members with distinct controllers."""
    members = [r for r in records if r.get("purpose") == "pair_member" and r.get("pair_id") == pair_id]
    controls = [r for r in records if r.get("purpose") in PURPOSES - {"pair_member"}
                and r.get("pair_id") == pair_id]
    controllers = {m.get("controller") for m in members}
    if len(members) != 2 or len(controllers) != 2:
        raise ValueError(f"pair {pair_id[:12]}: must have exactly two formal members with distinct "
                         f"controllers (found {len(members)} member records, {len(controllers)} controllers); "
                         f"{len(controls)} control records are excluded from membership")
    return {"pair_members": len(members), "controls": len(controls)}


def trial_id_for(run_id, pair_id):
    """Deterministic per-run trial id: sha-256(run_id|pair_id), first 16 hex characters."""
    return "trial-" + hashlib.sha256(f"{run_id}|{pair_id}".encode("utf-8")).hexdigest()[:16]


def validate_run_manifest(manifest, formal_bindings=None):
    """Re-verifies one run manifest independently.

    formal_bindings: optional EvaluationMaterialBindings-equivalent mapping
    {video_id: {"video_sha256": ...}} — when given, the manifest's video fingerprint must match
    the formal binding for its video id (the fixed c*64 placeholder is always refused).
    Returns the derived trial id. Raises ValueError with a field-path style diagnostic.
    """
    where = "run manifest"
    if not isinstance(manifest, dict):
        raise ValueError(f"{where}: must be an object")
    missing = [f for f in REQUIRED_FIELDS if f not in manifest]
    if missing:
        raise ValueError(f"{where}: missing required fields: {', '.join(missing)}")
    for field in ("trial_id", "run_id", "session_id", "pair_id", "controller", "video_grounding",
                  "final_status", "receipt_artifact_id", "permission_principal"):
        if not isinstance(manifest[field], str) or not manifest[field].strip():
            raise ValueError(f"{where}.{field}: must be a non-empty string")
    if manifest["controller"] != manifest["controller_invoked"]:
        raise ValueError(f"{where}.controller_invoked: must match the declared controller")
    repeat = manifest["repeat_no"]
    if not isinstance(repeat, int) or isinstance(repeat, bool) or repeat < 1:
        raise ValueError(f"{where}.repeat_no: must be a positive integer")
    video_id = manifest["video_id"]
    if not isinstance(video_id, int) or isinstance(video_id, bool) or video_id <= 0:
        raise ValueError(f"{where}.video_id: must be a positive integer")
    for field in ("material_video_sha256", "material_comment_sha256", "material_danmaku_sha256"):
        if not _is_hex64(manifest[field]):
            raise ValueError(f"{where}.{field}: must be a 64-hex digest")
    if not _is_hex64(manifest["video_source_version"]):
        raise ValueError(f"{where}.video_source_version: must be a 64-hex digest")
    if formal_bindings is not None:
        expected = formal_bindings.get(str(video_id)) or formal_bindings.get(video_id)
        if expected is None:
            raise ValueError(f"{where}.video_id: no formal binding for this video id")
        expected_sha = expected["video_sha256"] if isinstance(expected, dict) else expected
        if manifest["video_source_version"] != expected_sha:
            raise ValueError(f"{where}.video_source_version: does not match the formal binding; "
                             "a fixed placeholder or foreign fingerprint is refused")
    # Repair-01: the joined entry records REAL dispatches to the local loopback double. A
    # non-zero count is legitimate evidence ONLY with a dispatch table whose every entry is
    # attributed to THIS run and aimed at a loopback host; a real provider host in the table
    # is refused by name (the fee evidence chain lives in the run's ledger + request log).
    dispatch_count = manifest["provider_dispatches"]
    if isinstance(dispatch_count, bool) or not isinstance(dispatch_count, int) or dispatch_count < 0:
        raise ValueError(f"{where}.provider_dispatches: must be a non-negative integer")
    dispatches = manifest.get("dispatches")
    if dispatch_count > 0:
        if not isinstance(dispatches, list) or len(dispatches) != dispatch_count:
            raise ValueError(f"{where}.dispatches: a manifest recording {dispatch_count} dispatches "
                             f"must carry a dispatch table of exactly {dispatch_count} entries")
        for entry in dispatches:
            host = entry.get("host")
            if host not in ("127.0.0.1", "localhost", "[::1]", "::1"):
                raise ValueError(f"{where}.dispatches: non-loopback dispatch host {host!r} — this "
                                 "evidence dispatches only to the local double; a real provider "
                                 "host here is refused")
            if entry.get("run_id") != manifest["run_id"]:
                raise ValueError(f"{where}.dispatches: dispatch attributed to {entry.get('run_id')!r} "
                                 f"does not belong to run {manifest['run_id']!r}")
    elif dispatches not in (None, []):
        raise ValueError(f"{where}.dispatches: a zero-dispatch manifest must not carry a dispatch table")
    derived = trial_id_for(manifest["run_id"], manifest["pair_id"])
    if manifest["trial_id"] != derived:
        raise ValueError(f"{where}.trial_id: does not match the run/pair derivation")
    return derived


def validate_run_directory(run_dir, formal_bindings=None):
    """Validates one per-run directory: manifest integrity plus the ORIGINAL artifacts' digests
    and trace containment (every span run.id must be the directory's run)."""
    run_dir = Path(run_dir)
    manifest = json.loads(run_dir.joinpath("manifest.json").read_text(encoding="utf-8"))
    trial_id = validate_run_manifest(manifest, formal_bindings)
    grounding = manifest.get("video_grounding")
    report_file = run_dir / "report.json"
    if grounding == "VIDEO_GROUNDED" or manifest.get("final_status") == "SUCCEEDED":
        if not report_file.is_file():
            raise ValueError(f"run {manifest['run_id']}: delivered runs must archive report.json")
    if report_file.is_file():
        data = report_file.read_bytes()
        if hashlib.sha256(data).hexdigest() != manifest.get("report_json_sha256"):
            raise ValueError(f"run {manifest['run_id']}: report.json digest does not match the manifest")
    trace_file = run_dir / "trace-spans.jsonl"
    if trace_file.is_file():
        for line in trace_file.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            span = json.loads(line)
            span_run = span.get("attributes", {}).get("run.id")
            if span_run and span_run != manifest["run_id"]:
                raise ValueError(f"run {manifest['run_id']}: trace contains spans of another run")
    return trial_id


# The unified ledger field tables. Runs carry identity + repeat number + mode + purpose +
# terminal status + the four digests recomputed from the ORIGINAL files; refusals carry the
# full flat identity plus the outcome and reason. The Java rebuild emits exactly the same
# tables, so one generation compares field-by-field across languages.
RUN_ENTRY_FIELDS = ("run_id", "trial_id", "pair_id", "controller", "repeat_no", "grounding",
                    "purpose", "final_status", "report_json_sha256", "report_html_sha256",
                    "trace_sha256", "manifest_sha256")
REFUSAL_ENTRY_FIELDS = ("run_id", "trial_id", "pair_id", "controller", "purpose",
                        "final_status", "error_code")
# Service video id -> formal material key; the SAME table the Java EvaluationMaterialBindings
# uses, so the Python rebuild consumes the bindings the Java entry was verified against.
SERVICE_VIDEO_KEYS = {730011: "hxc-doufu", 730012: "my-dragon", 730013: "ysjf-canon"}


def bindings_from_config(config):
    """Builds the formal bindings table {video_id: {"video_sha256": ...}} from the shared
    admission config (materials[].binding), keyed by the service video ids."""
    bindings = {}
    for material in config.get("materials", []):
        key = material.get("key")
        binding = material.get("binding") or {}
        for video_id, material_key in SERVICE_VIDEO_KEYS.items():
            if material_key == key:
                bindings[str(video_id)] = {"video_sha256": binding.get("video_sha256", "")}
    return bindings


def validate_refusal_record(record):
    """Refusal ledger entry validation: the full flat identity, a non-SUCCEEDED outcome, the
    reason code and the SAME run/pair trial derivation as delivered runs."""
    if not isinstance(record, dict):
        raise ValueError("refusal record: must be an object")
    for field in ("run_id", "trial_id", "pair_id", "controller"):
        if not isinstance(record.get(field), str) or not record[field].strip():
            raise ValueError(f"refusal record.{field}: must be a non-empty string")
    purpose = record.get("purpose")
    if purpose not in PURPOSES:
        raise ValueError(f"refusal record.purpose: must be one of {', '.join(sorted(PURPOSES))}")
    status = record.get("final_status")
    if not isinstance(status, str) or not status.strip() or status == "SUCCEEDED":
        raise ValueError("refusal record.final_status: must name a non-SUCCEEDED outcome")
    if not isinstance(record.get("error_code"), str) or not record["error_code"].strip():
        raise ValueError("refusal record.error_code: must be a non-empty string")
    if record["trial_id"] != trial_id_for(record["run_id"], record["pair_id"]):
        raise ValueError("refusal record.trial_id: does not match the run/pair derivation")


def _read_json(path, where):
    """Reads one ledger file, wrapping parse failures into a ValueError that names the file."""
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise ValueError(f"{where}: {path.name} unreadable as JSON ({error})") from None


def _verified_digests(run_dir, manifest_file, manifest):
    """Recomputes every delivered artifact digest from the ORIGINAL files on disk and refuses
    missing files, manifest/file drift and cross-run trace contamination — naming the run id
    and the digest field in every refusal."""
    run_id = manifest["run_id"]
    digests = {}
    for field, name in (("report_json_sha256", "report.json"),
                        ("report_html_sha256", "report.html"),
                        ("trace_sha256", "trace-spans.jsonl")):
        path = run_dir / name
        if not path.is_file():
            raise ValueError(f"run {run_id}: delivered runs must archive {name} "
                             f"(missing file for {field})")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != manifest.get(field):
            raise ValueError(f"run {run_id}: {field} does not match {name} on disk "
                             f"(manifest declares {manifest.get(field)}, file hashes to {actual})")
        digests[field] = actual
    digests["manifest_sha256"] = hashlib.sha256(manifest_file.read_bytes()).hexdigest()
    for line in (run_dir / "trace-spans.jsonl").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        span = _read_json_text(line, f"run {run_id}: trace span")
        span_run = span.get("attributes", {}).get("run.id")
        if span_run and span_run != run_id:
            raise ValueError(f"run {run_id}: trace contains spans of another run ({span_run}) "
                             "— cross-run contamination is refused")
    return digests


def _read_json_text(text, where):
    try:
        return json.loads(text)
    except ValueError as error:
        raise ValueError(f"{where} unreadable as JSON ({error})") from None


def rebuild_index(runs_root, formal_bindings=None):
    """Rebuilds the generation batch ledger from the per-run directories' ORIGINALS. Every
    manifest is fully re-verified (required fields, trial derivation, formal video binding,
    zero real dispatches), every declared artifact digest is RECOMPUTED from the files, trace
    files are checked for cross-run contamination, refused control runs enter the ledger with
    their full flat identity, orphan directories (neither manifest.json nor refusal.json) are
    diagnosed by name, and duplicate run or trial ids across the generation are refused."""
    runs_root = Path(runs_root)
    index = []
    refusals = []
    trial_ids = set()
    run_ids = set()
    if runs_root.is_dir():
        for run_dir in sorted(p for p in runs_root.iterdir() if p.is_dir()):
            manifest_file = run_dir / "manifest.json"
            refusal_file = run_dir / "refusal.json"
            has_manifest = manifest_file.is_file()
            has_refusal = refusal_file.is_file()
            if has_manifest and has_refusal:
                # exactly ONE terminal-state original per directory: a directory carrying both
                # could otherwise contribute a success AND a refusal ledger entry at once
                raise ValueError(f"run directory {run_dir.name}: both manifest.json and "
                                 "refusal.json exist — each run directory must hold exactly one "
                                 "terminal-state original")
            if has_manifest:
                manifest = _read_json(manifest_file, f"run directory {run_dir.name}")
                validate_run_manifest(manifest, formal_bindings)
                run_id = manifest["run_id"]
                if run_dir.name != run_id:
                    # Repair-08: the ledger attributes originals by directory; a directory name
                    # disagreeing with the recorded run_id is a misattribution risk
                    raise ValueError(f"run directory {run_dir.name}: manifest.json records "
                                     f"run_id {run_id} — the directory name must equal the "
                                     "recorded run_id")
                if run_id in run_ids:
                    raise ValueError(f"duplicate run id across the generation: {run_id}")
                run_ids.add(run_id)
                trial = manifest["trial_id"]
                if trial in trial_ids:
                    raise ValueError(f"duplicate trial id across the generation: {trial}")
                trial_ids.add(trial)
                entry = {"run_id": run_id, "trial_id": trial, "pair_id": manifest["pair_id"],
                         "controller": manifest["controller"], "repeat_no": manifest["repeat_no"],
                         "grounding": manifest["video_grounding"], "purpose": manifest["purpose"],
                         "final_status": manifest["final_status"]}
                if manifest["final_status"] == "SUCCEEDED":
                    entry.update(_verified_digests(run_dir, manifest_file, manifest))
                else:
                    if not str(manifest.get("error_code") or "").strip():
                        raise ValueError(f"run {run_id}: non-SUCCEEDED manifests must carry "
                                         "an error_code")
                index.append(entry)
            elif has_refusal:
                refusal = _read_json(refusal_file, f"run directory {run_dir.name}")
                validate_refusal_record(refusal)
                if run_dir.name != refusal["run_id"]:
                    raise ValueError(f"run directory {run_dir.name}: refusal.json records "
                                     f"run_id {refusal['run_id']} — the directory name must "
                                     "equal the recorded run_id")
                if refusal["run_id"] in run_ids:
                    raise ValueError(f"duplicate run id across the generation: {refusal['run_id']}")
                run_ids.add(refusal["run_id"])
                if refusal["trial_id"] in trial_ids:
                    raise ValueError(f"duplicate trial id across the generation: {refusal['trial_id']}")
                trial_ids.add(refusal["trial_id"])
                refusals.append({field: refusal[field] for field in REFUSAL_ENTRY_FIELDS})
            else:
                raise ValueError(f"orphan directory {run_dir.name}: neither manifest.json nor "
                                 "refusal.json — the run cannot be attributed to this ledger")

    return {"runs": index, "refusals": refusals, "run_count": len(index),
            "refusal_count": len(refusals)}


def _ledger_entries_match(rebuilt_entries, declared_entries, fields, validator):
    """Full-identity comparison of one ledger entry list: equal counts, every entry a
    well-formed record of its kind, run ids unique on BOTH sides, and the sorted field tuples
    equal — omissions, extras and per-field drift all fail."""
    if not isinstance(rebuilt_entries, list) or not isinstance(declared_entries, list):
        return False
    if len(rebuilt_entries) != len(declared_entries):
        return False
    try:
        for side in (rebuilt_entries, declared_entries):
            seen = set()
            for entry in side:
                if not isinstance(entry, dict):
                    return False
                validator(entry)
                rid = entry.get("run_id", "")
                if rid in seen:
                    return False
                seen.add(rid)
        def _key(entry):
            return tuple(entry.get(field) for field in fields)
        return sorted(map(_key, rebuilt_entries)) == sorted(map(_key, declared_entries))
    except (ValueError, TypeError):
        return False


def _strict_total(side, count_field, list_field):
    """One side's total must be present, strictly an integer (bool refused), and equal its own
    array length."""
    if not isinstance(side, dict) or count_field not in side:
        return False
    value = side[count_field]
    if isinstance(value, bool) or not isinstance(value, int):
        return False
    return value == len(side[list_field])


def batch_index_matches(rebuilt, declared):
    """Field-by-field comparison of a rebuilt batch ledger against the declared one: the
    top-level run/refusal totals are required strict integers equal to their own array lengths
    and to each other, every run entry matches on the full unified table
    (identity, repeat number, grounding, purpose, terminal status, all four digests) and every
    refusal entry matches on its full flat identity; duplicates, omissions, extras and
    identity-less records fail in either direction. Malformed entries compare unequal instead
    of raising, so tampering can never crash past the comparison."""
    if not isinstance(rebuilt, dict) or not isinstance(declared, dict):
        return False
    for list_field in ("runs", "refusals"):
        if not isinstance(rebuilt.get(list_field), list) or not isinstance(declared.get(list_field), list):
            return False
    # Repair-08: the totals are REQUIRED strict integers (bool refused — Python treats bool
    # as an integer subtype), each equal to its own array length, and the declared total must
    # equal the rebuilt total. Missing, textual, boolean, wrong-valued or drifted totals,
    # missing entries and extras are all mismatches.
    for count_field, list_field in (("run_count", "runs"), ("refusal_count", "refusals")):
        if not _strict_total(rebuilt, count_field, list_field) \
                or not _strict_total(declared, count_field, list_field):
            return False
        if rebuilt[count_field] != declared[count_field]:
            return False
    if not _ledger_entries_match(rebuilt["runs"], declared["runs"], RUN_ENTRY_FIELDS,
                                 validate_run_record):
        return False
    return _ledger_entries_match(rebuilt["refusals"], declared["refusals"], REFUSAL_ENTRY_FIELDS,
                                 validate_refusal_record)


def index_for_generation(generation_dir):
    """Convenience: rebuild + write nothing; returns the index for the generation directory."""
    return rebuild_index(generation_dir)
