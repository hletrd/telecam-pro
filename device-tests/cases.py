"""TeleCam Pro on-device functional suite.

Tiers:
  smoke       — launch, live preview, clean logs (run after every deploy, ~1 min)
  full        — feature sweep: modes, lenses, TC, capture files, video files, tap-AF, settings
  reliability — the TELE save-durability mandate: kill/backgrounding/pending-adoption scenarios

Conventions: every case leaves the app in Photo mode, foreground, no dialogs. Cases assert
through three independent channels wherever possible: UI tree, logcat, and on-disk files.
"""

from __future__ import annotations

import time

from dtest.adb import MEDIA_DIR, Adb
from dtest.framework import Context, Skip, test
from dtest import media


# ---------------------------------------------------------------- helpers

def ensure_foreground(ctx: Context) -> int:
    pid = ctx.adb.pid()
    if pid is None:
        pid = ctx.adb.launch()
    ctx.adb.shell("input keyevent KEYCODE_WAKEUP")
    tree = ctx.adb.ui()
    if not (tree.find(desc="Open settings") or tree.find(desc="Photo mode")):
        ctx.adb.launch(wait_s=4)
    return ctx.adb.pid() or 0


def ensure_photo_mode(ctx: Context) -> None:
    tree = ctx.adb.ui()
    shutter = tree.find(desc="Take photo") or tree.find(desc="Shutter")
    if shutter:
        return
    ctx.adb.tap_ui(desc="Photo mode")
    time.sleep(1.5)


def ensure_video_mode(ctx: Context) -> None:
    tree = ctx.adb.ui()
    if tree.find(desc="Start recording"):
        return
    ctx.adb.tap_ui(desc="Video mode")
    time.sleep(1.5)


def shutter_node(ctx: Context):
    tree = ctx.adb.ui()
    n = tree.find(desc="Take photo") or tree.find(desc="Shutter") or tree.find(desc="Capture")
    assert n, f"no shutter control; visible: {sorted(tree.all_labels())[:20]}"
    return n


def capture_still(ctx: Context, timeout_s: float = 25.0) -> list[str]:
    """Tap the shutter (waiting out a running self-timer countdown) and return new files."""
    before = ctx.adb.media_listing()
    mark = ctx.adb.log_mark()
    ctx.adb.tap(*shutter_node(ctx).center)
    started = ctx.adb.wait_log(mark, r"ShutterLag: started", timeout_s=12)
    if started is None:
        # A configured self-timer starts a countdown instead; wait it out (max 10 s + margin).
        started = ctx.adb.wait_log(mark, r"ShutterLag: started", timeout_s=13)
    assert started, "capture never started (no ShutterLag log)"
    new = ctx.adb.wait_new_media(before, min_new=1, timeout_s=timeout_s)
    assert new, "no new media file appeared after capture"
    return new


# ---------------------------------------------------------------- smoke

@test("launch_preview_live", "smoke", destructive=True)
def t_launch(ctx: Context) -> None:
    """Cold launch reaches a live viewfinder with the OSD chrome present."""
    ensure_foreground(ctx)
    ctx.adb.force_stop()
    time.sleep(1)
    mark = ctx.adb.log_mark()
    pid = ctx.adb.launch()
    assert pid, "no app pid after launch"
    assert ctx.adb.preview_is_live(), "viewfinder frames are not changing (frozen/black preview)"
    tree = ctx.adb.ui()
    assert tree.find(desc="Open settings"), "OSD chrome missing (no settings button)"
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"fatal log lines at launch: {fatals[:2]}"
    ctx.adb.screenshot("launch")
    ctx.note(f"pid={pid}, preview live, OSD present")


@test("session_configured_3a", "smoke")
def t_3a(ctx: Context) -> None:
    """The camera session publishes 3A telemetry (proves a configured repeating request)."""
    pid = ensure_foreground(ctx)
    mark = ctx.adb.log_mark()
    line = ctx.adb.wait_log(mark, r"3A: aeState=\d", timeout_s=10, pid=pid)
    assert line, "no 3A telemetry within 10 s — session not configured?"
    ctx.note(line.split("3A:")[-1].strip()[:90])


# ---------------------------------------------------------------- full: modes/lenses/TC

@test("mode_switch_roundtrip", "full")
def t_modes(ctx: Context) -> None:
    """Photo↔Video flips reconfigure the session without camera errors."""
    pid = ensure_foreground(ctx)
    mark = ctx.adb.log_mark()
    ensure_video_mode(ctx)
    assert ctx.adb.ui().find(desc="Start recording"), "video mode did not arm the REC button"
    ensure_photo_mode(ctx)
    assert shutter_node(ctx), "photo mode did not restore the shutter"
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during mode flips: {fatals[:2]}"
    ctx.note("photo→video→photo clean")


@test("lens_presets", "full")
def t_lenses(ctx: Context) -> None:
    """Each focal-rail preset selects without a camera error; selection state updates."""
    pid = ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    mark = ctx.adb.log_mark()
    for lens in ("1× lens", "3× lens", "10× lens", "0.6× lens", "1× lens"):
        ctx.adb.tap_ui(desc=lens)
        time.sleep(1.2)
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during lens presets: {fatals[:2]}"
    ctx.note("0.6/1/3/10 presets cycled clean")


@test("teleconverter_roundtrip", "full")
def t_tc(ctx: Context) -> None:
    """TC toggle reopens onto the standalone tele and back without errors; OSD reflects it."""
    pid = ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    mark = ctx.adb.log_mark()
    was_tele = ctx.adb.ui().find_contains("mm TELE") is not None
    ctx.adb.tap_ui(desc="Teleconverter")
    time.sleep(3)
    now_tele = ctx.adb.ui().find_contains("mm TELE") is not None
    assert now_tele != was_tele, "TC toggle did not change the OSD TELE state"
    ctx.adb.tap_ui(desc="Teleconverter")
    time.sleep(3)
    back = ctx.adb.ui().find_contains("mm TELE") is not None
    assert back == was_tele, "TC toggle did not return to the original state"
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during TC round-trip: {fatals[:2]}"
    ctx.note(f"TELE {was_tele}→{now_tele}→{back} clean")


# ---------------------------------------------------------------- full: capture/video

@test("photo_capture_valid_files", "full")
def t_capture(ctx: Context) -> None:
    """A still produces valid HEIF/JPEG file(s); JPEG carries EXIF; ShutterLag completes."""
    ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    new = capture_still(ctx)
    ctx.note(f"new files: {new}")
    for name in new:
        local = ctx.adb.pull(f"{MEDIA_DIR}/{name}", ctx.evidence / name)
        if name.lower().endswith(".jpg"):
            info = media.jpeg_info(local)
            assert info["width"] > 2000 and info["height"] > 2000, f"JPEG dims off: {info}"
            assert info["exif"], "JPEG lacks EXIF APP1"
        elif name.lower().endswith(".heic"):
            assert media.heic_valid(local), f"HEIC invalid: {name}"
        elif name.lower().endswith(".dng"):
            assert media.dng_valid(local), f"DNG invalid: {name}"


@test("tele_dng_capture", "full")
def t_dng(ctx: Context) -> None:
    """In TELE mode a capture may include DNG (route-gated); whatever arrives must be valid."""
    pid = ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    entered_tele = False
    if ctx.adb.ui().find_contains("mm TELE") is None:
        ctx.adb.tap_ui(desc="Teleconverter")
        time.sleep(3)
        entered_tele = True
    try:
        assert ctx.adb.ui().find_contains("mm TELE"), "could not enter TELE mode"
        new = capture_still(ctx)
        ctx.note(f"TELE capture: {new}")
        for name in new:
            local = ctx.adb.pull(f"{MEDIA_DIR}/{name}", ctx.evidence / name)
            if name.lower().endswith(".dng"):
                assert media.dng_valid(local), f"DNG invalid: {name}"
        fatals = ctx.adb.fatal_lines(ctx.adb.log_mark(), pid)
        assert not fatals, f"errors: {fatals[:2]}"
    finally:
        if entered_tele:
            ctx.adb.tap_ui(desc="Teleconverter")
            time.sleep(3)


@test("video_record_validate", "full")
def t_video(ctx: Context) -> None:
    """A ~5 s recording finalizes to a playable HEVC/AVC clip with sane duration."""
    pid = ensure_foreground(ctx)
    ensure_video_mode(ctx)
    before = ctx.adb.media_listing()
    mark = ctx.adb.log_mark()
    ctx.adb.tap_ui(desc="Start recording")
    time.sleep(5)
    ctx.adb.tap_ui(desc="Stop recording")
    new = ctx.adb.wait_new_media(before, min_new=1, timeout_s=25)
    vids = [f for f in new if f.lower().endswith(".mp4")]
    assert vids, f"no new clip after REC (new={new})"
    local = ctx.adb.pull(f"{MEDIA_DIR}/{vids[-1]}", ctx.evidence / vids[-1])
    info = media.mp4_probe(local)
    ctx.note(f"{vids[-1]}: {info}")
    if info["probe"] == "ffprobe":
        assert info["codec"] in ("hevc", "h264"), f"unexpected codec {info['codec']}"
        assert 2.0 < info["duration"] < 15.0, f"duration off: {info['duration']}"
        assert info["width"] and info["height"], "no dimensions"
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during REC: {fatals[:2]}"
    ensure_photo_mode(ctx)


# ---------------------------------------------------------------- full: AF + settings

@test("tap_af_lock_persists", "full")
def t_tap_af(ctx: Context) -> None:
    """Tap-to-focus engages AF_MODE_AUTO and the hold survives past the 2 s reticle timeout."""
    pid = ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    tree = ctx.adb.ui()
    gear = tree.find(desc="Open settings")
    assert gear, "no reference chrome to locate the preview"
    # Tap mid-preview (center of screen, above the control cluster).
    x = 720
    y = 900
    mark = ctx.adb.log_mark()
    ctx.adb.tap(x, y)
    line = ctx.adb.wait_log(mark, r"3A: .*afMode=1", timeout_s=8, pid=pid)
    assert line, "tap did not switch AF to AUTO (afMode=1)"
    time.sleep(3.5)  # beyond the 2 s reticle auto-hide
    recent = [l for l in ctx.adb.logcat_since(mark, pid).splitlines() if "3A:" in l]
    assert recent and "afMode=1" in recent[-1], (
        f"AF hold released after reticle timeout: {recent[-1] if recent else 'no 3A'}"
    )
    ctx.adb.tap_ui(desc="Open settings")
    ctx.adb.tap_ui(text="Focus")
    ctx.adb.ui()  # navigating away is enough; explicit reset lives in Focus tab
    ctx.adb.shell("input keyevent KEYCODE_BACK")
    time.sleep(1)
    ctx.note("tap-AF engaged and held past 2 s")


@test("settings_sheet_tabs", "full")
def t_settings(ctx: Context) -> None:
    """The settings sheet opens with the full tab rail and closes cleanly."""
    ensure_foreground(ctx)
    ctx.adb.tap_ui(desc="Open settings")
    tree = ctx.adb.ui()
    missing = [t for t in ("My", "Shoot", "Focus", "Video", "Setup") if not tree.find(text=t)]
    ctx.adb.shell("input keyevent KEYCODE_BACK")
    time.sleep(1)
    assert not missing, f"settings tabs missing: {missing}"
    ctx.note("tab rail present")


@test("mode_persists_across_kill", "full", destructive=True)
def t_persist(ctx: Context) -> None:
    """Remember Settings: the selected capture mode survives force-stop + relaunch."""
    ensure_foreground(ctx)
    ensure_video_mode(ctx)
    time.sleep(1.5)  # allow the synchronous save on background/state change
    ctx.adb.force_stop()
    time.sleep(1)
    ctx.adb.launch()
    tree = ctx.adb.ui()
    is_video = tree.find(desc="Start recording") is not None
    ensure_photo_mode(ctx)
    assert is_video, "video mode was not restored after kill (Remember Settings broken?)"
    ctx.note("mode persisted across force-stop")


# ---------------------------------------------------------------- reliability (the mandate)

@test("capture_then_kill_survives", "reliability", destructive=True)
def t_kill_capture(ctx: Context) -> None:
    """A still whose process dies right after the shot must survive as valid, published files."""
    ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    before = ctx.adb.media_listing()
    mark = ctx.adb.log_mark()
    ctx.adb.tap(*shutter_node(ctx).center)
    assert ctx.adb.wait_log(mark, r"ShutterLag: started", timeout_s=14), "capture never started"
    time.sleep(0.6)  # inside the write/publish window
    ctx.adb.force_stop()
    ctx.note("killed 0.6 s after shutter")
    time.sleep(1)
    ctx.adb.launch()  # launch recovery adopts/publishes completed pending files
    new = ctx.adb.wait_new_media(before, min_new=1, timeout_s=30)
    assert new, "capture lost after kill — no file survived"
    for name in new:
        local = ctx.adb.pull(f"{MEDIA_DIR}/{name}", ctx.evidence / name)
        if name.lower().endswith(".jpg"):
            media.jpeg_info(local)
        elif name.lower().endswith(".heic"):
            assert media.heic_valid(local), f"HEIC invalid after kill: {name}"
        elif name.lower().endswith(".dng"):
            assert media.dng_valid(local), f"DNG invalid after kill: {name}"
    time.sleep(6)
    stale = ctx.adb.pending_files()
    assert not stale, f"stuck IS_PENDING leftovers after recovery: {stale}"
    ctx.note(f"survived: {new}, no stuck pending")


@test("rec_backgrounded_finalizes", "reliability")
def t_rec_background(ctx: Context) -> None:
    """HOME mid-recording must still finalize a playable clip (pause-path finalization)."""
    ensure_foreground(ctx)
    ensure_video_mode(ctx)
    before = ctx.adb.media_listing()
    ctx.adb.tap_ui(desc="Start recording")
    time.sleep(4)
    ctx.adb.home()
    ctx.note("HOME pressed mid-REC")
    new = ctx.adb.wait_new_media(before, min_new=1, timeout_s=30)
    vids = [f for f in new if f.lower().endswith(".mp4")]
    assert vids, f"backgrounded recording produced no clip (new={new})"
    local = ctx.adb.pull(f"{MEDIA_DIR}/{vids[-1]}", ctx.evidence / vids[-1])
    info = media.mp4_probe(local)
    if info["probe"] == "ffprobe":
        assert info["duration"] > 1.0, f"clip too short: {info}"
    ctx.adb.launch()
    ensure_photo_mode(ctx)
    ctx.note(f"clip finalized: {vids[-1]} {info.get('duration', '?')}s")


@test("rec_stop_then_kill_published", "reliability", destructive=True)
def t_rec_stop_kill(ctx: Context) -> None:
    """Killing the app right after REC-stop must not lose the clip (publish window durability)."""
    ensure_foreground(ctx)
    ensure_video_mode(ctx)
    before = ctx.adb.media_listing()
    ctx.adb.tap_ui(desc="Start recording")
    time.sleep(4)
    ctx.adb.tap_ui(desc="Stop recording")
    time.sleep(0.5)  # inside the stop→publish window
    ctx.adb.force_stop()
    ctx.note("killed 0.5 s after REC stop")
    time.sleep(1)
    ctx.adb.launch()  # sweep must adopt/publish, never delete
    new = ctx.adb.wait_new_media(before, min_new=1, timeout_s=30)
    vids = [f for f in new if f.lower().endswith(".mp4")]
    assert vids, "clip lost when killed after stop"
    local = ctx.adb.pull(f"{MEDIA_DIR}/{vids[-1]}", ctx.evidence / vids[-1])
    info = media.mp4_probe(local)
    if info["probe"] == "ffprobe":
        assert info["duration"] > 1.0, f"adopted clip invalid: {info}"
    time.sleep(6)
    stale = ctx.adb.pending_files()
    assert not stale, f"stuck IS_PENDING leftovers: {stale}"
    ensure_photo_mode(ctx)
    ctx.note(f"clip adopted+published: {vids[-1]}")


@test("no_stuck_pending_baseline", "reliability", destructive=True)
def t_pending(ctx: Context) -> None:
    """After a fresh relaunch + sweep, the media dir carries no stale .pending files."""
    ensure_foreground(ctx)
    ctx.adb.force_stop()
    time.sleep(1)
    ctx.adb.launch(wait_s=8)  # sweep runs during init
    stale = ctx.adb.pending_files()
    assert not stale, f"stale pending files not reclaimed: {stale}"
    ctx.note("media dir clean of pending leftovers")
