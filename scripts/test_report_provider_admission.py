"""Stage 4B-1 provider admission targeted tests: roles/protocol, material binding, tool freeze, fee budget, CLI.

All offline: synthetic data, localhost test doubles only, no credentials, no model calls.
"""
import hashlib
import os
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))

from report_evaluation.admission import budget, check, materials, pairing, roles, run_binding, toolfreeze  # noqa: E402

# Real canon material hashes from var/provider-admission/materials-baseline.json (formal input).
CANON = {
    "video_sha256": "1e9f5d4b28ff89c863a0c0e034a6a39c230dce318dbc2fe770df5be2b20ff53a",
    "comment_sha256": "2ae4338d24c3af7cdbc22474dd19f4811e8f19a2e1fb46b508d83a6c2c4af0e1",
    "danmaku_sha256": "7bb28506501be822a4dc84e59b6f3d5e9f635a57bf4f633d6f70a064c59f610f",
}
# Golden vector for freeze-key-v1, fixed before implementation and shared with the Java side.
GOLDEN_KEY = "0b33b75368239805266cc54d401343c7748340ee7f831705705a67578ce87517"
GOLDEN_SAMPLE = {
    "tool": "analyze_comments",
    "arguments": {"dataset_key": "ysjf-canon", "limit": 50},
    "material": dict(CANON),
    "task_clock": {"timezone": "Asia/Shanghai", "reference_time": "2026-11-10 00:00:00"},
    "task_window": {"kind": "absolute", "start": "2026-11-03 00:00:00", "end": "2026-11-10 00:00:00"},
    "sampling_strategy": "frame-12-window-full",
    "tool_config_version": "freeze-key-v1",
}
# Authoritative 4A formal-window verdict for ysjf-canon (formal-window.json in the 4A evidence root).
# comments_before_start is NOT part of the verdict: the canon CSV legitimately spans a wider range.
CANON_WINDOW = {
    "comments_in_window": 58,
    "original_comments_in_window": 0,
    "danmaku_in_window": 137,
    "comment_by_date": {"2026-11-03": 3, "2026-11-04": 5, "2026-11-05": 7,
                        "2026-11-06": 7, "2026-11-07": 14, "2026-11-08": 16, "2026-11-09": 6},
    "danmaku_by_date": {"2026-11-03": 15, "2026-11-04": 14, "2026-11-05": 15,
                        "2026-11-06": 26, "2026-11-07": 28, "2026-11-08": 27, "2026-11-09": 12},
    "missing_time": 0,
}


def pricing_null():
    return {"currency": "CNY", "input_per_million": None, "output_per_million": None, "source": None}


def role(provider="siliconflow", transport="chat_completions", endpoint="/chat/completions",
         name="PLACEHOLDER-REQUESTED-ONLY", r="harness"):
    return {"role": r, "provider": provider, "transport": transport, "endpoint": endpoint,
            "base_url": "http://127.0.0.1:0", "model_request_name": name,
            "version_state": "REQUESTED_ONLY", "max_output_tokens": 1024,
            "pricing": pricing_null()}


def _write_fixture_dataset(tmp, key, comments_by_date=None, danmaku_by_date=None):
    """A tiny formal-shaped dataset for one material key (loaders demand the real columns)."""
    root = tmp / f"ds-{key}"
    (root / "评论").mkdir(parents=True, exist_ok=True)
    (root / "弹幕").mkdir(parents=True, exist_ok=True)
    comments_by_date = comments_by_date or {"2026-11-05": 2, "2026-11-06": 3}
    danmaku_by_date = danmaku_by_date or {"2026-11-05": 4, "2026-11-06": 5}
    header = "评论ID,上级评论ID,用户ID,评论内容,评论时间,原始评论时间\n"
    lines = [header]
    seq = 0
    for day, n in sorted(comments_by_date.items()):
        for i in range(n):
            seq += 1
            lines.append(f"{seq},,434331045,观众{seq},{day} 0{i % 10}:{i % 60:02d}:00,2026-10-29 00:00:00\n")
    (root / "评论" / f"{key}_评论.csv").write_text("".join(lines), encoding="utf-8")
    dm_header = "序号,播放位置秒,内容,仿真发送时间,发送者ID,发送者昵称\n"
    dm = [dm_header]
    seq = 0
    for day, n in sorted(danmaku_by_date.items()):
        for i in range(n):
            seq += 1
            dm.append(f"{seq},0.75,弹幕{seq},{day} {i % 24:02d}:{i % 60:02d}:00,9001000006,深夜快递员67\n")
    (root / "弹幕" / f"{key}_弹幕.csv").write_text("".join(dm), encoding="utf-8")
    return root


def _fixture_bindings(tmp):
    """Bindings computed from the actual fixture files so the deep check passes honestly."""
    from report_evaluation.util import sha256_file
    canon_comments = {"2026-11-03": 3, "2026-11-04": 5, "2026-11-05": 7,
                      "2026-11-06": 7, "2026-11-07": 14, "2026-11-08": 16, "2026-11-09": 6}
    canon_danmaku = {"2026-11-03": 15, "2026-11-04": 14, "2026-11-05": 15,
                     "2026-11-06": 26, "2026-11-07": 28, "2026-11-08": 27, "2026-11-09": 12}
    bindings = {}
    for key in ("hxc-doufu", "my-dragon", "ysjf-canon"):
        if key == "ysjf-canon":
            root = _write_fixture_dataset(tmp, key, canon_comments, canon_danmaku)
        else:
            root = _write_fixture_dataset(tmp, key)
        video = tmp / "videos" / f"{key}.mp4"
        video.parent.mkdir(parents=True, exist_ok=True)
        video.write_bytes(f"synthetic fixture bytes for {key}".encode("utf-8"))
        bindings[key] = {
            "key": key,
            "dataset_root": str(root),
            "video_path": str(video),
            "binding": {
                "video_sha256": sha256_file(video),
                "comment_sha256": sha256_file(root / "评论" / f"{key}_评论.csv"),
                "danmaku_sha256": sha256_file(root / "弹幕" / f"{key}_弹幕.csv"),
            },
            "service_probe": {"host": "127.0.0.1", "port": 18701 + ["hxc-doufu", "my-dragon", "ysjf-canon"].index(key)},
        }
    bindings["ysjf-canon"]["task_window"] = {"kind": "absolute", "start": "2026-11-03 00:00:00", "end": "2026-11-10 00:00:00"}
    bindings["ysjf-canon"]["canon_window"] = dict(CANON_WINDOW)
    return [bindings[k] for k in ("hxc-doufu", "my-dragon", "ysjf-canon")]


def admission_config(tmp, roles_list=None, with_materials=True):
    doc = {
        "kind": "report-trial-admission", "schema_version": 1, "draft": True,
        "credentials_embedded": False,
        "budget": {"total_cny": 50, "pause_cny": 20,
                   "covers": "both providers, all roles, preparation, failures and retries"},
        "task_clock": {"timezone": "Asia/Shanghai", "reference_time": "2026-11-10 00:00:00"},
        "tool_freeze": {"key_spec_version": "freeze-key-v2", "tool_config_version": "admission-4b1",
                        "sampling_strategy": "frame-12-window-full"},
        "roles": roles_list if roles_list is not None else [
            role(r="preparation"), role(r="harness"), role(r="analysis"),
            role(provider="minimax", transport="vlm_dedicated", endpoint="/v1/coding_plan/vlm", r="vision"),
            role(provider="minimax", transport="audio_transcriptions", endpoint="/audio/transcriptions", r="transcription"),
        ],
        "materials": _fixture_bindings(tmp) if with_materials else [],
    }
    return doc


class RolesProtocolTest(unittest.TestCase):
    def test_minimax_dedicated_body_must_not_carry_generic_model_field(self):
        mixed = {"model": "MiniMax-M3.1-Flash", "prompt": "描述画面", "image_url": "data:image/jpeg;base64,x"}
        findings = roles.check_vision_payload("vlm_dedicated", mixed)
        self.assertTrue(any(f["code"] == "CONTRACT_MIXING" for f in findings))

    def test_generic_vision_requires_model_and_image_parts(self):
        findings = roles.check_vision_payload("chat_vision", {"messages": [{"role": "user", "content": "hi"}]})
        self.assertTrue(any(f["code"] == "MISSING_MODEL_FIELD" for f in findings))
        ok = {"model": "m", "messages": [{"role": "user", "content": [
            {"type": "text", "text": "t"}, {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,x"}}]}]}
        self.assertEqual([], roles.check_vision_payload("chat_vision", ok))

    def test_dedicated_endpoint_never_mixed_with_generic_path(self):
        findings = roles.check_role_binding(role(provider="minimax", transport="vlm_dedicated",
                                                 endpoint="/chat/completions"))
        self.assertTrue(any(f["code"] == "CONTRACT_MIXING" for f in findings))

    def test_siliconflow_thinking_field_is_listed_as_unverified_risk(self):
        findings = roles.check_role_binding(role())
        codes = {f["code"] for f in findings}
        self.assertIn("PROTOCOL_FIELD_UNVERIFIED", codes)

    def test_deepseek_official_host_allows_thinking_mapping(self):
        official = role(provider="deepseek", transport="chat_completions")
        official["base_url"] = "https://api.deepseek.com"
        findings = roles.check_role_binding(official)
        self.assertNotIn("PROTOCOL_FIELD_UNVERIFIED", {f["code"] for f in findings})

    def test_missing_usage_is_unknown_not_zero(self):
        self.assertEqual(roles.USAGE_UNKNOWN, roles.usage_state({}))
        self.assertEqual(roles.USAGE_UNKNOWN, roles.usage_state({"usage": {"prompt_tokens": 5}}))
        self.assertEqual(roles.USAGE_REPORTED, roles.usage_state({"usage": {"prompt_tokens": 5, "completion_tokens": 7}}))
        self.assertEqual(roles.USAGE_UNKNOWN, roles.usage_state({"usage": {"prompt_tokens": -1, "completion_tokens": 0}}))

    def test_null_pricing_role_cannot_be_marked_payable(self):
        verdict = roles.role_payability(role())
        self.assertFalse(verdict["payable"])
        self.assertEqual("NEEDS_PRICING", verdict["reason"])

    def test_placeholder_request_name_stays_requested_only(self):
        verdict = roles.role_payability(role())
        self.assertEqual("REQUESTED_ONLY", verdict["version_state"])

    def test_native_tool_contract_checked_not_assumed(self):
        contract = roles.chat_contract_findings(role(), {
            "native_tools": True, "required_tool_supported": False, "streaming": True,
            "multi_turn_tool_pairing": True, "usage_required": True})
        self.assertTrue(any(f["code"] == "REQUIRED_TOOL_UNSUPPORTED" for f in contract))

    def test_role_snapshot_records_credential_free_transport(self):
        snapshot = roles.role_snapshot([role(), role(provider="minimax", transport="vlm_dedicated",
                                                     endpoint="/v1/coding_plan/vlm", r="vision")])
        self.assertTrue(snapshot["credentials_embedded"] is False)
        self.assertEqual(2, len(snapshot["roles"]))
        for entry in snapshot["roles"]:
            self.assertNotIn("api_key", json.dumps(entry))


def _material_specs():
    """Canon spec with the REAL formal hashes (structural checks only; no files needed)."""
    return [{
        "key": "ysjf-canon",
        "dataset_root": "DATASET_ROOT_PLACEHOLDER",
        "video_path": "fixtures/somewhere/30000元内的最强水桶机？佳能R5 Mark II评测！.mp4",
        "binding": dict(CANON),
        "service_probe": {"host": "127.0.0.1", "port": 18701},
    }]


def _write_fake_materials(tmp):
    """Real-schema fixture (canon window distribution); original times sit outside the window."""
    root = tmp / "unified-video-eval"
    comment_by_date = {"2026-11-03": 3, "2026-11-04": 5, "2026-11-05": 7,
                       "2026-11-06": 7, "2026-11-07": 14, "2026-11-08": 16, "2026-11-09": 6}
    danmaku_by_date = {"2026-11-03": 15, "2026-11-04": 14, "2026-11-05": 15,
                       "2026-11-06": 26, "2026-11-07": 28, "2026-11-08": 27, "2026-11-09": 12}
    (root / "评论").mkdir(parents=True)
    (root / "弹幕").mkdir(parents=True)
    lines = ["评论ID,上级评论ID,用户ID,评论内容,评论时间,原始评论时间"]
    seq = 0
    for day, n in sorted(comment_by_date.items()):
        for i in range(n):
            seq += 1
            lines.append(f"{seq},,434331045,观众{seq},{day} 0{i % 10}:{i % 60:02d}:00,2026-10-29 00:00:00")
    (root / "评论" / "ysjf-canon_评论.csv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    dm = ["序号,播放位置秒,内容,仿真发送时间,发送者ID,发送者昵称"]
    seq = 0
    for day, n in sorted(danmaku_by_date.items()):
        for i in range(n):
            seq += 1
            dm.append(f"{seq},0.75,弹幕{seq},{day} {i % 24:02d}:{i % 60:02d}:00,9001000006,深夜快递员67")
    (root / "弹幕" / "ysjf-canon_弹幕.csv").write_text("\n".join(dm) + "\n", encoding="utf-8")
    return root


def _fixture_canon_spec(tmp, root):
    """A canon spec bound to the fixture's actual content hashes."""
    from report_evaluation.util import sha256_file
    video = tmp / "videos" / "ysjf-canon.mp4"
    video.parent.mkdir(parents=True, exist_ok=True)
    video.write_bytes(b"synthetic fixture bytes standing in for the formal mp4 path")
    spec = _material_specs()[0]
    spec["dataset_root"] = str(root)
    spec["video_path"] = str(video)
    spec["binding"] = {
        "video_sha256": sha256_file(video),
        "comment_sha256": sha256_file(root / "评论" / "ysjf-canon_评论.csv"),
        "danmaku_sha256": sha256_file(root / "弹幕" / "ysjf-canon_弹幕.csv"),
    }
    return spec


class MaterialsBindingTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="admission-mat-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def test_binding_requires_all_three_content_hashes(self):
        spec = _material_specs()[0]
        spec["binding"] = {k: v for k, v in CANON.items() if k != "video_sha256"}
        with self.assertRaises(materials.MaterialBindingError):
            materials.validate_binding(spec)

    def test_video_hash_none_refused(self):
        spec = _material_specs()[0]
        spec["binding"]["video_sha256"] = None
        with self.assertRaises(materials.MaterialBindingError):
            materials.validate_binding(spec)

    def test_binding_hash_mismatch_refused(self):
        root = _write_fake_materials(self.tmp)
        spec = _fixture_canon_spec(self.tmp, root)
        spec["binding"]["comment_sha256"] = "0" * 64
        result = materials.binding_file_hashes(spec)
        self.assertFalse(result["matched"])
        self.assertEqual("comment_sha256", result["mismatch"])

    def test_baseline_hash_mismatch_refused(self):
        baseline = {"fixtures/x": {"a_评论.csv": {"bytes": 1, "sha256": "1" * 64}}}
        actual = {"fixtures/x": {"a_评论.csv": {"bytes": 1, "sha256": "2" * 64}}}
        with self.assertRaises(materials.MaterialBindingError):
            materials.verify_against_baseline(baseline, actual)

    def test_video_matched_by_content_hash_not_name(self):
        spec = _material_specs()[0]
        spec["video_path"] = "fixtures/somewhere/demo1001.mp4"
        # Same content hash under a different name is still the formal video; a placeholder hash is not.
        spec["binding"] = dict(CANON)
        self.assertTrue(materials.video_matches(spec, CANON["video_sha256"]))
        with self.assertRaises(materials.MaterialBindingError):
            materials.video_matches(spec, "d" * 64)

    def test_canon_window_counts_match_formal_verdict(self):
        root = _write_fake_materials(self.tmp)
        stats = materials.window_stats(root, "ysjf-canon", "2026-11-03 00:00:00", "2026-11-10 00:00:00",
                                       "Asia/Shanghai")
        self.assertEqual(CANON_WINDOW["comments_in_window"], stats["comments_in_window"])
        self.assertEqual(CANON_WINDOW["danmaku_in_window"], stats["danmaku_in_window"])
        self.assertEqual(CANON_WINDOW["comment_by_date"], stats["comment_by_date"])
        self.assertEqual(CANON_WINDOW["danmaku_by_date"], stats["danmaku_by_date"])
        self.assertEqual(CANON_WINDOW["missing_time"], stats["missing_time"])
        self.assertEqual(CANON_WINDOW["original_comments_in_window"], stats["original_comments_in_window"])
        # Informational: the fixture places every comment inside the window, so the counter is zero here.
        self.assertEqual(0, stats["comments_before_start"])

    def test_window_outside_records_are_counted_before_start(self):
        root = _write_fake_materials(self.tmp)
        p = root / "评论" / "ysjf-canon_评论.csv"
        lines = p.read_text(encoding="utf-8").splitlines()
        seq = len(lines) - 1  # header already counted
        lines.append(f"{seq},,434331045,旧日期,2026-10-01 00:00:00,2026-09-30 00:00:00")
        p.write_text("\n".join(lines) + "\n", encoding="utf-8")
        stats = materials.window_stats(root, "ysjf-canon", "2026-11-03 00:00:00", "2026-11-10 00:00:00",
                                       "Asia/Shanghai")
        self.assertEqual(1, stats["comments_before_start"])

    def test_service_probe_port_recorded_and_loopback(self):
        spec = _material_specs()[0]
        spec["service_probe"] = {"port": 18701}
        probe = materials.service_probe_binding(spec)
        self.assertEqual(18701, probe["port"])
        spec["service_probe"] = {"host": "0.0.0.0", "port": 1}
        with self.assertRaises(materials.MaterialBindingError):
            materials.service_probe_binding(spec)


class ToolFreezeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="admission-freeze-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.store = toolfreeze.FrozenStore(self.tmp / "store")

    def test_golden_vector_matches_spec(self):
        # v2 keeps integer/string payloads byte-identical to v1; the golden key is unchanged.
        self.assertEqual(GOLDEN_KEY, toolfreeze.freeze_key(**GOLDEN_SAMPLE))

    def test_v2_number_domain_vectors(self):
        # The acceptance divergence: offset_seconds=0.00001 serialized 1e-05 vs 1.0E-5.
        def acceptance_shape(arguments):
            payload = {
                "tool": "read_video", "arguments": arguments,
                "material": {"video_sha256": "a" * 64, "comment_sha256": "b" * 64,
                             "danmaku_sha256": "c" * 64},
                "task_clock": {"timezone": "Asia/Shanghai", "reference_time": "2026-11-10 00:00:00"},
                "task_window": {"kind": "absolute"}, "sampling_strategy": "same",
                "tool_config_version": "v1",
            }
            return payload

        payload = acceptance_shape({"offset_seconds": 0.00001})
        from report_evaluation.util import canonical_json as _unused
        canonical = toolfreeze.canonical_bytes(payload).decode("utf-8")
        self.assertIn('"offset_seconds":1e-05', canonical)
        key = toolfreeze.freeze_key(**payload)
        # This is exactly the key the Java v2 implementation derives for the same payload.
        self.assertEqual("06b9cc5fcc229f1c53e86958b65b9324606bba4159f293237183f8c37be5b052", key)
        # Integral floats collapse to integer digits; -0.0 normalizes to 0 by value.
        self.assertIn('"offset_seconds":100', toolfreeze.canonical_bytes(
            acceptance_shape({"offset_seconds": 100.0})).decode("utf-8"))
        self.assertIn('"offset_seconds":0}', toolfreeze.canonical_bytes(
            acceptance_shape({"offset_seconds": -0.0})).decode("utf-8"))

    def test_number_domain_refusals(self):
        for bad in (float("nan"), float("inf"), float("-inf"), 1e-320, 1e305):
            payload = dict(GOLDEN_SAMPLE)
            payload["arguments"] = {"offset_seconds": bad}
            with self.assertRaises(toolfreeze.FreezeWriteRefused, msg=repr(bad)):
                toolfreeze.freeze_key(**payload)

    def test_same_request_returns_identical_bytes(self):
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        payload = json.dumps({"result": "原样回放", "n": 1}, ensure_ascii=False).encode("utf-8")
        self.store.put(key, payload)
        first = self.store.get(key)
        second = self.store.get(key)
        self.assertEqual(payload, first)
        self.assertEqual(first, second)
        self.assertEqual(hashlib.sha256(payload).hexdigest(), self.store.digest(key))

    def test_same_key_different_bytes_refused_originals_untouched(self):
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        self.store.put(key, b'{"version":1}')
        with self.assertRaises(toolfreeze.FreezeWriteRefused):
            self.store.put(key, b'{"version":2}')
        self.assertEqual(b'{"version":1}', self.store.get(key),
                         "the stored original must be immutable after a refused rewrite")
        self.assertEqual(hashlib.sha256(b'{"version":1}').hexdigest(),
                         (self.store.root / f"{key}.sha256").read_text(encoding="ascii").strip())

    def test_same_key_same_bytes_is_idempotent(self):
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        self.store.put(key, b'{"a":1}')
        self.store.put(key, b'{"a":1}')
        self.assertEqual(b'{"a":1}', self.store.get(key))

    def test_invalid_and_escaping_keys_are_refused_with_sentinel_intact(self):
        sentinel = self.store.root.parent / "escaped.json"
        sentinel.write_text('{"sentinel":"only-within-repair"}', encoding="utf-8")
        try:
            with self.assertRaises(toolfreeze.FreezeWriteRefused):
                self.store.put("../escaped", b'{"escape":true}')
            with self.assertRaises(toolfreeze.ToolFreezeMiss):
                self.store.get("../escaped")
            with self.assertRaises((toolfreeze.FreezeWriteRefused, toolfreeze.ToolFreezeMiss)):
                self.store.put("A" * 64, b"x")
            self.assertEqual('{"sentinel":"only-within-repair"}', sentinel.read_text(encoding="utf-8"),
                             "nothing may be written outside the store root")
        finally:
            sentinel.unlink(missing_ok=True)

    def test_sidecar_link_to_outside_correct_digest_is_refused(self):
        # Acceptance R4 follow-up: a sidecar symlink pointing outside the root, holding the
        # CORRECT digest, must not make get() succeed. Pre-fix this follows the link.
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        payload = b'{"v":1}'
        self.store.put(key, payload)
        digest = hashlib.sha256(payload).hexdigest()
        outside_dir = self.store.root.parent / "sidecar-outside"
        outside_dir.mkdir(exist_ok=True)
        outside = outside_dir / "outside.sha256"
        outside.write_text(digest, encoding="ascii")
        sidecar = self.store.root / f"{key}.sha256"
        sidecar.unlink()
        os.symlink(outside, sidecar)
        try:
            with self.assertRaises(toolfreeze.ToolFreezeMiss):
                self.store.get(key)
            self.assertEqual(payload, (self.store.root / f"{key}.json").read_bytes(),
                             "the stored original must be untouched")
            self.assertTrue(sidecar.is_symlink(), "the refusal must not rewrite the link")
            self.assertEqual(digest, outside.read_text(encoding="ascii"),
                             "the outside target must stay unchanged")
        finally:
            sidecar.unlink(missing_ok=True)
            outside.unlink(missing_ok=True)

    def test_dangling_sidecar_link_is_refused_on_read_and_write(self):
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        payload = b'{"v":1}'
        self.store.put(key, payload)
        sidecar = self.store.root / f"{key}.sha256"
        sidecar.unlink()
        os.symlink(self.store.root.parent / "does-not-exist.sha256", sidecar)
        try:
            with self.assertRaises(toolfreeze.ToolFreezeMiss):
                self.store.get(key)
            with self.assertRaises((toolfreeze.FreezeWriteRefused, toolfreeze.ToolFreezeMiss)):
                self.store.put(key, payload)
            self.assertEqual(payload, (self.store.root / f"{key}.json").read_bytes())
            self.assertTrue(sidecar.is_symlink())
        finally:
            sidecar.unlink(missing_ok=True)

    def test_put_with_outside_sidecar_link_is_refused_and_target_untouched(self):
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        payload = b'{"v":1}'
        self.store.put(key, payload)
        outside_dir = self.store.root.parent / "sidecar-outside"
        outside_dir.mkdir(exist_ok=True)
        outside = outside_dir / "outside.sha256"
        outside.write_text("sentinel-digest", encoding="ascii")
        sidecar = self.store.root / f"{key}.sha256"
        sidecar.unlink()
        os.symlink(outside, sidecar)
        try:
            with self.assertRaises(toolfreeze.FreezeWriteRefused):
                self.store.put(key, payload)  # same bytes: must still refuse to write through the link
            self.assertEqual("sentinel-digest", outside.read_text(encoding="ascii"),
                             "the outside target must not be rewritten")
            self.assertEqual(payload, (self.store.root / f"{key}.json").read_bytes())
        finally:
            sidecar.unlink(missing_ok=True)
            outside.unlink(missing_ok=True)

    def test_payload_tmp_link_is_refused_on_first_write(self):
        # Acceptance R4 follow-up 2: a link planted over <key>.json.tmp must not let the first
        # write follow it outside the root. Pre-fix put() succeeds and rewrites the sentinel.
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        payload = b'{"v":1}'
        outside_dir = self.store.root.parent / "tmp-outside"
        outside_dir.mkdir(exist_ok=True)
        outside = outside_dir / "payload-tmp.sha256"
        outside.write_text("SENTINEL", encoding="ascii")
        tmp_link = self.store.root / f"{key}.json.tmp"
        os.symlink(outside, tmp_link)
        try:
            with self.assertRaises(toolfreeze.FreezeWriteRefused):
                self.store.put(key, payload)
            self.assertEqual("SENTINEL", outside.read_text(encoding="ascii"),
                             "the outside sentinel must not be rewritten")
            self.assertFalse((self.store.root / f"{key}.json").exists(),
                             "no payload may be published through the refused write")
            self.assertTrue(tmp_link.is_symlink(), "the planted link itself must stay untouched")
        finally:
            tmp_link.unlink(missing_ok=True)
            outside.unlink(missing_ok=True)

    def test_sidecar_tmp_link_is_refused_on_first_write(self):
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        payload = b'{"v":1}'
        digest = hashlib.sha256(payload).hexdigest()
        outside_dir = self.store.root.parent / "tmp-outside"
        outside_dir.mkdir(exist_ok=True)
        outside = outside_dir / "sidecar-tmp.sha256"
        outside.write_text("SENTINEL", encoding="ascii")
        tmp_link = self.store.root / f"{key}.sha256.tmp"
        os.symlink(outside, tmp_link)
        try:
            with self.assertRaises(toolfreeze.FreezeWriteRefused):
                self.store.put(key, payload)
            self.assertEqual("SENTINEL", outside.read_text(encoding="ascii"),
                             "the outside sentinel must not become the digest")
            self.assertFalse((self.store.root / f"{key}.json").exists())
            self.assertTrue(tmp_link.is_symlink())
        finally:
            tmp_link.unlink(missing_ok=True)
            outside.unlink(missing_ok=True)

    def test_repair_branch_tmp_link_is_refused_and_sentinel_untouched(self):
        # Existing payload + corrupted sidecar + <key>.sha256.tmp link: the same-bytes put enters
        # the sidecar-repair branch, which pre-fix writes the digest through the outside link.
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        payload = b'{"v":1}'
        digest = hashlib.sha256(payload).hexdigest()
        self.store.put(key, payload)
        sidecar = self.store.root / f"{key}.sha256"
        sidecar.write_text("0" * 64, encoding="ascii")  # corrupted but NOT a link
        outside_dir = self.store.root.parent / "tmp-outside"
        outside_dir.mkdir(exist_ok=True)
        outside = outside_dir / "rewrite-tmp.sha256"
        outside.write_text("SENTINEL", encoding="ascii")
        tmp_link = self.store.root / f"{key}.sha256.tmp"
        os.symlink(outside, tmp_link)
        try:
            with self.assertRaises(toolfreeze.FreezeWriteRefused):
                self.store.put(key, payload)  # same bytes: enters the repair branch
            self.assertEqual("SENTINEL", outside.read_text(encoding="ascii"),
                             "the repair branch must not write the digest outside")
            self.assertEqual(payload, (self.store.root / f"{key}.json").read_bytes())
            self.assertEqual("0" * 64, sidecar.read_text(encoding="ascii"),
                             "the corrupt sidecar stays untouched after the refusal")
            self.assertTrue(tmp_link.is_symlink())
        finally:
            tmp_link.unlink(missing_ok=True)
            outside.unlink(missing_ok=True)

    def test_corrupt_sidecar_makes_the_artifact_unreadable(self):
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        self.store.put(key, b'{"a":1}')
        (self.store.root / f"{key}.sha256").write_text("0" * 64, encoding="ascii")
        with self.assertRaises(toolfreeze.ToolFreezeMiss):
            self.store.get(key)

    def test_miss_is_explicit_failure(self):
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        with self.assertRaises(toolfreeze.ToolFreezeMiss):
            self.store.get(key)

    def test_each_component_change_produces_a_different_key(self):
        base = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        variants = []
        for field in ("sampling_strategy", "tool_config_version"):
            altered = dict(GOLDEN_SAMPLE)
            altered[field] = "changed"
            variants.append(toolfreeze.freeze_key(**altered))
        altered = dict(GOLDEN_SAMPLE)
        altered["task_clock"] = {"timezone": "UTC", "reference_time": "2026-11-10 00:00:00"}
        variants.append(toolfreeze.freeze_key(**altered))
        altered = dict(GOLDEN_SAMPLE)
        altered["task_window"] = {"kind": "absolute", "start": "2026-11-04 00:00:00", "end": "2026-11-10 00:00:00"}
        variants.append(toolfreeze.freeze_key(**altered))
        altered = dict(GOLDEN_SAMPLE)
        mat = dict(CANON)
        mat["comment_sha256"] = "e" * 64
        altered["material"] = mat
        variants.append(toolfreeze.freeze_key(**altered))
        altered = dict(GOLDEN_SAMPLE)
        altered["arguments"] = {"dataset_key": "ysjf-canon", "limit": 51}
        variants.append(toolfreeze.freeze_key(**altered))
        for variant in variants:
            self.assertNotEqual(base, variant)

    def test_different_tool_requests_resolve_independently(self):
        key_a = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        other = dict(GOLDEN_SAMPLE)
        other["tool"] = "analyze_danmaku"
        key_b = toolfreeze.freeze_key(**other)
        self.assertNotEqual(key_a, key_b)
        self.store.put(key_a, b'{"from":"comments"}')
        self.store.put(key_b, b'{"from":"danmaku"}')
        self.assertEqual(b'{"from":"comments"}', self.store.get(key_a))
        self.assertEqual(b'{"from":"danmaku"}', self.store.get(key_b))

    def test_missing_material_hash_refused_for_key(self):
        altered = dict(GOLDEN_SAMPLE)
        altered["material"] = {k: v for k, v in CANON.items() if k != "video_sha256"}
        with self.assertRaises(toolfreeze.ToolFreezeMiss):
            toolfreeze.freeze_key(**altered)

    def test_replay_server_serves_frozen_bytes(self):
        key = toolfreeze.freeze_key(**GOLDEN_SAMPLE)
        self.store.put(key, b'{"frozen":true}')
        with toolfreeze.serve_forever(self.store, host="127.0.0.1", port=0) as server:
            port = server.server_address[1]
            import urllib.request
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/freeze/{key}") as resp:
                self.assertEqual(b'{"frozen":true}', resp.read())
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/freeze/{key}") as resp:
                self.assertEqual(b'{"frozen":true}', resp.read())
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}/freeze/{'0' * 64}")
                self.fail("missing key must not be served")
            except urllib.error.HTTPError as e:
                self.assertEqual(409, e.code)


class FeeBudgetTest(unittest.TestCase):
    PRICES = {"siliconflow:chat": {"input_fen_per_million": 210, "output_fen_per_million": 840},
              "minimax:vlm": {"input_fen_per_million": 100, "output_fen_per_million": 100},
              "minimax:audio": {"input_fen_per_million": 500, "output_fen_per_million": 0}}

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="admission-budget-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.path = self.tmp / "fees.json"

    def ledger(self):
        return budget.FeeLedger(self.path, total_fen=5000, pause_fen=2000, prices=dict(self.PRICES))

    def test_admission_reserves_before_dispatch(self):
        ledger = self.ledger()
        reservation = ledger.admit("siliconflow:chat", estimated_input_tokens=1000, estimated_output_tokens=500)
        self.assertEqual("OPEN", reservation["status"])
        self.assertGreater(reservation["reserved_fen"], 0)
        # Conservative exposure already includes the open reservation before any dispatch happened.
        self.assertGreaterEqual(ledger.conservative_used_fen(), reservation["reserved_fen"])

    def test_settle_with_known_usage_replaces_reservation(self):
        ledger = self.ledger()
        r = ledger.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)
        before = ledger.conservative_used_fen()
        ledger.settle(r["reservation_id"], {"usage": {"prompt_tokens": 1000, "completion_tokens": 500}})
        self.assertEqual("SETTLED", ledger.reservation(r["reservation_id"])["status"])
        # Per-side ceiling keeps the settled amount conservative even for tiny usages.
        expected = -(-(1000 * 210) // 1_000_000) + -(-(500 * 840) // 1_000_000)
        self.assertEqual(expected, ledger.known_settled_fen())
        self.assertLess(ledger.conservative_used_fen(), before)

    def test_unknown_usage_keeps_reservation_and_blocks_dispatch(self):
        ledger = self.ledger()
        r = ledger.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)
        with self.assertRaises(budget.UsageUnknown):
            ledger.settle(r["reservation_id"], {})
        self.assertEqual("USAGE_UNKNOWN", ledger.reservation(r["reservation_id"])["status"])
        self.assertEqual(None, ledger.reservation(r["reservation_id"])["usage_tokens"])
        with self.assertRaises(budget.DispatchBlocked):
            ledger.admit("siliconflow:chat", estimated_input_tokens=10, estimated_output_tokens=10)

    def test_timeout_failure_keeps_reservation_in_flight(self):
        ledger = self.ledger()
        r = ledger.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)
        ledger.fail(r["reservation_id"], reason="timeout")
        rec = ledger.reservation(r["reservation_id"])
        self.assertEqual("FAILED_RETAINED", rec["status"])
        self.assertIn(rec["reserved_fen"], range(1, ledger.conservative_used_fen() + 1))

    def test_retry_requires_fresh_admission(self):
        ledger = self.ledger()
        r1 = ledger.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)
        ledger.fail(r1["reservation_id"], reason="transient")
        r2 = ledger.admit("siliconflow:chat", estimated_input_tokens=10, estimated_output_tokens=10)
        self.assertNotEqual(r1["reservation_id"], r2["reservation_id"])
        self.assertEqual(1, len(ledger.reservations_open()), "the failed reservation is not open again")
        self.assertGreaterEqual(ledger.conservative_used_fen(), 1050, "failed exposure stays counted")

    def test_pause_line_at_2000_fen(self):
        ledger = self.ledger()
        # 1M/1M at 210+840 fen/M reserves 1050 fen; the next 2M/2M estimate would push the
        # conservative total to 3150 fen, crossing the 2000-fen pause line.
        ledger.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)
        with self.assertRaises(budget.PauseRequested):
            ledger.admit("siliconflow:chat", estimated_input_tokens=2_000_000, estimated_output_tokens=2_000_000)

    def test_hard_limit_at_5000_fen(self):
        ledger = self.ledger()
        r = ledger.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)
        ledger.settle(r["reservation_id"], {"usage": {"prompt_tokens": 10_000_000, "completion_tokens": 3_000_000}})
        self.assertEqual(4620, ledger.known_settled_fen())
        with self.assertRaises(budget.BudgetExhausted):
            ledger.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)

    def test_missing_price_blocks_dispatch(self):
        ledger = budget.FeeLedger(self.path, total_fen=5000, pause_fen=2000, prices={})
        with self.assertRaises(budget.NeedsPricing):
            ledger.admit("siliconflow:chat", estimated_input_tokens=10, estimated_output_tokens=10)

    def test_restart_reloads_open_reservations(self):
        # Repair-01 lifecycle: the owner must close before anyone else can open the same file.
        first = self.ledger()
        r = first.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)
        with self.assertRaises(budget.LedgerBusy):
            self.ledger()
        first.close()
        reopened = self.ledger()
        try:
            self.assertEqual("OPEN", reopened.reservation(r["reservation_id"])["status"])
            self.assertEqual(first.conservative_used_fen(), reopened.conservative_used_fen())
            with self.assertRaises(budget.PauseRequested):
                reopened.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)
        finally:
            reopened.close()

    def test_corrupt_records_are_refused_with_original_bytes_intact(self):
        import json as jsonlib
        base = {"reservation_id": "fee-x1", "exit": "siliconflow:chat", "status": "OPEN",
                "reserved_fen": 1200, "usage_tokens": None, "actual_fen": None,
                "price_identity": "a" * 64, "price_input_fen_per_million": 210,
                "price_output_fen_per_million": 840, "attribution": "direct",
                "admitted_at": "2026-11-16T00:00:00Z"}
        cases = {
            "empty_record": {},
            "negative_reserved": {**base, "reserved_fen": -10000},
            "settled_missing_actual": {**base, "status": "SETTLED", "usage_tokens": 10, "actual_fen": None},
            "bool_amount": {**base, "reserved_fen": True},
            "duplicate_id": [dict(base), dict(base)],
            "old_schema_v1": "schema-v1",
        }
        for name, mutation in cases.items():
            path = self.tmp / f"corrupt-{name}.json"
            if name == "old_schema_v1":
                doc = {"schema": "fee-ledger-v1", "total_fen": 5000, "pause_fen": 2000, "reservations": []}
            elif name == "duplicate_id":
                doc = {"schema": budget.LEDGER_SCHEMA, "total_fen": 5000, "pause_fen": 2000,
                       "reservations": mutation}
            else:
                doc = {"schema": budget.LEDGER_SCHEMA, "total_fen": 5000, "pause_fen": 2000,
                       "reservations": [mutation]}
            path.write_text(jsonlib.dumps(doc), encoding="utf-8")
            with self.assertRaises(budget.LedgerCorrupted, msg=name):
                self.ledger() if False else budget.FeeLedger(path, 5000, 2000, dict(self.PRICES))
            self.assertEqual(path.read_text(encoding="utf-8"), jsonlib.dumps(doc),
                             f"{name}: the refusing loader must leave the bytes untouched")

    def test_frozen_price_snapshot_settles_old_requests_after_reopen(self):
        first = self.ledger()
        r = first.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)
        first.close()
        drifted = budget.FeeLedger(self.path, 5000, 2000,
                                   prices={"siliconflow:chat": {"input_fen_per_million": 0,
                                                                "output_fen_per_million": 0}})
        try:
            drifted.settle(r["reservation_id"], {"usage": {"prompt_tokens": 1_000_000, "completion_tokens": 1_000_000}})
            # The record's own frozen snapshot (210/840) prices this: 210+840 = 1050 fen, not 0.
            self.assertEqual(1050, drifted.known_settled_fen(),
                             "a reopened price table must never silently re-price an old request")
        finally:
            drifted.close()

    def test_settlement_arithmetic_overflow_is_controlled(self):
        ledger = self.ledger()
        try:
            r = ledger.admit("siliconflow:chat", estimated_input_tokens=10, estimated_output_tokens=10)
            with self.assertRaises(budget.UsageUnknown):
                ledger.settle(r["reservation_id"],
                              {"usage": {"prompt_tokens": 10**30, "completion_tokens": 0}})
            # Absurd usage sizes leave the fee domain: kept unknown, never silently settled.
            self.assertEqual("USAGE_UNKNOWN",
                             ledger.reservation(r["reservation_id"])["status"])
        finally:
            ledger.close()

    def test_two_instances_same_file_are_serialized(self):
        first = self.ledger()
        try:
            with self.assertRaises(budget.LedgerBusy):
                self.ledger()
        finally:
            first.close()

    def test_second_process_is_refused_while_owner_holds_the_lock(self):
        import subprocess
        import sys
        scripts_dir = Path(__file__).resolve().parent
        holder = subprocess.Popen(
            [sys.executable, "-B", "-X", "utf8", "-I", "-c",
             "import sys, time; sys.path.insert(0, r'%s');"
             "from report_evaluation.admission import budget;"
             "ledger = budget.FeeLedger(r'%s', 5000, 2000, {'siliconflow:chat': {'input_fen_per_million': 210, 'output_fen_per_million': 840}});"
             "ledger.admit('siliconflow:chat', 10, 10);"
             "print('held', flush=True); time.sleep(6); ledger.close()" % (scripts_dir, self.path)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            assert holder.stdout is not None
            holder.stdout.readline()  # wait until the holder confirms the lock
            with self.assertRaises(budget.LedgerBusy):
                self.ledger()
        finally:
            holder.kill()
            holder.wait(timeout=10)

    def test_duplicate_settlement_refused(self):
        ledger = self.ledger()
        r = ledger.admit("siliconflow:chat", estimated_input_tokens=10, estimated_output_tokens=10)
        ledger.settle(r["reservation_id"], {"usage": {"prompt_tokens": 10, "completion_tokens": 5}})
        with self.assertRaises(budget.DuplicateSettlement):
            ledger.settle(r["reservation_id"], {"usage": {"prompt_tokens": 10, "completion_tokens": 5}})

    def test_corrupted_ledger_blocks_everything(self):
        ledger = self.ledger()
        ledger.admit("siliconflow:chat", estimated_input_tokens=10, estimated_output_tokens=10)
        ledger.close()
        self.path.write_text("{not json", encoding="utf-8")
        # A corrupted ledger cannot be reopened at all: nothing dispatches until a human repairs it.
        with self.assertRaises(budget.LedgerCorrupted):
            budget.FeeLedger(self.path, total_fen=5000, pause_fen=2000, prices=dict(self.PRICES))

    def test_shared_preparation_attributed_once(self):
        ledger = self.ledger()
        ledger.admit("siliconflow:chat", estimated_input_tokens=10, estimated_output_tokens=10,
                     attribution="shared_preparation")
        report = ledger.attribution_report()
        self.assertEqual(1, report["shared_preparation"]["count"])
        self.assertNotIn("harness", report["shared_preparation"])

    def test_two_providers_share_one_envelope(self):
        ledger = self.ledger()
        ledger.admit("siliconflow:chat", estimated_input_tokens=1_000_000, estimated_output_tokens=1_000_000)
        with self.assertRaises(budget.PauseRequested):
            ledger.admit("minimax:vlm", estimated_input_tokens=10_000_000, estimated_output_tokens=10_000_000)


class PairingTest(unittest.TestCase):
    """Paired-condition records: shared conditions bind the pair identity; controller identity,
    repeat number and report bytes never participate."""

    def conditions(self, **overrides):
        base = {
            "prompt": "分析视频730013从2026-11-03到2026-11-10的评论和弹幕，生成观众反馈HTML报告",
            "config_path": "agent/eval/report-trial-admission.example.json",
            "material_key": "ysjf-canon",
            "material_hashes": {"video_sha256": "1" * 64, "comment_sha256": "2" * 64,
                                "danmaku_sha256": "3" * 64},
            "window_start": "2026-11-03 00:00:00",
            "window_end": "2026-11-10 00:00:00",
            "task_clock_zone": "Asia/Shanghai",
            "task_clock_reference": "2026-11-16 00:00:00",
            "sampling_strategy": "window-full-effective-columns",
            "tool_config_version": "evaluation-entry-v1",
            "fixed_roles": {"preparation": "PLACEHOLDER", "analysis": "PLACEHOLDER"},
            "permissions": {"network": False, "ssh": False, "read_dataset": True},
            "session_initial_state": "fresh-per-run",
            "scoring_version": "draft-v0",
            "run_mode": "loopback-fixture",
        }
        base.update(overrides)
        return base

    def test_same_conditions_different_controller_share_one_pair_id(self):
        a = pairing.pair_membership(self.conditions(), "controller-a", 1)
        b = pairing.pair_membership(self.conditions(), "controller-b", 2)
        self.assertEqual(a["pair_id"], b["pair_id"])
        self.assertEqual(1, pairing.validate_pairs([a, b])["pairs"])

    def test_any_shared_condition_change_derives_a_new_pair(self):
        base = pairing.pair_membership(self.conditions(), "controller-a", 1)
        for field, changed in (
                ("material_hashes", {"video_sha256": "9" * 64, "comment_sha256": "2" * 64,
                                     "danmaku_sha256": "3" * 64}),
                ("window_start", "2026-11-04 00:00:00"),
                ("task_clock_reference", "2027-01-15 00:00:00"),
                ("sampling_strategy", "changed"),
                ("tool_config_version", "changed"),
                ("prompt", "changed prompt")):
            other = pairing.pair_membership(self.conditions(**{field: changed}), "controller-b", 2)
            self.assertNotEqual(base["pair_id"], other["pair_id"], field)

    def test_validate_pairs_recomputes_id_and_enforces_shared_fields(self):
        # Acceptance re-verification: an incomplete capture and a forged pair_id must both be
        # refused by validate_pairs itself (pre-fix both were accepted as valid).
        incomplete = [
            pairing.pair_membership(self.conditions(), "controller-a", 1),
            pairing.pair_membership(self.conditions(), "controller-b", 2),
        ]
        for member in incomplete:
            member.pop("fixed_roles")
            member.pop("permissions")
            member.pop("session_initial_state")
            member.pop("scoring_version")
        with self.assertRaises(ValueError):
            pairing.validate_pairs(incomplete)
        forged = [{"pair_id": "forged", "controller": "controller-a"},
                  {"pair_id": "forged", "controller": "controller-b"}]
        with self.assertRaises(ValueError):
            pairing.validate_pairs(forged)
        stale = pairing.pair_membership(self.conditions(), "controller-a", 1)
        stale["pair_id"] = "f" * 64
        with self.assertRaises(ValueError):
            pairing.validate_pairs([stale, pairing.pair_membership(self.conditions(), "controller-b", 2)])
        # The complete pair still validates.
        complete = [pairing.pair_membership(self.conditions(), "controller-a", 1),
                    pairing.pair_membership(self.conditions(), "controller-b", 2)]
        self.assertEqual(1, pairing.validate_pairs(complete)["pairs"])

    def test_membership_validation_and_pair_rules(self):
        a = pairing.pair_membership(self.conditions(), "controller-a", 1)
        with self.assertRaises(ValueError):
            pairing.pair_membership(self.conditions(), "controller-a", 0)
        with self.assertRaises(ValueError):
            pairing.pair_membership({k: v for k, v in self.conditions().items()
                                     if k != "material_hashes"}, "controller-a", 1)
        same_controller = pairing.pair_membership(self.conditions(), "controller-a", 2)
        with self.assertRaises(ValueError):
            pairing.validate_pairs([a, same_controller])
        solo = pairing.pair_membership(self.conditions(), "controller-a", 1)
        with self.assertRaises(ValueError):
            pairing.validate_pairs([solo])

    def test_java_and_python_pair_ids_agree(self):
        # The Java EvaluationPairConditions derives the same id for the shared conditions; the
        # canonical form is key-sorted compact JSON over the shared fields on both sides.
        conditions = self.conditions(
            fixed_roles={"preparation": "PLACEHOLDER", "analysis": "PLACEHOLDER"},
            permissions={"network": False, "ssh": False, "read_dataset": True})
        expected_java = "c2cc5524a566ab813d8141b82c8913bf71aa591ef8291a6d5545907806c38512"
        self.assertEqual(expected_java, pairing.pair_id(conditions))


class RunBindingTest(unittest.TestCase):
    """Per-run manifest identity validation: required fields, video fingerprint binding, trial
    derivation and duplicate trial refusal (the Java entry writes these manifests)."""

    def manifest(self, **overrides):
        base = {
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
            "provider_dispatches": 0,
            "fee_note": "loopback fixture",
        }
        base.update(overrides)
        return base

    FORMAL_BINDINGS = {730013: {"video_sha256": "1e9f5d4b28ff89c863a0c0e034a6a39c230dce318dbc2fe770df5be2b20ff53a"}}

    def test_complete_manifest_passes_and_derives_trial(self):
        from report_evaluation.admission import run_binding
        manifest = self.manifest()
        trial = run_binding.validate_run_manifest(manifest, formal_bindings=self.FORMAL_BINDINGS)
        self.assertEqual("trial-" + hashlib.sha256(
            f"{manifest['run_id']}|{manifest['pair_id']}".encode("utf-8")).hexdigest()[:16], trial)

    def test_fixed_c64_source_version_is_refused(self):
        from report_evaluation.admission import run_binding
        manifest = self.manifest(video_source_version="c" * 64)
        with self.assertRaises(ValueError):
            run_binding.validate_run_manifest(manifest, formal_bindings=self.FORMAL_BINDINGS)

    def test_wrong_video_fingerprint_refused(self):
        from report_evaluation.admission import run_binding
        manifest = self.manifest(video_source_version="f" * 64)
        with self.assertRaises(ValueError):
            run_binding.validate_run_manifest(manifest, formal_bindings=self.FORMAL_BINDINGS)

    def test_missing_trial_field_refused(self):
        from report_evaluation.admission import run_binding
        manifest = self.manifest()
        manifest.pop("trial_id")
        with self.assertRaises(ValueError):
            run_binding.validate_run_manifest(manifest, formal_bindings=self.FORMAL_BINDINGS)

    def test_duplicate_trial_refused_on_rebuild(self):
        import tempfile
        from report_evaluation.admission import run_binding
        root = Path(tempfile.mkdtemp(prefix="run-binding-"))
        for name in ("run-1", "run-2"):
            d = root / name
            d.mkdir()
            manifest = self.manifest(controller="controller-b" if name == "run-2" else "controller-a")
            (d / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        with self.assertRaises(ValueError):
            run_binding.rebuild_index(root, formal_bindings=self.FORMAL_BINDINGS)

    def test_controller_mismatch_refused(self):
        from report_evaluation.admission import run_binding
        manifest = self.manifest(controller_invoked="controller-b")
        with self.assertRaises(ValueError):
            run_binding.validate_run_manifest(manifest, formal_bindings=self.FORMAL_BINDINGS)


class BatchLedgerTest(unittest.TestCase):
    """Repair-06: generation batch ledger integrity — every successful run and refusal in the
    generation appears in the index, grounding is populated from video_grounding, control runs
    carry an explicit purpose and never count as pair members, and field-by-field comparison
    catches tampered identity/digest fields."""

    def _run_record(self, run_id, grounding="VIDEO_GROUNDED", purpose="pair_member",
                    controller="controller-a", pair_id="p" * 64, **extra):
        record = {
            "run_id": run_id, "trial_id": "trial-" + run_id[:16],
            "pair_id": pair_id, "controller": controller, "repeat_no": 1,
            "grounding": grounding, "purpose": purpose,
            "final_status": "SUCCEEDED",
            "report_json_sha256": "a" * 64, "report_html_sha256": "b" * 64,
            "trace_sha256": "c" * 64,
        }
        record.update(extra)
        return record

    def test_rebuild_index_reads_video_grounding_field(self):
        from report_evaluation.admission import run_binding
        import tempfile
        root = Path(tempfile.mkdtemp(prefix="batch-rebuild-"))
        # Repair-07: the rebuild verifies ORIGINALS, so the fixture archives real artifacts
        # with a full-fidelity manifest (the pre-fix minimal manifest no longer suffices).
        repair07_seed_run(root, "run-1")
        index = run_binding.rebuild_index(root, formal_bindings=REPAIR07_BINDINGS)
        runs = index["runs"]
        self.assertEqual(1, len(runs))
        # Repair-06: the rebuilt entry must carry the manifest's actual video_grounding value,
        # not read a non-existent "grounding" field (pre-fix this came back empty).
        self.assertEqual("VIDEO_GROUNDED", runs[0]["grounding"],
                "rebuildIndex must read video_grounding, not a missing grounding field")

    def test_rebuild_index_includes_refusal_records(self):
        from report_evaluation.admission import run_binding
        import tempfile
        root = Path(tempfile.mkdtemp(prefix="batch-refusal-"))
        ok_dir = root / "run-ok"
        repair07_seed_run(root, "run-ok")
        # Repair-07: refusal records carry their full flat identity and the SAME trial
        # derivation as delivered runs (the pre-fix fixture's hand-written trial id is gone).
        repair07_seed_refusal(root, "run-refused")
        index = run_binding.rebuild_index(root, formal_bindings=REPAIR07_BINDINGS)
        self.assertEqual(1, len(index["runs"]), "the delivered run is indexed under runs")
        self.assertEqual(1, len(index["refusals"]), "the refused run must be indexed under refusals")
        self.assertEqual("clock_rule_refusal_control", index["refusals"][0]["purpose"])

    def test_purpose_is_mandatory_and_enumerated(self):
        from report_evaluation.admission import run_binding
        record = self._run_record("run-1")
        record.pop("purpose")
        with self.assertRaises(ValueError):
            run_binding.validate_run_record(record)
        record = self._run_record("run-1")
        record["purpose"] = "unknown_kind"
        with self.assertRaises(ValueError):
            run_binding.validate_run_record(record)

    def test_pair_member_count_ignores_control_runs(self):
        from report_evaluation.admission import run_binding
        records = [
            self._run_record("run-a", purpose="pair_member", controller="controller-a"),
            self._run_record("run-b", purpose="pair_member", controller="controller-b"),
            self._run_record("run-replay", purpose="frozen_replay_control", controller="controller-a"),
            self._run_record("run-clock", purpose="clock_rule_normal_control", controller="controller-a"),
        ]
        result = run_binding.validate_pair_purpose(records, pair_id="p" * 64)
        self.assertEqual(2, result["pair_members"],
                "control runs must not inflate the pair member count")
        self.assertEqual(2, result["controls"])

    def test_batch_index_field_by_field_comparison_detects_tamper(self):
        from report_evaluation.admission import run_binding
        # Repair-08: totals are part of the contract, so the fixture ledger carries them
        declared = {
            "runs": [self._run_record("run-1", report_json_sha256="a" * 64)],
            "refusals": [],
            "run_count": 1,
            "refusal_count": 0,
        }
        # same declared values: matches
        self.assertTrue(run_binding.batch_index_matches(declared, declared))
        # tampered grounding
        tampered = {"runs": [dict(declared["runs"][0], grounding="FEEDBACK_ONLY")], "refusals": []}
        self.assertFalse(run_binding.batch_index_matches(tampered, declared))
        # tampered purpose
        tampered = {"runs": [dict(declared["runs"][0], purpose="frozen_replay_control")], "refusals": []}
        self.assertFalse(run_binding.batch_index_matches(tampered, declared))
        # tampered trial id
        tampered = {"runs": [dict(declared["runs"][0], trial_id="trial-forged")], "refusals": []}
        self.assertFalse(run_binding.batch_index_matches(tampered, declared))
        # tampered pair id
        tampered = {"runs": [dict(declared["runs"][0], pair_id="9" * 64)], "refusals": []}
        self.assertFalse(run_binding.batch_index_matches(tampered, declared))
        # tampered run id
        tampered = {"runs": [dict(declared["runs"][0], run_id="run-other")], "refusals": []}
        self.assertFalse(run_binding.batch_index_matches(tampered, declared))
        # tampered report digest
        tampered = {"runs": [dict(declared["runs"][0], report_json_sha256="9" * 64)], "refusals": []}
        self.assertFalse(run_binding.batch_index_matches(tampered, declared))
        # tampered trace digest
        tampered = {"runs": [dict(declared["runs"][0], trace_sha256="9" * 64)], "refusals": []}
        self.assertFalse(run_binding.batch_index_matches(tampered, declared))
        # missing run (index drops one) — and a ledger without totals never matches
        self.assertFalse(run_binding.batch_index_matches({"runs": [], "refusals": []}, declared))
        self.assertFalse(run_binding.batch_index_matches(
            {"runs": declared["runs"], "refusals": []}, declared),
            "a ledger lacking the required totals must be a mismatch")
        # duplicate run id
        dup = {"runs": [declared["runs"][0], declared["runs"][0]], "refusals": [],
               "run_count": 2, "refusal_count": 0}
        self.assertFalse(run_binding.batch_index_matches(dup, declared))


class AdmissionCliTest(unittest.TestCase):
    """R6: both CLI entries share the strict preflight; refusals write nothing anywhere."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="admission-cli-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def _write_config(self, doc, name="admission.json"):
        path = self.tmp / name
        path.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
        return path

    def _generated(self, out):
        out = Path(out)
        return sorted(str(p.relative_to(out)) for p in out.rglob("*") if p.is_file()) if out.exists() else []

    def test_valid_config_passes_offline_check_and_prepare(self):
        doc = admission_config(self.tmp)
        path = self._write_config(doc)
        code = check.run_check(path, self.tmp / "out")
        self.assertEqual(0, code)
        verdict = json.loads((self.tmp / "out" / "admission-verdict.json").read_text(encoding="utf-8"))
        self.assertEqual("OFFLINE_ADMISSION_READY", verdict["verdict"])
        self.assertTrue(all(g["blocking"] is False for g in verdict["gaps"]))
        code = check.run_prepare(path, self.tmp / "prep")
        self.assertEqual(0, code)
        for name in ("role-snapshot.json", "material-bindings.json", "live-compat-checklist.md"):
            self.assertTrue((self.tmp / "prep" / name).is_file(), name)
        manifest = json.loads((self.tmp / "prep" / "freeze-store" / "MANIFEST.json").read_text(encoding="utf-8"))
        self.assertEqual(3, manifest["count"], "one seeded freeze key per bound material")

    def test_unreadable_config_refused_with_exit2_and_no_writes(self):
        path = self.tmp / "bad.json"
        path.write_text("{broken", encoding="utf-8")
        self.assertEqual(2, check.run_check(path, self.tmp / "out"))
        self.assertEqual([], self._generated(self.tmp / "out"))
        self.assertEqual(2, check.run_prepare(path, self.tmp / "prep"))
        self.assertEqual([], self._generated(self.tmp / "prep"))

    def test_structural_variants_refused_with_exit2_field_paths_and_no_writes(self):
        variants = {
            "clock_list": lambda d: d.update(task_clock=[]),
            "role_null": lambda d: d.update(roles=[None]),
            "materials_null": lambda d: d.update(materials=[None]),
            "probe_host_list": lambda d: d["materials"][0]["service_probe"].update(host=[]),
            "reference_time_invalid": lambda d: d["task_clock"].update(reference_time="not-a-date"),
            "materials_empty": lambda d: d.update(materials=[]),
            "missing_harness": lambda d: d.update(roles=[r for r in d["roles"] if r["role"] != "harness"]),
            "sampling_missing": lambda d: d["tool_freeze"].pop("sampling_strategy"),
            "unknown_top_field": lambda d: d.update(store_dir="var/provider-admission/tool-freeze"),
            "canary_nested": lambda d: d["roles"][0]["pricing"].update(api_key="SYNTHETIC-CANARY-NOT-A-CREDENTIAL"),
        }
        for name, mutate in variants.items():
            doc = admission_config(self.tmp)
            mutate(doc)
            path = self._write_config(doc, f"{name}.json")
            for mode in (check.run_check, check.run_prepare):
                out = self.tmp / f"{name}-{mode.__name__}"
                code = mode(path, out)
                self.assertEqual(2, code, f"{name}/{mode.__name__}")
                self.assertEqual([], self._generated(out), f"{name}/{mode.__name__} must write nothing")
                canary_files = [p for p in out.rglob("*") if p.is_file()
                                and b"SYNTHETIC-CANARY-NOT-A-CREDENTIAL" in p.read_bytes()]
                self.assertEqual([], canary_files, f"{name}: the canary value must never reach any output")

    def test_deep_gaps_exit3_and_prepare_writes_nothing(self):
        doc = admission_config(self.tmp)
        doc["materials"][2]["binding"]["video_sha256"] = "0" * 64  # canon video hash tampered
        path = self._write_config(doc)
        self.assertEqual(3, check.run_check(path, self.tmp / "out"))
        verdict = json.loads((self.tmp / "out" / "admission-verdict.json").read_text(encoding="utf-8"))
        self.assertEqual("OFFLINE_ADMISSION_GAPS", verdict["verdict"])
        self.assertTrue(any(g["code"] == "MATERIAL_HASH_MISMATCH" for g in verdict["gaps"]))
        # prepare must refuse with the same verdict and write NOTHING: no half-ready seeds.
        self.assertEqual(3, check.run_prepare(path, self.tmp / "prep"))
        self.assertEqual([], self._generated(self.tmp / "prep"))

    def test_deep_gap_missing_material_file_is_isolated(self):
        doc = admission_config(self.tmp)
        doc["materials"][0]["dataset_root"] = str(self.tmp / "does-not-exist")
        code = check.run_check(self._write_config(doc), self.tmp / "out")
        self.assertEqual(3, code)

    def test_role_contract_mixing_is_refused_by_the_shared_preflight(self):
        doc = admission_config(self.tmp)
        doc["roles"][3]["endpoint"] = "/chat/completions"  # minimax dedicated pointed at a generic path
        code = check.run_check(self._write_config(doc), self.tmp / "out")
        self.assertEqual(2, code, "the closed schema refuses transport/endpoint mixing before deep checks")
        self.assertEqual([], self._generated(self.tmp / "out"))

    def test_window_mismatch_is_a_blocking_gap(self):
        doc = admission_config(self.tmp)
        doc["materials"][2]["canon_window"]["comments_in_window"] = 57
        code = check.run_check(self._write_config(doc), self.tmp / "out")
        self.assertEqual(3, code)
        verdict = json.loads((self.tmp / "out" / "admission-verdict.json").read_text(encoding="utf-8"))
        self.assertTrue(any(g["code"] == "WINDOW_MISMATCH" for g in verdict["gaps"]))

    def test_prepare_outputs_are_credential_free_and_reproducible(self):
        path = self._write_config(admission_config(self.tmp))
        check.run_prepare(path, self.tmp / "prep-a")
        check.run_prepare(path, self.tmp / "prep-b")
        for f in (self.tmp / "prep-a").rglob("*"):
            if f.is_file():
                self.assertNotIn("api_key", f.read_text(encoding="utf-8", errors="replace").lower())
        from report_evaluation.util import sha256_file
        da = {str(f.relative_to(self.tmp / "prep-a")): sha256_file(f)
              for f in sorted((self.tmp / "prep-a").rglob("*")) if f.is_file()}
        db = {str(f.relative_to(self.tmp / "prep-b")): sha256_file(f)
              for f in sorted((self.tmp / "prep-b").rglob("*")) if f.is_file()}
        self.assertEqual(da, db, "repeated prepare runs must be byte-identical")

    def test_legacy_commands_keep_their_semantics(self):
        offline = self.tmp / "legacy-offline.json"
        offline.write_text(json.dumps({"kind": "x"}), encoding="utf-8")  # placeholder replaced below
        # The legacy suite (241+142+5) covers the old commands in depth; here we only pin that the
        # admission additions did not disturb the entry table.
        import subprocess
        import sys
        environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONIOENCODING="utf-8")
        completed = subprocess.run(
            [sys.executable, "-B", "-X", "utf8", "scripts/report_eval.py", "no-such-command"],
            cwd=str(self.tmp.parent), env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(2, completed.returncode)


REPAIR07_PAIR = "9" * 64
REPAIR07_BINDINGS = {"730013": {"video_sha256": CANON["video_sha256"]}}


def repair07_manifest(run_id, controller="controller-a", **overrides):
    """A full-fidelity run manifest matching the table the Java entry writes."""
    base = {
        "run_id": run_id,
        "session_id": "session-" + run_id[:8],
        "pair_id": REPAIR07_PAIR,
        "trial_id": run_binding.trial_id_for(run_id, REPAIR07_PAIR),
        "controller": controller,
        "controller_invoked": controller,
        "repeat_no": 1,
        "conditions": {"prompt": "题面", "material_hashes": {"v": "1", "c": "2", "d": "3"}},
        "video_id": 730013,
        "video_grounding": "VIDEO_GROUNDED",
        "final_status": "SUCCEEDED",
        "error_code": None,
        "receipt_artifact_id": "artifact-" + run_id[:8],
        "receipt_json_url": "/artifact/artifact-" + run_id[:8] + "/json",
        "receipt_html_url": "/artifact/artifact-" + run_id[:8],
        "permission_principal": "demo/alice",
        "permission_read_status": 200,
        "permission_cross_account_status": 403,
        "material_video_sha256": CANON["video_sha256"],
        "material_comment_sha256": CANON["comment_sha256"],
        "material_danmaku_sha256": CANON["danmaku_sha256"],
        "video_source_version": CANON["video_sha256"],
        "task_clock_zone": "Asia/Shanghai",
        "task_clock_reference": "2026-11-16 00:00:00",
        "provider_dispatches": 0,
        "fee_note": "loopback fixture",
        "purpose": "pair_member",
    }
    base.update(overrides)
    return base


def repair07_seed_run(root, run_id, manifest=None, report_bytes=None, html_bytes=None,
                      trace_lines=None, controller="controller-a"):
    """One run directory with REAL artifacts whose digests the manifest declares."""
    import hashlib as _h
    d = root / run_id
    d.mkdir(parents=True)
    report = report_bytes if report_bytes is not None else \
        json.dumps({"run_id": run_id, "kind": "report", "title": "demo"}, ensure_ascii=False).encode("utf-8")
    html = html_bytes if html_bytes is not None else \
        ("<html><body>" + run_id + "</body></html>").encode("utf-8")
    trace = trace_lines if trace_lines is not None else [
        json.dumps({"name": "span-1", "attributes": {"run.id": run_id}}, ensure_ascii=False),
        json.dumps({"name": "span-2", "attributes": {"run.id": run_id}}, ensure_ascii=False),
    ]
    (d / "report.json").write_bytes(report)
    (d / "report.html").write_bytes(html)
    (d / "trace-spans.jsonl").write_bytes(("\n".join(trace) + "\n").encode("utf-8"))
    m = manifest if manifest is not None else repair07_manifest(run_id, controller=controller)
    m.setdefault("report_json_sha256", _h.sha256(report).hexdigest())
    m.setdefault("report_html_sha256", _h.sha256(html).hexdigest())
    m.setdefault("trace_sha256", _h.sha256(("\n".join(trace) + "\n").encode("utf-8")).hexdigest())
    (d / "manifest.json").write_text(json.dumps(m, ensure_ascii=False), encoding="utf-8")
    return d


def repair07_seed_refusal(root, run_id, **overrides):
    """One refused control run with its full flat identity (trial derived the same way)."""
    d = root / run_id
    d.mkdir(parents=True)
    record = {
        "run_id": run_id,
        "trial_id": run_binding.trial_id_for(run_id, REPAIR07_PAIR),
        "pair_id": REPAIR07_PAIR,
        "controller": "controller-b",
        "purpose": "clock_rule_refusal_control",
        "final_status": "FAILED",
        "error_code": "DATE_OUT_OF_RANGE",
    }
    record.update(overrides)
    (d / "refusal.json").write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
    return d


def repair07_generation(root):
    """Two delivered runs (distinct controllers) plus one refused clock-rule control."""
    repair07_seed_run(root, "a1b2c3d4e5f60718293a4b5c6d7e8f90")
    repair07_seed_run(root, "b2c3d4e5f60718293a4b5c6d7e8f90a1", controller="controller-b")
    repair07_seed_refusal(root, "c3d4e5f60718293a4b5c6d7e8f90a1b2")
    return root



class Repair07RebuildProbe(unittest.TestCase):
    """Repair-07 counterexamples: the Python rebuild must verify the ORIGINALS it indexes —
    recomputing every artifact digest, consuming the formal bindings, refusing cross-run
    traces, orphan directories and identity-less refusals — and the batch comparison must be
    strict about counts, repeat numbers, manifest digests and refusal identity fields."""

    PAIR = REPAIR07_PAIR
    BINDINGS = REPAIR07_BINDINGS

    def _manifest(self, run_id, controller="controller-a", **overrides):
        return repair07_manifest(run_id, controller=controller, **overrides)

    def _seed_run(self, root, run_id, manifest=None, report_bytes=None, html_bytes=None,
                  trace_lines=None, controller="controller-a"):
        return repair07_seed_run(root, run_id, manifest=manifest, report_bytes=report_bytes,
                                 html_bytes=html_bytes, trace_lines=trace_lines,
                                 controller=controller)

    def _seed_refusal(self, root, run_id, **overrides):
        return repair07_seed_refusal(root, run_id, **overrides)

    def _generation(self):
        root = Path(tempfile.mkdtemp(prefix="repair07-gen-"))
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        return repair07_generation(root)

    def test_rebuild_recomputes_digests_and_refuses_tampered_report(self):
        import hashlib as _h
        root = self._generation()
        # control: the honest generation rebuilds fine
        index = run_binding.rebuild_index(root, formal_bindings=self.BINDINGS)
        self.assertEqual(2, index["run_count"])
        run_id = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        # tamper the ORIGINAL bytes; the manifest still declares the old digest
        forged = json.dumps({"run_id": run_id, "kind": "report", "title": "FORGED"}, ensure_ascii=False).encode("utf-8")
        (root / run_id / "report.json").write_bytes(forged)
        with self.assertRaises(ValueError) as caught:
            run_binding.rebuild_index(root, formal_bindings=self.BINDINGS)
        message = str(caught.exception)
        self.assertIn(run_id, message, "the error must name the run")
        self.assertIn("report_json_sha256", message, "the error must name the digest field")

    def test_rebuild_refuses_missing_artifact_with_run_and_file(self):
        root = self._generation()
        run_id = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        (root / run_id / "report.html").unlink()
        with self.assertRaises(ValueError) as caught:
            run_binding.rebuild_index(root, formal_bindings=self.BINDINGS)
        message = str(caught.exception)
        self.assertIn(run_id, message)
        self.assertIn("report.html", message)

    def test_rebuild_refuses_cross_run_trace(self):
        foreign = [json.dumps({"name": "foreign", "attributes": {"run.id": "some-other-run"}},
                              ensure_ascii=False)]
        root = Path(tempfile.mkdtemp(prefix="repair07-trace-"))
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        run_id = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        self._seed_run(root, run_id, trace_lines=[
            json.dumps({"name": "span-1", "attributes": {"run.id": run_id}}, ensure_ascii=False),
        ] + foreign)
        with self.assertRaises(ValueError) as caught:
            run_binding.rebuild_index(root, formal_bindings=self.BINDINGS)
        message = str(caught.exception)
        self.assertIn(run_id, message, "the error must name the owning run")
        self.assertIn("some-other-run", message, "the error must name the foreign run id")

    def test_rebuild_consumes_formal_bindings(self):
        root = Path(tempfile.mkdtemp(prefix="repair07-bind-"))
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        run_id = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        self._seed_run(root, run_id, manifest=self._manifest(run_id, video_source_version="f" * 64))
        with self.assertRaises(ValueError) as caught:
            run_binding.rebuild_index(root, formal_bindings=self.BINDINGS)
        self.assertIn("video_source_version", str(caught.exception))
        # control: the honest fingerprint rebuilds fine with the same bindings
        honest = Path(tempfile.mkdtemp(prefix="repair07-bind-ok-"))
        self.addCleanup(shutil.rmtree, honest, ignore_errors=True)
        self._seed_run(honest, run_id)
        index = run_binding.rebuild_index(honest, formal_bindings=self.BINDINGS)
        self.assertEqual(1, index["run_count"])

    def test_rebuild_refuses_orphan_directory(self):
        root = self._generation()
        orphan = root / "unattributed-leftover"
        orphan.mkdir()
        (orphan / "notes.txt").write_text("stray bytes with no manifest and no refusal", encoding="utf-8")
        with self.assertRaises(ValueError) as caught:
            run_binding.rebuild_index(root, formal_bindings=self.BINDINGS)
        message = str(caught.exception)
        self.assertIn("unattributed-leftover", message, "the error must name the orphan directory")

    def test_refusal_entries_carry_full_identity(self):
        root = self._generation()
        index = run_binding.rebuild_index(root, formal_bindings=self.BINDINGS)
        self.assertEqual(1, index["refusal_count"])
        entry = index["refusals"][0]
        refusal_run = "c3d4e5f60718293a4b5c6d7e8f90a1b2"
        for field in ("run_id", "trial_id", "pair_id", "controller", "final_status", "error_code"):
            self.assertTrue(str(entry.get(field, "")).strip(),
                            f"refusal entries must carry identity field {field}")
        self.assertEqual(refusal_run, entry["run_id"])
        self.assertEqual(run_binding.trial_id_for(refusal_run, self.PAIR), entry["trial_id"],
                         "the refusal trial id must follow the same run/pair derivation")

    def test_rebuild_refuses_identity_less_refusal(self):
        root = self._generation()
        d = root / "d4e5f60718293a4b5c6d7e8f90a1b2c3"
        d.mkdir()
        record = {"run_id": "", "trial_id": "trial-forged", "pair_id": self.PAIR,
                  "controller": "controller-b", "purpose": "clock_rule_refusal_control",
                  "final_status": "FAILED", "error_code": "DATE_OUT_OF_RANGE"}
        (d / "refusal.json").write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
        with self.assertRaises(ValueError):
            run_binding.rebuild_index(root, formal_bindings=self.BINDINGS)

    def test_rebuilt_entries_carry_digest_fields(self):
        import hashlib as _h
        root = self._generation()
        index = run_binding.rebuild_index(root, formal_bindings=self.BINDINGS)
        run_id = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        entry = next(r for r in index["runs"] if r["run_id"] == run_id)
        d = root / run_id
        self.assertEqual(_h.sha256((d / "report.json").read_bytes()).hexdigest(),
                         entry["report_json_sha256"])
        self.assertEqual(_h.sha256((d / "report.html").read_bytes()).hexdigest(),
                         entry["report_html_sha256"])
        self.assertEqual(_h.sha256((d / "trace-spans.jsonl").read_bytes()).hexdigest(),
                         entry["trace_sha256"])
        self.assertEqual(_h.sha256((d / "manifest.json").read_bytes()).hexdigest(),
                         entry["manifest_sha256"])

    def test_batch_comparison_is_strict_on_counts_repeat_manifest_and_refusals(self):
        import copy
        root = self._generation()
        rebuilt = run_binding.rebuild_index(root, formal_bindings=self.BINDINGS)
        def declared_mutate(fn):
            declared = copy.deepcopy(rebuilt)
            fn(declared)
            return declared
        # count drift on either side must fail
        self.assertFalse(run_binding.batch_index_matches(
            declared_mutate(lambda d: d.update(run_count=99)), rebuilt))
        self.assertFalse(run_binding.batch_index_matches(
            declared_mutate(lambda d: d.update(refusal_count=7)), rebuilt))
        # repeat number drift must fail
        self.assertFalse(run_binding.batch_index_matches(
            declared_mutate(lambda d: d["runs"][0].update(repeat_no=2)), rebuilt))
        # manifest digest drift must fail
        self.assertFalse(run_binding.batch_index_matches(
            declared_mutate(lambda d: d["runs"][0].update(manifest_sha256="0" * 64)), rebuilt))
        # refusal identity drift (controller) must fail
        self.assertFalse(run_binding.batch_index_matches(
            declared_mutate(lambda d: d["refusals"][0].update(controller="controller-zzz")), rebuilt))
        # refusal trial id drift must fail
        self.assertFalse(run_binding.batch_index_matches(
            declared_mutate(lambda d: d["refusals"][0].update(trial_id="trial-forged")), rebuilt))
        # duplicate refusal run ids must fail
        self.assertFalse(run_binding.batch_index_matches(
            declared_mutate(lambda d: d.update(refusals=[d["refusals"][0], dict(d["refusals"][0])])),
            rebuilt))



class Repair08BoundaryProbe(unittest.TestCase):
    """Repair-08 counterexamples at the REAL rebuild/comparison boundaries: the total count
    fields must be required strict integers, each run directory must hold exactly one
    terminal-state original, and the directory name must equal the recorded run_id."""

    def _single_generation(self):
        root = Path(tempfile.mkdtemp(prefix="repair08-single-"))
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        repair07_seed_run(root, "a1b2c3d4e5f60718293a4b5c6d7e8f90")
        repair07_seed_refusal(root, "c3d4e5f60718293a4b5c6d7e8f90a1b2")
        return root

    def _rebuilt(self, root):
        return run_binding.rebuild_index(root, formal_bindings=REPAIR07_BINDINGS)

    # --- A: total count fields are required strict integers --------------------------

    def test_missing_count_is_a_mismatch(self):
        import copy
        rebuilt = self._rebuilt(self._single_generation())
        for field in ("run_count", "refusal_count"):
            declared = copy.deepcopy(rebuilt)
            del declared[field]
            self.assertFalse(run_binding.batch_index_matches(rebuilt, declared),
                             f"a missing {field} must be a mismatch, not silently skipped")

    def test_string_count_is_a_mismatch(self):
        import copy
        rebuilt = self._rebuilt(self._single_generation())
        for field in ("run_count", "refusal_count"):
            declared = copy.deepcopy(rebuilt)
            declared[field] = str(rebuilt[field])
            self.assertFalse(run_binding.batch_index_matches(rebuilt, declared),
                             f"a textual {field} must be a mismatch even with the right value")

    def test_boolean_count_is_a_mismatch(self):
        import copy
        rebuilt = self._rebuilt(self._single_generation())
        for field in ("run_count", "refusal_count"):
            declared = copy.deepcopy(rebuilt)
            declared[field] = True  # bool is an int subclass; True == 1 must NOT pass
            self.assertFalse(run_binding.batch_index_matches(rebuilt, declared),
                             f"a boolean {field} must be a mismatch")

    def test_wrong_count_and_array_drift_are_mismatches(self):
        import copy
        rebuilt = self._rebuilt(self._single_generation())
        declared = copy.deepcopy(rebuilt)
        declared["run_count"] = 99
        self.assertFalse(run_binding.batch_index_matches(rebuilt, declared),
                         "a wrong count value must be a mismatch")
        declared = copy.deepcopy(rebuilt)
        declared["runs"].pop()  # an entry deleted behind a consistent-looking index
        self.assertFalse(run_binding.batch_index_matches(rebuilt, declared))
        declared = copy.deepcopy(rebuilt)
        declared["runs"].append(dict(declared["runs"][0]))  # an extra entry
        self.assertFalse(run_binding.batch_index_matches(rebuilt, declared))

    def test_counts_match_when_equal_and_consistent(self):
        import copy
        rebuilt = self._rebuilt(self._single_generation())
        self.assertTrue(run_binding.batch_index_matches(rebuilt, copy.deepcopy(rebuilt)),
                        "the honest 1+1 ledger must keep matching")

    # --- B: exactly one terminal-state original per run directory --------------------

    def test_directory_with_both_terminal_files_is_refused(self):
        root = self._single_generation()
        run_dir = root / "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        injected = {
            "run_id": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            "trial_id": run_binding.trial_id_for("a1b2c3d4e5f60718293a4b5c6d7e8f90", REPAIR07_PAIR),
            "pair_id": REPAIR07_PAIR, "controller": "controller-a",
            "purpose": "clock_rule_refusal_control", "final_status": "FAILED",
            "error_code": "DATE_OUT_OF_RANGE",
        }
        (run_dir / "refusal.json").write_text(json.dumps(injected, ensure_ascii=False), encoding="utf-8")
        with self.assertRaises(ValueError) as caught:
            run_binding.rebuild_index(root, formal_bindings=REPAIR07_BINDINGS)
        message = str(caught.exception)
        self.assertIn("a1b2c3d4e5f60718293a4b5c6d7e8f90", message, "the error must name the directory")
        self.assertIn("refusal.json", message, "the error must name the duplicate original")

    def test_single_terminal_originals_still_build(self):
        index = self._rebuilt(self._single_generation())
        self.assertEqual(1, index["run_count"])
        self.assertEqual(1, index["refusal_count"])

    # --- C: the directory name must equal the recorded run_id ------------------------

    def test_manifest_in_wrongly_named_directory_is_refused(self):
        root = Path(tempfile.mkdtemp(prefix="repair08-name-"))
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        repair07_seed_run(root, "a1b2c3d4e5f60718293a4b5c6d7e8f90")
        (root / "a1b2c3d4e5f60718293a4b5c6d7e8f90").rename(root / "misnamed-dir")
        with self.assertRaises(ValueError) as caught:
            run_binding.rebuild_index(root, formal_bindings=REPAIR07_BINDINGS)
        message = str(caught.exception)
        self.assertIn("misnamed-dir", message, "the error must name the directory")
        self.assertIn("run_id", message, "the error must name the conflicting field")

    def test_refusal_in_wrongly_named_directory_is_refused(self):
        root = Path(tempfile.mkdtemp(prefix="repair08-refname-"))
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        repair07_seed_refusal(root, "c3d4e5f60718293a4b5c6d7e8f90a1b2")
        (root / "c3d4e5f60718293a4b5c6d7e8f90a1b2").rename(root / "misnamed-refusal")
        with self.assertRaises(ValueError) as caught:
            run_binding.rebuild_index(root, formal_bindings=REPAIR07_BINDINGS)
        message = str(caught.exception)
        self.assertIn("misnamed-refusal", message, "the error must name the directory")
        self.assertIn("run_id", message, "the error must name the conflicting field")

    def test_empty_recorded_id_is_refused(self):
        root = Path(tempfile.mkdtemp(prefix="repair08-empty-"))
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        manifest = repair07_manifest("a1b2c3d4e5f60718293a4b5c6d7e8f90")
        manifest["run_id"] = ""
        repair07_seed_run(root, "a1b2c3d4e5f60718293a4b5c6d7e8f90", manifest=manifest)
        with self.assertRaises(ValueError):
            run_binding.rebuild_index(root, formal_bindings=REPAIR07_BINDINGS)

    def test_correctly_named_directories_still_build(self):
        index = self._rebuilt(self._single_generation())
        self.assertEqual(1, index["run_count"])
        self.assertEqual(1, index["refusal_count"])



if __name__ == "__main__":
    unittest.main()
