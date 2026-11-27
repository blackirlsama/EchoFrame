"""4A product-report bridge tests.

Two evidence kinds are kept strictly apart:
  * REAL_CAPTURE - bundles actually serialized by the product ReportController under
    demo/mock via ReportEvaluationBridgeHttpTest. Only these prove product integration.
  * MUTATION - deliberately broken copies or synthetic shapes used as counterexamples.
    They are never presented as product run facts.

New modules are imported inside the tests that need them so that a missing API shows up
as a functional failure alongside real assertion failures, instead of one collection
error hiding every other result.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))

from report_evaluation import cases, delivery, ledger, summary  # noqa: E402
from report_evaluation.util import OutOfBoundsError  # noqa: E402

TREE = SCRIPTS.parent
PYTHON = sys.executable
RUN_ROOT = TREE / "agent/eval/fixtures"
REAL_CAPTURE_ROOT = RUN_ROOT / "product-captures"
MUTATION_MARKER = "mutation_fixture_not_product_evidence"
REBUILD_CAPTURES = ("mvn -o -f agent/pom.xml -Dspring.profiles.active=demo "
                    "-Dtest=ReportEvaluationBridgeHttpTest test")

# The product refuses explicit ranges older than 30 days, so the captures use this window.
WINDOW_START = "2026-11-05T00:00"
WINDOW_END = "2026-11-12T00:00"
PROMPT = "分析视频1001从2026-11-05到2026-11-12的评论和弹幕，生成观众反馈HTML报告，不读取视频内容"
OUT_OF_RANGE_PROMPT = "分析视频1001从2026-10-10到2026-10-20的评论和弹幕，生成观众反馈HTML报告，不读取视频内容"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def product_json_bytes(**over):
    """A synthetic product-shaped payload for counterexamples only, never product evidence."""
    doc = {
        "schemaVersion": "feedback-report-json-v1",
        "title": "观众反馈报告",
        "summary": "本次范围内没有符合筛选条件的观众反馈。",
        "topics": [], "otherTopics": [],
        "method": {"raw": 0, "topics": 0, "unclassified": 0, "eligible": 0,
                   "representatives": 0, "raw_comments": 0, "raw_danmaku": 0,
                   "method": "fixture-v1", "route": "DIRECT_LLM"},
        "videoSummary": None,
        "scope": {"video_id": "1001", "start": WINDOW_START, "end": WINDOW_END,
                  "timezone": "Asia/Shanghai", "sources": ["COMMENT", "DANMAKU"],
                  "grounding": "FEEDBACK_ONLY"},
        "dataAsOf": "2026-11-12T00:00:00Z",
        "videoCoverage": None, "unclassifiedExamples": [], "daily": {},
        "playback": None, "videoDetails": None,
    }
    doc.update(over)
    return json.dumps(doc, ensure_ascii=False).encode("utf-8")


def product_scope(**over):
    scope = {"video_id": "1001", "start": WINDOW_START, "end": WINDOW_END,
             "timezone": "Asia/Shanghai", "sources": ["COMMENT", "DANMAKU"],
             "grounding": "FEEDBACK_ONLY"}
    scope.update(over)
    return scope


def product_html_bytes():
    return ("<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
            "<title>观众反馈报告</title></head><body><main>"
            "<p>本次范围内没有符合筛选条件的观众反馈。</p></main></body></html>").encode("utf-8")


def make_case(case_id="p1", bundle_id="b1", **over):
    case = {
        "case_id": case_id, "case_version": "0.1.0",
        "scenario_id": "product_capture", "report_scope": "FEEDBACK_ONLY",
        "source_group": "demo_fixture_group", "material_class": "synthetic_fixture",
        "prompt": PROMPT,
        "material": {"kind": "synthetic_inline", "content": "demo fixture 1001",
                     "content_sha256": sha("demo fixture 1001".encode("utf-8"))},
        "timezone": "Asia/Shanghai",
        "task_clock": {"reference_time": "2026-11-12 10:00:00", "timezone": "Asia/Shanghai"},
        "task_window": {"kind": "absolute", "start": "2026-11-05", "end": "2026-11-12"},
        "interaction_script": {"clarification_replies": []},
        "roles": {"controller": "demo-scripted", "feedback_analysis": "demo-scripted",
                  "vision": "not_enabled", "transcription": "not_enabled",
                  "protocol": "product_compressed"},
        "tool_permissions": {"read_dataset": False, "network": False, "ssh": False},
        "cache_replay_policy": {"cache": "disabled", "replay": "not_implemented"},
        "scoring_rules_version": "draft-v0",
        "expected": {"facts": [], "evidence_anchors": [], "timestamp_tolerance_ms": None,
                     "prohibited_claims": ["真实平台用户行为", "真实模型输出质量"]},
        "review": {"reviewer_ref": None, "reviewed_at": None, "status": "pending"},
        "product_capture": {
            "bundle_id": bundle_id,
            "evidence_level": "product_http_mockmvc",
            "adapter_version": "product-adapter-v1",
            "request": {"prompt": PROMPT, "owner_token": "demo-alice", "video_id": "1001",
                        "sources": ["COMMENT", "DANMAKU"], "grounding": "FEEDBACK_ONLY"},
        },
    }
    case.update(over)
    return case


def real_case(bundle_id, case_id=None, **over):
    """A case bound to a real capture; the needs-input capture used a different prompt."""
    prompt = OUT_OF_RANGE_PROMPT if bundle_id == "product-needs-input" else PROMPT
    case = make_case(case_id or bundle_id, bundle_id, prompt=prompt, **over)
    case["product_capture"]["request"]["prompt"] = prompt
    if bundle_id == "product-needs-input":
        case["task_clock"] = {"reference_time": "2026-10-20 10:00:00", "timezone": "Asia/Shanghai"}
        case["task_window"] = {"kind": "absolute", "start": "2026-10-10", "end": "2026-10-20"}
    return case


def access_checks():
    return [
        {"name": "unauthenticated", "http_status": 401, "authorization": None},
        {"name": "cross_account", "http_status": 403, "authorization": "Bearer demo-bob"},
        {"name": "unknown_artifact", "http_status": 404, "authorization": "Bearer demo-alice"},
        {"name": "revoked_authorization", "http_status": 403, "authorization": "Bearer demo-alice"},
    ]


_DELETE = object()


def coordinator_receipt(json_bytes, run):
    """Mirror FeedbackOperationCoordinator.deliver() for generate_feedback_report.

    The receipt is derived from the report bytes this factory actually writes, so a counterexample
    that overrides the report keeps a self-consistent receipt and stays isolated to its own defect.
    An unreadable report falls back to the default shape: the report defect is the subject there.
    """
    try:
        report = json.loads(json_bytes)
    except (ValueError, UnicodeDecodeError):
        report = None
    if not isinstance(report, dict):
        report = json.loads(product_json_bytes())
    topics = report.get("topics")
    titles = [t.get("title") for t in topics if isinstance(t, dict)] if isinstance(topics, list) else []
    return {"status": "READY", "evidence_id": run["evidence_id"], "artifact_id": run["artifact_id"],
            "url": run["html_url"], "json_url": run["json_url"], "summary": report.get("summary"),
            "topics": titles, "method": report.get("method"), "demo": True}


def make_bundle(root, bundle_id="b1", json_bytes=None, html_bytes=None, trace_bytes=None,
                manifest_over=None, drop=()):
    """Write a MUTATION bundle. Real product evidence only comes from the Java capture test."""
    root = Path(root)
    (root / "responses").mkdir(parents=True, exist_ok=True)
    jb = product_json_bytes() if json_bytes is None else json_bytes
    hb = product_html_bytes() if html_bytes is None else html_bytes
    responses = {}
    if "json" not in drop:
        (root / "responses/report.json").write_bytes(jb)
        responses["json"] = {"path": "responses/report.json", "http_status": 200,
                             "content_type": "application/json", "sha256": sha(jb), "bytes": len(jb)}
    else:
        responses["json"] = None
    if "html" not in drop:
        (root / "responses/report.html").write_bytes(hb)
        responses["html"] = {"path": "responses/report.html", "http_status": 200,
                             "content_type": "text/html;charset=UTF-8", "sha256": sha(hb), "bytes": len(hb)}
    else:
        responses["html"] = None
    trace = None
    if trace_bytes is not None:
        (root / "traces").mkdir(parents=True, exist_ok=True)
        (root / "traces/spans.jsonl").write_bytes(trace_bytes)
        trace = {"path": "traces/spans.jsonl", "sha256": sha(trace_bytes), "bytes": len(trace_bytes),
                 "span_count": len([l for l in trace_bytes.decode("utf-8").splitlines() if l.strip()]),
                 "run_id": "11111111-2222-3333-4444-555555555555", "source": "harness_jsonl_archive"}
    run = {"run_id": "11111111-2222-3333-4444-555555555555", "final_status": "SUCCEEDED",
           "artifact_id": "rp_fixture", "evidence_id": "ev_fixture",
           "receipt_tool": "generate_feedback_report",
           "json_url": "/api/agent/runs/11111111-2222-3333-4444-555555555555/reports/rp_fixture/json",
           "html_url": "/api/agent/runs/11111111-2222-3333-4444-555555555555/reports/rp_fixture"}
    run["receipt"] = coordinator_receipt(jb, run)
    manifest = {
        "schema_version": 1, "bundle_id": bundle_id, "adapter_contract": "product-bundle-v1",
        "evidence_level": "product_http_mockmvc", "material_class": "synthetic_fixture",
        "mutation_fixture": True,
        "producer": {"kind": "product_http_mockmvc", "test_class": MUTATION_MARKER,
                     "test_method": MUTATION_MARKER, "maven_command": "none",
                     "captured_at": "2026-11-14T00:00:00Z", "spring_profile": "demo"},
        "request": {"prompt": PROMPT, "owner_token": "demo-alice", "video_id": "1001",
                    "sources": ["COMMENT", "DANMAKU"], "grounding": "FEEDBACK_ONLY"},
        "run": run,
        "responses": responses, "access_checks": access_checks(), "trace": trace,
        "notes": [MUTATION_MARKER],
    }
    if manifest_over:
        for key, value in manifest_over.items():
            if value is _DELETE:
                manifest.pop(key, None)
            else:
                manifest[key] = value
    (root / "bundle.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    return root


def mutation_trace_bytes():
    span = {"traceId": "t" * 32, "spanId": "s" * 16, "name": "agent.run",
            "startTimeUnixNano": 1, "endTimeUnixNano": 2,
            "attributes": {"run.id": "11111111-2222-3333-4444-555555555555", "run.status": "SUCCEEDED"}}
    return (json.dumps(span) + "\n").encode("utf-8")


def cli(*args):
    proc = subprocess.run([PYTHON, str(SCRIPTS / "report_eval.py"), *args],
                          capture_output=True, cwd=str(TREE),
                          env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "PYTHONIOENCODING": "utf-8"})
    return proc.returncode, proc.stdout.decode("utf-8", "replace"), proc.stderr.decode("utf-8", "replace")


def write_cases(path, case_list):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"kind": "report-eval-cases", "cases": case_list}, ensure_ascii=False),
                    encoding="utf-8")
    return path


class TempDirTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="4a-bridge-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)


class CliSurfaceTest(TempDirTest):
    def test_import_product_subcommand_exists(self):
        code, out, err = cli("import-product", "--help")
        self.assertEqual(code, 0, f"import-product missing: {out}{err}")
        self.assertIn("--bundle", out)

    def test_offline_run_subcommand_unchanged(self):
        code, out, err = cli("run", "--help")
        self.assertEqual(code, 0, err)
        self.assertIn("--mode", out)


class ProductDeliveryGapTest(TempDirTest):
    """The offline delivery contract cannot judge product originals, in either direction."""

    def _product_trial(self, case):
        fp = cases.case_fingerprint(case)
        trial = ledger.create_trial(self.tmp, case, fp, cases.experiment_id(fp))
        trial.write_artifact("artifacts/product/report.json", product_json_bytes())
        trial.write_artifact("artifacts/product/report.html", product_html_bytes())
        trial.append_attempt({"kind": "request", "outcome": "SUCCEEDED", "report_id": "rp_fixture"})
        trial.finish({"final_status": "SUCCEEDED", "report_id": "rp_fixture",
                      "first_attempt_outcome": "SUCCEEDED", "recovered": False,
                      "trace_state": "MISSING"})
        return trial

    def test_product_originals_without_a_capture_manifest_are_not_a_delivery(self):
        """Bytes alone cannot prove terminal status, authorization or scope; the manifest is required."""
        case = make_case()
        trial = self._product_trial(case)
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertTrue(any(r.startswith("product_capture_invalid") for r in result["reasons"]),
                        result["reasons"])

    def test_product_json_is_not_the_offline_report_format(self):
        """Characterisation: the offline contract genuinely differs, so a branch is required."""
        case = make_case()
        case.pop("product_capture")
        trial = self._product_trial(case)
        trial.write_artifact("artifacts/report.json", product_json_bytes())
        trial.write_artifact("artifacts/report.md", b"# derived\n")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertIn("report_id_mismatch", result["reasons"])


class FingerprintIsolationTest(TempDirTest):
    def test_capture_binding_changes_fingerprint(self):
        from report_evaluation import product_bundle
        case = make_case()
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b"))
        plain = cases.case_fingerprint(case)
        bound = cases.case_fingerprint(case, capture=product_bundle.fingerprint_payload(bundle))
        self.assertNotEqual(plain, bound)

    def test_offline_fingerprint_unchanged_without_capture(self):
        case = make_case()
        case.pop("product_capture")
        case["offline_simulation"] = {"scenario": "success"}
        self.assertEqual(cases.case_fingerprint(case), cases.case_fingerprint(case))

    def test_different_captures_of_one_case_are_separate_experiments(self):
        from report_evaluation import product_bundle
        case = make_case()
        first = product_bundle.load_bundle(make_bundle(self.tmp / "b1"))
        second = product_bundle.load_bundle(make_bundle(
            self.tmp / "b2", html_bytes=product_html_bytes() + b"\n<!-- second run -->"))
        fp1 = cases.case_fingerprint(case, capture=product_bundle.fingerprint_payload(first))
        fp2 = cases.case_fingerprint(case, capture=product_bundle.fingerprint_payload(second))
        self.assertNotEqual(fp1, fp2)


class PathGuardTest(TempDirTest):
    def test_reparse_point_is_refused_even_inside_root(self):
        from report_evaluation.util import ensure_real_path
        root = self.tmp / "root"
        inner = root / "inner"
        inner.mkdir(parents=True)
        target = root / "target"
        target.mkdir()
        link = inner / "jump"
        import _winapi
        _winapi.CreateJunction(str(target), str(link))
        with self.assertRaises(OutOfBoundsError):
            ensure_real_path(root, link / "x.json")

    def test_dotdot_and_absolute_are_refused(self):
        from report_evaluation.util import ensure_real_path
        root = self.tmp / "root"
        root.mkdir()
        for rel in ("../escape.json", "/etc/passwd", "a/../../escape.json"):
            with self.assertRaises(OutOfBoundsError):
                ensure_real_path(root, rel)

    def test_bundle_response_path_escape_is_refused(self):
        from report_evaluation import product_bundle
        outside = self.tmp / "outside.json"
        outside.write_bytes(product_json_bytes())
        for rel in ("../outside.json", str(outside)):
            bundle = make_bundle(self.tmp / ("b" + sha(rel.encode())[:8]))
            manifest = json.loads((bundle / "bundle.json").read_text(encoding="utf-8"))
            manifest["responses"]["json"]["path"] = rel
            (bundle / "bundle.json").write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(product_bundle.BundleError) as ctx:
                product_bundle.load_bundle(bundle)
            self.assertIn("unsafe_response_path", str(ctx.exception))

    def test_bundle_response_junction_is_refused(self):
        from report_evaluation import product_bundle
        import _winapi
        bundle = make_bundle(self.tmp / "b")
        target = self.tmp / "elsewhere"
        target.mkdir()
        (target / "report.json").write_bytes(product_json_bytes())
        link = bundle / "responses" / "jump"
        _winapi.CreateJunction(str(target), str(link))
        manifest = json.loads((bundle / "bundle.json").read_text(encoding="utf-8"))
        manifest["responses"]["json"]["path"] = "responses/jump/report.json"
        (bundle / "bundle.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(product_bundle.BundleError) as ctx:
            product_bundle.load_bundle(bundle)
        self.assertIn("unsafe_response_path", str(ctx.exception))


class BundleValidationTest(TempDirTest):
    def _load(self, **kw):
        from report_evaluation import product_bundle
        return product_bundle.load_bundle(make_bundle(self.tmp / "b", **kw))

    def _expect_refused(self, marker, **kw):
        from report_evaluation import product_bundle
        with self.assertRaises(product_bundle.BundleError) as ctx:
            self._load(**kw)
        self.assertIn(marker, str(ctx.exception))

    def test_unknown_bundle_schema_is_refused(self):
        self._expect_refused("unknown_bundle_schema", manifest_over={"schema_version": 99})

    def test_bool_schema_version_is_refused(self):
        self._expect_refused("unknown_bundle_schema", manifest_over={"schema_version": True})

    def test_bool_http_status_is_refused(self):
        from report_evaluation import product_bundle
        bundle = make_bundle(self.tmp / "b")
        manifest = json.loads((bundle / "bundle.json").read_text(encoding="utf-8"))
        manifest["responses"]["json"]["http_status"] = True
        (bundle / "bundle.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(product_bundle.BundleError):
            product_bundle.load_bundle(bundle)

    def test_stale_hash_is_refused(self):
        from report_evaluation import product_bundle
        bundle = make_bundle(self.tmp / "b")
        (bundle / "responses/report.json").write_bytes(product_json_bytes(summary="tampered"))
        with self.assertRaises(product_bundle.BundleError) as ctx:
            product_bundle.load_bundle(bundle)
        self.assertIn("response_hash_mismatch", str(ctx.exception))

    def test_byte_count_mismatch_is_refused(self):
        from report_evaluation import product_bundle
        bundle = make_bundle(self.tmp / "b")
        manifest = json.loads((bundle / "bundle.json").read_text(encoding="utf-8"))
        manifest["responses"]["json"]["bytes"] += 1
        (bundle / "bundle.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(product_bundle.BundleError) as ctx:
            product_bundle.load_bundle(bundle)
        self.assertIn("response_bytes_mismatch", str(ctx.exception))

    def test_unknown_product_schema_version_is_refused(self):
        self._expect_refused("unknown_product_schema",
                             json_bytes=product_json_bytes(schemaVersion="feedback-report-json-v2"))

    def test_unparsable_product_json_is_refused(self):
        self._expect_refused("report_json_unreadable", json_bytes=b"{not json")

    def test_non_object_product_json_is_refused(self):
        self._expect_refused("report_not_object", json_bytes=b"[]")

    def test_negative_and_bool_counts_are_refused(self):
        self._expect_refused("method_bad_raw", json_bytes=product_json_bytes(
            method={"raw": -1, "topics": 0, "unclassified": 0, "eligible": 0, "representatives": 0}))
        self._expect_refused("method_bad_eligible", json_bytes=product_json_bytes(
            method={"raw": 0, "topics": 0, "unclassified": 0, "eligible": True, "representatives": 0}))

    def test_topic_count_relation_is_enforced(self):
        self._expect_refused("method_topics_disagrees_with_cards", json_bytes=product_json_bytes(
            method={"raw": 0, "topics": 3, "unclassified": 0, "eligible": 0, "representatives": 0}))

    def test_duplicate_sources_are_refused(self):
        self._expect_refused("duplicate_scope_source",
                             json_bytes=product_json_bytes(scope=product_scope(
                                 sources=["COMMENT", "COMMENT"])))

    def test_non_product_html_is_refused(self):
        self._expect_refused("report_html_not_product_html", html_bytes=b"# markdown, not product HTML")

    def test_access_check_returning_200_is_refused(self):
        checks = access_checks()
        checks[0]["http_status"] = 200
        self._expect_refused("access_check_not_denied", manifest_over={"access_checks": checks})

    def test_missing_access_check_is_refused(self):
        self._expect_refused("missing_access_check", manifest_over={"access_checks": access_checks()[:2]})

    def test_unknown_evidence_level_is_refused(self):
        self._expect_refused("unknown_evidence_level", manifest_over={"evidence_level": "real_service"})

    def test_unknown_terminal_status_is_refused(self):
        from report_evaluation import product_bundle
        bundle = make_bundle(self.tmp / "b")
        manifest = json.loads((bundle / "bundle.json").read_text(encoding="utf-8"))
        manifest["run"]["final_status"] = "MAYBE"
        (bundle / "bundle.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(product_bundle.BundleError) as ctx:
            product_bundle.load_bundle(bundle)
        self.assertIn("unknown_terminal_status", str(ctx.exception))

    def test_artifact_url_mismatch_is_refused(self):
        from report_evaluation import product_bundle
        bundle = make_bundle(self.tmp / "b")
        manifest = json.loads((bundle / "bundle.json").read_text(encoding="utf-8"))
        manifest["run"]["json_url"] = "/api/agent/runs/x/reports/someone_else/json"
        (bundle / "bundle.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(product_bundle.BundleError) as ctx:
            product_bundle.load_bundle(bundle)
        self.assertIn("artifact_url_mismatch", str(ctx.exception))

    def test_trace_run_mismatch_is_refused(self):
        from report_evaluation import product_bundle
        bundle = make_bundle(self.tmp / "b", trace_bytes=mutation_trace_bytes())
        manifest = json.loads((bundle / "bundle.json").read_text(encoding="utf-8"))
        manifest["trace"]["run_id"] = "99999999-2222-3333-4444-555555555555"
        (bundle / "bundle.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(product_bundle.BundleError) as ctx:
            product_bundle.load_bundle(bundle)
        self.assertIn("trace_run_mismatch", str(ctx.exception))

    def test_wrong_video_scope_fails_case_binding(self):
        from report_evaluation import product_bundle
        bundle = self._load(json_bytes=product_json_bytes(scope=product_scope(video_id="1002")))
        self.assertIn("product_video_out_of_scope", product_bundle.bind_case(bundle, make_case()))

    def test_wrong_window_fails_case_binding(self):
        from report_evaluation import product_bundle
        bundle = self._load(json_bytes=product_json_bytes(
            scope=product_scope(start="2026-11-06T00:00")))
        self.assertIn("product_window_out_of_scope", product_bundle.bind_case(bundle, make_case()))

    def test_wrong_sources_fail_case_binding(self):
        from report_evaluation import product_bundle
        bundle = self._load(json_bytes=product_json_bytes(scope=product_scope(sources=["COMMENT"])))
        self.assertIn("product_sources_out_of_scope", product_bundle.bind_case(bundle, make_case()))

    def test_wrong_grounding_fails_case_binding(self):
        from report_evaluation import product_bundle
        bundle = self._load(json_bytes=product_json_bytes(scope=product_scope(grounding="VIDEO_GROUNDED")))
        self.assertIn("product_grounding_out_of_scope", product_bundle.bind_case(bundle, make_case()))

    def test_empty_result_is_legal(self):
        from report_evaluation import product_bundle
        bundle = self._load()
        self.assertEqual(product_bundle.bind_case(bundle, make_case()), [])

    def test_missing_response_record_is_a_delivery_problem_not_corruption(self):
        from report_evaluation import product_bundle
        bundle = self._load(drop=("json",))
        self.assertIn("missing_response:json", bundle.delivery_problems)
        self.assertIsNone(bundle.json_bytes)


class BridgeImportTest(TempDirTest):
    def test_import_creates_trial_and_preserves_original_bytes(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b"))
        case = make_case()
        trial = product_bridge.import_bundle(self.tmp / "out", case, bundle)
        stored = (trial.dir / "artifacts/product/responses/report.json").read_bytes()
        self.assertEqual(stored, bundle.json_bytes)
        self.assertEqual(sha(stored), bundle.manifest["responses"]["json"]["sha256"])
        self.assertEqual((trial.dir / "artifacts/product/bundle.json").read_bytes(), bundle.manifest_bytes)

    def test_repeat_import_keeps_old_trial_bytes(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b"))
        case = make_case()
        out = self.tmp / "out"
        t1 = product_bridge.import_bundle(out, case, bundle)
        first = (t1.dir / "artifacts/product/responses/report.html").read_bytes()
        t2 = product_bridge.import_bundle(out, case, bundle)
        self.assertNotEqual(t1.trial_id, t2.trial_id)
        self.assertEqual((t1.dir / "artifacts/product/responses/report.html").read_bytes(), first)

    def test_derived_markdown_is_marked_and_not_used_for_delivery(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b"))
        trial = product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)
        md = (trial.dir / "artifacts/report.md").read_text(encoding="utf-8")
        self.assertIn("adapter_derived_view", md)
        (trial.dir / "artifacts/report.md").write_text("garbage", encoding="utf-8")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(result["passed"], result["reasons"])

    def test_missing_report_file_fails_delivery_without_corrupting_the_trial(self):
        from report_evaluation import product_bridge, product_bundle
        source = make_bundle(self.tmp / "b")
        (source / "responses/report.json").unlink()
        bundle = product_bundle.load_bundle(source)
        trial = product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertIn("missing_artifact:report.json", result["reasons"])
        row = summary.summarize_run(self.tmp / "out")["rows"][0]
        self.assertEqual(row["classification"], "success_delivery_failed")

    def test_wrong_scope_fails_delivery(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(
            self.tmp / "b", json_bytes=product_json_bytes(scope=product_scope(video_id="1002"))))
        trial = product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertIn("product_video_out_of_scope", result["reasons"])

    def test_tampering_after_import_fails_delivery(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b"))
        trial = product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)
        target = trial.dir / "artifacts/product/responses/report.json"
        target.write_bytes(product_json_bytes(summary="tampered after import"))
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertTrue(any("response_hash_mismatch" in r for r in result["reasons"]), result["reasons"])

    def test_missing_trace_keeps_delivery_and_unknown_usage(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b"))
        product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)
        row = summary.summarize_run(self.tmp / "out")["rows"][0]
        self.assertTrue(row["delivery_passed"], row["delivery_reasons"])
        self.assertEqual(row["classification"], "success_delivered")
        self.assertEqual(row["trace_state"], "MISSING")
        for field in ("input_tokens", "output_tokens", "cost_low", "cost_high"):
            self.assertIsNone(row[field], field)

    def test_corrupt_trace_keeps_delivery_and_marks_trace_corrupt(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b", trace_bytes=b"{broken\n"))
        product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)
        row = summary.summarize_run(self.tmp / "out")["rows"][0]
        self.assertTrue(row["delivery_passed"], row["delivery_reasons"])
        self.assertEqual(row["trace_state"], "CORRUPT")
        self.assertIsNone(row["input_tokens"])

    def test_present_trace_without_model_calls_reports_unknown_usage(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b", trace_bytes=mutation_trace_bytes()))
        product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)
        row = summary.summarize_run(self.tmp / "out")["rows"][0]
        self.assertTrue(row["delivery_passed"], row["delivery_reasons"])
        self.assertEqual(row["trace_state"], "PRESENT_NO_REQUEST_SPANS")
        self.assertIsNone(row["input_tokens"])
        self.assertIsNone(row["cost_low"])

    def test_interrupted_import_is_not_a_delivery(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b"))
        case = make_case()
        fp = cases.case_fingerprint(case, capture=product_bundle.fingerprint_payload(bundle))
        trial = ledger.create_trial(self.tmp / "out", case, fp, cases.experiment_id(fp))
        trial.simulate_interrupt()
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertEqual(result["reasons"], ["incomplete_trial"])
        row = summary.summarize_run(self.tmp / "out")["rows"][0]
        self.assertEqual(row["classification"], "interrupted")

    def test_rejected_capture_keeps_evidence_and_asserts_no_terminal_status(self):
        from report_evaluation import product_bridge
        source = make_bundle(self.tmp / "b", json_bytes=b"{not json")
        trial, verified = product_bridge.import_capture(self.tmp / "out", make_case(), source)
        self.assertFalse(verified)
        self.assertFalse((trial.dir / "trial_summary.json").exists())
        self.assertTrue((trial.dir / "artifacts/product/bundle.json").is_file())
        self.assertTrue((trial.dir / "artifacts/product/rejected.json").is_file())
        result = summary.summarize_run(self.tmp / "out")
        row = result["rows"][0]
        self.assertEqual(row["classification"], "corrupted")
        self.assertIn("unverified_product_capture", row["diagnostics"])
        self.assertIsNone(row["final_status"])
        self.assertEqual(result["classifications"].get("success_delivered", 0), 0)

    def test_mixed_batch_still_summarizes_every_trial(self):
        from report_evaluation import product_bridge, product_bundle
        out = self.tmp / "out"
        good = product_bundle.load_bundle(make_bundle(self.tmp / "good", bundle_id="b1"))
        product_bridge.import_bundle(out, make_case("ok", "b1"), good)
        product_bridge.import_capture(out, make_case("bad", "b2"),
                                      make_bundle(self.tmp / "bad", bundle_id="b2", json_bytes=b"{"))
        result = summary.summarize_run(out)
        self.assertEqual(result["trial_count"], 2)
        self.assertEqual(result["classifications"].get("success_delivered"), 1)
        self.assertEqual(result["classifications"].get("corrupted"), 1)
        self.assertEqual(result["corrupted_trial_count"], 1)


class CaseValidationTest(TempDirTest):
    def test_product_case_requires_capture_and_forbids_offline_script(self):
        case = make_case()
        self.assertEqual(cases.validate_case(case, "product"), [])
        case.pop("product_capture")
        self.assertTrue(any("product_capture" in e for e in cases.validate_case(case, "product")))

    def test_product_case_cannot_claim_formal_material(self):
        case = make_case(material_class="time_adapted_real_content")
        self.assertTrue(any("synthetic_fixture" in e for e in cases.validate_case(case, "product")))

    def test_product_case_prompt_must_match_the_capture_request(self):
        case = make_case()
        case["product_capture"]["request"]["prompt"] = "something else"
        self.assertTrue(any("prompt" in e for e in cases.validate_case(case, "product")))

    def test_product_case_forbids_network(self):
        case = make_case(tool_permissions={"network": True})
        self.assertTrue(any("network" in e for e in cases.validate_case(case, "product")))

    def test_offline_case_still_requires_offline_simulation(self):
        case = make_case()
        case.pop("product_capture")
        self.assertTrue(any("offline_simulation" in e for e in cases.validate_case(case, "offline")))


@unittest.skipUnless(REAL_CAPTURE_ROOT.is_dir(),
                     "real product captures absent; rebuild with: " + REBUILD_CAPTURES)
class RealProductCaptureTest(TempDirTest):
    """Only these tests prove product integration; every byte comes from the Java capture test."""

    def _import(self, bundle_id, out=None):
        from report_evaluation import product_bridge, product_bundle
        source = REAL_CAPTURE_ROOT / bundle_id
        bundle = product_bundle.load_bundle(source)
        trial = product_bridge.import_bundle(out or self.tmp / "out", real_case(bundle_id), bundle)
        return source, bundle, trial

    def test_success_capture_is_delivered(self):
        _source, bundle, trial = self._import("product-success")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(result["passed"], result["reasons"])
        self.assertEqual(bundle.final_status, "SUCCEEDED")
        self.assertEqual(len(bundle.report["topics"]) + len(bundle.report["otherTopics"]),
                         bundle.report["method"]["topics"])

    def test_empty_capture_is_delivered_without_inventing_topics(self):
        _source, bundle, trial = self._import("product-empty")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(result["passed"], result["reasons"])
        self.assertEqual(bundle.report["topics"], [])
        self.assertEqual(bundle.report["otherTopics"], [])
        self.assertEqual(bundle.report["method"]["raw"], 0)

    def test_needs_input_capture_is_not_a_delivery(self):
        _source, bundle, trial = self._import("product-needs-input")
        self.assertEqual(bundle.final_status, "NEEDS_INPUT")
        self.assertIsNone(bundle.json_bytes)
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertEqual(result["reasons"], ["status_NEEDS_INPUT"])
        self.assertFalse((trial.dir / "artifacts/product/responses").exists())

    def test_preserved_originals_are_byte_identical_to_the_capture(self):
        source, bundle, trial = self._import("product-success")
        for rel, data in (("responses/report.json", bundle.json_bytes),
                          ("responses/report.html", bundle.html_bytes)):
            self.assertEqual((trial.dir / "artifacts/product" / rel).read_bytes(),
                             (source / rel).read_bytes())
            self.assertEqual(data, (source / rel).read_bytes())
        self.assertEqual((trial.dir / "artifacts/product/bundle.json").read_bytes(),
                         (source / "bundle.json").read_bytes())
        self.assertEqual((trial.dir / "traces/trace.jsonl").read_bytes(),
                         (source / "traces/spans.jsonl").read_bytes())

    def test_real_trace_is_present_but_usage_stays_unknown(self):
        self._import("product-success")
        row = summary.summarize_run(self.tmp / "out")["rows"][0]
        self.assertEqual(row["trace_state"], "PRESENT_NO_REQUEST_SPANS")
        for field in ("input_tokens", "output_tokens", "cost_low", "cost_high", "usage_complete"):
            self.assertIsNone(row[field], field)
        self.assertEqual(row["evidence_level"], "product_http_mockmvc")
        self.assertEqual(row["semantic_quality"], "NOT_ASSESSED")
        self.assertFalse(row["semantic_eligible"])

    def test_real_capture_spans_contain_no_dispatched_model_call(self):
        _source, bundle, _trial = self._import("product-success")
        spans = [json.loads(l) for l in bundle.trace_bytes.decode("utf-8").splitlines() if l.strip()]
        self.assertTrue(spans)
        self.assertTrue(any(s["name"] == "agent.run" for s in spans))
        self.assertFalse(any(s["name"] == "model.request"
                             and s.get("attributes", {}).get("model.dispatched") is True for s in spans))

    def test_real_access_checks_are_all_denied(self):
        _source, bundle, _trial = self._import("product-success")
        checks = bundle.manifest["access_checks"]
        self.assertEqual({c["name"] for c in checks},
                         {"unauthenticated", "cross_account", "unknown_artifact", "revoked_authorization"})
        for check in checks:
            self.assertNotEqual(check["http_status"], 200, check)

    def test_repeat_import_of_real_capture_keeps_old_bytes(self):
        from report_evaluation import product_bridge, product_bundle
        out = self.tmp / "out"
        bundle = product_bundle.load_bundle(REAL_CAPTURE_ROOT / "product-success")
        case = real_case("product-success")
        t1 = product_bridge.import_bundle(out, case, bundle)
        first = (t1.dir / "artifacts/product/responses/report.html").read_bytes()
        digest = sha(first)
        t2 = product_bridge.import_bundle(out, case, bundle)
        self.assertNotEqual(t1.trial_id, t2.trial_id)
        self.assertEqual(sha((t1.dir / "artifacts/product/responses/report.html").read_bytes()), digest)
        result = summary.summarize_run(out)
        self.assertEqual(result["trial_count"], 2)
        self.assertEqual(result["classifications"].get("success_delivered"), 2)


@unittest.skipUnless(REAL_CAPTURE_ROOT.is_dir(),
                     "real product captures absent; rebuild with: " + REBUILD_CAPTURES)
class ProductCliTest(TempDirTest):
    def _run(self, case_list, bundle_ids, out=None):
        cases_path = write_cases(self.tmp / "cases.json", case_list)
        out = out or self.tmp / "out"
        args = ["import-product", "--cases", str(cases_path), "--output", str(out)]
        for bundle_id in bundle_ids:
            args += ["--bundle", str(REAL_CAPTURE_ROOT / bundle_id)]
        return cli(*args), out

    def test_clean_batch_exits_zero(self):
        (code, out, err), root = self._run(
            [real_case("product-success"), real_case("product-empty"), real_case("product-needs-input")],
            ["product-success", "product-empty", "product-needs-input"])
        self.assertEqual(code, 0, f"{out}{err}")
        payload = json.loads(out)
        self.assertEqual(payload["trials_created"], 3)
        self.assertEqual(payload["evidence_level"], "product_http_mockmvc")
        self.assertEqual(payload["classifications"], {"success_delivered": 2, "needs_input": 1})
        result = json.loads((root / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(result["corrupted_trial_count"], 0)
        self.assertEqual({r["evidence_level"] for r in result["rows"]}, {"product_http_mockmvc"})

    def test_mixed_batch_exits_three_and_still_summarizes(self):
        broken = make_bundle(self.tmp / "broken", bundle_id="product-broken", json_bytes=b"{")
        cases_path = write_cases(self.tmp / "cases.json",
                                 [real_case("product-success"), real_case("product-broken")])
        out = self.tmp / "out"
        code, stdout, stderr = cli("import-product", "--cases", str(cases_path), "--output", str(out),
                                   "--bundle", str(REAL_CAPTURE_ROOT / "product-success"),
                                   "--bundle", str(broken))
        self.assertEqual(code, 3, f"{stdout}{stderr}")
        payload = json.loads(stdout)
        self.assertEqual(payload["unverified_captures"], 1)
        result = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(result["trial_count"], 2)
        self.assertEqual(result["classifications"].get("success_delivered"), 1)
        self.assertEqual(result["classifications"].get("corrupted"), 1)
        self.assertTrue((out / "summary.csv").is_file())

    def test_case_without_a_bundle_exits_two(self):
        (code, out, err), _root = self._run([real_case("product-success"), real_case("product-empty")],
                                            ["product-success"])
        self.assertEqual(code, 2, f"{out}{err}")
        self.assertIn("product-empty", json.loads(out)["missing_bundles"])

    def test_invalid_case_exits_two(self):
        bad = real_case("product-success")
        bad["product_capture"]["evidence_level"] = "real_service"
        (code, out, err), _root = self._run([bad], ["product-success"])
        self.assertEqual(code, 2, f"{out}{err}")
        self.assertEqual(json.loads(out)["error"], "invalid cases")

    def test_repeat_cli_import_adds_trials_and_keeps_old_files(self):
        case_list = [real_case("product-success")]
        (_c1, _o1, _e1), root = self._run(case_list, ["product-success"])
        before = {p: sha(p.read_bytes()) for p in (root / "trials").rglob("*") if p.is_file()}
        (code2, out2, err2), _ = self._run(case_list, ["product-success"], out=root)
        self.assertEqual(code2, 0, f"{out2}{err2}")
        self.assertEqual(json.loads(out2)["trials_created"], 1)
        for path, digest in before.items():
            if path.name in ("summary.json", "summary.csv"):
                continue
            self.assertEqual(sha(path.read_bytes()), digest, str(path))
        result = json.loads((root / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(result["trial_count"], 2)

    def test_offline_and_product_captures_are_separate_batches(self):
        (_c, _o, _e), root = self._run([real_case("product-success")], ["product-success"])
        offline_case = make_case("offline-1")
        offline_case.pop("product_capture")
        offline_case["offline_simulation"] = {"scenario": "success"}
        offline_path = write_cases(self.tmp / "offline.json", [offline_case])
        code, out, err = cli("run", "--cases", str(offline_path), "--output", str(root))
        self.assertEqual(code, 0, f"{out}{err}")
        code, out, err = cli("summarize", "--input", str(root))
        self.assertEqual(code, 0, f"{out}{err}")
        result = json.loads((root / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(len(result["batches"]), 2)
        self.assertEqual({r["evidence_level"] for r in result["rows"]},
                         {"product_http_mockmvc", "offline_simulated"})
        self.assertEqual(result["batch_split_case_ids"], [])


class Repair01TypeClosureTest(TempDirTest):
    """R1: a wrong type must become a field diagnosis, never a TypeError that sinks the batch."""

    def _refused(self, expected, **kw):
        from report_evaluation import product_bundle
        bundle = make_bundle(self.tmp / "b", **kw)
        with self.assertRaises(product_bundle.BundleError) as ctx:
            product_bundle.load_bundle(bundle)
        self.assertIn(expected, str(ctx.exception))

    def test_object_source_element_is_a_field_problem(self):
        self._refused("unknown_request_source",
                      manifest_over={"request": {"prompt": PROMPT, "owner_token": "demo-alice",
                                                 "video_id": "1001", "sources": [{}],
                                                 "grounding": "FEEDBACK_ONLY"}})

    def test_object_scope_source_element_is_a_field_problem(self):
        self._refused("unknown_scope_source",
                      json_bytes=product_json_bytes(scope=product_scope(sources=[{}])))

    def test_object_final_status_is_a_field_problem(self):
        self._refused("unknown_terminal_status",
                      manifest_over={"run": {"run_id": "11111111-2222-3333-4444-555555555555",
                                             "final_status": {}}})

    def test_object_access_check_name_is_a_field_problem(self):
        checks = access_checks()
        checks[0]["name"] = {}
        self._refused("unknown_access_check", manifest_over={"access_checks": checks})

    def test_object_receipt_tool_is_a_field_problem(self):
        self._refused("bad_receipt_tool",
                      manifest_over={"run": {"run_id": "11111111-2222-3333-4444-555555555555",
                                             "final_status": "SUCCEEDED", "receipt_tool": {}}})

    def test_empty_method_object_is_refused(self):
        self._refused("method_bad_raw", json_bytes=product_json_bytes(method={}))

    def test_absent_required_method_key_is_refused(self):
        method = {"raw": 0, "topics": 0, "unclassified": 0, "eligible": 0, "representatives": 0,
                  "raw_comments": 0, "method": "fixture-v1", "route": "DIRECT_LLM"}
        self._refused("method_bad_raw_danmaku", json_bytes=product_json_bytes(method=method))

    def test_absent_method_route_is_refused(self):
        method = {"raw": 0, "topics": 0, "unclassified": 0, "eligible": 0, "representatives": 0,
                  "raw_comments": 0, "raw_danmaku": 0, "method": "fixture-v1"}
        self._refused("method_bad_route", json_bytes=product_json_bytes(method=method))

    def test_legal_empty_method_still_passes(self):
        from report_evaluation import product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b"))
        self.assertEqual(bundle.report["method"]["raw"], 0)
        self.assertEqual(bundle.report["topics"], [])


class Repair01ReceiptIdentityTest(TempDirTest):
    """R2: receipt/run/report identity is compared whole, never by substring."""

    def _manifest(self, **run_over):
        manifest = json.loads((make_bundle(self.tmp / "b") / "bundle.json").read_text(encoding="utf-8"))
        manifest["run"].update(run_over)
        (self.tmp / "b" / "bundle.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        return self.tmp / "b"

    def _refused(self, expected, **run_over):
        from report_evaluation import product_bundle
        with self.assertRaises(product_bundle.BundleError) as ctx:
            product_bundle.load_bundle(self._manifest(**run_over))
        self.assertIn(expected, str(ctx.exception))

    def test_empty_receipt_is_refused(self):
        self._refused("receipt_missing_artifact_id", receipt={})

    def test_receipt_not_ready_is_refused(self):
        self._refused("receipt_not_ready", receipt={"status": "PENDING", "artifact_id": "rp_fixture"})

    def test_receipt_artifact_mismatch_is_refused(self):
        self._refused("receipt_artifact_mismatch",
                      receipt={"status": "READY", "artifact_id": "rp_other"})

    def test_receipt_evidence_mismatch_is_refused(self):
        self._refused("receipt_evidence_id_mismatch",
                      receipt={"status": "READY", "artifact_id": "rp_fixture",
                               "evidence_id": "ev_other"})

    def test_receipt_url_mismatch_is_refused(self):
        self._refused("receipt_url_mismatch",
                      receipt={"status": "READY", "artifact_id": "rp_fixture",
                               "url": "/api/agent/runs/11111111-2222-3333-4444-555555555555/reports/rp_other"})

    def test_json_url_naming_another_run_is_refused(self):
        self._refused("artifact_url_mismatch:json_url",
                      json_url="/api/agent/runs/99999999-2222-3333-4444-555555555555/reports/rp_fixture/json")

    def test_html_url_with_extra_suffix_is_refused(self):
        self._refused("artifact_url_mismatch:html_url",
                      html_url="/api/agent/runs/11111111-2222-3333-4444-555555555555/reports/rp_fixture/extra")

    def test_receipt_summary_must_match_the_report(self):
        self._refused("receipt_summary_mismatch",
                      receipt={"status": "READY", "artifact_id": "rp_fixture", "summary": "别的结论"})

    def test_receipt_topics_must_match_report_titles(self):
        self._refused("receipt_topics_mismatch",
                      receipt={"status": "READY", "artifact_id": "rp_fixture", "topics": ["不存在的主题"]})

    def test_receipt_method_must_match_report_method(self):
        self._refused("receipt_method_mismatch",
                      receipt={"status": "READY", "artifact_id": "rp_fixture", "method": {"raw": 999}})

    def test_receipt_demo_must_be_boolean(self):
        self._refused("receipt_bad_demo",
                      receipt={"status": "READY", "artifact_id": "rp_fixture", "demo": "true"})


class Repair01ScopeOffsetTest(TempDirTest):
    """R5: the product writes offset-free local time; an offset is refused, never overwritten."""

    def _refused(self, expected, **scope_over):
        from report_evaluation import product_bundle
        bundle = make_bundle(self.tmp / "b", json_bytes=product_json_bytes(scope=product_scope(**scope_over)))
        with self.assertRaises(product_bundle.BundleError) as ctx:
            product_bundle.load_bundle(bundle)
        self.assertIn(expected, str(ctx.exception))

    def test_offset_scope_is_refused(self):
        self._refused("scope_offset_not_supported",
                      start="2026-11-05T00:00+00:00", end="2026-11-12T00:00+00:00")

    def test_mixed_aware_and_naive_scope_is_a_field_problem(self):
        self._refused("scope_offset_not_supported", start="2026-11-05T00:00+08:00")

    def test_verify_scope_refuses_an_offset_instead_of_replacing_it(self):
        from report_evaluation import product_bundle
        report = json.loads(product_json_bytes(scope=product_scope(
            start="2026-11-05T00:00+00:00", end="2026-11-12T00:00+00:00")).decode("utf-8"))
        problems = product_bundle.verify_scope(report, make_case())
        self.assertIn("product_window_offset_not_supported", problems)
        self.assertNotIn("product_window_out_of_scope", problems)

    def test_legal_local_window_still_binds(self):
        from report_evaluation import product_bundle
        report = json.loads(product_json_bytes().decode("utf-8"))
        self.assertEqual(product_bundle.verify_scope(report, make_case()), [])

    def test_non_increasing_window_still_refused(self):
        self._refused("scope_window_not_increasing", start="2026-11-12T00:00", end="2026-11-05T00:00")


class Repair01TraceAxisTest(TempDirTest):
    """R4: a Trace fault marks the Trace axis; it never erases a verified report delivery."""

    def _row(self, bundle):
        from report_evaluation import product_bridge
        product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)
        return summary.summarize_run(self.tmp / "out")["rows"][0]

    def _load_keeping_trace_fault(self, root):
        from report_evaluation import product_bundle
        try:
            return product_bundle.load_bundle(root)
        except product_bundle.TraceBundleError as e:
            return e.bundle

    def test_trace_hash_fault_keeps_delivery_and_unknown_usage(self):
        source = make_bundle(self.tmp / "b", trace_bytes=mutation_trace_bytes())
        manifest = json.loads((source / "bundle.json").read_text(encoding="utf-8"))
        manifest["trace"]["sha256"] = "0" * 64
        (source / "bundle.json").write_text(json.dumps(manifest), encoding="utf-8")
        row = self._row(self._load_keeping_trace_fault(source))
        self.assertTrue(row["delivery_passed"], row["delivery_reasons"])
        self.assertEqual(row["classification"], "success_delivered")
        self.assertEqual(row["trace_state"], "CORRUPT")
        self.assertIsNone(row["input_tokens"])
        self.assertIsNone(row["cost_low"])

    def test_declared_but_absent_trace_keeps_delivery(self):
        source = make_bundle(self.tmp / "b", trace_bytes=mutation_trace_bytes())
        (source / "traces/spans.jsonl").unlink()
        row = self._row(self._load_keeping_trace_fault(source))
        self.assertTrue(row["delivery_passed"], row["delivery_reasons"])
        self.assertEqual(row["trace_state"], "CORRUPT")
        self.assertIn("product_trace_unbound:trace_original_missing", row["diagnostics"])

    def test_trace_declaring_another_run_keeps_delivery(self):
        source = make_bundle(self.tmp / "b", trace_bytes=mutation_trace_bytes())
        manifest = json.loads((source / "bundle.json").read_text(encoding="utf-8"))
        manifest["trace"]["run_id"] = "99999999-2222-3333-4444-555555555555"
        (source / "bundle.json").write_text(json.dumps(manifest), encoding="utf-8")
        row = self._row(self._load_keeping_trace_fault(source))
        self.assertTrue(row["delivery_passed"], row["delivery_reasons"])
        self.assertEqual(row["trace_state"], "CORRUPT")
        self.assertIn("product_trace_unbound:trace_run_mismatch", row["diagnostics"])

    def test_foreign_span_run_id_marks_trace_corrupt(self):
        span = json.loads(mutation_trace_bytes().decode("utf-8"))
        span["attributes"]["run.id"] = "99999999-2222-3333-4444-555555555555"
        data = (json.dumps(span) + "\n").encode("utf-8")
        row = self._row(self._load_keeping_trace_fault(make_bundle(self.tmp / "b", trace_bytes=data)))
        self.assertTrue(row["delivery_passed"], row["delivery_reasons"])
        self.assertEqual(row["trace_state"], "CORRUPT")
        self.assertIn("product_trace_unbound:trace_span_foreign_run", row["diagnostics"])

    def test_replaced_billing_copy_never_enters_usage(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b", trace_bytes=mutation_trace_bytes()))
        trial = product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)
        forged = {"traceId": "t" * 32, "spanId": "f" * 16, "name": "model.request",
                  "startTimeUnixNano": 1, "endTimeUnixNano": 2,
                  "attributes": {"run.id": "99999999-2222-3333-4444-555555555555",
                                 "model.dispatched": True, "model.name": "qwen3-max",
                                 "model.input_tokens": 999, "model.output_tokens": 99}}
        (trial.dir / "traces/trace.jsonl").write_text(json.dumps(forged) + "\n", encoding="utf-8")
        row = summary.summarize_run(self.tmp / "out")["rows"][0]
        self.assertTrue(row["delivery_passed"], row["delivery_reasons"])
        self.assertEqual(row["trace_state"], "CORRUPT")
        self.assertIsNone(row["input_tokens"])
        self.assertIsNone(row["output_tokens"])
        self.assertIsNone(row["cost_low"])
        self.assertIsNone(row["usage_complete"])
        self.assertIn("product_trace_unbound:trace_copy_differs_from_original", row["diagnostics"])

    def test_root_sentinel_parent_is_not_a_trace_fault(self):
        span = json.loads(mutation_trace_bytes().decode("utf-8"))
        span["parentSpanId"] = "0" * 16
        data = (json.dumps(span) + "\n").encode("utf-8")
        row = self._row(self._load_keeping_trace_fault(make_bundle(self.tmp / "b", trace_bytes=data)))
        self.assertTrue(row["delivery_passed"], row["delivery_reasons"])
        self.assertEqual(row["trace_state"], "PRESENT_NO_REQUEST_SPANS")

    def test_absent_trace_declaration_stays_missing(self):
        from report_evaluation import product_bundle
        row = self._row(product_bundle.load_bundle(make_bundle(self.tmp / "b")))
        self.assertEqual(row["trace_state"], "MISSING")
        self.assertTrue(row["delivery_passed"], row["delivery_reasons"])


class Repair01StoredIdentityTest(TempDirTest):
    """R3: new bytes must not keep claiming the recorded fingerprint, on the report axis only."""

    def _import(self):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b", trace_bytes=mutation_trace_bytes()))
        return product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)

    def _resync(self, trial, rel, data):
        target = trial.dir / "artifacts/product" / rel
        target.write_bytes(data)
        manifest_path = trial.dir / "artifacts/product/bundle.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["responses"]["json"]["sha256"] = sha(data)
        manifest["responses"]["json"]["bytes"] = len(data)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")

    def test_resynced_report_edit_fails_delivery(self):
        trial = self._import()
        before = json.loads((trial.dir / "trial.json").read_text(encoding="utf-8"))["fingerprint"]
        report = json.loads((trial.dir / "artifacts/product/responses/report.json").read_text(encoding="utf-8"))
        report["title"] = "导入后被改写的标题"
        self._resync(trial, "responses/report.json", json.dumps(report, ensure_ascii=False).encode("utf-8"))
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertTrue(any(r.startswith("stale_report_identity") for r in result["reasons"]),
                        result["reasons"])
        after = json.loads((trial.dir / "trial.json").read_text(encoding="utf-8"))["fingerprint"]
        self.assertEqual(before, after, "history must not be rewritten to make the edit match")

    def test_trace_only_edit_keeps_report_delivery(self):
        trial = self._import()
        spans = trial.dir / "artifacts/product/traces/spans.jsonl"
        spans.write_bytes(spans.read_bytes() + b"\n")
        manifest_path = trial.dir / "artifacts/product/bundle.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["trace"]["sha256"] = sha(spans.read_bytes())
        manifest["trace"]["bytes"] = len(spans.read_bytes())
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(result["passed"], result["reasons"])
        row = summary.summarize_run(self.tmp / "out")["rows"][0]
        self.assertEqual(row["trace_state"], "CORRUPT")
        self.assertIsNone(row["input_tokens"])

    def test_untouched_import_records_both_identity_axes(self):
        trial = self._import()
        meta = json.loads((trial.dir / "trial.json").read_text(encoding="utf-8"))
        self.assertEqual(set(meta["capture_identity"]), {"report", "trace"})
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(result["passed"], result["reasons"])


class Repair01RejectedOriginalsTest(TempDirTest):
    """R6: a rejected capture keeps its readable bytes without relaxing path protection."""

    def test_readable_originals_are_preserved_with_actual_hashes(self):
        from report_evaluation import product_bridge
        source = make_bundle(self.tmp / "b", json_bytes=b"null\n", trace_bytes=mutation_trace_bytes())
        trial = product_bridge.import_unverifiable(self.tmp / "out", make_case(), source, "report_not_object")
        record = json.loads((trial.dir / "artifacts/product/rejected.json").read_text(encoding="utf-8"))
        for rel in ("bundle.json", "responses/report.json", "responses/report.html", "traces/spans.jsonl"):
            self.assertIn(rel, record["preserved_files"], rel)
            preserved = (trial.dir / "artifacts/product" / rel).read_bytes()
            self.assertEqual(preserved, (source / rel).read_bytes(), rel)
            self.assertEqual(record["preserved_files"][rel]["sha256"], sha(preserved), rel)
            self.assertEqual(record["preserved_files"][rel]["bytes"], len(preserved), rel)
        self.assertEqual((trial.dir / "artifacts/product/responses/report.json").read_bytes(), b"null\n")
        self.assertIsNone(record["terminal_status"])
        self.assertFalse((trial.dir / "trial_summary.json").exists())
        row = summary.summarize_run(self.tmp / "out")["rows"][0]
        self.assertEqual(row["classification"], "corrupted")

    def test_unsafe_declared_path_is_diagnosed_not_followed(self):
        from report_evaluation import product_bridge
        source = make_bundle(self.tmp / "b", json_bytes=b"null\n")
        manifest = json.loads((source / "bundle.json").read_text(encoding="utf-8"))
        manifest["responses"]["json"]["path"] = "../outside.json"
        (source / "bundle.json").write_text(json.dumps(manifest), encoding="utf-8")
        (self.tmp / "outside.json").write_bytes(b"never copied")
        trial = product_bridge.import_unverifiable(self.tmp / "out", make_case(), source, "report_not_object")
        record = json.loads((trial.dir / "artifacts/product/rejected.json").read_text(encoding="utf-8"))
        self.assertNotIn("../outside.json", record["preserved_files"])
        self.assertTrue(any("outside.json" in r for r in record["unpreserved_paths"]),
                        record["unpreserved_paths"])
        self.assertFalse((self.tmp / "out" / "outside.json").exists())

    def test_missing_declared_original_is_diagnosed(self):
        from report_evaluation import product_bridge
        source = make_bundle(self.tmp / "b", json_bytes=b"null\n")
        (source / "responses/report.html").unlink()
        trial = product_bridge.import_unverifiable(self.tmp / "out", make_case(), source, "report_not_object")
        record = json.loads((trial.dir / "artifacts/product/rejected.json").read_text(encoding="utf-8"))
        self.assertIn("responses/report.json", record["preserved_files"])
        self.assertTrue(any("report.html" in r for r in record["unpreserved_paths"]),
                        record["unpreserved_paths"])


class Repair02GroundingTypeTest(TempDirTest):
    """A: a wrong-typed enum value is a field problem, never a TypeError that destroys the batch."""

    def _refused(self, expected, **over):
        from report_evaluation import product_bundle
        with self.assertRaises(product_bundle.BundleError) as ctx:
            product_bundle.load_bundle(make_bundle(self.tmp / "b", **over))
        self.assertIn(expected, str(ctx.exception))

    def test_object_request_grounding_is_a_field_problem(self):
        self._refused("bad_request_grounding",
                      manifest_over={"request": {"prompt": PROMPT, "owner_token": "demo-alice",
                                                 "video_id": "1001", "sources": ["COMMENT"],
                                                 "grounding": {}}})

    def test_list_request_grounding_is_a_field_problem(self):
        self._refused("bad_request_grounding",
                      manifest_over={"request": {"prompt": PROMPT, "owner_token": "demo-alice",
                                                 "video_id": "1001", "sources": ["COMMENT"],
                                                 "grounding": []}})

    def test_object_scope_grounding_is_a_field_problem(self):
        self._refused("scope_bad_grounding",
                      json_bytes=product_json_bytes(scope=product_scope(grounding={})))

    def test_list_scope_grounding_is_a_field_problem(self):
        self._refused("scope_bad_grounding",
                      json_bytes=product_json_bytes(scope=product_scope(grounding=[])))

    def test_null_and_unknown_grounding_stay_field_problems(self):
        for value in (None, "NOT_A_GROUNDING"):
            with self.subTest(value=value):
                self._refused("scope_bad_grounding",
                              json_bytes=product_json_bytes(scope=product_scope(grounding=value)))

    def test_one_bad_grounding_does_not_destroy_the_healthy_sibling(self):
        from report_evaluation import product_bridge, product_bundle
        good = product_bundle.load_bundle(make_bundle(self.tmp / "good", bundle_id="good"))
        product_bridge.import_bundle(self.tmp / "out", make_case(bundle_id="good"), good)
        bad = make_bundle(self.tmp / "bad", bundle_id="bad",
                          json_bytes=product_json_bytes(scope=product_scope(grounding={})))
        _, verified = product_bridge.import_capture(
            self.tmp / "out", make_case(case_id="bad-grounding", bundle_id="bad"), bad)
        self.assertFalse(verified)
        result = summary.summarize_run(self.tmp / "out")
        self.assertEqual(result["trial_count"], 2)
        self.assertEqual(result["classifications"].get("success_delivered"), 1)
        self.assertEqual(result["corrupted_trial_count"], 1)


class Repair02ReceiptRequiredSetTest(TempDirTest):
    """B: the required set is what FeedbackOperationCoordinator.deliver() emits, per tool."""

    GENERATE = ("status", "evidence_id", "artifact_id", "url", "demo",
                "json_url", "summary", "topics", "method")
    CREATE = ("status", "evidence_id", "artifact_id", "url", "demo")

    def _load(self, receipt, tool="generate_feedback_report"):
        from report_evaluation import product_bundle
        manifest = json.loads((make_bundle(self.tmp / "b") / "bundle.json").read_text(encoding="utf-8"))
        manifest["run"]["receipt_tool"] = tool
        manifest["run"]["receipt"] = receipt
        (self.tmp / "b" / "bundle.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        return product_bundle.load_bundle(self.tmp / "b")

    def _refused(self, expected, receipt, tool="generate_feedback_report"):
        from report_evaluation import product_bundle
        with self.assertRaises(product_bundle.BundleError) as ctx:
            self._load(receipt, tool)
        self.assertIn(expected, str(ctx.exception))

    def _full(self):
        return json.loads((make_bundle(self.tmp / "full") / "bundle.json")
                          .read_text(encoding="utf-8"))["run"]["receipt"]

    def test_every_generate_key_is_required(self):
        for key in self.GENERATE:
            with self.subTest(key=key):
                receipt = self._full()
                del receipt[key]
                self._refused(f"receipt_missing_{key}", receipt)

    def test_every_create_key_is_required(self):
        full = self._full()
        for key in self.CREATE:
            with self.subTest(key=key):
                receipt = {k: full[k] for k in self.CREATE if k != key}
                self._refused(f"receipt_missing_{key}", receipt, "create_feedback_report")

    def test_create_with_exactly_the_common_keys_is_legal(self):
        full = self._full()
        bundle = self._load({k: full[k] for k in self.CREATE}, "create_feedback_report")
        self.assertEqual(bundle.final_status, "SUCCEEDED")

    def test_create_does_not_demand_generate_extras(self):
        full = self._full()
        bundle = self._load({k: full[k] for k in self.CREATE}, "create_feedback_report")
        self.assertEqual(set(bundle.manifest["run"]["receipt"]), set(self.CREATE))

    def test_create_carrying_consistent_extras_is_legal(self):
        bundle = self._load(self._full(), "create_feedback_report")
        self.assertEqual(bundle.final_status, "SUCCEEDED")

    def test_legal_empty_result_is_not_refused(self):
        bundle = self._load(self._full())
        self.assertEqual(bundle.report["topics"], [])
        self.assertEqual(bundle.manifest["run"]["receipt"]["topics"], [])
        self.assertEqual(bundle.report["method"]["raw"], 0)

    def test_wrong_typed_artifact_id_is_not_reported_as_missing(self):
        receipt = self._full()
        receipt["artifact_id"] = 5
        self._refused("receipt_bad_artifact_id", receipt)

    def test_receipt_follows_an_overridden_report(self):
        source = make_bundle(self.tmp / "b", json_bytes=product_json_bytes(summary="另一条结论"))
        from report_evaluation import product_bundle
        bundle = product_bundle.load_bundle(source)
        self.assertEqual(bundle.manifest["run"]["receipt"]["summary"], "另一条结论")


class Repair02LedgerClosureTest(TempDirTest):
    """C: capture -> ledgered identity -> total fingerprint -> experiment_id -> process usage."""

    def _import(self, trace=True, **over):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(
            self.tmp / "b", trace_bytes=mutation_trace_bytes() if trace else None, **over))
        return product_bridge.import_bundle(self.tmp / "out", make_case(), bundle)

    def _meta(self, trial):
        return json.loads((trial.dir / "trial.json").read_text(encoding="utf-8"))

    def _write_meta(self, trial, meta):
        (trial.dir / "trial.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")

    def _row(self):
        return summary.summarize_run(self.tmp / "out")["rows"][0]

    def _append_span(self, trial, also_copy, resync):
        manifest_path = trial.dir / "artifacts/product/bundle.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        span = {"traceId": "c" * 32, "spanId": "d" * 16, "name": "model.request",
                "startTimeUnixNano": 0, "endTimeUnixNano": 100,
                "attributes": {"run.id": manifest["run"]["run_id"], "model.dispatched": True,
                               "model.input_tokens": 999, "model.output_tokens": 99}}
        original = trial.dir / "artifacts/product" / manifest["trace"]["path"]
        data = original.read_bytes().rstrip(b"\n") + b"\n" + json.dumps(span).encode("utf-8") + b"\n"
        original.write_bytes(data)
        if also_copy:
            (trial.dir / "traces/trace.jsonl").write_bytes(data)
        if resync:
            manifest["trace"]["sha256"] = sha(data)
            manifest["trace"]["bytes"] = len(data)
            manifest["trace"]["span_count"] = len(data.decode("utf-8").splitlines())
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")

    def test_import_ledgers_the_fingerprint_payload(self):
        trial = self._import()
        meta = self._meta(trial)
        self.assertIn("capture_fingerprint_payload", meta)
        self.assertEqual(meta["fingerprint"],
                         cases.case_fingerprint(meta["case_snapshot"],
                                                capture=meta["capture_fingerprint_payload"]))
        self.assertEqual(meta["experiment_id"], cases.experiment_id(meta["fingerprint"]))

    def test_clean_import_has_no_attribution_diagnostics(self):
        trial = self._import()
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(result["passed"], result["reasons"])
        self.assertEqual(result["attribution_diagnostics"], [])
        self.assertEqual(self._row()["classification"], "success_delivered")

    def test_zeroed_fingerprint_is_caught_without_erasing_delivery(self):
        trial = self._import()
        meta = self._meta(trial)
        meta["fingerprint"] = "0" * 64
        self._write_meta(trial, meta)
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertIn("capture_fingerprint_invalid", result["attribution_diagnostics"])
        self.assertIn("capture_experiment_mismatch", result["attribution_diagnostics"])
        self.assertTrue(result["passed"], result["reasons"])
        row = self._row()
        self.assertEqual(row["classification"], "corrupted")
        self.assertTrue(row["delivery_passed"])

    def test_foreign_experiment_id_is_caught(self):
        trial = self._import()
        meta = self._meta(trial)
        meta["experiment_id"] = "exp-0000000000000000"
        self._write_meta(trial, meta)
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertIn("capture_experiment_mismatch", result["attribution_diagnostics"])
        self.assertEqual(self._row()["classification"], "corrupted")

    def test_summary_identity_disagreement_is_caught(self):
        trial = self._import()
        path = trial.dir / "trial_summary.json"
        record = json.loads(path.read_text(encoding="utf-8"))
        record["experiment_id"] = "exp-0000000000000000"
        path.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertIn("trial_summary_identity_mismatch:experiment_id", result["attribution_diagnostics"])

    def test_fabricated_terminal_status_is_caught(self):
        trial = self._import()
        path = trial.dir / "trial_summary.json"
        record = json.loads(path.read_text(encoding="utf-8"))
        record["final_status"] = "FAILED"
        path.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertIn("trial_summary_final_status_mismatch", result["attribution_diagnostics"])
        self.assertEqual(self._row()["classification"], "corrupted")

    def test_coherent_trace_mutation_cannot_bill_fabricated_usage(self):
        trial = self._import()
        self._append_span(trial, also_copy=True, resync=True)
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(result["passed"], result["reasons"])
        row = self._row()
        self.assertEqual(row["trace_state"], "CORRUPT")
        self.assertIsNone(row["input_tokens"])
        self.assertIsNone(row["output_tokens"])
        self.assertIsNone(row["cost_low"])
        self.assertTrue(row["delivery_passed"])

    def test_trace_only_drift_is_attributed_to_the_trace_axis(self):
        trial = self._import()
        self._append_span(trial, also_copy=True, resync=True)
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(any(d.startswith("capture_fingerprint_trace_drift")
                            for d in result["trace_diagnostics"]), result["trace_diagnostics"])
        self.assertFalse(any("capture_fingerprint" in r for r in result["reasons"]), result["reasons"])

    def test_report_edit_is_attributed_to_the_report_axis(self):
        trial = self._import()
        path = trial.dir / "artifacts/product/responses/report.json"
        report = json.loads(path.read_text(encoding="utf-8"))
        report["summary"] = "导入后被改写的结论"
        data = json.dumps(report, ensure_ascii=False).encode("utf-8")
        path.write_bytes(data)
        manifest_path = trial.dir / "artifacts/product/bundle.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["responses"]["json"]["sha256"] = sha(data)
        manifest["responses"]["json"]["bytes"] = len(data)
        manifest["run"]["receipt"]["summary"] = report["summary"]
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertTrue(any(r.startswith("capture_fingerprint_payload_mismatch")
                            for r in result["reasons"]), result["reasons"])
        self.assertEqual(result["trace_diagnostics"], [])

    def test_copy_only_mutation_still_corrupt(self):
        trial = self._import()
        self._append_span(trial, also_copy=False, resync=False)
        (trial.dir / "traces/trace.jsonl").write_bytes(
            (trial.dir / "traces/trace.jsonl").read_bytes() + b"\n")
        row = self._row()
        self.assertEqual(row["trace_state"], "CORRUPT")
        self.assertTrue(row["delivery_passed"])

    def test_no_trace_declaration_stays_missing(self):
        trial = self._import(trace=False)
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(result["passed"], result["reasons"])
        self.assertEqual(result["attribution_diagnostics"], [])
        row = self._row()
        self.assertEqual(row["trace_state"], "MISSING")
        self.assertEqual(row["classification"], "success_delivered")

    def test_attribution_invalid_trials_are_counted_per_batch(self):
        trial = self._import()
        meta = self._meta(trial)
        meta["experiment_id"] = "exp-0000000000000000"
        self._write_meta(trial, meta)
        result = summary.summarize_run(self.tmp / "out")
        self.assertEqual(result["attribution_invalid_trial_count"], 1)
        self.assertEqual(result["batches"][0]["attribution_invalid_trial_count"], 1)

    def test_interrupted_trial_is_not_reclassified_as_attribution_corrupt(self):
        from report_evaluation import product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / "b"))
        case = make_case()
        fp = cases.case_fingerprint(case, capture=product_bundle.fingerprint_payload(bundle))
        trial = ledger.create_trial(self.tmp / "out", case, fp, cases.experiment_id(fp))
        trial.simulate_interrupt()
        self.assertEqual(self._row()["classification"], "interrupted")


class Repair03CaseRequestTypeTest(TempDirTest):
    """A: the case declaration's request types are refused as fields, never as a TypeError."""

    CRASH_GROUNDING = ({}, [], ["FEEDBACK_ONLY"])
    CRASH_SOURCES = ([{}], [[]])

    def _errors(self, **request_over):
        case = make_case()
        for key, value in request_over.items():
            if value is _DELETE:
                case["product_capture"]["request"].pop(key, None)
            else:
                case["product_capture"]["request"][key] = value
        return cases.validate_case(case, "product")

    def _assert_field(self, errors, field):
        self.assertTrue(errors, "expected a controlled refusal")
        self.assertTrue(any(field in e for e in errors), errors)

    def test_wrong_typed_grounding_is_a_field_problem(self):
        for value in self.CRASH_GROUNDING:
            with self.subTest(value=value):
                self._assert_field(self._errors(grounding=value), "product_capture.request.grounding")

    def test_wrong_typed_sources_elements_are_field_problems(self):
        for value in self.CRASH_SOURCES:
            with self.subTest(value=value):
                self._assert_field(self._errors(sources=value), "product_capture.request.sources")

    def test_already_controlled_request_values_stay_field_problems(self):
        for value in (None, "VIDEO_ONLY"):
            with self.subTest(grounding=value):
                self._assert_field(self._errors(grounding=value), "product_capture.request.grounding")
        for value in ({}, [], ["COMMENT", "COMMENT"], [None], [7], ["SUBTITLE"]):
            with self.subTest(sources=value):
                self._assert_field(self._errors(sources=value), "product_capture.request.sources")

    def test_missing_request_fields_stay_field_problems(self):
        for field in ("grounding", "sources"):
            with self.subTest(field=field):
                self._assert_field(self._errors(**{field: _DELETE}),
                                   "product_capture.request missing: " + field)

    def test_both_legal_grounding_enums_are_accepted(self):
        for grounding in ("FEEDBACK_ONLY", "VIDEO_GROUNDED"):
            with self.subTest(grounding=grounding):
                case = make_case(report_scope=grounding)
                case["product_capture"]["request"]["grounding"] = grounding
                self.assertEqual(cases.validate_case(case, "product"), [])

    def test_legal_source_subsets_are_accepted(self):
        for sources in (["COMMENT"], ["DANMAKU"], ["COMMENT", "DANMAKU"]):
            with self.subTest(sources=sources):
                self.assertEqual(self._errors(sources=sources), [])

    def test_grounding_must_still_equal_the_case_report_scope(self):
        case = make_case(report_scope="FEEDBACK_ONLY")
        case["product_capture"]["request"]["grounding"] = "VIDEO_GROUNDED"
        self._assert_field(cases.validate_case(case, "product"), "report_scope")
        case = make_case(report_scope="VIDEO_GROUNDED")
        case["product_capture"]["request"]["grounding"] = "FEEDBACK_ONLY"
        self._assert_field(cases.validate_case(case, "product"), "report_scope")

    def _bad_case_files(self):
        for i, value in enumerate(self.CRASH_GROUNDING):
            yield "grounding-%d" % i, "grounding", make_case(), {"grounding": value}
        for i, value in enumerate(self.CRASH_SOURCES):
            yield "sources-%d" % i, "sources", make_case(), {"sources": value}

    def test_cli_validate_refuses_wrong_request_types_with_exit_2(self):
        for label, field, case, over in self._bad_case_files():
            with self.subTest(label=label):
                case["product_capture"]["request"].update(over)
                path = write_cases(self.tmp / (label + ".json"), [case])
                code, out, err = cli("validate", "--mode", "product", "--cases", str(path))
                self.assertEqual(code, 2, out + err)
                self.assertNotIn("Traceback", out + err)
                self.assertIn("product_capture.request." + field, out)

    def test_cli_import_refuses_wrong_request_types_without_a_trial(self):
        bundle = make_bundle(self.tmp / "b", bundle_id="b1")
        for label, field, case, over in self._bad_case_files():
            with self.subTest(label=label):
                case["product_capture"]["request"].update(over)
                path = write_cases(self.tmp / (label + ".json"), [case])
                out_dir = self.tmp / ("out-" + label)
                code, out, err = cli("import-product", "--cases", str(path),
                                     "--bundle", str(bundle), "--output", str(out_dir))
                self.assertEqual(code, 2, out + err)
                self.assertNotIn("Traceback", out + err)
                self.assertIn("product_capture.request." + field, out)
                self.assertFalse((out_dir / "trials").exists())
                self.assertFalse((out_dir / "summary.json").exists())

    def test_refused_case_file_leaves_existing_output_untouched(self):
        bundle = make_bundle(self.tmp / "b", bundle_id="b1")
        out_dir = self.tmp / "out"
        out_dir.mkdir(parents=True)
        sentinel = out_dir / "PRE-EXISTING.txt"
        sentinel.write_bytes(b"must survive a refused case file\n")
        bad = make_case()
        bad["product_capture"]["request"]["grounding"] = {}
        path = write_cases(self.tmp / "cases.json", [make_case(case_id="healthy"), bad])
        code, out, err = cli("import-product", "--cases", str(path), "--bundle", str(bundle),
                             "--output", str(out_dir))
        self.assertEqual(code, 2, out + err)
        self.assertEqual(sentinel.read_bytes(), b"must survive a refused case file\n")
        self.assertFalse((out_dir / "trials").exists())
        self.assertFalse((out_dir / "summary.json").exists())

    def test_whole_file_refusal_does_not_execute_the_healthy_peer(self):
        bundle = make_bundle(self.tmp / "b", bundle_id="b1")
        bad = make_case(case_id="bad")
        bad["product_capture"]["request"]["sources"] = [{}]
        mixed = write_cases(self.tmp / "mixed.json", [make_case(case_id="healthy"), bad])
        code, out, err = cli("import-product", "--cases", str(mixed), "--bundle", str(bundle),
                             "--output", str(self.tmp / "mixed-out"))
        self.assertEqual(code, 2, out + err)
        self.assertFalse((self.tmp / "mixed-out" / "trials").exists())
        alone = write_cases(self.tmp / "alone.json", [make_case(case_id="healthy")])
        code, out, err = cli("import-product", "--cases", str(alone), "--bundle", str(bundle),
                             "--output", str(self.tmp / "alone-out"))
        self.assertEqual(code, 0, out + err)
        self.assertEqual(len(list((self.tmp / "alone-out" / "trials").iterdir())), 1)


class Repair03TerminalAttributionTest(TempDirTest):
    """B: a corrupted terminal declaration convicts attribution, not an independently verified delivery."""

    def _import(self, bundle_id="b1", case_id="p1", out=None):
        from report_evaluation import product_bridge, product_bundle
        bundle = product_bundle.load_bundle(make_bundle(self.tmp / bundle_id, bundle_id=bundle_id))
        return product_bridge.import_bundle(out or self.tmp / "out",
                                            make_case(case_id=case_id, bundle_id=bundle_id), bundle)

    def _tamper(self, trial, status):
        path = trial.dir / "trial_summary.json"
        before = path.read_bytes()
        old = json.loads(before.decode("utf-8"))["final_status"]
        needle = ('"final_status": "%s"' % old).encode("utf-8")
        self.assertEqual(before.count(needle), 1)
        after = before.replace(needle, ('"final_status": "%s"' % status).encode("utf-8"))
        path.write_bytes(after)
        return before, after

    def _product_hashes(self, trial):
        root = trial.dir / "artifacts" / "product"
        return {str(p.relative_to(root)): sha(p.read_bytes()) for p in sorted(root.rglob("*")) if p.is_file()}

    def _row(self, case_id, out=None):
        rows = summary.summarize_run(out or self.tmp / "out")["rows"]
        return [r for r in rows if r["case_id"] == case_id][0]

    def test_fabricated_failed_status_keeps_the_verified_delivery_fact(self):
        trial = self._import()
        self._tamper(trial, "FAILED")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(result["passed"], result["reasons"])
        self.assertEqual(result["reasons"], [])
        self.assertIn("trial_summary_final_status_mismatch", result["attribution_diagnostics"])
        row = self._row("p1")
        self.assertTrue(row["delivery_passed"])
        self.assertEqual(row["delivery_reasons"], [])
        self.assertEqual(row["classification"], "corrupted")
        self.assertEqual(row["diagnostics"], ["trial_summary_final_status_mismatch"])

    def test_fabricated_needs_input_status_keeps_the_verified_delivery_fact(self):
        trial = self._import()
        self._tamper(trial, "NEEDS_INPUT")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertTrue(result["passed"], result["reasons"])
        self.assertEqual(result["reasons"], [])
        row = self._row("p1")
        self.assertTrue(row["delivery_passed"])
        self.assertEqual(row["classification"], "corrupted")

    def test_the_erroneous_declaration_is_preserved_not_rewritten(self):
        trial = self._import()
        before, after = self._tamper(trial, "FAILED")
        self.assertNotEqual(before, after)
        self.assertEqual(sum(1 for a, b in zip(before.split(b"\n"), after.split(b"\n")) if a != b), 1)
        self.assertEqual(json.loads(after.decode("utf-8"))["final_status"], "FAILED")
        self.assertEqual(self._row("p1")["final_status"], "FAILED")
        self.assertEqual(delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])["final_status"],
                         "FAILED")

    def test_tampering_the_summary_leaves_every_product_byte_alone(self):
        trial = self._import()
        before = self._product_hashes(trial)
        self._tamper(trial, "NEEDS_INPUT")
        self.assertEqual(self._product_hashes(trial), before)
        self.assertTrue(before)

    def test_corrupted_declaration_still_exits_3_and_stays_out_of_the_clean_score(self):
        trial = self._import()
        self._tamper(trial, "FAILED")
        result = summary.summarize_run(self.tmp / "out")
        self.assertEqual(result["corrupted_trial_count"], 1)
        self.assertEqual(result["attribution_invalid_trial_count"], 1)
        self.assertNotIn("success_delivered", result["classifications"])

    def test_semantic_quality_and_unknown_usage_are_unchanged_by_the_split(self):
        trial = self._import()
        self._tamper(trial, "FAILED")
        row = self._row("p1")
        self.assertEqual(row["semantic_quality"], "NOT_ASSESSED")
        for key in ("input_tokens", "output_tokens", "cost_low", "cost_high"):
            self.assertIsNone(row[key])

    def test_cli_mixed_batch_keeps_delivery_and_still_exits_3(self):
        clean = make_bundle(self.tmp / "clean", bundle_id="clean")
        subject = make_bundle(self.tmp / "subject", bundle_id="subject")
        cases_path = write_cases(self.tmp / "cases.json",
                                 [make_case(case_id="clean", bundle_id="clean"),
                                  make_case(case_id="subject", bundle_id="subject")])
        out_dir = self.tmp / "cli-out"
        code, out, err = cli("import-product", "--cases", str(cases_path), "--bundle", str(clean),
                             "--bundle", str(subject), "--output", str(out_dir))
        self.assertEqual(code, 0, out + err)
        for trial_dir in sorted((out_dir / "trials").iterdir()):
            meta = json.loads((trial_dir / "trial.json").read_text(encoding="utf-8"))
            if meta["case_id"] == "subject":
                path = trial_dir / "trial_summary.json"
                data = path.read_bytes()
                needle = b'"final_status": "SUCCEEDED"'
                self.assertEqual(data.count(needle), 1)
                path.write_bytes(data.replace(needle, b'"final_status": "FAILED"'))
        code, out, err = cli("summarize", "--input", str(out_dir))
        self.assertEqual(code, 3, out + err)
        self.assertNotIn("Traceback", out + err)
        rows = {r["case_id"]: r for r in json.loads((out_dir / "summary.json").read_text(encoding="utf-8"))["rows"]}
        self.assertTrue(rows["subject"]["delivery_passed"])
        self.assertEqual(rows["subject"]["delivery_reasons"], [])
        self.assertEqual(rows["subject"]["classification"], "corrupted")
        self.assertEqual(rows["subject"]["diagnostics"], ["trial_summary_final_status_mismatch"])
        self.assertEqual(rows["subject"]["final_status"], "FAILED")
        self.assertTrue(rows["clean"]["delivery_passed"])
        self.assertEqual(rows["clean"]["classification"], "success_delivered")
        self.assertEqual(rows["clean"]["diagnostics"], [])

    def test_genuine_needs_input_original_keeps_its_own_status_reason(self):
        from report_evaluation import product_bridge, product_bundle
        run = {"run_id": "11111111-2222-3333-4444-555555555555", "final_status": "NEEDS_INPUT",
               "clarification": "which window?"}
        root = make_bundle(self.tmp / "ni", bundle_id="ni", drop=("json", "html"),
                           manifest_over={"run": run})
        bundle = product_bundle.load_bundle(root)
        trial = product_bridge.import_bundle(self.tmp / "out", make_case(bundle_id="ni"), bundle)
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertEqual(result["reasons"], ["status_NEEDS_INPUT"])
        self.assertEqual(self._row("p1")["classification"], "needs_input")

    def test_missing_report_original_still_fails_delivery(self):
        trial = self._import()
        (trial.dir / "artifacts/product/responses/report.json").unlink()
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertTrue(result["reasons"])

    def test_corrupt_report_original_still_fails_delivery(self):
        trial = self._import()
        path = trial.dir / "artifacts/product/responses/report.json"
        data = bytearray(path.read_bytes())
        data[len(data) // 2] ^= 0xFF
        path.write_bytes(bytes(data))
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertTrue(any(r.startswith("product_capture_invalid") for r in result["reasons"]),
                        result["reasons"])

    def test_tampered_manifest_status_is_not_trusted_as_a_delivery(self):
        trial = self._import()
        path = trial.dir / "artifacts/product/bundle.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["run"]["final_status"] = "FAILED"
        path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertTrue(result["reasons"])

    def test_missing_summary_stays_incomplete(self):
        trial = self._import()
        (trial.dir / "trial_summary.json").unlink()
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertEqual(result["reasons"], ["incomplete_trial"])

    def test_unreadable_summary_stays_unreadable(self):
        trial = self._import()
        (trial.dir / "trial_summary.json").write_text("{ not json", encoding="utf-8")
        result = delivery.check_delivery(trial.dir, trial.meta["case_snapshot"])
        self.assertFalse(result["passed"])
        self.assertEqual(result["reasons"], ["trial_summary_unreadable"])


class Repair04CaseFieldTypeTest(TempDirTest):
    """The four case-declaration fields are type-checked before they are consumed.

    report_scope / material_class / offline_simulation.scenario / review.status used to enter a set
    membership test or a .get() call before any type check, so a dict/list value escaped as an
    uncaught TypeError/AttributeError (exit 1 + traceback) instead of a controlled field refusal.
    """

    CRASH_SCOPE = ({}, [], ["FEEDBACK_ONLY"])
    CRASH_MATERIAL_CLASS = ({}, [], ["synthetic_fixture"])
    CRASH_CONTAINER = ([], "success", None)
    CRASH_STATUS = ({}, [], ["pending"])
    CRASH_SCENARIO = ({}, [], ["success"])

    def _apply(self, case, over):
        for key, value in over.items():
            if value is _DELETE:
                case.pop(key, None)
            else:
                case[key] = value
        return case

    def _offline(self, **over):
        case = make_case()
        case.pop("product_capture")
        case["offline_simulation"] = {"scenario": "success"}
        return self._apply(case, over)

    def _both_modes(self, **over):
        yield "offline", cases.validate_case(self._offline(**over), "offline")
        yield "product", cases.validate_case(self._apply(make_case(), over), "product")

    def _assert_field(self, errors, field):
        self.assertTrue(errors, "expected a controlled refusal")
        self.assertTrue(any(field in e for e in errors), errors)

    def test_wrong_typed_report_scope_is_a_field_problem(self):
        for value in self.CRASH_SCOPE:
            with self.subTest(value=value):
                for mode, errors in self._both_modes(report_scope=value):
                    self._assert_field(errors, "bad report_scope:")

    def test_wrong_typed_material_class_is_a_field_problem(self):
        for value in self.CRASH_MATERIAL_CLASS:
            with self.subTest(value=value):
                for mode, errors in self._both_modes(material_class=value):
                    self._assert_field(errors, "bad material_class:")

    def test_wrong_typed_review_container_is_a_field_problem(self):
        for value in self.CRASH_CONTAINER:
            with self.subTest(value=value):
                for mode, errors in self._both_modes(review=value):
                    self._assert_field(errors, "bad review:")

    def test_wrong_typed_review_status_is_a_field_problem(self):
        for value in self.CRASH_STATUS:
            with self.subTest(value=value):
                for mode, errors in self._both_modes(review={"status": value}):
                    self._assert_field(errors, "bad review.status:")

    def test_wrong_typed_offline_simulation_container_is_a_field_problem(self):
        for value in self.CRASH_CONTAINER:
            with self.subTest(value=value):
                self._assert_field(cases.validate_case(self._offline(offline_simulation=value), "offline"),
                                   "bad offline_simulation:")

    def test_wrong_typed_scenario_is_a_field_problem(self):
        for value in self.CRASH_SCENARIO:
            with self.subTest(value=value):
                self._assert_field(
                    cases.validate_case(self._offline(offline_simulation={"scenario": value}), "offline"),
                    "bad offline_simulation.scenario:")

    def test_already_controlled_values_keep_their_exact_messages(self):
        """These messages predate the fix and must not change: old assertions depend on them."""
        expected = [
            ({"report_scope": None}, "bad report_scope: None"),
            ({"report_scope": "NOT_A_SCOPE"}, "bad report_scope: 'NOT_A_SCOPE'"),
            ({"material_class": None}, "bad material_class: None"),
            ({"material_class": "not_a_class"}, "bad material_class: 'not_a_class'"),
            ({"review": {}}, "bad review.status: None"),
            ({"review": {"status": None}}, "bad review.status: None"),
            ({"review": {"status": "not_a_status"}}, "bad review.status: 'not_a_status'"),
        ]
        for over, message in expected:
            with self.subTest(message=message):
                for mode, errors in self._both_modes(**over):
                    self.assertIn(message, errors)
        for over, message in [({"offline_simulation": {}}, "bad offline_simulation.scenario: None"),
                              ({"offline_simulation": {"scenario": None}},
                               "bad offline_simulation.scenario: None"),
                              ({"offline_simulation": {"scenario": "not_a_scenario"}},
                               "bad offline_simulation.scenario: 'not_a_scenario'")]:
            with self.subTest(message=message):
                self.assertIn(message, cases.validate_case(self._offline(**over), "offline"))

    def test_missing_fields_keep_their_exact_messages(self):
        for field in ("report_scope", "material_class", "review"):
            with self.subTest(field=field):
                for mode, errors in self._both_modes(**{field: _DELETE}):
                    self.assertIn("missing field: " + field, errors)
        offline = self._offline()
        del offline["offline_simulation"]
        self.assertIn("missing field: offline_simulation", cases.validate_case(offline, "offline"))

    def test_legal_values_still_pass_in_both_modes(self):
        self.assertEqual(cases.validate_case(self._offline(), "offline"), [])
        self.assertEqual(cases.validate_case(make_case(), "product"), [])
        for scope in ("FEEDBACK_ONLY", "VIDEO_GROUNDED", "VIDEO_ONLY"):
            with self.subTest(scope=scope):
                self.assertEqual(cases.validate_case(self._offline(report_scope=scope), "offline"), [])
        for scenario in sorted(cases.SCENARIOS):
            with self.subTest(scenario=scenario):
                self.assertEqual(cases.validate_case(
                    self._offline(offline_simulation={"scenario": scenario}), "offline"), [])
        for status in sorted(cases.REVIEW_STATUSES):
            with self.subTest(status=status):
                self.assertEqual(cases.validate_case(
                    self._offline(review={"status": status}), "offline"), [])

    def test_product_mode_ignores_offline_simulation_entirely(self):
        """Scope control: the fix must not leak into the product declaration channel."""
        baseline = cases.validate_case(make_case(), "product")
        for value in ([], "success", None, {}, {"scenario": {}}, {"scenario": []}):
            with self.subTest(value=value):
                self.assertEqual(cases.validate_case(make_case(offline_simulation=value), "product"),
                                 baseline)

    def _bad_offline_cases(self):
        for i, value in enumerate(self.CRASH_SCOPE):
            yield "scope-%d" % i, "bad report_scope:", self._offline(report_scope=value)
        for i, value in enumerate(self.CRASH_MATERIAL_CLASS):
            yield "matclass-%d" % i, "bad material_class:", self._offline(material_class=value)
        for i, value in enumerate(self.CRASH_CONTAINER):
            yield "review-%d" % i, "bad review:", self._offline(review=value)
        for i, value in enumerate(self.CRASH_STATUS):
            yield "status-%d" % i, "bad review.status:", self._offline(review={"status": value})
        for i, value in enumerate(self.CRASH_CONTAINER):
            yield "sim-%d" % i, "bad offline_simulation:", self._offline(offline_simulation=value)
        for i, value in enumerate(self.CRASH_SCENARIO):
            yield "scenario-%d" % i, "bad offline_simulation.scenario:", self._offline(
                offline_simulation={"scenario": value})

    def _bad_product_cases(self):
        for i, value in enumerate(self.CRASH_SCOPE):
            yield "scope-%d" % i, "bad report_scope:", make_case(report_scope=value)
        for i, value in enumerate(self.CRASH_MATERIAL_CLASS):
            yield "matclass-%d" % i, "bad material_class:", make_case(material_class=value)
        for i, value in enumerate(self.CRASH_CONTAINER):
            yield "review-%d" % i, "bad review:", make_case(review=value)
        for i, value in enumerate(self.CRASH_STATUS):
            yield "status-%d" % i, "bad review.status:", make_case(review={"status": value})

    def test_cli_validate_offline_refuses_wrong_field_types_with_exit_2(self):
        for label, field, case in self._bad_offline_cases():
            with self.subTest(label=label):
                path = write_cases(self.tmp / (label + ".json"), [case])
                code, out, err = cli("validate", "--mode", "offline", "--cases", str(path))
                self.assertEqual(code, 2, out + err)
                self.assertNotIn("Traceback", out + err)
                self.assertIn(field, out)

    def test_cli_validate_product_refuses_wrong_field_types_with_exit_2(self):
        for label, field, case in self._bad_product_cases():
            with self.subTest(label=label):
                path = write_cases(self.tmp / (label + ".json"), [case])
                code, out, err = cli("validate", "--mode", "product", "--cases", str(path))
                self.assertEqual(code, 2, out + err)
                self.assertNotIn("Traceback", out + err)
                self.assertIn(field, out)

    def test_cli_run_refuses_wrong_field_types_without_a_trial(self):
        for label, field, case in self._bad_offline_cases():
            with self.subTest(label=label):
                path = write_cases(self.tmp / (label + ".json"), [case])
                out_dir = self.tmp / ("out-" + label)
                code, out, err = cli("run", "--mode", "offline", "--cases", str(path),
                                     "--output", str(out_dir))
                self.assertEqual(code, 2, out + err)
                self.assertNotIn("Traceback", out + err)
                self.assertIn(field, out)
                self.assertFalse((out_dir / "trials").exists())
                self.assertFalse((out_dir / "summary.json").exists())

    def test_cli_import_refuses_wrong_field_types_without_a_trial(self):
        bundle = make_bundle(self.tmp / "b", bundle_id="b1")
        for label, field, case in self._bad_product_cases():
            with self.subTest(label=label):
                path = write_cases(self.tmp / (label + ".json"), [case])
                out_dir = self.tmp / ("out-" + label)
                code, out, err = cli("import-product", "--cases", str(path),
                                     "--bundle", str(bundle), "--output", str(out_dir))
                self.assertEqual(code, 2, out + err)
                self.assertNotIn("Traceback", out + err)
                self.assertIn(field, out)
                self.assertFalse((out_dir / "trials").exists())
                self.assertFalse((out_dir / "summary.json").exists())

    def test_refused_case_file_leaves_existing_output_untouched(self):
        bundle = make_bundle(self.tmp / "b", bundle_id="b1")
        out_dir = self.tmp / "out"
        out_dir.mkdir(parents=True)
        sentinel = out_dir / "PRE-EXISTING.txt"
        sentinel.write_bytes(b"must survive a refused case file\n")
        bad = make_case(case_id="bad", review={"status": {}})
        path = write_cases(self.tmp / "cases.json", [make_case(case_id="healthy"), bad])
        code, out, err = cli("import-product", "--cases", str(path), "--bundle", str(bundle),
                             "--output", str(out_dir))
        self.assertEqual(code, 2, out + err)
        self.assertEqual(sentinel.read_bytes(), b"must survive a refused case file\n")
        self.assertFalse((out_dir / "trials").exists())
        self.assertFalse((out_dir / "summary.json").exists())

    def test_whole_file_refusal_does_not_execute_the_healthy_peer(self):
        bundle = make_bundle(self.tmp / "b", bundle_id="b1")
        bad = make_case(case_id="bad", material_class=[])
        mixed = write_cases(self.tmp / "mixed.json", [make_case(case_id="healthy"), bad])
        code, out, err = cli("import-product", "--cases", str(mixed), "--bundle", str(bundle),
                             "--output", str(self.tmp / "mixed-out"))
        self.assertEqual(code, 2, out + err)
        self.assertFalse((self.tmp / "mixed-out" / "trials").exists())
        alone = write_cases(self.tmp / "alone.json", [make_case(case_id="healthy")])
        code, out, err = cli("import-product", "--cases", str(alone), "--bundle", str(bundle),
                             "--output", str(self.tmp / "alone-out"))
        self.assertEqual(code, 0, out + err)
        self.assertEqual(len(list((self.tmp / "alone-out" / "trials").iterdir())), 1)

    def test_offline_run_refusal_does_not_execute_the_healthy_peer(self):
        bad = self._offline(case_id="bad", report_scope={})
        mixed = write_cases(self.tmp / "mixed.json", [self._offline(case_id="healthy"), bad])
        code, out, err = cli("run", "--mode", "offline", "--cases", str(mixed),
                             "--output", str(self.tmp / "mixed-out"))
        self.assertEqual(code, 2, out + err)
        self.assertFalse((self.tmp / "mixed-out" / "trials").exists())
        alone = write_cases(self.tmp / "alone.json", [self._offline(case_id="healthy")])
        code, out, err = cli("run", "--mode", "offline", "--cases", str(alone),
                             "--output", str(self.tmp / "alone-out"))
        self.assertEqual(code, 0, out + err)
        self.assertEqual(len(list((self.tmp / "alone-out" / "trials").iterdir())), 1)


from datetime import datetime, timedelta  # noqa: E402
from report_evaluation.util import CaseError  # noqa: E402


class Repair05EntryPointTest(TempDirTest):
    """A: the cases element and the shared identity/container fields are checked before consumption.

    A non-object element used to reach `field not in case` (None/int/bool -> TypeError) or the CLI's
    own case.get (list/str -> AttributeError), so the whole file died with a traceback instead of a
    locatable refusal. load_cases now guarantees every element is an object before any consumer sees it.
    """

    NON_OBJECTS = (None, [], ["x"], 123, 0, True, False, "text", "")

    def _offline(self, **over):
        case = make_case()
        case.pop("product_capture")
        case["offline_simulation"] = {"scenario": "success"}
        case.update(over)
        return case

    def _kind(self, value):
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
        return "object"

    def test_load_cases_refuses_every_non_object_element_with_an_index(self):
        for value in self.NON_OBJECTS:
            with self.subTest(value=value):
                path = write_cases(self.tmp / "c.json", [self._offline(case_id="healthy"), value])
                with self.assertRaises(CaseError) as ctx:
                    cases.load_cases(path)
                message = str(ctx.exception)
                self.assertIn("cases[1]", message)
                self.assertIn(self._kind(value), message)

    def test_load_cases_refuses_a_non_object_first_element(self):
        path = write_cases(self.tmp / "c.json", [None, self._offline(case_id="healthy")])
        with self.assertRaises(CaseError) as ctx:
            cases.load_cases(path)
        self.assertIn("cases[0]", str(ctx.exception))

    def test_load_cases_reports_every_offending_index(self):
        path = write_cases(self.tmp / "c.json", [None, self._offline(case_id="healthy"), 7])
        with self.assertRaises(CaseError) as ctx:
            cases.load_cases(path)
        message = str(ctx.exception)
        self.assertIn("cases[0]", message)
        self.assertIn("cases[2]", message)

    def test_load_cases_still_accepts_an_all_object_file(self):
        path = write_cases(self.tmp / "c.json", [self._offline(case_id="a"), self._offline(case_id="b")])
        doc = cases.load_cases(path)
        self.assertEqual(len(doc["cases"]), 2)

    def test_validate_case_direct_call_on_non_object_is_controlled(self):
        for value in self.NON_OBJECTS:
            with self.subTest(value=value):
                for mode in ("offline", "product"):
                    errors = cases.validate_case(value, mode)
                    self.assertTrue(errors, "expected a controlled refusal")
                    self.assertIn("case must be a JSON object", errors[0])
                    self.assertIn(self._kind(value), errors[0])

    def test_identity_fields_must_be_non_empty_strings(self):
        for field in ("case_id", "case_version", "scenario_id", "prompt", "source_group"):
            for value in (None, [], {}, 123, True, ""):
                with self.subTest(field=field, value=value):
                    errors = cases.validate_case(self._offline(**{field: value}), "offline")
                    self.assertTrue(errors, "expected a controlled refusal")
                    self.assertTrue(any(field in e for e in errors), errors)

    def test_missing_identity_fields_still_report_missing_field(self):
        for field in ("case_id", "case_version", "scenario_id", "prompt", "source_group"):
            with self.subTest(field=field):
                case = self._offline()
                case.pop(field)
                errors = cases.validate_case(case, "offline")
                self.assertIn(f"missing field: {field}", errors)

    def test_passthrough_containers_must_be_objects(self):
        for field in ("expected", "roles", "interaction_script"):
            for value in (None, [], 123, True, "text"):
                with self.subTest(field=field, value=value):
                    for mode in ("offline", "product"):
                        base = self._offline() if mode == "offline" else make_case()
                        base[field] = value
                        errors = cases.validate_case(base, mode)
                        self.assertTrue(errors, "expected a controlled refusal")
                        self.assertTrue(any(field in e for e in errors), errors)

    def test_clarification_replies_must_be_a_string_list(self):
        for value in (None, {}, 123, True, "text", [123], [None], [{}], [[]]):
            with self.subTest(value=value):
                case = self._offline(interaction_script={"clarification_replies": value})
                errors = cases.validate_case(case, "offline")
                self.assertTrue(errors, "expected a controlled refusal")
                self.assertTrue(any("clarification_replies" in e for e in errors), errors)

    def test_empty_clarification_replies_stay_legal(self):
        case = self._offline(interaction_script={"clarification_replies": []})
        self.assertEqual(cases.validate_case(case, "offline"), [])

    def test_absent_clarification_replies_stay_legal(self):
        case = self._offline(interaction_script={})
        self.assertEqual(cases.validate_case(case, "offline"), [])

    def test_needs_clarification_with_bad_replies_is_refused_before_a_trial_exists(self):
        bad = self._offline(case_id="bad", offline_simulation={"scenario": "needs_clarification"},
                            interaction_script={"clarification_replies": [123]})
        mixed = write_cases(self.tmp / "mixed.json", [self._offline(case_id="healthy"), bad])
        out = self.tmp / "out"
        code, so, se = cli("run", "--mode", "offline", "--cases", str(mixed), "--output", str(out))
        self.assertEqual(code, 2, so + se)
        self.assertNotIn("Traceback", so + se)
        self.assertFalse(out.exists())

    def test_needs_clarification_with_legal_replies_still_recovers(self):
        case = self._offline(case_id="ok", offline_simulation={"scenario": "needs_clarification"},
                             interaction_script={"clarification_replies": ["最近7天，含昨天。"]})
        path = write_cases(self.tmp / "ok.json", [case])
        out = self.tmp / "out"
        code, so, se = cli("run", "--mode", "offline", "--cases", str(path), "--output", str(out))
        self.assertEqual(code, 0, so + se)
        self.assertEqual(len(list((out / "trials").iterdir())), 1)

    def test_cli_refuses_non_object_elements_on_all_four_entry_points(self):
        bundle = make_bundle(self.tmp / "b", bundle_id="b1")
        for value in (None, [], "text", 7, True):
            with self.subTest(value=value):
                offline = write_cases(self.tmp / "off.json", [self._offline(case_id="healthy"), value])
                product = write_cases(self.tmp / "prod.json", [make_case(case_id="healthy"), value])
                for args in (["validate", "--cases", str(offline), "--mode", "offline"],
                             ["validate", "--cases", str(product), "--mode", "product"],
                             ["run", "--mode", "offline", "--cases", str(offline),
                              "--output", str(self.tmp / "o-run")],
                             ["import-product", "--cases", str(product), "--bundle", str(bundle),
                              "--output", str(self.tmp / "o-import")]):
                    code, so, se = cli(*args)
                    self.assertEqual(code, 2, so + se)
                    self.assertNotIn("Traceback", so + se)
                    self.assertIn("cases[1]", so + se)
        self.assertFalse((self.tmp / "o-run").exists())
        self.assertFalse((self.tmp / "o-import").exists())


class Repair05TimeWindowTest(TempDirTest):
    """B: timezone, clock and window are type-checked and computable before any date arithmetic."""

    def setUp(self):
        super().setUp()
        from report_evaluation import timebase
        self.timebase = timebase

    def _clock(self, **over):
        clock = {"reference_time": "2026-11-10 10:00:00", "timezone": "Asia/Shanghai"}
        clock.update(over)
        return clock

    def _offline(self, **over):
        case = make_case()
        case.pop("product_capture")
        case["offline_simulation"] = {"scenario": "success"}
        case.update(over)
        return case

    def test_get_timezone_refuses_non_strings_without_hashing_them(self):
        for value in ({}, [], ["Asia/Shanghai"], 123, True, None):
            with self.subTest(value=value):
                with self.assertRaises(CaseError):
                    self.timebase.get_timezone(value)

    def test_get_timezone_keeps_legal_zones_and_offsets(self):
        for name, hours in (("Asia/Shanghai", 8), ("UTC", 0), ("Etc/UTC", 0)):
            with self.subTest(name=name):
                tz = self.timebase.get_timezone(name)
                self.assertEqual(datetime(2026, 11, 10, tzinfo=tz).utcoffset(), timedelta(hours=hours))

    def test_get_timezone_unknown_string_message_is_unchanged(self):
        with self.assertRaises(CaseError) as ctx:
            self.timebase.get_timezone("Mars/Olympus")
        self.assertEqual(str(ctx.exception), "unknown timezone: 'Mars/Olympus'")

    def test_parse_dt_refuses_non_strings(self):
        tz = self.timebase.get_timezone("UTC")
        for value in (None, [], {}, 123, True):
            with self.subTest(value=value):
                with self.assertRaises(CaseError):
                    self.timebase.parse_dt(value, tz)

    def test_parse_dt_keeps_legal_formats(self):
        tz = self.timebase.get_timezone("Asia/Shanghai")
        self.assertEqual(self.timebase.parse_dt("2026-11-10 10:00:00", tz).isoformat(),
                         "2026-11-10T10:00:00+08:00")
        self.assertEqual(self.timebase.parse_dt("2026-11-10", tz).isoformat(),
                         "2026-11-10T00:00:00+08:00")

    def test_parse_dt_unparsable_string_message_is_unchanged(self):
        tz = self.timebase.get_timezone("UTC")
        with self.assertRaises(CaseError) as ctx:
            self.timebase.parse_dt("not-a-date", tz)
        self.assertEqual(str(ctx.exception), "bad datetime: 'not-a-date'")

    def test_resolve_window_refuses_non_object_clock_and_spec(self):
        for clock in (None, [], "x", 7, True):
            with self.subTest(clock=clock):
                with self.assertRaises(CaseError):
                    self.timebase.resolve_window(clock, {"kind": "relative", "days": 7})
        for spec in (None, [], "x", 7, True):
            with self.subTest(spec=spec):
                with self.assertRaises(CaseError):
                    self.timebase.resolve_window(self._clock(), spec)

    def test_resolve_window_refuses_missing_and_wrong_typed_clock_fields(self):
        for over in ({"timezone": None}, {"timezone": []}, {"timezone": {}}, {"timezone": 5},
                     {"timezone": True}, {"timezone": ""},
                     {"reference_time": None}, {"reference_time": []}, {"reference_time": 5},
                     {"reference_time": True}, {"reference_time": ""}):
            with self.subTest(over=over):
                with self.assertRaises(CaseError):
                    self.timebase.resolve_window(self._clock(**over), {"kind": "relative", "days": 7})
        for field in ("timezone", "reference_time"):
            clock = self._clock()
            clock.pop(field)
            with self.subTest(missing=field):
                with self.assertRaises(CaseError):
                    self.timebase.resolve_window(clock, {"kind": "relative", "days": 7})

    def test_absolute_window_requires_both_bounds_as_strings(self):
        for spec in ({"kind": "absolute"}, {"kind": "absolute", "start": "2026-11-05"},
                     {"kind": "absolute", "start": [], "end": "2026-11-12"},
                     {"kind": "absolute", "start": "2026-11-05", "end": {}},
                     {"kind": "absolute", "start": 5, "end": 7},
                     {"kind": "absolute", "start": True, "end": False}):
            with self.subTest(spec=spec):
                with self.assertRaises(CaseError):
                    self.timebase.resolve_window(self._clock(), spec)

    def test_boolean_days_is_rejected_in_both_polarities(self):
        for days in (True, False):
            with self.subTest(days=days):
                with self.assertRaises(CaseError) as ctx:
                    self.timebase.resolve_window(self._clock(), {"kind": "relative", "days": days})
                self.assertIn("positive integer", str(ctx.exception))

    def test_non_integer_and_non_positive_days_are_rejected(self):
        for days in (None, [], {}, "7", 7.5, 0, -3):
            with self.subTest(days=days):
                with self.assertRaises(CaseError):
                    self.timebase.resolve_window(self._clock(), {"kind": "relative", "days": days})

    def test_missing_days_is_a_case_error(self):
        with self.assertRaises(CaseError):
            self.timebase.resolve_window(self._clock(), {"kind": "relative"})

    def test_huge_days_is_a_case_error_not_an_overflow(self):
        with self.assertRaises(CaseError):
            self.timebase.resolve_window(self._clock(), {"kind": "relative", "days": 10 ** 12})

    def test_date_boundary_overflow_is_a_case_error(self):
        for ref, anchor in (("0001-01-01", "yesterday"), ("9999-12-31", "reference_day")):
            with self.subTest(ref=ref, anchor=anchor):
                with self.assertRaises(CaseError):
                    self.timebase.resolve_window(self._clock(reference_time=ref),
                                                 {"kind": "relative", "days": 7, "anchor": anchor})

    def test_legal_relative_windows_are_unchanged(self):
        for days in (1, 7, 3650):
            for anchor in ("yesterday", "reference_day"):
                with self.subTest(days=days, anchor=anchor):
                    got = self.timebase.resolve_window(self._clock(),
                                                       {"kind": "relative", "days": days,
                                                        "anchor": anchor})
                    self.assertEqual(got["basis"], f"relative:{anchor}:{days}d")
                    self.assertEqual(got["timezone"], "Asia/Shanghai")
                    self.assertLess(got["start"], got["end"])

    def test_legal_absolute_window_is_unchanged(self):
        got = self.timebase.resolve_window(self._clock(),
                                           {"kind": "absolute", "start": "2026-11-05",
                                            "end": "2026-11-12"})
        self.assertEqual(got["basis"], "absolute")
        self.assertEqual(got["start"], "2026-11-05T00:00:00+08:00")
        self.assertEqual(got["end"], "2026-11-12T00:00:00+08:00")

    def test_default_anchor_is_still_reference_day(self):
        got = self.timebase.resolve_window(self._clock(), {"kind": "relative", "days": 7})
        self.assertEqual(got["basis"], "relative:reference_day:7d")

    def test_unknown_kind_and_anchor_messages_are_unchanged(self):
        with self.assertRaises(CaseError) as ctx:
            self.timebase.resolve_window(self._clock(), {"kind": "sideways"})
        self.assertEqual(str(ctx.exception), "unknown window kind: 'sideways'")
        with self.assertRaises(CaseError) as ctx:
            self.timebase.resolve_window(self._clock(),
                                         {"kind": "relative", "days": 7, "anchor": "tomorrow"})
        self.assertEqual(str(ctx.exception), "unknown relative anchor: 'tomorrow'")

    def test_start_not_before_end_message_is_unchanged(self):
        with self.assertRaises(CaseError) as ctx:
            self.timebase.resolve_window(self._clock(),
                                         {"kind": "absolute", "start": "2026-11-12",
                                          "end": "2026-11-05"})
        self.assertEqual(str(ctx.exception), "window start must be before end")

    def test_case_level_time_fields_are_locatable(self):
        for field, value in (("timezone", {}), ("timezone", []), ("task_clock", []),
                             ("task_clock", None), ("task_window", []), ("task_window", "x")):
            with self.subTest(field=field, value=value):
                errors = cases.validate_case(self._offline(**{field: value}), "offline")
                self.assertTrue(errors, "expected a controlled refusal")
                self.assertTrue(any(field in e for e in errors), errors)

    def test_case_level_boolean_days_is_refused(self):
        errors = cases.validate_case(self._offline(task_window={"kind": "relative", "days": True,
                                                                "anchor": "yesterday"}), "offline")
        self.assertTrue(any("task_window" in e for e in errors), errors)

    def test_legal_time_fields_still_validate_and_fingerprint_identically(self):
        before = cases.case_fingerprint(self._offline())
        for window in ({"kind": "relative", "days": 1, "anchor": "yesterday"},
                       {"kind": "relative", "days": 3650, "anchor": "reference_day"},
                       {"kind": "absolute", "start": "2026-11-05", "end": "2026-11-12"}):
            with self.subTest(window=window):
                case = self._offline(task_window=window)
                self.assertEqual(cases.validate_case(case, "offline"), [])
                self.assertEqual(len(cases.case_fingerprint(case)), 64)
        self.assertEqual(cases.case_fingerprint(self._offline()), before)


class Repair05MaterialTest(TempDirTest):
    """C: material is consumable before Path(), iteration, .encode(), file reads or fingerprinting."""

    def _fixture(self):
        root = self.tmp / "ds"
        (root / "评论").mkdir(parents=True, exist_ok=True)
        (root / "弹幕").mkdir(parents=True, exist_ok=True)
        manifest = "video_key,title\ndemo-fix-a,私有合成对照A\n"
        (root / "视频清单.csv").write_bytes(manifest.encode("utf-8"))
        for key in ("demo-fix-a", "demo-fix-b"):
            (root / "评论" / f"{key}_评论.csv").write_bytes(
                f"comment_id,video_key,text\nc1,{key},私有合成评论\n".encode("utf-8"))
            (root / "弹幕" / f"{key}_弹幕.csv").write_bytes(
                f"danmaku_id,video_key,text\nd1,{key},私有合成弹幕\n".encode("utf-8"))
        (root / "video.mp4").write_bytes(b"private synthetic fixture, not real media")
        return root, hashlib.sha256((root / "视频清单.csv").read_bytes()).hexdigest()

    def _dataset(self, **over):
        root, digest = self._fixture()
        case = make_case()
        case.pop("product_capture")
        case["offline_simulation"] = {"scenario": "success"}
        case["material"] = {"kind": "dataset_ref", "dataset_root": str(root), "keys": ["demo-fix-a"],
                            "manifest_sha256": digest,
                            "effective_timeline": {"demo-fix-a": {"start": "2026-11-03",
                                                                  "end": "2026-11-10"}}}
        case["material"].update(over)
        return case

    def _inline(self, **over):
        case = make_case()
        case.pop("product_capture")
        case["offline_simulation"] = {"scenario": "success"}
        case["material"].update(over)
        return case

    def test_non_object_material_is_a_field_problem_in_both_modes(self):
        for value in (None, [], 123, True, "text"):
            with self.subTest(value=value):
                for mode in ("offline", "product"):
                    base = make_case() if mode == "product" else self._inline()
                    base["material"] = value
                    errors = cases.validate_case(base, mode)
                    self.assertTrue(errors, "expected a controlled refusal")
                    self.assertTrue(any("material" in e for e in errors), errors)

    def test_inline_content_must_be_a_string(self):
        for value in (None, [], {}, 123, True):
            with self.subTest(value=value):
                errors = cases.validate_case(self._inline(content=value), "offline")
                self.assertTrue(any("content must be a string" in e for e in errors), errors)

    def test_inline_digest_must_be_a_string(self):
        for value in (None, [], {}, 123, True):
            with self.subTest(value=value):
                errors = cases.validate_case(self._inline(content_sha256=value), "offline")
                self.assertTrue(any("content_sha256 must be a string" in e for e in errors), errors)

    def test_empty_content_with_a_matching_hash_stays_legal(self):
        empty = hashlib.sha256(b"").hexdigest()
        case = self._inline(content="", content_sha256=empty)
        self.assertEqual(cases.validate_case(case, "offline"), [])
        self.assertEqual(len(cases.case_fingerprint(case)), 64)

    def test_mismatched_digest_message_is_unchanged(self):
        errors = cases.validate_case(self._inline(content_sha256="0" * 64), "offline")
        self.assertIn("synthetic_inline content_sha256 does not match content", errors)

    def test_dataset_root_must_be_a_non_empty_string(self):
        for value in (None, [], {}, 123, True, ""):
            with self.subTest(value=value):
                errors = cases.validate_case(self._dataset(dataset_root=value), "offline")
                self.assertTrue(any("dataset_root" in e for e in errors), errors)

    def test_dataset_keys_container_and_elements_are_checked(self):
        for value in (None, {}, 123, True, "demo-fix-a", [], [123], [None], [{}], [[]], [""]):
            with self.subTest(value=value):
                errors = cases.validate_case(self._dataset(keys=value), "offline")
                self.assertTrue(any("keys" in e for e in errors), errors)

    def test_duplicate_keys_are_refused_not_silently_deduplicated(self):
        errors = cases.validate_case(self._dataset(keys=["demo-fix-a", "demo-fix-a"]), "offline")
        self.assertTrue(any("keys" in e for e in errors), errors)

    def test_manifest_digest_must_be_a_string(self):
        for value in (None, [], {}, 123, True):
            with self.subTest(value=value):
                errors = cases.validate_case(self._dataset(manifest_sha256=value), "offline")
                self.assertTrue(any("manifest_sha256" in e for e in errors), errors)

    def test_video_path_must_be_a_string_when_present(self):
        for value in (None, [], {}, 123, True):
            with self.subTest(value=value):
                errors = cases.validate_case(self._dataset(video_path=value), "offline")
                self.assertTrue(any("video_path" in e for e in errors), errors)

    def test_absent_video_path_stays_legal_because_it_means_no_video(self):
        case = self._dataset()
        self.assertNotIn("video_path", case["material"])
        self.assertEqual(cases.validate_case(case, "offline"), [])
        binding = cases.resolve_material_inventory(case)
        self.assertIsNone(binding["dataset"]["videos"]["demo-fix-a"]["video_sha256"])

    def test_effective_timeline_accepts_both_legal_shapes(self):
        for value in ({"demo-fix-a": {"start": "2026-11-03", "end": "2026-11-10"}}, "shifted"):
            with self.subTest(value=value):
                self.assertEqual(cases.validate_case(self._dataset(effective_timeline=value),
                                                     "offline"), [])

    def test_missing_effective_timeline_is_still_refused(self):
        case = self._dataset()
        case["material"].pop("effective_timeline")
        errors = cases.validate_case(case, "offline")
        self.assertIn("dataset_ref material missing: effective_timeline", errors)

    def test_resolve_material_inventory_raises_case_error_on_unconsumable_material(self):
        for material in (None, [], "text",
                         {"kind": "dataset_ref", "dataset_root": [], "keys": ["demo-fix-a"]},
                         {"kind": "dataset_ref", "dataset_root": str(self.tmp), "keys": "demo-fix-a"},
                         {"kind": "dataset_ref", "dataset_root": str(self.tmp), "keys": [{}]}):
            with self.subTest(material=material):
                case = make_case()
                case["material"] = material
                with self.assertRaises(CaseError):
                    cases.resolve_material_inventory(case)

    def test_case_fingerprint_raises_case_error_on_unconsumable_material(self):
        for material in (None, [],
                         {"kind": "dataset_ref", "dataset_root": str(self.tmp), "keys": [123],
                          "manifest_sha256": "0" * 64, "effective_timeline": {}},
                         {"kind": "synthetic_inline", "content_sha256": []}):
            with self.subTest(material=material):
                case = make_case()
                case["material"] = material
                with self.assertRaises(CaseError):
                    cases.case_fingerprint(case)

    def test_legal_dataset_binding_and_fingerprint_are_stable(self):
        case = self._dataset()
        self.assertEqual(cases.validate_case(case, "offline"), [])
        binding = cases.resolve_material_inventory(case)
        self.assertIn("demo-fix-a", binding["dataset"]["videos"])
        first = cases.case_fingerprint(case, inventory=binding)
        self.assertEqual(first, cases.case_fingerprint(case, inventory=binding))
        self.assertEqual(len(first), 64)
        errors, fp, bound = cases.prepare_case(case, "offline")
        self.assertEqual(errors, [])
        self.assertEqual(fp, first)
        self.assertEqual(bound, binding)

    def test_two_legal_keys_bind_both_without_inventing_samples(self):
        case = self._dataset(keys=["demo-fix-a", "demo-fix-b"])
        self.assertEqual(cases.validate_case(case, "offline"), [])
        binding = cases.resolve_material_inventory(case)
        self.assertEqual(sorted(binding["dataset"]["videos"]), ["demo-fix-a", "demo-fix-b"])

    def test_legal_video_path_is_hashed_into_the_binding(self):
        root, _ = self._fixture()
        case = self._dataset(video_path=str(root / "video.mp4"))
        self.assertEqual(cases.validate_case(case, "offline"), [])
        binding = cases.resolve_material_inventory(case)
        self.assertIsNotNone(binding["dataset"]["videos"]["demo-fix-a"]["video_sha256"])

    def test_dataset_counterexamples_never_write_outside_their_own_directory(self):
        case = self._dataset(dataset_root=str(self.tmp / "escaped"))
        before = {p for p in self.tmp.rglob("*")}
        cases.validate_case(case, "offline")
        self.assertEqual({p for p in self.tmp.rglob("*")}, before)
        self.assertFalse((self.tmp / "escaped").exists())


class Repair05PermissionsTest(TempDirTest):
    """D: permission declarations are JSON booleans, not Python truthiness."""

    FALSEY_WRONG = ([], {}, 0, "", 0.0)
    TRUTHY_WRONG = (["x"], {"a": 1}, 1, "yes", 2.5)

    def _offline(self, **over):
        case = make_case()
        case.pop("product_capture")
        case["offline_simulation"] = {"scenario": "success"}
        case.update(over)
        return case

    def test_permission_container_must_be_an_object(self):
        for value in (None, [], 123, True, "text"):
            with self.subTest(value=value):
                for mode in ("offline", "product"):
                    base = self._offline() if mode == "offline" else make_case()
                    base["tool_permissions"] = value
                    errors = cases.validate_case(base, mode)
                    self.assertTrue(errors, "expected a controlled refusal")
                    self.assertTrue(any("tool_permissions" in e for e in errors), errors)

    def test_falsey_wrong_typed_permissions_are_rejected(self):
        for field in ("network", "ssh", "read_dataset"):
            for value in self.FALSEY_WRONG:
                with self.subTest(field=field, value=value):
                    perms = {"read_dataset": False, "network": False, "ssh": False}
                    perms[field] = value
                    errors = cases.validate_case(self._offline(tool_permissions=perms), "offline")
                    self.assertTrue(any(field in e for e in errors), errors)

    def test_truthy_wrong_typed_permissions_are_rejected(self):
        for field in ("network", "ssh", "read_dataset"):
            for value in self.TRUTHY_WRONG:
                with self.subTest(field=field, value=value):
                    perms = {"read_dataset": False, "network": False, "ssh": False}
                    perms[field] = value
                    errors = cases.validate_case(self._offline(tool_permissions=perms), "offline")
                    self.assertTrue(any(field in e for e in errors), errors)

    def test_network_access_must_be_a_json_boolean(self):
        for value in self.FALSEY_WRONG + self.TRUTHY_WRONG + (None,):
            with self.subTest(value=value):
                errors = cases.validate_case(self._offline(network_access=value), "offline")
                self.assertTrue(any("network_access" in e for e in errors), errors)

    def test_true_permissions_are_still_refused_by_mode_with_the_original_messages(self):
        for field in ("network", "ssh"):
            perms = {"read_dataset": False, "network": False, "ssh": False}
            perms[field] = True
            with self.subTest(field=field):
                self.assertIn("offline mode forbids network/ssh access",
                              cases.validate_case(self._offline(tool_permissions=perms), "offline"))
                product = make_case(tool_permissions=perms)
                self.assertIn("product capture mode forbids network/ssh access",
                              cases.validate_case(product, "product"))
        self.assertIn("offline mode forbids network/ssh access",
                      cases.validate_case(self._offline(network_access=True), "offline"))

    def test_false_and_absent_permissions_stay_legal(self):
        self.assertEqual(cases.validate_case(self._offline(), "offline"), [])
        case = self._offline()
        case.pop("tool_permissions")
        self.assertEqual(cases.validate_case(case, "offline"), [])
        self.assertEqual(cases.validate_case(self._offline(network_access=False), "offline"), [])

    def test_read_dataset_true_stays_legal_because_it_is_type_checked_only(self):
        perms = {"read_dataset": True, "network": False, "ssh": False}
        self.assertEqual(cases.validate_case(self._offline(tool_permissions=perms), "offline"), [])

    def test_permission_type_errors_do_not_change_a_legal_fingerprint(self):
        before = cases.case_fingerprint(self._offline())
        self.assertEqual(cases.case_fingerprint(self._offline()), before)
        self.assertEqual(len(before), 64)


class Repair05ErrorLayeringTest(TempDirTest):
    """E: an illegal case file refuses as a whole; a bad capture still isolates per trial."""

    def _offline(self, **over):
        case = make_case()
        case.pop("product_capture")
        case["offline_simulation"] = {"scenario": "success"}
        case.update(over)
        return case

    def _hashes(self, root):
        if not root.exists():
            return {}
        return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in root.rglob("*") if p.is_file()}

    def test_offline_case_refusal_creates_no_output_and_no_summary(self):
        bad = self._offline(case_id="bad", task_window=[])
        mixed = write_cases(self.tmp / "m.json", [self._offline(case_id="healthy"), bad])
        out = self.tmp / "out"
        code, so, se = cli("run", "--mode", "offline", "--cases", str(mixed), "--output", str(out))
        self.assertEqual(code, 2, so + se)
        self.assertNotIn("Traceback", so + se)
        self.assertFalse(out.exists())

    def test_product_case_refusal_creates_no_output_and_no_summary(self):
        bundle = make_bundle(self.tmp / "b", bundle_id="b1")
        bad = make_case(case_id="bad", task_window=[])
        mixed = write_cases(self.tmp / "m.json", [make_case(case_id="healthy"), bad])
        out = self.tmp / "out"
        code, so, se = cli("import-product", "--cases", str(mixed), "--bundle", str(bundle),
                           "--output", str(out))
        self.assertEqual(code, 2, so + se)
        self.assertNotIn("Traceback", so + se)
        self.assertFalse(out.exists())

    def test_case_refusal_leaves_existing_output_byte_identical(self):
        out = self.tmp / "out"
        healthy = write_cases(self.tmp / "h.json", [self._offline(case_id="healthy")])
        code, so, se = cli("run", "--mode", "offline", "--cases", str(healthy), "--output", str(out))
        self.assertEqual(code, 0, so + se)
        before = self._hashes(out)
        self.assertTrue(before)
        bad = self._offline(case_id="bad", material=[])
        mixed = write_cases(self.tmp / "m.json", [self._offline(case_id="healthy2"), bad])
        code, so, se = cli("run", "--mode", "offline", "--cases", str(mixed), "--output", str(out))
        self.assertEqual(code, 2, so + se)
        self.assertEqual(self._hashes(out), before)

    def test_healthy_file_alone_still_runs(self):
        path = write_cases(self.tmp / "h.json", [self._offline(case_id="healthy")])
        out = self.tmp / "out"
        code, so, se = cli("run", "--mode", "offline", "--cases", str(path), "--output", str(out))
        self.assertEqual(code, 0, so + se)
        self.assertEqual(len(list((out / "trials").iterdir())), 1)

    def test_bad_capture_still_isolates_per_trial_with_a_summary(self):
        good = make_bundle(self.tmp / "good", bundle_id="b1")
        broken = make_bundle(self.tmp / "broken", bundle_id="b2", json_bytes=b"{not json")
        path = write_cases(self.tmp / "c.json", [make_case(case_id="good", bundle_id="b1"),
                                               make_case(case_id="bad", bundle_id="b2")])
        out = self.tmp / "out"
        code, so, se = cli("import-product", "--cases", str(path), "--bundle", str(good),
                           "--bundle", str(broken), "--output", str(out))
        self.assertEqual(code, 3, so + se)
        self.assertTrue((out / "summary.json").is_file())
        self.assertEqual(len(list((out / "trials").iterdir())), 2)

    def test_product_mode_still_ignores_offline_simulation_entirely(self):
        baseline = cases.validate_case(make_case(), "product")
        for value in ([], "success", None, {}, {"scenario": {}}, {"scenario": []}):
            with self.subTest(value=value):
                case = make_case()
                case["offline_simulation"] = value
                self.assertEqual(cases.validate_case(case, "product"), baseline)


if __name__ == "__main__":
    unittest.main()