#!/usr/bin/env python3
"""Partitioned JaCoCo line-coverage report.

The 99.5% coverage goal is only honest with an explicit partition (docs/TESTING.md):
  Partition A "host-executable logic"  — everything NOT matched by partition-b.txt or
                                          partition-excluded.txt; drivable by app/src/test
                                          host-JVM unit tests. Target: >= 99.5% line.
  Partition B "device-bound glue"      — classes matched by partition-b.txt;
                                          Camera2/GL/MediaCodec/MediaStore/Activity/Compose-
                                          emission code exercised by device-tests/ and
                                          instrumented runs, not host unit tests.
  Excluded                             — partition-excluded.txt; debug/preview QA scaffolding
                                          counted in NEITHER partition but reported by size so
                                          nothing is hidden.

Pattern syntax (one per line, # comments):
  com/pkg/Class                whole class (fnmatch glob, * allowed)
  !com/pkg/Class               negation: force-A even if a later B glob matches (checked first)

Method-level entries are forbidden. JaCoCo method LINE counters are not additive: compiler bridges,
lambdas, and source methods can share a source line, while a class counter is the unique line union.
Mixed classes are conservatively assigned wholly to B until framework glue is extracted; this can
understate Partition A but can never inflate it.

The analyzer always fails if an expected bucket has no lines or any configured rule matches
nothing. Those checks protect the measurement basis even when no coverage threshold is supplied.

Usage:
  python3 tools/coverage/partition_report.py app/build/reports/coverage/test/debug/report.xml \
      [--fail-under-a 99.5] [--gaps N]

The default `partition-a-residuals.txt` is a required exact review manifest: every current
Partition-A miss must have the same class/count plus a valid source region and rationale, and every
missed line of that class (resolved from the report's `<sourcefile><line nr mi ci>` rows, owned by
the class whose method starts nearest above it) must lie inside the cited region. A count-only gate
let a reviewed guard be deleted and an unreviewed one added elsewhere in the same class while the
manifest kept "explaining" a line that no longer existed (TE3-1 / REG3-2).

Regenerating the regions after source lines move (rationales are kept; a NEW residual class gets an
`UNREVIEWED:` placeholder the manifest loader refuses until a reviewer writes a real rationale):
  ./gradlew :app:createDebugUnitTestCoverageReport
  python3 tools/coverage/partition_report.py app/build/reports/coverage/test/debug/report.xml \
      --write-regions
"""

from __future__ import annotations

import argparse
import bisect
import fnmatch
import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent
DEFAULT_PARTITION_B = TOOL_DIR / "partition-b.txt"
DEFAULT_EXCLUDED = TOOL_DIR / "partition-excluded.txt"
DEFAULT_RESIDUALS = TOOL_DIR / "partition-a-residuals.txt"
DEFAULT_SOURCE_ROOT = TOOL_DIR.parent.parent


MANIFEST_HEADER = (
    "# Reviewed Partition-A line misses. Enforced exactly by partition_report.py.\n"
    "# Format (TAB-separated, sorted by class): class | missed | source region | rationale.\n"
    "# Rationale prefixes are closed: framework-bound, proven-unreachable, race-only.\n"
    "# Every missed line the report attributes to a class must lie inside its cited region.\n"
    "# Regenerate regions (keeps rationales) after source lines move:\n"
    "#   ./gradlew :app:createDebugUnitTestCoverageReport\n"
    "#   python3 tools/coverage/partition_report.py \\\n"
    "#       app/build/reports/coverage/test/debug/report.xml --write-regions\n"
)
UNREVIEWED_REASON = "UNREVIEWED: write a framework-bound, proven-unreachable, or race-only rationale"


@dataclass(frozen=True)
class Residual:
    class_name: str
    missed: int
    source_region: str
    reason: str
    cited_lines: frozenset[int] = frozenset()


@dataclass(frozen=True)
class ActualResidual:
    """One Partition-A class with misses, as the report sees it."""

    missed: int
    source_filename: str
    # Fully missed lines (no covered instruction) owned by this class's methods.
    attributed_lines: frozenset[int]
    # Every fully missed line of the class's source file, whichever class owns it.
    file_missed_lines: frozenset[int]


def compress_lines(lines: frozenset[int] | set[int]) -> str:
    """`1,3,4,5,9` -> `1,3-5,9`; the region syntax the manifest uses."""
    ranges: list[str] = []
    ordered = sorted(lines)
    start = previous = None
    for line in ordered:
        if start is None:
            start = previous = line
        elif line == previous + 1:
            previous = line
        else:
            ranges.append(f"{start}" if start == previous else f"{start}-{previous}")
            start = previous = line
    if start is not None:
        ranges.append(f"{start}" if start == previous else f"{start}-{previous}")
    return ",".join(ranges)


class Residuals:
    """Reviewed Partition-A misses, with an exact class/count/source/reason contract."""

    REASON_PREFIXES = ("framework-bound:", "proven-unreachable:", "race-only:")
    SOURCE_REGION = re.compile(
        r"^(?P<path>[A-Za-z0-9_./-]+\.kt):"
        r"(?P<lines>[0-9]+(?:-[0-9]+)?(?:,[0-9]+(?:-[0-9]+)?)*)$"
    )

    def __init__(self, path: Path, source_root: Path) -> None:
        self.path = path
        self.entries: dict[str, Residual] = {}
        previous = ""
        errors: list[str] = []
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except OSError as error:
            print(f"could not read Partition-A residual manifest {path}: {error}", file=sys.stderr)
            raise SystemExit(1) from error
        for number, raw in enumerate(lines, 1):
            if not raw.strip() or raw.lstrip().startswith("#"):
                continue
            fields = raw.split("\t")
            if len(fields) != 4:
                errors.append(f"line {number}: expected 4 tab-separated fields")
                continue
            class_name, missed_text, source_region, reason = fields
            if not re.fullmatch(r"[A-Za-z0-9_/$]+", class_name):
                errors.append(f"line {number}: invalid class name {class_name!r}")
            try:
                missed = int(missed_text)
            except ValueError:
                missed = 0
            if missed <= 0:
                errors.append(f"line {number}: missed count must be a positive integer")
            source_match = self.SOURCE_REGION.fullmatch(source_region)
            cited: list[int] = []
            if source_match is None:
                errors.append(f"line {number}: invalid source region {source_region!r}")
            else:
                source = source_root / source_match.group("path")
                if not source.is_file():
                    errors.append(f"line {number}: source file does not exist: {source_match.group('path')}")
                else:
                    line_count = len(source.read_text(encoding="utf-8").splitlines())
                    cited = []
                    for item in source_match.group("lines").split(","):
                        bounds = [int(value) for value in item.split("-")]
                        cited.extend(range(bounds[0], bounds[-1] + 1))
                    if any(line < 1 or line > line_count for line in cited):
                        errors.append(
                            f"line {number}: source region exceeds {source_match.group('path')} "
                            f"({line_count} lines)"
                        )
            if not reason.startswith(self.REASON_PREFIXES) or len(reason.split(":", 1)[-1].strip()) < 12:
                errors.append(
                    f"line {number}: reason must have a concrete framework-bound, "
                    "proven-unreachable, or race-only rationale"
                )
            if class_name in self.entries:
                errors.append(f"line {number}: duplicate class {class_name}")
            if previous and class_name <= previous:
                errors.append(f"line {number}: classes must be strictly sorted")
            previous = class_name
            self.entries[class_name] = Residual(
                class_name, missed, source_region, reason, frozenset(cited),
            )
        if errors:
            print(f"invalid Partition-A residual manifest: {path}", file=sys.stderr)
            for error in errors:
                print(f"  {error}", file=sys.stderr)
            raise SystemExit(1)

    def drift(self, actual: dict[str, ActualResidual]) -> list[str]:
        errors: list[str] = []
        for name in sorted(actual.keys() - self.entries.keys()):
            errors.append(f"unexpected residual {name}: missed={actual[name].missed}")
        for name in sorted(self.entries.keys() - actual.keys()):
            errors.append(f"resolved/stale residual {name}: manifest missed={self.entries[name].missed}")
        for name in sorted(actual.keys() & self.entries.keys()):
            entry = self.entries[name]
            report = actual[name]
            if report.missed != entry.missed:
                errors.append(
                    f"residual count drift {name}: report missed={report.missed}, "
                    f"manifest missed={entry.missed}"
                )
            manifest_source = entry.source_region.rsplit(":", 1)[0]
            if Path(manifest_source).name != report.source_filename:
                errors.append(
                    f"residual source drift {name}: report source={report.source_filename}, "
                    f"manifest source={manifest_source}"
                )
                continue
            # Line identity, not just count: a reviewed guard that became covered while a NEW miss
            # appeared elsewhere in the same class keeps the count but moves the line.
            outside = sorted(report.attributed_lines - entry.cited_lines)
            if outside:
                errors.append(
                    f"residual line drift {name}: missed line(s) {compress_lines(set(outside))} "
                    f"outside cited region {entry.source_region}"
                )
            # Attribution is by nearest preceding method start, so it can only UNDER-attribute (a
            # line owned by an interleaved lambda of another class). The region must still hold at
            # least as many fully missed lines as the class reports, or it cites covered code.
            in_region = len(report.file_missed_lines & entry.cited_lines)
            if in_region < report.missed:
                errors.append(
                    f"residual line drift {name}: cited region {entry.source_region} holds "
                    f"{in_region} missed line(s), report missed={report.missed}"
                )
        return errors


class Patterns:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.force_a: list[str] = []
        self.classes: list[str] = []
        self.used: set[str] = set()
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("!"):
                self.force_a.append(line[1:])
            elif "#" in line:
                print(
                    f"method-level coverage partition is mathematically invalid: {line}",
                    file=sys.stderr,
                )
                raise SystemExit(1)
            else:
                self.classes.append(line)

    def matches_class(self, name: str) -> bool:
        force_a_matches = [p for p in self.force_a if fnmatch.fnmatchcase(name, p)]
        if force_a_matches:
            self.used.update(f"!{p}" for p in force_a_matches)
            return False
        matches = [p for p in self.classes if fnmatch.fnmatchcase(name, p)]
        self.used.update(matches)
        return bool(matches)

    def unused(self) -> list[str]:
        out = [f"!{p}" for p in self.force_a if f"!{p}" not in self.used]
        out += [p for p in self.classes if p not in self.used]
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


def fully_missed_lines(sourcefile: ET.Element) -> frozenset[int]:
    """Lines JaCoCo counts as missed: some instruction missed and none covered (`mi>0, ci=0`)."""
    return frozenset(
        int(line.get("nr", "0"))
        for line in sourcefile.findall("line")
        if int(line.get("mi", "0")) > 0 and int(line.get("ci", "0")) == 0
    )


def attribute_package_lines(package: ET.Element) -> tuple[dict[str, frozenset[int]], dict[str, frozenset[int]]]:
    """Resolve each class's missed lines from its package's per-source line rows.

    JaCoCo's XML has no class-to-line map, only each method's FIRST line. Every fully missed source
    line is owned by the method (of any class in that source file, lambdas and companions included)
    whose start line is the nearest at or above it. Methods sharing a start line (default-argument
    bridges, synthetic accessors) all own it. Returns (class -> owned missed lines, source file ->
    all fully missed lines).
    """
    file_missed = {
        sourcefile.get("name", ""): fully_missed_lines(sourcefile)
        for sourcefile in package.findall("sourcefile")
    }
    starts: dict[str, list[tuple[int, str]]] = {}
    for cls in package.findall("class"):
        for method in cls.findall("method"):
            line = method.get("line")
            if line:
                starts.setdefault(cls.get("sourcefilename", ""), []).append(
                    (int(line), cls.get("name", ""))
                )
    owned: dict[str, set[int]] = {}
    for source, methods in starts.items():
        methods.sort()
        keys = [start for start, _ in methods]
        for line in file_missed.get(source, frozenset()):
            index = bisect.bisect_right(keys, line) - 1
            if index < 0:
                continue
            first_line = keys[index]
            for start, name in methods[bisect.bisect_left(keys, first_line):index + 1]:
                owned.setdefault(name, set()).add(line)
    return {name: frozenset(lines) for name, lines in owned.items()}, file_missed


def read_rationales(path: Path) -> dict[str, tuple[str, str]]:
    """Lenient manifest read for regeneration: class -> (source path, rationale); no validation."""
    out: dict[str, tuple[str, str]] = {}
    if not path.exists():
        return out
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        fields = raw.split("\t")
        if len(fields) == 4:
            out[fields[0]] = (fields[2].rsplit(":", 1)[0], fields[3])
    return out


def regenerated_manifest(
    actual: dict[str, ActualResidual],
    packages: dict[str, str],
    rationales: dict[str, tuple[str, str]],
    source_root: Path,
) -> str:
    """The manifest with every region recomputed from the report; rationales carried by class."""
    rows: list[str] = []
    for name in sorted(actual):
        report = actual[name]
        source = f"app/src/main/kotlin/{packages[name]}/{report.source_filename}"
        previous = rationales.get(name)
        if not (source_root / source).is_file() and previous is not None:
            source = previous[0]
        reason = previous[1] if previous is not None else UNREVIEWED_REASON
        lines = report.attributed_lines or (report.file_missed_lines)
        rows.append(f"{name}\t{report.missed}\t{source}:{compress_lines(lines)}\t{reason}")
    return MANIFEST_HEADER + "".join(f"{row}\n" for row in rows)


def pct(covered: int, missed: int) -> float:
    total = covered + missed
    return 100.0 * covered / total if total else 100.0


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report", type=Path)
    ap.add_argument("--partition", type=Path, default=DEFAULT_PARTITION_B)
    ap.add_argument("--excluded", type=Path, default=DEFAULT_EXCLUDED)
    ap.add_argument("--residuals", type=Path, default=DEFAULT_RESIDUALS)
    ap.add_argument("--source-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    ap.add_argument("--fail-under-a", type=float, default=None,
                    help="exit 1 if Partition A line coverage is below this percentage")
    ap.add_argument("--gaps", type=int, default=40,
                    help="how many worst Partition A classes to list (default 40)")
    ap.add_argument("--write-regions", action="store_true",
                    help="rewrite --residuals with every region recomputed from this report "
                         "(rationales kept by class; new classes get an UNREVIEWED placeholder) "
                         "and exit without gating")
    args = ap.parse_args(argv)

    part_b = Patterns(args.partition)
    excluded = Patterns(args.excluded) if args.excluded.exists() else None
    residuals = None if args.write_regions else Residuals(args.residuals, args.source_root)

    a = {"m": 0, "c": 0}
    b = {"m": 0, "c": 0}
    x = {"m": 0, "c": 0}
    a_rows: list[tuple[str, int, int, str]] = []
    owned_lines: dict[str, frozenset[int]] = {}
    file_missed: dict[str, frozenset[int]] = {}
    packages: dict[str, str] = {}

    root = parse_report(args.report)
    for pkg in root.findall("package"):
        package_owned, package_files = attribute_package_lines(pkg)
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
            am, ac = cm, cc
            a["m"] += am
            a["c"] += ac
            if am:
                source_filename = cls.get("sourcefilename", "")
                a_rows.append((name, am, ac, source_filename))
                owned_lines[name] = package_owned.get(name, frozenset())
                file_missed[name] = package_files.get(source_filename, frozenset())
                packages[name] = pkg.get("name", "")

    actual_residuals = {
        name: ActualResidual(missed, source_filename, owned_lines[name], file_missed[name])
        for name, missed, _, source_filename in a_rows
    }
    if args.write_regions:
        text = regenerated_manifest(
            actual_residuals, packages, read_rationales(args.residuals), args.source_root,
        )
        temporary = args.residuals.with_name(f".{args.residuals.name}.tmp")
        temporary.write_text(text, encoding="utf-8")
        os.replace(temporary, args.residuals)
        print(f"rewrote {args.residuals} with {len(actual_residuals)} residual region(s)")
        return

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
        for name, m, c, _ in sorted(a_rows, key=lambda r: -r[1])[: args.gaps]:
            print(f"  {pct(c, m):6.2f}%  missed={m:5d}  {name}")

    assert residuals is not None
    residual_drift = residuals.drift(actual_residuals)
    if residuals.entries and not residual_drift:
        print(f"\nREVIEWED A RESIDUALS: {sum(value.missed for value in actual_residuals.values())} lines "
              f"across {len(actual_residuals)} classes (manifest exact, lines inside cited regions)")

    stale = part_b.unused() + (excluded.unused() if excluded else [])
    if stale:
        print("\nFAIL: partition patterns matching nothing in this report:", file=sys.stderr)
        for p in stale:
            print(f"  {p}", file=sys.stderr)

    empty = [name for name, counts in (("Partition A", a), ("Partition B", b), ("Excluded", x))
             if counts["c"] + counts["m"] == 0 and (name != "Excluded" or excluded is not None)]
    if empty:
        print(f"\nFAIL: expected non-empty coverage bucket(s): {', '.join(empty)}",
              file=sys.stderr)

    if residual_drift:
        print("\nFAIL: Partition-A residual manifest drift:", file=sys.stderr)
        for error in residual_drift:
            print(f"  {error}", file=sys.stderr)

    below_threshold = (
        args.fail_under_a is not None
        and pct(a["c"], a["m"]) < args.fail_under_a
    )
    if below_threshold:
        print(f"\nFAIL: Partition A {pct(a['c'], a['m']):.2f}% < {args.fail_under_a}%",
              file=sys.stderr)

    if stale or empty or residual_drift or below_threshold:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
