"""Paired-condition records for the 4B-1 trial pairs.

The pair identity binds the SHARED experimental conditions only — prompt (题面), formal material
content hashes, window and task clock, sampling / tool versions, fixed non-controller roles,
permissions / session initial state, scoring version and run mode. The controller identity, the
repeat number and any report bytes never participate, so the two controllers of one pair share a
single pair id while a changed material / window / clock derives a different one. Mirrors the
Java EvaluationPairConditions canonicalization (key-sorted compact JSON, sha-256).
"""
import hashlib
import json

from ..util import canonical_json

SHARED_FIELDS = ("prompt", "config_path", "material_key", "material_hashes", "window_start",
                 "window_end", "task_clock_zone", "task_clock_reference", "sampling_strategy",
                 "tool_config_version", "fixed_roles", "permissions", "session_initial_state",
                 "scoring_version", "run_mode")


def _validate_conditions(conditions):
    if not isinstance(conditions, dict):
        raise ValueError(f"pair conditions must be an object: {type(conditions).__name__}")
    missing = [f for f in SHARED_FIELDS if f not in conditions]
    if missing:
        raise ValueError(f"pair conditions missing shared fields: {', '.join(missing)}")
    hashes = conditions["material_hashes"]
    if not isinstance(hashes, dict) or set(hashes) != {"video_sha256", "comment_sha256", "danmaku_sha256"}:
        raise ValueError("material_hashes must bind exactly video/comment/danmaku sha256 values")


def pair_id(conditions):
    """Identity over shared conditions only; any change to a shared field derives a new id."""
    _validate_conditions(conditions)
    payload = {field: conditions[field] for field in SHARED_FIELDS}
    return hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()


def pair_membership(conditions, controller, repeat_no):
    """One trial's membership: the shared pair id plus the identity that is NOT part of the id."""
    _validate_conditions(conditions)
    if not isinstance(controller, str) or not controller.strip():
        raise ValueError(f"controller must be a non-empty string: {controller!r}")
    if not isinstance(repeat_no, int) or isinstance(repeat_no, bool) or repeat_no < 1:
        raise ValueError(f"repeat_no starts at 1: {repeat_no!r}")
    record = {field: conditions[field] for field in SHARED_FIELDS}
    record.update({"controller": controller, "repeat_no": repeat_no, "pair_id": pair_id(conditions)})
    return record


def validate_pairs(records):
    """A valid pairing: every record carries the FULL shared-field table and a pair_id that
    matches a fresh recomputation from its own conditions (a forged, stale or hand-written id is
    rejected); every pair has exactly two members with distinct controllers; members of one pair
    share every shared condition."""
    if not isinstance(records, list) or not records:
        raise ValueError("pair records must be a non-empty list")
    problems = []
    groups = {}
    for index, record in enumerate(records):
        where = f"records[{index}]"
        if not isinstance(record, dict):
            raise ValueError(f"{where}: must be an object")
        try:
            _validate_conditions(record)
        except ValueError as error:
            raise ValueError(f"{where}: {error}") from error
        controller = record.get("controller")
        if not isinstance(controller, str) or not controller.strip():
            raise ValueError(f"{where}.controller: must be a non-empty string")
        repeat = record.get("repeat_no")
        if not isinstance(repeat, int) or isinstance(repeat, bool) or repeat < 1:
            raise ValueError(f"{where}.repeat_no: must start at 1")
        claimed = record.get("pair_id")
        if not isinstance(claimed, str) or len(claimed) != 64:
            raise ValueError(f"{where}.pair_id: must be a 64-hex string")
        recomputed = pair_id(record)
        if claimed != recomputed:
            raise ValueError(f"{where}.pair_id: does not match a recomputation from the record's "
                             "own conditions; forged or stale ids are refused")
        groups.setdefault(recomputed, []).append(record)
    for pair, members in groups.items():
        if len(members) != 2:
            problems.append({"pair_id": pair, "problem": "a pair has exactly two members",
                             "members": len(members)})
            continue
        controllers = {m.get("controller") for m in members}
        if len(controllers) != 2:
            problems.append({"pair_id": pair, "problem": "the two members must use different controllers"})
        for field in SHARED_FIELDS:
            if len({json.dumps(m.get(field), ensure_ascii=False, sort_keys=True) for m in members}) != 1:
                problems.append({"pair_id": pair, "problem": f"shared field {field} differs inside one pair"})
    if problems:
        raise ValueError(f"invalid pairing: {json.dumps(problems, ensure_ascii=False)}")
    return {"pairs": len(groups), "records": len(records), "valid": True}
