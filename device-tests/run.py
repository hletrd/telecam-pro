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
import shlex
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from dtest.adb import APP_ID, Adb  # noqa: E402
from dtest.framework import TIERS, run  # noqa: E402
import cases  # noqa: E402, F401  — registers all test cases

EXPECTED_MODEL = "PMA110"
EXPECTED_API = 36
DEFAULT_APK = Path(__file__).resolve().parent.parent / "app/build/outputs/apk/debug/app-debug.apk"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def base_apk_path(pm_path_output: str) -> str | None:
    paths = [line.removeprefix("package:") for line in pm_path_output.splitlines() if line.startswith("package:")]
    return next((path for path in paths if path.endswith("/base.apk")), paths[0] if paths else None)


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

    return run(
        adb,
        tiers,
        args.filter,
        report_dir,
        allow_destructive=args.allow_destructive,
        allow_settings=args.allow_settings,
        allow_media_writes=args.allow_media_writes,
    )


if __name__ == "__main__":
    raise SystemExit(main())
