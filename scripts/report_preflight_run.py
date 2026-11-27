"""4B-2A preflight driver: dry-run plan, real frame extraction, run reconciliation, readiness file.

Default posture is DRY RUN: nothing here dispatches a provider request or reads a credential. The
`frames` stage reads the FORMAL Canon MP4 (read-only) and records the sampled timecodes, per-frame
sha256 and source video fingerprint, so a later live run can prove its pictures came from the正式
video rather than a synthetic fixture. The `verify` stage reconciles a run directory's manifest, the
fee ledger and the loopback stub's request log: since Repair-01 every manifest dispatch is bound
FIELD BY FIELD to its reservation and its logged request (exit, model, path, body digest, price
identity, reserved amount, usage, settled amount); counts alone no longer pass a run that carries a
dispatch table. `readiness` writes the pre-pay checklist as data.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from report_evaluation.admission import contracts

FRAME_COUNT = 12
# Sampling covers the whole runtime plus the R-F1 hot segment (07:26-07:36) already confirmed by the
# human review; timestamps are computed from the real duration, never hardcoded.
HOT_SEGMENT_MS = (7 * 60 + 26) * 1000
PEAK_SEGMENT_MS = (7 * 60 + 36) * 1000
# One report run's provider dispatches, by role (4B-2A live-readiness ALIGNED, 2026-11-19).
# Harness: the loop's ENFORCED maxModelCalls (16; maxTurns 6 usually binds first — the measured
# healthy run used 4). Preparation: explicit FIXTURE (0 dispatches). Analysis: conservative upper
# bound (<=3 classify batches x 3 attempts + <=3 merge attempts incl. the representative review;
# rerank confirmation disabled) — measured shape 2 per uncached report. Vision: 1 dispatch on a
# frame-cache miss, carrying the ACTUAL in-window frame list (2 formal frames).
DISPATCHES_PER_RUN = {"preparation": 0, "harness": 16, "analysis": 12, "vision": 1}
# Upper-bound envelopes per dispatch (input tokens, output tokens). The OUTPUT caps are
# SAME-SOURCED with the report entry's effective defaults (contracts.ROLE_ENVELOPE /
# contracts.FIXED_ROLES / contracts.HARNESS_MAX_OUTPUT_TOKENS — the Python source-parse test pins
# them against DeepSeekProperties), so the planner can never understate a real run's declared
# budget again (the repair-01 fix: analysis was understated at 2048 instead of 16384).
ENVELOPE = {
    "preparation": (contracts.ROLE_ENVELOPE["preparation"]["input_tokens"],
                    contracts.ROLE_ENVELOPE["preparation"]["output_tokens"]),
    "harness": (contracts.ROLE_ENVELOPE["harness"]["input_tokens"],
                contracts.ROLE_ENVELOPE["harness"]["output_tokens"]),
    "analysis": (contracts.ROLE_ENVELOPE["analysis"]["input_tokens"],
                 contracts.ROLE_ENVELOPE["analysis"]["output_tokens"]),
    "vision_bytes": 20_000,
    # the ACTUAL in-window frame list of the formal extraction (446000-457000 ms carries exactly
    # two human-confirmed anchors) — the bound prices what the entry really sends, not the whole
    # 12-frame extraction
    "vision_images": contracts.WINDOW_FRAMES_IN_WINDOW,
    "vision_output": contracts.VISION["max_completion_tokens"],
}
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "[::1]", "::1"}
# Which documented endpoint each dispatch kind must have hit.
EXIT_PATHS = {"chat": "/chat/completions", "vision": "/v1/chat/completions"}


def _ceil_div(a, b):
    return -(-a // b)


def _fen(tokens, per_million):
    return _ceil_div(tokens * per_million, 1_000_000)


def measure_vision_payload(frames_manifest, text_overhead=4_096):
    """Transport payload bytes a vision dispatch would carry, measured from a real frame manifest.

    Raw JPEG bytes are not what goes on the wire: every frame is base64 encoded inside the JSON body,
    so the body is at least ceil(4/3 * raw) plus the text prompt. Rounding up keeps the transport
    figure an upper bound. Returns (image_count, payload_bytes).

    Price-refresh note (2026-11-19): these bytes are TRANSPORT evidence only — they feed the
    documented request-body (<= 64 MB) and per-image (<= 10 MB) limit checks. They are never
    tokens and never a fee: the official metering names the real response usage authoritative,
    so the fee stays UNKNOWN regardless of this measurement.
    """
    doc = json.loads(Path(frames_manifest).read_text(encoding="utf-8"))
    frames = doc.get("frames")
    if not isinstance(frames, list) or not frames:
        raise ValueError("frames manifest carries no frames")
    sizes = []
    for frame in frames:
        size = frame.get("bytes")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise ValueError(f"frame {frame.get('index')} has no byte size")
        sizes.append(size)
    encoded = (sum(sizes) * 4 + 2) // 3
    return len(frames), encoded + text_overhead


def build_plan(controllers=2, repeats=1, materials=1, frames_manifest=None, frames_manifests=None):
    """Dry-run plan for the paired first slice, priced PER CONTROLLER at each controller's own
    public list price. Refuses anything outside the approved envelope.

    Grounded-budget semantics (v3, 2026-11-19): the vision fee is bounded by the measured
    202-token request floor + the 1000-char prompt envelope + the documented 600/image estimate,
    with the sampled image count fixed at 12 per run BY DESIGN — so the fee no longer depends on
    frame manifest bytes at all. Every plan closes at a fen-scale total; settlement in a real run
    still re-prices from the actual response usage. Frame manifests remain transport evidence
    (documented 64 MB body / 10 MB image limits) and provenance; a material without one simply
    carries no payload record (never another material's payload)."""
    if controllers != len(contracts.CONTROLLERS):
        raise ValueError(f"the approved comparison is exactly {len(contracts.CONTROLLERS)} controllers")
    runs = controllers * repeats * materials
    if runs > 12:
        raise ValueError("plan exceeds the approved 12 agent-report trials")
    if frames_manifests is not None:
        if len(frames_manifests) != materials:
            raise ValueError("frames_manifests must carry exactly one entry per material")
        material_payloads = {}
        for key, path in frames_manifests.items():
            material_payloads[key] = None if path is None else measure_vision_payload(path)
    elif frames_manifest is not None:
        if materials != 1:
            raise ValueError("a multi-material plan needs frames_manifests per material; a single "
                             "manifest must not stand in for the other materials")
        material_payloads = {"ysjf-canon": measure_vision_payload(frames_manifest)}
    else:
        if materials != 1:
            raise ValueError("a multi-material plan needs frames_manifests per material; the "
                             "placeholder payload must not stand in for the other materials")
        material_payloads = {"ysjf-canon": None}

    def chat_fen(entry, input_tokens, output_tokens):
        return _fen(input_tokens, entry["price_input_fen_per_million"]) + \
            _fen(output_tokens, entry["price_output_fen_per_million"])

    # The fixed roles are pinned to controller-a's request name by the contract registry, so
    # preparation/analysis price at that entry for BOTH controllers; only the harness role
    # follows each controller's own price (that is the comparison being paid for).
    fixed_entry = contracts.controller_by_name(contracts.FIXED_ROLES[0]["model_request_name"])
    controllers_breakdown = []
    chat_fen_by_controller = {}
    for controller in contracts.CONTROLLERS:
        per_role = []
        for role in ("preparation", "harness", "analysis"):
            role_envelope = contracts.ROLE_ENVELOPE[role]
            entry = fixed_entry if role in ("preparation", "analysis") else controller
            fen = chat_fen(entry, role_envelope["input_tokens"], role_envelope["output_tokens"]) \
                * role_envelope["dispatches"]
            per_role.append({
                "role": role, "dispatches": role_envelope["dispatches"], "fen": fen,
                "priced_with": entry["model_request_name"],
                "input_tokens": role_envelope["input_tokens"], "output_tokens": role_envelope["output_tokens"],
            })
        total = sum(role["fen"] for role in per_role)
        chat_fen_by_controller[controller["controller_id"]] = total
        controllers_breakdown.append({
            "controller_id": controller["controller_id"],
            "model": controller["model_request_name"],
            "price_input_fen_per_million": controller["price_input_fen_per_million"],
            "price_output_fen_per_million": controller["price_output_fen_per_million"],
            "roles": per_role,
            "chat_fen_per_run": total,
        })

    # The sampled image count is 12 per run by design, so every material's vision leg prices at
    # the same grounded bound; the manifest only decides whether a transport payload is recorded.
    vision_images = ENVELOPE["vision_images"]
    vision_fen_per_run = contracts.vision_reservation_fen(vision_images, 1, ENVELOPE["vision_output"])
    materials_breakdown = []
    unmeasured_materials = []
    for key in sorted(material_payloads):
        measured = material_payloads[key]
        if measured is None:
            unmeasured_materials.append(key)
            materials_breakdown.append({
                "material_key": key, "vision_state": "GROUNDED_BOUND",
                "vision_fee_state": "GROUNDED_ESTIMATE", "vision_fen_per_run": vision_fen_per_run,
                "vision_images": vision_images, "transport_payload": "NOT_MEASURED",
            })
        else:
            image_count, payload_bytes = measured
            materials_breakdown.append({
                "material_key": key, "vision_state": "GROUNDED_BOUND",
                "vision_fee_state": "GROUNDED_ESTIMATE", "vision_fen_per_run": vision_fen_per_run,
                "vision_images": image_count, "vision_payload_bytes": payload_bytes,
                "vision_transport": contracts.vision_transport_check(image_count, payload_bytes),
            })

    per_run = {entry["controller_id"]: entry["chat_fen_per_run"] + vision_fen_per_run
               for entry in controllers_breakdown}
    for entry in controllers_breakdown:
        entry["per_run_fen"] = per_run[entry["controller_id"]]
    total = runs * vision_fen_per_run + repeats * materials * sum(chat_fen_by_controller.values())

    # TWO CACHE STATES for the first pair (2026-11-19): the analysis result cache is shared
    # across controllers (same fixed Flash analysis role), so the second report may be
    # cache-served. Both pair ceilings are stated on the ALIGNED basis; whether the REAL batch
    # accepts shared analysis is the main dialog's release decision.
    harness_only_b = next(entry for entry in controllers_breakdown
                          if entry["controller_id"] == "controller-b")["roles"]
    harness_only_b_fen = next(role for role in harness_only_b if role["role"] == "harness")["fen"]
    a_full = per_run["controller-a"]
    carried = contracts.CARRIED_SETTLED_FEN + contracts.CARRIED_RETAINED_FEN
    shared_new = a_full + harness_only_b_fen
    isolated_new = a_full + per_run["controller-b"]
    pause_line = contracts.FIRST_PAIR_PAUSE_FEN
    project_pause = 2000
    cache_states = {
        "shared_analysis_pair_fen": shared_new,
        "isolated_analysis_pair_fen": isolated_new,
        "with_history": {
            "shared_total_fen": carried + shared_new,
            "shared_total_cny": format(float(carried + shared_new) / 100, ".2f") + " CNY",
            "isolated_total_fen": carried + isolated_new,
            "isolated_total_cny": format(float(carried + isolated_new) / 100, ".2f") + " CNY",
        },
        "new_request_room_under_lines": {
            "under_10_cny_line_fen": pause_line - carried,
            "under_project_20_cny_pause_fen": project_pause - carried,
            "note": "the room counts NEW spend before the respective line refuses the next admission",
        },
        "coverage_verdict": {
            "shared_fits_under_10cny_line": shared_new < pause_line - carried,
            "isolated_fits_under_10cny_line": isolated_new < pause_line - carried,
            "shared_fits_under_project_pause": shared_new < project_pause - carried,
            "isolated_fits_under_project_pause": isolated_new < project_pause - carried,
            "verdict": "NEITHER scheme completes in one pass under the 10 CNY line; under the "
                       "project 20 CNY pause line the SHARED scheme also crosses (2006 new vs "
                       "1696 room) and the ISOLATED scheme crosses by more; expect a mid-pair "
                       "pause, or use the staged option below",
        },
        "staged_option": {
            "description": "run the pair as TWO release stages (one ledger per stage, the stage "
                           "ledger carries the prior stage's actual settled spend): stage 1 = "
                           "controller-a report alone, stage 2 = controller-b report alone",
            "stage_1_new_fen": a_full,
            "stage_2_new_shared_fen": harness_only_b_fen,
            "stage_2_new_isolated_fen": per_run["controller-b"],
            "cost": "two separate launches and two acceptance rounds; with the carried history "
                    "stage 1 alone fits the 10 CNY line (918 cumulative) but stage 2 crosses it "
                    "in BOTH schemes — stage 2 still needs a line decision at release",
            "basis": "each stage opens its own batch envelope carrying the previous stages' "
                     "actual settled spend — the pause stays protective without raising it",
        },
        "note": "shared = second report consumes the first report's analysis (cache-served, "
                "harness+nothing else); isolated = both reports dispatch their own analysis",
    }
    plan = {
        "kind": "report-preflight-plan",
        "contract_version": contracts.CONTRACT_VERSION,
        "controllers": [c["model_request_name"] for c in contracts.CONTROLLERS],
        "runs": runs,
        "dispatches_per_run": sum(DISPATCHES_PER_RUN.values()),
        "provider_dispatches": runs * sum(DISPATCHES_PER_RUN.values()),
        "controllers_breakdown": controllers_breakdown,
        "materials_breakdown": materials_breakdown,
        "unmeasured_materials": unmeasured_materials,
        "unknown_vision_runs": 0,
        "vision_fee_state": "GROUNDED_ESTIMATE",
        "verdict": "PLANNED_GROUNDED_ESTIMATE",
        "fee_upper_fen_per_run": max(per_run.values()),
        "fee_upper_fen": total,
        "vision_fen_per_run": vision_fen_per_run,
        "vision_images": vision_images,
        "vision_payload_source": ("frames-manifest" if frames_manifest or frames_manifests
                                  else "envelope-placeholder"),
        "fee_basis_note": "chat legs at declared envelopes and each controller's own list price; "
                          "the vision leg at the grounded bound (measured 202-word request floor "
                          "+ 1000-char prompt envelope + documented 600/image estimate); "
                          "settlement in a real run re-prices from actual response usage",
        "role_envelope": {role: dict(contracts.ROLE_ENVELOPE[role]) for role in contracts.ROLE_ENVELOPE},
        "pause_fen": 2000,
        "hard_limit_fen": 5000,
        "first_pair_pause_fen": contracts.FIRST_PAIR_PAUSE_FEN,
        "first_pair_pause_cny": "10.00 CNY",
        "batch_new_spend_cap_fen": contracts.BATCH_NEW_SPEND_CAP_FEN,
        "cache_states": cache_states,
        "currency_note": "fen = 1/100 CNY; the previous round's 622-fen (6.22 CNY) ceiling was an "
                         "OLD-plan value on declared token units and a 12-image placeholder — "
                         "SUPERSEDED by the aligned basis above",
        "carried_in": {
            "settled_fen": contracts.CARRIED_SETTLED_FEN,
            "retained_fen": contracts.CARRIED_RETAINED_FEN,
            "source": contracts.CARRIED_SOURCE,
            "provider_reconciliation": "PENDING",
        },
        "first_pair_conservative_total_fen": (
            contracts.CARRIED_SETTLED_FEN + contracts.CARRIED_RETAINED_FEN + total),
        "request_digest_policy": "required",
        "live_dispatch_enabled": False,
        "note": "dry run: totals are grounded reservation ceilings, not bills; arming real runs "
                "is a separate release decision",
    }
    if total > 5000:
        raise ValueError(f"grounded upper bound {total} fen exceeds the 50 CNY hard limit "
                         f"(controller-a {per_run['controller-a']} fen/run, controller-b "
                         f"{per_run['controller-b']} fen/run; per-controller breakdown in the plan)")
    return plan


def _sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _probe(ffprobe, video):
    result = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration",
                             "-of", "default=noprint_wrappers=1:nokey=1", str(video)],
                            capture_output=True, text=True, check=True)
    return int(float(result.stdout.strip()) * 1000)


def _ffmpeg_version(ffmpeg):
    result = subprocess.run([ffmpeg, "-version"], capture_output=True, text=True, check=True)
    return result.stdout.splitlines()[0].strip()


def sample_timestamps(duration_ms, count=FRAME_COUNT):
    """Even coverage of the whole runtime, with the confirmed R-F1 hot segment always sampled.

    The two anchors (start/end of the human-confirmed hot segment) are reserved first, so an even
    sample can never overwrite one of them; the remaining slots are spread over the runtime.
    """
    if duration_ms <= 0 or count < 3:
        raise ValueError("a frame plan needs a positive duration and at least three frames")
    anchors = [anchor for anchor in (HOT_SEGMENT_MS, PEAK_SEGMENT_MS) if anchor < duration_ms]
    slots = count - len(anchors)
    step = duration_ms / slots
    stamps = {int(step * (index + 0.5)) for index in range(slots)}
    stamps.update(anchors)
    if len(stamps) != count:
        raise ValueError(f"frame plan produced {len(stamps)} distinct timestamps, expected {count}")
    return sorted(stamps)


def extract_frames(config_path, out_dir, ffmpeg="ffmpeg", ffprobe="ffprobe"):
    doc = json.loads(Path(config_path).read_text(encoding="utf-8"))
    canon = next(m for m in doc["materials"] if m["key"] == "ysjf-canon")
    video = Path(canon["video_path"])
    if not video.is_file():
        raise SystemExit(f"formal video is missing: {video}")
    actual = _sha256_file(video)
    if actual != canon["binding"]["video_sha256"]:
        raise SystemExit("formal video content does not match the declared binding; refusing to sample")
    duration_ms = _probe(ffprobe, video)
    out_dir = Path(out_dir)
    (out_dir / "frames").mkdir(parents=True, exist_ok=True)
    frames = []
    for index, at_ms in enumerate(sample_timestamps(duration_ms)):
        target = out_dir / "frames" / f"frame-{index:02d}.jpg"
        command = [ffmpeg, "-v", "error", "-y", "-ss", f"{at_ms / 1000:.3f}", "-i", str(video),
                   "-frames:v", "1", "-q:v", "3", str(target)]
        subprocess.run(command, check=True, capture_output=True)
        if not target.is_file() or target.stat().st_size == 0:
            raise SystemExit(f"frame extraction produced no bytes at {at_ms} ms")
        frames.append({"index": index, "at_ms": at_ms, "path": f"frames/{target.name}",
                       "bytes": target.stat().st_size, "sha256": _sha256_file(target)})
    manifest = {
        "kind": "formal-frame-manifest",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": {"video_path": str(video), "video_sha256": actual, "duration_ms": duration_ms},
        "sampling": {"strategy": doc["tool_freeze"]["sampling_strategy"], "count": len(frames),
                     "tool_config_version": doc["tool_freeze"]["tool_config_version"],
                     "hot_segment_ms": [HOT_SEGMENT_MS, PEAK_SEGMENT_MS]},
        "frames": frames,
        "extractor": {"ffmpeg": ffmpeg, "version": _ffmpeg_version(ffmpeg),
                      "command_template": "ffmpeg -ss <ms/1000> -i <video> -frames:v 1 -q:v 3 <out>"},
        "provenance": "frames decoded from the formal Canon MP4; no synthetic frame is accepted here",
    }
    target_manifest = out_dir / "frames-manifest.json"
    target_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({"frames": len(frames), "duration_ms": duration_ms,
                      "manifest": str(target_manifest), "video_sha256": actual}, ensure_ascii=False))
    return 0


def _load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _unreadable_run(run_dir, error):
    """A wrong path or a broken manifest is a verdict, not a traceback: the operator needs to know
    which file was unreadable, and a script driving this must see a stable exit code."""
    verdict = {
        "kind": "report-preflight-verify",
        "verdict": "RUN_UNREADABLE",
        "run_dir": str(run_dir),
        "requests": 0, "reservations": 0, "settled": 0,
        "known_settled_fen": 0, "retained_fen": 0,
        "errors": [f"unreadable run directory: {error}"],
    }
    print(json.dumps(verdict, ensure_ascii=False))
    return 2


def _fen_bound(tokens, per_million):
    return _ceil_div(tokens * per_million, 1_000_000)


def _bind_dispatches(manifest, dispatches, requests, reservations):
    """Repair-01 strict reconciliation: bind each manifest dispatch to its ledger reservation and
    its request-log entry FIELD BY FIELD — exit, model, protocol path, request body digest, price
    identity, reserved amount, usage and settled amount. Every mismatch is an error naming the
    drifted field; a tampered run copy can never reconcile by count alone."""
    errors = []
    by_reservation = {}
    for record in reservations:
        rid = record.get("reservation_id")
        if rid in by_reservation:
            errors.append(f"ledger.reservation_id: duplicate reservation id {rid!r}")
        by_reservation[rid] = record
    run_id = manifest.get("run_id")
    controller = manifest.get("controller")
    if manifest.get("provider_dispatches") != len(dispatches):
        errors.append(f"manifest.provider_dispatches {manifest.get('provider_dispatches')!r} != "
                      f"dispatch table {len(dispatches)}")
    if len(dispatches) != len(requests):
        errors.append(f"dispatch table {len(dispatches)} != stub requests {len(requests)}")
    if len(reservations) != len(dispatches):
        errors.append(f"ledger reservations {len(reservations)} != dispatch table {len(dispatches)}")
    bound_ids = set()
    for index, dispatch in enumerate(dispatches):
        prefix = f"dispatches[{index}]"
        request = requests[index] if index < len(requests) else {}
        rid = dispatch.get("reservation_id")
        record = by_reservation.get(rid)
        if record is None:
            errors.append(f"{prefix}.reservation_id: {rid!r} not found in the ledger")
            continue
        bound_ids.add(rid)
        kind = dispatch.get("kind")
        # run attribution: the dispatch belongs to THIS manifest's run and controller
        if dispatch.get("run_id") != run_id:
            errors.append(f"{prefix}.run_id: dispatch attributed to {dispatch.get('run_id')!r}, "
                          f"the manifest run is {run_id!r}")
        if dispatch.get("controller") != controller:
            errors.append(f"{prefix}.controller: dispatch attributed to {dispatch.get('controller')!r}, "
                          f"the manifest controller is {controller!r}")
        # request-log identity: host, protocol path, model and the request body itself
        for field in ("index", "host", "path", "model", "body_sha256", "body_bytes"):
            if dispatch.get(field) != request.get(field):
                errors.append(f"{prefix}.{field}: manifest {dispatch.get(field)!r} != request log "
                              f"{request.get(field)!r}")
        # 4B-2A content repair: new-batch records carry a REQUIRED serialized-request digest
        # (64-hex, positive bytes) bound at the client send boundary, bound to the ledger
        # reservation, and equal on the dispatch and the request log. Missing, empty or
        # mismatched digests refuse the run. Manifests without the policy keep their historical
        # semantics and are reported at the lower ABSENT_LOW evidence level by the verdict.
        if manifest.get("request_digest_policy") == "required":
            digest = dispatch.get("body_sha256")
            body_bytes = dispatch.get("body_bytes")
            if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
                errors.append(f"{prefix}.body_sha256: the required policy needs a 64-hex lowercase "
                              f"digest, got {digest!r}")
            if not isinstance(body_bytes, int) or isinstance(body_bytes, bool) or body_bytes < 1:
                errors.append(f"{prefix}.body_bytes: the required policy needs a positive byte "
                              f"count, got {body_bytes!r}")
            ledger_digest = record.get("request_body_sha256")
            if ledger_digest != digest:
                errors.append(f"{prefix}.request_digest: ledger reservation digest {ledger_digest!r} "
                              f"!= dispatch digest {digest!r} (the send-boundary binding is missing "
                              f"or drifted)")
            # 4B-2A release-gates: the client boundary names the role; the stub's observed role
            # must AGREE (a missing stub role — production mode — is fine; a drift is not).
            # Byte count must be strictly positive (zero bytes are refused, never tolerated).
            client_role = dispatch.get("client_role")
            stub_role = request.get("observed_role")
            # vision dispatches MUST name the vision role from their own admission boundary
            if kind == "vision" and client_role != "vision":
                errors.append(f"{prefix}.client_role: a vision dispatch must carry the client-boundary "
                              f"role {json.dumps('vision')} (got {client_role!r})")
            if client_role is None and stub_role is None:
                errors.append(f"{prefix}.client_role: the required policy needs the client-boundary "
                              f"role recorded at the send boundary, got none")
            elif client_role is not None and stub_role is not None and client_role != stub_role:
                errors.append(f"{prefix}.role_drift: client boundary {client_role!r} != stub-observed "
                              f"{stub_role!r}")
            if isinstance(body_bytes, int) and not isinstance(body_bytes, bool) and body_bytes < 1:
                errors.append(f"{prefix}.body_bytes_zero: the required policy refuses zero/negative "
                              f"byte counts (got {body_bytes!r})")
        expected_path = EXIT_PATHS.get(kind)
        if expected_path is None:
            errors.append(f"{prefix}.kind: unknown dispatch kind {kind!r}")
        elif request.get("path") != expected_path:
            errors.append(f"{prefix}.path: request log path {request.get('path')!r} is not the "
                          f"documented {kind} exit {expected_path!r}")
        # exit identity: the ledger's own exit key must be rebuilt from the dispatch's identity,
        # and the ledger's model must be the dispatch's model (a re-labelled reservation is refused)
        model = dispatch.get("model")
        host = dispatch.get("host")
        if record.get("model") != model:
            errors.append(f"{prefix}.model: ledger model {record.get('model')!r} != dispatch model "
                          f"{model!r}")
        if kind == "chat":
            expected_exit = f"chat:{host}:{model}"
        elif kind == "vision":
            expected_exit = contracts.vision_exit_key(dispatch.get("provider"), model,
                                                      dispatch.get("transport"))
        else:
            expected_exit = None
        if expected_exit is not None and record.get("exit") != expected_exit:
            errors.append(f"{prefix}.exit: ledger exit {record.get('exit')!r} != dispatch identity "
                          f"{expected_exit!r}")
        # price identity: recompute the frozen snapshot hash from the ledger's own fields
        price_in = record.get("price_input_fen_per_million")
        price_out = record.get("price_output_fen_per_million")
        identity = hashlib.sha256(f"{record.get('exit')}|{price_in}|{price_out}".encode("utf-8")).hexdigest()
        if record.get("price_identity") != identity:
            errors.append(f"{prefix}.price_identity: ledger identity {record.get('price_identity')!r} "
                          f"does not match its own frozen snapshot {identity}")
        # reserved amount: recompute from the dispatch's MEASURED components, not a copied number.
        # Grounded-budget (v3): vision reservations are legitimate again, but only under the
        # measured formula (floor 202 + prompt envelope 1000 + images x 600). The withdrawn
        # price-refresh bytes-as-tokens formula can never reconcile.
        if kind == "chat":
            est_in, est_out = dispatch.get("payload_bytes"), dispatch.get("max_tokens")
        elif kind == "vision":
            est_in = contracts.VISION["request_text_floor_measured"] + contracts.VISION["prompt_chars_max"] \
                + (dispatch.get("image_count") or 0) * contracts.VISION["per_image_token_ceiling"]
            est_out = dispatch.get("max_output_tokens")
        else:
            est_in = est_out = None
        if not isinstance(est_in, int) or isinstance(est_in, bool) or est_in < 1 \
                or not isinstance(est_out, int) or isinstance(est_out, bool) or est_out < 1:
            errors.append(f"{prefix}.estimate: dispatch must carry positive measured components "
                          f"(input {est_in!r}, output {est_out!r})")
        else:
            expected_reserved = max(1, _fen_bound(est_in, price_in) + _fen_bound(est_out, price_out))
            if record.get("reserved_fen") != expected_reserved:
                errors.append(f"{prefix}.reserved_fen: ledger reserved {record.get('reserved_fen')!r} "
                              f"!= recomputed {expected_reserved} from the dispatch's measured components")
        # status/usage/settlement binding
        status = record.get("status")
        usage = request.get("usage")
        if status == "SETTLED":
            if not isinstance(usage, dict) or not isinstance(usage.get("prompt_tokens"), int) \
                    or isinstance(usage.get("prompt_tokens"), bool) \
                    or not isinstance(usage.get("completion_tokens"), int) \
                    or isinstance(usage.get("completion_tokens"), bool):
                errors.append(f"{prefix}.usage: a SETTLED reservation needs the request log's parsed "
                              f"usage, got {usage!r}")
            else:
                prompt, completion = usage["prompt_tokens"], usage["completion_tokens"]
                if record.get("usage_tokens") != prompt + completion:
                    errors.append(f"{prefix}.usage_tokens: ledger {record.get('usage_tokens')!r} != "
                                  f"request log {prompt + completion}")
                expected_actual = max(1, _fen_bound(prompt, price_in) + _fen_bound(completion, price_out))
                if record.get("actual_fen") != expected_actual:
                    errors.append(f"{prefix}.actual_fen: ledger {record.get('actual_fen')!r} != "
                                  f"recomputed {expected_actual} from the request log's usage")
        elif status == "USAGE_UNKNOWN":
            if isinstance(usage, dict):
                errors.append(f"{prefix}.status: USAGE_UNKNOWN but the request log carries a parsed "
                              f"usage {usage!r}")
            if record.get("usage_tokens") is not None or record.get("actual_fen") is not None:
                errors.append(f"{prefix}.status: USAGE_UNKNOWN must keep usage_tokens/actual_fen null")
            if not manifest.get("expected_unknown_usage"):
                errors.append(f"{prefix}.expected_unknown_usage: a USAGE_UNKNOWN dispatch must be "
                              "declared by the manifest")
        elif status == "FAILED_RETAINED":
            if request.get("response_status") == 200 and not request.get("timed_out"):
                errors.append(f"{prefix}.status: FAILED_RETAINED but the request log shows a served "
                              "200 response")
            if not record.get("fail_reason"):
                errors.append(f"{prefix}.fail_reason: FAILED_RETAINED must carry the failure reason")
        elif status == "OPEN":
            errors.append(f"{prefix}.status: an OPEN reservation must be settled, failed or declared "
                          "unknown before verification")
        else:
            errors.append(f"{prefix}.status: unknown reservation status {status!r}")
    for rid in by_reservation:
        if rid not in bound_ids:
            errors.append(f"ledger.reservation_id: {rid!r} is not bound to any manifest dispatch")
    return errors


def verify_run(run_dir, ledger_path=None, requests_path=None):
    """Reconcile a run manifest with its ledger and the stub's own request log. Runs carrying a
    manifest `dispatches` table get the strict field-by-field binding; older evidence without a
    table keeps its historical count-only reconciliation semantics."""
    run_dir = Path(run_dir)
    try:
        manifest = _load_json(run_dir / "manifest.json")
        ledger = _load_json(Path(ledger_path) if ledger_path else run_dir / "fee-ledger.json")
    except (OSError, ValueError) as error:
        return _unreadable_run(run_dir, error)
    requests = []
    request_file = Path(requests_path) if requests_path else run_dir / "requests.jsonl"
    if request_file.is_file():
        try:
            requests = [json.loads(line) for line in request_file.read_text(encoding="utf-8").splitlines()
                        if line.strip()]
        except ValueError as error:
            return _unreadable_run(run_dir, f"requests.jsonl: {error}")
    reservations = ledger.get("reservations", [])
    reservations_total = len(reservations)
    errors = []
    dispatches = manifest.get("dispatches")
    if isinstance(dispatches, list):
        # 4B-2A content repair: a manifest declaring batch_ledger_shared=true reconciles against
        # a CUMULATIVE batch envelope that also holds sibling runs' reservations — scope the
        # binding to this run's own dispatch-referenced reservations, and report both counts.
        batch_shared = manifest.get("batch_ledger_shared") is True
        if batch_shared:
            run_ids = {d.get("reservation_id") for d in dispatches}
            reservations = [r for r in reservations if r.get("reservation_id") in run_ids]
            unknown_siblings = reservations_total - len(reservations)
            if unknown_siblings < 0:
                errors.append("batch_ledger_shared: the dispatch table references more "
                              "reservations than the ledger carries")
        errors.extend(_bind_dispatches(manifest, dispatches, requests, reservations))
        settled = [r for r in reservations if r.get("status") == "SETTLED"]
        unknown = [r for r in reservations if r.get("status") == "USAGE_UNKNOWN"]
        retained = [r for r in reservations if r.get("status") == "FAILED_RETAINED"]
        bound_counts = {"bound_dispatches": len(dispatches), "unknown_usage": len(unknown),
                        "retained_dispatches": len(retained)}
    else:
        if manifest.get("provider_dispatches") != len(requests):
            errors.append(f"manifest provider_dispatches {manifest.get('provider_dispatches')} != stub requests {len(requests)}")
        if len(reservations) != len(requests):
            errors.append(f"ledger reservations {len(reservations)} != stub requests {len(requests)}")
        settled = [r for r in reservations if r.get("status") == "SETTLED"]
        unknown = []
        retained = [r for r in reservations if r.get("status") not in (None, "SETTLED")]
        if manifest.get("provider_dispatches") != len(settled) and not manifest.get("expected_unknown_usage"):
            errors.append(f"settled {len(settled)} != dispatches {manifest.get('provider_dispatches')}")
        bound_counts = {"bound_dispatches": len(dispatches) if isinstance(dispatches, list) else None,
                        "unknown_usage": 0, "retained_dispatches": len(retained)}
    for frame in manifest.get("frames", []):
        path = run_dir / frame["path"]
        if not path.is_file():
            errors.append(f"frame missing: {frame['path']}")
            continue
        if _sha256_file(path) != frame["sha256"]:
            errors.append(f"frame digest changed: {frame['path']}")
    actual = sum(r.get("actual_fen") or 0 for r in reservations)
    retained_fen = sum(r.get("reserved_fen") or 0 for r in reservations if r.get("status") != "SETTLED")
    digest_required = (isinstance(dispatches, list)
                       and isinstance(manifest.get("request_digest_policy"), str)
                       and manifest.get("request_digest_policy") == "required")
    verdict = {
        "kind": "report-preflight-verify",
        "verdict": "RUN_RECONCILED" if not errors else "RUN_MISMATCH",
        "requests": len(requests),
        "reservations": len(reservations),
        "settled": len(settled),
        "known_settled_fen": actual,
        "retained_fen": retained_fen,
        # 4B-2A content repair: BOUND = every dispatch carries a verified send-boundary digest;
        # ABSENT_LOW = a historical record whose digests stay null on purpose — the evidence
        # level is lower BY RECORD, the values are never rewritten.
        "request_digest_evidence": "BOUND" if digest_required else "ABSENT_LOW",
        "reservations_total": reservations_total,
        "batch_ledger_shared": manifest.get("batch_ledger_shared") is True,
        **bound_counts,
        "errors": errors,
    }
    carried = ledger.get("carried_in")
    if isinstance(carried, dict):
        verdict["carried_in_fen"] = (carried.get("settled_fen") or 0) + (carried.get("retained_fen") or 0)
    print(json.dumps(verdict, ensure_ascii=False))
    return 0 if not errors else 4


def build_readiness(run_report=None):
    """The pre-pay sheet as data: known / locally tested / to verify / blocked. Nothing is claimed
    as connected: account permission and real provider compatibility stay unverified by design."""
    report = _load_json(run_report) if run_report else {}
    doc = {
        "kind": "report-live-readiness",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "contract_version": contracts.CONTRACT_VERSION,
        "account_permission_verified": False,
        "real_provider_call_count": 0,
        "real_spend_cny": 0,
        "known_from_public_docs": contracts.readiness_facts(),
        "locally_tested": report.get("locally_tested", []),
        "to_verify_with_account": [
            {"item": "controller request names resolve and are served", "blocking_for_live": True,
             "how": "one minimal chat completion per controller, read response.model"},
            {"item": "siliconflow accepts enable_thinking=false on both controllers", "blocking_for_live": True,
             "how": "same minimal request; a 400 means the field mapping must be re-read"},
            {"item": "native tool pairing on both controllers", "blocking_for_live": True,
             "how": "one tool round-trip; finish_reason=tool_calls then stop"},
            {"item": "MiniMax-M3 generic image input with thinking.disabled and detail=low", "blocking_for_live": True,
             "how": "one request with one real frame; read usage.prompt_tokens"},
            {"item": "vision usage reports image tokens in prompt_tokens", "blocking_for_live": True,
             "how": "compare usage.prompt_tokens against the text-only request"},
            {"item": "account balance covers the 20 CNY pause line", "blocking_for_live": True,
             "how": "read the provider console before the first live run"},
        ],
        "blocked": [
            {"item": blocked["exit"], "reason": blocked["reason"]} for blocked in contracts.BLOCKED_EXITS
        ] + [{"item": "product entry joined with real clients in one process",
              "reason": "the entry leg is the accepted integration test; the provider leg is this driver; "
                        "the single-process join is the first live step and is not claimed as done"}],
        "fee_envelope": {"hard_limit_fen": 5000, "pause_fen": 2000, "shared_by": "both providers, all roles"},
        "live_wiring": [
            {"item": "declare agent.models.protocol when the base URL is not an official host",
             "why": "auto classifies only api.deepseek.com and api.siliconflow.cn; anything else "
                    "(including the loopback rehearsal double) resolves to none and sends neither "
                    "thinking field, so the rehearsal would not exercise the declared wire format",
             "value": "siliconflow for this slice"},
            {"item": "declare agent.models.vision.transport=openai_chat_vision with provider=minimax",
             "why": "auto maps provider=minimax to the dedicated /v1/coding_plan/vlm exit, which has no "
                    "reproducible bound and stays refused by the fee ledger",
             "value": "openai_chat_vision + model MiniMax-M3"},
            {"item": "chat exit keys in a stub ledger name the loopback host",
             "why": "the exit key is the host the bytes go to; a live ledger names api.siliconflow.cn and "
                    "the price row must exist there before the dispatch, not after",
             "value": "do not reuse a stub ledger as a live price source"},
        ],
        "credential_injection": "main conversation injects the MiniMax key at run time; this tree never reads it",
        "safe_to_pay": False,
        "note": "local green never implies real compatibility; no real request was made in this slice",
    }
    return doc


def cmd_readiness(args):
    doc = build_readiness(args.run_report)
    target = Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({"output": str(target), "safe_to_pay": doc["safe_to_pay"],
                      "known": len(doc["known_from_public_docs"]),
                      "blocking_live": len([i for i in doc["to_verify_with_account"] if i["blocking_for_live"]])},
                     ensure_ascii=False))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("plan", help="Dry-run plan and fee ceiling for the paired first slice")
    p.add_argument("--controllers", type=int, default=2)
    p.add_argument("--repeats", type=int, default=1)
    p.add_argument("--materials", type=int, default=1)
    p.add_argument("--frames-manifest", default=None,
                   help="price the vision ceiling against real frames instead of the 20 KB placeholder")
    p.add_argument("--output", default=None)
    p.set_defaults(func=lambda a: _plan(a))

    p = sub.add_parser("frames", help="Extract real sampled frames from the formal Canon MP4")
    p.add_argument("--config", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--ffmpeg", default="ffmpeg")
    p.add_argument("--ffprobe", default="ffprobe")
    p.set_defaults(func=lambda a: extract_frames(a.config, a.output, a.ffmpeg, a.ffprobe))

    p = sub.add_parser("verify", help="Reconcile a run directory with its ledger and stub request log")
    p.add_argument("run_dir")
    p.add_argument("--ledger", default=None)
    p.add_argument("--requests", default=None)
    p.set_defaults(func=lambda a: verify_run(a.run_dir, a.ledger, a.requests))

    p = sub.add_parser("readiness", help="Write the pre-pay readiness sheet")
    p.add_argument("--run-report", default=None)
    p.add_argument("--output", required=True)
    p.set_defaults(func=cmd_readiness)

    p = sub.add_parser("contracts", help="Print the frozen provider contract registry")
    p.add_argument("--output", default=None)
    p.set_defaults(func=lambda a: _contracts(a))

    args = parser.parse_args(argv)
    return args.func(args)


def _plan(args):
    plan = build_plan(args.controllers, args.repeats, args.materials, args.frames_manifest)
    text = json.dumps(plan, ensure_ascii=False, indent=1)
    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(text, encoding="utf-8")
    print(json.dumps({k: plan[k] for k in ("runs", "provider_dispatches", "verdict",
                                           "vision_fee_state", "fee_upper_fen",
                                           "vision_fen_per_run", "vision_payload_source",
                                           "live_dispatch_enabled")},
                     ensure_ascii=False))
    return 0


def _contracts(args):
    doc = {"contract_version": contracts.CONTRACT_VERSION, "controllers": contracts.CONTROLLERS,
           "fixed_roles": contracts.FIXED_ROLES, "vision": contracts.VISION,
           "blocked": contracts.BLOCKED_EXITS, "sources": contracts.PRIMARY_SOURCES}
    text = json.dumps(doc, ensure_ascii=False, indent=1)
    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(text, encoding="utf-8")
    print(json.dumps({"contract_version": doc["contract_version"],
                      "controllers": [c["model_request_name"] for c in doc["controllers"]],
                      "vision": doc["vision"]["model_request_name"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
