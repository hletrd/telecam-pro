#!/usr/bin/env python3
"""TeleCam Pro on-device functional test runner.

Usage:
  python3 device-tests/run.py --serial 127.0.0.1:5599 --tier smoke
  python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full --tier reliability
  python3 device-tests/run.py --serial 127.0.0.1:5599 --tier all -k capture

Requires: adb on PATH with the PMA110 connected (wireless-debugging loopback proxy is
fine). ffprobe is optional — video checks degrade to structural validation without it.
Reports land in device-tests/reports/<timestamp>/ (gitignored).
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from dtest.adb import Adb  # noqa: E402
from dtest.framework import TIERS, run  # noqa: E402
import cases  # noqa: E402, F401  — registers all test cases


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--serial", required=True, help="adb serial, e.g. 127.0.0.1:5599")
    ap.add_argument("--tier", action="append", choices=[*TIERS, "all"], default=None,
                    help="tier(s) to run; repeatable; default smoke")
    ap.add_argument("-k", dest="filter", default=None, help="substring filter on case names")
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
    adb = Adb(args.serial, report_dir / "evidence")
    installed = adb.shell("pm path me.hletrd.telecampro.debug || true")
    if "package:" not in installed:
        print("me.hletrd.telecampro.debug is not installed — deploy first "
              "(adb install -r app/build/outputs/apk/debug/app-debug.apk)", file=sys.stderr)
        return 2

    return run(adb, tiers, args.filter, report_dir)


if __name__ == "__main__":
    raise SystemExit(main())
