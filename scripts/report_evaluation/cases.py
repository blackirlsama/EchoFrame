"""Case loading/validation, material classification, semantic eligibility, experiment fingerprint."""
import hashlib
from pathlib import Path

from . import timebase
from .util import CaseError, fingerprint

REPORT_SCOPES = {"FEEDBACK_ONLY", "VIDEO_GROUNDED", "VIDEO_ONLY"}
MATERIAL_CLASSES = {"synthetic_fixture", "time_adapted_real_content"}
SCENARIOS = {"success", "failure", "needs_clarification", "retry_success",
             "missing_report", "missing_trace", "interrupted", "out_of_bounds"}
REVIEW_STATUSES = {"pending", "approved", "rejected"}

REQUIRED_FIELDS = ["case_id", "case_version", "scenario_id", "prompt", "report_scope", "source_group",
                   "material_class", "material", "task_clock", "timezone", "expected", "review",
                   "roles", "interaction_script", "offline_simulation"]
# A product capture case is bound to real captured bytes, not to an offline scenario script.
PRODUCT_REQUIRED_FIELDS = [f for f in REQUIRED_FIELDS if f != "offline_simulation"] + ["product_capture"]
PRODUCT_EVIDENCE_LEVEL = "product_http_mockmvc"
PRODUCT_ADAPTER_VERSION = "product-adapter-v1"
PRODUCT_SOURCES = {"COMMENT", "DANMAKU"}
PRODUCT_REQUEST_FIELDS = ["prompt", "owner_token", "video_id", "sources", "grounding"]
MATERIAL_KINDS = {"synthetic_inline", "dataset_ref"}
IDENTITY_FIELDS = ("case_id", "case_version", "scenario_id", "prompt", "source_group")
CONTAINER_FIELDS = ("expected", "roles", "interaction_script")
PERMISSION_FIELDS = ("network", "ssh", "read_dataset")


def _json_kind(value):
    """Name the JSON type so a refusal says what arrived, not just that it was wrong."""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return type(value).__name__


def _dataset_keys(material):
    """Keys must be distinct non-empty strings before any of them is built into a filename."""
    keys = material.get("keys")
    if (not isinstance(keys, list) or not keys
            or any(not isinstance(k, str) or not k.strip() for k in keys)):
        raise CaseError(f"dataset_ref keys must be a non-empty list of non-empty strings: {keys!r}")
    if len(set(keys)) != len(keys):
        # Silently deduplicating would invent a different sample set than the case declared.
        raise CaseError(f"dataset_ref keys must not repeat: {keys!r}")
    return keys


def _material_for_consumption(case):
    material = case.get("material")
    if not isinstance(material, dict):
        raise CaseError(f"bad material: {material!r}")
    kind = material.get("kind")
    if not isinstance(kind, str) or kind not in MATERIAL_KINDS:
        raise CaseError(f"bad material.kind: {kind!r}")
    return material, kind


def load_cases(path):
    import json
    path = Path(path)
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        raise CaseError(f"cannot read cases file {path}: {e}") from e
    if not isinstance(doc, dict) or doc.get("kind") != "report-eval-cases":
        raise CaseError(f"{path}: expected kind=report-eval-cases")
    if not isinstance(doc.get("cases"), list) or not doc["cases"]:
        raise CaseError(f"{path}: cases must be a non-empty list")
    # Every consumer downstream does case.get(...) or `field in case`; guarantee objects here so a
    # non-object element is a locatable whole-file refusal instead of a traceback in the CLI.
    bad = [i for i, c in enumerate(doc["cases"]) if not isinstance(c, dict)]
    if bad:
        detail = ", ".join(f"cases[{i}] must be a JSON object, got {_json_kind(doc['cases'][i])}"
                           for i in bad)
        raise CaseError(f"{path}: {detail}")
    return doc


def validate_case(case, mode="offline"):
    if mode not in ("offline", "product"):
        return [f"unknown case mode: {mode!r}"]
    if not isinstance(case, dict):
        return [f"case must be a JSON object, got {_json_kind(case)}"]
    errors = []
    for field in (REQUIRED_FIELDS if mode == "offline" else PRODUCT_REQUIRED_FIELDS):
        if field not in case:
            errors.append(f"missing field: {field}")
    if errors:
        return errors
    for field in IDENTITY_FIELDS:
        value = case[field]
        if not isinstance(value, str) or not value.strip():
            errors.append(f"{field} must be a non-empty string: {value!r}")
    for field in CONTAINER_FIELDS:
        if not isinstance(case[field], dict):
            errors.append(f"bad {field}: {case[field]!r}")
    script = case["interaction_script"]
    # offline.py indexes replies[0] after a trial already exists; refuse here so a bad reply list
    # cannot turn into a mid-run crash on the needs_clarification path.
    if isinstance(script, dict) and "clarification_replies" in script:
        replies = script["clarification_replies"]
        if not isinstance(replies, list) or any(not isinstance(r, str) for r in replies):
            errors.append(
                f"interaction_script.clarification_replies must be a list of strings: {replies!r}")
    # Type first, enum second: an unhashable value must be a field problem, not a TypeError. The
    # message format is unchanged so already-controlled values (None, unknown strings) read the same.
    scope = case["report_scope"]
    if not isinstance(scope, str) or scope not in REPORT_SCOPES:
        errors.append(f"bad report_scope: {scope!r}")
    material_class = case["material_class"]
    if not isinstance(material_class, str) or material_class not in MATERIAL_CLASSES:
        errors.append(f"bad material_class: {material_class!r}")
    if mode == "offline":
        simulation = case["offline_simulation"]
        if not isinstance(simulation, dict):
            errors.append(f"bad offline_simulation: {simulation!r}")
        else:
            scenario = simulation.get("scenario")
            if not isinstance(scenario, str) or scenario not in SCENARIOS:
                errors.append(f"bad offline_simulation.scenario: {scenario!r}")
    else:
        errors.extend(validate_product_capture(case))
    review = case["review"]
    if not isinstance(review, dict):
        errors.append(f"bad review: {review!r}")
    else:
        status = review.get("status")
        if not isinstance(status, str) or status not in REVIEW_STATUSES:
            errors.append(f"bad review.status: {status!r}")
    clock = case["task_clock"]
    time_types_ok = isinstance(case["timezone"], str) and bool(case["timezone"].strip())
    if not time_types_ok:
        errors.append(f"timezone must be a non-empty string: {case['timezone']!r}")
    if not isinstance(clock, dict):
        errors.append(f"bad task_clock: {clock!r}")
        time_types_ok = False
    else:
        for field in ("timezone", "reference_time"):
            if field not in clock:
                errors.append(f"task_clock missing field: {field}")
                time_types_ok = False
            elif not isinstance(clock[field], str) or not clock[field].strip():
                errors.append(f"task_clock.{field} must be a non-empty string: {clock[field]!r}")
                time_types_ok = False
    if time_types_ok:
        try:
            timebase.get_timezone(case["timezone"])
            timebase.parse_dt(clock["reference_time"], timebase.get_timezone(clock["timezone"]))
        except (CaseError, KeyError) as e:
            errors.append(f"bad task_clock/timezone: {e}")
    if "task_window" in case:
        spec = case["task_window"]
        if not isinstance(spec, dict):
            errors.append(f"bad task_window: {spec!r}")
        elif time_types_ok:
            try:
                timebase.resolve_window(clock, spec)
            except (CaseError, KeyError) as e:
                errors.append(f"bad task_window: {e}")
    material = case["material"]
    if not isinstance(material, dict):
        errors.append(f"bad material: {material!r}")
        material = {}
    kind = material.get("kind")
    if mode == "product":
        # This slice connects no formal material; a product case may not claim a real dataset.
        if case["material_class"] != "synthetic_fixture":
            errors.append("product capture requires material_class=synthetic_fixture")
        if kind != "synthetic_inline":
            errors.append(f"product capture requires synthetic_inline material, got: {kind!r}")
    if kind == "synthetic_inline":
        if "content" not in material or "content_sha256" not in material:
            errors.append("synthetic_inline material requires content and content_sha256")
        elif not isinstance(material["content"], str):
            errors.append(f"synthetic_inline content must be a string: {material['content']!r}")
        elif not isinstance(material["content_sha256"], str):
            errors.append(
                f"synthetic_inline content_sha256 must be a string: {material['content_sha256']!r}")
        elif hashlib.sha256(material["content"].encode("utf-8")).hexdigest() != material["content_sha256"]:
            errors.append("synthetic_inline content_sha256 does not match content")
    elif kind == "dataset_ref":
        for field in ("dataset_root", "keys", "manifest_sha256", "effective_timeline"):
            if field not in material:
                errors.append(f"dataset_ref material missing: {field}")
        if "dataset_root" in material:
            value = material["dataset_root"]
            if not isinstance(value, str) or not value.strip():
                errors.append(f"dataset_ref dataset_root must be a non-empty string: {value!r}")
        if "keys" in material:
            try:
                _dataset_keys(material)
            except CaseError as e:
                errors.append(str(e))
        if "manifest_sha256" in material and not isinstance(material["manifest_sha256"], str):
            errors.append(
                f"dataset_ref manifest_sha256 must be a string: {material['manifest_sha256']!r}")
        # Absence declares "no video"; a present declaration must be a path string, not a falsy
        # non-string that `if video_path:` would silently read as absence.
        if "video_path" in material and not isinstance(material["video_path"], str):
            errors.append(f"dataset_ref video_path must be a string: {material['video_path']!r}")
        if not errors and mode == "offline":
            root = Path(material["dataset_root"])
            manifest = root / "视频清单.csv"
            if not manifest.is_file():
                errors.append(f"dataset_ref manifest not readable: {manifest}")
            else:
                from .util import sha256_file
                if sha256_file(manifest) != material["manifest_sha256"]:
                    errors.append("dataset_ref manifest_sha256 does not match actual manifest")
                for key in material["keys"]:
                    for sub in ("评论", "弹幕"):
                        if not (root / sub / f"{key}_{sub}.csv").is_file():
                            errors.append(f"dataset_ref file missing: {key} {sub}")
    else:
        errors.append(f"bad material.kind: {kind!r}")
    perms = case.get("tool_permissions", {})
    if not isinstance(perms, dict):
        errors.append(f"bad tool_permissions: {perms!r}")
        perms = {}
    for field in PERMISSION_FIELDS:
        # A falsy [] or 0 is still a wrong declaration; Python truthiness would accept it silently.
        if field in perms and not isinstance(perms[field], bool):
            errors.append(f"tool_permissions.{field} must be a JSON boolean: {perms[field]!r}")
    if "network_access" in case and not isinstance(case["network_access"], bool):
        errors.append(f"network_access must be a JSON boolean: {case['network_access']!r}")
    if mode == "offline":
        if perms.get("network") or perms.get("ssh") or case.get("network_access"):
            errors.append("offline mode forbids network/ssh access")
    else:
        if perms.get("network") or perms.get("ssh") or case.get("network_access"):
            errors.append("product capture mode forbids network/ssh access")
    return errors


def validate_product_capture(case):
    """The case only declares which capture it expects; the bundle bytes are checked separately."""
    capture = case["product_capture"]
    if not isinstance(capture, dict):
        return ["product_capture must be an object"]
    errors = []
    for field in ("bundle_id", "evidence_level", "adapter_version", "request"):
        if field not in capture:
            errors.append(f"product_capture missing: {field}")
    if errors:
        return errors
    if not isinstance(capture["bundle_id"], str) or not capture["bundle_id"].strip():
        errors.append("product_capture.bundle_id must be a non-empty string")
    if capture["evidence_level"] != PRODUCT_EVIDENCE_LEVEL:
        errors.append(f"product_capture.evidence_level must be {PRODUCT_EVIDENCE_LEVEL}")
    if capture["adapter_version"] != PRODUCT_ADAPTER_VERSION:
        errors.append(f"product_capture.adapter_version must be {PRODUCT_ADAPTER_VERSION}")
    request = capture["request"]
    if not isinstance(request, dict):
        return errors + ["product_capture.request must be an object"]
    for field in PRODUCT_REQUEST_FIELDS:
        if field not in request:
            errors.append(f"product_capture.request missing: {field}")
    if errors:
        return errors
    if not isinstance(request["prompt"], str) or not request["prompt"].strip():
        errors.append("product_capture.request.prompt must be a non-empty string")
    if request["prompt"] != case["prompt"]:
        errors.append("product_capture.request.prompt must equal the case prompt")
    if not isinstance(request["owner_token"], str) or not request["owner_token"].strip():
        errors.append("product_capture.request.owner_token must be a non-empty string")
    if not isinstance(request["video_id"], str) or not request["video_id"].isdigit():
        errors.append("product_capture.request.video_id must be a numeric string")
    sources = request["sources"]
    if (not isinstance(sources, list) or not sources
            or any(not isinstance(s, str) for s in sources)
            or any(s not in PRODUCT_SOURCES for s in sources) or len(set(sources)) != len(sources)):
        errors.append("product_capture.request.sources must be a non-empty unique subset of COMMENT/DANMAKU")
    grounding = request["grounding"]
    if not isinstance(grounding, str) or grounding not in {"FEEDBACK_ONLY", "VIDEO_GROUNDED"}:
        errors.append(f"bad product_capture.request.grounding: {grounding!r}")
    if grounding != case["report_scope"]:
        errors.append("product_capture.request.grounding must equal the case report_scope")
    return errors


def semantic_eligibility(case):
    if case["material_class"] == "synthetic_fixture":
        return {"eligible": False, "reason": "synthetic_fixture", "scope": "engineering_only"}
    status = case["review"].get("status")
    if status == "approved":
        return {"eligible": True, "reason": "human_review_approved", "scope": "library_facts_only"}
    return {"eligible": False, "reason": "human_review_pending", "scope": "not_formally_scored"}


def resolve_material_inventory(case):
    """Hash the actual selected material files so the fingerprint binds real versions, not just the manifest."""
    material, kind = _material_for_consumption(case)
    if kind != "dataset_ref":
        return None
    from .util import sha256_file
    root_value = material.get("dataset_root")
    if not isinstance(root_value, str) or not root_value.strip():
        raise CaseError(f"dataset_ref dataset_root must be a non-empty string: {root_value!r}")
    keys = _dataset_keys(material)
    root = Path(root_value)
    videos = {}
    for key in keys:
        comment = root / "评论" / f"{key}_评论.csv"
        danmaku = root / "弹幕" / f"{key}_弹幕.csv"
        for p in (comment, danmaku):
            if not p.is_file():
                raise CaseError(f"dataset_ref material file missing for key {key}: {p}")
        entry = {"comment_sha256": sha256_file(comment), "danmaku_sha256": sha256_file(danmaku)}
        video_path = material.get("video_path")
        if video_path is not None and not isinstance(video_path, str):
            raise CaseError(f"dataset_ref video_path must be a string: {video_path!r}")
        if video_path:
            vp = Path(video_path)
            if not vp.is_file():
                raise CaseError(f"dataset_ref video file missing: {vp}")
            entry["video_sha256"] = sha256_file(vp)
        else:
            entry["video_sha256"] = None
        videos[key] = entry
    return {"dataset": {"videos": videos}}


def prepare_case(case, mode="offline", capture=None):
    """Single resolution chain shared by validate and run: validate, bind real material, fingerprint."""
    errors = validate_case(case, mode)
    if errors:
        return errors, None, None
    binding = resolve_material_inventory(case)
    return [], case_fingerprint(case, inventory=binding, capture=capture), binding


def case_fingerprint(case, inventory=None, capture=None):
    material, kind = _material_for_consumption(case)
    if kind == "dataset_ref":
        for field in ("dataset_root", "keys", "manifest_sha256", "effective_timeline"):
            if field not in material:
                raise CaseError(f"dataset_ref material missing: {field}")
        if not isinstance(material["dataset_root"], str) or not material["dataset_root"].strip():
            raise CaseError(
                f"dataset_ref dataset_root must be a non-empty string: {material['dataset_root']!r}")
        _dataset_keys(material)
        mat = {k: material[k] for k in ("kind", "dataset_root", "keys", "manifest_sha256", "effective_timeline")}
        if inventory is not None:
            videos = inventory.get("dataset", {}).get("videos", {})
            hashes = {}
            for key in material["keys"]:
                v = videos.get(key)
                if not v or "comment_sha256" not in v or "danmaku_sha256" not in v:
                    raise CaseError(f"material binding missing hashes for selected key: {key}")
                entry = {"comment_sha256": v["comment_sha256"], "danmaku_sha256": v["danmaku_sha256"]}
                if "video_sha256" in v:
                    entry["video_sha256"] = v["video_sha256"]
                hashes[key] = entry
            mat["file_hashes"] = hashes
    else:
        if "content_sha256" not in material:
            raise CaseError("synthetic_inline material requires content and content_sha256")
        if not isinstance(material["content_sha256"], str):
            raise CaseError(
                f"synthetic_inline content_sha256 must be a string: {material['content_sha256']!r}")
        mat = {"kind": "synthetic_inline", "content_sha256": material["content_sha256"]}
    payload = {
        "case_id": case["case_id"], "case_version": case["case_version"], "prompt": case["prompt"],
        "report_scope": case["report_scope"], "source_group": case["source_group"],
        "material": mat, "task_clock": case["task_clock"], "timezone": case["timezone"],
        "task_window": case.get("task_window"),
        "interaction_script": case["interaction_script"], "roles": case["roles"],
        "tool_permissions": case.get("tool_permissions", {}),
        "cache_replay_policy": case.get("cache_replay_policy", {}),
        "scoring_rules_version": case.get("scoring_rules_version", "draft-v0"),
    }
    if capture is not None:
        # Only product captures add this key, so offline fingerprints stay byte-identical.
        payload["product_capture"] = capture
    return fingerprint(payload)


def experiment_id(fp):
    return "exp-" + fp[:16]