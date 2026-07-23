#!/usr/bin/env python3
"""Exact line-level union of two JaCoCo XML reports sharing the same class basis.

Produces the "merged (host unit + instrumented smoke)" OVERALL number defined in
docs/TESTING.md. A line is covered in the union if either report covers it. Attribution is by
(package, sourcefile, line-number), which is split-proof: it does not depend on how JaCoCo
assigns lines to classes (the per-leg reports disagree on synthetic-class bucketing but share
the identical source basis), only on the sources themselves.

Honest-number rules (docs/TESTING.md): the union is a DIFFERENT measurement basis than the
host-only OVERALL — always label it "merged (host unit + instrumented smoke)" and never
substitute it for the host-only OVERALL or the Partition A number. A nonzero basis drift means
the two reports were built from different bytecode — regenerate both from the same commit
before quoting the union.

Usage:
  python3 tools/coverage/union_report.py \
      app/build/reports/coverage/test/debug/report.xml \
      app/build/reports/coverage/androidTest/debug/connected/report.xml
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


def load(path: Path) -> dict[tuple[str, str, int], bool]:
    # Same XXE stance as partition_report.py / the device-tests harness: local build artifacts,
    # but refuse entity declarations outright and strip the DTD line before parsing.
    text = path.read_text(encoding="utf-8")
    if "<!ENTITY" in text:
        raise SystemExit(f"refusing XML with entity declarations: {path}")
    text = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("<!DOCTYPE"))
    root = ET.fromstring(text)
    lines: dict[tuple[str, str, int], bool] = {}
    for pkg in root.findall("package"):
        pname = pkg.get("name", "")
        for sf in pkg.findall("sourcefile"):
            sname = sf.get("name", "")
            for ln in sf.findall("line"):
                lines[(pname, sname, int(ln.get("nr", "0")))] = int(ln.get("ci", "0")) > 0
    return lines


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report_a", type=Path, help="host unit-test JaCoCo XML")
    ap.add_argument("report_b", type=Path, help="connected androidTest JaCoCo XML")
    ap.add_argument("--fail-on-drift", action="store_true",
                    help="exit 1 if the two reports do not share an identical line basis")
    args = ap.parse_args()

    a, b = load(args.report_a), load(args.report_b)
    keys = set(a) | set(b)
    drift = len(keys) - len(set(a) & set(b))
    covered = sum(1 for k in keys if a.get(k, False) or b.get(k, False))
    only_a = sum(1 for k in keys if a.get(k, False) and not b.get(k, False))
    only_b = sum(1 for k in keys if b.get(k, False) and not a.get(k, False))

    print(f"MERGED (host unit + instrumented smoke) LINE COVERAGE: "
          f"{covered}/{len(keys)} = {100.0 * covered / len(keys):.2f}%")
    print(f"  covered by host unit only:    {only_a}")
    print(f"  covered by instrumented only: {only_b}")
    print(f"  covered by both:              {covered - only_a - only_b}")
    print(f"  basis drift (lines in exactly one report): {drift}")

    pkg_tot: dict[str, list[int]] = defaultdict(lambda: [0, 0])
    for k in keys:
        pkg_tot[k[0]][0] += 1
        if a.get(k, False) or b.get(k, False):
            pkg_tot[k[0]][1] += 1
    print("\nPER-PACKAGE UNION:")
    for pname, (tot, cov) in sorted(pkg_tot.items(), key=lambda e: e[1][1] - e[1][0]):
        print(f"  {100.0 * cov / tot:6.2f}%  covered={cov:5d}/{tot:5d}  {pname}")

    if drift and args.fail_on_drift:
        print(f"\nFAIL: basis drift {drift} != 0 — reports built from different bytecode",
              file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
