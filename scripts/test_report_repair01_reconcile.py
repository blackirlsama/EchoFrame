"""4B-2A Repair-01: per-controller budget plan, strict run reconciliation, catalog keys.

Written against the REQUIRED behaviour before the implementation exists: the first run is the
recorded first failure (evidence root first-fail/py-firstfail.log). Nothing here contacts a
network or reads a credential; the gold 7080/4500 figures are the main dialog's declared-envelope
arithmetic anchors, never a pass threshold or a relaxed budget.
"""
import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from report_evaluation.admission import contracts

REPO = Path(__file__).resolve().parents[1]
# The formal Canon frame manifest measured at the 4B-2A initial round (frames/frames-manifest.json,
# video_sha256 1e9f5d4b...); the raw byte totals below were recomputed from it on 2026-11-18 and are
# embedded so this anchor stays deterministic without coupling tests to evidence directories.
FRAME_RAW_TOTAL_12 = 905_198      # sum of all 12 frames' raw JPEG bytes
FRAME_RAW_TOTAL_6 = 522_907       # subset [0,3,4,5,8,11] (both anchors included)
TEXT_OVERHEAD_BYTES = 4_096       # measure_vision_payload's declared text overhead

# The OLD declared envelopes the main dialog's gold figures were computed under (planner state at
# round start). Kept as explicit constants so the anchor survives the envelope realignment.
OLD_ENVELOPE = {
    "preparation": (4_000, 384),
    "harness": (12_000, 1_024),
    "analysis": (20_000, 2_048),
    "vision_output": 2_048,
    "harness_dispatches": 6,
}


def _ceil_div(a, b):
    return -(-a // b)


def _fen(tokens, per_million):
    return _ceil_div(tokens * per_million, 1_000_000)


def _old_vision_fen(total_raw_bytes):
    encoded = (total_raw_bytes * 4 + 2) // 3
    payload = encoded + TEXT_OVERHEAD_BYTES
    input_tokens = payload + 12 * contracts.VISION["per_image_token_ceiling"]
    return (_fen(input_tokens, contracts.VISION["price_input_fen_per_million"])
            + _fen(OLD_ENVELOPE["vision_output"], contracts.VISION["price_output_fen_per_million"]))


def _old_run_fen(controller_index, vision_fen):
    """The old planner's arithmetic: the harness role follows the CONTROLLER's own price, while the
    fixed roles (preparation/analysis) are priced at controller-a for BOTH controllers. prep 1
    dispatch, harness 6, analysis 1, vision 1."""
    controller = contracts.CONTROLLERS[controller_index]
    fixed = contracts.CONTROLLERS[0]  # the planner prices fixed roles at controller-a
    prep_in, prep_out = OLD_ENVELOPE["preparation"]
    harness_in, harness_out = OLD_ENVELOPE["harness"]
    prep = _fen(prep_in, fixed["price_input_fen_per_million"]) + \
        _fen(prep_out, fixed["price_output_fen_per_million"])
    harness = (_fen(harness_in, controller["price_input_fen_per_million"])
               + _fen(harness_out, controller["price_output_fen_per_million"])) * OLD_ENVELOPE["harness_dispatches"]
    analysis = 6  # (20000, 2048) at controller-a prices: ceil(4.0)=4 + ceil(1.6384)=2
    return prep + harness + analysis + vision_fen


class GoldArithmeticAnchorTest(unittest.TestCase):
    """Reproduces the main dialog's declared-envelope figures: 12-frame matrix 7080 fen, 6-frame
    subset 4500 fen, split evenly across the two controllers (548/632 and 333/417 per run). These
    are reference anchors for the PRE price-refresh state — not a budget approval, not a pass
    threshold. Price-refresh verdict (2026-11-19): the 516-fen vision component inside these
    figures priced transport bytes as input tokens and is unfounded; the anchors stay only to
    document where the old numbers came from, and the planner no longer emits such totals."""

    def test_old_envelope_twelve_frame_matrix_is_7080_fen(self):
        vision = _old_vision_fen(FRAME_RAW_TOTAL_12)
        per_a = _old_run_fen(0, vision)
        per_b = _old_run_fen(1, vision)
        self.assertEqual(516, vision)
        self.assertEqual(548, per_a)
        self.assertEqual(632, per_b)
        self.assertEqual(7080, 6 * per_a + 6 * per_b)

    def test_old_envelope_six_frame_subset_recomputation(self):
        """Reliable recomputation of the 6-frame subset [0,3,4,5,8,11] under the SAME declared
        envelopes and the planner's own payload formula: 302 fen vision per run (the main dialog's
        quick reference said 301, a 1-fen rounding slip per run, +12 fen over the 12-run matrix —
        documented in the round report; never a budget approval or pass threshold)."""
        vision = _old_vision_fen(FRAME_RAW_TOTAL_6)
        per_a = _old_run_fen(0, vision)
        per_b = _old_run_fen(1, vision)
        self.assertEqual(302, vision)
        self.assertEqual(334, per_a)
        self.assertEqual(418, per_b)
        self.assertEqual(4512, 6 * per_a + 6 * per_b)

    def test_the_current_planner_claims_the_grounded_closed_total(self):
        """Grounded-budget verdict: the planner prices each controller's chat legs at its own list
        price and the vision leg at the measured grounded bound (8 fen per run), so a closed
        total exists again — and it is fen-scale, not the withdrawn bytes-as-tokens scale."""
        from report_preflight_run import build_plan
        frames = [{"index": i, "bytes": FRAME_RAW_TOTAL_12 // 12} for i in range(12)]
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "m.json"
            manifest.write_text(json.dumps({"frames": frames}), encoding="utf-8")
            plan = build_plan(controllers=2, repeats=1, materials=1, frames_manifest=manifest)
        self.assertEqual("PLANNED_GROUNDED_ESTIMATE", plan["verdict"])
        breakdown = {entry["controller_id"]: entry["chat_fen_per_run"] for entry in plan["controllers_breakdown"]}
        # 4B-2A live-readiness ALIGNED envelope: input = runtime byte cap (65536), counts =
        # loop maxModelCalls (16), preparation fixture 0, analysis upper bound 12
        # controller-a chat: harness 16x(14+3)=272 + analysis 12x(14+14)=336 = 608
        # controller-b chat: harness 16x(79+8)=1392 + analysis 12x28=336 = 1728
        self.assertEqual(608, breakdown["controller-a"])
        self.assertEqual(1728, breakdown["controller-b"])
        # first pair closed total (isolated-cache worst case): chat 2336 + vision 6 x 2 runs
        self.assertEqual(2348, plan["fee_upper_fen"])
        self.assertEqual(6, plan["vision_fen_per_run"])


class PlannerPerControllerTest(unittest.TestCase):
    """The plan must price each controller's CHAT legs with its OWN public list price and say so;
    the vision leg prices at the same grounded bound for every run, so per-run totals close."""

    def test_plan_reports_a_per_controller_breakdown(self):
        from report_preflight_run import build_plan
        plan = build_plan(controllers=2, repeats=1, materials=1)
        breakdown = plan.get("controllers_breakdown")
        self.assertIsInstance(breakdown, list)
        self.assertEqual(2, len(breakdown))
        by_controller = {entry["controller_id"]: entry for entry in breakdown}
        self.assertIn("controller-a", by_controller)
        self.assertIn("controller-b", by_controller)
        def harness_fen(entry):
            return next(role["fen"] for role in entry["roles"] if role["role"] == "harness")
        # controller-b bills strictly more for the identical declared harness envelope
        self.assertGreater(harness_fen(by_controller["controller-b"]),
                           harness_fen(by_controller["controller-a"]))
        a = contracts.controller_by_name("deepseek-ai/DeepSeek-V4.1-Flash")
        b = contracts.controller_by_name("deepseek-ai/DeepSeek-V4-Pro")
        self.assertEqual(a["price_input_fen_per_million"], by_controller["controller-a"]["price_input_fen_per_million"])
        self.assertEqual(b["price_input_fen_per_million"], by_controller["controller-b"]["price_input_fen_per_million"])
        # grounded vision 6 fen/run closes each controller's per-run total: 608+6=614, 1728+6=1734
        self.assertEqual(by_controller["controller-a"]["per_run_fen"], 614)
        self.assertEqual(by_controller["controller-b"]["per_run_fen"], 1734)
        self.assertEqual(by_controller["controller-a"]["chat_fen_per_run"], 608)
        self.assertEqual(by_controller["controller-b"]["chat_fen_per_run"], 1728)
        self.assertEqual(by_controller["controller-b"]["per_run_fen"], plan["fee_upper_fen_per_run"])

    def test_twelve_run_matrix_is_refused_at_the_aligned_hard_limit(self):
        from report_preflight_run import build_plan
        frames = [{"index": i, "bytes": FRAME_RAW_TOTAL_12 // 12} for i in range(12)]
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "m.json"
            manifest.write_text(json.dumps({"frames": frames}), encoding="utf-8")
            # ALIGNED (2026-11-19): the honest 12-run matrix at runtime-enforced ceilings is
            # 6 x 2348 = 14088 fen — above the 50 CNY hard limit, so the planner REFUSES it
            with self.assertRaises(ValueError):
                build_plan(controllers=2, repeats=6, materials=1, frames_manifest=manifest)
            # the first PAIR plans and stays far under the hard limit
            pair = build_plan(controllers=2, repeats=1, materials=1, frames_manifest=manifest)
        self.assertEqual("PLANNED_GROUNDED_ESTIMATE", pair["verdict"])
        self.assertEqual(6, pair["vision_fen_per_run"])
        self.assertLess(pair["fee_upper_fen"], pair["hard_limit_fen"])
        self.assertFalse(pair["live_dispatch_enabled"])


class PlannerEnvelopeSourceTest(unittest.TestCase):
    """The planner's role envelopes must come from the SAME declared values the report entry runs
    with (DeepSeekProperties defaults), not from a private smaller table."""

    def _java_default(self, source, field):
        import re
        match = re.search(r'@DefaultValue\("([^"]+)"\)\s+int\s+' + field, source)
        self.assertIsNotNone(match, f"@DefaultValue for {field} not found in DeepSeekProperties")
        return int(match.group(1))

    def test_output_caps_match_the_entrys_declared_defaults(self):
        from report_preflight_run import ENVELOPE
        source = (REPO / "agent/src/main/java/com/tianmu/agent/model/deepseek/DeepSeekProperties.java"
                  ).read_text(encoding="utf-8")
        self.assertEqual(self._java_default(source, "preparationMaxTokens"), ENVELOPE["preparation"][1])
        self.assertEqual(self._java_default(source, "harnessMaxTokens"), ENVELOPE["harness"][1])
        self.assertEqual(self._java_default(source, "analysisMaxTokens"), ENVELOPE["analysis"][1])

    def test_analysis_output_cap_is_the_fixed_role_declaration(self):
        from report_preflight_run import ENVELOPE
        self.assertEqual(contracts.fixed_role("analysis")["max_output_tokens"], ENVELOPE["analysis"][1],
                         "the planner must not understate the fixed analysis output cap")


class PerMaterialPlanTest(unittest.TestCase):
    """Grounded-budget semantics: the vision FEE is bounded by the measured floor + prompt
    envelope + the documented per-image estimate, and the sampled image count is 12 per material
    BY DESIGN — so every material closes, and frame manifests matter only for transport evidence.
    The canon payload is still never reused as a stand-in for the other materials' transport
    record: unmeasured materials carry no payload bytes."""

    def test_three_material_plan_closes_at_the_grounded_bound(self):
        from report_preflight_run import build_plan
        frames = [{"index": i, "bytes": FRAME_RAW_TOTAL_12 // 12} for i in range(12)]
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "m.json"
            manifest.write_text(json.dumps({"frames": frames}), encoding="utf-8")
            # ALIGNED (2026-11-19): the honest three-material matrix at runtime-enforced
            # ceilings exceeds the 50 CNY hard limit and is REFUSED — per-batch planning
            # under the batch cap governs real work
            with self.assertRaises(ValueError):
                build_plan(controllers=2, repeats=2, materials=3, frames_manifests={
                    "ysjf-canon": manifest, "hxc-doufu": None, "my-dragon": None})
            # the canon FIRST PAIR plans, carries its measured transport payload, and the
            # vision bound prices the ACTUAL in-window 2-frame list
            pair = build_plan(controllers=2, repeats=1, materials=1, frames_manifest=manifest)
        self.assertEqual("PLANNED_GROUNDED_ESTIMATE", pair["verdict"])
        materials = {entry["material_key"]: entry for entry in pair["materials_breakdown"]}
        entry = materials["ysjf-canon"]
        self.assertEqual("GROUNDED_BOUND", entry["vision_state"])
        self.assertEqual("GROUNDED_ESTIMATE", entry["vision_fee_state"])
        self.assertEqual(6, entry["vision_fen_per_run"])
        # the manifest lists the whole 12-frame extraction as TRANSPORT evidence; the FEE prices
        # the ACTUAL in-window 2-frame list (asserted via vision_fen_per_run == 6 above)
        self.assertEqual(12, entry["vision_images"])
        self.assertIsNotNone(entry["vision_payload_bytes"])
        self.assertEqual(0, pair["unknown_vision_runs"])
        self.assertFalse(pair["live_dispatch_enabled"])


class ReconcilerBindingTest(unittest.TestCase):
    """verify_run must bind ledger <-> request log <-> manifest dispatch table field by field:
    exit, model, path, body digest, price identity, reserved amount, usage and settled amount.
    Any single-field tamper must exit 4 naming the field."""

    RESERVATION = "fee-0123456789abcdef"

    def _entry(self, tmp):
        run = Path(tmp) / "run"
        run.mkdir(parents=True)
        model_a = "deepseek-ai/DeepSeek-V4.1-Flash"
        host = "127.0.0.1"
        exit_key = f"chat:{host}:{model_a}"
        price_in, price_out = 200, 800
        payload_bytes, max_tokens = 4000, 384
        reserved = _fen(payload_bytes, price_in) + _fen(max_tokens, price_out)
        identity = hashlib.sha256(f"{exit_key}|{price_in}|{price_out}".encode("utf-8")).hexdigest()
        prompt_tokens, completion_tokens = 1200, 300
        actual = max(1, _fen(prompt_tokens, price_in) + _fen(completion_tokens, price_out))
        dispatch = {
            "index": 0, "kind": "chat", "model": model_a, "host": host,
            "path": "/chat/completions", "body_bytes": 1234,
            "body_sha256": hashlib.sha256(b"request-bytes").hexdigest(),
            "reservation_id": self.RESERVATION, "payload_bytes": payload_bytes,
            "max_tokens": max_tokens, "controller": "controller-a", "run_id": "run-1",
        }
        request = {
            "index": 0, "host": host, "path": "/chat/completions", "model": model_a,
            "body_bytes": 1234, "body_sha256": dispatch["body_sha256"],
            "response_status": 200, "timed_out": False,
            "usage": {"prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens},
        }
        ledger = {"schema": "fee-ledger-v2", "total_fen": 5000, "pause_fen": 2000, "reservations": [{
            "reservation_id": self.RESERVATION, "exit": exit_key, "model": model_a,
            "status": "SETTLED", "reserved_fen": reserved, "usage_tokens": prompt_tokens + completion_tokens,
            "actual_fen": actual, "price_identity": identity,
            "price_input_fen_per_million": price_in, "price_output_fen_per_million": price_out,
            "attribution": "direct", "admitted_at": "2026-11-18T00:00:00Z"}]}
        manifest = {"provider_dispatches": 1, "run_id": "run-1", "controller": "controller-a",
                    "frames": [], "dispatches": [dispatch]}
        (run / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (run / "fee-ledger.json").write_text(json.dumps(ledger), encoding="utf-8")
        (run / "requests.jsonl").write_text(json.dumps(request) + "\n", encoding="utf-8")
        return run

    def _verify(self, run):
        from report_preflight_run import verify_run
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = verify_run(run)
        return code, json.loads(buffer.getvalue())

    # ---- group 1: normal ------------------------------------------------------------

    def test_a_fully_bound_run_reconciles(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, verdict = self._verify(self._entry(tmp))
        self.assertEqual(0, code, verdict)
        self.assertEqual("RUN_RECONCILED", verdict["verdict"])
        self.assertEqual(1, verdict["bound_dispatches"])

    def test_a_vision_dispatch_reconciles_at_the_grounded_bound(self):
        """Grounded-budget reconciliation: a vision dispatch's reserved amount is recomputed as
        floor 202 + prompt envelope 1000 + images x 600 at the frozen prices (2 images -> 6 fen),
        and its settlement still comes from the request log's usage."""
        import hashlib as _hashlib
        run = Path(tempfile.mkdtemp()) / "run"
        run.mkdir(parents=True)
        model, host = "MiniMax-M3", "127.0.0.1"
        exit_key = f"vision:minimax:{model}:openai_chat_vision"
        price_in, price_out = contracts.VISION["price_input_fen_per_million"], \
            contracts.VISION["price_output_fen_per_million"]
        image_count, max_output = 2, 2048
        est_in = contracts.VISION["request_text_floor_measured"] + contracts.VISION["prompt_chars_max"] \
            + image_count * contracts.VISION["per_image_token_ceiling"]
        reserved = _fen(est_in, price_in) + _fen(max_output, price_out)
        self.assertEqual(6, reserved)
        identity = _hashlib.sha256(f"{exit_key}|{price_in}|{price_out}".encode("utf-8")).hexdigest()
        prompt_tokens, completion_tokens = 800, 300
        actual = max(1, _fen(prompt_tokens, price_in) + _fen(completion_tokens, price_out))
        dispatch = {
            "index": 0, "kind": "vision", "model": model, "host": host,
            "path": "/v1/chat/completions", "body_bytes": 23456,
            "body_sha256": _hashlib.sha256(b"vision-bytes").hexdigest(),
            "reservation_id": self.RESERVATION, "provider": "minimax",
            "transport": "openai_chat_vision", "image_count": image_count,
            "payload_bytes": 23456, "max_output_tokens": max_output,
            "controller": "controller-a", "run_id": "run-1",
        }
        request = {
            "index": 0, "host": host, "path": "/v1/chat/completions", "model": model,
            "body_bytes": dispatch["body_bytes"], "body_sha256": dispatch["body_sha256"],
            "response_status": 200, "timed_out": False,
            "usage": {"prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens},
        }
        ledger = {"schema": "fee-ledger-v2", "total_fen": 5000, "pause_fen": 2000, "reservations": [{
            "reservation_id": self.RESERVATION, "exit": exit_key, "model": model,
            "status": "SETTLED", "reserved_fen": reserved, "usage_tokens": prompt_tokens + completion_tokens,
            "actual_fen": actual, "price_identity": identity,
            "price_input_fen_per_million": price_in, "price_output_fen_per_million": price_out,
            "attribution": "vision", "admitted_at": "2026-11-19T00:00:00Z"}]}
        manifest = {"provider_dispatches": 1, "run_id": "run-1", "controller": "controller-a",
                    "frames": [], "dispatches": [dispatch]}
        (run / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (run / "fee-ledger.json").write_text(json.dumps(ledger), encoding="utf-8")
        (run / "requests.jsonl").write_text(json.dumps(request) + "\n", encoding="utf-8")
        code, verdict = self._verify(run)
        self.assertEqual(0, code, verdict)
        self.assertEqual("RUN_RECONCILED", verdict["verdict"])

    def test_a_bytes_reserved_vision_dispatch_is_refused(self):
        """The withdrawn price-refresh formula (transport bytes + images x 600) must never
        reconcile: reserved amounts computed that way are unfounded and the run mismatches."""
        import hashlib as _hashlib
        run = Path(tempfile.mkdtemp()) / "run"
        run.mkdir(parents=True)
        model, host = "MiniMax-M3", "127.0.0.1"
        exit_key = f"vision:minimax:{model}:openai_chat_vision"
        price_in, price_out = contracts.VISION["price_input_fen_per_million"], \
            contracts.VISION["price_output_fen_per_million"]
        image_count, payload_bytes, max_output = 2, 240_000, 2048
        # the withdrawn formula: payload bytes treated as input tokens
        reserved = _fen(payload_bytes + image_count * contracts.VISION["per_image_token_ceiling"],
                        price_in) + _fen(max_output, price_out)
        identity = _hashlib.sha256(f"{exit_key}|{price_in}|{price_out}".encode("utf-8")).hexdigest()
        prompt_tokens, completion_tokens = 800, 300
        actual = max(1, _fen(prompt_tokens, price_in) + _fen(completion_tokens, price_out))
        dispatch = {
            "index": 0, "kind": "vision", "model": model, "host": host,
            "path": "/v1/chat/completions", "body_bytes": payload_bytes,
            "body_sha256": _hashlib.sha256(b"vision-bytes").hexdigest(),
            "reservation_id": self.RESERVATION, "provider": "minimax",
            "transport": "openai_chat_vision", "image_count": image_count,
            "payload_bytes": payload_bytes, "max_output_tokens": max_output,
            "controller": "controller-a", "run_id": "run-1",
        }
        request = {
            "index": 0, "host": host, "path": "/v1/chat/completions", "model": model,
            "body_bytes": payload_bytes, "body_sha256": dispatch["body_sha256"],
            "response_status": 200, "timed_out": False,
            "usage": {"prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens},
        }
        ledger = {"schema": "fee-ledger-v2", "total_fen": 5000, "pause_fen": 2000, "reservations": [{
            "reservation_id": self.RESERVATION, "exit": exit_key, "model": model,
            "status": "SETTLED", "reserved_fen": reserved, "usage_tokens": prompt_tokens + completion_tokens,
            "actual_fen": actual, "price_identity": identity,
            "price_input_fen_per_million": price_in, "price_output_fen_per_million": price_out,
            "attribution": "vision", "admitted_at": "2026-11-19T00:00:00Z"}]}
        manifest = {"provider_dispatches": 1, "run_id": "run-1", "controller": "controller-a",
                    "frames": [], "dispatches": [dispatch]}
        (run / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (run / "fee-ledger.json").write_text(json.dumps(ledger), encoding="utf-8")
        (run / "requests.jsonl").write_text(json.dumps(request) + "\n", encoding="utf-8")
        code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertEqual("RUN_MISMATCH", verdict["verdict"])
        self.assertTrue(any("reserved_fen" in error for error in verdict["errors"]), verdict)

    # ---- group 2: tampered identity -------------------------------------------------

    def test_tampered_ledger_model_is_refused_with_field_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            doc = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
            doc["reservations"][0]["model"] = "deepseek-ai/DeepSeek-V4-Pro"
            (run / "fee-ledger.json").write_text(json.dumps(doc), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("model" in e for e in verdict["errors"]), verdict)

    def test_tampered_request_log_model_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            lines = (run / "requests.jsonl").read_text(encoding="utf-8").splitlines()
            entry = json.loads(lines[0])
            entry["model"] = "other-model"
            (run / "requests.jsonl").write_text(json.dumps(entry) + "\n", encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("model" in e for e in verdict["errors"]), verdict)

    def test_tampered_exit_host_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            doc = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
            doc["reservations"][0]["exit"] = "chat:api.siliconflow.cn:deepseek-ai/DeepSeek-V4.1-Flash"
            (run / "fee-ledger.json").write_text(json.dumps(doc), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("exit" in e for e in verdict["errors"]), verdict)

    def test_tampered_request_body_digest_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            lines = (run / "requests.jsonl").read_text(encoding="utf-8").splitlines()
            entry = json.loads(lines[0])
            entry["body_sha256"] = "0" * 64
            (run / "requests.jsonl").write_text(json.dumps(entry) + "\n", encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("body_sha256" in e for e in verdict["errors"]), verdict)

    def test_tampered_price_identity_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            doc = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
            doc["reservations"][0]["price_identity"] = "1" * 64
            (run / "fee-ledger.json").write_text(json.dumps(doc), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("price_identity" in e for e in verdict["errors"]), verdict)

    # ---- group 3: tampered amounts --------------------------------------------------

    def test_tampered_reserved_amount_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            doc = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
            doc["reservations"][0]["reserved_fen"] += 1
            (run / "fee-ledger.json").write_text(json.dumps(doc), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("reserved_fen" in e for e in verdict["errors"]), verdict)

    def test_tampered_settled_amount_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            doc = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
            doc["reservations"][0]["actual_fen"] += 1
            (run / "fee-ledger.json").write_text(json.dumps(doc), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("actual_fen" in e for e in verdict["errors"]), verdict)

    def test_settled_amount_inconsistent_with_usage_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            lines = (run / "requests.jsonl").read_text(encoding="utf-8").splitlines()
            entry = json.loads(lines[0])
            entry["usage"]["completion_tokens"] += 500
            (run / "requests.jsonl").write_text(json.dumps(entry) + "\n", encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("usage" in e or "actual_fen" in e for e in verdict["errors"]), verdict)

    # ---- group 4: missing usage -----------------------------------------------------

    def test_missing_usage_stays_unknown_and_is_declared(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            lines = (run / "requests.jsonl").read_text(encoding="utf-8").splitlines()
            request = json.loads(lines[0])
            request["usage"] = None
            (run / "requests.jsonl").write_text(json.dumps(request) + "\n", encoding="utf-8")
            doc = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
            record = doc["reservations"][0]
            record.update({"status": "USAGE_UNKNOWN", "usage_tokens": None, "actual_fen": None})
            (run / "fee-ledger.json").write_text(json.dumps(doc), encoding="utf-8")
            manifest = json.loads((run / "manifest.json").read_text(encoding="utf-8"))
            manifest["expected_unknown_usage"] = True
            (run / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(0, code, verdict)
        self.assertEqual(1, verdict["unknown_usage"])

    def test_undeclared_unknown_usage_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            lines = (run / "requests.jsonl").read_text(encoding="utf-8").splitlines()
            request = json.loads(lines[0])
            request["usage"] = None
            (run / "requests.jsonl").write_text(json.dumps(request) + "\n", encoding="utf-8")
            doc = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
            record = doc["reservations"][0]
            record.update({"status": "USAGE_UNKNOWN", "usage_tokens": None, "actual_fen": None})
            (run / "fee-ledger.json").write_text(json.dumps(doc), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("expected_unknown_usage" in e for e in verdict["errors"]), verdict)

    # ---- group 5: failure / timeout -------------------------------------------------

    def test_failed_dispatch_keeps_its_reservation_and_reconciles(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            lines = (run / "requests.jsonl").read_text(encoding="utf-8").splitlines()
            request = json.loads(lines[0])
            request["response_status"] = 500
            request["usage"] = None
            (run / "requests.jsonl").write_text(json.dumps(request) + "\n", encoding="utf-8")
            doc = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
            record = doc["reservations"][0]
            record.update({"status": "FAILED_RETAINED", "usage_tokens": None, "actual_fen": None,
                           "fail_reason": "RuntimeException"})
            (run / "fee-ledger.json").write_text(json.dumps(doc), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(0, code, verdict)
        self.assertEqual(1, verdict["retained_dispatches"])
        self.assertEqual(0, verdict["settled"])

    def test_failed_dispatch_claiming_settlement_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            lines = (run / "requests.jsonl").read_text(encoding="utf-8").splitlines()
            request = json.loads(lines[0])
            request["response_status"] = 504
            request["timed_out"] = True
            request["usage"] = None
            (run / "requests.jsonl").write_text(json.dumps(request) + "\n", encoding="utf-8")
            # the ledger still claims SETTLED with usage: identity contradiction
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(verdict["errors"], verdict)

    # ---- attribution binding --------------------------------------------------------

    def test_dispatch_attributed_to_another_run_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            doc = json.loads((run / "manifest.json").read_text(encoding="utf-8"))
            doc["dispatches"][0]["run_id"] = "run-2"
            (run / "manifest.json").write_text(json.dumps(doc), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("run_id" in e for e in verdict["errors"]), verdict)

    def test_unbound_ledger_reservation_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp)
            doc = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
            extra = dict(doc["reservations"][0])
            extra["reservation_id"] = "fee-unbound-extra"
            doc["reservations"].append(extra)
            (run / "fee-ledger.json").write_text(json.dumps(doc), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("fee-unbound-extra" in e for e in verdict["errors"]), verdict)


class RunManifestDispatchTest(unittest.TestCase):
    """Loopback dispatches recorded by the joined entry are legitimate evidence: the manifest-level
    validator must accept them with the dispatch table and refuse any non-loopback host by name."""

    BASE = {
        "run_id": "0123456789abcdef0123456789abcdef",
        "session_id": "session-1",
        "pair_id": "9" * 64,
        "trial_id": "trial-" + hashlib.sha256(
            ("0123456789abcdef0123456789abcdef|" + "9" * 64).encode("utf-8")).hexdigest()[:16],
        "controller": "controller-a",
        "controller_invoked": "controller-a",
        "repeat_no": 1,
        "conditions": {"prompt": "题面", "material_hashes": {"v": "1", "c": "2", "d": "3"}},
        "video_id": 730013,
        "video_grounding": "VIDEO_GROUNDED",
        "final_status": "SUCCEEDED",
        "error_code": None,
        "receipt_artifact_id": "artifact-1",
        "receipt_json_url": "/artifact/artifact-1/json",
        "receipt_html_url": "/artifact/artifact-1",
        "permission_principal": "demo/alice",
        "permission_read_status": 200,
        "permission_cross_account_status": 403,
        "report_json_sha256": "a" * 64,
        "report_html_sha256": "b" * 64,
        "material_video_sha256": "e" * 64,
        "material_comment_sha256": "2ae4338d24c3af7cdbc22474dd19f4811e8f19a2e1fb46b508d83a6c2c4af0e1",
        "material_danmaku_sha256": "7bb28506501be822a4dc84e59b6f3d5e9f635a57bf4f633d6f70a064c59f610f",
        "video_source_version": "1e9f5d4b28ff89c863a0c0e034a6a39c230dce318dbc2fe770df5be2b20ff53a",
        "task_clock_zone": "Asia/Shanghai",
        "task_clock_reference": "2026-11-16 00:00:00",
        "purpose": "pair_member",
        "fee_note": "loopback double",
    }

    def _dispatch(self, host="127.0.0.1"):
        return {"index": 0, "kind": "chat", "model": "deepseek-ai/DeepSeek-V4.1-Flash",
                "host": host, "path": "/chat/completions", "body_bytes": 10,
                "body_sha256": "0" * 64, "reservation_id": "fee-1", "controller": "controller-a",
                "run_id": "0123456789abcdef0123456789abcdef"}

    def test_loopback_dispatches_with_table_are_accepted(self):
        from report_evaluation.admission import run_binding
        manifest = dict(self.BASE, provider_dispatches=1, dispatches=[self._dispatch()])
        run_binding.validate_run_manifest(manifest, formal_bindings={730013: {"video_sha256": "1e9f5d4b28ff89c863a0c0e034a6a39c230dce318dbc2fe770df5be2b20ff53a"}})

    def test_non_loopback_dispatch_host_is_refused_by_name(self):
        from report_evaluation.admission import run_binding
        manifest = dict(self.BASE, provider_dispatches=1,
                        dispatches=[self._dispatch(host="api.siliconflow.cn")])
        with self.assertRaises(ValueError) as caught:
            run_binding.validate_run_manifest(manifest, formal_bindings={730013: {"video_sha256": "1e9f5d4b28ff89c863a0c0e034a6a39c230dce318dbc2fe770df5be2b20ff53a"}})
        self.assertIn("api.siliconflow.cn", str(caught.exception))

    def test_zero_dispatches_stay_accepted_without_a_table(self):
        from report_evaluation.admission import run_binding
        manifest = dict(self.BASE, provider_dispatches=0)
        run_binding.validate_run_manifest(manifest, formal_bindings={730013: {"video_sha256": "1e9f5d4b28ff89c863a0c0e034a6a39c230dce318dbc2fe770df5be2b20ff53a"}})


class CatalogKeysTest(unittest.TestCase):
    """The generated configuration catalog must carry the two keys this slice introduced, with
    their real Spring binding names (not the conceptual vision.transport spelling)."""

    def test_catalog_contains_models_protocol_key(self):
        catalog = (REPO / "agent/docs/配置目录.md").read_text(encoding="utf-8")
        self.assertIn("`agent.models.protocol`", catalog)

    def test_catalog_contains_video_vision_transport_key(self):
        catalog = (REPO / "agent/docs/配置目录.md").read_text(encoding="utf-8")
        self.assertIn("`agent.video.vision-transport`", catalog)
        self.assertNotIn("`agent.models.vision.transport`", catalog,
                         "the conceptual spelling must not pose as the effective Spring key")

    def test_generator_emits_both_keys(self):
        source = (REPO / "scripts/check_agent_governance.py").read_text(encoding="utf-8")
        self.assertIn("agent.models.protocol", source)
        self.assertIn("agent.video.vision-transport", source)


class RequestDigestPolicyTest(ReconcilerBindingTest):
    """4B-2A content repair: manifests declaring request_digest_policy=required must bind a
    64-hex serialized-request digest on the dispatch AND the request log AND the ledger
    reservation (the client send boundary, the receiving record and the frozen reservation).
    Missing, empty or mismatched digests refuse the run. Manifests without the policy keep the
    historical semantics and are reported at the lower ABSENT_LOW evidence level."""

    def _entry(self, tmp, policy=None, ledger_digest="KEEP", dispatch_digest="KEEP",
               request_digest="KEEP", ledger_bytes=1234, dispatch_bytes=1234, request_bytes=1234):
        run = super()._entry(tmp)
        manifest = json.loads((run / "manifest.json").read_text(encoding="utf-8"))
        ledger = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
        requests = [json.loads(line) for line in (run / "requests.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
        if policy is not None:
            manifest["request_digest_policy"] = policy
            # the required policy also needs the client-boundary role (and the stub's observed
            # role agreeing with it) on every dispatch/request pair
            manifest["dispatches"][0]["client_role"] = "harness"
            for request in requests:
                request["observed_role"] = "harness"
        if policy == "required" and ledger_digest == "KEEP":
            # the fully-bound base: the ledger reservation carries the same digest the dispatch does
            ledger["reservations"][0]["request_body_sha256"] = manifest["dispatches"][0]["body_sha256"]
            ledger["reservations"][0]["request_body_bytes"] = manifest["dispatches"][0]["body_bytes"]
        if dispatch_digest != "KEEP":
            manifest["dispatches"][0]["body_sha256"] = dispatch_digest
        if dispatch_bytes != "KEEP":
            manifest["dispatches"][0]["body_bytes"] = dispatch_bytes
        if ledger_digest != "KEEP":
            if ledger_digest is None:
                ledger["reservations"][0].pop("request_body_sha256", None)
                ledger["reservations"][0].pop("request_body_bytes", None)
            else:
                ledger["reservations"][0]["request_body_sha256"] = ledger_digest
                ledger["reservations"][0]["request_body_bytes"] = ledger_bytes
        if request_digest != "KEEP":
            requests[0]["body_sha256"] = request_digest
        if request_bytes != "KEEP":
            requests[0]["body_bytes"] = request_bytes
        (run / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (run / "fee-ledger.json").write_text(json.dumps(ledger), encoding="utf-8")
        (run / "requests.jsonl").write_text(
            "\n".join(json.dumps(row) for row in requests) + "\n", encoding="utf-8")
        return run

    def test_a_fully_bound_required_run_reconciles_at_evidence_level_bound(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, verdict = self._verify(self._entry(tmp, policy="required"))
        self.assertEqual(0, code, verdict)
        self.assertEqual("RUN_RECONCILED", verdict["verdict"])
        self.assertEqual("BOUND", verdict["request_digest_evidence"])

    def test_a_required_run_with_a_missing_digest_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, verdict = self._verify(self._entry(tmp, policy="required",
                                                     dispatch_digest=None, request_digest=None,
                                                     dispatch_bytes=None, request_bytes=None))
        self.assertEqual(4, code)
        self.assertEqual("RUN_MISMATCH", verdict["verdict"])
        self.assertTrue(any("body_sha256" in e for e in verdict["errors"]), verdict["errors"])
        self.assertTrue(any("body_bytes" in e for e in verdict["errors"]), verdict["errors"])

    def test_a_required_run_with_an_empty_digest_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, verdict = self._verify(self._entry(tmp, policy="required",
                                                     dispatch_digest="", request_digest="",
                                                     dispatch_bytes=0, request_bytes=0))
        self.assertEqual(4, code)
        self.assertTrue(any("body_sha256" in e for e in verdict["errors"]), verdict["errors"])

    def test_a_required_run_without_the_ledger_binding_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, verdict = self._verify(self._entry(tmp, policy="required", ledger_digest=None))
        self.assertEqual(4, code)
        self.assertTrue(any("request_digest" in e for e in verdict["errors"]), verdict["errors"])

    def test_a_required_run_with_a_drifted_digest_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, verdict = self._verify(self._entry(tmp, policy="required",
                                                     ledger_digest="a" * 64))
        self.assertEqual(4, code)
        self.assertTrue(any("request_digest" in e and "drifted" in e for e in verdict["errors"]),
                        verdict["errors"])

    def test_a_historical_run_keeps_its_semantics_at_the_lower_evidence_level(self):
        # no policy field, digests stay null on both sides: reconciles as before, reported as
        # ABSENT_LOW — the values are never rewritten
        with tempfile.TemporaryDirectory() as tmp:
            code, verdict = self._verify(self._entry(tmp, policy=None,
                                                     dispatch_digest=None, request_digest=None,
                                                     dispatch_bytes=None, request_bytes=None))
        self.assertEqual(0, code, verdict)
        self.assertEqual("RUN_RECONCILED", verdict["verdict"])
        self.assertEqual("ABSENT_LOW", verdict["request_digest_evidence"])

    def test_a_required_run_with_a_role_drift_is_refused(self):
        # the client boundary says harness, the receiving stub saw analysis: drift is refused
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp, policy="required")
            requests = [json.loads(line) for line in (run / "requests.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
            requests[0]["observed_role"] = "analysis"
            (run / "requests.jsonl").write_text(
                chr(10).join(json.dumps(row) for row in requests) + chr(10), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("role_drift" in e for e in verdict["errors"]), verdict["errors"])

    def test_a_required_run_with_zero_byte_dispatch_is_refused(self):
        # a zero-byte dispatch is refused even when a digest string is present
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp, policy="required", dispatch_bytes=0, request_bytes=0)
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertTrue(any("body_bytes_zero" in e for e in verdict["errors"]), verdict["errors"])

    def test_a_carried_in_ledger_block_is_reported_by_the_verdict(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._entry(tmp, policy="required")
            ledger = json.loads((run / "fee-ledger.json").read_text(encoding="utf-8"))
            ledger["carried_in"] = {"source": contracts.CARRIED_SOURCE,
                                    "settled_fen": contracts.CARRIED_SETTLED_FEN,
                                    "retained_fen": contracts.CARRIED_RETAINED_FEN}
            (run / "fee-ledger.json").write_text(json.dumps(ledger), encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(0, code, verdict)
        self.assertEqual(contracts.CARRIED_SETTLED_FEN + contracts.CARRIED_RETAINED_FEN,
                         verdict["carried_in_fen"])


if __name__ == "__main__":
    unittest.main()
