"""Admission check/prepare orchestration for the report_eval CLI.

Both entries share schema.validate_config as their first step: a structurally invalid config is
reported to stdout with field paths (never values), exits 2, and writes NOTHING. A structurally
valid config then runs the deep checks (role findings, material bindings, canon window, budget
self-test); blocking gaps exit 3 with a diagnosable verdict for admission-check, while
admission-prepare refuses with exit 3 and writes NOTHING (it must never fake readiness when the
check found gaps, and it must not seed usable frozen results for unverified materials). Only a
fully clean run (exit 0) writes snapshots, seeds and the checklist. The CLI is the formal
three-material five-role admission; subset checks live in unit tests against internal validators.
All written artifacts pass through deep credential redaction.
"""
import json
import sys
from pathlib import Path

from ..util import atomic_write_json, canonical_json
from . import materials, roles, schema, toolfreeze
from .budget import FeeLedger, BudgetError

CONFIG_KIND = schema.CONFIG_KIND


class ConfigError(Exception):
    def __init__(self, issues):
        self.issues = issues
        super().__init__("; ".join(f"{i.path}: {i.expectation}" for i in issues))


def load_config(path):
    """Read and strictly preflight the config; raise ConfigError with field-path issues."""
    path = Path(path)
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        raise ConfigError([schema.Issue("<file>", f"config is unreadable: {type(e).__name__}")]) from e
    issues = schema.validate_config(doc)
    if issues:
        raise ConfigError(issues)
    return doc


def _prices_from_config(doc):
    """The offline config carries null pricing; the ledger gets an empty table so any dispatch
    attempt is refused with NEEDS_PRICING instead of being waved through."""
    return {}


def _ledger_identity(doc):
    budget = doc["budget"]
    return {"total_fen": int(budget["total_cny"] * 100), "pause_fen": int(budget["pause_cny"] * 100)}


def _window_bounds(spec):
    window = spec.get("task_window") or {}
    return window.get("start", "2026-11-03 00:00:00"), window.get("end", "2026-11-10 00:00:00")


def _collect_gaps(doc, ledger_probe_dir=None):
    """Deep checks on a structurally valid config; returns (gaps, snapshot). The ledger self-test
    writes only into a throwaway temp dir that is removed afterwards, so a refusing run never
    leaves artifacts in any output directory."""
    gaps = []
    snapshot = roles.role_snapshot(doc["roles"])
    for entry in snapshot["roles"]:
        for finding in roles.blocking_findings(entry["findings"]):
            gaps.append({"code": finding["code"], "blocking": True, "role": entry.get("role"),
                         "detail": finding["detail"]})
        for finding in entry["findings"]:
            if finding["code"] not in roles.BLOCKING_FINDING_CODES:
                gaps.append({"code": finding["code"], "blocking": False, "role": entry.get("role"),
                             "detail": finding["detail"]})
    for spec in doc["materials"]:
        try:
            materials.validate_binding(spec)
            probe = materials.service_probe_binding(spec)
            hashes = materials.binding_file_hashes(spec)
            if not hashes["matched"]:
                problem = hashes.get("missing") or hashes.get("mismatch")
                gaps.append({"code": "MATERIAL_HASH_MISMATCH", "blocking": True, "material": spec.get("key"),
                             "detail": f"{problem}: actual file content does not match the declared binding"})
                continue
            if "canon_window" in spec:
                start, end = _window_bounds(spec)
                stats = materials.window_stats(spec["dataset_root"], spec["key"], start, end,
                                               doc["task_clock"]["timezone"])
                diffs = materials.window_matches(stats, spec["canon_window"])
                if diffs:
                    gaps.append({"code": "WINDOW_MISMATCH", "blocking": True, "material": spec.get("key"),
                                 "detail": "; ".join(diffs)})
            gaps.append({"code": "MATERIAL_BOUND", "blocking": False, "material": spec.get("key"),
                         "detail": canonical_json({"binding": schema.redact(spec["binding"]),
                                                   "service_probe": probe})})
        except materials.MaterialBindingError as e:
            gaps.append({"code": "MATERIAL_BINDING_INVALID", "blocking": True,
                         "material": schema.redact(spec).get("key"), "detail": str(e)})
    # Fee ledger identity self-test: an empty offline ledger must refuse dispatch (null pricing).
    import tempfile
    scratch = Path(tempfile.mkdtemp(prefix="admission-ledger-selftest-"))
    try:
        identity = _ledger_identity(doc)
        ledger = FeeLedger(scratch / "fee-ledger-selftest.json",
                           identity["total_fen"], identity["pause_fen"], _prices_from_config(doc))
        try:
            with ledger:
                ledger.admit("offline-selftest", 10, 10)
            gaps.append({"code": "BUDGET_SELFTEST_DISPATCHED", "blocking": True,
                         "detail": "a null-pricing ledger allowed a dispatch; the stop path is broken"})
        except BudgetError:
            pass
    except BudgetError as e:
        gaps.append({"code": "BUDGET_SELFTEST_FAILED", "blocking": True, "detail": str(e)})
    finally:
        import shutil
        shutil.rmtree(scratch, ignore_errors=True)
    return gaps, snapshot


def _blocking(gaps):
    return [g for g in gaps if g.get("blocking") is True]


def _refuse(out_dir, issues):
    """Refusals print field-path diagnostics to stdout and write nothing anywhere."""
    print(json.dumps({"error": "CONFIG_REFUSED",
                      "issues": [schema.redact(i.as_dict()) for i in issues]}, ensure_ascii=False))
    return 2


def run_check(config_path, out_dir):
    out_dir = Path(out_dir)
    try:
        doc = load_config(config_path)
    except ConfigError as error:
        return _refuse(out_dir, error.issues)
    out_dir.mkdir(parents=True, exist_ok=True)
    gaps, snapshot = _collect_gaps(doc)
    blocking = _blocking(gaps)
    verdict = schema.redact({
        "verdict": "OFFLINE_ADMISSION_GAPS" if blocking else "OFFLINE_ADMISSION_READY",
        "roles": len(snapshot["roles"]),
        "materials": len(doc["materials"]),
        "gaps": gaps,
        "scope": "OFFLINE_PREPARATION_ONLY: no credentials used, no live model call, no account "
                 "permission verified; live request names stay REQUESTED_ONLY and pricing stays null",
    })
    atomic_write_json(out_dir / "admission-verdict.json", verdict)
    return 3 if blocking else 0


def run_prepare(config_path, out_dir):
    out_dir = Path(out_dir)
    try:
        doc = load_config(config_path)
    except ConfigError as error:
        return _refuse(out_dir, error.issues)
    gaps, snapshot = _collect_gaps(doc)
    if _blocking(gaps):
        # Same inputs, same verdict as admission-check: refuse before writing any artifact so a
        # gapped run can never produce usable snapshots, freeze seeds or a checklist.
        print(json.dumps({"error": "OFFLINE_ADMISSION_GAPS",
                          "issues": [g for g in gaps if g.get("blocking")]}, ensure_ascii=False))
        return 3
    out_dir.mkdir(parents=True, exist_ok=True)
    atomic_write_json(out_dir / "role-snapshot.json", schema.redact(snapshot))
    bindings = []
    for spec in doc["materials"]:
        entry = {"key": spec.get("key"), "dataset_root": spec.get("dataset_root"),
                 "video_path": spec.get("video_path"),
                 "binding": schema.redact(spec.get("binding")),
                 "task_clock": doc["task_clock"]}
        probe = materials.service_probe_binding(spec)
        entry["service_probe"] = probe
        hashes = materials.binding_file_hashes(spec)
        entry["matched"] = hashes["matched"]
        if not hashes["matched"]:
            entry["problem"] = hashes.get("missing") or hashes.get("mismatch")
        if "canon_window" in spec and hashes["matched"]:
            start, end = _window_bounds(spec)
            stats = materials.window_stats(spec["dataset_root"], spec["key"], start, end,
                                           doc["task_clock"]["timezone"])
            entry["window_stats"] = stats
            entry["window_matches"] = materials.window_matches(stats, spec["canon_window"]) == []
        bindings.append(entry)
    atomic_write_json(out_dir / "material-bindings.json", {"materials": bindings,
                                                           "formal_input_note":
                                                           "已平移日期就是正式输入；只读绑定，不再次平移"})
    # Seed the freeze store with deterministic synthetic responses so replay is exercisable
    # offline; these are fixtures, never presented as real service results.
    store = toolfreeze.FrozenStore(out_dir / "freeze-store")
    seeded = []
    for spec in doc["materials"]:
        key = toolfreeze.freeze_key(
            tool="analyze_comments",
            arguments={"dataset_key": spec.get("key")},
            material=spec["binding"],
            task_clock=doc["task_clock"],
            task_window=spec.get("task_window"),
            sampling_strategy=doc["tool_freeze"]["sampling_strategy"],
            tool_config_version=doc["tool_freeze"]["tool_config_version"])
        store.put(key, canonical_json({
            "seed": "synthetic-offline-fixture", "material": spec.get("key"),
            "note": "本地合成回放种子，不代表真实服务结果"}).encode("utf-8"))
        seeded.append({"material": spec.get("key"), "key": key})
    atomic_write_json(out_dir / "freeze-store" / "MANIFEST.json", store.manifest())
    checklist = [
        "# 付费前小额定检清单（主对话放行后执行）",
        "",
        "本清单是离线准备产物；以下每项都需要真实凭据注入后由主对话授权执行，本轮未执行：",
        "",
        "1. 核对硅基流动托管的实际请求名与响应可见版本（当前占位为 REQUESTED_ONLY）。",
        "2. 用最小请求验证 thinking/reasoning_effort 字段在硅基流动上是否被接受（当前判 PROTOCOL_FIELD_UNVERIFIED）。",
        "3. 验证原生工具配对：必选工具、连续工具调用、流式收尾、异常与非 JSON 回包。",
        "4. 验证 MiniMax 专用 /v1/coding_plan/vlm 的权限与计费语义；通用图片接口候选单独验证，不与专用契约混拼。",
        "5. 核对两款主控各自的实际单价并回填价格表（当前为 null，不可付费；价目按 主控请求名 分列，不共用）。",
        "6. 用合成请求重放冻结库，确认同请求同字节后，再进入 12 次小试的首次佳能两次。",
        "",
        f"冻结键规范：{toolfreeze.KEY_SPEC_VERSION}；种子键见 freeze-store/MANIFEST.json。",
    ]
    # Paired-condition records for the agreed canon first pair: two members, one per controller
    # (placeholder REQUESTED_ONLY names - real request names stay unverified), repeat 1/2. The
    # pair identity binds shared conditions only; controller identity and repeat number are
    # carried as membership, never into the id.
    from . import pairing
    canon = next(m for m in doc["materials"] if m.get("key") == "ysjf-canon")
    window = canon.get("task_window") or {}
    conditions = {
        "prompt": "分析视频{video_id}从2026-11-03到2026-11-10的评论和弹幕，生成观众反馈HTML报告（VIDEO_GROUNDED 模板占位，正式题面待小试前定稿）",
        "config_path": "agent/eval/report-trial-admission.example.json",
        "material_key": canon["key"],
        "material_hashes": {k: canon["binding"][k] for k in ("video_sha256", "comment_sha256", "danmaku_sha256")},
        "window_start": window.get("start", "2026-11-03 00:00:00"),
        "window_end": window.get("end", "2026-11-10 00:00:00"),
        "task_clock_zone": doc["task_clock"]["timezone"],
        "task_clock_reference": doc["task_clock"]["reference_time"],
        "sampling_strategy": doc["tool_freeze"]["sampling_strategy"],
        "tool_config_version": doc["tool_freeze"]["tool_config_version"],
        "fixed_roles": {r["role"]: r["model_request_name"] for r in doc["roles"] if r["role"] != "harness"},
        "permissions": {"network": False, "ssh": False, "read_dataset": True},
        "session_initial_state": "fresh-per-run",
        "scoring_version": "draft-v0",
        "run_mode": "loopback-fixture",
    }
    members = [pairing.pair_membership(conditions, "controller-a-REQUESTED-ONLY", 1),
               pairing.pair_membership(conditions, "controller-b-REQUESTED-ONLY", 2)]
    pairing.validate_pairs(members)
    atomic_write_json(out_dir / "pair-conditions.json", {
        "pair_id": members[0]["pair_id"], "members": members,
        "note": "佳能首对成对条件记录；主控请求名占位 REQUESTED_ONLY，正式试跑前待核"})
    (out_dir / "live-compat-checklist.md").write_text("\n".join(checklist) + "\n", encoding="utf-8")
    return 0
