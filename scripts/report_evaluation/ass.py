"""In-memory ZIP/ASS parsing for the observed 10-column Tex variant; never extracts to disk."""
import hashlib
import zipfile
from pathlib import Path

from .textnorm import ass_start_to_seconds, strip_ass_tags
from .util import MaterialError, sha256_file

_EVENT_COLUMNS = 10


def parse_ass_bytes(data, source_label):
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as e:
        raise MaterialError(f"ASS decode error (expected UTF-8): {source_label}: {e}") from e
    format_columns = None
    in_events = False
    events = []
    bad = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("["):
            in_events = stripped.lower() == "[events]"
            continue
        if not in_events:
            continue
        if stripped.startswith("Format:"):
            cols = [c.strip() for c in stripped[len("Format:"):].split(",")]
            if len(cols) != _EVENT_COLUMNS or cols[-1].lower() not in ("text", "tex"):
                raise MaterialError(
                    f"ASS {source_label} line {lineno}: expected 10 event columns ending Text/Tex, got {cols}")
            format_columns = cols
            continue
        if stripped.startswith("Dialogue:"):
            if format_columns is None:
                raise MaterialError(f"ASS {source_label} line {lineno}: Dialogue before Format")
            body = line[line.index("Dialogue:") + len("Dialogue:"):]
            parts = body.split(",", _EVENT_COLUMNS - 1)
            if len(parts) != _EVENT_COLUMNS:
                bad.append(lineno)
                continue
            layer, start, end, style, name, ml, mr, mv, effect = [p.strip() for p in parts[:9]]
            raw = parts[9]
            try:
                start_s = ass_start_to_seconds(start)
                end_s = ass_start_to_seconds(end)
            except ValueError:
                bad.append(lineno)
                continue
            events.append({
                "layer": layer, "start_seconds": str(start_s), "end_seconds": str(end_s),
                "style": style, "name": name, "margin_l": ml, "margin_r": mr, "margin_v": mv,
                "effect": effect, "text_raw": raw, "text": strip_ass_tags(raw),
            })
    if format_columns is None:
        raise MaterialError(f"ASS {source_label}: no [Events] Format line found")
    if bad:
        raise MaterialError(f"ASS {source_label}: {len(bad)} malformed Dialogue lines at lines {bad[:10]}")
    return {"format_columns": format_columns, "variant": format_columns[-1],
            "events": events, "event_count": len(events)}


def read_zip_ass(zip_path, max_members=4, max_uncompressed_bytes=64 * 1024 * 1024):
    zip_path = Path(zip_path)
    try:
        with zipfile.ZipFile(zip_path) as z:
            infos = z.infolist()
            if len(infos) > max_members:
                raise MaterialError(f"ZIP {zip_path}: {len(infos)} members exceeds limit {max_members}")
            ass_members = [i for i in infos if i.filename.lower().endswith(".ass")]
            if len(ass_members) != 1:
                raise MaterialError(
                    f"ZIP {zip_path}: expected exactly one .ass member, got {[i.filename for i in infos]}")
            info = ass_members[0]
            if info.file_size > max_uncompressed_bytes:
                raise MaterialError(
                    f"ZIP {zip_path}: member {info.filename} uncompressed size {info.file_size} exceeds limit")
            data = z.read(info)
    except zipfile.BadZipFile as e:
        raise MaterialError(f"ZIP corrupt or bad CRC: {zip_path}: {e}") from e
    parsed = parse_ass_bytes(data, f"{zip_path.name}:{info.filename}")
    parsed["member_name"] = info.filename
    parsed["ass_sha256"] = hashlib.sha256(data).hexdigest()
    parsed["zip_sha256"] = sha256_file(zip_path)
    return parsed