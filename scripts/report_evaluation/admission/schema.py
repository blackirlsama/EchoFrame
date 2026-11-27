"""Limited, closed preflight shared by admission-check and admission-prepare.

validate_config returns a list of issues with field paths and expected shapes — never the
offending value — so both CLI entries refuse identically, without stack traces and without
writing anything. The CLI is the FORMAL three-material five-role admission; empty material
lists or a missing harness role are refusals, not gaps. Subset checks for unit diagnostics call
the internal validators directly and never produce a formal READY. The closed schema also keeps
credential-shaped canaries out: any unknown key (e.g. pricing.api_key) is refused by field path.
"""
import math

from .. import timebase
from . import toolfreeze

CONFIG_KIND = "report-trial-admission"
SCHEMA_VERSION = 1
ROLE_KEYS = ("preparation", "harness", "analysis", "vision", "transcription")
TRANSPORT_ENDPOINTS = {
    "chat_completions": "/chat/completions",
    "vlm_dedicated": "/v1/coding_plan/vlm",
    "chat_vision": "/chat/completions",
    "openai_chat_vision": "/v1/chat/completions",
    "audio_transcriptions": "/audio/transcriptions",
}
MATERIAL_KEYS = ("hxc-doufu", "my-dragon", "ysjf-canon")
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}
CREDENTIAL_KEY_MARKERS = ("api_key", "apikey", "api-key", "secret", "token", "password", "credential")


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def _is_str(value, limit=None):
    ok = isinstance(value, str) and bool(value.strip())
    return ok and (limit is None or len(value) <= limit)


def _is_hash(value):
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


class Issue:
    __slots__ = ("path", "expectation")

    def __init__(self, path, expectation):
        self.path = path
        self.expectation = expectation

    def as_dict(self):
        return {"field": self.path, "expectation": self.expectation}


def _collect(issues, path, expectation):
    issues.append(Issue(path, expectation))


def _closed_object(issues, path, value, allowed):
    if not isinstance(value, dict):
        _collect(issues, path, "must be a JSON object")
        return False
    for key in value:
        if key not in allowed:
            _collect(issues, f"{path}.{key}", "unknown field; the admission schema is closed")
    return True


def validate_budget(issues, path, budget):
    if not _closed_object(issues, path, budget, ("total_cny", "pause_cny", "covers")):
        return
    if not _is_int(budget.get("total_cny")) or budget.get("total_cny") != 50:
        _collect(issues, f"{path}.total_cny", "must be the approved integer 50")
    if not _is_int(budget.get("pause_cny")) or budget.get("pause_cny") != 20:
        _collect(issues, f"{path}.pause_cny", "must be the approved integer 20")
    if not _is_str(budget.get("covers")):
        _collect(issues, f"{path}.covers", "must be a non-empty string")


def validate_task_clock(issues, path, clock):
    if not _closed_object(issues, path, clock, ("timezone", "reference_time")):
        return
    timezone = clock.get("timezone")
    if not _is_str(timezone):
        _collect(issues, f"{path}.timezone", "must be a non-empty string")
        return
    try:
        tz = timebase.get_timezone(timezone)
    except Exception:
        _collect(issues, f"{path}.timezone", "must be a timezone the evaluation clock can resolve")
        return
    reference = clock.get("reference_time")
    if not _is_str(reference):
        _collect(issues, f"{path}.reference_time", "must be a non-empty string")
        return
    try:
        timebase.parse_dt(reference, tz)
    except Exception:
        _collect(issues, f"{path}.reference_time",
                 "must be a datetime like YYYY-MM-DD[ HH:MM:SS] in the declared timezone")


def validate_tool_freeze(issues, path, freeze):
    if not _closed_object(issues, path, freeze,
                          ("key_spec_version", "tool_config_version", "sampling_strategy")):
        return
    if freeze.get("key_spec_version") != toolfreeze.KEY_SPEC_VERSION:
        _collect(issues, f"{path}.key_spec_version",
                 f"must be {toolfreeze.KEY_SPEC_VERSION}")
    if not _is_str(freeze.get("tool_config_version"), 100):
        _collect(issues, f"{path}.tool_config_version", "must be a non-empty string of at most 100 characters")
    if not _is_str(freeze.get("sampling_strategy"), 100):
        _collect(issues, f"{path}.sampling_strategy", "must be a non-empty string of at most 100 characters")


def _validate_pricing(issues, path, pricing):
    if not _closed_object(issues, path, pricing, ("currency", "input_per_million", "output_per_million", "source")):
        return
    if not _is_str(pricing.get("currency")):
        _collect(issues, f"{path}.currency", "must be a non-empty string")
    for side in ("input_per_million", "output_per_million"):
        value = pricing.get(side)
        if value is None:
            continue
        if isinstance(value, bool) or not isinstance(value, (int, float)) \
                or not math.isfinite(value) or value < 0:
            _collect(issues, f"{path}.{side}", "must be null (unknown) or a non-negative finite number")
    if pricing.get("source") is not None and not _is_str(pricing.get("source")):
        _collect(issues, f"{path}.source", "must be null (unverified) or a non-empty string")


def validate_role(issues, path, role):
    allowed = ("role", "provider", "transport", "endpoint", "base_url", "model_request_name",
               "version_state", "max_output_tokens", "pricing", "note")
    if not _closed_object(issues, path, role, allowed):
        return
    if role.get("role") not in ROLE_KEYS:
        _collect(issues, f"{path}.role", f"must be one of {', '.join(ROLE_KEYS)}")
    if not _is_str(role.get("provider"), 60):
        _collect(issues, f"{path}.provider", "must be a non-empty string of at most 60 characters")
    transport = role.get("transport")
    if transport not in TRANSPORT_ENDPOINTS:
        _collect(issues, f"{path}.transport", f"must be one of {', '.join(TRANSPORT_ENDPOINTS)}")
    elif role.get("endpoint") != TRANSPORT_ENDPOINTS[transport]:
        _collect(issues, f"{path}.endpoint",
                 f"must be {TRANSPORT_ENDPOINTS[transport]} for transport {transport}")
    if transport == "openai_chat_vision" and role.get("provider") != "minimax":
        # The generic image-input transport is a protocol, not a provider: the provider identity
        # stays minimax and must never be disguised as the generic "compatible" adapter.
        _collect(issues, f"{path}.provider",
                 "must be minimax when the generic image-input transport is declared")
    base_url = role.get("base_url")
    if not _is_str(base_url):
        _collect(issues, f"{path}.base_url", "must be a non-empty string")
    else:
        from urllib.parse import urlparse
        parsed = urlparse(base_url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            _collect(issues, f"{path}.base_url", "must be an http(s) URL with a host")
    if not _is_str(role.get("model_request_name"), 120):
        _collect(issues, f"{path}.model_request_name", "must be a non-empty string of at most 120 characters")
    if role.get("version_state") not in ("REQUESTED_ONLY", "PROVIDER_REPORTED"):
        _collect(issues, f"{path}.version_state", "must be REQUESTED_ONLY or PROVIDER_REPORTED")
    tokens = role.get("max_output_tokens")
    if not _is_int(tokens) or not (0 <= tokens <= 32768):
        _collect(issues, f"{path}.max_output_tokens", "must be an integer between 0 and 32768")
    pricing = role.get("pricing")
    if pricing is not None:
        _validate_pricing(issues, f"{path}.pricing", pricing)


def validate_roles(issues, path, roles):
    if not isinstance(roles, list) or not roles:
        _collect(issues, path, "must be a non-empty list of the five formal roles")
        return
    if len(roles) > 32:
        _collect(issues, path, "must hold at most 32 roles")
        return
    declared = []
    for index, role in enumerate(roles):
        if not isinstance(role, dict):
            _collect(issues, f"{path}[{index}]", "must be a JSON object")
            continue
        validate_role(issues, f"{path}[{index}]", role)
        if isinstance(role.get("role"), str):
            declared.append(role["role"])
    if len(declared) != len(set(declared)):
        _collect(issues, path, "role values must not repeat")
    missing = [r for r in ROLE_KEYS if r not in declared]
    if missing:
        _collect(issues, path, f"the formal admission requires all five roles; missing: {', '.join(missing)}")


def validate_canon_window(issues, path, window):
    if not _closed_object(issues, path, window,
                          ("comments_in_window", "original_comments_in_window", "danmaku_in_window",
                           "comment_by_date", "danmaku_by_date", "missing_time")):
        return
    for field in ("comments_in_window", "original_comments_in_window", "danmaku_in_window", "missing_time"):
        value = window.get(field)
        if not _is_int(value) or value < 0:
            _collect(issues, f"{path}.{field}", "must be a non-negative integer")
    for field in ("comment_by_date", "danmaku_by_date"):
        by_date = window.get(field)
        if not isinstance(by_date, dict):
            _collect(issues, f"{path}.{field}", "must be an object of date -> non-negative integer")
            continue
        for day, count in by_date.items():
            if not _is_int(count) or count < 0:
                _collect(issues, f"{path}.{field}.{day}", "must be a non-negative integer")


def validate_material(issues, path, material):
    allowed = ("key", "label", "dataset_root", "video_path", "binding", "service_probe",
               "task_window", "canon_window")
    if not _closed_object(issues, path, material, allowed):
        return
    if material.get("key") not in MATERIAL_KEYS:
        _collect(issues, f"{path}.key", f"must be one of {', '.join(MATERIAL_KEYS)}")
    label = material.get("label")
    if label is not None and not _is_str(label):
        _collect(issues, f"{path}.label", "must be a non-empty string when present")
    for field in ("dataset_root", "video_path"):
        if not _is_str(material.get(field)):
            _collect(issues, f"{path}.{field}", "must be a non-empty string")
    binding = material.get("binding")
    if not _closed_object(issues, f"{path}.binding", binding,
                          ("video_sha256", "comment_sha256", "danmaku_sha256")):
        return
    for field in ("video_sha256", "comment_sha256", "danmaku_sha256"):
        if not _is_hash(binding.get(field)):
            _collect(issues, f"{path}.binding.{field}",
                     "must be a 64-character lowercase hexadecimal sha256 digest")
    probe = material.get("service_probe")
    if not _closed_object(issues, f"{path}.service_probe", probe, ("host", "port")):
        return
    host = probe.get("host")
    if not isinstance(host, str) or host not in LOOPBACK_HOSTS:
        _collect(issues, f"{path}.service_probe.host", "must be a loopback host (127.0.0.1, localhost or ::1)")
    port = probe.get("port")
    if not _is_int(port) or not (1 <= port <= 65535):
        _collect(issues, f"{path}.service_probe.port", "must be an integer between 1 and 65535")
    window = material.get("task_window")
    if "canon_window" in material or "task_window" in material:
        if not isinstance(window, dict):
            _collect(issues, f"{path}.task_window",
                     "must be an object with kind/start/end when a canon_window is declared")
        else:
            kind = window.get("kind")
            if kind == "absolute":
                for field in ("start", "end"):
                    if not _is_str(window.get(field)):
                        _collect(issues, f"{path}.task_window.{field}", "must be a datetime string")
            else:
                _collect(issues, f"{path}.task_window.kind", "must be 'absolute'")
    if "canon_window" in material:
        validate_canon_window(issues, f"{path}.canon_window", material.get("canon_window"))
    if material.get("key") == "ysjf-canon" and "canon_window" not in material:
        _collect(issues, f"{path}.canon_window", "the formal canon material must pin the 4A window verdict")


def validate_materials(issues, path, materials):
    if not isinstance(materials, list):
        _collect(issues, path, "must be a list of exactly the three formal calibration materials")
        return
    if len(materials) != len(MATERIAL_KEYS):
        _collect(issues, path,
                 f"the formal admission requires exactly {len(MATERIAL_KEYS)} calibration materials")
    keys = []
    for index, material in enumerate(materials):
        if not isinstance(material, dict):
            _collect(issues, f"{path}[{index}]", "must be a JSON object")
            continue
        validate_material(issues, f"{path}[{index}]", material)
        if isinstance(material.get("key"), str):
            keys.append(material["key"])
    if len(keys) != len(set(keys)):
        _collect(issues, path, "material keys must not repeat")
    for expected in MATERIAL_KEYS:
        if expected not in keys:
            _collect(issues, path, f"the formal admission requires material {expected}")


def validate_config(doc):
    """Structural preflight over every consumed field; returns a list of Issue objects."""
    issues = []
    if not isinstance(doc, dict):
        _collect(issues, "", "config must be a JSON object")
        return issues
    if doc.get("kind") != CONFIG_KIND:
        _collect(issues, "kind", f"must be {CONFIG_KIND}")
    if doc.get("schema_version") != SCHEMA_VERSION or not _is_int(doc.get("schema_version")):
        _collect(issues, "schema_version", f"must be the integer {SCHEMA_VERSION}")
    if doc.get("draft") is not True:
        _collect(issues, "draft", "must be true: this stage only accepts draft configs")
    if doc.get("credentials_embedded") is not False:
        _collect(issues, "credentials_embedded", "must be false: admission configs never embed credentials")
    for key in doc:
        if key not in ("kind", "schema_version", "draft", "credentials_embedded", "note",
                       "budget", "task_clock", "tool_freeze", "roles", "materials"):
            _collect(issues, key, "unknown field; the admission schema is closed")
    if isinstance(doc.get("budget"), dict):
        validate_budget(issues, "budget", doc["budget"])
    else:
        _collect(issues, "budget", "must be a JSON object")
    if isinstance(doc.get("task_clock"), dict):
        validate_task_clock(issues, "task_clock", doc["task_clock"])
    else:
        _collect(issues, "task_clock", "must be a JSON object")
    if isinstance(doc.get("tool_freeze"), dict):
        validate_tool_freeze(issues, "tool_freeze", doc["tool_freeze"])
    else:
        _collect(issues, "tool_freeze", "must be a JSON object")
    validate_roles(issues, "roles", doc.get("roles"))
    validate_materials(issues, "materials", doc.get("materials"))
    return issues


def redact(value):
    """Deep redaction: any dict key carrying a credential-shaped name has its value replaced by
    [REDACTED] at any depth, including inside lists. Diagnostics never echo the original."""
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            name = str(key).lower().replace("-", "_")
            if any(marker in name for marker in CREDENTIAL_KEY_MARKERS):
                out[key] = "[REDACTED]"
            else:
                out[key] = redact(item)
        return out
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value
