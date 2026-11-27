"""Shared helpers: hashing, canonical JSON, atomic writes, output containment."""
import hashlib
import json
import os
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path


class ReportEvalError(Exception):
    pass


class MaterialError(ReportEvalError):
    pass


class CaseError(ReportEvalError):
    pass


class OutOfBoundsError(ReportEvalError):
    pass


class DeliveryError(ReportEvalError):
    pass


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def canonical_json(obj):
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def fingerprint(payload):
    return hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()


def atomic_write_json(path, obj, anchor=None):
    path = Path(path)
    if anchor is not None:
        path = ensure_within(anchor, path)
        ensure_within(anchor, path.parent)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=path.name + ".", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def append_jsonl(path, obj, anchor=None):
    path = Path(path)
    if anchor is not None:
        path = ensure_within(anchor, path)
        ensure_within(anchor, path.parent)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False, sort_keys=True) + "\n")
        f.flush()
        os.fsync(f.fileno())


def read_jsonl_tolerant(path, validate=None):
    """Parse JSONL without rewriting the file; return (records, bad_lines).

    Bad bytes are preserved on disk and reported, never silently skipped, so an
    interrupted write stays diagnosable instead of sinking the whole batch. An
    unreadable path (e.g. a directory occupying the file) is reported as a
    read_failed bad line carrying the path, never disguised as missing or empty.
    ``validate`` (optional) checks a parsed record's structure; a record it rejects
    becomes a bad line carrying its line number, so parseable-but-invalid records
    (e.g. a JSON null attempt) are not counted as valid.
    """
    path = Path(path)
    if not path.exists():
        return [], []
    try:
        raw_bytes = path.read_bytes()
    except OSError as e:
        return [], [{"line_no": 0, "error": f"read_failed:{type(e).__name__}:{e}",
                     "raw_preview": "", "path": str(path)}]
    records, bad_lines = [], []
    for line_no, raw in enumerate(raw_bytes.split(b"\n"), start=1):
        if not raw.strip():
            continue
        preview = raw[:80].decode("utf-8", errors="replace")
        try:
            record = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError) as e:
            bad_lines.append({"line_no": line_no, "error": str(e), "raw_preview": preview})
            continue
        if validate is not None:
            problem = validate(record)
            if problem is not None:
                bad_lines.append({"line_no": line_no, "error": problem, "raw_preview": preview})
                continue
        records.append(record)
    return records, bad_lines


def ensure_within(root, target):
    root = Path(root).resolve()
    target = Path(target).resolve()
    if target != root and root not in target.parents:
        raise OutOfBoundsError(f"path escapes output root: {target} not within {root}")
    return target


def ensure_real_path(root, rel):
    """Resolve a relative path under a real anchor, refusing any redirection.

    ensure_within() resolves symlinks/junctions before comparing, so a junction that points
    back inside the root would slip through. Capture and output paths must be the literal
    files they claim to be, so every component below the anchor is checked for a reparse point.
    """
    root = Path(root).resolve()
    rel = Path(rel)
    if rel.is_absolute() or rel.drive or rel.root:
        raise OutOfBoundsError(f"absolute capture path refused: {rel}")
    if any(part == ".." for part in rel.parts):
        raise OutOfBoundsError(f"parent traversal refused: {rel}")
    current = root
    for part in rel.parts:
        current = current / part
        if current.is_symlink() or os.path.isjunction(current):
            raise OutOfBoundsError(f"reparse point refused: {current}")
    if current != root and root not in current.parents:
        raise OutOfBoundsError(f"path escapes output root: {current} not within {root}")
    return current


def utc_now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def new_id(prefix=""):
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"{prefix}{stamp}-{uuid.uuid4().hex[:8]}"