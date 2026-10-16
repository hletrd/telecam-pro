#!/usr/bin/env python3
"""Field check: does a tap actually meter the point the user tapped?

Answers ONE question — whether the AE metering region lands on the tapped half of the frame or its
horizontal mirror. That is the check the front route needs after the metering-mirror split
(`FrontMirrorConvention.meteringMirrorX`, 2026-07-28), and the rear route can be run the same way as
a control.

WHY THIS EXISTS AS A SCRIPT. The check is easy to run and easy to MISREAD. A first remote attempt
(2026-07-28) tapped mirrored pairs on a lit ceiling, saw ISO not move at all, and could have been
recorded either as "regions are ignored" or as "the mirror is wrong". It was neither: that sensor's
advertised sensitivityRange is [100, 16000], the video FPS pin held exposure at 1/30 s, and AE was
sitting hard against BOTH limits with no freedom to respond to anything. A railed meter cannot
disagree with you. So this script REFUSES to return a verdict unless it first proves the meter has
somewhere to move, and unless the scene actually differs across the two tap points.

PRECONDITIONS the script enforces (it exits non-zero and says which one failed):
  1. A configuration where HAL AE owns exposure, because only then do AE regions apply at all.
     `programShouldRunAppSide` says that is VIDEO+PROGRAM, or PHOTO+PROGRAM with flash AUTO/ON.
     Everything else runs the APP-SIDE loop, which meters GL luma and ignores HAL regions entirely.
     The script cannot read the flash mode out of the 3A line, so it ACCEPTS photo and warns: a
     photo run without flash simply comes back INCONCLUSIVE at check 4 rather than lying.
  2. AE off its rails: reported ISO strictly inside the advertised range, so it can move both ways.
  3. Real scene contrast between the two tap points (default >= 18 luma), or the meter has no reason
     to respond even when it is working.
  4. The meter actually moved between the two taps.

CHOOSING A MODE. Video is the reliable one, but it PINS the frame rate, so exposure cannot go past
~1/30 s and a dim scene rails ISO against its ceiling (exactly what defeated the first attempt).
Photo lifts that pin — but only earns HAL AE with flash AUTO/ON, and the FRONT camera on this device
advertises no flash at all, so front runs must use video and therefore need more light. For the
front route, add light rather than switching modes.

WHAT IT DOES NOT PROVE. Only the horizontal half. The tap mapping's ROTATION term is still
uncalibrated on the front route (`viewTapToSensorPoint` documents itself as approximate), so a
vertical or axis error would survive a PASS here and is a separate finding.

Usage:
    tools/field/tap_af_aim.py --serial 127.0.0.1:37605 [--package me.hletrd.telecampro.debug]

Aim the camera at a scene that is clearly brighter on one side than the other (a window wall, a lit
desk beside a shaded one), put the app in VIDEO mode with exposure PROGRAM, then run it.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time

# Tap points are placed well inside the preview and clear of the bottom control cluster.
TAP_Y_FRACTION = 0.66
TAP_X_INSET_FRACTION = 0.12
# A tapped region that differs by less than this in mean luma gives the meter nothing to say.
MIN_SCENE_CONTRAST = 18.0
# Below this relative ISO change the two taps are indistinguishable from meter noise.
MIN_ISO_RESPONSE = 0.06
SETTLE_S = 9.0


def adb(serial: str, *args: str, binary: bool = False):
    out = subprocess.run(
        ["adb", "-s", serial, *args], capture_output=True, timeout=180
    )
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def latest_3a(serial: str, package: str) -> dict[str, int] | None:
    """Newest 3A debug line as a dict. Requires a DEBUG build — release strips these logs."""
    pid = adb(serial, "shell", "pidof", package).strip()
    if not pid:
        return None
    log = adb(serial, "logcat", "-d", f"--pid={pid}")
    lines = [ln for ln in log.splitlines() if "3A:" in ln]
    if not lines:
        return None
    fields = dict(re.findall(r"(\w+)=(-?\d+)", lines[-1]))
    mode = re.search(r"mode=(\w+)", lines[-1])
    out = {k: int(v) for k, v in fields.items() if v.lstrip("-").isdigit()}
    if mode:
        out["_video"] = 1 if mode.group(1) == "VIDEO" else 0
    return out


def sensitivity_range(serial: str, iso_now: int) -> tuple[int, int] | None:
    """Advertised ISO range of the camera whose ceiling/floor brackets the live value.

    dumpsys lists every HAL device; we cannot tell which is open from it, so pick the range that
    actually contains the reported ISO. Ambiguity is resolved toward the TIGHTEST such range, which
    is the conservative choice: a tighter range makes "railed" easier to declare, never harder.
    """
    dump = adb(serial, "shell", "dumpsys media.camera")
    ranges = [
        (int(a), int(b))
        for a, b in re.findall(r"sensitivityRange.*?\n\s*\[(\d+)\s+(\d+)\s*\]", dump, re.S)
    ]
    holding = [r for r in ranges if r[0] <= iso_now <= r[1]]
    return min(holding, key=lambda r: r[1] - r[0]) if holding else None


def screen_size(serial: str) -> tuple[int, int]:
    m = re.search(r"(\d+)x(\d+)", adb(serial, "shell", "wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1440, 3168)


def region_luma(serial: str, x: int, y: int, half: int = 110) -> float:
    from PIL import Image  # noqa: PLC0415 - optional dep, only needed at run time
    import io

    png = adb(serial, "exec-out", "screencap", "-p", binary=True)
    a = Image.open(io.BytesIO(png)).convert("L")
    box = a.crop((max(0, x - half), max(0, y - half), x + half, y + half))
    px = box.tobytes()  # not getdata(): deprecated in Pillow 14
    return sum(px) / len(px)


def fail(msg: str) -> None:
    print(f"INCONCLUSIVE — {msg}")
    sys.exit(2)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--package", default="me.hletrd.telecampro.debug")
    args = ap.parse_args()

    before = latest_3a(args.serial, args.package)
    if not before or "iso" not in before:
        fail(
            "no 3A telemetry. Needs the DEBUG build running and streaming (release strips these "
            "logs). If the debug app IS live, the process has most likely burned ColorOS's 300-row "
            "per-process log quota and everything it prints is being dropped — force-stop and "
            "relaunch it to get a fresh process, then re-run."
        )
    if not before.get("_video"):
        # Not a refusal: photo + PROGRAM + flash AUTO/ON is genuinely HAL-AE, and it is the only way
        # to escape video's FPS pin when the scene is dim. The flash mode is not recoverable from the
        # 3A line, so warn rather than guess — a photo run WITHOUT flash runs the app-side loop,
        # which ignores regions, and that shows up honestly as "AE barely moved" at the end.
        print(
            "note: PHOTO mode — valid only with flash AUTO/ON (which is what hands exposure to HAL "
            "AE). Without it the app-side loop ignores regions and this run will end INCONCLUSIVE. "
            "The front camera advertises no flash, so front runs must use VIDEO."
        )

    iso0 = before["iso"]
    rng = sensitivity_range(args.serial, iso0)
    if rng and (iso0 <= rng[0] or iso0 >= rng[1]):
        fail(
            f"AE is RAILED (iso={iso0}, advertised range {rng[0]}-{rng[1]}). The meter has no "
            "freedom to respond to any region, so neither answer would mean anything. Add light."
        )

    w, h = screen_size(args.serial)
    y = int(h * TAP_Y_FRACTION)
    x_left, x_right = int(w * TAP_X_INSET_FRACTION), int(w * (1 - TAP_X_INSET_FRACTION))

    lum_l, lum_r = region_luma(args.serial, x_left, y), region_luma(args.serial, x_right, y)
    if abs(lum_l - lum_r) < MIN_SCENE_CONTRAST:
        fail(
            f"scene is too even (left {lum_l:.0f} vs right {lum_r:.0f}, need "
            f">= {MIN_SCENE_CONTRAST:.0f}). Aim at something clearly brighter on one side."
        )

    readings = {}
    for label, x in (("left", x_left), ("right", x_right)):
        adb(args.serial, "logcat", "-c")
        adb(args.serial, "shell", "input", "tap", str(x), str(y))
        time.sleep(SETTLE_S)
        sample = latest_3a(args.serial, args.package)
        if not sample or "iso" not in sample:
            fail(f"lost 3A telemetry after the {label} tap")
        readings[label] = sample["iso"]

    bright, dim = ("left", "right") if lum_l > lum_r else ("right", "left")
    iso_bright, iso_dim = readings[bright], readings[dim]
    print(f"scene luma      left={lum_l:.0f} right={lum_r:.0f}  (brighter side: {bright})")
    print(f"iso after taps  {bright}={iso_bright}  {dim}={iso_dim}")

    spread = abs(iso_bright - iso_dim) / max(iso_bright, iso_dim)
    if spread < MIN_ISO_RESPONSE:
        fail(
            f"AE barely moved ({spread * 100:.1f}% < {MIN_ISO_RESPONSE * 100:.0f}%). Either regions "
            "are not driving this meter or the contrast is too small to separate the halves."
        )

    # Metering the BRIGHT side must pull exposure DOWN, i.e. a lower ISO than metering the dim side.
    if iso_bright < iso_dim:
        print("PASS — tapping the bright side lowered ISO: metering follows the tapped half.")
        sys.exit(0)
    print(
        "FAIL — tapping the bright side RAISED ISO relative to the dim side: the region is landing "
        "on the horizontal mirror of the tap. Check FrontMirrorConvention.meteringMirrorX."
    )
    sys.exit(1)


if __name__ == "__main__":
    main()
