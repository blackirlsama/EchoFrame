"""Current-stage Canon ledger: 24 rows, 770 fen, carried block 638, rows 132.

Java and Python must agree on the canonical row digest: a rewrite that keeps the batch sum is
still refused. The source ledger is read-only; only a temporary copy is mutated.
"""
import hashlib
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from report_evaluation.admission import budget

EXPECTED_SOURCE_SHA256 = "ccbe66593429389a4b1ce700cb7f482ce9a1c68bf53c7c5f612120ac05adf30c"


def source():
    raw = os.environ.get("EVAL_HISTORICAL_BATCH_LEDGER", "").strip()
    if not raw:
        raise unittest.SkipTest("inject EVAL_HISTORICAL_BATCH_LEDGER to run the 770-fen carry test")
    path = Path(raw).expanduser().resolve()
    if not path.is_file():
        raise unittest.SkipTest(f"injected ledger missing: {path}")
    return path


def bound_copy(source_path, directory):
    raw = source_path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != EXPECTED_SOURCE_SHA256:
        raise AssertionError(f"source ledger digest changed: {digest}")
    doc = json.loads(raw.decode("utf-8"))
    rows = doc["reservations"]
    doc["carried_in"]["rows_sha256"] = budget.rows_digest(rows)
    doc["carried_in"]["rows_count"] = len(rows)
    copy = Path(directory) / "batch-cumulative.json"
    copy.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    return copy, raw


class CanonLedgerCarryTest(unittest.TestCase):
    def test_twenty_four_rows_open_at_770_and_do_not_double_count(self):
        source_path = source()
        with tempfile.TemporaryDirectory() as directory:
            copy, raw = bound_copy(source_path, directory)
            ledger = budget.FeeLedger(copy, total_fen=100_000, pause_fen=10_000, prices={})
            try:
                self.assertEqual(24, len(ledger.reservations))
                carried = ledger.carried_in
                self.assertEqual(560, carried["settled_fen"])
                self.assertEqual(78, carried["retained_fen"])
                self.assertEqual(770, ledger.conservative_used_fen())
                self.assertEqual(132, ledger.batch_new_spend_fen())
                self.assertEqual(113, ledger.known_settled_fen() - 560)
                self.assertEqual([], ledger.reservations_open())
            finally:
                ledger.close()
            reopened = budget.FeeLedger(copy, total_fen=100_000, pause_fen=10_000, prices={})
            try:
                self.assertEqual(770, reopened.conservative_used_fen())
                self.assertEqual(132, reopened.batch_new_spend_fen())
            finally:
                reopened.close()
            self.assertEqual(raw, source_path.read_bytes())

    def test_same_sum_row_rewrite_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            copy, _ = bound_copy(source(), directory)
            doc = json.loads(copy.read_text(encoding="utf-8"))
            doc["reservations"][0]["actual_fen"] += 1
            doc["reservations"][1]["actual_fen"] -= 1
            copy.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
            with self.assertRaises(budget.LedgerCorrupted):
                budget.FeeLedger(copy, total_fen=100_000, pause_fen=10_000, prices={}).close()

    def test_next_reservation_at_the_ten_thousand_line_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic-line.json"
            path.write_text(json.dumps({
                "schema": "fee-ledger-v2", "total_fen": 100_000, "pause_fen": 10_000,
                "carried_in": {"source": "synthetic line fixture", "settled_fen": 9_999,
                               "retained_fen": 0},
                "reservations": []}), encoding="utf-8")
            ledger = budget.FeeLedger(path, total_fen=100_000, pause_fen=10_000,
                                      prices={"chat:probe": {"input_fen_per_million": 1_000_000,
                                                             "output_fen_per_million": 1_000_000}})
            try:
                with self.assertRaises(budget.PauseRequested):
                    ledger.admit("chat:probe", 1, 1)
            finally:
                ledger.close()


if __name__ == "__main__":
    unittest.main()
