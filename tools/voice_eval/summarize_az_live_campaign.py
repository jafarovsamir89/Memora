#!/usr/bin/env python3
"""Summarize sanitized, resumable Gemini campaign reports without reading transcripts."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "docs/voice-evaluation/az-gemini-live-scenarios-v1.jsonl"
REPORT_DIR = ROOT / "apps/android/app/build/live-gemini-e2e"
CAMPAIGN_REVISION = 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", type=Path, default=REPORT_DIR)
    parser.add_argument("--revision", type=int, default=CAMPAIGN_REVISION)
    args = parser.parse_args()

    expected = {
        json.loads(line)["scenario_id"]
        for line in CORPUS.read_text(encoding="utf-8").splitlines()
        if line.strip()
    }
    latest: dict[str, tuple[int, dict[str, object]]] = {}
    report_count = 0
    for path in sorted(args.report_dir.glob("*.jsonl"), key=lambda item: item.stat().st_mtime_ns):
        report_count += 1
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            row = json.loads(line)
            if row.get("campaign_revision") != args.revision:
                continue
            scenario_id = row.get("scenario_id")
            if scenario_id not in expected:
                raise SystemExit(f"unexpected_scenario_id:{path.name}:{line_number}")
            latest[scenario_id] = (path.stat().st_mtime_ns, row)

    missing = sorted(expected - latest.keys())
    failures = sorted(
        (scenario_id, str(row.get("code", "unknown")))
        for scenario_id, (_, row) in latest.items()
        if row.get("status") != "passed"
    )
    passed = sum(row.get("status") == "passed" for _, row in latest.values())
    print(
        f"revision={args.revision} reports={report_count} covered={len(latest)}/{len(expected)} "
        f"passed={passed} failed={len(failures)} missing={len(missing)}"
    )
    for scenario_id, code in failures:
        print(f"failed {scenario_id} {code}")
    if missing:
        print("missing " + ",".join(missing[:20]) + ("..." if len(missing) > 20 else ""))
    return 0 if not missing and not failures else 1


if __name__ == "__main__":
    sys.exit(main())
