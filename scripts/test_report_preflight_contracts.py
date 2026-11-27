"""4B-2A preflight: provider contract registry, vision upper bound, and dry-run plan.

These tests are written against the REQUIRED behaviour before the implementation exists, so the
first run is the recorded first failure (see the round's first-fail/ log). Nothing here contacts a
network or reads a credential; every price is a public list price with its read date and source.
"""
import json
import tempfile
import unittest
from pathlib import Path

from report_evaluation.admission import contracts


class ControllerRegistryTest(unittest.TestCase):
    def test_two_controllers_are_named_with_public_prices(self):
        names = [c["model_request_name"] for c in contracts.CONTROLLERS]
        self.assertEqual(names, ["deepseek-ai/DeepSeek-V4.1-Flash", "deepseek-ai/DeepSeek-V4-Pro"])
        for entry in contracts.CONTROLLERS:
            self.assertEqual(entry["provider"], "siliconflow")
            self.assertEqual(entry["base_url"], "https://api.siliconflow.cn/v1")
            self.assertEqual(entry["endpoint"], "/chat/completions")
            self.assertTrue(entry["native_tools"])
            self.assertEqual(entry["version_state"], "REQUESTED_ONLY")
            self.assertGreater(entry["price_input_fen_per_million"], 0)
            self.assertGreater(entry["price_output_fen_per_million"], 0)
            self.assertTrue(entry["price_source"].startswith("https://"))
            self.assertRegex(entry["read_at"], r"^2026-11-\d\d$")

    def test_alias_is_not_a_locked_snapshot(self):
        flash = contracts.controller_by_name("deepseek-ai/DeepSeek-V4.1-Flash")
        self.assertTrue(flash["alias_of_upstream_build"])
        self.assertIn("alias", flash["version_note"].lower())
        self.assertNotEqual(flash["version_state"], "PROVIDER_REPORTED")

    def test_unknown_controller_raises(self):
        with self.assertRaises(contracts.ContractError):
            contracts.controller_by_name("deepseek-ai/DeepSeek-V5")


class VisionContractTest(unittest.TestCase):
    def test_selected_contract_is_the_generic_image_input_protocol(self):
        vision = contracts.VISION
        self.assertEqual(vision["provider"], "minimax")
        self.assertEqual(vision["transport"], "openai_chat_vision")
        self.assertEqual(vision["base_url"], "https://api.minimax.cn")
        self.assertEqual(vision["endpoint"], "/v1/chat/completions")
        self.assertEqual(vision["model_request_name"], "MiniMax-M3")
        self.assertEqual(vision["image_detail"], "low")
        self.assertEqual(vision["thinking"], "disabled")
        self.assertEqual(vision["per_image_token_ceiling"], 600)
        self.assertEqual(vision["max_completion_tokens"], 2048)
        self.assertEqual(vision["version_state"], "REQUESTED_ONLY")

    def test_forced_thinking_model_is_refused_for_a_bounded_vision_exit(self):
        # MiniMax-M3.1-Flash returns HTTP 400 for thinking.disabled, so reasoning tokens cannot be
        # bounded by max_completion_tokens on public evidence.
        with self.assertRaises(contracts.ContractError):
            contracts.vision_contract_for("MiniMax-M3.1-Flash")

    def test_dedicated_coding_plan_contract_is_never_the_generic_one(self):
        with self.assertRaises(contracts.ContractError):
            contracts.vision_contract_for("MiniMax-M3", transport="vlm_dedicated")

    def test_vision_exit_key_separates_transport_and_provider(self):
        key = contracts.vision_exit_key("minimax", "MiniMax-M3", "openai_chat_vision")
        self.assertEqual(key, "vision:minimax:MiniMax-M3:openai_chat_vision")
        self.assertNotEqual(key, contracts.vision_exit_key("minimax", "MiniMax-M3", "vlm_dedicated"))


class VisionExitBlockTest(unittest.TestCase):
    """A dedicated exit has no bound, so only the *claim* that it is payable may block a config."""

    def _role(self, priced):
        pricing = ({"currency": "CNY", "input_per_million": 4.2, "output_per_million": 16.8,
                    "source": "https://platform.minimax.cn/docs/pricing/overview"} if priced
                   else {"currency": "CNY", "input_per_million": None, "output_per_million": None,
                         "source": None})
        return {"role": "vision", "provider": "minimax", "transport": "vlm_dedicated",
                "endpoint": "/v1/coding_plan/vlm", "base_url": "http://127.0.0.1:18710",
                "model_request_name": "MiniMax-M3", "max_output_tokens": 2048,
                "version_state": "REQUESTED_ONLY", "pricing": pricing}

    def test_priced_dedicated_exit_refuses_the_whole_config(self):
        from report_evaluation.admission import roles
        findings = roles.check_role_binding(self._role(priced=True))
        self.assertIn("VISION_BOUND_UNAVAILABLE", {f["code"] for f in findings})
        self.assertTrue(roles.blocking_findings(findings))

    def test_unpriced_dedicated_exit_is_recorded_without_blocking_offline_preparation(self):
        from report_evaluation.admission import roles
        findings = roles.check_role_binding(self._role(priced=False))
        codes = {f["code"] for f in findings}
        self.assertIn("VISION_EXIT_UNPRICED", codes)
        self.assertNotIn("VISION_BOUND_UNAVAILABLE", codes)
        self.assertEqual([], roles.blocking_findings(findings))
        self.assertFalse(roles.role_payability(self._role(priced=False))["payable"])

    def test_the_registered_model_is_the_only_payable_generic_exit(self):
        from report_evaluation.admission import roles
        priced = self._role(priced=True)
        priced.update(transport="openai_chat_vision", endpoint="/v1/chat/completions",
                      model_request_name="MiniMax-M3.1-Flash")
        findings = roles.check_role_binding(priced)
        self.assertIn("VISION_BOUND_UNAVAILABLE", {f["code"] for f in findings})


class VisionBoundTest(unittest.TestCase):
    """Grounded-budget verdict (2026-11-19, after the two authorized real metering requests):
    the image probe measured prompt_tokens=540 for one formal frame + short text, the text-only
    comparison measured 202 for the identical text (so the frame = 338 image tokens), and the
    provider's documented detail=low estimate is <=600/image. The vision reservation therefore
    becomes a GROUNDED estimate: measured 202-token request floor + a declared prompt-length
    envelope (1000 chars >= tokens, ~2x the official 0.625 token/char ratio) + the documented
    600/image bound. Transport bytes still never contribute to the token math."""

    def test_bound_is_measured_floor_plus_prompt_envelope_plus_images(self):
        bound = contracts.vision_bound(image_count=12, payload_bytes=1_211_027, output_tokens=2048)
        expected_input = 202 + 1000 + 12 * 600
        self.assertEqual(bound["input_tokens"], expected_input)
        self.assertEqual(bound["image_tokens"], 12 * 600)
        self.assertEqual(bound["output_tokens"], 2048)
        self.assertEqual(bound["state"], "GROUNDED_ESTIMATE")
        self.assertNotIn(str(1_211_027), json.dumps(bound), "transport bytes must not appear as tokens")

    def test_reservation_fen_matches_the_grounding_formula(self):
        # 202 + 1000 + 7200 = 8402 input words -> ceil(8402*420/1e6)=4; output ceil(2048*1680/1e6)=4.
        fen = contracts.vision_reservation_fen(image_count=12, payload_bytes=1_211_027, output_tokens=2048)
        self.assertEqual(fen, 8)

    def test_bound_still_validates_identity_inputs(self):
        for kwargs in ({"image_count": 0, "payload_bytes": 10, "output_tokens": 10},
                       {"image_count": 25, "payload_bytes": 10, "output_tokens": 10},
                       {"image_count": 1, "payload_bytes": 0, "output_tokens": 10},
                       {"image_count": 1, "payload_bytes": 10, "output_tokens": 0},
                       {"image_count": 1, "payload_bytes": 10, "output_tokens": 4096}):
            with self.assertRaises(contracts.ContractError):
                contracts.vision_bound(**kwargs)

    def test_vision_contract_declares_the_grounding(self):
        vision = contracts.VISION
        self.assertEqual(vision["prepay_fee_state"], "GROUNDED_ESTIMATE")
        self.assertEqual(vision["request_text_floor_measured"], 202)
        self.assertEqual(vision["measured_image_tokens_frame00"], 338)
        self.assertEqual(vision["prompt_chars_max"], 1000)
        self.assertEqual(vision["per_image_token_ceiling"], 600)
        self.assertIn("probe-run-20261119T024727Z", vision["metering_grounding"]["image_probe"])
        self.assertIn("text-compare-run-20261119T025509Z", vision["metering_grounding"]["text_compare"])
        self.assertFalse(vision["assumes_cache_hit"])
        self.assertEqual(vision["price_tier"], "standard")

    def test_frozen_reservation_base_dominates_every_reachable_tier(self):
        """Official 2026-11-19 facts: the standard tier itself is 永久五折 (2.10/8.40 CNY per
        million), priority is 1.5x standard, and the >512k tier only applies beyond 512k input.
        The frozen 420/1680 fen base (the struck-through list price) stays the reservation basis
        because it is >= both reachable prices while input stays under 512k."""
        vision = contracts.VISION
        self.assertEqual(vision["price_input_fen_per_million"], 420)
        self.assertEqual(vision["price_output_fen_per_million"], 1680)
        self.assertEqual(vision["applicable_standard_input_fen"], 210)
        self.assertEqual(vision["applicable_standard_output_fen"], 840)
        self.assertEqual(vision["applicable_priority_input_fen"], 315)
        self.assertEqual(vision["applicable_priority_output_fen"], 1260)
        self.assertGreaterEqual(vision["price_input_fen_per_million"],
                                vision["applicable_priority_input_fen"])
        self.assertGreaterEqual(vision["price_output_fen_per_million"],
                                vision["applicable_priority_output_fen"])
        self.assertIn("永久五折", vision["price_window"])
        self.assertEqual(vision["recheck_read_at"], "2026-11-19")


class LedgerVisionAdmissionTest(unittest.TestCase):
    def _ledger(self, tmp, prices=None):
        from report_evaluation.admission.budget import FeeLedger
        return FeeLedger(Path(tmp) / "ledger.json", 5000, 2000,
                         prices if prices is not None else contracts.ledger_prices())

    def test_vision_admission_reserves_the_grounded_bound_and_settles_from_real_usage(self):
        """12 frames reserve 8 fen (202+1000+7200 input, 2048 output at the frozen base). The
        settlement fixture uses the REAL probe response usage (prompt 540 / completion 35): the
        actual 2 fen lands far below the reservation, exactly as the live probe did."""
        with tempfile.TemporaryDirectory() as tmp:
            with self._ledger(tmp) as ledger:
                record = ledger.admit_vision("minimax", "MiniMax-M3", "openai_chat_vision",
                                             image_count=12, payload_bytes=1_211_027, output_tokens=2048)
                self.assertEqual(record["reserved_fen"], 8)
                settled = ledger.settle(record["reservation_id"],
                                        {"usage": {"prompt_tokens": 540, "completion_tokens": 35}})
                self.assertEqual(settled["status"], "SETTLED")
                # ceil(540*420/1e6)=1, ceil(35*1680/1e6)=1 — the real probe's own settlement
                self.assertEqual(settled["actual_fen"], 2)

    def test_dedicated_and_audio_exits_stay_refused(self):
        from report_evaluation.admission.budget import DispatchBlocked
        with tempfile.TemporaryDirectory() as tmp:
            with self._ledger(tmp) as ledger:
                with self.assertRaises(DispatchBlocked):
                    ledger.admit_vision("minimax", "MiniMax-M3", "vlm_dedicated",
                                        image_count=1, payload_bytes=100, output_tokens=100)
                with self.assertRaises(DispatchBlocked):
                    ledger.admit("audio:minimax:/audio/transcriptions", 100, 100)

    def test_unknown_vision_pricing_is_refused_not_zeroed(self):
        from report_evaluation.admission.budget import DispatchBlocked
        with tempfile.TemporaryDirectory() as tmp:
            with self._ledger(tmp, prices={}) as ledger:
                with self.assertRaises(DispatchBlocked):
                    ledger.admit_vision("minimax", "MiniMax-M3", "openai_chat_vision",
                                        image_count=1, payload_bytes=100, output_tokens=100)


class SchemaTransportTest(unittest.TestCase):
    def _config(self, **role_overrides):
        # Resolve from this file so the suite runs from either the repo root or scripts/.
        example = Path(__file__).resolve().parents[1] / "agent/eval/report-trial-admission.example.json"
        base = json.loads(example.read_text(encoding="utf-8"))
        for role in base["roles"]:
            if role["role"] in role_overrides:
                role.update(role_overrides[role["role"]])
        return base

    def test_vision_role_accepts_the_generic_minimax_transport(self):
        from report_evaluation.admission import schema
        config = self._config(vision={"transport": "openai_chat_vision",
                                     "endpoint": "/v1/chat/completions",
                                     "provider": "minimax"})
        issues = schema.validate_config(config)
        self.assertEqual([i.path for i in issues], [])

    def test_vision_role_rejects_dedicated_transport_with_generic_endpoint(self):
        from report_evaluation.admission import schema
        config = self._config(vision={"transport": "vlm_dedicated",
                                     "endpoint": "/v1/chat/completions"})
        paths = [i.path for i in schema.validate_config(config)]
        self.assertIn("roles[3].endpoint", paths)

    def test_vision_role_rejects_a_provider_that_is_not_minimax(self):
        from report_evaluation.admission import schema
        config = self._config(vision={"transport": "openai_chat_vision",
                                     "endpoint": "/v1/chat/completions",
                                     "provider": "compatible"})
        paths = [i.path for i in schema.validate_config(config)]
        self.assertIn("roles[3].provider", paths)


class DryRunPlanTest(unittest.TestCase):
    """Grounded-budget semantics: the vision fee no longer depends on frame manifest bytes. Every
    run's vision dispatch is bounded by the measured 202-token request floor + the 1000-char
    prompt envelope + the documented 600/image estimate, so plans claim closed totals again —
    at fen scale, ~70x below the withdrawn bytes-as-tokens figures. Arming stays a separate
    release (live_dispatch_enabled=false)."""

    def test_canon_first_pair_plan_has_two_runs_one_dispatch_each(self):
        from report_preflight_run import build_plan
        import report_evaluation.admission.contracts as contracts
        plan = build_plan(controllers=2, repeats=1, materials=1)
        self.assertEqual(plan["runs"], 2)
        self.assertEqual(plan["provider_dispatches"], 2 * plan["dispatches_per_run"])
        self.assertEqual(plan["verdict"], "PLANNED_GROUNDED_ESTIMATE")
        self.assertEqual(plan["vision_fee_state"], "GROUNDED_ESTIMATE")
        self.assertEqual(plan["vision_fen_per_run"], 6)
        # 4B-2A live-readiness ALIGNED envelope (2026-11-19): input on the runtime-enforced byte
        # cap (65536 bytes-as-tokens), counts on the loop's enforced maxModelCalls (16), vision
        # on the ACTUAL in-window 2-frame list.
        # controller-a chat: harness 16x(14+3)=272 + analysis 12x(14+14)=336 = 608
        # controller-b chat: harness 16x(79+8)=1392 + analysis 12x28=336 = 1728
        self.assertEqual(plan["fee_upper_fen_per_run"], 1734)
        self.assertEqual(plan["fee_upper_fen"], 2348)
        self.assertEqual(plan["live_dispatch_enabled"], False)
        # two cache states, stated separately
        self.assertEqual(plan["cache_states"]["shared_analysis_pair_fen"], 2006)
        self.assertEqual(plan["cache_states"]["isolated_analysis_pair_fen"], 2348)
        # carried prior spend + the dual lines (both enforced before any send)
        self.assertEqual(plan["carried_in"]["settled_fen"], contracts.CARRIED_SETTLED_FEN)
        self.assertEqual(plan["carried_in"]["retained_fen"], contracts.CARRIED_RETAINED_FEN)
        self.assertEqual(plan["carried_in"]["provider_reconciliation"], "PENDING")
        self.assertEqual(plan["first_pair_pause_fen"], 1000)
        self.assertEqual(plan["first_pair_pause_cny"], "10.00 CNY")
        self.assertEqual(plan["batch_new_spend_cap_fen"], 2348)
        self.assertEqual(plan["first_pair_conservative_total_fen"], 304 + 2348)
        self.assertIn("622", plan["currency_note"])
        self.assertEqual(plan["request_digest_policy"], "required")

    def test_plan_refuses_a_budget_over_the_approved_envelope(self):
        from report_preflight_run import build_plan
        with self.assertRaises(ValueError):
            build_plan(controllers=2, repeats=6, materials=3)

    def test_placeholder_payload_is_flagged_as_optimistic(self):
        """Without a manifest the transport payload is a placeholder — recorded honestly, but the
        grounded fee no longer depends on it."""
        from report_preflight_run import build_plan
        plan = build_plan()
        self.assertEqual(plan["vision_payload_source"], "envelope-placeholder")
        materials = {entry["material_key"]: entry for entry in plan["materials_breakdown"]}
        entry = materials["ysjf-canon"]
        self.assertEqual(entry["vision_state"], "GROUNDED_BOUND")
        self.assertIsNone(entry.get("vision_payload_bytes"))
        self.assertEqual(entry["vision_fen_per_run"], 6)

    def test_real_frames_change_transport_bytes_but_never_the_fee(self):
        """Real base64 frames (~1.21 MB) and the 20 KB placeholder must produce the SAME grounded
        fee: transport bytes are recorded, never priced. The old planner turned the byte
        difference into a 516-vs-26 fen difference; that path is gone for good."""
        from report_preflight_run import build_plan, measure_vision_payload
        frames = [{"index": i, "bytes": 152_405} for i in range(12)]
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "frames-manifest.json"
            manifest.write_text(json.dumps({"frames": frames}), encoding="utf-8")
            images, payload = measure_vision_payload(manifest)
            measured = build_plan(frames_manifest=manifest)
            placeholder = build_plan()
        self.assertEqual(images, 12)
        self.assertGreater(payload, 1_200_000)
        self.assertEqual(measured["vision_payload_source"], "frames-manifest")
        self.assertEqual(placeholder["vision_payload_source"], "envelope-placeholder")
        self.assertGreater(payload, 50 * 20_000)
        for plan in (measured, placeholder):
            self.assertEqual(plan["verdict"], "PLANNED_GROUNDED_ESTIMATE")
            self.assertEqual(plan["vision_fen_per_run"], 6)
            self.assertEqual(plan["fee_upper_fen"], 2348)
        entry = {e["material_key"]: e for e in measured["materials_breakdown"]}["ysjf-canon"]
        self.assertEqual(entry["vision_payload_bytes"], payload)
        self.assertEqual(entry["vision_state"], "GROUNDED_BOUND")
        self.assertIsNotNone(entry["vision_transport"])

    def test_the_full_twelve_run_matrix_fits_well_under_fifty_cny(self):
        """12 trials at the grounded bound: chat 1284 fen + vision 8 fen/run x 12 = 1380 fen
        (~13.8 CNY), far below the 50 CNY hard limit and ~70x below the withdrawn 7476-fen
        bytes-as-tokens figure."""
        from report_preflight_run import build_plan
        frames = [{"index": i, "bytes": 152_405} for i in range(12)]
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "frames-manifest.json"
            manifest.write_text(json.dumps({"frames": frames}), encoding="utf-8")
            # ALIGNED (2026-11-19): the honest 12-run matrix at runtime-enforced ceilings is
            # 6 x 1444 = 8664 fen — ABOVE the project's 50 CNY hard limit, so the planner now
            # REFUSES it: bigger batches need per-batch planning under the batch cap; the
            # project's 50 CNY cap is not negotiable
            with self.assertRaises(ValueError):
                build_plan(controllers=2, repeats=6, materials=1, frames_manifest=manifest)
            # the first PAIR still plans (and is the unit the batch cap governs)
            pair = build_plan(controllers=2, repeats=1, materials=1, frames_manifest=manifest)
        self.assertEqual(pair["runs"], 2)
        self.assertEqual(pair["verdict"], "PLANNED_GROUNDED_ESTIMATE")
        self.assertEqual(pair["unknown_vision_runs"], 0)
        self.assertLess(pair["fee_upper_fen"], pair["hard_limit_fen"])
        self.assertEqual(pair["live_dispatch_enabled"], False)

    def test_measure_refuses_a_manifest_without_usable_frame_sizes(self):
        from report_preflight_run import measure_vision_payload
        for broken in ({"frames": []}, {"frames": [{"index": 0}]}, {"frames": [{"index": 0, "bytes": True}]}):
            with tempfile.TemporaryDirectory() as tmp:
                manifest = Path(tmp) / "m.json"
                manifest.write_text(json.dumps(broken), encoding="utf-8")
                with self.assertRaises(ValueError):
                    measure_vision_payload(manifest)


class VerifyRunTest(unittest.TestCase):
    """The reconciliation is the only proof that money and dispatches agree; it must never traceback."""

    def _run_dir(self, tmp):
        import hashlib
        run = Path(tmp) / "run"
        (run / "frames").mkdir(parents=True)
        frame = run / "frames" / "f.jpg"
        frame.write_bytes(b"jpeg-bytes")
        digest = hashlib.sha256(b"jpeg-bytes").hexdigest()
        (run / "manifest.json").write_text(json.dumps(
            {"provider_dispatches": 1, "frames": [{"path": "frames/f.jpg", "sha256": digest}]}),
            encoding="utf-8")
        (run / "fee-ledger.json").write_text(json.dumps(
            {"reservations": [{"status": "SETTLED", "reserved_fen": 5, "actual_fen": 3}]}), encoding="utf-8")
        (run / "requests.jsonl").write_text('{"index":0}\n', encoding="utf-8")
        return run

    def _verify(self, run):
        import contextlib
        import io
        from report_preflight_run import verify_run
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = verify_run(run)
        return code, json.loads(buffer.getvalue())

    def test_a_complete_run_reconciles(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, verdict = self._verify(self._run_dir(tmp))
        self.assertEqual(0, code)
        self.assertEqual("RUN_RECONCILED", verdict["verdict"])
        self.assertEqual(3, verdict["known_settled_fen"])
        self.assertEqual([], verdict["errors"])

    def test_a_dropped_dispatch_is_a_mismatch_not_a_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self._run_dir(tmp)
            (run / "requests.jsonl").write_text('{"index":0}\n{"index":1}\n', encoding="utf-8")
            code, verdict = self._verify(run)
        self.assertEqual(4, code)
        self.assertEqual("RUN_MISMATCH", verdict["verdict"])
        self.assertTrue(any("stub requests" in error for error in verdict["errors"]))

    def test_an_unreadable_directory_is_a_verdict_not_a_traceback(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, verdict = self._verify(Path(tmp) / "does-not-exist")
        self.assertEqual(2, code)
        self.assertEqual("RUN_UNREADABLE", verdict["verdict"])
        self.assertTrue(verdict["errors"])


if __name__ == "__main__":
    unittest.main()
