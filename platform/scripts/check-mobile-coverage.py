#!/usr/bin/env python3
"""Fail when an application source file is missing coverage or below 80 percent."""
from pathlib import Path
import sys

report = Path("coverage/lcov.info")
results = {}
for record in report.read_text().split("end_of_record"):
    lines = record.strip().splitlines()
    source = next((line[3:] for line in lines if line.startswith("SF:")), None)
    counts = [int(line.split(",")[1]) for line in lines if line.startswith("DA:")]
    if source and counts:
        results[source] = sum(count > 0 for count in counts) / len(counts)

failed = []
for source in sorted(Path("lib").rglob("*.dart")):
    ratio = results.get(str(source))
    if ratio is None or ratio < 0.80:
        failed.append(str(source))
    print(f"{source}: {ratio:.1%}" if ratio is not None else f"{source}: missing coverage")
if failed:
    sys.exit("Mobile coverage must reach 80% in every source file: " + ", ".join(failed))
