"""Report-evaluation CLI: inventory / validate / run / import-product / summarize. No network, no model calls."""
import argparse
import json
import sys
from pathlib import Path

from report_evaluation import cases, datasets, ledger, offline, product_bridge, summary
from report_evaluation.admission import check as admission_check
from report_evaluation.util import CaseError, OutOfBoundsError, ReportEvalError, ensure_real_path


def _print(obj):
    print(json.dumps(obj, ensure_ascii=False, sort_keys=True))


def cmd_inventory(args):
    index = datasets.build_inventory(args.dataset_root, args.source_root, args.output, args.ffprobe)
    _print({"videos": index["lineage"]["independent_video_samples"],
            "dataset_comments": index["totals"]["dataset_comments"],
            "dataset_danmaku": index["totals"]["dataset_danmaku"],
            "source_comments": index["totals"]["source_comments"],
            "source_danmaku": index["totals"]["source_danmaku"],
            "measured_duration_seconds_total": index["totals"]["measured_duration_seconds_total"],
            "ffprobe": index["source"]["ffprobe"]["state"],
            "findings": len(index["findings"]),
            "output": str(Path(args.output) / "index.json")})
    return 3 if index["findings"] else 0


def _prepare(case, mode):
    try:
        return cases.prepare_case(case, mode)
    except CaseError as e:
        return [str(e)], None, None


def cmd_validate(args):
    doc = cases.load_cases(args.cases)
    results = []
    invalid = []
    for case in doc["cases"]:
        errors, fp, _binding = _prepare(case, args.mode)
        if errors:
            invalid.append({"case_id": case.get("case_id"), "errors": errors})
        results.append({"case_id": case.get("case_id"), "valid": not errors, "errors": errors,
                        "fingerprint": fp, "experiment_id": cases.experiment_id(fp) if fp else None,
                        "semantic_eligibility": cases.semantic_eligibility(case) if not errors else None})
        if args.mode == "product" and not errors:
            # The real fingerprint binds the capture bytes, which validate does not read.
            results[-1].update({"fingerprint": None, "experiment_id": None,
                                "fingerprint_note": "product fingerprints bind the capture bundle; use import-product"})
    _print({"cases": len(results), "invalid": len(invalid), "results": results})
    return 2 if invalid else 0


def cmd_run(args):
    doc = cases.load_cases(args.cases)
    prepared = []
    invalid = []
    for case in doc["cases"]:
        errors, fp, binding = _prepare(case, args.mode)
        if errors:
            invalid.append({"case_id": case.get("case_id"), "errors": errors})
        else:
            prepared.append((case, fp, binding))
    if invalid:
        _print({"error": "invalid cases", "invalid": invalid})
        return 2
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    created = []
    for case, fp, binding in prepared:
        if cases.resolve_material_inventory(case) != binding:
            raise CaseError(f"material changed between validation and execution for case {case['case_id']}")
        exp_id = cases.experiment_id(fp)
        trial = ledger.create_trial(output, case, fp, exp_id)
        offline.run_offline_case(trial, case)
        summary_path = trial.dir / "trial_summary.json"
        final = json.loads(summary_path.read_text(encoding="utf-8"))["final_status"] if summary_path.is_file() else "INTERRUPTED"
        created.append({"trial_id": trial.trial_id, "case_id": case["case_id"],
                        "experiment_id": exp_id, "final_status": final})
    _print({"trials_created": len(created), "output": str(output), "trials": created})
    return 0


def _bundle_id(root):
    """Read only the id, so a bundle that fails strict validation can still be matched to its case."""
    try:
        manifest = json.loads(ensure_real_path(root, "bundle.json").read_text(encoding="utf-8"))
    except (OutOfBoundsError, OSError, ValueError):
        return None
    return manifest.get("bundle_id") if isinstance(manifest, dict) else None


def cmd_import_product(args):
    doc = cases.load_cases(args.cases)
    invalid = [{"case_id": c.get("case_id"), "errors": errors}
               for c in doc["cases"] if (errors := cases.validate_case(c, "product"))]
    if invalid:
        _print({"error": "invalid cases", "invalid": invalid})
        return 2

    roots = {}
    for raw in args.bundle:
        root = Path(raw)
        bundle_id = _bundle_id(root)
        if not isinstance(bundle_id, str) or not bundle_id.strip():
            _print({"error": "unidentifiable bundle", "bundle": str(root),
                    "message": "bundle.json is unreadable or has no bundle_id; cannot bind it to a case"})
            return 2
        if bundle_id in roots:
            _print({"error": "duplicate bundle_id", "bundle_id": bundle_id})
            return 2
        roots[bundle_id] = root
    wanted = {c["product_capture"]["bundle_id"] for c in doc["cases"]}
    missing = sorted(wanted - set(roots))
    unused = sorted(set(roots) - wanted)
    if missing or unused:
        _print({"error": "case/bundle mismatch", "missing_bundles": missing, "unused_bundles": unused})
        return 2

    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    created = []
    for case in doc["cases"]:
        bundle_id = case["product_capture"]["bundle_id"]
        trial, verified = product_bridge.import_capture(output, case, roots[bundle_id])
        summary_path = trial.dir / "trial_summary.json"
        final = json.loads(summary_path.read_text(encoding="utf-8"))["final_status"] if summary_path.is_file() else None
        created.append({"trial_id": trial.trial_id, "case_id": case["case_id"],
                        "experiment_id": trial.meta["experiment_id"], "bundle_id": bundle_id,
                        "capture_verified": verified, "final_status": final})
    result = summary.summarize_run(output, args.prices, strict=False)
    _print({"trials_created": len(created), "evidence_level": product_bridge.EVIDENCE_LEVEL,
            "adapter_version": product_bridge.ADAPTER_VERSION,
            "unverified_captures": sum(1 for c in created if not c["capture_verified"]),
            "classifications": result["classifications"],
            "corrupted_trial_count": result["corrupted_trial_count"],
            "output": str(output), "summary": str(output / "summary.json"), "trials": created})
    return 3 if result["corrupted_trial_count"] else 0


def cmd_summarize(args):
    result = summary.summarize_run(args.input, args.prices, strict=False)
    _print({"trial_count": result["trial_count"], "classifications": result["classifications"],
            "corrupted_trial_count": result["corrupted_trial_count"],
            "batches": len(result["batches"]), "batch_split_case_ids": result["batch_split_case_ids"],
            "billing_status": result["billing_status"],
            "output": str(Path(args.input) / "summary.json")})
    return 3 if result["corrupted_trial_count"] else 0


def cmd_admission_check(args):
    """Stage 4B-1 offline admission check. Exit 0 = OFFLINE_ADMISSION_READY,
    2 = config/input refused, 3 = OFFLINE_ADMISSION_GAPS (blocking findings)."""
    return admission_check.run_check(args.config, args.output)


def cmd_admission_prepare(args):
    """Stage 4B-1 offline admission prepare: snapshots, freeze seeds, live-compat checklist.
    Exit 0 = written, 2 = config/input refused."""
    return admission_check.run_prepare(args.config, args.output)


def cmd_admission_rebuild(args):
    """Repair-07 cross-language ledger check: rebuild the generation batch ledger from the
    per-run ORIGINALS (full verification, formal material bindings consumed) and, when a
    declared batch index is given, compare it field-by-field — the same comparison both sides
    run, so a tampered copy is refused by either language. Exit 0 = matched/rebuilt,
    2 = rebuild refused (the directory failed verification), 4 = field-by-field mismatch."""
    from report_evaluation.admission import run_binding
    with open(args.config, encoding="utf-8") as handle:
        bindings = run_binding.bindings_from_config(json.load(handle))
    try:
        rebuilt = run_binding.rebuild_index(args.runs_root, formal_bindings=bindings)
    except ValueError as error:
        _print({"error": "REBUILD_REFUSED", "detail": str(error)})
        return 2
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(rebuilt, ensure_ascii=False, indent=1), encoding="utf-8")
    if args.against:
        with open(args.against, encoding="utf-8") as handle:
            declared = json.load(handle)
        declared_index = declared.get("index", declared) if isinstance(declared, dict) else declared
        if not run_binding.batch_index_matches(rebuilt, declared_index):
            _print({"error": "BATCH_MISMATCH",
                    "detail": "the rebuilt ledger does not match the declared batch index"})
            return 4
        _print({"verdict": "BATCH_MATCHED", "run_count": rebuilt["run_count"],
                "refusal_count": rebuilt["refusal_count"]})
    else:
        _print(rebuilt)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("inventory", help="Read-only index of formal dataset and collection source")
    p.add_argument("--dataset-root", required=True)
    p.add_argument("--source-root", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--ffprobe", default=None, help="Explicit ffprobe path; otherwise PATH lookup")
    p.set_defaults(func=cmd_inventory)

    p = sub.add_parser("validate", help="Validate report cases")
    p.add_argument("--cases", required=True)
    p.add_argument("--mode", default="offline", choices=["offline", "product"])
    p.set_defaults(func=cmd_validate)

    p = sub.add_parser("run", help="Run cases with the deterministic offline adapter")
    p.add_argument("--cases", required=True)
    p.add_argument("--mode", default="offline", choices=["offline"])
    p.add_argument("--output", required=True)
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("import-product", help="Import real product capture bundles into the trial ledger")
    p.add_argument("--cases", required=True)
    p.add_argument("--bundle", required=True, action="append", help="Capture directory; repeat per bundle")
    p.add_argument("--output", required=True)
    p.add_argument("--prices", default=None)
    p.set_defaults(func=cmd_import_product)

    p = sub.add_parser("summarize", help="Summarize recorded trials")
    p.add_argument("--input", required=True)
    p.add_argument("--prices", default=None)
    p.set_defaults(func=cmd_summarize)

    p = sub.add_parser("admission-check",
                       help="Stage 4B-1 offline provider admission check (no credentials, no network)")
    p.add_argument("--config", required=True)
    p.add_argument("--output", required=True)
    p.set_defaults(func=cmd_admission_check)

    p = sub.add_parser("admission-prepare",
                       help="Stage 4B-1 offline admission prepare: snapshots, freeze seeds, checklist")
    p.add_argument("--config", required=True)
    p.add_argument("--output", required=True)
    p.set_defaults(func=cmd_admission_prepare)

    p = sub.add_parser("admission-rebuild",
                       help="Repair-07: rebuild the generation ledger from the per-run originals "
                            "and field-by-field compare it with a declared batch index")
    p.add_argument("runs_root", help="the generation directory holding <run-id>/manifest.json|refusal.json")
    p.add_argument("--config", required=True, help="admission config carrying the formal material bindings")
    p.add_argument("--against", default=None, help="declared batch index JSON to compare with")
    p.add_argument("--output", default=None, help="optional path for the rebuilt ledger JSON")
    p.set_defaults(func=cmd_admission_rebuild)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except ReportEvalError as e:
        _print({"error": type(e).__name__, "message": str(e)})
        return 2
    except FileNotFoundError as e:
        _print({"error": "FileNotFoundError", "message": str(e)})
        return 2


if __name__ == "__main__":
    sys.exit(main())