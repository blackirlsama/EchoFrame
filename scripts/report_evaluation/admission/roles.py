"""Provider protocol and role configuration checks on synthetic payloads (no network, no credentials).

The DeepSeek client sends the deepseek.com `thinking`/`reasoning_effort` fields whenever the model
name starts with "deepseek", so a siliconflow-hosted deepseek model receives them today. That
divergence is a documented live-compat risk (PROTOCOL_FIELD_UNVERIFIED), not a pass: no account
permission or provider acceptance has been verified in this slice.
"""
from ..util import canonical_json
from . import schema

USAGE_REPORTED = "REPORTED"
USAGE_UNKNOWN = "UNKNOWN"

CHAT_TRANSPORT = "chat_completions"
VISION_DEDICATED = "vlm_dedicated"
VISION_GENERIC = "chat_vision"
VISION_OPENAI_GENERIC = "openai_chat_vision"
AUDIO_TRANSPORT = "audio_transcriptions"

TRANSPORT_ENDPOINTS = {
    CHAT_TRANSPORT: "/chat/completions",
    VISION_DEDICATED: "/v1/coding_plan/vlm",
    AUDIO_TRANSPORT: "/audio/transcriptions",
    VISION_GENERIC: "/chat/completions",
    VISION_OPENAI_GENERIC: "/v1/chat/completions",
}


def check_vision_payload(transport, body):
    """Two vision contracts must never be mixed: the MiniMax dedicated body carries prompt +
    image_url and no model field; the generic body carries model + messages with image parts."""
    findings = []
    if transport == VISION_DEDICATED:
        if "model" in body or "messages" in body:
            findings.append({"code": "CONTRACT_MIXING",
                             "detail": "dedicated /v1/coding_plan/vlm payload carries generic chat fields"})
        if "prompt" not in body or "image_url" not in body:
            findings.append({"code": "DEDICATED_PAYLOAD_INCOMPLETE",
                             "detail": "dedicated payload needs prompt and image_url"})
        return findings
    if transport in (VISION_GENERIC, VISION_OPENAI_GENERIC):
        if "model" not in body:
            findings.append({"code": "MISSING_MODEL_FIELD", "detail": "generic vision payload needs model"})
        messages = body.get("messages")
        has_image = isinstance(messages, list) and any(
            isinstance(part, dict) and part.get("type") == "image_url"
            for message in messages
            if isinstance(message, dict)
            for part in (message.get("content") if isinstance(message.get("content"), list) else []))
        if not has_image:
            findings.append({"code": "MISSING_IMAGE_INPUT",
                             "detail": "vision payload must carry image_url parts"})
        return findings
    return [{"code": "UNKNOWN_TRANSPORT", "detail": f"unsupported vision transport: {transport!r}"}]


def check_role_binding(role):
    """Structural and provider-specific checks for one declared role."""
    findings = []
    transport = role.get("transport")
    endpoint = role.get("endpoint")
    provider = role.get("provider")
    expected_endpoint = TRANSPORT_ENDPOINTS.get(transport)
    if expected_endpoint is None:
        findings.append({"code": "UNKNOWN_TRANSPORT", "detail": f"unsupported transport: {transport!r}"})
    elif endpoint != expected_endpoint:
        findings.append({"code": "CONTRACT_MIXING",
                         "detail": f"transport {transport} must use {expected_endpoint}, got {endpoint!r}"})
    if transport in (CHAT_TRANSPORT, VISION_GENERIC) and provider == "siliconflow":
        # Stage 4B-2A: the client now selects the thinking contract by host (siliconflow gets
        # enable_thinking, never deepseek.com's thinking object) and this slice sends
        # enable_thinking=false. Whether the account accepts the field at all, and whether
        # enable_thinking=true works with native tools, still needs a real response.
        findings.append({"code": "PROTOCOL_FIELD_UNVERIFIED",
                         "detail": "siliconflow thinking contract unverified live: this slice sends "
                                   "enable_thinking=false on every dispatch; a real response must confirm "
                                   "acceptance before any enable_thinking=true trial"})
    if transport == VISION_OPENAI_GENERIC:
        # Stage 4B-2A: the generic image-input transport must name the registered model; any other
        # name has no reproducible token bound and therefore no payable vision exit.
        from . import contracts
        try:
            contracts.vision_contract_for(role.get("model_request_name"), transport)
        except contracts.ContractError as error:
            findings.append({"code": _vision_bound_code(role), "detail": str(error)})
    if transport == VISION_DEDICATED:
        # The dedicated coding-plan contract publishes no per-image/per-call billing unit and no
        # output cap, so it can never be a paid admission exit in this stage. Declaring a price for it
        # is the unsafe claim and refuses the whole config; declaring it unpriced is merely recorded,
        # because payability is already false and the fee ledger refuses the exit at dispatch time.
        detail = ("dedicated /v1/coding_plan/vlm publishes no verified billing unit or output cap; it "
                  "stays a refused exit and is never mixed with the generic image contract")
        if _claims_payable(role):
            findings.append({"code": "VISION_BOUND_UNAVAILABLE",
                             "detail": f"{detail}; the role nevertheless declares a price"})
        else:
            findings.append({"code": "VISION_EXIT_UNPRICED",
                             "detail": f"{detail}; it is not payable and no live dispatch is admitted"})
    return findings


def _claims_payable(role):
    """A role claims a payable exit when it names both sides of the price. Null prices are a refusal."""
    pricing = role.get("pricing")
    if not isinstance(pricing, dict):
        return False
    return pricing.get("input_per_million") is not None or pricing.get("output_per_million") is not None


def _vision_bound_code(role):
    return "VISION_BOUND_UNAVAILABLE" if _claims_payable(role) else "VISION_EXIT_UNPRICED"


def chat_contract_findings(role, contract_declared):
    """Declared native-tool contract capabilities must cover what the controller needs."""
    findings = []
    if not contract_declared.get("native_tools"):
        findings.append({"code": "NATIVE_TOOLS_UNSUPPORTED", "detail": "controller needs native tool pairing"})
    if not contract_declared.get("required_tool_supported"):
        findings.append({"code": "REQUIRED_TOOL_UNSUPPORTED", "detail": "controller may require a specific tool"})
    if not contract_declared.get("streaming"):
        findings.append({"code": "STREAMING_UNSUPPORTED", "detail": "controller may stream"})
    if not contract_declared.get("multi_turn_tool_pairing"):
        findings.append({"code": "MULTI_TURN_UNSUPPORTED", "detail": "controller needs multi-turn tool messages"})
    if not contract_declared.get("usage_required"):
        findings.append({"code": "USAGE_NOT_REQUIRED", "detail": "usage absence must be surfaced, not zeroed"})
    return findings


def usage_state(response):
    """Missing, partial or malformed usage is UNKNOWN, never zero."""
    usage = response.get("usage") if isinstance(response, dict) else None
    if not isinstance(usage, dict):
        return USAGE_UNKNOWN
    prompt = usage.get("prompt_tokens")
    completion = usage.get("completion_tokens")
    for value in (prompt, completion):
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            return USAGE_UNKNOWN
    return USAGE_REPORTED


def role_payability(role):
    """Pricing and version-state gates: null pricing can never be marked payable, and a
    placeholder request name stays REQUESTED_ONLY until the provider reports a version."""
    pricing = role.get("pricing") or {}
    known = pricing.get("input_per_million") is not None and pricing.get("output_per_million") is not None
    version_state = role.get("version_state")
    if version_state not in ("REQUESTED_ONLY", "PROVIDER_REPORTED"):
        version_state = "REQUESTED_ONLY"
    return {"payable": bool(known) and version_state == "PROVIDER_REPORTED",
            "reason": None if known else "NEEDS_PRICING",
            "version_state": version_state}


def role_snapshot(roles):
    """Credential-free snapshot of the declared roles: deep redaction replaces the value of any
    credential-shaped key at any depth (nested dicts and lists included) with [REDACTED]."""
    sanitized = []
    for role in roles:
        entry = schema.redact(dict(role))
        entry["payability"] = role_payability(role)
        entry["findings"] = check_role_binding(role)
        sanitized.append(entry)
    return {"credentials_embedded": False, "roles": sanitized}


BLOCKING_FINDING_CODES = {"CONTRACT_MIXING", "DEDICATED_PAYLOAD_INCOMPLETE", "MISSING_MODEL_FIELD",
                          "VISION_BOUND_UNAVAILABLE",
                          "MISSING_IMAGE_INPUT", "UNKNOWN_TRANSPORT", "NATIVE_TOOLS_UNSUPPORTED",
                          "REQUIRED_TOOL_UNSUPPORTED", "STREAMING_UNSUPPORTED", "MULTI_TURN_UNSUPPORTED",
                          "USAGE_NOT_REQUIRED"}


def blocking_findings(findings):
    """Which findings block an OFFLINE_ADMISSION_READY verdict; PROTOCOL_FIELD_UNVERIFIED is a
    recorded live-compat risk, not an offline blocker."""
    return [f for f in findings if f.get("code") in BLOCKING_FINDING_CODES]


def canonical_payload_of(body):
    return canonical_json(body)
