#!/usr/bin/env python3
#
# Copyright 2026 David Greco
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Markdown summary of a CI run: test counts per module and the JaCoCo coverage per module.

Prints the summary on stdout (CI appends it to $GITHUB_STEP_SUMMARY) and, with --badge, writes
a shields.io endpoint file for the coverage badge. Standard library only.
"""

import argparse
import csv
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

COUNTERS = ("INSTRUCTION", "BRANCH", "LINE", "METHOD")


def test_counts(root):
    """{module: {kind: [tests, failures, errors, skipped, seconds]}} from the surefire/failsafe XML reports."""
    counts = defaultdict(dict)
    for kind, folder in (("unit", "surefire-reports"), ("integration", "failsafe-reports")):
        for path in glob.glob(os.path.join(root, "*", "target", folder, "TEST-*.xml")):
            module = path.split(os.sep)[-4]
            try:
                suite = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            row = counts[module].setdefault(kind, [0, 0, 0, 0, 0.0])
            for i, attr in enumerate(("tests", "failures", "errors", "skipped")):
                row[i] += int(suite.get(attr, "0"))
            row[4] += float(suite.get("time", "0") or 0)
    return counts


def coverage(csv_path):
    """{module: {counter: [missed, covered]}} from JaCoCo's CSV report."""
    totals = defaultdict(lambda: {c: [0, 0] for c in COUNTERS})
    with open(csv_path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            module = row["GROUP"].split("/")[-1]
            for c in COUNTERS:
                totals[module][c][0] += int(row[c + "_MISSED"])
                totals[module][c][1] += int(row[c + "_COVERED"])
    return totals


def pct(missed, covered):
    total = missed + covered
    return None if total == 0 else 100.0 * covered / total


def fmt(value):
    return "–" if value is None else f"{value:.1f}%"


def badge_color(value):
    if value is None:
        return "lightgrey"
    for limit, color in ((90, "brightgreen"), (80, "green"), (70, "yellowgreen"), (60, "yellow"), (50, "orange")):
        if value >= limit:
            return color
    return "red"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="the Maven project root")
    parser.add_argument("--jacoco-csv", help="the aggregated JaCoCo CSV report")
    parser.add_argument("--badge", help="write a shields.io endpoint JSON file here")
    args = parser.parse_args()

    out = []
    tests = test_counts(args.root)
    if tests:
        out += ["## Tests", "", "| Module | Unit | Integration | Failed | Skipped | Time |", "|---|---:|---:|---:|---:|---:|"]
        grand = [0, 0, 0, 0, 0.0]
        for module in sorted(tests):
            unit = tests[module].get("unit", [0, 0, 0, 0, 0.0])
            it = tests[module].get("integration", [0, 0, 0, 0, 0.0])
            failed = unit[1] + unit[2] + it[1] + it[2]
            skipped = unit[3] + it[3]
            seconds = unit[4] + it[4]
            out.append(f"| `{module}` | {unit[0]} | {it[0]} | {failed} | {skipped} | {seconds / 60:.1f} min |")
            for i, v in enumerate((unit[0], it[0], failed, skipped, seconds)):
                grand[i] += v
        out.append(f"| **Total** | **{grand[0]}** | **{grand[1]}** | **{grand[2]}** | **{grand[3]}** | **{grand[4] / 60:.1f} min** |")
        out.append("")

    total_line = None
    if args.jacoco_csv and os.path.exists(args.jacoco_csv):
        cov = coverage(args.jacoco_csv)
        out += ["## Coverage", "", "Unit and integration tests together.", "",
                "| Module | Lines | Branches | Instructions | Methods |", "|---|---:|---:|---:|---:|"]
        grand = {c: [0, 0] for c in COUNTERS}
        for module in sorted(cov):
            cells = []
            for c in ("LINE", "BRANCH", "INSTRUCTION", "METHOD"):
                cells.append(fmt(pct(*cov[module][c])))
                grand[c][0] += cov[module][c][0]
                grand[c][1] += cov[module][c][1]
            out.append(f"| `{module}` | " + " | ".join(cells) + " |")
        total_line = pct(*grand["LINE"])
        out.append("| **Total** | " + " | ".join(f"**{fmt(pct(*grand[c]))}**" for c in ("LINE", "BRANCH", "INSTRUCTION", "METHOD")) + " |")
        out.append("")
    elif args.jacoco_csv:
        out += ["## Coverage", "", "No coverage report: the build stopped before it was written.", ""]

    print("\n".join(out))

    if args.badge and total_line is not None:
        os.makedirs(os.path.dirname(os.path.abspath(args.badge)), exist_ok=True)
        with open(args.badge, "w", encoding="utf-8") as f:
            json.dump({"schemaVersion": 1, "label": "coverage", "message": fmt(total_line), "color": badge_color(total_line)}, f)
    return 0


if __name__ == "__main__":
    sys.exit(main())
