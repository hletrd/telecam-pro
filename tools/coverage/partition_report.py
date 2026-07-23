#!/usr/bin/env python3
"""Partitioned JaCoCo line-coverage report.

The 99.5% coverage goal is only honest with an explicit partition (docs/TESTING.md):
  Partition A "host-executable logic"  — everything NOT matched by partition-b.txt or
                                          partition-excluded.txt; drivable by app/src/test
                                          host-JVM unit tests. Target: >= 99.5% line.
  Partition B "device-bound glue"      — classes/methods matched by partition-b.txt;
                                          Camera2/GL/MediaCodec/MediaStore/Activity/Compose-
                                          emission code exercised by device-tests/ and
                                          instrumented runs, not host unit tests.
  Excluded                             — partition-excluded.txt; debug/preview QA scaffolding
                                          counted in NEITHER partition but reported by size so
                                          nothing is hidden.

Pattern syntax (one per line, # comments):
  com/pkg/Class                whole class (fnmatch glob, * allowed)
  com/pkg/Class#method         one method of the class, by exact bytecode name
  com/pkg/Class#method#SUBSTR  ...only when the method descriptor contains SUBSTR (splits
                               same-named overloads: framework-typed overload -> B)
  !com/pkg/Class               negation: force-A even if a later B glob matches (checked first)

Method-level entries exist because a handful of classes mix pure logic with framework-typed
glue in one JaCoCo class (e.g. ManualControlsKt: tested pure normalization + CaptureRequest.
Builder extensions that cannot run on the host JVM). The split is by framework-boundedness
only — "hard to test" is never a valid reason for a B entry.

Usage:
  python3 tools/coverage/partition_report.py app/build/reports/coverage/test/debug/report.xml \
      [--fail-under-a 99.5] [--gaps N]
"""

from __future__ import annotations

import argparse
import fnmatch
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent
DEFAULT_PARTITION_B = TOOL_DIR / "partition-b.txt"
DEFAULT_EXCLUDED = TOOL_DIR / "partition-excluded.txt"


class Patterns:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.force_a: list[str] = []
        self.classes: list[str] = []
        # (class_glob, method_name, desc_substring_or_None)
        self.methods: list[tuple[str, str, str | None]] = []
        self.used: set[str] = set()
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("!"):
                self.force_a.append(line[1:])
            elif "#" in line:
                parts = line.split("#", 2)
                self.methods.append((parts[0], parts[1], parts[2] if len(parts) > 2 else None))
            else:
                self.classes.append(line)

    def matches_class(self, name: str) -> bool:
        if any(fnmatch.fnmatchcase(name, p) for p in self.force_a):
            return False
        for p in self.classes:
            if fnmatch.fnmatchcase(name, p):
                self.used.add(p)
                return True
        return False

    def method_rules_for(self, name: str) -> list[tuple[str, str, str | None]]:
        return [r for r in self.methods if fnmatch.fnmatchcase(name, r[0])]

    def unused(self) -> list[str]:
        # Globs are defensive by nature (X$* guards future inner classes) — only exact-name
        # patterns and method rules participate in the rename-drift warning.
        out = [p for p in self.classes if "*" not in p and p not in self.used]
        out += [f"{c}#{m}" + (f"#{d}" if d else "")
                for (c, m, d) in self.methods if f"{c}#{m}#{d}" not in self.used]
        return out


def parse_report(path: Path) -> ET.Element:
    # Same XXE stance as the device-tests harness: local build artifact, but refuse entity
    # declarations outright and strip the DTD line so the parser never resolves it.
    text = path.read_text(encoding="utf-8")
    if "<!ENTITY" in text:
        raise SystemExit("refusing XML with entity declarations")
    text = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("<!DOCTYPE"))
    return ET.fromstring(text)


def line_counter(node: ET.Element) -> tuple[int, int]:
    for c in node.findall("counter"):
        if c.get("type") == "LINE":
            return int(c.get("missed", "0")), int(c.get("covered", "0"))
    return 0, 0


def pct(covered: int, missed: int) -> float:
    total = covered + missed
    return 100.0 * covered / total if total else 100.0


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report", type=Path)
    ap.add_argument("--partition", type=Path, default=DEFAULT_PARTITION_B)
    ap.add_argument("--excluded", type=Path, default=DEFAULT_EXCLUDED)
    ap.add_argument("--fail-under-a", type=float, default=None,
                    help="exit 1 if Partition A line coverage is below this percentage")
    ap.add_argument("--gaps", type=int, default=40,
                    help="how many worst Partition A classes to list (default 40)")
    args = ap.parse_args()

    part_b = Patterns(args.partition)
    excluded = Patterns(args.excluded) if args.excluded.exists() else None

    a = {"m": 0, "c": 0}
    b = {"m": 0, "c": 0}
    x = {"m": 0, "c": 0}
    a_rows: list[tuple[str, int, int]] = []

    root = parse_report(args.report)
    for pkg in root.findall("package"):
        for cls in pkg.findall("class"):
            name = cls.get("name", "")
            cm, cc = line_counter(cls)
            # Zero-line classes (interfaces, const holders) still run the matchers so that
            # defensive glob patterns register as "used" and don't false-trip the drift warning.
            if excluded and excluded.matches_class(name):
                x["m"] += cm
                x["c"] += cc
                continue
            if part_b.matches_class(name):
                b["m"] += cm
                b["c"] += cc
                continue
            rules = part_b.method_rules_for(name)
            bm = bc = 0
            if rules:
                for meth in cls.findall("method"):
                    mn, md = meth.get("name", ""), meth.get("desc", "")
                    for (cglob, rname, dsub) in rules:
                        if mn == rname and (dsub is None or dsub in md):
                            mm, mc = line_counter(meth)
                            bm += mm
                            bc += mc
                            part_b.used.add(f"{cglob}#{rname}#{dsub}")
                            break
            b["m"] += bm
            b["c"] += bc
            am, ac = max(cm - bm, 0), max(cc - bc, 0)
            a["m"] += am
            a["c"] += ac
            if am:
                a_rows.append((name, am, ac))

    total_c, total_m = a["c"] + b["c"] + x["c"], a["m"] + b["m"] + x["m"]
    print(f"OVERALL     : {total_c}/{total_c + total_m} lines = {pct(total_c, total_m):6.2f}%")
    print(f"PARTITION A : {a['c']}/{a['c'] + a['m']} lines = {pct(a['c'], a['m']):6.2f}%"
          "   (host-executable logic; target >= 99.5%)")
    print(f"PARTITION B : {b['c']}/{b['c'] + b['m']} lines = {pct(b['c'], b['m']):6.2f}%"
          "   (device-bound glue; device-tests/ + instrumented)")
    if excluded:
        print(f"EXCLUDED    : {x['c']}/{x['c'] + x['m']} lines"
              "   (debug/preview QA scaffolding; counted in neither partition)")

    if a_rows:
        print(f"\nPARTITION A GAPS (top {args.gaps} by missed lines):")
        for name, m, c in sorted(a_rows, key=lambda r: -r[1])[: args.gaps]:
            print(f"  {pct(c, m):6.2f}%  missed={m:5d}  {name}")

    stale = part_b.unused() + (excluded.unused() if excluded else [])
    if stale:
        # A pattern matching nothing usually means a rename drifted past the partition files.
        print("\nWARNING: partition patterns matching nothing in this report:")
        for p in stale:
            print(f"  {p}")

    if args.fail_under_a is not None and pct(a["c"], a["m"]) < args.fail_under_a:
        print(f"\nFAIL: Partition A {pct(a['c'], a['m']):.2f}% < {args.fail_under_a}%",
              file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
