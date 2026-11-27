"""Deterministic tool result freeze (freeze-key-v2): same request, same verifiable original bytes.

Key spec v2 defines the exact canonical bytes and number domain so both this module and the Java
EvaluationToolFreeze derive one key for the same logical request: object keys sorted; strings use
each language's standard JSON escaping; integers (any magnitude) as plain digits; floats
normalized to one form (integral values become integer digits, other finite values become
"first-digit[.tail]e±two-digit-exponent" with trailing zeros stripped; -0.0 normalizes to 0;
non-finite values and magnitudes outside [1e-30, 1e30] are refused). The v1 golden vector
(integer/string payloads) is byte-identical under v2; payloads carrying floats must use v2.

A cache miss is an explicit failure: no nearest match, no silent material substitution, no
on-the-spot paid recomputation. The store is immutable: the same key with the same bytes is an
idempotent success, the same key with different bytes is refused with the original untouched; all
reads and writes are anchored inside the real store root (reparse points that resolve outside are
refused). Different controllers may pick different tool steps; keys are request-derived and
model-independent.
"""
import hashlib
import json
import math
import os
import re
import tempfile
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from ..util import ensure_real_path

KEY_SPEC_VERSION = "freeze-key-v2"
REQUIRED_MATERIAL_FIELDS = ("video_sha256", "comment_sha256", "danmaku_sha256")
KEY_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
# Tool-scale arguments stay far inside this band; outside it the cross-language representation
# domain would be untestable, so it is a controlled refusal rather than a best-effort key.
NUMBER_MAGNITUDE_BOUNDS = (1e-30, 1e30)


class ToolFreezeMiss(Exception):
    """A missing (or corrupt) frozen artifact: an explicit refusal, never a fallback."""


class FreezeWriteRefused(Exception):
    """Write refusals: invalid key, path escape, or a same-key different-bytes conflict."""


def _canonical_number(value):
    if not math.isfinite(value):
        raise FreezeWriteRefused("freeze key number domain: non-finite values are refused")
    if value == 0:
        return "0"  # covers -0.0 by value
    magnitude = abs(value)
    if magnitude < NUMBER_MAGNITUDE_BOUNDS[0] or magnitude > NUMBER_MAGNITUDE_BOUNDS[1]:
        raise FreezeWriteRefused("freeze key number domain: magnitude outside [1e-30,1e30]")
    text = repr(value)
    negative = text.startswith("-")
    if negative:
        text = text[1:]
    if "e" in text:
        mantissa, _, exponent_text = text.partition("e")
        exp = int(exponent_text.replace("+", ""))
    else:
        exp = 0
        mantissa = text
    if "." in mantissa:
        whole, _, fraction = mantissa.partition(".")
        digits = whole + fraction
        exp -= len(fraction)
    else:
        digits = mantissa
    digits = digits.lstrip("0") or "0"
    while len(digits) > 1 and digits.endswith("0"):
        digits = digits[:-1]
        exp += 1
    sign = "-" if negative else ""
    if exp >= 0:
        return sign + digits + "0" * exp
    body = digits[0]
    if len(digits) > 1:
        body += "." + digits[1:]
    exponent_text = f"{-exp:02d}" if -exp < 100 else str(-exp)
    return f"{sign}{body}e-{exponent_text}"


def _canonical_string(value):
    return json.dumps(value, ensure_ascii=False)


def _emit(value, out):
    if value is None:
        out.append("null")
    elif isinstance(value, bool):
        out.append("true" if value else "false")
    elif isinstance(value, str):
        out.append(_canonical_string(value))
    elif isinstance(value, int):
        out.append(str(value))
    elif isinstance(value, float):
        out.append(_canonical_number(value))
    elif isinstance(value, dict):
        out.append("{")
        for index, key in enumerate(sorted(value)):
            if index:
                out.append(",")
            if not isinstance(key, str):
                raise FreezeWriteRefused("freeze key object keys must be strings")
            out.append(_canonical_string(key))
            out.append(":")
            _emit(value[key], out)
        out.append("}")
    elif isinstance(value, (list, tuple)):
        out.append("[")
        for index, item in enumerate(value):
            if index:
                out.append(",")
            _emit(item, out)
        out.append("]")
    else:
        raise FreezeWriteRefused(f"freeze key payload contains an unsupported type: {type(value).__name__}")


def canonical_bytes(payload):
    out = []
    _emit(payload, out)
    return "".join(out).encode("utf-8")


def freeze_key(tool, arguments, material, task_clock, task_window, sampling_strategy,
               tool_config_version):
    for field in REQUIRED_MATERIAL_FIELDS:
        value = material.get(field)
        if not isinstance(value, str) or not value:
            raise ToolFreezeMiss(f"material binding incomplete for a freeze key: missing {field}")
    payload = {
        "tool": tool,
        "arguments": arguments,
        "material": {field: material[field] for field in REQUIRED_MATERIAL_FIELDS},
        "task_clock": task_clock,
        "task_window": task_window,
        "sampling_strategy": sampling_strategy,
        "tool_config_version": tool_config_version,
    }
    return hashlib.sha256(canonical_bytes(payload)).hexdigest()


class FrozenStore:
    """Content-addressed immutable store: <key>.json plus a <key>.sha256 sidecar guarding the bytes."""

    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.root = self.root.resolve()
        self._lock = threading.Lock()

    def _validated_path(self, key, writing=False, suffix=".json"):
        refused = (FreezeWriteRefused if writing else ToolFreezeMiss)(
            "frozen artifact path is not contained in the store root")
        if not isinstance(key, str) or not KEY_PATTERN.match(key):
            raise (FreezeWriteRefused("freeze keys must be 64 lowercase hex characters") if writing
                   else ToolFreezeMiss("freeze keys must be 64 lowercase hex characters"))
        try:
            # ensure_real_path refuses reparse points (symlink/junction) on EVERY component,
            # including the final artifact name itself.
            path = ensure_real_path(self.root, f"{key}{suffix}")
        except Exception as error:
            raise refused from error
        # Belt and braces: an existing artifact that resolves outside the real root is refused.
        if path.is_symlink() or os.path.isjunction(path):
            raise refused
        if path.exists():
            real = path.resolve()
            anchored = self.root
            if not str(real).lower().startswith(str(anchored).lower()):
                raise refused
        return path

    def _sidecar(self, key, writing=False):
        # The digest sidecar gets the SAME containment proof as the payload: a link planted over
        # <key>.sha256 pointing outside the root must be refused on read and on write.
        return self._validated_path(key, writing=writing, suffix=".sha256")

    def put(self, key, data):
        if not isinstance(data, (bytes, bytearray)) or not data:
            raise FreezeWriteRefused("frozen artifacts are stored as non-empty bytes")
        data = bytes(data)
        with self._lock:
            path = self._validated_path(key, writing=True)
            sidecar = self._sidecar(key, writing=True)
            expected = hashlib.sha256(data).hexdigest()
            if path.exists():
                existing = path.read_bytes()
                if existing != data:
                    raise FreezeWriteRefused(
                        f"freeze key {key} already holds different bytes; the stored original was left untouched")
                if not sidecar.exists() or sidecar.read_text(encoding="ascii").strip() != expected:
                    self._atomic_sidecar(sidecar, expected, key)
                return
            # Every path the write touches - payload, sidecar, and BOTH .tmp staging files -
            # gets the same containment proof. A link planted over a .tmp name must be refused
            # before anything is written through it.
            payload_tmp = self._validated_path(key, writing=True, suffix=".json.tmp")
            sidecar_tmp = self._validated_path(key, writing=True, suffix=".sha256.tmp")
            payload_tmp.write_bytes(data)
            sidecar_tmp.write_text(expected, encoding="ascii")
            os.replace(payload_tmp, path)
            os.replace(sidecar_tmp, sidecar)

    def _atomic_sidecar(self, sidecar, expected, key):
        tmp = self._validated_path(key, writing=True, suffix=".sha256.tmp")
        tmp.write_text(expected, encoding="ascii")
        os.replace(tmp, sidecar)

    def get(self, key):
        with self._lock:
            path = self._validated_path(key)
            if not path.is_file():
                raise ToolFreezeMiss(f"no frozen result for key {key}")
            data = path.read_bytes()
            sidecar = self._sidecar(key)
            if not sidecar.is_file():
                raise ToolFreezeMiss(f"frozen result for {key} has no integrity sidecar")
            expected = sidecar.read_text(encoding="ascii").strip()
            actual = hashlib.sha256(data).hexdigest()
            if actual != expected:
                raise ToolFreezeMiss(
                    f"frozen result for {key} fails its integrity sidecar; repair before reading")
            return data

    def digest(self, key):
        return hashlib.sha256(self.get(key)).hexdigest()

    def manifest(self):
        if not self.root.is_dir():
            return {"key_spec_version": KEY_SPEC_VERSION, "keys": [], "count": 0}
        keys = sorted(p.stem for p in self.root.glob("*.json") if p.name != "MANIFEST.json")
        return {"key_spec_version": KEY_SPEC_VERSION, "keys": keys, "count": len(keys)}


def _make_handler(store):
    class FreezeHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            key = self.path.removeprefix("/freeze/").strip("/")
            try:
                data = store.get(key)
            except (ToolFreezeMiss, ValueError):
                self.send_response(409)
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt, *args):  # keep test output clean
            pass

    return FreezeHandler


@contextmanager
def serve_forever(store, host="127.0.0.1", port=0):
    """Loopback replay server: GET /freeze/<key> returns the stored bytes verbatim, 409 on a miss."""
    server = ThreadingHTTPServer((host, port), _make_handler(store))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
