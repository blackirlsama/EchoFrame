"""Read-only indexing of the formal dataset and collection source, with explicit 1:1 matching."""
import json
import shutil
import subprocess
from collections import Counter
from decimal import Decimal, InvalidOperation
from pathlib import Path

from . import ass, csvio
from .textnorm import normalize_title, strip_ass_tags
from .util import MaterialError, atomic_write_json, sha256_file, utc_now_iso

MANIFEST_COLUMNS = ["key", "作者", "标题", "仿真发布日", "数据截止", "弹幕条数", "评论条数",
                    "其中回复", "评论用户数", "虚拟观众数", "视频时长秒", "评论映射方式", "评论时间范围", "备注"]
DATASET_COMMENT_COLUMNS = ["评论ID", "上级评论ID", "用户ID", "评论内容", "评论时间", "原始评论时间"]
DATASET_DANMAKU_COLUMNS = ["序号", "播放位置秒", "内容", "仿真发送时间", "发送者ID", "发送者昵称"]
SOURCE_COMMENT_COLUMNS = ["评论ID", "上级评论ID", "评论内容", "评论时间"]

LINEAGE_NOTE = ("unified-video / unified-video-shift / unified-video-hexiachu / unified-video-eval "
                "are same-source copies of the same videos; they are not independent samples.")
LINEAGE_REFERENCE = "docs/evaluation/technical/real-data-audit.md"


def load_dataset_comments(dataset_root, key):
    return csvio.read_csv_records(Path(dataset_root) / "评论" / f"{key}_评论.csv", DATASET_COMMENT_COLUMNS)


def load_dataset_danmaku(dataset_root, key):
    return csvio.read_csv_records(Path(dataset_root) / "弹幕" / f"{key}_弹幕.csv", DATASET_DANMAKU_COLUMNS)


def _reply_stats(records):
    ids = {r["评论ID"] for r in records}
    replies = [r for r in records if r["上级评论ID"].strip()]
    orphans = [r["评论ID"] for r in replies if r["上级评论ID"] not in ids]
    return len(replies), orphans


def _manifest_int(row, field):
    try:
        return int(row[field])
    except (TypeError, ValueError):
        raise MaterialError(f"manifest field {field} is not an integer: {row.get(field)!r} (key={row.get('key')})")


def index_dataset(root):
    root = Path(root)
    manifest_path = root / "视频清单.csv"
    if not manifest_path.is_file():
        raise MaterialError(f"dataset manifest missing: {manifest_path}")
    manifest = csvio.read_csv_records(manifest_path, MANIFEST_COLUMNS)
    findings = []
    videos = {}
    for row in manifest:
        key = row["key"]
        if key in videos:
            raise MaterialError(f"duplicate manifest key: {key}")
        comment_path = root / "评论" / f"{key}_评论.csv"
        danmaku_path = root / "弹幕" / f"{key}_弹幕.csv"
        for p in (comment_path, danmaku_path):
            if not p.is_file():
                raise MaterialError(f"dataset file missing for key {key}: {p}")
        comments = csvio.read_csv_records(comment_path, DATASET_COMMENT_COLUMNS)
        danmaku = csvio.read_csv_records(danmaku_path, DATASET_DANMAKU_COLUMNS)
        reply_count, orphans = _reply_stats(comments)
        for field, actual in (("评论条数", len(comments)), ("弹幕条数", len(danmaku)), ("其中回复", reply_count)):
            expected = _manifest_int(row, field)
            if expected != actual:
                findings.append({"kind": "count_mismatch", "key": key, "field": field,
                                 "manifest": expected, "actual": actual})
        videos[key] = {
            "key": key, "author": row["作者"], "title": row["标题"],
            "normalized_title": normalize_title(row["标题"]),
            "effective_timeline": {
                "publish_date": row["仿真发布日"], "data_cutoff": row["数据截止"],
                "comment_time_range": row["评论时间范围"], "comment_mapping": row["评论映射方式"],
            },
            "comment_fields": {"effective_time": "评论时间", "original_collection_time": "原始评论时间"},
            "danmaku_fields": {"effective_time": "仿真发送时间", "sender_id": "设定虚拟观众",
                               "original_sender": "unknown", "original_send_date": "unknown",
                               "original_danmaku_id": "unknown"},
            "comment_file": str(comment_path), "comment_sha256": sha256_file(comment_path),
            "comment_count": len(comments), "reply_count": reply_count,
            "orphan_reply_count": len(orphans), "orphan_reply_ids": orphans,
            "danmaku_file": str(danmaku_path), "danmaku_sha256": sha256_file(danmaku_path),
            "danmaku_count": len(danmaku),
            "manifest_duration_seconds": _manifest_int(row, "视频时长秒"),
            "virtual_audience_count": _manifest_int(row, "虚拟观众数"),
            "note": row["备注"],
        }
    truth = _index_truth(root, videos, findings)
    return {
        "dataset_root": str(root), "manifest_sha256": sha256_file(manifest_path),
        "videos": videos, "truth": truth, "findings": findings,
        "counts": {"videos": len(videos),
                   "comments": sum(v["comment_count"] for v in videos.values()),
                   "danmaku": sum(v["danmaku_count"] for v in videos.values()),
                   "orphan_replies": sum(v["orphan_reply_count"] for v in videos.values())},
    }


def _index_truth(root, videos, findings):
    truth = {}
    daily_path = root / "真值日报.csv"
    if daily_path.is_file():
        daily = csvio.read_csv_records(daily_path, ["日期", "视频key", "弹幕数", "评论数"])
        sums = {}
        for r in daily:
            s = sums.setdefault(r["视频key"], {"评论数": 0, "弹幕数": 0})
            s["评论数"] += int(r["评论数"])
            s["弹幕数"] += int(r["弹幕数"])
        for key, s in sums.items():
            if key not in videos:
                findings.append({"kind": "truth_unknown_key", "table": "真值日报", "key": key})
                continue
            for field, video_field, actual in (("评论条数", "comment_count", s["评论数"]),
                                               ("弹幕条数", "danmaku_count", s["弹幕数"])):
                if videos[key][video_field] != actual:
                    findings.append({"kind": "truth_sum_mismatch", "table": "真值日报", "key": key,
                                     "field": field, "video_count": videos[key][video_field], "truth_sum": actual})
        truth["daily"] = {"file": str(daily_path), "sha256": sha256_file(daily_path), "records": len(daily)}
    ranking_path = root / "真值弹幕排行.csv"
    if ranking_path.is_file():
        ranking = csvio.read_csv_records(ranking_path, ["视频key", "发送者ID", "弹幕条数"])
        per_key = {}
        for r in ranking:
            s = per_key.setdefault(r["视频key"], {"audience": 0, "danmaku": 0})
            s["audience"] += 1
            s["danmaku"] += int(r["弹幕条数"])
        for key, s in per_key.items():
            if key not in videos:
                findings.append({"kind": "truth_unknown_key", "table": "真值弹幕排行", "key": key})
                continue
            if videos[key]["virtual_audience_count"] != s["audience"]:
                findings.append({"kind": "truth_sum_mismatch", "table": "真值弹幕排行", "key": key,
                                 "field": "虚拟观众数", "video_count": videos[key]["virtual_audience_count"],
                                 "truth_sum": s["audience"]})
            if videos[key]["danmaku_count"] != s["danmaku"]:
                findings.append({"kind": "truth_sum_mismatch", "table": "真值弹幕排行", "key": key,
                                 "field": "弹幕条数", "video_count": videos[key]["danmaku_count"],
                                 "truth_sum": s["danmaku"]})
        truth["danmaku_ranking"] = {"file": str(ranking_path), "sha256": sha256_file(ranking_path),
                                    "records": len(ranking)}
    position_path = root / "真值播放位置.csv"
    if position_path.is_file():
        positions = csvio.read_csv_records(position_path, ["视频key", "弹幕总数"])
        for r in positions:
            key = r["视频key"]
            if key in videos and videos[key]["danmaku_count"] != int(r["弹幕总数"]):
                findings.append({"kind": "truth_sum_mismatch", "table": "真值播放位置", "key": key,
                                 "field": "弹幕总数", "video_count": videos[key]["danmaku_count"],
                                 "truth_sum": int(r["弹幕总数"])})
        truth["play_position"] = {"file": str(position_path), "sha256": sha256_file(position_path),
                                  "records": len(positions)}
    return truth


def _resolve_ffprobe(explicit):
    if explicit:
        return explicit, "explicit"
    found = shutil.which("ffprobe")
    if found:
        return found, "path"
    return None, "missing"


def _probe_duration(path, ffprobe):
    if not ffprobe:
        return None, "missing_ffprobe"
    try:
        out = subprocess.run(
            [ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "json", str(path)],
            capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        return None, "ffprobe_failed"
    if out.returncode != 0:
        return None, "ffprobe_failed"
    try:
        return float(json.loads(out.stdout)["format"]["duration"]), "measured"
    except (ValueError, KeyError, json.JSONDecodeError):
        return None, "ffprobe_failed"


def index_source(root, ffprobe=None):
    root = Path(root)
    if not root.is_dir():
        raise MaterialError(f"source root missing: {root}")
    ffprobe_path, ffprobe_state = _resolve_ffprobe(ffprobe)
    videos = []
    auxiliary = []
    for author_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        groups = {}
        for p in sorted(author_dir.iterdir()):
            if not p.is_file():
                continue
            if p.suffix.lower() == ".mp4":
                kind, stem = "video", p.stem
            elif p.name.endswith("_评论.csv"):
                kind, stem = "comments", p.name[: -len("_评论.csv")]
            elif p.suffix.lower() == ".zip":
                kind, stem = "zip", p.stem
            else:
                auxiliary.append(str(p))
                continue
            norm = normalize_title(stem)
            slot = groups.setdefault(norm, {"title": stem, "video": None, "comments": None, "zip": None})
            if slot[kind] is not None:
                raise MaterialError(
                    f"source title conflict under {author_dir.name}: {stem!r} duplicates {slot[kind]}")
            slot[kind] = p
        for norm, slot in groups.items():
            missing = [k for k in ("video", "comments", "zip") if slot[k] is None]
            if missing:
                raise MaterialError(
                    f"source group incomplete for {author_dir.name}/{slot['title']!r}: missing {missing}")
            duration, duration_state = _probe_duration(slot["video"], ffprobe_path)
            comments = csvio.read_csv_records(slot["comments"], SOURCE_COMMENT_COLUMNS)
            reply_count, orphans = _reply_stats(comments)
            zip_ass = ass.read_zip_ass(slot["zip"])
            videos.append({
                "author": author_dir.name, "title": slot["title"], "normalized_title": norm,
                "video_path": str(slot["video"]), "video_bytes": slot["video"].stat().st_size,
                "video_sha256": sha256_file(slot["video"]),
                "duration_seconds": duration, "duration_state": duration_state,
                "comment_csv_path": str(slot["comments"]), "comment_sha256": sha256_file(slot["comments"]),
                "comment_count": len(comments), "reply_count": reply_count, "orphan_reply_count": len(orphans),
                "comment_time_field_semantics": "original_collection_time",
                "zip_path": str(slot["zip"]), "ass": zip_ass,
                "ass_sender": "unknown", "ass_send_date": "unknown", "ass_original_id": "unknown",
            })
    return {"source_root": str(root), "ffprobe": {"path": ffprobe_path, "state": ffprobe_state},
            "videos": videos, "auxiliary_files": auxiliary,
            "counts": {"videos": len(videos),
                       "comments": sum(v["comment_count"] for v in videos),
                       "danmaku": sum(v["ass"]["event_count"] for v in videos)}}


def _danmaku_multiset_dataset(records):
    out = Counter()
    for r in records:
        try:
            pos = str(Decimal(r["播放位置秒"]))
        except InvalidOperation:
            raise MaterialError(f"bad danmaku position: {r['播放位置秒']!r} (序号={r.get('序号')})")
        out[(pos, strip_ass_tags(r["内容"]).strip())] += 1
    return out


def _danmaku_multiset_ass(events):
    # The formal dataset strips leading/trailing whitespace from danmaku text; reconcile on the
    # same explicit rule while each side keeps its own stored text.
    return Counter((e["start_seconds"], e["text"].strip()) for e in events)


def match_and_reconcile(dataset, source, dataset_root, source_root):
    by_norm = {}
    for v in source["videos"]:
        if v["normalized_title"] in by_norm:
            raise MaterialError(f"source normalized title collision: {v['normalized_title']!r}")
        by_norm[v["normalized_title"]] = v
    pairs = []
    findings = []
    claimed_sources = {}
    for key, dv in dataset["videos"].items():
        sv = by_norm.get(dv["normalized_title"])
        if sv is None:
            raise MaterialError(f"no source match for dataset key {key}: title {dv['title']!r}")
        src_sha = sv["video_sha256"]
        if src_sha in claimed_sources:
            raise MaterialError(
                f"source video reused by multiple dataset keys: {claimed_sources[src_sha]!r} and {key!r} "
                f"(video_sha256={src_sha}); same-source copies are not independent samples")
        claimed_sources[src_sha] = key
        ds_comments = load_dataset_comments(dataset_root, key)
        src_comments = csvio.read_csv_records(sv["comment_csv_path"], SOURCE_COMMENT_COLUMNS)
        ds_by_id = {r["评论ID"]: r for r in ds_comments}
        src_by_id = {r["评论ID"]: r for r in src_comments}
        if len(ds_by_id) != len(ds_comments) or len(src_by_id) != len(src_comments):
            raise MaterialError(f"duplicate comment IDs in pair {key}")
        only_ds = sorted(set(ds_by_id) - set(src_by_id))
        only_src = sorted(set(src_by_id) - set(ds_by_id))
        content_diff, time_diff = [], []
        for cid in sorted(set(ds_by_id) & set(src_by_id)):
            if ds_by_id[cid]["评论内容"] != src_by_id[cid]["评论内容"]:
                content_diff.append(cid)
            if ds_by_id[cid]["原始评论时间"] != src_by_id[cid]["评论时间"]:
                time_diff.append(cid)
        comment_state = "match" if not (only_ds or only_src or content_diff or time_diff) else "mismatch"
        if comment_state == "mismatch":
            findings.append({"kind": "comment_reconciliation_mismatch", "key": key,
                             "only_dataset": len(only_ds), "only_source": len(only_src),
                             "content_diff": len(content_diff), "original_time_diff": len(time_diff),
                             "sample_ids": (only_ds + only_src + content_diff + time_diff)[:5]})
        ds_danmaku = load_dataset_danmaku(dataset_root, key)
        ds_multi = _danmaku_multiset_dataset(ds_danmaku)
        src_multi = _danmaku_multiset_ass(sv["ass"]["events"])
        danmaku_state = "match" if ds_multi == src_multi else "mismatch"
        if danmaku_state == "mismatch":
            diff = sum((ds_multi - src_multi).values()) + sum((src_multi - ds_multi).values())
            findings.append({"kind": "danmaku_reconciliation_mismatch", "key": key,
                             "multiset_diff_entries": diff})
        pairs.append({
            "key": key, "author": sv["author"], "source_title": sv["title"],
            "normalized_title": dv["normalized_title"], "match_rule": "normalized_title_exact",
            "source_video_sha256": sv["video_sha256"],
            "reconciliation": {"comments": comment_state, "danmaku": danmaku_state,
                               "comment_original_time_preserved": not time_diff},
        })
    matched_norms = {p["normalized_title"] for p in pairs}
    for v in source["videos"]:
        if v["normalized_title"] not in matched_norms:
            findings.append({"kind": "source_video_unmatched", "author": v["author"], "title": v["title"]})
    return {"pairs": pairs, "findings": findings}


def build_inventory(dataset_root, source_root, output, ffprobe=None):
    dataset_root, source_root, output = Path(dataset_root), Path(source_root), Path(output)
    dataset = index_dataset(dataset_root)
    source = index_source(source_root, ffprobe)
    matched = match_and_reconcile(dataset, source, dataset_root, source_root)
    findings = dataset["findings"] + matched["findings"]
    durations = [v["duration_seconds"] for v in source["videos"] if v["duration_seconds"] is not None]
    index = {
        "schema_version": 1, "generated_at": utc_now_iso(),
        "dataset_root": str(dataset_root), "source_root": str(source_root),
        "dataset": dataset, "source": source,
        "pairs": matched["pairs"],
        "lineage": {"note": LINEAGE_NOTE, "reference": LINEAGE_REFERENCE,
                    "independent_video_samples": len({p["source_video_sha256"] for p in matched["pairs"]})},
        "totals": {"dataset_comments": dataset["counts"]["comments"],
                   "dataset_danmaku": dataset["counts"]["danmaku"],
                   "source_comments": source["counts"]["comments"],
                   "source_danmaku": source["counts"]["danmaku"],
                   "measured_duration_seconds_total": sum(durations) if durations else None,
                   "videos_with_measured_duration": len(durations)},
        "findings": findings,
    }
    output.mkdir(parents=True, exist_ok=True)
    atomic_write_json(output / "index.json", index)
    return index