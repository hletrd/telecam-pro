"""Tiny test framework: registry, tiers, runner, markdown + JUnit XML reports."""

from __future__ import annotations

import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from xml.sax.saxutils import escape

from .adb import Adb

TIERS = ("smoke", "full", "reliability")
_REGISTRY: list["Case"] = []


class Skip(Exception):
    """Raise inside a test to mark it skipped (unreachable UI path, missing host tool)."""


@dataclass
class Case:
    name: str
    tier: str
    fn: object
    doc: str


@dataclass
class Result:
    case: Case
    status: str  # pass | fail | error | skip
    detail: str = ""
    seconds: float = 0.0


def test(name: str, tier: str):
    assert tier in TIERS, tier
    def deco(fn):
        _REGISTRY.append(Case(name, tier, fn, (fn.__doc__ or "").strip()))
        return fn
    return deco


@dataclass
class Context:
    adb: Adb
    evidence: Path
    notes: list[str] = field(default_factory=list)

    def note(self, msg: str) -> None:
        print(f"    · {msg}")
        self.notes.append(msg)


def run(adb: Adb, tiers: list[str], name_filter: str | None, report_dir: Path) -> int:
    cases = [c for c in _REGISTRY if c.tier in tiers and (not name_filter or name_filter in c.name)]
    results: list[Result] = []
    print(f"Running {len(cases)} case(s), tiers={','.join(tiers)}, device={adb.serial}\n")
    for case in cases:
        ctx = Context(adb=adb, evidence=report_dir / "evidence" / case.name)
        ctx.evidence.mkdir(parents=True, exist_ok=True)
        print(f"[{case.tier}] {case.name} — {case.doc.splitlines()[0] if case.doc else ''}")
        t0 = time.time()
        try:
            case.fn(ctx)
            res = Result(case, "pass", "; ".join(ctx.notes[-3:]), time.time() - t0)
            print(f"  PASS ({res.seconds:.1f}s)")
        except Skip as e:
            res = Result(case, "skip", str(e), time.time() - t0)
            print(f"  SKIP: {e}")
        except AssertionError as e:
            res = Result(case, "fail", f"{e}", time.time() - t0)
            print(f"  FAIL: {e}")
        except Exception as e:  # noqa: BLE001 — harness must survive any test blowup
            res = Result(case, "error", f"{type(e).__name__}: {e}\n{traceback.format_exc(limit=4)}", time.time() - t0)
            print(f"  ERROR: {type(e).__name__}: {e}")
        results.append(res)
    _write_reports(results, report_dir)
    bad = sum(1 for r in results if r.status in ("fail", "error"))
    print(f"\n{len(results)} run: "
          f"{sum(1 for r in results if r.status == 'pass')} pass, "
          f"{bad} fail/error, {sum(1 for r in results if r.status == 'skip')} skip")
    print(f"Report: {report_dir / 'report.md'}")
    return 1 if bad else 0


def _write_reports(results: list[Result], out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    lines = ["# Device test report", "", "| tier | case | status | time | detail |", "|---|---|---|---|---|"]
    for r in results:
        detail = r.detail.replace("\n", " ")[:180].replace("|", "\\|")
        lines.append(f"| {r.case.tier} | {r.case.name} | **{r.status.upper()}** | {r.seconds:.1f}s | {detail} |")
    (out / "report.md").write_text("\n".join(lines) + "\n")

    tests = len(results)
    failures = sum(1 for r in results if r.status == "fail")
    errors = sum(1 for r in results if r.status == "error")
    skipped = sum(1 for r in results if r.status == "skip")
    xml = [f'<testsuite name="device-tests" tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">']
    for r in results:
        xml.append(f'  <testcase classname="{r.case.tier}" name="{escape(r.case.name)}" time="{r.seconds:.1f}">')
        body = escape(r.detail[:2000])
        if r.status == "fail":
            xml.append(f'    <failure message="{body}"/>')
        elif r.status == "error":
            xml.append(f'    <error message="{body}"/>')
        elif r.status == "skip":
            xml.append(f'    <skipped message="{body}"/>')
        xml.append("  </testcase>")
    xml.append("</testsuite>")
    (out / "junit.xml").write_text("\n".join(xml) + "\n")
