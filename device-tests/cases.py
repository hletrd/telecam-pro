"""TeleCam Pro on-device functional suite.

Tiers:
  smoke       — launch, live preview, clean logs (run after every deploy, ~1 min)
  full        — feature sweep: modes, lenses, TC, capture files, video files, tap-AF, settings
  reliability — the TELE save-durability mandate: kill/backgrounding/pending-adoption scenarios

Conventions: every case leaves the app in Photo mode, foreground, no dialogs. Cases assert
through three independent channels wherever possible: UI tree, logcat, and on-disk files.
"""

from __future__ import annotations

import re
import time

from dtest.adb import APP_ID, MEDIA_DIR, MEDIA_RELATIVE_PATH, Adb, MediaRow
from dtest.framework import Context, Incomplete, test
from dtest import media

FOCAL_PRESETS = ("0.6× lens", "1× lens", "3× lens", "10× lens")
SETTINGS_TABS = ("My", "Shoot", "Exposure", "Focus", "Lens", "Video", "Image", "Assist", "Setup")
CAPTURE_SETTLED = re.compile(
    r"CaptureFamily: settled stem=(IMG_TELECAM_F1_[0-9]{13}_[0-9]{10}) "
    r"outputs=([a-z0-9,]+)"
)


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


def active_multi_drive_label(tree) -> str | None:
    """Return the non-Single drive OSD tag without mutating the user's persisted setting."""
    for label in tree.all_labels():
        if label == "BURST" or re.fullmatch(r"AEB±\d+", label) or re.fullmatch(r"TL \d+s", label):
            return label
    return None


def capture_still(ctx: Context, timeout_s: float = 25.0) -> list[MediaRow]:
    """Tap the shutter and return the complete, stably published MediaStore family."""
    drive_label = active_multi_drive_label(ctx.adb.ui())
    if drive_label is not None:
        raise Incomplete(
            f"still verification requires Single drive; current persisted drive is {drive_label}"
        )
    before = {row.key for row in ctx.adb.media_store_rows()}
    mark = ctx.adb.log_mark()
    ctx.adb.tap(*shutter_node(ctx).center)
    started = ctx.adb.wait_log(mark, r"ShutterLag: started", timeout_s=12)
    if started is None:
        # A configured self-timer starts a countdown instead; wait it out (max 10 s + margin).
        started = ctx.adb.wait_log(mark, r"ShutterLag: started", timeout_s=13)
    assert started, "capture never started (no ShutterLag log)"
    settled_line = ctx.adb.wait_log(mark, CAPTURE_SETTLED.pattern, timeout_s=timeout_s)
    assert settled_line, "capture save lanes did not report a settled family"
    settled = CAPTURE_SETTLED.search(settled_line)
    assert settled is not None, f"malformed capture-family completion log: {settled_line}"
    stem = settled.group(1)
    expected_names = {f"{stem}.{extension}" for extension in settled.group(2).split(",")}

    new = ctx.adb.wait_new_media_rows(
        before,
        min_new=len(expected_names),
        timeout_s=timeout_s,
        settle_s=1.0,
    )
    assert new, "no new MediaStore row appeared after capture"
    assert all(not row.is_pending for row in new), f"capture left pending rows: {new}"
    actual_names = {row.display_name for row in new}
    assert actual_names == expected_names, (
        f"capture family outputs differ: expected={sorted(expected_names)}, "
        f"actual={sorted(actual_names)}"
    )
    return new


def assert_published_row(row: MediaRow) -> None:
    assert row.relative_path == MEDIA_RELATIVE_PATH, f"wrong relative path: {row}"
    assert row.owner_package_name == APP_ID, f"wrong MediaStore owner: {row}"
    assert not row.is_pending, f"row is still pending: {row}"
    assert row.size_bytes > 0, f"row has no bytes: {row}"


def focal_rail_error(tree, expected: str) -> str | None:
    nodes = {description: tree.find_desc_exact(description) for description in FOCAL_PRESETS}
    missing = [description for description, node in nodes.items() if node is None]
    if missing:
        return f"focal controls missing: {missing}"

    present = [node for node in nodes.values() if node is not None]
    wrong_roles = [node.desc for node in present if not node.class_name.endswith("RadioButton")]
    if wrong_roles:
        return f"focal controls are not radio buttons: {wrong_roles}"
    if any(not node.checkable for node in present):
        return "one or more focal controls are not checkable"

    checked = [node.desc for node in present if node.checked]
    if checked != [expected]:
        return f"expected exactly {expected!r} checked, got {checked}"
    return None


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
        deadline = time.time() + 6
        error = "selection state did not settle"
        while time.time() < deadline:
            error = focal_rail_error(ctx.adb.ui(), lens)
            if error is None:
                break
            time.sleep(0.4)
        assert error is None, error
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
    ctx.note(f"new rows: {[(row.key, row.display_name, row.mime_type) for row in new]}")
    assert all(row.collection == "image" for row in new), f"non-image rows joined still capture: {new}"
    known_mimes = {"image/heic", "image/jpeg", "image/x-adobe-dng"}
    assert all(row.mime_type in known_mimes for row in new), f"unexpected still MIME: {new}"
    for row in new:
        assert_published_row(row)
        name = row.display_name
        local = ctx.adb.pull(f"{MEDIA_DIR}/{name}", ctx.evidence / name)
        if row.mime_type == "image/jpeg":
            info = media.jpeg_info(local)
            assert info["width"] > 2000 and info["height"] > 2000, f"JPEG dims off: {info}"
            assert info["exif"], "JPEG lacks EXIF APP1"
            assert (row.width, row.height) == (info["width"], info["height"]), (
                f"JPEG MediaStore/file dimensions differ: row={row}, file={info}"
            )
        elif row.mime_type == "image/heic":
            assert media.heic_valid(local), f"HEIC invalid: {name}"
            assert row.width > 2000 and row.height > 2000, f"HEIC MediaStore dimensions off: {row}"
            file_dimensions = media.image_dimensions(local)
            if file_dimensions is None:
                raise Incomplete("sips is unavailable; HEIF file dimensions were not decoded")
            assert (row.width, row.height) == file_dimensions, (
                f"HEIF MediaStore/file dimensions differ: row={row}, file={file_dimensions}"
            )
        elif row.mime_type == "image/x-adobe-dng":
            assert media.dng_valid(local), f"DNG invalid: {name}"


@test("tele_dng_capture", "full")
def t_dng(ctx: Context) -> None:
    """In TELE mode a capture may include DNG (route-gated); whatever arrives must be valid."""
    pid = ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    mark = ctx.adb.log_mark()
    entered_tele = False
    if ctx.adb.ui().find_contains("mm TELE") is None:
        ctx.adb.tap_ui(desc="Teleconverter")
        time.sleep(3)
        entered_tele = True
    try:
        assert ctx.adb.ui().find_contains("mm TELE"), "could not enter TELE mode"
        new = capture_still(ctx)
        ctx.note(f"TELE capture: {[(row.key, row.display_name, row.mime_type) for row in new]}")
        dng_rows = [row for row in new if row.mime_type == "image/x-adobe-dng"]
        for row in new:
            assert_published_row(row)
        fatals = ctx.adb.fatal_lines(mark, pid)
        assert not fatals, f"errors: {fatals[:2]}"
        if not dng_rows:
            raise Incomplete("TELE capture valid, but DNG output was not enabled; RAW was not verified")
        for row in dng_rows:
            local = ctx.adb.pull(
                f"{MEDIA_DIR}/{row.display_name}",
                ctx.evidence / row.display_name,
            )
            assert media.dng_valid(local), f"DNG invalid: {row.display_name}"
    finally:
        if entered_tele:
            ctx.adb.tap_ui(desc="Teleconverter")
            time.sleep(3)


@test("video_record_validate", "full")
def t_video(ctx: Context) -> None:
    """A ~5 s recording finalizes to a playable HEVC/AVC clip with sane duration."""
    pid = ensure_foreground(ctx)
    ensure_video_mode(ctx)
    before = {row.key for row in ctx.adb.media_store_rows()}
    mark = ctx.adb.log_mark()
    ctx.adb.tap_ui(desc="Start recording")
    time.sleep(5)
    ctx.adb.tap_ui(desc="Stop recording")
    new = ctx.adb.wait_new_media_rows(before, min_new=1, timeout_s=25)
    vids = [row for row in new if row.collection == "video" and row.mime_type == "video/mp4"]
    assert vids, f"no new clip after REC (new={new})"
    assert len(vids) == 1 and len(new) == 1, f"REC published an unexpected row family: {new}"
    row = vids[0]
    assert_published_row(row)
    local = ctx.adb.pull(
        f"{MEDIA_DIR}/{row.display_name}",
        ctx.evidence / row.display_name,
    )
    info = media.mp4_probe(local)
    ctx.note(f"{row.display_name}: {info}")
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
    tab_nodes = {tab: tree.find(text=tab) for tab in SETTINGS_TABS}
    missing = [tab for tab, node in tab_nodes.items() if node is None]
    selected = [tab for tab, node in tab_nodes.items() if node is not None and node.selected]
    ctx.adb.shell("input keyevent KEYCODE_BACK")
    time.sleep(1)
    assert not missing, f"settings tabs missing: {missing}"
    assert len(selected) == 1, f"expected exactly one selected settings tab, got {selected}"
    ctx.note(f"all 9 tabs present; selected={selected[0]}")


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
    before = {row.key for row in ctx.adb.media_store_rows()}
    mark = ctx.adb.log_mark()
    ctx.adb.tap(*shutter_node(ctx).center)
    assert ctx.adb.wait_log(mark, r"ShutterLag: started", timeout_s=14), "capture never started"
    time.sleep(0.6)  # inside the write/publish window
    ctx.adb.force_stop()
    ctx.note("killed 0.6 s after shutter")
    time.sleep(1)
    ctx.adb.launch()  # launch recovery adopts/publishes completed pending files
    new = ctx.adb.wait_new_media_rows(before, min_new=1, timeout_s=30)
    assert new, "capture lost after kill — no file survived"
    for row in new:
        assert_published_row(row)
        name = row.display_name
        local = ctx.adb.pull(f"{MEDIA_DIR}/{name}", ctx.evidence / name)
        if row.mime_type == "image/jpeg":
            media.jpeg_info(local)
        elif row.mime_type == "image/heic":
            assert media.heic_valid(local), f"HEIC invalid after kill: {name}"
        elif row.mime_type == "image/x-adobe-dng":
            assert media.dng_valid(local), f"DNG invalid after kill: {name}"
    time.sleep(6)
    stale = ctx.adb.pending_rows()
    assert not stale, f"stuck IS_PENDING leftovers after recovery: {stale}"
    ctx.note(f"survived: {new}, no stuck pending")


@test("rec_backgrounded_finalizes", "reliability")
def t_rec_background(ctx: Context) -> None:
    """HOME mid-recording must still finalize a playable clip (pause-path finalization)."""
    ensure_foreground(ctx)
    ensure_video_mode(ctx)
    before = {row.key for row in ctx.adb.media_store_rows()}
    ctx.adb.tap_ui(desc="Start recording")
    time.sleep(4)
    ctx.adb.home()
    ctx.note("HOME pressed mid-REC")
    new = ctx.adb.wait_new_media_rows(before, min_new=1, timeout_s=30)
    vids = [row for row in new if row.collection == "video" and row.mime_type == "video/mp4"]
    assert vids, f"backgrounded recording produced no clip (new={new})"
    row = vids[-1]
    assert_published_row(row)
    local = ctx.adb.pull(
        f"{MEDIA_DIR}/{row.display_name}",
        ctx.evidence / row.display_name,
    )
    info = media.mp4_probe(local)
    if info["probe"] == "ffprobe":
        assert info["duration"] > 1.0, f"clip too short: {info}"
    ctx.adb.launch()
    ensure_photo_mode(ctx)
    ctx.note(f"clip finalized: {row.display_name} {info.get('duration', '?')}s")


@test("rec_stop_then_kill_published", "reliability", destructive=True)
def t_rec_stop_kill(ctx: Context) -> None:
    """Killing the app right after REC-stop must not lose the clip (publish window durability)."""
    ensure_foreground(ctx)
    ensure_video_mode(ctx)
    before = {row.key for row in ctx.adb.media_store_rows()}
    ctx.adb.tap_ui(desc="Start recording")
    time.sleep(4)
    ctx.adb.tap_ui(desc="Stop recording")
    time.sleep(0.5)  # inside the stop→publish window
    ctx.adb.force_stop()
    ctx.note("killed 0.5 s after REC stop")
    time.sleep(1)
    ctx.adb.launch()  # sweep must adopt/publish, never delete
    new = ctx.adb.wait_new_media_rows(before, min_new=1, timeout_s=30)
    vids = [row for row in new if row.collection == "video" and row.mime_type == "video/mp4"]
    assert vids, "clip lost when killed after stop"
    row = vids[-1]
    assert_published_row(row)
    local = ctx.adb.pull(
        f"{MEDIA_DIR}/{row.display_name}",
        ctx.evidence / row.display_name,
    )
    info = media.mp4_probe(local)
    if info["probe"] == "ffprobe":
        assert info["duration"] > 1.0, f"adopted clip invalid: {info}"
    time.sleep(6)
    stale = ctx.adb.pending_rows()
    assert not stale, f"stuck IS_PENDING leftovers: {stale}"
    ensure_photo_mode(ctx)
    ctx.note(f"clip adopted+published: {row.display_name}")


@test("no_stuck_pending_baseline", "reliability", destructive=True)
def t_pending(ctx: Context) -> None:
    """After a fresh relaunch + sweep, the media dir carries no stale .pending files."""
    ensure_foreground(ctx)
    ctx.adb.force_stop()
    time.sleep(1)
    ctx.adb.launch(wait_s=8)  # sweep runs during init
    stale = ctx.adb.pending_rows()
    assert not stale, f"stale pending files not reclaimed: {stale}"
    ctx.note("media dir clean of pending leftovers")
