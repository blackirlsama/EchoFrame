"""Strict validation of product report capture bundles (product-bundle-v1).

A bundle holds the bytes the product ReportController actually serialized under demo/mock.
Nothing here invents a report: load_bundle() reads each file once, hashes exactly those bytes
and parses the same snapshot. bind_case() compares the product's own scope against the case
expectation, so a bundle can never certify its own source range.
"""
import hashlib
import json
import re
from datetime import datetime
from pathlib import Path

from . import timebase
from .util import CaseError, OutOfBoundsError, ReportEvalError, canonical_json, ensure_real_path

ADAPTER_VERSION = "product-adapter-v1"
EVIDENCE_LEVEL = "product_http_mockmvc"
BUNDLE_CONTRACT = "product-bundle-v1"
PRODUCT_SCHEMA = "feedback-report-json-v1"
TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_INPUT"}
SOURCES = {"COMMENT", "DANMAKU"}
GROUNDINGS = {"FEEDBACK_ONLY", "VIDEO_GROUNDED"}
RECEIPT_TOOLS = {"generate_feedback_report", "create_feedback_report"}
ACCESS_EXPECTATIONS = {
    "unauthenticated": {401},
    "cross_account": {403, 404},
    "unknown_artifact": {400, 404},
    "revoked_authorization": {403},
}
_VIDEO_ID = re.compile(r"[1-9][0-9]{0,18}\Z")
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_RUN_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")


class BundleError(ReportEvalError):
    pass


class TraceBundleError(BundleError):
    """Only the Trace axis failed. The report originals were independently verified.

    Raised so load_bundle stays strict for existing callers, while the bridge can recover the
    verified report instead of discarding a valid delivery over a process-evidence fault.
    """

    def __init__(self, message, bundle):
        super().__init__(message)
        self.bundle = bundle


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def _sha(data):
    return hashlib.sha256(data).hexdigest()


class Bundle:
    def __init__(self, anchor, manifest, manifest_bytes, json_bytes, html_bytes, trace_bytes, report,
                 delivery_problems=(), trace_problems=()):
        self.anchor = anchor
        self.manifest = manifest
        self.manifest_bytes = manifest_bytes
        self.json_bytes = json_bytes
        self.html_bytes = html_bytes
        self.trace_bytes = trace_bytes
        self.report = report
        # A missing artifact is a delivery failure on a structurally sound bundle, not corruption.
        self.delivery_problems = list(delivery_problems)
        # Trace faults never touch delivery; they make usage unknown on this trial only.
        self.trace_problems = list(trace_problems)

    @property
    def bundle_id(self):
        return self.manifest["bundle_id"]

    @property
    def final_status(self):
        return self.manifest["run"]["final_status"]

    @property
    def delivered(self):
        return self.final_status == "SUCCEEDED" and self.json_bytes is not None


def _check_str(manifest, key, problems, nullable=False):
    value = manifest.get(key)
    if value is None and nullable:
        return
    if not isinstance(value, str) or not value.strip():
        problems.append(f"bad_{key}")


def _check_enum(value, allowed, problem, problems):
    # Membership on a set raises TypeError for a dict/list, which would abort the whole batch
    # before any diagnostic is recorded. The type test must come first.
    if not isinstance(value, str) or value not in allowed:
        problems.append(problem)


def _validate_manifest(manifest, problems):
    if not _is_int(manifest.get("schema_version")) or manifest["schema_version"] != 1:
        problems.append("unknown_bundle_schema")
    for key in ("bundle_id", "adapter_contract", "evidence_level", "material_class"):
        _check_str(manifest, key, problems)
    if manifest.get("adapter_contract") != BUNDLE_CONTRACT:
        problems.append("unknown_adapter_contract")
    if manifest.get("evidence_level") != EVIDENCE_LEVEL:
        problems.append("unknown_evidence_level")
    if manifest.get("material_class") != "synthetic_fixture":
        problems.append("unexpected_material_class")

    producer = manifest.get("producer")
    if not isinstance(producer, dict):
        problems.append("bad_producer")
    else:
        for key in ("kind", "test_class", "test_method", "maven_command", "captured_at", "spring_profile"):
            _check_str(producer, key, problems)
        if producer.get("kind") != EVIDENCE_LEVEL:
            problems.append("unknown_producer_kind")

    request = manifest.get("request")
    if not isinstance(request, dict):
        problems.append("bad_request")
    else:
        _check_str(request, "prompt", problems)
        _check_str(request, "owner_token", problems)
        if not isinstance(request.get("video_id"), str) or not _VIDEO_ID.match(request["video_id"]):
            problems.append("bad_request_video_id")
        _validate_sources(request.get("sources"), problems, "request")
        _check_enum(request.get("grounding"), GROUNDINGS, "bad_request_grounding", problems)

    run = manifest.get("run")
    if not isinstance(run, dict):
        problems.append("bad_run")
        return
    if not isinstance(run.get("run_id"), str) or not _RUN_ID.match(run["run_id"]):
        problems.append("bad_run_id")
    status = run.get("final_status")
    _check_enum(status, TERMINAL_STATUSES, "unknown_terminal_status", problems)
    for key in ("clarification", "error_code", "artifact_id", "evidence_id", "receipt_tool",
                "json_url", "html_url"):
        if key in run and run[key] is not None and not isinstance(run[key], str):
            problems.append(f"bad_run_{key}")
    if "receipt" in run and run["receipt"] is not None and not isinstance(run["receipt"], dict):
        problems.append("bad_run_receipt")
    if status == "SUCCEEDED":
        for key in ("artifact_id", "evidence_id", "json_url", "html_url"):
            if not isinstance(run.get(key), str) or not run[key].strip():
                problems.append(f"missing_run_{key}")
        tool = run.get("receipt_tool")
        _check_enum(tool, RECEIPT_TOOLS, "bad_receipt_tool", problems)
        receipt = run.get("receipt")
        if not isinstance(receipt, dict):
            problems.append("missing_receipt")
        else:
            _validate_receipt(receipt, run, problems)
        run_id, artifact = run.get("run_id"), run.get("artifact_id")
        if isinstance(run_id, str) and isinstance(artifact, str):
            base = f"/api/agent/runs/{run_id}/reports/{artifact}"
            for key, expected in (("json_url", base + "/json"), ("html_url", base)):
                url = run.get(key)
                if isinstance(url, str) and url != expected:
                    problems.append(f"artifact_url_mismatch:{key}")
    elif run.get("artifact_id") is not None:
        problems.append("unexpected_artifact_without_success")


_RECEIPT_COMMON = ("status", "evidence_id", "artifact_id", "url", "demo")
_RECEIPT_GENERATE_EXTRA = ("json_url", "summary", "topics", "method")
# FeedbackOperationCoordinator.deliver() is the only place the product builds a receipt. The
# required set is what that method actually puts in the map, per tool.
_RECEIPT_REQUIRED = {"generate_feedback_report": _RECEIPT_COMMON + _RECEIPT_GENERATE_EXTRA,
                     "create_feedback_report": _RECEIPT_COMMON}
_RECEIPT_RUN_FIELD = {"evidence_id": "evidence_id", "artifact_id": "artifact_id",
                      "url": "html_url", "json_url": "json_url"}
_RECEIPT_MISMATCH = {"evidence_id": "receipt_evidence_id_mismatch",
                     "artifact_id": "receipt_artifact_mismatch",
                     "url": "receipt_url_mismatch", "json_url": "receipt_json_url_mismatch"}


def _validate_receipt(receipt, run, problems):
    """A key the product always sends cannot be dropped to escape its own check.

    Extras a create_feedback_report receipt happens to carry are still validated; they are simply
    not demanded, because the product does not emit them for that tool.
    """
    for key in _RECEIPT_REQUIRED.get(run.get("receipt_tool"), _RECEIPT_COMMON):
        if key not in receipt:
            problems.append(f"receipt_missing_{key}")
    if "status" in receipt and receipt["status"] != "READY":
        problems.append("receipt_not_ready")
    for key in ("evidence_id", "artifact_id", "url", "json_url"):
        if key not in receipt:
            continue
        value = receipt[key]
        if not isinstance(value, str) or not value.strip():
            problems.append(f"receipt_bad_{key}")
            continue
        if value != run.get(_RECEIPT_RUN_FIELD[key]):
            problems.append(_RECEIPT_MISMATCH[key])
    if "demo" in receipt and not isinstance(receipt["demo"], bool):
        problems.append("receipt_bad_demo")
    if "summary" in receipt and not isinstance(receipt["summary"], str):
        problems.append("receipt_bad_summary")
    if "topics" in receipt and (not isinstance(receipt["topics"], list)
                                or not all(isinstance(t, str) for t in receipt["topics"])):
        problems.append("receipt_bad_topics")
    if "method" in receipt and not isinstance(receipt["method"], dict):
        problems.append("receipt_bad_method")


def _validate_receipt_report(receipt, report, problems):
    """generate_feedback_report copies summary/topic titles/method straight out of the report JSON."""
    if not isinstance(receipt, dict) or not isinstance(report, dict):
        return
    if "summary" in receipt and receipt["summary"] != report.get("summary"):
        problems.append("receipt_summary_mismatch")
    if "method" in receipt and receipt["method"] != report.get("method"):
        problems.append("receipt_method_mismatch")
    if "topics" in receipt and isinstance(report.get("topics"), list):
        titles = [t.get("title") for t in report["topics"] if isinstance(t, dict)]
        if receipt["topics"] != titles:
            problems.append("receipt_topics_mismatch")


def _validate_sources(value, problems, where):
    if not isinstance(value, list) or not value:
        problems.append(f"bad_{where}_sources")
        return
    if not all(isinstance(s, str) for s in value):
        problems.append(f"unknown_{where}_source")
        return
    if not all(s in SOURCES for s in value):
        problems.append(f"unknown_{where}_source")
    if len(set(value)) != len(value):
        problems.append(f"duplicate_{where}_source")


def _validate_response(record, problems, where):
    if not isinstance(record, dict):
        problems.append(f"bad_response:{where}")
        return
    if not isinstance(record.get("path"), str) or not record["path"].strip():
        problems.append(f"bad_response_path:{where}")
    if not _is_int(record.get("http_status")) or record["http_status"] != 200:
        problems.append(f"response_not_200:{where}")
    content_type = record.get("content_type")
    expected = "application/json" if where == "json" else "text/html"
    if not isinstance(content_type, str) or expected not in content_type:
        problems.append(f"bad_content_type:{where}")
    if not isinstance(record.get("sha256"), str) or not _SHA256.match(record["sha256"]):
        problems.append(f"bad_response_sha256:{where}")
    if not _is_int(record.get("bytes")) or record["bytes"] < 0:
        problems.append(f"bad_response_bytes:{where}")


def _validate_access_checks(checks, problems, required):
    if not isinstance(checks, list):
        problems.append("bad_access_checks")
        return
    seen = {}
    for check in checks:
        if not isinstance(check, dict):
            problems.append("bad_access_check")
            continue
        name = check.get("name")
        if not isinstance(name, str) or name not in ACCESS_EXPECTATIONS:
            problems.append("unknown_access_check")
            continue
        if name in seen:
            problems.append("duplicate_access_check")
        seen[name] = True
        status = check.get("http_status")
        if not _is_int(status):
            problems.append(f"bad_access_status:{name}")
        elif status not in ACCESS_EXPECTATIONS[name]:
            problems.append(f"access_check_not_denied:{name}")
    if required:
        for name in ACCESS_EXPECTATIONS:
            if name not in seen:
                problems.append(f"missing_access_check:{name}")


def _validate_topic(topic, problems, where):
    if not isinstance(topic, dict):
        problems.append(f"topic_not_object:{where}")
        return
    for key in ("id", "title", "observation"):
        if not isinstance(topic.get(key), str) or not topic[key].strip():
            problems.append(f"topic_bad_{key}:{where}")
    if topic.get("recommendation") is not None and not isinstance(topic["recommendation"], str):
        problems.append(f"topic_bad_recommendation:{where}")
    for key in ("commentCount", "danmakuCount"):
        if not _is_int(topic.get(key)) or topic[key] < 0:
            problems.append(f"topic_bad_{key}:{where}")
    if not isinstance(topic.get("representatives"), list):
        problems.append(f"topic_bad_representatives:{where}")
    frames = topic.get("selectedFrameMs")
    if frames is not None and (not isinstance(frames, list) or not all(_is_int(f) and f >= 0 for f in frames)):
        problems.append(f"topic_bad_selectedFrameMs:{where}")


def _validate_report(report, problems):
    """Table-driven validation of exactly the fields the adapter and delivery check consume."""
    if not isinstance(report, dict):
        problems.append("report_not_object")
        return
    if report.get("schemaVersion") != PRODUCT_SCHEMA:
        problems.append("unknown_product_schema")
    for key in ("title", "summary"):
        if not isinstance(report.get(key), str) or not report[key].strip():
            problems.append(f"report_bad_{key}")
    for key in ("topics", "otherTopics", "unclassifiedExamples"):
        if not isinstance(report.get(key), list):
            problems.append(f"report_bad_{key}")
    if isinstance(report.get("topics"), list):
        for i, topic in enumerate(report["topics"]):
            _validate_topic(topic, problems, f"topics[{i}]")
    if isinstance(report.get("otherTopics"), list):
        for i, topic in enumerate(report["otherTopics"]):
            _validate_topic(topic, problems, f"otherTopics[{i}]")

    method = report.get("method")
    if not isinstance(method, dict):
        problems.append("report_bad_method")
    else:
        # FeedbackCompression.summary() and FeedbackReportJson.build always emit these, and the
        # adapter's reconciliation consumes them, so an absent key is not a legal empty report.
        for key in ("raw", "topics", "unclassified", "eligible", "representatives",
                    "raw_comments", "raw_danmaku"):
            if not _is_int(method.get(key)) or method[key] < 0:
                problems.append(f"method_bad_{key}")
        for key in ("method", "route"):
            if not isinstance(method.get(key), str) or not method[key].strip():
                problems.append(f"method_bad_{key}")

    scope = report.get("scope")
    if not isinstance(scope, dict):
        problems.append("report_bad_scope")
    else:
        if not isinstance(scope.get("video_id"), str) or not _VIDEO_ID.match(scope["video_id"]):
            problems.append("scope_bad_video_id")
        if scope.get("timezone") != "Asia/Shanghai":
            problems.append("scope_bad_timezone")
        _validate_sources(scope.get("sources"), problems, "scope")
        _check_enum(scope.get("grounding"), GROUNDINGS, "scope_bad_grounding", problems)
        for key in ("start", "end"):
            if not isinstance(scope.get(key), str):
                problems.append(f"scope_bad_{key}")
        if isinstance(scope.get("start"), str) and isinstance(scope.get("end"), str):
            try:
                start = datetime.fromisoformat(scope["start"])
                end = datetime.fromisoformat(scope["end"])
            except ValueError:
                problems.append("scope_unparsable_window")
            else:
                # The product writes LocalDateTime.toString(), always offset-free Asia/Shanghai
                # local time. An offset is a different representation, not the same instant.
                if start.tzinfo is not None or end.tzinfo is not None:
                    problems.append("scope_offset_not_supported")
                elif start >= end:
                    problems.append("scope_window_not_increasing")

    if not isinstance(report.get("dataAsOf"), str):
        problems.append("report_bad_dataAsOf")
    else:
        try:
            datetime.fromisoformat(report["dataAsOf"].replace("Z", "+00:00"))
        except ValueError:
            problems.append("report_unparsable_dataAsOf")

    daily = report.get("daily")
    if not isinstance(daily, dict):
        problems.append("report_bad_daily")
    else:
        for key, value in daily.items():
            try:
                datetime.strptime(key, "%Y-%m-%d")
            except (ValueError, TypeError):
                problems.append("daily_bad_date")
                break
        if not all(_is_int(v) and v >= 0 for v in daily.values()):
            problems.append("daily_bad_count")

    for key in ("videoSummary", "videoCoverage"):
        if report.get(key) is not None and not isinstance(report[key], str):
            problems.append(f"report_bad_{key}")
    playback = report.get("playback")
    if playback is not None:
        if not isinstance(playback, dict):
            problems.append("report_bad_playback")
        else:
            for key in ("startMs", "endMs", "bucketMs", "omitted"):
                if key in playback and (not _is_int(playback[key]) or playback[key] < 0):
                    problems.append(f"playback_bad_{key}")
            if "counts" in playback and (not isinstance(playback["counts"], list)
                                         or not all(_is_int(c) and c >= 0 for c in playback["counts"])):
                problems.append("playback_bad_counts")
            if "knownRange" in playback and not isinstance(playback["knownRange"], bool):
                problems.append("playback_bad_knownRange")
    details = report.get("videoDetails")
    if details is not None:
        if not isinstance(details, dict):
            problems.append("report_bad_videoDetails")
        else:
            for key in ("startMs", "endMs", "momentMatchMaxGapMs"):
                if key in details and (not _is_int(details[key]) or details[key] < 0):
                    problems.append(f"videoDetails_bad_{key}")
            if "transcriptAvailable" in details and not isinstance(details["transcriptAvailable"], bool):
                problems.append("videoDetails_bad_transcriptAvailable")

    _validate_report_relations(report, problems)


def _validate_report_relations(report, problems):
    """Cross-field relations supported by FeedbackReportJson.build and confirmed on real captures."""
    method = report.get("method")
    topics, others = report.get("topics"), report.get("otherTopics")
    if isinstance(method, dict) and isinstance(topics, list) and isinstance(others, list):
        if _is_int(method.get("topics")) and method["topics"] != len(topics) + len(others):
            problems.append("method_topics_disagrees_with_cards")
        if all(_is_int(method.get(k)) for k in ("raw", "raw_comments", "raw_danmaku")):
            if method["raw"] != method["raw_comments"] + method["raw_danmaku"]:
                problems.append("method_raw_disagrees_with_sources")
        counts = [t.get("commentCount") for t in topics + others if isinstance(t, dict)]
        counts += [t.get("danmakuCount") for t in topics + others if isinstance(t, dict)]
        if all(_is_int(c) for c in counts) and _is_int(method.get("eligible")) and _is_int(method.get("unclassified")):
            if sum(counts) + method["unclassified"] != method["eligible"]:
                problems.append("topic_counts_do_not_reconcile")


def _read_bound(anchor, record, problems, where, missing=None):
    """Read once through the reparse guard and bind the hash to exactly these bytes."""
    try:
        path = ensure_real_path(anchor, record["path"])
    except (OutOfBoundsError, KeyError, TypeError):
        problems.append(f"unsafe_response_path:{where}")
        return None
    if not path.is_file():
        (missing if missing is not None else problems).append(f"missing_response_file:{where}")
        return None
    data = path.read_bytes()
    if _sha(data) != record.get("sha256"):
        problems.append(f"response_hash_mismatch:{where}")
    if len(data) != record.get("bytes"):
        problems.append(f"response_bytes_mismatch:{where}")
    return data


def load_bundle(root):
    anchor = Path(root).resolve()
    if not anchor.is_dir():
        raise BundleError(f"capture bundle is not a directory: {anchor}")
    try:
        manifest_path = ensure_real_path(anchor, "bundle.json")
    except OutOfBoundsError as e:
        raise BundleError(str(e)) from e
    if not manifest_path.is_file():
        raise BundleError(f"missing bundle.json under {anchor}")
    manifest_bytes = manifest_path.read_bytes()
    try:
        manifest = json.loads(manifest_bytes.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as e:
        raise BundleError(f"bundle.json is not readable JSON: {e}") from e
    if not isinstance(manifest, dict):
        raise BundleError("bundle.json must be an object")

    problems = []
    _validate_manifest(manifest, problems)
    run = manifest.get("run") if isinstance(manifest.get("run"), dict) else {}
    delivered = run.get("final_status") == "SUCCEEDED"
    responses = manifest.get("responses")
    if not isinstance(responses, dict):
        problems.append("bad_responses")
        responses = {}
    _validate_access_checks(manifest.get("access_checks"), problems, required=delivered)

    json_bytes = html_bytes = trace_bytes = None
    report = None
    delivery_problems = []
    for where in ("json", "html"):
        record = responses.get(where)
        if record is None:
            if delivered:
                delivery_problems.append(f"missing_response:{where}")
            continue
        _validate_response(record, problems, where)
        data = _read_bound(anchor, record, problems, where, missing=delivery_problems)
        if where == "json":
            json_bytes = data
        else:
            html_bytes = data

    if json_bytes is not None:
        try:
            report = json.loads(json_bytes.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            problems.append("report_json_unreadable")
        else:
            _validate_report(report, problems)
            if delivered:
                _validate_receipt_report(run.get("receipt"), report, problems)
    if html_bytes is not None:
        try:
            text = html_bytes.decode("utf-8")
        except UnicodeDecodeError:
            problems.append("report_html_undecodable")
        else:
            if not text.strip():
                problems.append("report_html_empty")
            elif not text.lstrip().lower().startswith("<!doctype html>"):
                problems.append("report_html_not_product_html")

    trace = manifest.get("trace")
    trace_problems = []
    if trace is not None:
        if not isinstance(trace, dict):
            trace_problems.append("bad_trace")
        else:
            if not isinstance(trace.get("sha256"), str) or not _SHA256.match(trace["sha256"]):
                trace_problems.append("bad_trace_sha256")
            if not _is_int(trace.get("bytes")) or trace["bytes"] < 0:
                trace_problems.append("bad_trace_bytes")
            if not _is_int(trace.get("span_count")) or trace["span_count"] < 1:
                trace_problems.append("bad_trace_span_count")
            if trace.get("run_id") != run.get("run_id"):
                trace_problems.append("trace_run_mismatch")
            if trace.get("source") != "harness_jsonl_archive":
                trace_problems.append("unknown_trace_source")
            trace_bytes = _read_bound(anchor, trace, trace_problems, "trace")
            if trace_bytes is not None:
                lines = [l for l in trace_bytes.decode("utf-8", "replace").splitlines() if l.strip()]
                if len(lines) != trace.get("span_count"):
                    trace_problems.append("trace_span_count_mismatch")
                trace_problems.extend(_span_identity_problems(trace_bytes, run.get("run_id")))

    if problems:
        raise BundleError("; ".join(problems))
    bundle = Bundle(anchor, manifest, manifest_bytes, json_bytes, html_bytes, trace_bytes, report,
                    delivery_problems, trace_problems)
    if trace_problems:
        raise TraceBundleError("; ".join(trace_problems), bundle)
    return bundle


def _span_identity_problems(trace_bytes, run_id):
    """A child span may legitimately omit run.id, but an identity it does carry must agree with the
    declared run. Unexported parents and the all-zero root sentinel are normal partial exports, not
    contradictions, so they are not treated as faults. Unparseable spans are left to the tolerant
    ledger reader, which already reports them as a corrupt trace."""
    for line in trace_bytes.decode("utf-8", "replace").splitlines():
        if not line.strip():
            continue
        try:
            span = json.loads(line)
        except ValueError:
            return []
        if not isinstance(span, dict):
            return []
        attrs = span.get("attributes")
        if isinstance(attrs, dict) and "run.id" in attrs and attrs["run.id"] != run_id:
            return ["trace_span_foreign_run"]
    return []


def _case_window(case):
    resolved = timebase.resolve_window(case["task_clock"], case["task_window"])
    return datetime.fromisoformat(resolved["start"]), datetime.fromisoformat(resolved["end"])


def verify_scope(report, case):
    """Compare the product's own scope with the case expectation; never copy request sources."""
    problems = []
    capture = case.get("product_capture")
    if not isinstance(capture, dict):
        return ["missing_product_capture"]
    scope = report.get("scope") if isinstance(report, dict) else None
    if not isinstance(scope, dict):
        return ["product_scope_unavailable"]
    if scope.get("video_id") != capture["request"].get("video_id"):
        problems.append("product_video_out_of_scope")
    if scope.get("grounding") != case.get("report_scope"):
        problems.append("product_grounding_out_of_scope")
    sources = scope.get("sources")
    allowed = capture["request"].get("sources")
    if not isinstance(sources, list) or not isinstance(allowed, list) or sorted(sources) != sorted(allowed):
        problems.append("product_sources_out_of_scope")
    if "task_window" in case:
        try:
            expected_start, expected_end = _case_window(case)
            tz = timebase.get_timezone(scope["timezone"])
            parsed_start = datetime.fromisoformat(scope["start"])
            parsed_end = datetime.fromisoformat(scope["end"])
        except (CaseError, KeyError, ValueError, TypeError):
            problems.append("product_window_unresolvable")
        else:
            if parsed_start.tzinfo is not None or parsed_end.tzinfo is not None:
                # Attaching the case timezone to an already-offset value would silently rewrite
                # the instant the product reported, so an unsupported representation is refused.
                problems.append("product_window_offset_not_supported")
            else:
                actual_start = parsed_start.replace(tzinfo=tz)
                actual_end = parsed_end.replace(tzinfo=tz)
                if actual_start != expected_start or actual_end != expected_end:
                    problems.append("product_window_out_of_scope")
    return problems


def bind_case(bundle, case):
    problems = []
    capture = case.get("product_capture")
    if not isinstance(capture, dict):
        return ["missing_product_capture"]
    if capture.get("bundle_id") != bundle.bundle_id:
        problems.append("bundle_id_mismatch")
    if capture.get("evidence_level") != bundle.manifest.get("evidence_level"):
        problems.append("evidence_level_mismatch")
    request = bundle.manifest.get("request", {})
    for key in ("prompt", "owner_token", "video_id", "grounding"):
        if request.get(key) != capture["request"].get(key):
            problems.append(f"request_{key}_mismatch")
    if sorted(request.get("sources") or []) != sorted(capture["request"].get("sources") or []):
        problems.append("request_sources_mismatch")
    if bundle.report is not None:
        problems.extend(verify_scope(bundle.report, case))
    elif bundle.delivered:
        problems.append("product_report_unavailable")
    return problems


def fingerprint_payload(bundle):
    responses = bundle.manifest.get("responses", {})
    trace = bundle.manifest.get("trace")
    return {
        "evidence_level": EVIDENCE_LEVEL,
        "adapter_version": ADAPTER_VERSION,
        "bundle_contract": BUNDLE_CONTRACT,
        "bundle_id": bundle.bundle_id,
        "bundle_manifest_sha256": _sha(bundle.manifest_bytes),
        "run_id": bundle.manifest["run"]["run_id"],
        "final_status": bundle.final_status,
        "json_sha256": (responses.get("json") or {}).get("sha256"),
        "html_sha256": (responses.get("html") or {}).get("sha256"),
        "trace_sha256": (trace or {}).get("sha256"),
    }


# The manifest minus its trace declaration and free-text notes: everything that decides what report
# was delivered. Splitting it this way keeps a Trace-only edit off the delivery axis.
_REPORT_DECLARATION_KEYS = ("schema_version", "bundle_id", "adapter_contract", "evidence_level",
                            "material_class", "producer", "request", "run", "responses",
                            "access_checks")


def trace_identity(manifest, trace_bytes):
    """The Trace axis alone, recomputable from preserved bytes without revalidating the bundle."""
    return {
        "declaration_sha256": _sha(canonical_json(manifest.get("trace")).encode("utf-8")),
        "spans_sha256": _sha(trace_bytes) if trace_bytes is not None else None,
    }


def capture_identity(bundle):
    """Identity recomputed from the preserved bytes, separated by axis.

    Declarations alone are not identity: a post-import edit can resync them. The report axis hashes
    the actual JSON/HTML bytes plus the report-declaring manifest subset; the Trace axis hashes the
    span bytes plus the trace declaration.
    """
    manifest = bundle.manifest
    declaration = {key: manifest.get(key) for key in _REPORT_DECLARATION_KEYS}
    return {
        "report": {
            "declaration_sha256": _sha(canonical_json(declaration).encode("utf-8")),
            "json_sha256": _sha(bundle.json_bytes) if bundle.json_bytes is not None else None,
            "html_sha256": _sha(bundle.html_bytes) if bundle.html_bytes is not None else None,
        },
        "trace": trace_identity(manifest, bundle.trace_bytes),
    }