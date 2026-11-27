"""Formal material read-only binding and unified task-window verification.

Bind the three calibration materials by content hash (never by filename or title), verify the
canon formal window against the authoritative 4A verdict, and record loopback service probe ports.
Both source roots are read-only; the shifted dates are the formal input, never re-shifted.
"""
from pathlib import Path

from .. import datasets, timebase
from ..util import sha256_file

HASH_FIELDS = ("video_sha256", "comment_sha256", "danmaku_sha256")
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}


class MaterialBindingError(Exception):
    pass


def _is_sha256(value):
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def validate_binding(spec):
    """All three content hashes must be present, non-null and well formed."""
    binding = spec.get("binding")
    if not isinstance(binding, dict):
        raise MaterialBindingError(f"binding must be an object: {binding!r}")
    for field in HASH_FIELDS:
        value = binding.get(field)
        if value is None:
            raise MaterialBindingError(f"binding.{field} is missing or null; a video without content "
                                       "hash must never be bound")
        if not _is_sha256(value):
            raise MaterialBindingError(f"binding.{field} is not a sha256 hex digest: {value!r}")


def verify_against_baseline(baseline, actual):
    """Actual (root -> path -> {bytes, sha256}) must equal the declared baseline exactly."""
    for root, files in actual.items():
        declared = baseline.get(root)
        if declared is None:
            raise MaterialBindingError(f"root not in baseline: {root}")
        for rel, meta in files.items():
            expected = declared.get(rel)
            if expected is None:
                raise MaterialBindingError(f"file not in baseline: {root}/{rel}")
            if expected.get("sha256") != meta["sha256"] or expected.get("bytes") != meta["bytes"]:
                raise MaterialBindingError(f"baseline hash mismatch: {root}/{rel}")
    for root, files in baseline.items():
        if root not in actual:
            raise MaterialBindingError(f"baseline root missing from actual scan: {root}")
        for rel in files:
            if rel not in actual.get(root, {}):
                raise MaterialBindingError(f"baseline file missing from actual scan: {root}/{rel}")


def video_matches(spec, video_sha256):
    """The bound video is identified by content hash; a different hash is a binding failure,
    whatever the file name says."""
    validate_binding(spec)
    if spec["binding"]["video_sha256"] != video_sha256:
        raise MaterialBindingError(
            f"video content hash does not match the binding: {video_sha256} != {spec['binding']['video_sha256']}; "
            "never substitute a placeholder (e.g. demo1001) for the formal material")
    return True


def window_stats(dataset_root, key, start, end, timezone_name):
    """Canon window verification with exactly the 4A composition: formal comments on 评论时间,
    pre-shift comments on 原始评论时间, danmaku on 仿真发送时间."""
    tz = timebase.get_timezone(timezone_name)
    start_dt = timebase.parse_dt(start, tz)
    end_dt = timebase.parse_dt(end, tz)
    comments = datasets.load_dataset_comments(dataset_root, key)
    danmaku = datasets.load_dataset_danmaku(dataset_root, key)
    effective = timebase.window_stats(comments, "评论时间", start_dt, end_dt)
    original = timebase.window_stats(comments, "原始评论时间", start_dt, end_dt)
    danmaku_stats = timebase.window_stats(danmaku, "仿真发送时间", start_dt, end_dt)
    before_start = 0
    for record in comments:
        raw = record.get("评论时间")
        if not raw:
            continue
        if timebase.parse_dt(raw, tz) < start_dt:
            before_start += 1
    missing_time = effective["missing_time"] + original["missing_time"] + danmaku_stats["missing_time"]
    return {
        "comments_in_window": effective["count"],
        "comment_by_date": effective["by_date"],
        "original_comments_in_window": original["count"],
        "comments_before_start": before_start,
        "danmaku_in_window": danmaku_stats["count"],
        "danmaku_by_date": danmaku_stats["by_date"],
        "missing_time": missing_time,
    }


def window_matches(stats, expected):
    """Compare exactly the authoritative 4A verdict fields. comments_before_start is informational
    only: the canon CSV legitimately spans a wider formal range, so it is never part of the golden."""
    diffs = []
    for field in ("comments_in_window", "original_comments_in_window", "danmaku_in_window",
                  "missing_time"):
        if stats.get(field) != expected.get(field):
            diffs.append(f"{field}: actual={stats.get(field)} expected={expected.get(field)}")
    for field in ("comment_by_date", "danmaku_by_date"):
        if stats.get(field) != expected.get(field):
            diffs.append(f"{field}: actual={stats.get(field)} expected={expected.get(field)}")
    return diffs


def service_probe_binding(spec):
    """Each material binds to a loopback test-double port; non-loopback hosts are refused."""
    probe = spec.get("service_probe")
    if not isinstance(probe, dict) or "port" not in probe:
        raise MaterialBindingError(f"service_probe must be an object with a port: {probe!r}")
    port = probe.get("port")
    if not isinstance(port, int) or isinstance(port, bool) or not (1 <= port <= 65535):
        raise MaterialBindingError(f"service_probe.port must be an integer in [1,65535]: {port!r}")
    host = probe.get("host", "127.0.0.1")
    if host not in LOOPBACK_HOSTS:
        raise MaterialBindingError(f"service_probe.host must be loopback, got {host!r}")
    return {"host": host, "port": port}


def binding_file_hashes(spec):
    """Hash the actual files a binding declares (video under its own path, comment/danmaku under
    the formal dataset root) and confirm they match the declared content hashes."""
    validate_binding(spec)
    binding = spec["binding"]
    dataset_root = spec.get("dataset_root")
    video_path = spec.get("video_path")
    if not isinstance(dataset_root, str) or not dataset_root.strip():
        raise MaterialBindingError(f"dataset_root must be a non-empty string: {dataset_root!r}")
    if not isinstance(video_path, str) or not video_path.strip():
        raise MaterialBindingError(f"video_path must be a non-empty string: {video_path!r}")
    root = Path(dataset_root)
    key = spec.get("key")
    if not isinstance(key, str) or not key.strip():
        raise MaterialBindingError(f"key must be a non-empty string: {key!r}")
    comment = root / "评论" / f"{key}_评论.csv"
    danmaku = root / "弹幕" / f"{key}_弹幕.csv"
    video = Path(video_path)
    actual = {
        "comment_sha256": sha256_file(comment) if comment.is_file() else None,
        "danmaku_sha256": sha256_file(danmaku) if danmaku.is_file() else None,
        "video_sha256": sha256_file(video) if video.is_file() else None,
    }
    for field in HASH_FIELDS:
        if actual[field] is None:
            return {"matched": False, "actual": actual, "missing": field}
        if actual[field] != binding[field]:
            return {"matched": False, "actual": actual, "mismatch": field}
    return {"matched": True, "actual": actual}
