"""Title normalization and ASS text handling for the observed export variants."""
import html
import re
from decimal import Decimal

_SEPARATORS = str.maketrans({"|": " ", "｜": " ", "_": " "})
_ASS_TAG = re.compile(r"\{\\[^}]*\}")
_WS = re.compile(r"\s+")


def normalize_title(title):
    text = html.unescape(title).translate(_SEPARATORS)
    return _WS.sub(" ", text).strip()


def strip_ass_tags(text):
    text = _ASS_TAG.sub("", text)
    return text.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")


def ass_start_to_seconds(start):
    parts = start.strip().split(":")
    if len(parts) != 3:
        raise ValueError(f"bad ASS timestamp: {start!r}")
    h, m, s = parts
    return Decimal(h) * 3600 + Decimal(m) * 60 + Decimal(s)