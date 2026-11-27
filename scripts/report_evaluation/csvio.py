"""Strict CSV record parsing: real records, multiline text, no silent drops."""
import csv
from pathlib import Path

from .util import MaterialError


def read_csv_records(path, required_columns):
    path = Path(path)
    try:
        with open(path, encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f, strict=True)
            header = reader.fieldnames
            if header is None:
                raise MaterialError(f"empty CSV (no header): {path}")
            if any(name is None or not name.strip() for name in header):
                raise MaterialError(f"CSV {path} has an empty header column")
            if len(set(header)) != len(header):
                dupes = sorted({h for h in header if header.count(h) > 1})
                raise MaterialError(f"CSV {path} has duplicate header columns: {dupes}")
            missing = [c for c in required_columns if c not in header]
            if missing:
                raise MaterialError(f"CSV {path} missing columns: {missing}")
            records = []
            for lineno, row in enumerate(reader, start=2):
                if None in row:
                    raise MaterialError(f"CSV {path} line {lineno}: extra fields beyond header")
                if any(v is None for v in row.values()):
                    raise MaterialError(f"CSV {path} line {lineno}: short row (fewer fields than header)")
                records.append(row)
    except UnicodeDecodeError as e:
        raise MaterialError(f"CSV decode error (expected UTF-8): {path}: {e}") from e
    except csv.Error as e:
        raise MaterialError(f"CSV parse error (illegal quoting/structure): {path}: {e}") from e
    return records