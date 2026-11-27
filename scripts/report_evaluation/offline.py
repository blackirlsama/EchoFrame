"""Deterministic offline adapter: simulates outcomes to exercise the runner; never calls a model."""
import hashlib
import json

from . import timebase
from .util import OutOfBoundsError, canonical_json


def _source_keys(case):
    material = case["material"]
    if material["kind"] == "dataset_ref":
        return list(material["keys"])
    return ["synthetic"]


def _window(case):
    if "task_window" in case:
        return timebase.resolve_window(case["task_clock"], case["task_window"])
    return None


def _synthetic_spans(trial_id):
    trace_id = "trace-" + trial_id
    run_id = "run-" + trial_id
    return [
        {"traceId": trace_id, "spanId": "root", "name": "agent.run",
         "startTimeUnixNano": 1_000_000_000, "endTimeUnixNano": 2_000_000_000,
         "attributes": {"run.id": run_id, "run.status": "SUCCEEDED"}},
        {"traceId": trace_id, "spanId": "req1", "parentSpanId": "root", "name": "model.request",
         "startTimeUnixNano": 1_100_000_000, "endTimeUnixNano": 1_900_000_000,
         "attributes": {"run.id": run_id, "model.name": "offline-simulated", "model.dispatched": True,
                        "model.input_tokens": 120, "model.output_tokens": 80,
                        "model.usage_state": "REPORTED", "model.http_status": 200, "model.response_ms": 800}},
    ]


def _deliver(trial, case, with_trace=True):
    report_id = "rpt-" + trial.trial_id
    md = ("# 离线模拟报告\n\n"
          "这是确定性离线模拟产物，仅用于验证执行与检查流程，不代表真实模型输出或真实平台事实。\n\n"
          f"任务：{case['prompt']}\n")
    md_bytes = md.encode("utf-8")
    trial.write_artifact("artifacts/report.md", md_bytes)
    report = {
        "report_id": report_id, "generated_by": "offline_simulator",
        "sources": {"dataset_keys": _source_keys(case), "window": _window(case)},
        "content_sha256": hashlib.sha256(md_bytes).hexdigest(),
    }
    trial.write_artifact("artifacts/report.json", canonical_json(report).encode("utf-8"))
    if with_trace:
        spans = _synthetic_spans(trial.trial_id)
        data = "".join(json.dumps(s, ensure_ascii=False, sort_keys=True) + "\n" for s in spans)
        trial.write_artifact("traces/trace.jsonl", data.encode("utf-8"))
    return report_id


def run_offline_case(trial, case):
    scenario = case["offline_simulation"]["scenario"]
    if scenario == "success":
        report_id = _deliver(trial, case)
        trial.append_attempt({"kind": "request", "outcome": "SUCCEEDED", "report_id": report_id})
        trial.finish({"final_status": "SUCCEEDED", "report_id": report_id,
                      "first_attempt_outcome": "SUCCEEDED", "recovered": False, "trace_state": "PRESENT"})
    elif scenario == "failure":
        trial.append_attempt({"kind": "request", "outcome": "FAILED", "error": "simulated upstream failure"})
        trial.finish({"final_status": "FAILED", "report_id": None,
                      "first_attempt_outcome": "FAILED", "recovered": False, "trace_state": "MISSING"})
    elif scenario == "needs_clarification":
        trial.append_attempt({"kind": "request", "outcome": "NEEDS_INPUT",
                              "clarification_question": "请确认统计时间范围。"})
        replies = case["interaction_script"].get("clarification_replies", [])
        if replies:
            trial.append_attempt({"kind": "clarification_reply", "reply": replies[0]})
            report_id = _deliver(trial, case)
            trial.append_attempt({"kind": "recovery", "outcome": "SUCCEEDED", "report_id": report_id})
            trial.finish({"final_status": "SUCCEEDED", "report_id": report_id,
                          "first_attempt_outcome": "NEEDS_INPUT", "recovered": True, "trace_state": "PRESENT"})
        else:
            trial.finish({"final_status": "NEEDS_INPUT", "report_id": None,
                          "first_attempt_outcome": "NEEDS_INPUT", "recovered": False, "trace_state": "MISSING"})
    elif scenario == "retry_success":
        trial.append_attempt({"kind": "request", "outcome": "FAILED", "error": "simulated transient failure"})
        report_id = _deliver(trial, case)
        trial.append_attempt({"kind": "retry", "outcome": "SUCCEEDED", "report_id": report_id})
        trial.finish({"final_status": "SUCCEEDED", "report_id": report_id,
                      "first_attempt_outcome": "FAILED", "recovered": True, "trace_state": "PRESENT"})
    elif scenario == "missing_report":
        report_id = "rpt-" + trial.trial_id
        trial.append_attempt({"kind": "request", "outcome": "SUCCEEDED", "report_id": report_id,
                              "note": "claims success but no artifact is written"})
        trial.finish({"final_status": "SUCCEEDED", "report_id": report_id,
                      "first_attempt_outcome": "SUCCEEDED", "recovered": False, "trace_state": "MISSING"})
    elif scenario == "missing_trace":
        report_id = _deliver(trial, case, with_trace=False)
        trial.append_attempt({"kind": "request", "outcome": "SUCCEEDED", "report_id": report_id})
        trial.finish({"final_status": "SUCCEEDED", "report_id": report_id,
                      "first_attempt_outcome": "SUCCEEDED", "recovered": False, "trace_state": "MISSING"})
    elif scenario == "interrupted":
        trial.simulate_interrupt()
    elif scenario == "out_of_bounds":
        trial.append_attempt({"kind": "request", "outcome": "SUCCEEDED",
                              "requested_artifact": "../escaped-report.md"})
        try:
            trial.write_artifact("../escaped-report.md", b"escape attempt")
        except OutOfBoundsError:
            trial.append_attempt({"kind": "delivery", "outcome": "OUT_OF_BOUNDS_REFUSED",
                                  "requested_artifact": "../escaped-report.md"})
            trial.finish({"final_status": "FAILED", "report_id": None, "delivery_violation": "out_of_bounds",
                          "first_attempt_outcome": "SUCCEEDED", "recovered": False, "trace_state": "MISSING"})
    else:
        raise ValueError(f"unknown offline scenario: {scenario!r}")