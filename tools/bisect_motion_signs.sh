#!/usr/bin/env bash
# Device bisection for the motion-inversion signs. ONE command, then pan the phone.
#
# WHAT IS BEING MEASURED
#
# gl/MotionInversion.kt predicts which way the SCENE should move from what the gyro
# says the phone did. Turning that prediction into analysis-frame axes needs one
# sign pair — MOTION_YAW_SIGN and MOTION_PITCH_SIGN — and those are the only
# unmeasured terms in the whole feature. Everything else there degrades safely: a
# bad threshold costs coverage, an ambiguous frame reads UNJUDGEABLE. A wrong sign
# does not degrade. It INVERTS every verdict, so the detector would confidently
# tell an operator with a correct setting that their setting is wrong.
#
# This repo has paid for that exact composition four times (rear capture ±dev,
# front/rear gravity, glyph counter-rotation, the EIS axis). Every one was found on
# hardware; none by reasoning. Hence a script rather than an argument.
#
# WHAT THIS SCRIPT DOES
#
# Flips MOTION_SIGNS_VERIFIED true in a scratch copy of the source (never in your
# tree), builds, installs, launches, forces PHOTO mode, and tails the verdict. It
# restores the flag on exit even if you Ctrl-C.
#
# WHAT YOU DO
#
# Hold the phone. Point it at something TEXTURED and LIT — bookshelf, keyboard,
# window, patterned fabric. Not a blank wall, not a dark desk: blocks only vote if
# they carry detail along the pan axis, which is the guard that stops a featureless
# frame from coin-flipping a verdict.
#
# Then pan slowly RIGHT for 2-3 SECONDS. Sustained, not a flick — settling needs
# four consecutive judgeable frames at the ~6 Hz analysis cadence, so a quick jerk
# lands one frame and stops.
#
# READING THE OUTPUT
#
#   settled=MATCHES    signs are correct as written; nothing to change
#   settled=INVERTED   both signs are backwards; flip both constants
#   blocks=0/0         no rotation reached the grid — pan further or faster
#   blocks=0/54        scene has no texture along the pan — point somewhere busier
#   agree≈oppose       periodic content (blinds, tiling) — point elsewhere
#
# Run it with the converter OFF and TELE OFF first: that is the known-upright state
# where the answer is unambiguous. THEN, once the signs are right, mount the
# converter and confirm the feature itself — TELE ON must read MATCHES, and TELE
# OFF with the converter still mounted must read INVERTED. That second one is the
# whole point of the feature.
#
# A single-axis disagreement (yaw right, pitch wrong) is proof of a SIGN BUG, never
# of an inverted image: a 180° rotation flips both axes together.
set -uo pipefail

SERIAL="${1:-100.125.100.120:5555}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/kotlin/me/hletrd/telecampro/gl/MotionInversion.kt"
PKG=me.hletrd.telecampro.debug

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH:$HOME/Library/Android/sdk/platform-tools"

restore() {
  perl -pi -e 's/^internal const val MOTION_SIGNS_VERIFIED = true$/internal const val MOTION_SIGNS_VERIFIED = false/' "$SRC"
  echo
  echo "[restored] MOTION_SIGNS_VERIFIED = false — your tree is back to shipping state."
  echo "           The installed DEBUG build still has it enabled; that is fine, it is debug-only."
}
trap restore EXIT INT TERM

echo "[1/5] arming the gate (scratch edit, restored on exit)"
perl -pi -e 's/^internal const val MOTION_SIGNS_VERIFIED = false$/internal const val MOTION_SIGNS_VERIFIED = true/' "$SRC"
grep -q "MOTION_SIGNS_VERIFIED = true" "$SRC" || { echo "could not arm the gate — is the constant still named that?"; exit 1; }

echo "[2/5] building"
( cd "$ROOT" && ./gradlew -q :app:assembleDebug ) || { echo "build failed"; exit 1; }

echo "[3/5] installing on $SERIAL"
adb -s "$SERIAL" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null || { echo "install failed — is the device connected? try tools/adb_fleet.sh"; exit 1; }

echo "[4/5] launching the DEBUG build (NOT the release one — they share an icon)"
adb -s "$SERIAL" shell am force-stop me.hletrd.telecampro    # release build has no detector
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am start -n "$PKG/me.hletrd.telecampro.MainActivity" >/dev/null
sleep 8

# VIDEO mode is silent by construction: video-P uses the HAL's AE, which runs no
# analysis readback, and the rider deliberately rides that readback rather than
# forcing one. Persisted settings can leave the app there, so put it in PHOTO.
mode=$(adb -s "$SERIAL" logcat -d 2>/dev/null | grep "3A:" | tail -1 | grep -oE "mode=[A-Z]+")
if [ "$mode" = "mode=VIDEO" ]; then
  echo "      app was in VIDEO (no readback there) — switching to PHOTO"
  b=$(adb -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; \
      adb -s "$SERIAL" shell cat /sdcard/ui.xml 2>/dev/null | tr '>' '\n' \
      | grep -oE 'content-desc="Photo mode"[^\n]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
      | grep -oE '\[[0-9]+,[0-9]+\]' | head -2 | tr -d '[]' | tr '\n' ' ')
  set -- $b
  if [ $# -ge 4 ]; then adb -s "$SERIAL" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 4; fi
fi

echo "[5/5] watching. Pan RIGHT, slowly, for 2-3 seconds. Ctrl-C when done."
echo "-------------------------------------------------------------------"
adb -s "$SERIAL" logcat -v time | grep -E --line-buffered "MotionInversion|FATAL EXCEPTION"
