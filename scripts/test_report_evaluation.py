"""Synthetic-fixture tests for the report evaluation offline loop. No real user data."""
import csv
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from decimal import Decimal
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

from report_evaluation import ass, cases, csvio, datasets, delivery, ledger, offline, summary, textnorm, timebase
from report_evaluation.util import MaterialError, OutOfBoundsError, CaseError, sha256_file

SCRIPTS_DIR = Path(__file__).resolve().parent
WORKTREE_DIR = SCRIPTS_DIR.parent

DATASET_COMMENT_HEADER = ["评论ID", "上级评论ID", "用户ID", "用户名", "用户等级", "性别",
                          "评论内容", "评论时间", "原始评论时间", "回复数", "点赞数", "IP属地"]
DATASET_DANMAKU_HEADER = ["序号", "播放位置秒", "样式", "内容", "仿真发送时间", "发送者ID", "发送者昵称"]
SOURCE_COMMENT_HEADER = ["序号", "上级评论ID", "评论ID", "用户ID", "用户名", "用户等级", "性别",
                         "评论内容", "评论时间", "回复数", "点赞数", "个性签名", "IP属地", "是否是大会员", "头像"]
MANIFEST_HEADER = ["key", "作者", "标题", "仿真发布日", "数据截止", "弹幕条数", "评论条数",
                   "其中回复", "评论用户数", "虚拟观众数", "视频时长秒", "评论映射方式", "评论时间范围", "备注"]

ASS_TEMPLATE = """[Script Info]
Title: synthetic

[V4+ Styles]
Format: Name, Fontname, Fontsize
Style: Default,Arial,20

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Tex
Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,{\\pos(1,2)}{哈哈}你好
Dialogue: 0,0:00:03.97,0:00:08.00,Default,,0,0,0,,普通弹幕
"""


def write_csv(path, header, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


def make_ass_zip(path, ass_text=ASS_TEMPLATE, member="synthetic.ass"):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        z.writestr(member, ass_text)


def make_case(cid="case-1", scenario="success", **overrides):
    content = f"合成快照 {cid}：甲 2026-11-08 好评；乙 2026-11-09 差评。"
    case = {
        "case_id": cid, "case_version": "0.1.0", "scenario_id": f"offline_{scenario}",
        "assessment_scope": "synthetic_offline_fixture", "report_scope": "FEEDBACK_ONLY",
        "source_group": "synthetic_group", "material_class": "synthetic_fixture",
        "prompt": "统计最近7天反馈数量。",
        "material": {"kind": "synthetic_inline", "content": content,
                     "content_sha256": hashlib.sha256(content.encode("utf-8")).hexdigest()},
        "timezone": "Asia/Shanghai",
        "task_clock": {"reference_time": "2026-11-10 10:00:00", "timezone": "Asia/Shanghai"},
        "task_window": {"kind": "relative", "days": 7, "anchor": "yesterday"},
        "interaction_script": {"clarification_replies": ["最近7天，含昨天。"]},
        "roles": {"controller": "offline-simulated", "feedback_analysis": "offline-simulated"},
        "tool_permissions": {"read_dataset": True, "network": False, "ssh": False},
        "cache_replay_policy": {"cache": "disabled", "replay": "not_implemented"},
        "scoring_rules_version": "draft-v0",
        "expected": {"facts": [], "prohibited_claims": ["真实平台用户行为"]},
        "review": {"reviewer_ref": None, "reviewed_at": None, "status": "pending"},
        "offline_simulation": {"scenario": scenario},
    }
    case.update(overrides)
    return case


def build_dataset_fixture(root):
    """Two-key synthetic formal dataset; effective dates differ from original collection dates."""
    root = Path(root)
    manifest_rows = [
        ["demo-a", "合成作者", "测试视频 | 第一集", "2026-11-01", "2026-11-09", "2", "3", "2", "2", "1", "60", "shift", "2026-11-01~2026-11-09", ""],
        ["demo-b", "合成作者", "测试&amp;视频 第二集", "2026-11-02", "2026-11-09", "1", "1", "0", "1", "1", "60", "scale", "2026-11-02~2026-11-09", ""],
    ]
    write_csv(root / "视频清单.csv", MANIFEST_HEADER, manifest_rows)
    write_csv(root / "评论" / "demo-a_评论.csv", DATASET_COMMENT_HEADER, [
        ["c1", "", "u1", "甲", "1", "保密", "第一行\n第二行,含逗号", "2026-11-08 10:00:00", "2023-05-01 08:00:00", "1", "0", "北京"],
        ["c2", "c1", "u2", "乙", "1", "保密", "回复内容", "2026-11-09 09:00:00", "2023-05-02 09:00:00", "0", "0", "上海"],
        ["c3", "missing-parent", "u3", "丙", "1", "保密", "孤立回复", "2026-11-09 12:00:00", "2023-05-03 12:00:00", "0", "0", "广东"],
    ])
    write_csv(root / "弹幕" / "demo-a_弹幕.csv", DATASET_DANMAKU_HEADER, [
        ["1", "0.00", "滚动", "{哈哈}你好", "2026-11-01 08:00:00", "9000000001", "虚拟观众A"],
        ["2", "3.97", "滚动", "普通弹幕", "2026-11-02 08:00:00", "9000000002", "虚拟观众B"],
    ])
    write_csv(root / "评论" / "demo-b_评论.csv", DATASET_COMMENT_HEADER, [
        ["d1", "", "u4", "丁", "1", "保密", "另一条", "2026-11-08 11:00:00", "2024-01-01 11:00:00", "0", "0", "浙江"],
    ])
    write_csv(root / "弹幕" / "demo-b_弹幕.csv", DATASET_DANMAKU_HEADER, [
        ["1", "1.00", "滚动", "乙弹幕", "2026-11-02 09:00:00", "9000000003", "虚拟观众C"],
    ])
    return root


def build_source_fixture(root):
    root = Path(root)
    author = root / "合成作者"
    author.mkdir(parents=True, exist_ok=True)
    (author / "测试视频 _ 第一集.mp4").write_bytes(b"synthetic-video-a")
    write_csv(author / "测试视频 _ 第一集_评论.csv", SOURCE_COMMENT_HEADER, [
        ["1", "", "c1", "u1", "甲", "1", "保密", "第一行\n第二行,含逗号", "2023-05-01 08:00:00", "1", "0", "", "北京", "否", ""],
        ["2", "c1", "c2", "u2", "乙", "1", "保密", "回复内容", "2023-05-02 09:00:00", "0", "0", "", "上海", "否", ""],
        ["3", "missing-parent", "c3", "u3", "丙", "1", "保密", "孤立回复", "2023-05-03 12:00:00", "0", "0", "", "广东", "否", ""],
    ])
    make_ass_zip(author / "测试视频 _ 第一集.zip")
    (author / "测试&视频 第二集.mp4").write_bytes(b"synthetic-video-b")
    write_csv(author / "测试&视频 第二集_评论.csv", SOURCE_COMMENT_HEADER, [
        ["1", "", "d1", "u4", "丁", "1", "保密", "另一条", "2024-01-01 11:00:00", "0", "0", "", "浙江", "否", ""],
    ])
    ass_b = ASS_TEMPLATE.replace("0:00:00.00,0:00:05.00", "0:00:01.00,0:00:06.00")
    ass_b = ass_b.replace("{\\pos(1,2)}{哈哈}你好", "乙弹幕").replace("Dialogue: 0,0:00:03.97,0:00:08.00,Default,,0,0,0,,普通弹幕\n", "")
    make_ass_zip(author / "测试&视频 第二集.zip", ass_b)
    return root


class TestCsvParsing(unittest.TestCase):
    def test_multiline_quotes_chinese_path_and_replies(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "中文目录" / "评论.csv"
            write_csv(p, ["评论ID", "上级评论ID", "评论内容"],
                      [["c1", "", '第一行\n第二行,含"引号"'], ["c2", "c1", "回复"]])
            records = csvio.read_csv_records(p, ["评论ID", "上级评论ID", "评论内容"])
            self.assertEqual(len(records), 2)
            self.assertEqual(records[0]["评论内容"], '第一行\n第二行,含"引号"')
            self.assertEqual(records[1]["上级评论ID"], "c1")

    def test_missing_column_raises(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "x.csv"
            write_csv(p, ["评论ID"], [["c1"]])
            with self.assertRaises(MaterialError):
                csvio.read_csv_records(p, ["评论ID", "评论内容"])

    def test_decode_error_raises(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "gbk.csv"
            p.write_bytes("评论ID,评论内容\nc1,中文".encode("gbk"))
            with self.assertRaises(MaterialError):
                csvio.read_csv_records(p, ["评论ID", "评论内容"])

    def test_short_row_raises(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "short.csv"
            p.write_text("a,b\n1\n", encoding="utf-8")
            with self.assertRaises(MaterialError):
                csvio.read_csv_records(p, ["a", "b"])


class TestTextNorm(unittest.TestCase):
    def test_title_variants_normalize_equal(self):
        self.assertEqual(textnorm.normalize_title("咸蛋黄鲜虾豆腐 | 软嫩咸香"),
                         textnorm.normalize_title("咸蛋黄鲜虾豆腐 _ 软嫩咸香"))
        self.assertEqual(textnorm.normalize_title("选哪个？iPhone 18 Pro&Duo深度上手"),
                         textnorm.normalize_title("选哪个？iPhone 18 Pro&amp;Duo深度上手"))
        self.assertEqual(textnorm.normalize_title("  a  b  "), "a b")

    def test_strip_ass_tags_keeps_plain_braces(self):
        self.assertEqual(textnorm.strip_ass_tags("{\\pos(1,2)}{哈哈}你好"), "{哈哈}你好")
        self.assertEqual(textnorm.strip_ass_tags("a\\Nb"), "a\nb")
        self.assertEqual(textnorm.strip_ass_tags("a\\hb"), "a b")
        self.assertEqual(textnorm.strip_ass_tags("{普通}"), "{普通}")

    def test_ass_start_seconds(self):
        self.assertEqual(textnorm.ass_start_to_seconds("0:0:3.97"), Decimal("3.97"))
        self.assertEqual(textnorm.ass_start_to_seconds("1:02:03.50"), Decimal("3723.50"))
        with self.assertRaises(ValueError):
            textnorm.ass_start_to_seconds("bad")


class TestAssZip(unittest.TestCase):
    def test_tex_variant_empty_name_braces(self):
        parsed = ass.parse_ass_bytes(ASS_TEMPLATE.encode("utf-8"), "synthetic")
        self.assertEqual(parsed["variant"], "Tex")
        self.assertEqual(parsed["event_count"], 2)
        e0 = parsed["events"][0]
        self.assertEqual(e0["name"], "")
        self.assertEqual(e0["text"], "{哈哈}你好")
        self.assertEqual(e0["start_seconds"], "0.00")
        self.assertEqual(e0["end_seconds"], "5.00")

    def test_text_variant_also_accepted(self):
        parsed = ass.parse_ass_bytes(ASS_TEMPLATE.replace("Effect, Tex", "Effect, Text").encode("utf-8"), "s")
        self.assertEqual(parsed["variant"], "Text")

    def test_bad_format_raises(self):
        bad = ASS_TEMPLATE.replace("Effect, Tex", "Effect")
        with self.assertRaises(MaterialError):
            ass.parse_ass_bytes(bad.encode("utf-8"), "s")

    def test_bad_dialogue_counted_and_raises(self):
        bad = ASS_TEMPLATE + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0\n"
        with self.assertRaises(MaterialError) as ctx:
            ass.parse_ass_bytes(bad.encode("utf-8"), "s")
        self.assertIn("malformed", str(ctx.exception))

    def test_zip_single_member_and_no_disk_extraction(self):
        with tempfile.TemporaryDirectory() as td:
            zp = Path(td) / "x.zip"
            make_ass_zip(zp)
            before = sorted(p.name for p in Path(td).iterdir())
            parsed = ass.read_zip_ass(zp)
            after = sorted(p.name for p in Path(td).iterdir())
            self.assertEqual(before, after)
            self.assertEqual(parsed["event_count"], 2)
            self.assertEqual(parsed["member_name"], "synthetic.ass")

    def test_zip_two_ass_members_raises(self):
        with tempfile.TemporaryDirectory() as td:
            zp = Path(td) / "x.zip"
            with zipfile.ZipFile(zp, "w") as z:
                z.writestr("a.ass", ASS_TEMPLATE)
                z.writestr("b.ass", ASS_TEMPLATE)
            with self.assertRaises(MaterialError):
                ass.read_zip_ass(zp)

    def test_zip_size_limit_raises(self):
        with tempfile.TemporaryDirectory() as td:
            zp = Path(td) / "x.zip"
            make_ass_zip(zp)
            with self.assertRaises(MaterialError):
                ass.read_zip_ass(zp, max_uncompressed_bytes=10)

    def test_zip_bad_crc_raises(self):
        with tempfile.TemporaryDirectory() as td:
            zp = Path(td) / "x.zip"
            make_ass_zip(zp)
            data = bytearray(zp.read_bytes())
            idx = data.find("普通弹幕".encode("utf-8"))
            data[idx] ^= 0xFF
            zp.write_bytes(bytes(data))
            with self.assertRaises(MaterialError):
                ass.read_zip_ass(zp)


class TestTimebase(unittest.TestCase):
    def test_yesterday_and_last_week_resolution(self):
        clock = {"reference_time": "2026-11-10 10:00:00", "timezone": "Asia/Shanghai"}
        w = timebase.resolve_window(clock, {"kind": "relative", "days": 1, "anchor": "yesterday"})
        self.assertEqual(w["start"], "2026-11-09T00:00:00+08:00")
        self.assertEqual(w["end"], "2026-11-10T00:00:00+08:00")
        w7 = timebase.resolve_window(clock, {"kind": "relative", "days": 7, "anchor": "yesterday"})
        self.assertEqual(w7["start"], "2026-11-03T00:00:00+08:00")
        self.assertEqual(w7["end"], "2026-11-10T00:00:00+08:00")

    def test_resolution_is_deterministic_not_wall_clock(self):
        clock = {"reference_time": "2026-11-10 10:00:00", "timezone": "Asia/Shanghai"}
        spec = {"kind": "relative", "days": 7, "anchor": "yesterday"}
        self.assertEqual(timebase.resolve_window(clock, spec), timebase.resolve_window(clock, spec))

    def test_window_stats_use_effective_field(self):
        records = [
            {"评论时间": "2026-11-08 10:00:00", "原始评论时间": "2023-05-01 08:00:00"},
            {"评论时间": "2026-11-09 09:00:00", "原始评论时间": "2023-05-02 09:00:00"},
            {"评论时间": "2026-10-01 09:00:00", "原始评论时间": "2026-11-08 09:00:00"},
        ]
        tz = timebase.get_timezone("Asia/Shanghai")
        start = timebase.parse_dt("2026-11-03", tz)
        end = timebase.parse_dt("2026-11-10", tz)
        stats = timebase.window_stats(records, "评论时间", start, end)
        self.assertEqual(stats["count"], 2)
        self.assertEqual(stats["by_date"], {"2026-11-08": 1, "2026-11-09": 1})
        original_stats = timebase.window_stats(records, "原始评论时间", start, end)
        self.assertEqual(original_stats["count"], 1)

    def test_unknown_timezone_raises(self):
        with self.assertRaises(CaseError):
            timebase.get_timezone("Mars/Olympus")


class TestDatasetIndex(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        base = Path(self._td.name)
        self.dataset_root = build_dataset_fixture(base / "整理集")
        self.source_root = build_source_fixture(base / "采集源")

    def test_effective_and_original_dates_kept_separate(self):
        idx = datasets.index_dataset(self.dataset_root)
        va = idx["videos"]["demo-a"]
        self.assertEqual(va["comment_fields"]["effective_time"], "评论时间")
        self.assertEqual(va["comment_fields"]["original_collection_time"], "原始评论时间")
        self.assertEqual(va["danmaku_fields"]["original_sender"], "unknown")
        records = datasets.load_dataset_comments(self.dataset_root, "demo-a")
        self.assertEqual(records[0]["评论时间"], "2026-11-08 10:00:00")
        self.assertEqual(records[0]["原始评论时间"], "2023-05-01 08:00:00")
        self.assertEqual(va["orphan_reply_count"], 1)
        self.assertEqual(va["orphan_reply_ids"], ["c3"])
        self.assertEqual(idx["counts"]["comments"], 4)
        self.assertEqual(idx["counts"]["danmaku"], 3)
        self.assertEqual(idx["findings"], [])

    def test_source_index_missing_ffprobe_marks_duration_unknown(self):
        with mock.patch.object(datasets.shutil, "which", return_value=None):
            idx = datasets.index_source(self.source_root)
        self.assertEqual(idx["ffprobe"]["state"], "missing")
        for v in idx["videos"]:
            self.assertIsNone(v["duration_seconds"])
            self.assertEqual(v["duration_state"], "missing_ffprobe")
            self.assertEqual(v["ass_sender"], "unknown")
        self.assertEqual(idx["counts"]["comments"], 4)
        self.assertEqual(idx["counts"]["danmaku"], 3)

    def test_explicit_bad_ffprobe_marks_failed_not_ass_guess(self):
        idx = datasets.index_source(self.source_root, ffprobe="nonexistent-ffprobe-binary")
        for v in idx["videos"]:
            self.assertIsNone(v["duration_seconds"])
            self.assertEqual(v["duration_state"], "ffprobe_failed")

    def test_match_and_reconcile_success(self):
        ds = datasets.index_dataset(self.dataset_root)
        src = datasets.index_source(self.source_root, ffprobe="nonexistent-ffprobe-binary")
        matched = datasets.match_and_reconcile(ds, src, self.dataset_root, self.source_root)
        self.assertEqual(len(matched["pairs"]), 2)
        self.assertEqual(matched["findings"], [])
        for p in matched["pairs"]:
            self.assertEqual(p["reconciliation"]["comments"], "match")
            self.assertEqual(p["reconciliation"]["danmaku"], "match")
            self.assertTrue(p["reconciliation"]["comment_original_time_preserved"])

    def test_title_symbol_variants_match(self):
        ds = datasets.index_dataset(self.dataset_root)
        src = datasets.index_source(self.source_root, ffprobe="nonexistent-ffprobe-binary")
        matched = datasets.match_and_reconcile(ds, src, self.dataset_root, self.source_root)
        by_key = {p["key"]: p for p in matched["pairs"]}
        self.assertEqual(by_key["demo-a"]["source_title"], "测试视频 _ 第一集")
        self.assertEqual(by_key["demo-b"]["source_title"], "测试&视频 第二集")

    def test_danmaku_whitespace_normalization_reconciles(self):
        author = self.source_root / "合成作者"
        ass_path = author / "测试视频 _ 第一集.zip"
        padded = ASS_TEMPLATE.replace("普通弹幕", "   普通弹幕  ")
        make_ass_zip(ass_path, padded)
        ds = datasets.index_dataset(self.dataset_root)
        src = datasets.index_source(self.source_root, ffprobe="nonexistent-ffprobe-binary")
        matched = datasets.match_and_reconcile(ds, src, self.dataset_root, self.source_root)
        self.assertEqual(matched["findings"], [])
        sv = next(v for v in src["videos"] if v["normalized_title"] == ds["videos"]["demo-a"]["normalized_title"])
        self.assertEqual(sv["ass"]["events"][1]["text"], "   普通弹幕  ")

    def test_original_time_mismatch_reported(self):
        p = self.source_root / "合成作者" / "测试视频 _ 第一集_评论.csv"
        records = csvio.read_csv_records(p, SOURCE_COMMENT_HEADER)
        records[0]["评论时间"] = "2023-05-01 08:00:01"
        write_csv(p, SOURCE_COMMENT_HEADER, [[r[c] for c in SOURCE_COMMENT_HEADER] for r in records])
        ds = datasets.index_dataset(self.dataset_root)
        src = datasets.index_source(self.source_root, ffprobe="nonexistent-ffprobe-binary")
        matched = datasets.match_and_reconcile(ds, src, self.dataset_root, self.source_root)
        kinds = [f["kind"] for f in matched["findings"]]
        self.assertIn("comment_reconciliation_mismatch", kinds)

    def test_manifest_count_mismatch_finding(self):
        mp = self.dataset_root / "视频清单.csv"
        rows = csvio.read_csv_records(mp, MANIFEST_HEADER)
        rows[0]["评论条数"] = "99"
        write_csv(mp, MANIFEST_HEADER, [[r[c] for c in MANIFEST_HEADER] for r in rows])
        idx = datasets.index_dataset(self.dataset_root)
        self.assertTrue(any(f["kind"] == "count_mismatch" for f in idx["findings"]))

    def test_missing_dataset_file_raises(self):
        (self.dataset_root / "弹幕" / "demo-a_弹幕.csv").unlink()
        with self.assertRaises(MaterialError):
            datasets.index_dataset(self.dataset_root)

    def test_source_title_conflict_raises(self):
        author = self.source_root / "合成作者"
        # Windows forbids '|' in filenames; double-space variant normalizes to the same title.
        (author / "测试视频  第一集.mp4").write_bytes(b"conflict")
        with self.assertRaises(MaterialError):
            datasets.index_source(self.source_root, ffprobe="nonexistent-ffprobe-binary")

    def test_unmatched_dataset_key_raises(self):
        ds = datasets.index_dataset(self.dataset_root)
        ds["videos"]["demo-a"]["normalized_title"] = "不存在的标题"
        src = datasets.index_source(self.source_root, ffprobe="nonexistent-ffprobe-binary")
        with self.assertRaises(MaterialError):
            datasets.match_and_reconcile(ds, src, self.dataset_root, self.source_root)

    def test_build_inventory_writes_index(self):
        out = Path(self._td.name) / "index-out"
        idx = datasets.build_inventory(self.dataset_root, self.source_root, out, ffprobe="nonexistent-ffprobe-binary")
        self.assertTrue((out / "index.json").is_file())
        self.assertEqual(idx["lineage"]["independent_video_samples"], 2)
        self.assertEqual(idx["totals"]["dataset_comments"], 4)

    def test_truth_table_consistency_checked(self):
        daily = self.dataset_root / "真值日报.csv"
        header = ["日期", "视频key", "标题", "弹幕数", "评论数"]
        write_csv(daily, header, [
            ["2026-11-08", "demo-a", "测试视频 | 第一集", "1", "2"],
            ["2026-11-09", "demo-a", "测试视频 | 第一集", "1", "1"],
            ["2026-11-08", "demo-b", "测试&amp;视频 第二集", "1", "1"],
        ])
        idx = datasets.index_dataset(self.dataset_root)
        self.assertEqual(idx["findings"], [])
        self.assertIn("daily", idx["truth"])
        write_csv(daily, header, [
            ["2026-11-08", "demo-a", "测试视频 | 第一集", "1", "5"],
            ["2026-11-09", "demo-a", "测试视频 | 第一集", "1", "1"],
            ["2026-11-08", "demo-b", "测试&amp;视频 第二集", "1", "1"],
        ])
        idx2 = datasets.index_dataset(self.dataset_root)
        self.assertTrue(any(f["kind"] == "truth_sum_mismatch" for f in idx2["findings"]))


class TestCases(unittest.TestCase):
    def test_valid_case_passes(self):
        self.assertEqual(cases.validate_case(make_case()), [])

    def test_missing_fields_reported(self):
        case = make_case()
        del case["prompt"]
        errors = cases.validate_case(case)
        self.assertTrue(any("prompt" in e for e in errors))

    def test_bad_scenario_and_scope_reported(self):
        case = make_case()
        case["offline_simulation"]["scenario"] = "nope"
        case["report_scope"] = "EVERYTHING"
        errors = cases.validate_case(case)
        self.assertEqual(len(errors), 2)

    def test_content_hash_mismatch_reported(self):
        case = make_case()
        case["material"]["content_sha256"] = "0" * 64
        self.assertTrue(any("content_sha256" in e for e in cases.validate_case(case)))

    def test_offline_forbids_network(self):
        case = make_case(tool_permissions={"network": True})
        self.assertTrue(any("network" in e for e in cases.validate_case(case)))

    def test_fingerprint_sensitive_to_time_material_roles(self):
        base = make_case()
        fp0 = cases.case_fingerprint(base)
        self.assertEqual(fp0, cases.case_fingerprint(make_case()))
        later = make_case(task_clock={"reference_time": "2026-11-11 10:00:00", "timezone": "Asia/Shanghai"})
        self.assertNotEqual(fp0, cases.case_fingerprint(later))
        other_mat = make_case(cid="case-2")
        self.assertNotEqual(fp0, cases.case_fingerprint(other_mat))
        other_roles = make_case(roles={"controller": "other-model"})
        self.assertNotEqual(fp0, cases.case_fingerprint(other_roles))
        self.assertNotEqual(cases.experiment_id(fp0), cases.experiment_id(cases.case_fingerprint(later)))

    def test_dataset_ref_fingerprint_binds_file_hashes(self):
        with tempfile.TemporaryDirectory() as td:
            ds_root = build_dataset_fixture(Path(td) / "ds")
            idx = datasets.index_dataset(ds_root)
            manifest_sha = sha256_file(ds_root / "视频清单.csv")
            material = {"kind": "dataset_ref", "dataset_root": str(ds_root), "keys": ["demo-a"],
                        "manifest_sha256": manifest_sha,
                        "effective_timeline": idx["videos"]["demo-a"]["effective_timeline"]}
            case = make_case(material=material, material_class="time_adapted_real_content")
            self.assertEqual(cases.validate_case(case), [])
            fp_plain = cases.case_fingerprint(case)
            fp_inv = cases.case_fingerprint(case, inventory={"dataset": idx})
            self.assertNotEqual(fp_plain, fp_inv)
            comment_file = ds_root / "评论" / "demo-a_评论.csv"
            comment_file.write_text(comment_file.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            idx2 = datasets.index_dataset(ds_root)
            self.assertNotEqual(fp_inv, cases.case_fingerprint(case, inventory={"dataset": idx2}))

    def test_semantic_eligibility_rules(self):
        synthetic = cases.semantic_eligibility(make_case())
        self.assertFalse(synthetic["eligible"])
        self.assertEqual(synthetic["reason"], "synthetic_fixture")
        adapted = make_case(material_class="time_adapted_real_content")
        pending = cases.semantic_eligibility(adapted)
        self.assertFalse(pending["eligible"])
        self.assertEqual(pending["reason"], "human_review_pending")
        approved = make_case(material_class="time_adapted_real_content",
                             review={"reviewer_ref": "human", "reviewed_at": "2026-11-13", "status": "approved"})
        ok = cases.semantic_eligibility(approved)
        self.assertTrue(ok["eligible"])
        self.assertEqual(ok["scope"], "library_facts_only")


class TestLedger(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.root = Path(self._td.name) / "run"

    def test_repeat_runs_keep_independent_trials(self):
        case = make_case()
        fp = cases.case_fingerprint(case)
        t1 = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        t1.append_attempt({"kind": "request", "outcome": "SUCCEEDED"})
        t2 = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        t2.append_attempt({"kind": "request", "outcome": "FAILED"})
        self.assertNotEqual(t1.trial_id, t2.trial_id)
        trials = {t["meta"]["trial_id"]: t for t in ledger.load_trials(self.root)}
        self.assertEqual(len(trials), 2)
        self.assertEqual(len(trials[t1.trial_id]["attempts"]), 1)
        self.assertEqual(trials[t1.trial_id]["attempts"][0]["outcome"], "SUCCEEDED")
        self.assertEqual(trials[t2.trial_id]["attempts"][0]["outcome"], "FAILED")

    def test_artifact_out_of_bounds_refused(self):
        case = make_case()
        fp = cases.case_fingerprint(case)
        t = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        with self.assertRaises(OutOfBoundsError):
            t.write_artifact("../escaped.md", b"x")
        self.assertFalse((self.root / "escaped.md").exists())
        self.assertFalse((self.root / "trials" / "escaped.md").exists())

    def test_interrupt_has_no_summary(self):
        case = make_case()
        fp = cases.case_fingerprint(case)
        t = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        t.simulate_interrupt()
        trials = ledger.load_trials(self.root)
        self.assertIsNone(trials[0]["summary"])
        self.assertEqual(trials[0]["attempts"][-1]["outcome"], "interrupted")

    def test_finish_atomic_no_tmp_left(self):
        case = make_case()
        fp = cases.case_fingerprint(case)
        t = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        t.finish({"final_status": "SUCCEEDED"})
        leftovers = [p.name for p in t.dir.iterdir() if p.name.endswith(".tmp")]
        self.assertEqual(leftovers, [])
        self.assertTrue((t.dir / "trial_summary.json").is_file())


class TestOfflineDeliverySummary(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.root = Path(self._td.name) / "run"

    def _run(self, case):
        fp = cases.case_fingerprint(case)
        trial = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        offline.run_offline_case(trial, case)
        return trial

    def test_success_delivered_with_trace(self):
        trial = self._run(make_case("ok", "success"))
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(d["passed"], d["reasons"])
        self.assertEqual(d["semantic_quality"], "NOT_ASSESSED")
        s = summary.summarize_run(self.root)
        row = s["rows"][0]
        self.assertEqual(row["classification"], "success_delivered")
        self.assertEqual(row["trace_state"], "PRESENT")
        self.assertEqual(row["input_tokens"], 120)
        self.assertEqual(row["output_tokens"], 80)
        self.assertIsNone(row["cost_low"])
        self.assertEqual(row["unpriced_calls"], 1)

    def test_priced_trace_yields_range_not_invoice(self):
        self._run(make_case("ok", "success"))
        prices = {"currency": "CNY", "source": "synthetic-test", "checked_at": "2026-11-13",
                  "models": {"offline-simulated": {
                      "minimum": {"cache_hit": 1, "cache_miss": 2, "output": 3},
                      "maximum": {"cache_hit": 2, "cache_miss": 4, "output": 6}}}}
        pp = Path(self._td.name) / "prices.json"
        pp.write_text(json.dumps(prices), encoding="utf-8")
        s = summary.summarize_run(self.root, pp)
        row = s["rows"][0]
        self.assertIsNotNone(row["cost_low"])
        self.assertIsNotNone(row["cost_high"])
        self.assertEqual(row["unpriced_calls"], 0)
        self.assertEqual(s["billing_status"], "ESTIMATE_NOT_PROVIDER_INVOICE")

    def test_failure_recorded(self):
        trial = self._run(make_case("fail", "failure"))
        s = summary.summarize_run(self.root)
        self.assertEqual(s["rows"][0]["classification"], "failed")
        self.assertFalse(delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])["passed"])

    def test_clarification_first_and_recovery_separate(self):
        trial = self._run(make_case("clarify", "needs_clarification"))
        trials = ledger.load_trials(self.root)
        attempts = trials[0]["attempts"]
        self.assertEqual([a["kind"] for a in attempts], ["request", "clarification_reply", "recovery"])
        sm = trials[0]["summary"]
        self.assertEqual(sm["first_attempt_outcome"], "NEEDS_INPUT")
        self.assertTrue(sm["recovered"])
        self.assertEqual(sm["final_status"], "SUCCEEDED")

    def test_clarification_unresolved_without_scripted_reply(self):
        case = make_case("clarify2", "needs_clarification", interaction_script={"clarification_replies": []})
        self._run(case)
        s = summary.summarize_run(self.root)
        self.assertEqual(s["rows"][0]["classification"], "needs_input")

    def test_retry_success_first_failed(self):
        self._run(make_case("retry", "retry_success"))
        s = summary.summarize_run(self.root)
        row = s["rows"][0]
        self.assertEqual(row["first_attempt_outcome"], "FAILED")
        self.assertTrue(row["recovered"])
        self.assertEqual(row["classification"], "success_delivered")

    def test_missing_report_success_status_fails_delivery(self):
        trial = self._run(make_case("noreport", "missing_report"))
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(d["passed"])
        self.assertTrue(any("missing_artifact" in r for r in d["reasons"]))
        s = summary.summarize_run(self.root)
        self.assertEqual(s["rows"][0]["classification"], "success_delivery_failed")

    def test_missing_trace_stays_unknown_not_zero(self):
        self._run(make_case("notrace", "missing_trace"))
        s = summary.summarize_run(self.root)
        row = s["rows"][0]
        self.assertEqual(row["trace_state"], "MISSING")
        self.assertIsNone(row["input_tokens"])
        self.assertIsNone(row["output_tokens"])
        self.assertIsNone(row["cost_low"])
        self.assertEqual(row["classification"], "success_delivered")

    def test_interrupted_not_success(self):
        self._run(make_case("crash", "interrupted"))
        s = summary.summarize_run(self.root)
        row = s["rows"][0]
        self.assertEqual(row["classification"], "interrupted")
        self.assertIsNone(row["final_status"])
        self.assertFalse(row["delivery_passed"])

    def test_out_of_bounds_refused_and_recorded(self):
        trial = self._run(make_case("oob", "out_of_bounds"))
        self.assertFalse((self.root / "escaped-report.md").exists())
        self.assertFalse((Path(self._td.name) / "escaped-report.md").exists())
        s = summary.summarize_run(self.root)
        self.assertEqual(s["rows"][0]["classification"], "delivery_violation")
        sm = ledger.load_trials(self.root)[0]["summary"]
        self.assertEqual(sm["delivery_violation"], "out_of_bounds")

    def test_delivery_hash_mismatch_and_scope_violation(self):
        trial = self._run(make_case("ok", "success"))
        rm = trial.dir / "artifacts" / "report.md"
        rm.write_bytes(rm.read_bytes() + b"tampered")
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertIn("content_hash_mismatch", d["reasons"])
        rm.write_bytes("# 离线模拟报告\n".encode("utf-8"))
        rj = trial.dir / "artifacts" / "report.json"
        report = json.loads(rj.read_text(encoding="utf-8"))
        report["content_sha256"] = hashlib.sha256(rm.read_bytes()).hexdigest()
        report["sources"]["dataset_keys"] = ["unauthorized-key"]
        rj.write_text(json.dumps(report, ensure_ascii=False), encoding="utf-8")
        d2 = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertIn("source_scope_violation", d2["reasons"])

    def test_different_time_conditions_not_merged(self):
        c1 = make_case("same-case")
        c2 = make_case("same-case", task_clock={"reference_time": "2026-11-11 10:00:00", "timezone": "Asia/Shanghai"})
        self._run(c1)
        self._run(c2)
        s = summary.summarize_run(self.root)
        self.assertEqual(len(s["batches"]), 2)
        self.assertIn("same-case", s["batch_split_case_ids"])

    def test_repeat_same_case_preserves_trials_and_csv_rebuilds(self):
        case = make_case("repeat")
        self._run(case)
        self._run(case)
        s1 = summary.summarize_run(self.root)
        self.assertEqual(s1["trial_count"], 2)
        self.assertEqual(len(s1["batches"]), 1)
        csv_path = self.root / "summary.csv"
        first = csv_path.read_text(encoding="utf-8-sig")
        s2 = summary.summarize_run(self.root)
        self.assertEqual(csv_path.read_text(encoding="utf-8-sig"), first)
        self.assertEqual(s2["trial_count"], 2)
        with open(csv_path, encoding="utf-8-sig", newline="") as f:
            rows = list(csv.DictReader(f))
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["input_tokens"], "120")

    def test_duplicate_spans_dedup_and_conflict_raises(self):
        trial = self._run(make_case("dup", "success"))
        trace_path = trial.dir / "traces" / "trace.jsonl"
        lines = trace_path.read_text(encoding="utf-8").splitlines()
        trace_path.write_text("\n".join(lines + [lines[1]]) + "\n", encoding="utf-8")
        s = summary.summarize_run(self.root)
        self.assertEqual(s["rows"][0]["input_tokens"], 120)
        conflicting = json.loads(lines[1])
        conflicting["attributes"]["model.input_tokens"] = 999
        trace_path.write_text("\n".join(lines + [json.dumps(conflicting)]) + "\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            summary.summarize_run(self.root)

    def test_semantic_always_not_assessed(self):
        for scenario in ["success", "failure", "missing_trace"]:
            self._run(make_case(f"sem-{scenario}", scenario))
        s = summary.summarize_run(self.root)
        for row in s["rows"]:
            self.assertEqual(row["semantic_quality"], "NOT_ASSESSED")
            self.assertFalse(row["semantic_eligible"])


class TestCli(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name)

    def _cli(self, argv):
        import contextlib
        import io
        import report_eval
        with contextlib.redirect_stdout(io.StringIO()):
            return report_eval.main(argv)

    def test_validate_invalid_exits_2(self):
        bad = {"version": "0.1.0", "status": "draft", "kind": "report-eval-cases",
               "cases": [{"case_id": "broken"}]}
        p = self.base / "bad.json"
        p.write_text(json.dumps(bad), encoding="utf-8")
        self.assertEqual(self._cli(["validate", "--cases", str(p), "--mode", "offline"]), 2)

    def test_run_and_summarize_example(self):
        example = Path(__file__).resolve().parent.parent / "agent" / "eval" / "report-cases.example.json"
        out = self.base / "smoke"
        self.assertEqual(self._cli(["validate", "--cases", str(example), "--mode", "offline"]), 0)
        self.assertEqual(self._cli(["run", "--cases", str(example), "--mode", "offline", "--output", str(out)]), 0)
        self.assertEqual(self._cli(["summarize", "--input", str(out)]), 0)
        s = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(s["trial_count"], 9)
        self.assertEqual(s["classifications"].get("success_delivered"), 5)
        self.assertEqual(s["classifications"].get("interrupted"), 1)
        self.assertEqual(s["classifications"].get("delivery_violation"), 1)
        self.assertEqual(s["classifications"].get("success_delivery_failed"), 1)
        self.assertEqual(s["classifications"].get("failed"), 1)
        self.assertEqual(s["classifications"].get("needs_input", 0), 0)
        again = self._cli(["run", "--cases", str(example), "--mode", "offline", "--output", str(out)])
        self.assertEqual(again, 0)
        s2 = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(s2["trial_count"], 9)
        self._cli(["summarize", "--input", str(out)])
        s3 = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(s3["trial_count"], 18)


class TestRepairR1MaterialBinding(unittest.TestCase):
    """R1: validate/run must bind actual selected material versions through the real CLI."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name)
        self.ds = build_dataset_fixture(self.base / "ds")
        idx = datasets.index_dataset(self.ds)
        material = {"kind": "dataset_ref", "dataset_root": str(self.ds), "keys": ["demo-a"],
                    "manifest_sha256": sha256_file(self.ds / "视频清单.csv"),
                    "effective_timeline": idx["videos"]["demo-a"]["effective_timeline"]}
        self.case = make_case("r1-case", material=material, material_class="time_adapted_real_content")
        self.case_path = self.base / "cases.json"
        self._write_cases()
        self.out = self.base / "runs"

    def _write_cases(self):
        self.case_path.write_text(json.dumps({"kind": "report-eval-cases", "status": "draft",
                                              "version": "1", "cases": [self.case]}, ensure_ascii=False),
                                  encoding="utf-8")

    def _cli(self, *args):
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
        proc = subprocess.run([sys.executable, str(SCRIPTS_DIR / "report_eval.py"), *map(str, args)],
                              cwd=WORKTREE_DIR, env=env, capture_output=True, text=True, encoding="utf-8")
        return proc.returncode, proc.stdout, proc.stderr

    def _rewrite_comment(self, body=None, effective=None):
        cp = self.ds / "评论" / "demo-a_评论.csv"
        rows = csvio.read_csv_records(cp, DATASET_COMMENT_HEADER)
        if body is not None:
            rows[0]["评论内容"] = body
        if effective is not None:
            rows[0]["评论时间"] = effective
        write_csv(cp, DATASET_COMMENT_HEADER, [[r[c] for c in DATASET_COMMENT_HEADER] for r in rows])

    def test_cli_comment_body_and_effective_date_change_split_experiment(self):
        manifest_before = sha256_file(self.ds / "视频清单.csv")
        rc1, out1, _ = self._cli("run", "--cases", self.case_path, "--output", self.out)
        self.assertEqual(rc1, 0)
        exp1 = json.loads(out1)["trials"][0]["experiment_id"]
        self._rewrite_comment(body="修改后的合成正文，清单不变", effective="2026-11-02 10:00:00")
        self.assertEqual(sha256_file(self.ds / "视频清单.csv"), manifest_before)
        rc2, out2, _ = self._cli("run", "--cases", self.case_path, "--output", self.out)
        self.assertEqual(rc2, 0)
        exp2 = json.loads(out2)["trials"][0]["experiment_id"]
        self.assertNotEqual(exp1, exp2)
        rc3, out3, _ = self._cli("summarize", "--input", self.out)
        self.assertEqual(rc3, 0)
        summary_doc = json.loads((self.out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(len(summary_doc["batches"]), 2)

    def test_cli_danmaku_change_split_experiment(self):
        rc1, out1, _ = self._cli("run", "--cases", self.case_path, "--output", self.out)
        exp1 = json.loads(out1)["trials"][0]["experiment_id"]
        dp = self.ds / "弹幕" / "demo-a_弹幕.csv"
        rows = csvio.read_csv_records(dp, DATASET_DANMAKU_HEADER)
        rows[0]["内容"] = "改动的弹幕正文"
        write_csv(dp, DATASET_DANMAKU_HEADER, [[r[c] for c in DATASET_DANMAKU_HEADER] for r in rows])
        rc2, out2, _ = self._cli("run", "--cases", self.case_path, "--output", self.out)
        exp2 = json.loads(out2)["trials"][0]["experiment_id"]
        self.assertNotEqual(exp1, exp2)

    def test_cli_unchanged_input_repeat_stays_one_batch(self):
        self._cli("run", "--cases", self.case_path, "--output", self.out)
        self._cli("run", "--cases", self.case_path, "--output", self.out)
        rc, out, _ = self._cli("summarize", "--input", self.out)
        doc = json.loads((self.out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(doc["trial_count"], 2)
        self.assertEqual(len(doc["batches"]), 1)
        self.assertEqual(doc["batch_split_case_ids"], [])

    def test_validate_and_run_share_same_fingerprint(self):
        rc, out, _ = self._cli("validate", "--cases", self.case_path)
        self.assertEqual(rc, 0)
        fp = json.loads(out)["results"][0]["fingerprint"]
        rc2, out2, _ = self._cli("run", "--cases", self.case_path, "--output", self.out)
        exp = json.loads(out2)["trials"][0]["experiment_id"]
        self.assertEqual(exp, "exp-" + fp[:16])

    def test_missing_selected_key_fails_explicitly(self):
        self.case["material"]["keys"] = ["demo-a", "no-such-key"]
        self._write_cases()
        rc, out, _ = self._cli("validate", "--cases", self.case_path)
        self.assertEqual(rc, 2)
        self.assertIn("no-such-key", out)

    def test_fingerprint_missing_key_in_inventory_raises(self):
        with self.assertRaises(CaseError):
            cases.case_fingerprint(self.case, inventory={"dataset": {"videos": {}}})

    def test_binding_records_video_explicitly(self):
        binding = cases.resolve_material_inventory(self.case)
        entry = binding["dataset"]["videos"]["demo-a"]
        self.assertIn("comment_sha256", entry)
        self.assertIn("danmaku_sha256", entry)
        self.assertIn("video_sha256", entry)
        self.assertIsNone(entry["video_sha256"])
        video = self.base / "clip.mp4"
        video.write_bytes(b"synthetic-clip-v1")
        self.case["material"]["video_path"] = str(video)
        b1 = cases.resolve_material_inventory(self.case)
        video.write_bytes(b"synthetic-clip-v2")
        b2 = cases.resolve_material_inventory(self.case)
        self.assertNotEqual(b1["dataset"]["videos"]["demo-a"]["video_sha256"],
                            b2["dataset"]["videos"]["demo-a"]["video_sha256"])

    def test_material_change_between_validation_and_run_rejected(self):
        import contextlib
        import io
        import report_eval
        real = cases.resolve_material_inventory
        state = {"n": 0}

        def flaky(case):
            state["n"] += 1
            binding = real(case)
            if state["n"] == 2:
                binding = json.loads(json.dumps(binding))
                binding["dataset"]["videos"]["demo-a"]["comment_sha256"] = "0" * 64
            return binding

        with mock.patch.object(cases, "resolve_material_inventory", side_effect=flaky):
            with contextlib.redirect_stdout(io.StringIO()) as buf:
                rc = report_eval.main(["run", "--cases", str(self.case_path), "--output", str(self.out)])
        self.assertEqual(rc, 2)
        self.assertIn("changed", buf.getvalue())


class TestRepairR2DeliveryEvidence(unittest.TestCase):
    """R2: delivery must verify required fields, source identity, time scope and readability."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.root = Path(self._td.name) / "run"

    def _run(self, case):
        fp = cases.case_fingerprint(case)
        trial = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        offline.run_offline_case(trial, case)
        return trial

    def _report(self, trial):
        rj = trial.dir / "artifacts" / "report.json"
        return rj, json.loads(rj.read_text(encoding="utf-8"))

    def test_missing_window_and_empty_keys_fail(self):
        trial = self._run(make_case("r2a", "success"))
        rj, report = self._report(trial)
        report["sources"].pop("window")
        report["sources"]["dataset_keys"] = []
        rj.write_text(json.dumps(report, ensure_ascii=False), encoding="utf-8")
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(d["passed"])
        self.assertTrue(any("window" in r for r in d["reasons"]), d["reasons"])
        self.assertTrue(any("source" in r for r in d["reasons"]), d["reasons"])

    def test_invalid_utf8_markdown_fails_even_with_synced_hash(self):
        trial = self._run(make_case("r2b", "success"))
        rm = trial.dir / "artifacts" / "report.md"
        rm.write_bytes(b"\xff\xfe\x80\x00\x00")
        rj, report = self._report(trial)
        report["content_sha256"] = hashlib.sha256(rm.read_bytes()).hexdigest()
        rj.write_text(json.dumps(report), encoding="utf-8")
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(d["passed"])
        self.assertTrue(any("markdown" in r for r in d["reasons"]), d["reasons"])

    def test_legitimate_empty_result_passes(self):
        trial = self._run(make_case("r2c", "success"))
        rj, report = self._report(trial)
        report["data"] = {"rows": [], "empty_result": True}
        rj.write_text(json.dumps(report, ensure_ascii=False), encoding="utf-8")
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(d["passed"], d["reasons"])

    def test_wrong_structure_diagnosed(self):
        trial = self._run(make_case("r2-struct", "success"))
        (trial.dir / "artifacts" / "report.json").write_text(json.dumps(["not", "a", "dict"]), encoding="utf-8")
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(d["passed"])
        self.assertTrue(any("structure" in r for r in d["reasons"]), d["reasons"])

    def test_read_failure_diagnosed_not_crash(self):
        trial = self._run(make_case("r2-read", "success"))
        with mock.patch.object(Path, "read_bytes", side_effect=OSError("simulated disk error")):
            d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(d["passed"])
        self.assertTrue(d["reasons"])

    def test_bad_json_diagnosed_per_trial_batch_survives(self):
        self._run(make_case("r2-good", "success"))
        bad = self._run(make_case("r2-bad", "success"))
        (bad.dir / "artifacts" / "report.json").write_text("{not json", encoding="utf-8")
        s = summary.summarize_run(self.root)
        self.assertEqual(s["trial_count"], 2)
        rows = {r["case_id"]: r for r in s["rows"]}
        self.assertEqual(rows["r2-good"]["classification"], "success_delivered")
        self.assertEqual(rows["r2-bad"]["classification"], "success_delivery_failed")
        self.assertFalse(rows["r2-bad"]["delivery_passed"])


class TestRepairR3LedgerRobustness(unittest.TestCase):
    """R3: interrupted/corrupt records stay visible and diagnosable; one bad trial cannot sink the batch."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.root = Path(self._td.name) / "run"

    def _run(self, case):
        fp = cases.case_fingerprint(case)
        trial = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        offline.run_offline_case(trial, case)
        return trial

    def test_truncated_final_attempt_line_preserved_and_diagnosed(self):
        trial = self._run(make_case("r3-trunc", "success"))
        with (trial.dir / "attempts.jsonl").open("ab") as f:
            f.write(b'{"attempt_no":2,"kind":"request","outcome":')
        raw_before = (trial.dir / "attempts.jsonl").read_bytes()
        s = summary.summarize_run(self.root)
        self.assertEqual(s["trial_count"], 1)
        self.assertNotEqual(s["rows"][0]["classification"], "success_delivered")
        self.assertEqual((trial.dir / "attempts.jsonl").read_bytes(), raw_before)
        self.assertTrue(s["rows"][0]["diagnostics"])

    def test_bad_middle_line_keeps_complete_attempts(self):
        trial = self._run(make_case("r3-mid", "success"))
        p = trial.dir / "attempts.jsonl"
        lines = p.read_text(encoding="utf-8").splitlines()
        p.write_text(lines[0] + "\n{bad json\n" + "\n".join(lines[1:]) + "\n", encoding="utf-8")
        s = summary.summarize_run(self.root)
        self.assertEqual(s["trial_count"], 1)
        self.assertGreaterEqual(s["rows"][0]["attempts"], 1)
        self.assertNotEqual(s["rows"][0]["classification"], "success_delivered")

    def test_orphan_directory_visible_as_incomplete(self):
        self._run(make_case("r3-ok", "success"))
        orphan = self.root / "trials" / "trial-crash-before-meta"
        orphan.mkdir(parents=True)
        s = summary.summarize_run(self.root)
        self.assertEqual(s["trial_count"], 2)
        rows = {r["trial_id"]: r for r in s["rows"]}
        self.assertIn("trial-crash-before-meta", rows)
        self.assertEqual(rows["trial-crash-before-meta"]["classification"], "corrupted")
        self.assertTrue(rows["trial-crash-before-meta"]["diagnostics"])
        self.assertEqual(rows["r3-ok"] if "r3-ok" in rows else s["rows"][0]["classification"],
                         s["rows"][0]["classification"])

    def test_corrupt_trace_mixed_with_normal_trial(self):
        self._run(make_case("r3-good", "success"))
        bad = self._run(make_case("r3-badtrace", "success"))
        (bad.dir / "traces" / "trace.jsonl").write_bytes(b'{"broken\n')
        s = summary.summarize_run(self.root)
        self.assertEqual(s["trial_count"], 2)
        rows = {r["case_id"]: r for r in s["rows"]}
        self.assertEqual(rows["r3-good"]["classification"], "success_delivered")
        self.assertEqual(rows["r3-badtrace"]["trace_state"], "CORRUPT")
        self.assertIsNone(rows["r3-badtrace"]["input_tokens"])

    def test_corrupt_root_summarize_exit_code_honest(self):
        import contextlib
        import io
        import report_eval
        trial = self._run(make_case("r3-exit", "success"))
        with (trial.dir / "attempts.jsonl").open("ab") as f:
            f.write(b'{"partial')
        with contextlib.redirect_stdout(io.StringIO()):
            rc = report_eval.main(["summarize", "--input", str(self.root)])
        self.assertEqual(rc, 3)

    def test_clean_interrupted_not_flagged_corrupt(self):
        self._run(make_case("r3-clean-interrupt", "interrupted"))
        s = summary.summarize_run(self.root)
        self.assertEqual(s["rows"][0]["classification"], "interrupted")
        self.assertEqual(s["rows"][0]["diagnostics"], [])
        self.assertEqual(s["corrupted_trial_count"], 0)


class TestRepairR4PairingAndCsv(unittest.TestCase):
    """R4: dataset-key↔source-video must be one-to-one; CSV headers/quotes strictly validated."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name)
        self.ds = build_dataset_fixture(self.base / "ds")
        self.src = build_source_fixture(self.base / "src")

    def _cli(self, *args):
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
        proc = subprocess.run([sys.executable, str(SCRIPTS_DIR / "report_eval.py"), *map(str, args)],
                              cwd=WORKTREE_DIR, env=env, capture_output=True, text=True, encoding="utf-8")
        return proc.returncode, proc.stdout, proc.stderr

    def _add_duplicate_key(self):
        manifest = self.ds / "视频清单.csv"
        rows = csvio.read_csv_records(manifest, MANIFEST_HEADER)
        dup = dict(rows[0], key="demo-duplicate")
        rows.append(dup)
        write_csv(manifest, MANIFEST_HEADER, [[r[c] for c in MANIFEST_HEADER] for r in rows])
        for sub in ("评论", "弹幕"):
            (self.ds / sub / f"demo-duplicate_{sub}.csv").write_bytes(
                (self.ds / sub / f"demo-a_{sub}.csv").read_bytes())

    def test_duplicate_csv_header_rejected(self):
        p = self.base / "dup-header.csv"
        write_csv(p, ["评论ID", "评论内容", "评论内容"], [["c1", "first-column-text", "overwriting-column-text"]])
        with self.assertRaises(MaterialError):
            csvio.read_csv_records(p, ["评论ID", "评论内容"])

    def test_empty_header_column_rejected(self):
        p = self.base / "empty-header.csv"
        write_csv(p, ["评论ID", ""], [["c1", "text"]])
        with self.assertRaises(MaterialError):
            csvio.read_csv_records(p, ["评论ID"])

    def test_unterminated_quote_rejected_not_swallowed(self):
        p = self.base / "unterminated.csv"
        p.write_text('评论ID,评论内容\nc1,"unterminated\nc2,second-record\n', encoding="utf-8")
        with self.assertRaises(MaterialError):
            csvio.read_csv_records(p, ["评论ID", "评论内容"])

    def test_legal_multiline_chinese_control(self):
        p = self.base / "中文目录" / "legal.csv"
        write_csv(p, ["评论ID", "上级评论ID", "评论内容"],
                  [["c1", "", '第一行\n第二行,含"引号"'], ["c2", "c1", "回复"]])
        records = csvio.read_csv_records(p, ["评论ID", "上级评论ID", "评论内容"])
        self.assertEqual(len(records), 2)
        self.assertEqual(records[0]["评论内容"], '第一行\n第二行,含"引号"')

    def test_two_dataset_keys_reusing_source_rejected(self):
        self._add_duplicate_key()
        ds = datasets.index_dataset(self.ds)
        src = datasets.index_source(self.src, ffprobe="missing-ffprobe-executable")
        with self.assertRaises(MaterialError):
            datasets.match_and_reconcile(ds, src, self.ds, self.src)

    def test_inventory_cli_rejects_source_reuse(self):
        self._add_duplicate_key()
        rc, out, _ = self._cli("inventory", "--dataset-root", self.ds, "--source-root", self.src,
                               "--output", self.base / "index", "--ffprobe", "missing-ffprobe-executable")
        self.assertNotEqual(rc, 0)

    def test_inventory_cli_legal_control(self):
        rc, out, _ = self._cli("inventory", "--dataset-root", self.ds, "--source-root", self.src,
                               "--output", self.base / "index", "--ffprobe", "missing-ffprobe-executable")
        self.assertEqual(rc, 0, out)
        self.assertEqual(json.loads(out)["videos"], 2)
        self.assertEqual(json.loads(out)["findings"], 0)

    def test_independent_samples_are_unique_sources(self):
        idx = datasets.build_inventory(self.ds, self.src, self.base / "index", ffprobe="missing-ffprobe-executable")
        unique_sources = len({v["video_sha256"] for v in idx["source"]["videos"]})
        self.assertEqual(unique_sources, 2)
        self.assertEqual(idx["lineage"]["independent_video_samples"], unique_sources)


class TestRepairR5EvidenceSemantics(unittest.TestCase):
    """R5: missing trace is delivered but process-evidence incomplete; explanation must separate the axes."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.root = Path(self._td.name) / "run"

    def _run(self, case):
        fp = cases.case_fingerprint(case)
        trial = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        offline.run_offline_case(trial, case)
        return trial

    def test_missing_trace_delivered_with_incomplete_process_evidence(self):
        self._run(make_case("r5-missing-trace", "missing_trace"))
        s = summary.summarize_run(self.root)
        row = s["rows"][0]
        self.assertEqual(row["classification"], "success_delivered")
        self.assertTrue(row["delivery_passed"])
        self.assertEqual(row["trace_state"], "MISSING")
        self.assertEqual(row["semantic_quality"], "NOT_ASSESSED")
        self.assertIsNone(row["input_tokens"])
        joined = " ".join(s["notes"]).lower()
        self.assertIn("process", joined)

    def test_notes_separate_delivery_process_semantic(self):
        self._run(make_case("r5-notes", "success"))
        s = summary.summarize_run(self.root)
        joined = " ".join(s["notes"]).lower()
        for token in ("delivery", "process", "semantic"):
            self.assertIn(token, joined)

    def test_missing_report_is_delivery_failure_not_trace_issue(self):
        self._run(make_case("r5-missing-report", "missing_report"))
        s = summary.summarize_run(self.root)
        row = s["rows"][0]
        self.assertEqual(row["classification"], "success_delivery_failed")
        self.assertFalse(row["delivery_passed"])


class TestRepairR6OutputAnchor(unittest.TestCase):
    """R6: a junction/symlink trials dir must be refused before any write escapes the explicit root."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name)
        self.root = self.base / "run"

    def _refuse_redirect(self, output_root, outside):
        case = make_case("r6-redirect")
        fp = cases.case_fingerprint(case)
        with self.assertRaises(OutOfBoundsError):
            ledger.create_trial(output_root, case, fp, cases.experiment_id(fp))
        self.assertEqual(list(outside.rglob("*")), [])

    def test_junction_trials_dir_refused_before_write(self):
        output_root = self.base / "junction-output"
        outside = self.base / "outside-sentinel"
        output_root.mkdir()
        outside.mkdir()
        junction = output_root / "trials"
        link = subprocess.run(["cmd", "/d", "/c", "mklink", "/J", str(junction), str(outside)],
                              capture_output=True, encoding="utf-8", errors="replace")
        if link.returncode != 0:
            self.skipTest(f"junction creation unavailable (exit {link.returncode}): {link.stderr or link.stdout}")
        self._refuse_redirect(output_root, outside)

    def test_symlink_trials_dir_refused_before_write(self):
        output_root = self.base / "symlink-output"
        outside = self.base / "symlink-sentinel"
        output_root.mkdir()
        outside.mkdir()
        try:
            os.symlink(str(outside), str(output_root / "trials"), target_is_directory=True)
        except (OSError, NotImplementedError) as e:
            self.skipTest(f"symlink creation unavailable: {e}")
        self._refuse_redirect(output_root, outside)

    def test_normal_output_and_repeat_still_work(self):
        case = make_case("r6-normal")
        fp = cases.case_fingerprint(case)
        t1 = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        offline.run_offline_case(t1, case)
        t2 = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        offline.run_offline_case(t2, case)
        self.assertNotEqual(t1.trial_id, t2.trial_id)
        self.assertTrue((t1.dir / "trial_summary.json").is_file())
        self.assertTrue((t2.dir / "artifacts" / "report.md").is_file())
        s = summary.summarize_run(self.root)
        self.assertEqual(s["trial_count"], 2)
        self.assertEqual(s["corrupted_trial_count"], 0)


class TestRepair02R2ReportStructure(unittest.TestCase):
    """Repair-02 R2: null/invalid report structure and source element types must fail delivery
    per-trial without sinking a mixed CLI batch. Semantic quality stays NOT_ASSESSED."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name)
        self.root = self.base / "run"

    def _cli(self, *args):
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
        proc = subprocess.run([sys.executable, str(SCRIPTS_DIR / "report_eval.py"), *map(str, args)],
                              cwd=WORKTREE_DIR, env=env, capture_output=True, text=True, encoding="utf-8")
        return proc.returncode, proc.stdout, proc.stderr

    def _trial(self, case):
        fp = cases.case_fingerprint(case)
        trial = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        offline.run_offline_case(trial, case)
        return trial

    def _report_path(self, trial):
        return trial.dir / "artifacts" / "report.json"

    def _summarize(self):
        rc, out, err = self._cli("summarize", "--input", self.root)
        sp = self.root / "summary.json"
        doc = json.loads(sp.read_text(encoding="utf-8")) if sp.is_file() else None
        return rc, doc, err

    def test_null_report_fails_delivery(self):
        trial = self._trial(make_case("r2-null", "success"))
        self._report_path(trial).write_text("null", encoding="utf-8")
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(d["passed"], d["reasons"])
        self.assertTrue(any("structure" in r for r in d["reasons"]), d["reasons"])

    def test_null_report_mixed_batch_cli(self):
        bad = self._trial(make_case("r2-null-bad", "success"))
        self._trial(make_case("r2-null-healthy", "success"))
        self._report_path(bad).write_text("null", encoding="utf-8")
        rc, doc, err = self._summarize()
        self.assertIsNotNone(doc, err)
        self.assertEqual(doc["trial_count"], 2)
        rows = {r["case_id"]: r for r in doc["rows"]}
        self.assertEqual(rows["r2-null-bad"]["classification"], "success_delivery_failed")
        self.assertFalse(rows["r2-null-bad"]["delivery_passed"])
        self.assertEqual(rows["r2-null-healthy"]["classification"], "success_delivered")

    def test_unhashable_source_key_diagnosed_not_crash(self):
        trial = self._trial(make_case("r2-keys", "success"))
        rj = self._report_path(trial)
        report = json.loads(rj.read_text(encoding="utf-8"))
        report["sources"]["dataset_keys"] = [{}]
        rj.write_text(json.dumps(report), encoding="utf-8")
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(d["passed"], d["reasons"])
        self.assertTrue(any("source" in r for r in d["reasons"]), d["reasons"])

    def test_unhashable_source_key_mixed_batch_cli(self):
        bad = self._trial(make_case("r2-keys-bad", "success"))
        self._trial(make_case("r2-keys-healthy", "success"))
        rj = self._report_path(bad)
        report = json.loads(rj.read_text(encoding="utf-8"))
        report["sources"]["dataset_keys"] = [{}]
        rj.write_text(json.dumps(report), encoding="utf-8")
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 0, err)
        self.assertIsNotNone(doc, err)
        rows = {r["case_id"]: r for r in doc["rows"]}
        self.assertEqual(rows["r2-keys-bad"]["classification"], "success_delivery_failed")
        self.assertEqual(rows["r2-keys-healthy"]["classification"], "success_delivered")

    def test_report_top_level_scalar_and_array_fail(self):
        for i, value in enumerate((5, "text", ["a"], True)):
            trial = self._trial(make_case(f"r2-scalar-{i}", "success"))
            self._report_path(trial).write_text(json.dumps(value), encoding="utf-8")
            d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
            self.assertFalse(d["passed"], (value, d["reasons"]))
            self.assertTrue(any("structure" in r for r in d["reasons"]), (value, d["reasons"]))

    def test_report_missing_required_fields_fail(self):
        trial = self._trial(make_case("r2-empty-obj", "success"))
        self._report_path(trial).write_text("{}", encoding="utf-8")
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(d["passed"], d["reasons"])

    def test_source_object_wrong_type_fails(self):
        for sources in ([], "x", 5):
            trial = self._trial(make_case(f"r2-src-{type(sources).__name__}", "success"))
            rj = self._report_path(trial)
            report = json.loads(rj.read_text(encoding="utf-8"))
            report["sources"] = sources
            rj.write_text(json.dumps(report), encoding="utf-8")
            d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
            self.assertFalse(d["passed"], (sources, d["reasons"]))
            self.assertTrue(any("sources" in r for r in d["reasons"]), (sources, d["reasons"]))

    def test_report_id_and_hash_wrong_type_fail(self):
        trial = self._trial(make_case("r2-idtype", "success"))
        rj = self._report_path(trial)
        report = json.loads(rj.read_text(encoding="utf-8"))
        report["report_id"] = {"not": "a string"}
        report["content_sha256"] = ["not", "a", "hash"]
        rj.write_text(json.dumps(report), encoding="utf-8")
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(d["passed"], d["reasons"])
        self.assertTrue(any("report_id" in r for r in d["reasons"]), d["reasons"])
        self.assertTrue(any("hash" in r for r in d["reasons"]), d["reasons"])

    def test_window_wrong_type_fails(self):
        trial = self._trial(make_case("r2-window", "success"))
        rj = self._report_path(trial)
        report = json.loads(rj.read_text(encoding="utf-8"))
        report["sources"]["window"] = "not-a-window"
        rj.write_text(json.dumps(report), encoding="utf-8")
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(d["passed"], d["reasons"])
        self.assertTrue(any("window" in r for r in d["reasons"]), d["reasons"])

    def test_legal_report_and_empty_result_still_pass(self):
        trial = self._trial(make_case("r2-legal", "success"))
        d = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(d["passed"], d["reasons"])
        rj = self._report_path(trial)
        report = json.loads(rj.read_text(encoding="utf-8"))
        report["data"] = {"rows": [], "empty_result": True}
        rj.write_text(json.dumps(report), encoding="utf-8")
        d2 = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(d2["passed"], d2["reasons"])


class TestRepair02R3RecordIsolation(unittest.TestCase):
    """Repair-02 R3: partial metadata, illegal/conflicting Trace and unreadable logs must be
    diagnosed per-trial; a mixed CLI batch survives with exit 3 and preserved original bytes."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name)
        self.root = self.base / "run"

    def _cli(self, *args):
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
        proc = subprocess.run([sys.executable, str(SCRIPTS_DIR / "report_eval.py"), *map(str, args)],
                              cwd=WORKTREE_DIR, env=env, capture_output=True, text=True, encoding="utf-8")
        return proc.returncode, proc.stdout, proc.stderr

    def _trial(self, case):
        fp = cases.case_fingerprint(case)
        trial = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        offline.run_offline_case(trial, case)
        return trial

    def _summarize(self):
        rc, out, err = self._cli("summarize", "--input", self.root)
        sp = self.root / "summary.json"
        doc = json.loads(sp.read_text(encoding="utf-8")) if sp.is_file() else None
        return rc, doc, err

    def _snapshot(self, trial):
        return {str(p.relative_to(trial.dir)): sha256_file(p)
                for p in trial.dir.rglob("*") if p.is_file()}

    def _assert_preserved(self, trial, snap):
        for rel, digest in snap.items():
            self.assertEqual(sha256_file(trial.dir / rel), digest, rel)

    def _rows(self, doc):
        return {r["case_id"]: r for r in doc["rows"]}

    # R3-1: partial / empty / nested-type metadata is explicitly corrupted, never a KeyError crash.
    def test_empty_metadata_mixed_batch_cli(self):
        bad = self._trial(make_case("r3-empty-bad", "success"))
        self._trial(make_case("r3-empty-healthy", "success"))
        (bad.dir / "trial.json").write_text("{}", encoding="utf-8")
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        self.assertEqual(doc["trial_count"], 2)
        rows = self._rows(doc)
        bad_row = next(r for r in doc["rows"] if r["trial_id"] == bad.trial_id)
        self.assertEqual(bad_row["classification"], "corrupted")
        self.assertTrue(bad_row["diagnostics"])
        self.assertEqual(rows["r3-empty-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    def test_incomplete_metadata_mixed_batch_cli(self):
        bad = self._trial(make_case("r3-incomplete-bad", "success"))
        self._trial(make_case("r3-incomplete-healthy", "success"))
        meta = json.loads((bad.dir / "trial.json").read_text(encoding="utf-8"))
        meta.pop("case_snapshot")
        (bad.dir / "trial.json").write_text(json.dumps(meta), encoding="utf-8")
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        rows = self._rows(doc)
        bad_row = next(r for r in doc["rows"] if r["trial_id"] == bad.trial_id)
        self.assertEqual(bad_row["classification"], "corrupted")
        self.assertEqual(rows["r3-incomplete-healthy"]["classification"], "success_delivered")

    def test_metadata_nested_type_error_corrupted(self):
        bad = self._trial(make_case("r3-nested-bad", "success"))
        self._trial(make_case("r3-nested-healthy", "success"))
        meta = json.loads((bad.dir / "trial.json").read_text(encoding="utf-8"))
        meta["case_snapshot"] = []
        meta["semantic_eligibility"] = "not-a-dict"
        (bad.dir / "trial.json").write_text(json.dumps(meta), encoding="utf-8")
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        rows = self._rows(doc)
        bad_row = next(r for r in doc["rows"] if r["trial_id"] == bad.trial_id)
        self.assertEqual(bad_row["classification"], "corrupted")
        self.assertEqual(rows["r3-nested-healthy"]["classification"], "success_delivered")

    # R3-2: illegal / conflicting Trace is isolated per-trial; the public conflict rejection stays.
    def test_trace_null_mixed_batch_cli(self):
        bad = self._trial(make_case("r3-tracenull-bad", "success"))
        self._trial(make_case("r3-tracenull-healthy", "success"))
        (bad.dir / "traces" / "trace.jsonl").write_text("null\n", encoding="utf-8")
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        rows = self._rows(doc)
        self.assertEqual(rows["r3-tracenull-bad"]["trace_state"], "CORRUPT")
        self.assertIsNone(rows["r3-tracenull-bad"]["input_tokens"])
        self.assertTrue(rows["r3-tracenull-bad"]["diagnostics"])
        self.assertEqual(rows["r3-tracenull-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    def test_trace_missing_fields_mixed_batch_cli(self):
        bad = self._trial(make_case("r3-tracefields-bad", "success"))
        self._trial(make_case("r3-tracefields-healthy", "success"))
        (bad.dir / "traces" / "trace.jsonl").write_text('{"spanId":"only-one-field"}\n', encoding="utf-8")
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        rows = self._rows(doc)
        self.assertEqual(rows["r3-tracefields-bad"]["trace_state"], "CORRUPT")
        self.assertEqual(rows["r3-tracefields-healthy"]["classification"], "success_delivered")

    def _make_conflicting(self, trial):
        path = trial.dir / "traces" / "trace.jsonl"
        lines = path.read_text(encoding="utf-8").splitlines()
        conflicting = json.loads(lines[-1])
        conflicting["attributes"]["independent_test_conflict"] = True
        with path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(conflicting) + "\n")

    def test_conflicting_duplicate_strict_still_raises(self):
        trial = self._trial(make_case("r3-conflict-strict", "success"))
        self._make_conflicting(trial)
        with self.assertRaises(ValueError):
            summary.summarize_run(self.root)

    def test_conflicting_duplicate_mixed_batch_cli(self):
        bad = self._trial(make_case("r3-conflict-bad", "success"))
        self._trial(make_case("r3-conflict-healthy", "success"))
        self._make_conflicting(bad)
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        rows = self._rows(doc)
        self.assertEqual(rows["r3-conflict-bad"]["trace_state"], "CORRUPT")
        self.assertIsNone(rows["r3-conflict-bad"]["input_tokens"])
        self.assertEqual(rows["r3-conflict-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # R3-3: an attempts path occupied by a directory is an unreadable record, not a batch crash.
    def test_unreadable_attempts_mixed_batch_cli(self):
        bad = self._trial(make_case("r3-unread-bad", "success"))
        self._trial(make_case("r3-unread-healthy", "success"))
        original = bad.dir / "attempts.jsonl"
        preserved = bad.dir / "attempts-original.jsonl"
        original_bytes = original.read_bytes()
        original.rename(preserved)
        original.mkdir()
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        rows = self._rows(doc)
        self.assertEqual(rows["r3-unread-bad"]["classification"], "corrupted")
        self.assertTrue(any("unreadable" in d for d in rows["r3-unread-bad"]["diagnostics"]),
                        rows["r3-unread-bad"]["diagnostics"])
        self.assertEqual(rows["r3-unread-healthy"]["classification"], "success_delivered")
        self.assertEqual(preserved.read_bytes(), original_bytes)
        self.assertTrue(original.is_dir())

    # R3-4: terminal-state / summary read failures and bad-line matrix stay diagnosable.
    def test_unreadable_summary_diagnosed(self):
        bad = self._trial(make_case("r3-sumdir-bad", "success"))
        self._trial(make_case("r3-sumdir-healthy", "success"))
        sp = bad.dir / "trial_summary.json"
        sp.rename(bad.dir / "trial_summary_original.json")
        sp.mkdir()
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        rows = self._rows(doc)
        self.assertEqual(rows["r3-sumdir-bad"]["classification"], "corrupted")
        self.assertTrue(any("summary" in d for d in rows["r3-sumdir-bad"]["diagnostics"]),
                        rows["r3-sumdir-bad"]["diagnostics"])
        self.assertEqual(rows["r3-sumdir-healthy"]["classification"], "success_delivered")

    def test_bad_middle_and_truncated_attempt_matrix_cli(self):
        mid = self._trial(make_case("r3-mid-bad", "success"))
        trunc = self._trial(make_case("r3-trunc-bad", "success"))
        self._trial(make_case("r3-matrix-healthy", "success"))
        p = mid.dir / "attempts.jsonl"
        lines = p.read_text(encoding="utf-8").splitlines()
        p.write_text(lines[0] + "\n{bad json\n" + "\n".join(lines[1:]) + "\n", encoding="utf-8")
        with (trunc.dir / "attempts.jsonl").open("ab") as f:
            f.write(b'{"attempt_no":2,"outcome":')
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        self.assertEqual(doc["trial_count"], 3)
        rows = self._rows(doc)
        self.assertEqual(rows["r3-mid-bad"]["classification"], "corrupted")
        self.assertEqual(rows["r3-trunc-bad"]["classification"], "corrupted")
        self.assertEqual(rows["r3-matrix-healthy"]["classification"], "success_delivered")


class TestRepair03R3ConsumptionBoundary(unittest.TestCase):
    """Repair-03 R3: consumption-field-driven isolation. Metadata that only looks like an object,
    Trace internal structure / occupied path, and parseable-but-invalid terminal/attempt records
    must be diagnosed per-trial (exit 3, summary written, healthy trials visible, bytes preserved),
    never crash the batch (exit 1) nor masquerade as clean success."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name)
        self.root = self.base / "run"

    def _cli(self, *args):
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
        proc = subprocess.run([sys.executable, str(SCRIPTS_DIR / "report_eval.py"), *map(str, args)],
                              cwd=WORKTREE_DIR, env=env, capture_output=True, text=True, encoding="utf-8")
        return proc.returncode, proc.stdout, proc.stderr

    def _trial(self, case):
        fp = cases.case_fingerprint(case)
        trial = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        offline.run_offline_case(trial, case)
        return trial

    def _summarize(self):
        rc, out, err = self._cli("summarize", "--input", self.root)
        sp = self.root / "summary.json"
        doc = json.loads(sp.read_text(encoding="utf-8")) if sp.is_file() else None
        return rc, doc, err

    def _snapshot(self, trial):
        return {str(p.relative_to(trial.dir)): sha256_file(p)
                for p in trial.dir.rglob("*") if p.is_file()}

    def _assert_preserved(self, trial, snap):
        for rel, digest in snap.items():
            self.assertEqual(sha256_file(trial.dir / rel), digest, rel)

    def _rows(self, doc):
        return {r["case_id"]: r for r in doc["rows"]}

    def _edit_meta(self, trial, mutate):
        p = trial.dir / "trial.json"
        meta = json.loads(p.read_text(encoding="utf-8"))
        mutate(meta)
        p.write_text(json.dumps(meta), encoding="utf-8")

    def _edit_trace(self, trial, mutate):
        p = trial.dir / "traces" / "trace.jsonl"
        spans = [json.loads(line) for line in p.read_text(encoding="utf-8").splitlines() if line.strip()]
        mutate(spans)
        p.write_text("".join(json.dumps(s) + "\n" for s in spans), encoding="utf-8")

    def _edit_terminal(self, trial, mutate):
        p = trial.dir / "trial_summary.json"
        meta = json.loads(p.read_text(encoding="utf-8"))
        mutate(meta)
        p.write_text(json.dumps(meta), encoding="utf-8")

    def _assert_bad_corrupted(self, doc, trial):
        row = next(r for r in doc["rows"] if r["trial_id"] == trial.trial_id)
        self.assertEqual(row["classification"], "corrupted", trial.trial_id)
        self.assertTrue(row["diagnostics"], trial.trial_id)
        return row

    # R3-A1: metadata shell looks like an object but a consumed nested field is unusable.
    def test_material_kind_missing_mixed_batch_cli(self):
        bad = self._trial(make_case("r3a-kind-bad", "success"))
        self._trial(make_case("r3a-kind-healthy", "success"))
        self._edit_meta(bad, lambda m: m["case_snapshot"]["material"].pop("kind"))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        self.assertEqual(doc["trial_count"], 2)
        self._assert_bad_corrupted(doc, bad)
        self.assertEqual(self._rows(doc)["r3a-kind-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    def test_task_clock_wrong_type_mixed_batch_cli(self):
        bad = self._trial(make_case("r3a-clock-bad", "success"))
        self._trial(make_case("r3a-clock-healthy", "success"))
        self._edit_meta(bad, lambda m: m["case_snapshot"].update(task_clock=[]))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        self._assert_bad_corrupted(doc, bad)
        self.assertEqual(self._rows(doc)["r3a-clock-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # R3-A2: eligibility must be a real bool; a string must not become semantic admission.
    def test_eligibility_wrong_type_mixed_batch_cli(self):
        bad = self._trial(make_case("r3a-elig-bad", "success"))
        self._trial(make_case("r3a-elig-healthy", "success"))
        self._edit_meta(bad, lambda m: m["semantic_eligibility"].update(eligible="yes"))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        row = self._assert_bad_corrupted(doc, bad)
        self.assertNotEqual(row["semantic_eligible"], "yes")
        self.assertEqual(self._rows(doc)["r3a-elig-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # R3-A3: finite nested-type matrix for the fields check_delivery / resolve_window consume.
    def test_metadata_structure_matrix_cli(self):
        mutations = [
            ("kind-scalar", lambda m: m["case_snapshot"]["material"].update(kind=5)),
            ("keys-missing", lambda m: m["case_snapshot"]["material"].update(kind="dataset_ref")),
            ("keys-not-list", lambda m: m["case_snapshot"]["material"].update(kind="dataset_ref", keys="demo-a")),
            ("keys-nonstr", lambda m: m["case_snapshot"]["material"].update(kind="dataset_ref", keys=[{}])),
            ("clock-missing", lambda m: m["case_snapshot"].pop("task_clock")),
            ("tz-wrong", lambda m: m["case_snapshot"]["task_clock"].update(timezone=[])),
            ("reftime-wrong", lambda m: m["case_snapshot"]["task_clock"].update(reference_time=5)),
            ("window-not-dict", lambda m: m["case_snapshot"].update(task_window=[])),
            ("window-kind-bad", lambda m: m["case_snapshot"]["task_window"].update(kind="bogus")),
            ("window-days-bad", lambda m: m["case_snapshot"]["task_window"].update(days="seven")),
        ]
        bad_trials = []
        for name, mutate in mutations:
            t = self._trial(make_case("r3a-matrix-" + name, "success"))
            self._edit_meta(t, mutate)
            bad_trials.append(t)
        self._trial(make_case("r3a-matrix-healthy", "success"))
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        self.assertEqual(doc["trial_count"], len(mutations) + 1)
        for t in bad_trials:
            self._assert_bad_corrupted(doc, t)
        self.assertEqual(self._rows(doc)["r3a-matrix-healthy"]["classification"], "success_delivered")

    def test_metadata_legal_variants_still_deliver(self):
        ds = make_case("r3a-legal-ds", "success")
        ds["material"] = {"kind": "dataset_ref", "dataset_root": "synthetic", "keys": ["demo-a"],
                          "manifest_sha256": "0" * 64, "effective_timeline": "shifted"}
        self._trial(ds)
        ab = make_case("r3a-legal-abs", "success")
        ab["task_window"] = {"kind": "absolute", "start": "2026-11-03", "end": "2026-11-10"}
        self._trial(ab)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 0, err)
        rows = self._rows(doc)
        self.assertEqual(rows["r3a-legal-ds"]["classification"], "success_delivered")
        self.assertEqual(rows["r3a-legal-abs"]["classification"], "success_delivered")

    # R3-B1: span.attributes=null must be isolated per-trial, not an AttributeError batch crash.
    def test_trace_attributes_null_mixed_batch_cli(self):
        bad = self._trial(make_case("r3b-attr-bad", "success"))
        self._trial(make_case("r3b-attr-healthy", "success"))
        self._edit_trace(bad, lambda spans: spans[-1].update(attributes=None))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        rows = self._rows(doc)
        bad_row = rows["r3b-attr-bad"]
        self.assertEqual(bad_row["trace_state"], "CORRUPT")
        self.assertIsNone(bad_row["input_tokens"])
        self.assertTrue(bad_row["diagnostics"])
        self.assertEqual(bad_row["classification"], "success_delivered")
        self.assertEqual(rows["r3b-attr-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # R3-B2: an occupied trace path is CORRUPT (not MISSING); delivery axis keeps its verdict.
    def test_trace_path_directory_mixed_batch_cli(self):
        bad = self._trial(make_case("r3b-dir-bad", "success"))
        self._trial(make_case("r3b-dir-healthy", "success"))
        trace = bad.dir / "traces" / "trace.jsonl"
        preserved = bad.dir / "traces" / "trace-original.jsonl"
        original_bytes = trace.read_bytes()
        trace.rename(preserved)
        trace.mkdir()
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        rows = self._rows(doc)
        bad_row = rows["r3b-dir-bad"]
        self.assertEqual(bad_row["trace_state"], "CORRUPT")
        self.assertTrue(bad_row["diagnostics"])
        self.assertIsNone(bad_row["input_tokens"])
        self.assertEqual(bad_row["classification"], "success_delivered")
        self.assertTrue(bad_row["delivery_passed"])
        self.assertEqual(rows["r3b-dir-healthy"]["classification"], "success_delivered")
        self.assertEqual(preserved.read_bytes(), original_bytes)
        self.assertTrue(trace.is_dir())

    def test_trace_structure_matrix_cli(self):
        mutations = [
            ("attr-list", lambda s: s[-1].update(attributes=[])),
            ("attr-scalar", lambda s: s[-1].update(attributes="x")),
            ("parent-bad", lambda s: s[-1].update(parentSpanId=[])),
            ("root-ts-missing", lambda s: s[0].pop("endTimeUnixNano")),
            ("root-ts-bad", lambda s: s[0].update(startTimeUnixNano="soon")),
            ("runid-bad", lambda s: s[-1]["attributes"].update({"run.id": []})),
            ("respms-bad", lambda s: s[-1]["attributes"].update({"model.response_ms": "fast"})),
            ("timing-bad", lambda s: s[0]["attributes"].update({"execution.queue_ms": "x"})),
        ]
        bad_trials = []
        for name, mutate in mutations:
            t = self._trial(make_case("r3b-matrix-" + name, "success"))
            self._edit_trace(t, mutate)
            bad_trials.append(t)
        self._trial(make_case("r3b-matrix-healthy", "success"))
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        for t in bad_trials:
            row = self._rows(doc)["r3b-matrix-" + t.meta["case_id"].split("r3b-matrix-")[1]]
            self.assertEqual(row["trace_state"], "CORRUPT", t.trial_id)
            self.assertIsNone(row["input_tokens"], t.trial_id)
        self.assertEqual(self._rows(doc)["r3b-matrix-healthy"]["classification"], "success_delivered")

    def test_trace_legal_optional_fields_and_unknown_usage(self):
        legal = self._trial(make_case("r3b-legal-opt", "success"))
        self._edit_trace(legal, lambda spans: spans[0].pop("attributes"))
        unknown = self._trial(make_case("r3b-legal-unknown", "success"))
        self._edit_trace(unknown, lambda spans: spans[-1]["attributes"].update({"model.input_tokens": "unknown"}))
        self._trial(make_case("r3b-legal-missing", "missing_trace"))
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 0, err)
        rows = self._rows(doc)
        self.assertEqual(rows["r3b-legal-opt"]["trace_state"], "PRESENT")
        self.assertEqual(rows["r3b-legal-unknown"]["trace_state"], "PRESENT")
        self.assertIsNone(rows["r3b-legal-unknown"]["input_tokens"])
        self.assertEqual(rows["r3b-legal-missing"]["trace_state"], "MISSING")
        self.assertEqual(rows["r3b-legal-missing"]["classification"], "success_delivered")

    # R3-C1: an existing terminal with missing/unknown final_status is corrupted, not clean "other".
    def test_terminal_missing_status_mixed_batch_cli(self):
        bad = self._trial(make_case("r3c-missing-bad", "success"))
        self._trial(make_case("r3c-missing-healthy", "success"))
        self._edit_terminal(bad, lambda m: m.pop("final_status"))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        self._assert_bad_corrupted(doc, bad)
        self.assertEqual(self._rows(doc)["r3c-missing-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    def test_terminal_unknown_status_mixed_batch_cli(self):
        bad = self._trial(make_case("r3c-unknown-bad", "success"))
        self._trial(make_case("r3c-unknown-healthy", "success"))
        self._edit_terminal(bad, lambda m: m.update(final_status="NOT_A_REAL_STATUS"))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        self._assert_bad_corrupted(doc, bad)
        self.assertEqual(self._rows(doc)["r3c-unknown-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # R3-C2: parseable JSON null is not a valid attempt; bad line keeps bytes/line_no, not counted.
    def test_attempt_null_record_mixed_batch_cli(self):
        bad = self._trial(make_case("r3c-null-bad", "success"))
        self._trial(make_case("r3c-null-healthy", "success"))
        with (bad.dir / "attempts.jsonl").open("a", encoding="utf-8") as f:
            f.write("null\n")
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        row = self._assert_bad_corrupted(doc, bad)
        self.assertEqual(row["attempts"], 1)
        self.assertEqual(self._rows(doc)["r3c-null-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    def test_terminal_legal_variants_keep_classification(self):
        self._trial(make_case("r3c-leg-failed", "failure"))
        self._trial(make_case("r3c-leg-interrupt", "interrupted"))
        cancelled = self._trial(make_case("r3c-leg-cancelled", "success"))
        self._edit_terminal(cancelled, lambda m: m.update(final_status="CANCELLED"))
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 0, err)
        rows = self._rows(doc)
        self.assertEqual(rows["r3c-leg-failed"]["classification"], "failed")
        self.assertEqual(rows["r3c-leg-interrupt"]["classification"], "interrupted")
        self.assertEqual(rows["r3c-leg-cancelled"]["classification"], "other")
        self.assertFalse(rows["r3c-leg-cancelled"]["diagnostics"])

    def test_attempt_structure_matrix_cli(self):
        variants = [
            ("scalar", "42\n"),
            ("array", "[]\n"),
            ("missing-kind", json.dumps({"attempt_no": 2, "recorded_at": "x"}) + "\n"),
            ("bad-no-type", json.dumps({"attempt_no": "two", "recorded_at": "x",
                                        "kind": "request", "outcome": "SUCCEEDED"}) + "\n"),
        ]
        bad_trials = []
        for name, line in variants:
            t = self._trial(make_case("r3c-att-" + name, "success"))
            with (t.dir / "attempts.jsonl").open("a", encoding="utf-8") as f:
                f.write(line)
            bad_trials.append(t)
        legal = self._trial(make_case("r3c-att-legal", "success"))
        with (legal.dir / "attempts.jsonl").open("a", encoding="utf-8") as f:
            f.write(json.dumps({"attempt_no": 2, "recorded_at": "x",
                                "kind": "clarification_reply", "reply": "ok"}) + "\n")
        self._trial(make_case("r3c-att-healthy", "success"))
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        for t in bad_trials:
            self._assert_bad_corrupted(doc, t)
        rows = self._rows(doc)
        self.assertEqual(rows["r3c-att-legal"]["classification"], "success_delivered")
        self.assertEqual(rows["r3c-att-legal"]["attempts"], 2)
        self.assertEqual(rows["r3c-att-healthy"]["classification"], "success_delivered")


class TestRepair04ConsumptionValues(unittest.TestCase):
    """Repair-04: beyond type-correctness, consumed values must be legal/enumerated/computable.
    A type-valid but uncomputable window, non-bool terminal flags, an unknown material kind and a
    non-positive attempt number must each be isolated per-trial (exit 3, summary written, healthy
    visible, bytes preserved), never crash the batch nor pollute stats; legal variants stay clean."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name)
        self.root = self.base / "run"

    def _cli(self, *args):
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
        proc = subprocess.run([sys.executable, str(SCRIPTS_DIR / "report_eval.py"), *map(str, args)],
                              cwd=WORKTREE_DIR, env=env, capture_output=True, text=True, encoding="utf-8")
        return proc.returncode, proc.stdout, proc.stderr

    def _trial(self, case):
        fp = cases.case_fingerprint(case)
        trial = ledger.create_trial(self.root, case, fp, cases.experiment_id(fp))
        offline.run_offline_case(trial, case)
        return trial

    def _summarize(self):
        rc, out, err = self._cli("summarize", "--input", self.root)
        sp = self.root / "summary.json"
        doc = json.loads(sp.read_text(encoding="utf-8")) if sp.is_file() else None
        return rc, doc, err

    def _snapshot(self, trial):
        return {str(p.relative_to(trial.dir)): sha256_file(p)
                for p in trial.dir.rglob("*") if p.is_file()}

    def _assert_preserved(self, trial, snap):
        for rel, digest in snap.items():
            self.assertEqual(sha256_file(trial.dir / rel), digest, rel)

    def _rows(self, doc):
        return {r["case_id"]: r for r in doc["rows"]}

    def _edit_meta(self, trial, mutate):
        p = trial.dir / "trial.json"
        meta = json.loads(p.read_text(encoding="utf-8"))
        mutate(meta)
        p.write_text(json.dumps(meta), encoding="utf-8")

    def _edit_terminal(self, trial, mutate):
        p = trial.dir / "trial_summary.json"
        meta = json.loads(p.read_text(encoding="utf-8"))
        mutate(meta)
        p.write_text(json.dumps(meta), encoding="utf-8")

    def _set_last_attempt_no(self, trial, value):
        p = trial.dir / "attempts.jsonl"
        rows = [json.loads(line) for line in p.read_text(encoding="utf-8").splitlines() if line.strip()]
        rows[-1]["attempt_no"] = value
        p.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")

    def _assert_bad_corrupted(self, doc, trial):
        row = next(r for r in doc["rows"] if r["trial_id"] == trial.trial_id)
        self.assertEqual(row["classification"], "corrupted", trial.trial_id)
        self.assertTrue(row["diagnostics"], trial.trial_id)
        return row

    def _dataset_case(self, cid):
        case = make_case(cid, "success")
        case["material"] = {"kind": "dataset_ref", "dataset_root": "synthetic", "keys": ["demo-a"],
                            "manifest_sha256": "0" * 64, "effective_timeline": "shifted"}
        return case

    # A: a positive-int days that overflows timedelta must be isolated, not crash the batch.
    def test_window_days_overflow_mixed_batch_cli(self):
        bad = self._trial(make_case("r4-win-bad", "success"))
        self._trial(make_case("r4-win-healthy", "success"))
        self._edit_meta(bad, lambda m: m["case_snapshot"]["task_window"].update(days=10**12))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        self.assertEqual(doc["trial_count"], 2)
        row = self._assert_bad_corrupted(doc, bad)
        self.assertIn("incomplete_trial_metadata:case_snapshot.task_window", row["diagnostics"])
        self.assertEqual(self._rows(doc)["r4-win-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # B1: recovered={} must not enter the row/CSV; report delivery axis stays truthful.
    def test_terminal_recovered_object_mixed_batch_cli(self):
        bad = self._trial(make_case("r4-rec-bad", "success"))
        self._trial(make_case("r4-rec-healthy", "success"))
        self._edit_terminal(bad, lambda m: m.update(recovered={}))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        row = self._assert_bad_corrupted(doc, bad)
        self.assertIn("invalid_terminal_recovered", row["diagnostics"])
        self.assertIsNone(row["recovered"])
        self.assertTrue(row["delivery_passed"])
        self.assertEqual(self._rows(doc)["r4-rec-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # B2: first_attempt_outcome=[] must not enter first-result stats.
    def test_terminal_first_outcome_array_mixed_batch_cli(self):
        bad = self._trial(make_case("r4-fa-bad", "success"))
        self._trial(make_case("r4-fa-healthy", "success"))
        self._edit_terminal(bad, lambda m: m.update(first_attempt_outcome=[]))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        row = self._assert_bad_corrupted(doc, bad)
        self.assertIn("invalid_terminal_first_attempt_outcome", row["diagnostics"])
        self.assertIsNone(row["first_attempt_outcome"])
        self.assertTrue(row["delivery_passed"])
        self.assertEqual(self._rows(doc)["r4-fa-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # B3: string 'false' must not be truthy-coerced into a violation; out_of_bounds marker stays legal.
    def test_terminal_violation_false_string_mixed_batch_cli(self):
        bad = self._trial(make_case("r4-vio-bad", "success"))
        self._trial(make_case("r4-vio-healthy", "success"))
        self._edit_terminal(bad, lambda m: m.update(delivery_violation="false"))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        row = self._assert_bad_corrupted(doc, bad)
        self.assertIn("invalid_terminal_delivery_violation", row["diagnostics"])
        self.assertNotEqual(row["classification"], "delivery_violation")
        self.assertEqual(doc["classifications"].get("delivery_violation", 0), 0)
        self.assertTrue(row["delivery_passed"])
        self.assertEqual(self._rows(doc)["r4-vio-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # B5: attempt_no=-1 is not a valid attempt; bad line keeps bytes/line_no, not counted.
    def test_attempt_negative_number_mixed_batch_cli(self):
        bad = self._trial(make_case("r4-att-bad", "success"))
        self._trial(make_case("r4-att-healthy", "success"))
        self._set_last_attempt_no(bad, -1)
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        row = self._assert_bad_corrupted(doc, bad)
        self.assertIn("corrupt_attempt_lines", row["diagnostics"])
        self.assertEqual(row["attempts"], 0)
        loaded = next(t for t in ledger.load_trials(self.root) if t["dir"].name == bad.trial_id)
        self.assertEqual(loaded["bad_attempt_lines"][0]["line_no"], 1)
        self.assertIn("-1", loaded["bad_attempt_lines"][0]["raw_preview"])
        self.assertEqual(self._rows(doc)["r4-att-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # B4: unknown material kind is metadata corruption, not a default synthetic downgrade.
    def test_material_unknown_kind_mixed_batch_cli(self):
        bad = self._trial(make_case("r4-kind-bad", "success"))
        self._trial(make_case("r4-kind-healthy", "success"))
        self._edit_meta(bad, lambda m: m["case_snapshot"]["material"].update(kind="BROKEN_KIND"))
        snap = self._snapshot(bad)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        row = self._assert_bad_corrupted(doc, bad)
        self.assertIn("incomplete_trial_metadata:case_snapshot.material.kind", row["diagnostics"])
        self.assertIsNone(row["case_id"])
        self.assertEqual(self._rows(doc)["r4-kind-healthy"]["classification"], "success_delivered")
        self._assert_preserved(bad, snap)

    # Legal controls: bool flags and the frozen out_of_bounds marker keep their classification.
    def test_terminal_legal_bool_flags_keep_classification(self):
        rec_true = self._trial(make_case("r4-leg-recT", "success"))
        self._edit_terminal(rec_true, lambda m: m.update(recovered=True))
        self._trial(make_case("r4-leg-recF", "success"))
        vio_true = self._trial(make_case("r4-leg-vioT", "success"))
        self._edit_terminal(vio_true, lambda m: m.update(delivery_violation=True))
        vio_false = self._trial(make_case("r4-leg-vioF", "success"))
        self._edit_terminal(vio_false, lambda m: m.update(delivery_violation=False))
        oob = self._trial(make_case("r4-leg-oob", "out_of_bounds"))
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 0, err)
        rows = self._rows(doc)
        self.assertEqual(rows["r4-leg-recT"]["classification"], "success_delivered")
        self.assertIs(rows["r4-leg-recT"]["recovered"], True)
        self.assertIs(rows["r4-leg-recF"]["recovered"], False)
        self.assertEqual(rows["r4-leg-vioT"]["classification"], "delivery_violation")
        self.assertEqual(rows["r4-leg-vioF"]["classification"], "success_delivered")
        self.assertEqual(rows["r4-leg-oob"]["classification"], "delivery_violation")
        self.assertFalse(rows["r4-leg-oob"]["diagnostics"])
        oob_loaded = next(t for t in ledger.load_trials(self.root) if t["dir"].name == oob.trial_id)
        self.assertEqual(oob_loaded["summary"]["delivery_violation"], "out_of_bounds")

    def test_first_attempt_outcome_legal_statuses(self):
        statuses = [("r4-leg-faS", "SUCCEEDED"), ("r4-leg-faF", "FAILED"),
                    ("r4-leg-faN", "NEEDS_INPUT"), ("r4-leg-faC", "CANCELLED")]
        for cid, status in statuses:
            t = self._trial(make_case(cid, "success"))
            self._edit_terminal(t, lambda m, s=status: m.update(first_attempt_outcome=s))
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 0, err)
        rows = self._rows(doc)
        for cid, status in statuses:
            self.assertEqual(rows[cid]["first_attempt_outcome"], status)
            self.assertEqual(rows[cid]["classification"], "success_delivered")
            self.assertFalse(rows[cid]["diagnostics"])

    def test_material_both_legal_kinds_deliver(self):
        self._trial(make_case("r4-leg-syn", "success"))
        self._trial(self._dataset_case("r4-leg-ds"))
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 0, err)
        rows = self._rows(doc)
        self.assertEqual(rows["r4-leg-syn"]["classification"], "success_delivered")
        self.assertEqual(rows["r4-leg-ds"]["classification"], "success_delivered")

    def test_attempt_positive_numbers_count(self):
        t = self._trial(make_case("r4-leg-att", "success"))
        with (t.dir / "attempts.jsonl").open("a", encoding="utf-8") as f:
            f.write(json.dumps({"attempt_no": 2, "recorded_at": "x", "kind": "retry", "outcome": "SUCCEEDED"}) + "\n")
            f.write(json.dumps({"attempt_no": 3, "recorded_at": "x", "kind": "clarification_reply", "reply": "ok"}) + "\n")
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 0, err)
        row = self._rows(doc)["r4-leg-att"]
        self.assertEqual(row["attempts"], 3)
        self.assertEqual(row["classification"], "success_delivered")

    def test_window_computable_variants_deliver(self):
        self._trial(make_case("r4-leg-rel", "success"))
        ab = make_case("r4-leg-abs", "success")
        ab["task_window"] = {"kind": "absolute", "start": "2026-11-03", "end": "2026-11-10"}
        self._trial(ab)
        big = make_case("r4-leg-big", "success")
        big["task_window"] = {"kind": "relative", "days": 3650, "anchor": "yesterday"}
        self._trial(big)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 0, err)
        rows = self._rows(doc)
        for cid in ("r4-leg-rel", "r4-leg-abs", "r4-leg-big"):
            self.assertEqual(rows[cid]["classification"], "success_delivered")

    # All six new bad records + one normal in ONE batch: exit 3, full diagnostics, CSV clean.
    def test_all_six_bad_plus_normal_same_batch_cli(self):
        b_win = self._trial(make_case("r4-mix-win", "success"))
        self._edit_meta(b_win, lambda m: m["case_snapshot"]["task_window"].update(days=10**12))
        b_rec = self._trial(make_case("r4-mix-rec", "success"))
        self._edit_terminal(b_rec, lambda m: m.update(recovered={}))
        b_fa = self._trial(make_case("r4-mix-fa", "success"))
        self._edit_terminal(b_fa, lambda m: m.update(first_attempt_outcome=[]))
        b_vio = self._trial(make_case("r4-mix-vio", "success"))
        self._edit_terminal(b_vio, lambda m: m.update(delivery_violation="false"))
        b_att = self._trial(make_case("r4-mix-att", "success"))
        self._set_last_attempt_no(b_att, -1)
        b_kind = self._trial(make_case("r4-mix-kind", "success"))
        self._edit_meta(b_kind, lambda m: m["case_snapshot"]["material"].update(kind="BROKEN_KIND"))
        bad_trials = [b_win, b_rec, b_fa, b_vio, b_att, b_kind]
        self._trial(make_case("r4-mix-healthy", "success"))
        snaps = {t.trial_id: self._snapshot(t) for t in bad_trials}
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 3, err)
        self.assertIsNotNone(doc, err)
        self.assertEqual(doc["trial_count"], 7)
        self.assertEqual(doc["corrupted_trial_count"], 6)
        self.assertEqual(doc["classifications"].get("delivery_violation", 0), 0)
        for t in bad_trials:
            self._assert_bad_corrupted(doc, t)
            self._assert_preserved(t, snaps[t.trial_id])
        self.assertEqual(self._rows(doc)["r4-mix-healthy"]["classification"], "success_delivered")
        with open(self.root / "summary.csv", encoding="utf-8-sig", newline="") as f:
            csv_rows = list(csv.DictReader(f))
        by_trial_csv = {cr["trial_id"]: cr for cr in csv_rows}
        for cr in csv_rows:
            self.assertNotIn(cr["recovered"], ("{}", "[]"))
            self.assertNotIn(cr["first_attempt_outcome"], ("{}", "[]", "false"))
        self.assertEqual(by_trial_csv[b_rec.trial_id]["recovered"], "")
        self.assertEqual(by_trial_csv[b_fa.trial_id]["first_attempt_outcome"], "")

    def test_clean_batch_exit_zero(self):
        self._trial(make_case("r4-clean-syn", "success"))
        self._trial(make_case("r4-clean-missing", "missing_trace"))
        self._trial(make_case("r4-clean-interrupt", "interrupted"))
        self._trial(make_case("r4-clean-failed", "failure"))
        self._trial(self._dataset_case("r4-clean-ds"))
        ab = make_case("r4-clean-abs", "success")
        ab["task_window"] = {"kind": "absolute", "start": "2026-11-03", "end": "2026-11-10"}
        self._trial(ab)
        rc, doc, err = self._summarize()
        self.assertEqual(rc, 0, err)
        self.assertEqual(doc["trial_count"], 6)
        self.assertEqual(doc["corrupted_trial_count"], 0)
        self.assertEqual(doc["classifications"], {"success_delivered": 4, "interrupted": 1, "failed": 1})


if __name__ == "__main__":
    unittest.main()