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


class Incomplete(Exception):
    """Raise when a required verification could not run; the suite exits non-green."""


class UnsafeState(Exception):
    """Raise when cleanup cannot prove a safe state; no later case may run."""


@dataclass
class Case:
    name: str
    tier: str
    fn: object
    doc: str
    destructive: bool = False
    mutates_settings: bool = False
    writes_media: bool = False


@dataclass
class Result:
    case: Case
    status: str  # pass | fail | error | skip | incomplete
    detail: str = ""
    seconds: float = 0.0


def test(
    name: str,
    tier: str,
    *,
    destructive: bool = False,
    mutates_settings: bool = False,
    writes_media: bool = False,
):
    assert tier in TIERS, tier
    def deco(fn):
        _REGISTRY.append(
            Case(
                name,
                tier,
                fn,
                (fn.__doc__ or "").strip(),
                destructive,
                mutates_settings,
                writes_media,
            )
        )
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


def run(
    adb: Adb,
    tiers: list[str],
    name_filter: str | None,
    report_dir: Path,
    *,
    allow_destructive: bool = False,
    allow_settings: bool = False,
    allow_media_writes: bool = False,
) -> int:
    cases = [c for c in _REGISTRY if c.tier in tiers and (not name_filter or name_filter in c.name)]
    if not cases:
        print("No test cases matched the requested tiers/filter.")
        _write_reports([], report_dir)
        return 2

    results: list[Result] = []
    print(f"Running {len(cases)} case(s), tiers={','.join(tiers)}, device={adb.serial}\n")
    for case in cases:
        ctx = Context(adb=adb, evidence=report_dir / "evidence" / case.name)
        ctx.evidence.mkdir(parents=True, exist_ok=True)
        print(f"[{case.tier}] {case.name} — {case.doc.splitlines()[0] if case.doc else ''}")
        t0 = time.time()
        missing_approvals = []
        if case.destructive and not allow_destructive:
            missing_approvals.append("--allow-destructive")
        if case.mutates_settings and not allow_settings:
            missing_approvals.append("--allow-settings")
        if case.writes_media and not allow_media_writes:
            missing_approvals.append("--allow-media-writes")
        if missing_approvals:
            detail = "requires explicit approval: " + ", ".join(missing_approvals)
            results.append(Result(case, "skip", detail, time.time() - t0))
            print(f"  SKIP: {detail}")
            continue
        abort_suite = False
        try:
            case.fn(ctx)
            res = Result(case, "pass", "; ".join(ctx.notes[-3:]), time.time() - t0)
            print(f"  PASS ({res.seconds:.1f}s)")
        except Skip as e:
            res = Result(case, "skip", str(e), time.time() - t0)
            print(f"  SKIP: {e}")
        except Incomplete as e:
            res = Result(case, "incomplete", str(e), time.time() - t0)
            print(f"  INCOMPLETE: {e}")
        except UnsafeState as e:
            res = Result(case, "error", f"unsafe state: {e}", time.time() - t0)
            abort_suite = True
            print(f"  ERROR: unsafe state — {e}; aborting remaining cases")
        except AssertionError as e:
            res = Result(case, "fail", f"{e}", time.time() - t0)
            print(f"  FAIL: {e}")
        except Exception as e:  # noqa: BLE001 — harness must survive any test blowup
            res = Result(case, "error", f"{type(e).__name__}: {e}\n{traceback.format_exc(limit=4)}", time.time() - t0)
            print(f"  ERROR: {type(e).__name__}: {e}")
        results.append(res)
        if abort_suite:
            break
    _write_reports(results, report_dir)
    passed = sum(1 for r in results if r.status == "pass")
    bad = sum(1 for r in results if r.status in ("fail", "error"))
    skipped = sum(1 for r in results if r.status == "skip")
    incomplete = sum(1 for r in results if r.status == "incomplete")
    print(f"\n{len(results)} run: "
          f"{passed} pass, {bad} fail/error, {skipped} skip, {incomplete} incomplete")
    print(f"Report: {report_dir / 'report.md'}")
    if bad:
        return 1
    if incomplete:
        return 2
    return 0 if passed else 2


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
    skipped = sum(1 for r in results if r.status in ("skip", "incomplete"))
    xml = [f'<testsuite name="device-tests" tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">']
    for r in results:
        xml.append(f'  <testcase classname="{r.case.tier}" name="{escape(r.case.name)}" time="{r.seconds:.1f}">')
        body = escape(r.detail[:2000])
        if r.status == "fail":
            xml.append(f'    <failure message="{body}"/>')
        elif r.status == "error":
            xml.append(f'    <error message="{body}"/>')
        elif r.status in ("skip", "incomplete"):
            xml.append(f'    <skipped message="{body}"/>')
        xml.append("  </testcase>")
    xml.append("</testsuite>")
    (out / "junit.xml").write_text("\n".join(xml) + "\n")
