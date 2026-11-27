"""Public provider-contract registry for the 4B-2A preflight (no credentials, no network).

Every entry carries the official page it was read from and the read date. Request names are the
names the provider documents; nothing here is a locked model snapshot — a name that resolves to a
provider-side build is marked `alias_of_upstream_build` and stays REQUESTED_ONLY until a real
response reports the served version. Prices are public list prices of the highest applicable tier
(no discount, no cache-hit assumption, no package credit); they are reservation bases, not bills.

Two controller candidates are registered for the paired comparison, plus the fixed roles. The
vision role registers the MiniMax official OpenAI-compatible image input with MiniMax-M3, thinking
disabled and image detail=low. Grounded-budget verdict (v3, 2026-11-19): the two authorized real
metering requests (one formal frame with short text = prompt_tokens 540; the identical text
without the image = 202) split the frame at 338 image tokens — inside the provider's documented
detail=low estimate band ("最高约 600") and proving image tokens flow into usage.prompt_tokens.
`thinking: disabled` measured reasoning_tokens=0, so the output side is reliably capped by
max_completion_tokens. The vision reservation is therefore a GROUNDED ESTIMATE:
    input bound  = measured request floor 202 + declared prompt envelope (1000 chars >= tokens)
                 + images x 600 (documented estimate)
    output bound = max_completion_tokens
Transport bytes (base64 JSON body) still never contribute to the token math; frame manifests are
transport evidence only. Settlement stays usage-based (real response), and arming real runs is a
separate release. MiniMax-M3.1-Flash(-Preview) is deliberately absent: the provider forces
thinking on it (thinking.disabled returns HTTP 400). The dedicated `/v1/coding_plan/vlm` contract
is a different transport and is never payable here.
"""
from .budget import _ceil_div

CONTRACT_VERSION = "provider-contracts-4b2a-v3-grounded-budget"
READ_AT = "2026-11-18"
# The 2026-11-19 price-refresh round re-read every price page; per-entry `recheck_read_at` pins it.
PRICE_RECHECK_AT = "2026-11-19"
# The grounded-budget round executed the two authorized real metering requests on 2026-11-19.
METERING_AT = "2026-11-19"

PRIMARY_SOURCES = {
    "siliconflow_chat": ("https://docs.siliconflow.cn/cn/api-reference/chat-completions/chat-completions",
                         "docs/api/chat-completions-post", READ_AT),
    "siliconflow_pricing": ("https://www.siliconflow.cn/pricing", "https://www.siliconflow.cn/pricing", READ_AT),
    "siliconflow_models": ("https://www.siliconflow.cn/models", "https://www.siliconflow.cn/models", READ_AT),
    "minimax_chat_schema": ("https://platform.minimaxi.com/docs/api-reference/text-openai-api",
                            "https://platform.minimax.cn/docs/api-reference/text-chat-openai", READ_AT),
    "minimax_pricing": ("https://platform.minimax.cn/docs/pricing/overview",
                        "https://platform.minimax.cn/docs/pricing/overview", READ_AT),
    # 2026-11-19 re-check snapshots (verbatim excerpts live in the price-refresh evidence root):
    "minimax_pricing_recheck": ("https://platform.minimax.cn/docs/pricing/overview",
                                "定价概览 - MiniMax 开放平台文档中心", PRICE_RECHECK_AT),
    "minimax_chat_recheck": ("https://platform.minimax.cn/docs/api-reference/text-chat-openai",
                             "Chat Completions API - MiniMax 开放平台文档中心", PRICE_RECHECK_AT),
    "siliconflow_pricing_recheck": ("https://www.siliconflow.cn/pricing",
                                    "大模型 API 价格方案 - 硅基流动 SiliconFlow", PRICE_RECHECK_AT),
}

CONTROLLERS = (
    {
        "controller_id": "controller-a",
        "model_request_name": "deepseek-ai/DeepSeek-V4.1-Flash",
        "provider": "siliconflow",
        "transport": "chat_completions",
        "base_url": "https://api.siliconflow.cn/v1",
        "endpoint": "/chat/completions",
        "protocol_family": "openai_chat_completions",
        "native_tools": True,
        "native_tools_evidence": "model card '🛠️ 工具调用' + chat doc tools/tool_choice",
        "thinking_field": "enable_thinking",
        "reasoning_effort_documented": False,
        "version_state": "REQUESTED_ONLY",
        "alias_of_upstream_build": True,
        "version_note": "request name is an alias: the model card describes the -1017 upgrade of this "
                        "name, so the served build must be read from a real response, not assumed",
        "price_input_fen_per_million": 200,
        "price_output_fen_per_million": 800,
        "price_cache_fen_per_million": 4,
        "price_tier": "standard",
        "price_window": "0-2/8-24 Asia/Shanghai; the 2-8 window is cheaper and is not used",
        "price_source": PRIMARY_SOURCES["siliconflow_pricing"][1],
        "read_at": READ_AT,
        "recheck_read_at": PRICE_RECHECK_AT,
        "recheck_source": PRIMARY_SOURCES["siliconflow_pricing_recheck"][1],
        "recheck_note": "2026-11-19 official page re-read: 2.00/8.00/0.04 CNY per million in the "
                        "0-2/8-24 window — unchanged; the 2-8 window (1.00/4.00/0.02) stays unused",
    },
    {
        "controller_id": "controller-b",
        "model_request_name": "deepseek-ai/DeepSeek-V4-Pro",
        "provider": "siliconflow",
        "transport": "chat_completions",
        "base_url": "https://api.siliconflow.cn/v1",
        "endpoint": "/chat/completions",
        "protocol_family": "openai_chat_completions",
        "native_tools": True,
        "native_tools_evidence": "model card '🛠️ 工具调用'",
        "thinking_field": "enable_thinking",
        "reasoning_effort_documented": False,
        "version_state": "REQUESTED_ONLY",
        "alias_of_upstream_build": False,
        "version_note": "single list price, no time window",
        "price_input_fen_per_million": 1200,
        "price_output_fen_per_million": 2400,
        "price_cache_fen_per_million": 100,
        "price_tier": "standard",
        "price_window": "flat",
        "price_source": PRIMARY_SOURCES["siliconflow_pricing"][1],
        "read_at": READ_AT,
        "recheck_read_at": PRICE_RECHECK_AT,
        "recheck_source": PRIMARY_SOURCES["siliconflow_pricing_recheck"][1],
        "recheck_note": "2026-11-19 official page re-read: 12.00/24.00/1.00 CNY per million, "
                        "no time window — unchanged",
    },
)

FIXED_ROLES = (
    {
        "role": "preparation",
        "model_request_name": "deepseek-ai/DeepSeek-V4.1-Flash",
        "provider": "siliconflow",
        "transport": "chat_completions",
        "max_output_tokens": 384,
        "note": "fixed across both controllers; identical to controller-a, so preparation quality is not "
                "attributed to either controller (recorded confound)",
    },
    {
        "role": "analysis",
        "model_request_name": "deepseek-ai/DeepSeek-V4.1-Flash",
        "provider": "siliconflow",
        "transport": "chat_completions",
        "max_output_tokens": 16384,
        "note": "fixed across both controllers; thinking stays disabled through enable_thinking=false",
    },
)

VISION = {
    "provider": "minimax",
    "transport": "openai_chat_vision",
    "base_url": "https://api.minimax.cn",
    "endpoint": "/v1/chat/completions",
    "protocol_family": "openai_chat_completions_multimodal",
    "model_request_name": "MiniMax-M3",
    "version_state": "REQUESTED_ONLY",
    "thinking": "disabled",
    "image_detail": "low",
    # Grounded-budget metering basis (v3): the official estimate is now ANCHORED by the two
    # authorized real requests, and image tokens are proven to flow into usage.prompt_tokens.
    "per_image_token_ceiling": 600,
    "per_image_metering_state": "OFFICIAL_ESTIMATE_REAL_ANCHORED",
    "per_image_quote": "low | 通常为几百 token，最高约 600（单张图片粗略 token 用量估算）",
    "metering_grounding": {
        "image_probe": "probe-run-20261119T024727Z: one formal frame (frame-00, 62,099 ms) + short "
                       "text -> prompt_tokens 540, completion 35, reasoning_tokens 0, served model "
                       "MiniMax-M3",
        "text_compare": "text-compare-run-20261119T025509Z: identical text without the image -> "
                        "prompt_tokens 202",
        "derived_image_tokens_frame00": 338,
        "measured_at": METERING_AT,
        "interpretation": "image tokens flow into usage.prompt_tokens; the single-frame anchor sits "
                          "inside the documented <=600 estimate band",
    },
    "request_text_floor_measured": 202,
    "measured_image_tokens_frame00": 338,
    "prompt_chars_max": 1000,
    "prompt_chars_max_basis": "vision client skeleton ~300 chars + focus parameter enforced <=500 "
                              "chars (VideoAnalysisService) + transcript disabled (empty); chars>=tokens "
                              "per the official 0.625 token/char ratio, so the envelope doubles it",
    "max_images_per_request": 16,
    "max_completion_tokens": 2048,
    "billing_unit": "token",
    "image_metering": "image tokens are counted inside usage.prompt_tokens (grounded 2026-11-19 by "
                      "the probe/compare pair); the response usage remains the authoritative "
                      "settlement source (docs: '请以响应中的 usage 为准')",
    "prepay_fee_state": "GROUNDED_ESTIMATE",
    "prepay_fee_basis": "input = measured request floor 202 + declared prompt envelope 1000 chars "
                        "(chars>=tokens) + images x documented 600/image estimate; output capped by "
                        "max_completion_tokens; settlement always re-prices from the real response "
                        "usage, so the reservation stays a ceiling, not a bill",
    "max_request_body_bytes": 64 * 1024 * 1024,
    "max_single_image_bytes": 10 * 1024 * 1024,
    "transport_limits_quote": "URL / Base64 方式传入：视频文件 ≤ 50 MB，图片文件 ≤ 10 MB，请求体 ≤ 64 MB。",
    "price_input_fen_per_million": 420,
    "price_output_fen_per_million": 1680,
    "price_cache_fen_per_million": 84,
    "price_tier": "standard",
    # 2026-11-19 official pricing page: the standard tier itself is 永久五折 — 2.10/8.40/0.42 CNY
    # per million (struck-through list 4.20/16.80/0.84). Priority is 1.5x standard, opt-in via
    # service_tier=priority (never set here). The frozen 420/1680 reservation base equals the
    # struck-through list price of the <=512k tier and is >= every reachable price (standard
    # 2.10/8.40, priority 3.15/12.60) while input stays under 512k, so it stays the reservation
    # basis unchanged.
    "price_window": "MiniMax-M3 <=512k input tokens (this trial's requests are far below 512k); "
                    "the standard tier currently bills at 永久五折 (210/840 fen per million); "
                    "priority tier is 1.5x standard (315/1260 fen); >512k tiers do not apply",
    "applicable_standard_input_fen": 210,
    "applicable_standard_output_fen": 840,
    "applicable_priority_input_fen": 315,
    "applicable_priority_output_fen": 1260,
    "price_source": PRIMARY_SOURCES["minimax_pricing"][1],
    "recheck_read_at": PRICE_RECHECK_AT,
    "recheck_source": PRIMARY_SOURCES["minimax_pricing_recheck"][1],
    "recheck_note": "2026-11-19 official page re-read: standard <=512k is 永久五折 at "
                    "2.10/8.40/0.42 CNY per million (list 4.20/16.80/0.84 struck through); "
                    "priority = 1.5x standard; the old 'no permanent-discount' note was wrong "
                    "and is corrected by this registry",
    "assumes_discount": False,
    "assumes_cache_hit": False,
    "output_limit_enforced_by": "max_completion_tokens is a required supplier parameter "
                                "(docs: '指定生成内容长度的上限（Token 数）')",
    "source": PRIMARY_SOURCES["minimax_chat_schema"][1],
    "read_at": READ_AT,
}

# The harness role is controller-specific (that is the comparison), so its output cap is not a
# fixed-role row: this constant is SAME-SOURCED with DeepSeekProperties' harnessMaxTokens default
# (3072). test_report_repair01_reconcile.PlannerEnvelopeSourceTest parses the Java source and
# enforces the equality, so the planner can never quietly understate what a real run may spend.
HARNESS_MAX_OUTPUT_TOKENS = 3_072

# 4B-2A live-readiness ALIGNED budget basis (2026-11-19): every estimate is same-sourced with a
# RUNTIME-ENFORCED limit, so the plan can never understate what a dispatch may spend:
#   input  -> the entry's OWN runtime input cap ENTRY_MAX_INPUT_BYTES = 65536 bytes (2x the
#             measured largest dispatch, 18214 B; enforced by ContextPolicy at every dispatch).
#             The runtime reserves per REQUEST BYTES, so the plan states input in the SAME unit
#             (bytes-as-tokens; tokens <= chars <= bytes keeps the reservation a ceiling).
#   counts -> the loop's enforced maxModelCalls = 16 per run (maxTurns 6 usually binds first —
#             the measured healthy run used 4 harness dispatches).
#   output -> the supplier-enforced DeepSeekProperties caps (384/3072/16384, unchanged).
#   vision -> the ACTUAL in-window frame list: 2 formal frames (446000/456000 ms) per dispatch.
ENTRY_MAX_INPUT_BYTES = 65536
RUNTIME_MAX_MODEL_CALLS = 16
WINDOW_FRAMES_IN_WINDOW = 2


# Per-role declared envelopes for the dry-run plan, SAME-SOURCED with the report entry's effective
# defaults: DeepSeekProperties @DefaultValue preparationMaxTokens=384 / harnessMaxTokens=3072 /
# analysisMaxTokens=16384 (the fixed roles' rows above carry the same analysis figure). Input
# bounds are the entry's declared per-dispatch input envelopes; the same-source parse test also
# pins these against the Java file.
#
# 4B-2A content-repair recalculation (2026-11-19), after the deepseek-profile execution path was
# actually exercised end to end on the local loopback double:
#   preparation  -> 0 dispatches: the evaluation wiring pins preparation to an explicit FIXTURE
#                   (deterministic intent/understanding, no provider dispatch; recorded, never
#                   hidden).
#   analysis     -> 12 dispatches UPPER BOUND: the topic work splits into at most 3 classify
#                   batches (195 items at the 80-groups/batch cap; the measured healthy run used
#                   ONE batch because short texts pack under the 24000-byte cap), each work item
#                   retries at most 3 attempts (initial + 2 correction rounds), and the merge
#                   (representative review riding it) is likewise <=3; the rerank confirmation
#                   never runs (rerank disabled). The measured healthy shape was 2 dispatches
#                   (1 classify + 1 merge+review) per UNCACHED report.
ROLE_ENVELOPE = {
    # input bounds are now the RUNTIME-ENFORCED byte cap (bytes-as-tokens, same basis the ledger
    # reserves with); counts are the runtime-enforced limits (see the alignment block above)
    "preparation": {"input_tokens": ENTRY_MAX_INPUT_BYTES, "output_tokens": FIXED_ROLES[0]["max_output_tokens"], "dispatches": 0},
    "harness": {"input_tokens": ENTRY_MAX_INPUT_BYTES, "output_tokens": HARNESS_MAX_OUTPUT_TOKENS, "dispatches": RUNTIME_MAX_MODEL_CALLS},
    "analysis": {"input_tokens": ENTRY_MAX_INPUT_BYTES, "output_tokens": FIXED_ROLES[1]["max_output_tokens"], "dispatches": 12},
}

# Prior-round spend carried INTO every new batch envelope (4B-2A content repair, 2026-11-19).
# Both figures are file-verified from the named evidence roots and remain pending provider
# reconciliation; they count toward the conservative total from the first admission and survive
# restarts (the ledger re-loads its carried_in block from the file).
CARRIED_SETTLED_FEN = 280
CARRIED_RETAINED_FEN = 24
CARRIED_SOURCE = ("4b2a-first-pair-release-20261119 run ledgers: 276 fen settled across 16 ledgers "
                  "(12 formal positions 202 fen incl. 10 vision + 4 other attempts) + 24 fen "
                  "FAILED_RETAINED timeout reservation on the DeepSeek-V4-Pro exit; plus two 2-fen "
                  "metering probes in 4b2a-price-refresh-20261118/minimal-real-request "
                  "(probe 540/35 tokens, text-compare 202/28 tokens). File-verified 2026-11-19; "
                  "provider reconciliation pending.")

# CURRENCY CORRECTION (2026-11-19 live-readiness repair): fen is 1/100 CNY. The PREVIOUS round's
# "622 fen" first-pair ceiling was 6.22 CNY — an OLD-plan value built on declared token units and
# a 12-image placeholder; it is SUPERSEDED by the aligned ceilings below. Historical carried
# spend: 280 fen settled (2.80 CNY) + 24 fen retained (0.24 CNY) = 304 fen (3.04 CNY), provider
# reconciliation pending. The suggested cumulative first-pair line 1000 fen = 10.00 CNY.
FIRST_PAIR_PAUSE_FEN = 1000

# Two cache states for the first pair (per the reacceptance review): the analysis result cache
# is content-addressed and SHARED across controllers (same fixed Flash analysis role), so the
# second report may legitimately consume the first report's analysis. Both ceilings are stated;
# whether the REAL batch accepts shared analysis is the main dialog's release decision.
#   full report (cache miss): harness 16 x (14+3)=272 (a) / 16 x (79+8)=1392 (b);
#                             analysis 12 x (14+14)=336; vision 1 x (2+4)=6
#   controller-a report      = 418 fen;  controller-b report = 1026 fen
#   controller-a report      = 614 fen;  controller-b report = 1734 fen
#   shared-analysis pair     = 614 + 1392 = 2006 fen (20.06 CNY)
#   isolated-analysis pair   = 614 + 1734 = 2348 fen (23.48 CNY)  <- the batch new-spend cap
BATCH_NEW_SPEND_CAP_FEN = 2348

# Measured healthy dispatch shape from the content-repair loopback pair (2026-11-19,
# gen runs under 4b2a-canon-content-repair-20261119): the measured figures are recorded
# alongside the conservative envelope, never as a substitute for it.
MEASURED_DISPATCH_SHAPE = {
    "controller_a_report": {"harness": 4, "analysis": 2, "vision": 1, "preparation": 0},
    "controller_b_report": {"harness": 4, "analysis": 0, "vision": 0, "preparation": 0,
                            "note": "analysis and vision content-addressed cache hits"},
    "measured_at": "2026-11-19",
}

BLOCKED_EXITS = (
    {
        "exit": "vision dedicated /v1/coding_plan/vlm",
        "state": "BLOCKED",
        "reason": "the dedicated coding-plan contract lists no per-image/per-call billing unit and no "
                  "output cap; mixing it with generic model fields is forbidden",
        "source": PRIMARY_SOURCES["minimax_chat_schema"][1],
    },
    {
        "exit": "audio /audio/transcriptions",
        "state": "BLOCKED",
        "reason": "no approved audio source for this trial; transcription stays disabled and sound evidence "
                  "stays absent rather than being inferred from existing subtitles",
        "source": "docs/evaluation/decisions.md",
    },
)


class ContractError(Exception):
    pass


def controller_by_name(name):
    for entry in CONTROLLERS:
        if entry["model_request_name"] == name:
            return entry
    raise ContractError(f"unknown controller request name: {name!r}")


def fixed_role(name):
    for entry in FIXED_ROLES:
        if entry["role"] == name:
            return entry
    raise ContractError(f"unknown fixed role: {name!r}")


def vision_contract_for(model_request_name, transport=None):
    if model_request_name != VISION["model_request_name"]:
        raise ContractError(
            f"no bounded vision contract for {model_request_name!r}: MiniMax-M3.1-Flash forces thinking "
            "and its reasoning tokens are not stated to be inside completion_tokens")
    if transport is not None and transport != VISION["transport"]:
        raise ContractError(
            f"transport {transport!r} is not the registered vision contract "
            f"({VISION['transport']!r})")
    return VISION


def vision_exit_key(provider, model_request_name, transport):
    if not provider or not model_request_name or not transport:
        raise ContractError("vision exit key needs provider, request name and transport")
    return f"vision:{provider}:{model_request_name}:{transport}"


def chat_exit_key(host, model_request_name):
    return f"chat:{host}:{model_request_name}"


def ledger_prices():
    """Frozen reservation table: exit -> fen per million tokens (input, output)."""
    table = {}
    host = "api.siliconflow.cn"
    for entry in CONTROLLERS:
        table[chat_exit_key(host, entry["model_request_name"])] = {
            "input_fen_per_million": entry["price_input_fen_per_million"],
            "output_fen_per_million": entry["price_output_fen_per_million"],
        }
    table[chat_exit_key(host, FIXED_ROLES[0]["model_request_name"])] = {
        "input_fen_per_million": CONTROLLERS[0]["price_input_fen_per_million"],
        "output_fen_per_million": CONTROLLERS[0]["price_output_fen_per_million"],
    }
    table[vision_exit_key(VISION["provider"], VISION["model_request_name"], VISION["transport"])] = {
        "input_fen_per_million": VISION["price_input_fen_per_million"],
        "output_fen_per_million": VISION["price_output_fen_per_million"],
    }
    return table


def vision_bound(image_count, payload_bytes, output_tokens):
    """Grounded upper bound for one vision dispatch (v3).

    input  = measured request floor (202) + declared prompt envelope (1000 chars >= tokens)
           + images x documented per-image estimate (600)
    output = the supplier-enforced max_completion_tokens

    The middle parameter stays part of the signature (the ledger passes the measured transport
    payload) but is VALIDATED ONLY — transport bytes are never tokens and never enter the math;
    that was the withdrawn price-refresh bug. Settlement always re-prices from the real response
    usage, so this bound stays a reservation ceiling, not a bill.
    """
    if not isinstance(image_count, int) or isinstance(image_count, bool) \
            or not 1 <= image_count <= VISION["max_images_per_request"]:
        raise ContractError(f"image_count must be 1..{VISION['max_images_per_request']}")
    if not isinstance(payload_bytes, int) or isinstance(payload_bytes, bool) or payload_bytes < 1:
        raise ContractError("payload_bytes must be a positive integer")
    if not isinstance(output_tokens, int) or isinstance(output_tokens, bool) \
            or not 1 <= output_tokens <= VISION["max_completion_tokens"]:
        raise ContractError(f"output_tokens must be 1..{VISION['max_completion_tokens']}")
    image_tokens = image_count * VISION["per_image_token_ceiling"]
    input_tokens = VISION["request_text_floor_measured"] + VISION["prompt_chars_max"] + image_tokens
    return {"input_tokens": input_tokens, "image_tokens": image_tokens,
            "output_tokens": output_tokens, "state": VISION["prepay_fee_state"],
            "text_floor_measured": VISION["request_text_floor_measured"],
            "prompt_chars_max": VISION["prompt_chars_max"],
            "basis": "measured 202-token request floor + declared 1000-char prompt envelope "
                     "(chars>=tokens) + documented 600/image estimate; output = "
                     "max_completion_tokens; transport bytes are not tokens"}


def vision_reservation_fen(image_count, payload_bytes, output_tokens):
    """Reservation ceiling in fen for one vision dispatch under the grounded bound."""
    bound = vision_bound(image_count, payload_bytes, output_tokens)
    fen = _ceil_div(bound["input_tokens"] * VISION["price_input_fen_per_million"], 1_000_000) \
        + _ceil_div(bound["output_tokens"] * VISION["price_output_fen_per_million"], 1_000_000)
    return max(1, fen)


def vision_transport_check(image_count, payload_bytes):
    """The ONLY offline-checkable vision facts: the documented transport limits (single image
    <= 10 MB, request body <= 64 MB). Payload bytes stay transport evidence forever — they are
    never tokens and never a fee."""
    limits = []
    if payload_bytes > VISION["max_request_body_bytes"]:
        limits.append(f"payload {payload_bytes} bytes exceeds the documented 64 MB request-body limit")
    if image_count > VISION["max_images_per_request"]:
        limits.append(f"{image_count} images exceeds the {VISION['max_images_per_request']}-image request shape")
    if image_count > 0 and payload_bytes // image_count > VISION["max_single_image_bytes"]:
        limits.append(f"per-image payload exceeds the documented 10 MB single-image limit")
    return {"within_documented_limits": not limits, "violations": limits}


def readiness_facts():
    """Facts a reader must be able to check without trusting the driver's self-report."""
    facts = []
    for entry in CONTROLLERS:
        facts.append({
            "item": f"controller {entry['controller_id']} request name",
            "value": entry["model_request_name"],
            "state": entry["version_state"],
            "source": PRIMARY_SOURCES["siliconflow_models"][1],
            "read_at": READ_AT,
        })
        facts.append({
            "item": f"controller {entry['controller_id']} price (fen per million tokens)",
            "value": f"{entry['price_input_fen_per_million']}/{entry['price_output_fen_per_million']}",
            "state": "PUBLIC_LIST_PRICE",
            "source": entry["price_source"],
            "read_at": READ_AT,
        })
    facts.append({
        "item": "vision contract",
        "value": f"{VISION['provider']} {VISION['model_request_name']} {VISION['transport']} "
                 f"detail={VISION['image_detail']} thinking={VISION['thinking']}",
        "state": VISION["version_state"],
        "source": VISION["source"],
        "read_at": READ_AT,
    })
    facts.append({
        "item": "vision pre-pay fee",
        "value": f"GROUNDED_ESTIMATE — per request: measured floor {VISION['request_text_floor_measured']} "
                 f"words + prompt envelope {VISION['prompt_chars_max']} chars (chars>=tokens) + "
                 f"{VISION['per_image_token_ceiling']} words/image (official estimate, real anchor "
                 f"{VISION['metering_grounding']['derived_image_tokens_frame00']} words/frame); output "
                 f"capped by max_completion_tokens={VISION['max_completion_tokens']}; transport bytes "
                 f"are not tokens; settlement re-prices from the real response usage",
        "state": VISION["prepay_fee_state"],
        "source": VISION["source"],
        "read_at": READ_AT,
    })
    facts.append({
        "item": "vision metering anchors (real requests, 2026-11-19)",
        "value": f"frame+text probe prompt_tokens=540 ({VISION['metering_grounding']['image_probe']}); "
                 f"identical text without the image prompt_tokens=202 "
                 f"({VISION['metering_grounding']['text_compare']}); derived frame image tokens "
                 f"= {VISION['metering_grounding']['derived_image_tokens_frame00']}; "
                 f"reasoning_tokens=0 with thinking disabled",
        "state": "REAL_RESPONSE_MEASURED",
        "source": "var/provider-admission/execution/4b2a-price-refresh-20261118/minimal-real-request/",
        "read_at": METERING_AT,
    })
    facts.append({
        "item": "vision price tiers (2026-11-19 re-check)",
        "value": f"standard <=512k 永久五折 {VISION['applicable_standard_input_fen']}/"
                 f"{VISION['applicable_standard_output_fen']} fen per million; priority 1.5x "
                 f"({VISION['applicable_priority_input_fen']}/"
                 f"{VISION['applicable_priority_output_fen']}); reservation base frozen at "
                 f"{VISION['price_input_fen_per_million']}/{VISION['price_output_fen_per_million']} "
                 f"(>= every reachable price)",
        "state": "PUBLIC_LIST_PRICE",
        "source": VISION["recheck_source"],
        "read_at": VISION["recheck_read_at"],
    })
    for blocked in BLOCKED_EXITS:
        facts.append({"item": blocked["exit"], "value": "not payable", "state": blocked["state"],
                      "source": blocked["source"], "read_at": READ_AT})
    return facts
