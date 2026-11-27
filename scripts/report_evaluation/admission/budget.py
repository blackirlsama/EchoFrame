"""Fee admission ledger (fee-ledger-v2): integer-fen reservations covering every model-role exit.

Single writer per ledger file: construction takes an OS-level exclusive lock on the adjacent
``<ledger>.lock`` file BEFORE the first byte is read; a second instance (same process, another
process, or another thread) is refused with LedgerBusy until the owner closes. Every reservation
freezes its pricing identity (exit key, per-million price snapshot, and that identity's SHA-256)
at admit time, so a later reopen with a different price table can never silently re-price old
requests. Semantics: reservation precedes dispatch; known usage settles by the record's own frozen
snapshot per side (conservative ceiling); missing/malformed usage is USAGE_UNKNOWN (full
reservation retained, tokens null, all further paid dispatch blocked); timeouts, cancellations and
failures keep the reservation (FAILED_RETAINED); a retry is a fresh admission; the pause line
(configured per stage; the current Canon stage uses 10,000 fen = 100 CNY) refuses any admission
whose conservative total would reach it; the hard limit (100,000 fen = 1,000 CNY for the current
project) refuses anything past it; both providers share one envelope; restart reloads
open reservations under the lock; duplicate settlement is refused; corrupted ledger files are
refused in full with their bytes untouched. A ``carried_in`` block carries prior-round spend and
may carry ``rows_sha256``, a canonical digest of the historical rows that refuses any rewrite. Amounts are integers, never booleans, never negative;
oversized estimates are a controlled refusal rather than silent arithmetic.
"""
import hashlib
import json
import os
import uuid
from pathlib import Path

from ..util import atomic_write_json, utc_now_iso

LEDGER_SCHEMA = "fee-ledger-v2"
RESERVATION_STATUSES = {"OPEN", "SETTLED", "USAGE_UNKNOWN", "FAILED_RETAINED"}
# Refuse absurd estimates before any arithmetic: tool-scale arguments never reach this.
TOKEN_ESTIMATE_CEILING = 1_000_000_000_000


class BudgetError(Exception):
    pass


class DispatchBlocked(BudgetError):
    pass


class PauseRequested(DispatchBlocked):
    pass


class BudgetExhausted(DispatchBlocked):
    pass


class NeedsPricing(DispatchBlocked):
    pass


class UsageUnknown(BudgetError):
    pass


class DuplicateSettlement(BudgetError):
    pass


class LedgerCorrupted(BudgetError):
    pass


class LedgerBusy(DispatchBlocked):
    pass


def _ceil_div(a, b):
    return -(-a // b)


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


ROW_DIGEST_FIELDS = (
    "reservation_id", "exit", "model", "status", "reserved_fen", "usage_tokens",
    "actual_fen", "price_identity", "price_input_fen_per_million",
    "price_output_fen_per_million", "attribution", "admitted_at",
    "request_body_bytes", "request_body_sha256", "fail_reason",
)


def rows_digest(reservations):
    """Canonical digest over historical reservation rows; shared with the Java ledger.

    Field order is fixed, null/absent is ``-``, fields are separated by US and rows by LF.
    Any value change (including one that keeps the batch sum) changes this digest.
    """
    digest = hashlib.sha256()
    for record in reservations:
        for field in ROW_DIGEST_FIELDS:
            value = record.get(field) if isinstance(record, dict) else None
            text = "-" if value is None else str(value)
            digest.update(text.encode("utf-8"))
            digest.update(b"\x1f")
        digest.update(b"\n")
    return digest.hexdigest()


def _lock_file(path):
    """Open and exclusively lock the adjacent lock file; return the handle or raise LedgerBusy."""
    lock_path = Path(str(path) + ".lock")
    handle = open(lock_path, "a+b")
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except (OSError, ValueError):
        handle.close()
        raise LedgerBusy(
            f"another writer owns the fee ledger; a second instance is refused: {path}")
    return handle


class FeeLedger:
    def __init__(self, path, total_fen, pause_fen, prices, carried_in=None, batch_cap=None):
        self.path = Path(path)
        if not _is_int(total_fen) or total_fen <= 0:
            raise BudgetError(f"total_fen must be a positive integer")
        if not _is_int(pause_fen) or not (0 < pause_fen <= total_fen):
            raise BudgetError(f"pause_fen must be within (0, total_fen]")
        self.total_fen = total_fen
        self.pause_fen = pause_fen
        self.carried_in = carried_in
        self.batch_cap = batch_cap
        self.prices = {}
        for key, value in (prices or {}).items():
            p_in = value.get("input_fen_per_million")
            p_out = value.get("output_fen_per_million")
            if not _is_int(p_in) or p_in < 0 or not _is_int(p_out) or p_out < 0:
                raise BudgetError(f"price for {key} must be non-negative integer fen per million tokens")
            self.prices[key] = {"input_fen_per_million": p_in, "output_fen_per_million": p_out}
        self.reservations = []
        self._lock_handle = _lock_file(self.path)
        try:
            if self.path.exists():
                self._load()
        except BaseException:
            self.close()
            raise

    def close(self):
        """Release the single-writer lock; the next opener reloads the persisted state."""
        handle = getattr(self, "_lock_handle", None)
        if handle is not None:
            try:
                if os.name == "nt":
                    import msvcrt
                    handle.seek(0)
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            except (OSError, ValueError):
                pass
            handle.close()
            self._lock_handle = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()

    # ---- persistence ----------------------------------------------------------------------

    def _load(self):
        try:
            doc = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            raise LedgerCorrupted(
                f"fee ledger file is unreadable; dispatch stays blocked until a human repairs it: "
                f"{self.path}: {type(e).__name__}") from e
        self._validate_document(doc)
        self.reservations = doc["reservations"]

    def _validate_document(self, doc):
        if not isinstance(doc, dict) or doc.get("schema") != LEDGER_SCHEMA:
            raise LedgerCorrupted(f"fee ledger file is not a {LEDGER_SCHEMA} document: {self.path}")
        if not _is_int(doc.get("total_fen")) or doc["total_fen"] != self.total_fen \
                or not _is_int(doc.get("pause_fen")) or doc["pause_fen"] != self.pause_fen:
            raise LedgerCorrupted(f"fee ledger file does not match this ledger's identity: {self.path}")
        reservations = doc.get("reservations")
        if not isinstance(reservations, list):
            raise LedgerCorrupted(f"fee ledger reservations must be a list: {self.path}")
        seen_ids = set()
        for record in reservations:
            self._validate_reservation(record, seen_ids)
        self._validate_carried(doc, reservations)
        self._validate_batch_cap(doc)

    def _validate_carried(self, doc, reservations):
        block = doc.get("carried_in")
        if block is None:
            if self.carried_in is not None:
                raise LedgerCorrupted(
                    f"fee ledger declared carried-in but the file has no carried_in block: {self.path}")
            return
        if not isinstance(block, dict):
            raise LedgerCorrupted(f"fee ledger carried_in block is malformed: {self.path}")
        source = block.get("source")
        settled = block.get("settled_fen")
        retained = block.get("retained_fen")
        rows_sha = block.get("rows_sha256")
        rows_count = block.get("rows_count", 0)
        if rows_sha is None and rows_count not in (0, None) \
                or rows_sha is not None and (not _is_int(rows_count) or rows_count < 1
                                              or rows_count > len(reservations)):
            raise LedgerCorrupted(f"fee ledger carried_in row count is malformed: {self.path}")
        if not isinstance(source, str) or not source.strip() or not _is_int(settled) \
                or settled < 0 or not _is_int(retained) or retained < 0:
            raise LedgerCorrupted(f"fee ledger carried_in block is malformed: {self.path}")
        if rows_sha is not None and (not isinstance(rows_sha, str)
                                     or len(rows_sha) != 64
                                     or any(c not in "0123456789abcdef" for c in rows_sha)):
            raise LedgerCorrupted(f"fee ledger carried_in.rows_sha256 is malformed: {self.path}")
        if rows_sha is not None and rows_digest(reservations[:rows_count]) != rows_sha:
            raise LedgerCorrupted(
                f"fee ledger historical rows were rewritten; dispatch stays blocked: {self.path}")
        if self.carried_in is not None:
            declared = self.carried_in
            if (declared.get("source") != source or declared.get("settled_fen") != settled
                    or declared.get("retained_fen") != retained
                    or declared.get("rows_sha256") != rows_sha
                    or declared.get("rows_count", 0) != rows_count):
                raise LedgerCorrupted(
                    f"fee ledger carried_in does not match this opener's declaration: {self.path}")
        self.carried_in = {"source": source, "settled_fen": settled,
                           "retained_fen": retained, "rows_sha256": rows_sha,
                           "rows_count": rows_count}

    def _validate_batch_cap(self, doc):
        block = doc.get("batch_cap")
        if block is None:
            return
        if not isinstance(block, dict) or not isinstance(block.get("source"), str) \
                or not block["source"].strip() or not _is_int(block.get("new_spend_cap_fen")) \
                or block["new_spend_cap_fen"] < 1:
            raise LedgerCorrupted(f"fee ledger batch_cap block is malformed: {self.path}")
        if self.batch_cap is not None and (
                self.batch_cap.get("source") != block["source"]
                or self.batch_cap.get("new_spend_cap_fen") != block["new_spend_cap_fen"]):
            raise LedgerCorrupted(
                f"fee ledger batch_cap does not match this opener's declaration: {self.path}")
        self.batch_cap = {"source": block["source"],
                          "new_spend_cap_fen": block["new_spend_cap_fen"]}

    @staticmethod
    def _validate_reservation(record, seen_ids):
        where = "reservation"
        if not isinstance(record, dict):
            raise LedgerCorrupted(f"{where}: must be an object")
        rid = record.get("reservation_id")
        if not isinstance(rid, str) or not rid:
            raise LedgerCorrupted(f"{where}.reservation_id: must be a non-empty string")
        if rid in seen_ids:
            raise LedgerCorrupted(f"{where}.reservation_id: duplicate reservation id")
        seen_ids.add(rid)
        if not isinstance(record.get("exit"), str) or not record["exit"]:
            raise LedgerCorrupted(f"{where}.exit: must be a non-empty string")
        status = record.get("status")
        if status not in RESERVATION_STATUSES:
            raise LedgerCorrupted(f"{where}.status: unknown status")
        reserved = record.get("reserved_fen")
        if not _is_int(reserved) or reserved < 0:
            raise LedgerCorrupted(f"{where}.reserved_fen: must be a non-negative integer")
        usage = record.get("usage_tokens")
        actual = record.get("actual_fen")
        if status == "SETTLED":
            if not _is_int(usage) or usage < 0 or not _is_int(actual) or actual < 0:
                raise LedgerCorrupted(
                    f"{where} SETTLED: usage_tokens and actual_fen must be non-negative integers")
        else:
            if usage is not None or actual is not None:
                raise LedgerCorrupted(f"{where} {status}: usage_tokens and actual_fen must be null")
            if status == "FAILED_RETAINED" and not (isinstance(record.get("fail_reason"), str)
                                                    and record["fail_reason"]):
                raise LedgerCorrupted(f"{where} FAILED_RETAINED: fail_reason must be a non-empty string")
        identity = record.get("price_identity")
        if not isinstance(identity, str) or len(identity) != 64:
            raise LedgerCorrupted(f"{where}.price_identity: must be a 64-hex string")
        snapshot_in = record.get("price_input_fen_per_million")
        snapshot_out = record.get("price_output_fen_per_million")
        if not _is_int(snapshot_in) or snapshot_in < 0 or not _is_int(snapshot_out) or snapshot_out < 0:
            raise LedgerCorrupted(f"{where} price snapshot: must be non-negative integers")
        for field in ("attribution", "admitted_at"):
            if not isinstance(record.get(field), str) or not record[field]:
                raise LedgerCorrupted(f"{where}.{field}: must be a non-empty string")

    def _save(self):
        document = {
            "schema": LEDGER_SCHEMA, "total_fen": self.total_fen, "pause_fen": self.pause_fen,
            "reservations": self.reservations}
        if self.carried_in is not None:
            document["carried_in"] = dict(self.carried_in)
        if self.batch_cap is not None:
            document["batch_cap"] = dict(self.batch_cap)
        atomic_write_json(self.path, document)

    # ---- helpers --------------------------------------------------------------------------

    def _price_for(self, exit_key):
        price = self.prices.get(exit_key)
        if price is None:
            raise NeedsPricing(f"no verified price table entry for exit {exit_key}; dispatch blocked, "
                               "unknown pricing is never treated as zero")
        return price

    def _reserved_fen(self, price, estimated_input_tokens, estimated_output_tokens):
        for tokens in (estimated_input_tokens, estimated_output_tokens):
            if not _is_int(tokens) or tokens < 1 or tokens > TOKEN_ESTIMATE_CEILING:
                raise DispatchBlocked(
                    f"estimated tokens must be positive integers within the fee domain, got a value "
                    f"outside [1, {TOKEN_ESTIMATE_CEILING}]")
        total = _ceil_div(estimated_input_tokens * price["input_fen_per_million"], 1_000_000) \
            + _ceil_div(estimated_output_tokens * price["output_fen_per_million"], 1_000_000)
        return max(1, total)

    @staticmethod
    def _price_identity(exit_key, price):
        basis = f"{exit_key}|{price['input_fen_per_million']}|{price['output_fen_per_million']}"
        return hashlib.sha256(basis.encode("utf-8")).hexdigest()

    def reservation(self, reservation_id):
        for record in self.reservations:
            if record.get("reservation_id") == reservation_id:
                return record
        raise BudgetError(f"unknown reservation: {reservation_id}")

    def reservations_open(self):
        return [r for r in self.reservations if r.get("status") == "OPEN"]

    def known_settled_fen(self):
        total = sum(r.get("actual_fen", 0) for r in self.reservations if r.get("status") == "SETTLED")
        if self.carried_in:
            total += self.carried_in.get("settled_fen", 0)
        return total

    def conservative_used_fen(self):
        """Known settled fees plus every reservation still held (open, unknown, failed in-flight)."""
        total = 0
        for record in self.reservations:
            if record.get("status") == "SETTLED":
                total += record.get("actual_fen", 0)
            else:
                total += record.get("reserved_fen", 0)
        if self.carried_in:
            total += self.carried_in.get("settled_fen", 0) + self.carried_in.get("retained_fen", 0)
        return total

    def batch_new_spend_fen(self):
        carried = 0
        if self.carried_in:
            carried = self.carried_in.get("settled_fen", 0) + self.carried_in.get("retained_fen", 0)
        return self.conservative_used_fen() - carried

    def can_dispatch(self):
        return not any(r.get("status") == "USAGE_UNKNOWN" for r in self.reservations)

    # ---- lifecycle ------------------------------------------------------------------------

    def admit(self, exit_key, estimated_input_tokens, estimated_output_tokens,
              attribution="direct", model=""):
        if any(r.get("status") == "USAGE_UNKNOWN" for r in self.reservations):
            raise DispatchBlocked("an unknown-usage reservation is pending; dispatch stays blocked "
                                  "until a human resolves it")
        price = self._price_for(exit_key)
        reserved = self._reserved_fen(price, estimated_input_tokens, estimated_output_tokens)
        used = self.conservative_used_fen()
        if used + reserved > self.total_fen:
            raise BudgetExhausted(f"admission would push the conservative total to {used + reserved} fen "
                                  f"beyond the {self.total_fen}-fen limit")
        if used + reserved >= self.pause_fen:
            raise PauseRequested(f"admission would reach the {self.pause_fen}-fen pause line "
                                 f"(conservative total {used + reserved} fen); paused for review")
        if self.batch_cap:
            carried = 0
            if self.carried_in:
                carried = self.carried_in.get("settled_fen", 0) + self.carried_in.get("retained_fen", 0)
            new_spend = used + reserved - carried
            if new_spend > self.batch_cap.get("new_spend_cap_fen", 0) > 0:
                raise BudgetExhausted(
                    f"FEE_ADMISSION_BATCH_CAPPED: this batch's new spend would reach {new_spend} fen, "
                    f"beyond its {self.batch_cap['new_spend_cap_fen']}-fen cap")
        record = {
            "reservation_id": f"fee-{uuid.uuid4().hex[:16]}", "exit": exit_key, "model": model,
            "status": "OPEN", "reserved_fen": reserved, "usage_tokens": None, "actual_fen": None,
            "price_identity": self._price_identity(exit_key, price),
            "price_input_fen_per_million": price["input_fen_per_million"],
            "price_output_fen_per_million": price["output_fen_per_million"],
            "attribution": attribution, "admitted_at": utc_now_iso(),
        }
        self.reservations.append(record)
        self._save()
        return dict(record)

    def admit_chat(self, host, model, estimated_input_tokens, estimated_output_tokens):
        """Exit key carries the concrete request model: two controllers on one provider may differ."""
        return self.admit(f"chat:{host}:{model}", estimated_input_tokens, estimated_output_tokens,
                          model=model)

    def admit_vision(self, provider, model, transport, image_count, payload_bytes, output_tokens,
                     attribution="vision"):
        """Stage 4B-2A: the only payable image exit is the registered generic image-input contract
        with a reproducible upper bound (text bytes + images x documented ceiling, output capped by
        the supplier-enforced parameter). Everything else - the dedicated coding-plan VLM contract,
        audio, unknown models - stays a controlled refusal before any dispatch."""
        from . import contracts
        try:
            vision = contracts.vision_contract_for(model, transport)
            if provider != vision["provider"]:
                raise contracts.ContractError(
                    f"provider {provider!r} is not the registered vision provider "
                    f"({vision['provider']!r}); a generic transport is not a provider disguise")
            bound = contracts.vision_bound(image_count, payload_bytes, output_tokens)
        except contracts.ContractError as error:
            raise DispatchBlocked(f"FEE_ADMISSION_NEEDS_PRICING: {error}") from error
        return self.admit(contracts.vision_exit_key(provider, model, transport),
                          bound["input_tokens"], bound["output_tokens"],
                          attribution=attribution, model=model)

    def settle(self, reservation_id, response):
        record = self.reservation(reservation_id)
        if record.get("status") != "OPEN":
            raise DuplicateSettlement(f"reservation {reservation_id} is {record.get('status')!r}, "
                                      "it cannot settle again")
        usage = response.get("usage") if isinstance(response, dict) else None
        prompt = completion = None
        if isinstance(usage, dict):
            prompt = usage.get("prompt_tokens")
            completion = usage.get("completion_tokens")
        if (not _is_int(prompt) or prompt < 0 or not _is_int(completion) or completion < 0
                or prompt > TOKEN_ESTIMATE_CEILING or completion > TOKEN_ESTIMATE_CEILING):
            record["status"] = "USAGE_UNKNOWN"
            record["usage_tokens"] = None
            self._save()
            raise UsageUnknown(f"usage missing, malformed or outside the fee domain for {reservation_id}; "
                               "the full reservation is retained and paid dispatch is blocked")
        # Settlement always uses the record's own frozen price snapshot, never the current table.
        actual = max(1, _ceil_div(prompt * record["price_input_fen_per_million"], 1_000_000)
                     + _ceil_div(completion * record["price_output_fen_per_million"], 1_000_000))
        record["status"] = "SETTLED"
        record["usage_tokens"] = prompt + completion
        record["actual_fen"] = actual
        self._save()
        return dict(record)

    def fail(self, reservation_id, reason):
        record = self.reservation(reservation_id)
        if record.get("status") != "OPEN":
            raise BudgetError(f"reservation {reservation_id} is {record.get('status')!r}, not open")
        # Timeout, cancellation or upstream failure keeps the reservation: in-flight cost counts.
        record["status"] = "FAILED_RETAINED"
        record["fail_reason"] = str(reason) if str(reason).strip() else "unspecified"
        self._save()
        return dict(record)

    def attribution_report(self):
        report = {}
        for record in self.reservations:
            key = record.get("attribution", "direct")
            entry = report.setdefault(key, {"count": 0, "fen": 0})
            entry["count"] += 1
            entry["fen"] += record.get("actual_fen") or record.get("reserved_fen") or 0
        return report
