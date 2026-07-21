#!/usr/bin/env python3
"""TeleCam Pro on-device functional test runner.

Usage:
  python3 device-tests/run.py --serial 127.0.0.1:5599 --tier smoke
  python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full --allow-settings
  python3 device-tests/run.py --serial 127.0.0.1:5599 --tier all -k capture

Requires: adb on PATH with the PMA110 connected (wireless-debugging loopback proxy is
fine). ffprobe is required for a green video result; structural fallback is non-green.
Reports land in device-tests/reports/<timestamp>/ (gitignored).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shlex
import subprocess
import sys
import time
from datetime import UTC, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from dtest.adb import APP_ID, MAIN_ACTIVITY, Adb  # noqa: E402
from dtest.framework import TIERS, run  # noqa: E402
import cases  # noqa: E402, F401  — registers all test cases

EXPECTED_MODEL = "PMA110"
EXPECTED_API = 36
DEFAULT_APK = Path(__file__).resolve().parent.parent / "app/build/outputs/apk/debug/app-debug.apk"
REPO_ROOT = Path(__file__).resolve().parent.parent
ATTESTATION_NAME = "run-attestation.json"
ATTESTATION_SHA_NAME = "run-attestation.sha256"
RESTORED_SETTINGS = ("font_scale", "accelerometer_rotation", "user_rotation")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def base_apk_path(pm_path_output: str) -> str | None:
    paths = [line.removeprefix("package:") for line in pm_path_output.splitlines() if line.startswith("package:")]
    return next((path for path in paths if path.endswith("/base.apk")), paths[0] if paths else None)


def utc_now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def git_identity(repo_root: Path = REPO_ROOT) -> dict[str, object]:
    """Return the exact source revision and a reviewable working-tree state."""
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    status = subprocess.run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.splitlines()
    return {"head": head, "dirty": bool(status), "status": status}


def device_state(adb: Adb) -> dict[str, object]:
    """Capture the foreground, display, and operator state that a run must restore."""
    metrics = adb.display_metrics()
    return {
        "foreground_component": adb.resumed_activity(),
        "display": {
            "width_px": metrics.width_px,
            "height_px": metrics.height_px,
            "density_dpi": metrics.density_dpi,
        },
        "settings": {
            name: adb.shell(f"settings get system {name}")
            for name in RESTORED_SETTINGS
        },
    }


def restoration_errors(
    before: dict[str, object],
    after: dict[str, object] | None,
) -> list[str]:
    """Require a returned MainActivity and unchanged operator-controlled settings."""
    if after is None:
        return ["post-run device state could not be collected"]
    errors = []
    if after.get("foreground_component") != MAIN_ACTIVITY:
        errors.append(
            "foreground component was not restored to MainActivity: "
            f"{after.get('foreground_component')!r}"
        )
    before_settings = before.get("settings")
    after_settings = after.get("settings")
    if not isinstance(before_settings, dict) or not isinstance(after_settings, dict):
        errors.append("pre/post settings were not available for restoration comparison")
        return errors
    for name in RESTORED_SETTINGS:
        if after_settings.get(name) != before_settings.get(name):
            errors.append(
                f"{name} changed from {before_settings.get(name)!r} "
                f"to {after_settings.get(name)!r}"
            )
    return errors


def attested_exit_code(case_exit_code: int, errors: list[str]) -> int:
    """Preserve case failures while making an otherwise-green restoration failure non-green."""
    return case_exit_code if case_exit_code != 0 else (2 if errors else 0)


def artifact_manifest(report_dir: Path) -> list[dict[str, object]]:
    """Hash every regular report artifact except the self-referential attestation pair."""
    excluded = {ATTESTATION_NAME, ATTESTATION_SHA_NAME}
    artifacts = []
    for path in sorted(report_dir.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        relative = path.relative_to(report_dir).as_posix()
        if relative in excluded:
            continue
        artifacts.append(
            {
                "path": relative,
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )
    return artifacts


def append_attestation_summary(
    report_dir: Path,
    *,
    final_exit_code: int,
    errors: list[str],
) -> None:
    report = report_dir / "report.md"
    if not report.is_file():
        return
    status = "PASS" if not errors else "FAIL"
    lines = [
        "",
        "## Run attestation",
        "",
        f"- restoration: **{status}**",
        f"- final CLI exit code: `{final_exit_code}`",
        f"- metadata: `{ATTESTATION_NAME}` (`{ATTESTATION_SHA_NAME}`)",
    ]
    if errors:
        lines.append("- restoration errors: " + "; ".join(errors))
    with report.open("a", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")


def write_attestation(report_dir: Path, document: dict[str, object]) -> tuple[Path, Path]:
    """Write canonical-enough JSON plus a SHA-256 integrity sidecar."""
    attestation = report_dir / ATTESTATION_NAME
    payload = (json.dumps(document, indent=2, sort_keys=True) + "\n").encode()
    attestation.write_bytes(payload)
    checksum = hashlib.sha256(payload).hexdigest()
    sidecar = report_dir / ATTESTATION_SHA_NAME
    sidecar.write_text(f"{checksum}  {ATTESTATION_NAME}\n", encoding="utf-8")
    return attestation, sidecar


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--serial", required=True, help="adb serial, e.g. 127.0.0.1:5599")
    ap.add_argument("--tier", action="append", choices=[*TIERS, "all"], default=None,
                    help="tier(s) to run; repeatable; default smoke")
    ap.add_argument("-k", dest="filter", default=None, help="substring filter on case names")
    ap.add_argument("--apk", type=Path, default=DEFAULT_APK,
                    help="exact host debug APK that must match the installed base.apk")
    ap.add_argument("--allow-destructive", action="store_true",
                    help="allow cases that force-stop the app; requires explicit operator approval")
    ap.add_argument("--allow-settings", action="store_true",
                    help="allow cases that change persisted shooting settings; requires explicit approval")
    ap.add_argument("--allow-media-writes", action="store_true",
                    help="allow cases that create photos or videos; requires explicit approval")
    args = ap.parse_args()

    tiers = args.tier or ["smoke"]
    if "all" in tiers:
        tiers = list(TIERS)

    # Preflight: device reachable and the debug app installed.
    probe = subprocess.run(["adb", "-s", args.serial, "get-state"], capture_output=True, text=True)
    if probe.returncode != 0 or probe.stdout.strip() != "device":
        print(f"device {args.serial} not ready: {probe.stderr.strip() or probe.stdout.strip()}\n"
              f"hint: adb connect {args.serial}", file=sys.stderr)
        return 2

    report_dir = Path(__file__).parent / "reports" / time.strftime("%Y%m%d-%H%M%S")
    adb = Adb(
        args.serial,
        report_dir / "evidence",
        allow_destructive=args.allow_destructive,
    )
    model = adb.shell("getprop ro.product.model")
    api_text = adb.shell("getprop ro.build.version.sdk")
    if model != EXPECTED_MODEL or api_text != str(EXPECTED_API):
        print(
            f"refusing device {args.serial}: expected {EXPECTED_MODEL}/API {EXPECTED_API}, "
            f"got {model or '?'} / API {api_text or '?'}",
            file=sys.stderr,
        )
        return 2

    installed = adb.shell(f"pm path {APP_ID} || true")
    installed_apk = base_apk_path(installed)
    if installed_apk is None:
        print(f"{APP_ID} is not installed — deploy first "
              "(adb install -r app/build/outputs/apk/debug/app-debug.apk)", file=sys.stderr)
        return 2

    expected_apk = args.apk.resolve()
    if not expected_apk.is_file():
        print(f"expected APK does not exist: {expected_apk}", file=sys.stderr)
        return 2
    expected_sha = sha256_file(expected_apk)
    actual_sha = adb.shell(f"sha256sum {shlex.quote(installed_apk)}").split(maxsplit=1)[0].lower()
    if actual_sha != expected_sha:
        print(
            f"refusing stale/mismatched install: host={expected_sha}, installed={actual_sha or '?'}",
            file=sys.stderr,
        )
        return 2

    try:
        source = git_identity()
        before_state = device_state(adb)
        build_fingerprint = adb.shell("getprop ro.build.fingerprint")
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"could not capture run identity/state: {error}", file=sys.stderr)
        return 2

    started_at = utc_now()
    case_exit_code = run(
        adb,
        tiers,
        args.filter,
        report_dir,
        allow_destructive=args.allow_destructive,
        allow_settings=args.allow_settings,
        allow_media_writes=args.allow_media_writes,
    )
    state_error = None
    try:
        after_state = device_state(adb)
    except (OSError, RuntimeError) as error:
        after_state = None
        state_error = f"{type(error).__name__}: {error}"
    restore_errors = restoration_errors(before_state, after_state)
    if state_error is not None:
        restore_errors.append(state_error)
    final_exit_code = attested_exit_code(case_exit_code, restore_errors)
    append_attestation_summary(
        report_dir,
        final_exit_code=final_exit_code,
        errors=restore_errors,
    )
    document: dict[str, object] = {
        "schema_version": 1,
        "started_at_utc": started_at,
        "completed_at_utc": utc_now(),
        "invocation": {
            "serial": args.serial,
            "tiers": tiers,
            "filter": args.filter,
            "apk": str(expected_apk),
            "approvals": {
                "destructive": args.allow_destructive,
                "settings": args.allow_settings,
                "media_writes": args.allow_media_writes,
            },
        },
        "source": source,
        "device": {
            "serial": args.serial,
            "model": model,
            "api": int(api_text),
            "build_fingerprint": build_fingerprint,
        },
        "apk": {
            "host_path": str(expected_apk),
            "installed_path": installed_apk,
            "host_sha256": expected_sha,
            "installed_sha256": actual_sha,
        },
        "state": {
            "before": before_state,
            "after": after_state,
            "restoration_errors": restore_errors,
        },
        "result": {
            "case_exit_code": case_exit_code,
            "final_exit_code": final_exit_code,
            "restoration": "pass" if not restore_errors else "fail",
        },
        "artifacts": artifact_manifest(report_dir),
    }
    try:
        attestation, sidecar = write_attestation(report_dir, document)
    except OSError as error:
        print(f"could not write run attestation: {error}", file=sys.stderr)
        return 2
    print(f"Attestation: {attestation}")
    print(f"Attestation SHA-256: {sidecar}")
    if restore_errors:
        print("Restoration attestation failed: " + "; ".join(restore_errors), file=sys.stderr)
    return final_exit_code


if __name__ == "__main__":
    raise SystemExit(main())
