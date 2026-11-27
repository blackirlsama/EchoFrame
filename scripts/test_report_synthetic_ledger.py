"""Public synthetic ledger regression; no Canon paths or historical files."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from report_evaluation.admission import budget


def row(rid, status, reserved, actual):
    item = {
        "reservation_id": rid, "exit": "chat:127.0.0.1:synthetic", "model": "synthetic",
        "status": status, "reserved_fen": reserved, "usage_tokens": 10 if actual is not None else None,
        "actual_fen": actual, "price_identity": "a" * 64,
        "price_input_fen_per_million": 200, "price_output_fen_per_million": 800,
        "attribution": "direct", "admitted_at": "2026-11-01T00:00:00Z",
        "request_body_bytes": 10, "request_body_sha256": "b" * 64,
    }
    if actual is None:
        item["fail_reason"] = "SYNTHETIC_RETAINED"
    return item


def write_ledger(path, tamper=False):
    rows = [row("fee-synthetic-1", "SETTLED", 5, 113),
            row("fee-synthetic-2", "FAILED_RETAINED", 19, None)]
    if tamper:
        rows[0]["actual_fen"] += 1
        rows[1]["reserved_fen"] -= 1
    doc = {
        "schema": "fee-ledger-v2", "total_fen": 100_000, "pause_fen": 10_000,
        "carried_in": {"source": "synthetic public fixture", "settled_fen": 560,
                       "retained_fen": 78, "rows_sha256": budget.rows_digest(
                           [row("fee-synthetic-1", "SETTLED", 5, 113),
                            row("fee-synthetic-2", "FAILED_RETAINED", 19, None)]),
                       "rows_count": 2},
        "reservations": rows,
    }
    Path(path).write_text(json.dumps(doc), encoding="utf-8")


class SyntheticLedgerTest(unittest.TestCase):
    def test_envelope_carries_once(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "ledger.json"
            write_ledger(p)
            ledger = budget.FeeLedger(p, total_fen=100_000, pause_fen=10_000, prices={})
            try:
                self.assertEqual(770, ledger.conservative_used_fen())
                self.assertEqual(132, ledger.batch_new_spend_fen())
                self.assertEqual(673, ledger.known_settled_fen())
            finally:
                ledger.close()
            reopened = budget.FeeLedger(p, total_fen=100_000, pause_fen=10_000, prices={})
            try:
                self.assertEqual(770, reopened.conservative_used_fen())
            finally:
                reopened.close()

    def test_same_sum_rewrite_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "ledger.json"
            write_ledger(p)
            doc = json.loads(p.read_text(encoding="utf-8"))
            doc["reservations"][0]["actual_fen"] += 1
            doc["reservations"][1]["reserved_fen"] -= 1
            p.write_text(json.dumps(doc), encoding="utf-8")
            with self.assertRaises(budget.LedgerCorrupted):
                budget.FeeLedger(p, total_fen=100_000, pause_fen=10_000, prices={}).close()

    def test_ten_thousand_line_refuses(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "line.json"
            p.write_text(json.dumps({
                "schema": "fee-ledger-v2", "total_fen": 100_000, "pause_fen": 10_000,
                "carried_in": {"source": "synthetic line", "settled_fen": 9_999, "retained_fen": 0},
                "reservations": []}), encoding="utf-8")
            ledger = budget.FeeLedger(p, total_fen=100_000, pause_fen=10_000,
                                      prices={"chat:probe": {"input_fen_per_million": 1_000_000,
                                                             "output_fen_per_million": 1_000_000}})
            try:
                with self.assertRaises(budget.PauseRequested):
                    ledger.admit("chat:probe", 1, 1)
            finally:
                ledger.close()


if __name__ == "__main__":
    unittest.main()
