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
import math
from dataclasses import dataclass
from fractions import Fraction

from dtest.adb import APP_ID, MEDIA_DIR, MEDIA_RELATIVE_PATH, Adb, DisplayMetrics, MediaRow, UiNode
from dtest.framework import Context, Incomplete, UnsafeState, test
from dtest import media

FOCAL_PRESETS = ("0.6× lens", "1× lens", "3× lens", "10× lens")
CAPTURE_MODES = ("Photo mode", "Video mode")
SETTINGS_TABS = ("My", "Shoot", "Exposure", "Focus", "Lens", "Video", "Image", "Assist", "Setup")
SETTINGS_TITLES = (
    "My Menu", "Shooting", "Exposure", "Focus", "Lens", "Video", "Image", "Assist", "Setup"
)
FN_TILE_LABELS = {
    "AE", "Focus", "Shutter", "ISO", "WB", "EV", "Zoom", "Stabilization", "Drive",
    "Meter", "Peaking", "Zebra", "Gamma", "Audio", "Grid", "Level", "Loupe", "Tele",
    "Open Gate", "Frame",
}
CAPTURE_SETTLED = re.compile(
    r"CaptureFamily: settled stem=(IMG_TELECAM_F1_[0-9]{13}_[0-9]{10}) "
    r"outputs=([a-z0-9,]+)"
)
VIDEO_OSD = re.compile(
    r"^(?P<resolution>.+) (?P<fps>[0-9]+(?:\.[0-9]+)?)p "
    r"(?P<codec>HEVC|H\.264|APV) (?P<mbps>[0-9]+)Mb$"
)
RECORDING_SPEC = re.compile(
    r"RecordingSpec: admitted stem=(VID_TELECAM_F1_[0-9]{13}_[0-9]{10}) "
    r"codec=(HEVC|AVC|APV) source=([0-9]+)x([0-9]+) "
    r"encoder=([0-9]+)x([0-9]+) bitrate=([0-9]+) "
    r"fps=([0-9]+\.[0-9]+) transfer=(HLG|LOG|SDR) audio=(true|false)"
)
RECORDING_FINALIZED = re.compile(
    r"RecordingFinalized: captureId=([0-9]+) saved=(true|false) "
    r"error=([A-Za-z0-9_.$-]+)"
)
MODE_THREE_A = re.compile(
    r"3A: controllerId=([0-9]+) opticsGeneration=([0-9]+) "
    r"requestGeneration=([0-9]+) mode=(PHOTO|VIDEO)\b"
)
AF_MODE = re.compile(r"\bafMode=([0-9]+)\b")
SESSION_ACCEPTED = re.compile(
    r"CameraSessionAccepted: controllerId=([0-9]+) opticsGeneration=([0-9]+) "
    r"sessionGeneration=([0-9]+) requestGeneration=([0-9]+) "
    r"mode=(PHOTO|VIDEO) cameraId=(\S+) ready=(true|false)"
)
LONG_VIDEO_SECONDS = 65


@dataclass(frozen=True)
class ModeThreeAEvidence:
    line: str
    controller_id: int
    optics_generation: int
    request_generation: int


@dataclass(frozen=True)
class SessionAcceptance:
    line: str
    controller_id: int
    optics_generation: int
    session_generation: int
    request_generation: int


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


def newest_mode_three_a(
    log: str,
    mode: str,
    *,
    after_request_generation: int = 0,
    controller_id: int | None = None,
    optics_generation: int | None = None,
) -> ModeThreeAEvidence | None:
    """Return the newest 3A result owned by an exact accepted route when supplied."""
    candidates: list[ModeThreeAEvidence] = []
    for line in log.splitlines():
        match = MODE_THREE_A.search(line)
        if match is None:
            continue
        evidence = ModeThreeAEvidence(
            line=line,
            controller_id=int(match.group(1)),
            optics_generation=int(match.group(2)),
            request_generation=int(match.group(3)),
        )
        if match.group(4) != mode or evidence.request_generation <= after_request_generation:
            continue
        if controller_id is not None and evidence.controller_id != controller_id:
            continue
        if optics_generation is not None and evidence.optics_generation != optics_generation:
            continue
        candidates.append(evidence)
    return max(candidates, key=lambda item: item.request_generation) if candidates else None


def newest_session_acceptance(
    log: str,
    mode: str,
    *,
    after_optics_generation: int = -1,
) -> SessionAcceptance | None:
    candidates: list[SessionAcceptance] = []
    for line in log.splitlines():
        match = SESSION_ACCEPTED.search(line)
        if match is None or match.group(5) != mode or match.group(7) != "true":
            continue
        evidence = SessionAcceptance(
            line=line,
            controller_id=int(match.group(1)),
            optics_generation=int(match.group(2)),
            session_generation=int(match.group(3)),
            request_generation=int(match.group(4)),
        )
        if evidence.optics_generation > after_optics_generation:
            candidates.append(evidence)
    return max(
        candidates,
        key=lambda item: (item.optics_generation, item.session_generation),
    ) if candidates else None


def wait_mode_three_a(
    ctx: Context,
    mark: str,
    pid: int,
    mode: str,
    *,
    after_request_generation: int = 0,
    controller_id: int | None = None,
    optics_generation: int | None = None,
    timeout_s: float = 10,
) -> ModeThreeAEvidence | None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        evidence = newest_mode_three_a(
            ctx.adb.logcat_since(mark, pid),
            mode,
            after_request_generation=after_request_generation,
            controller_id=controller_id,
            optics_generation=optics_generation,
        )
        if evidence is not None:
            return evidence
        time.sleep(0.5)
    return None


def wait_session_acceptance(
    ctx: Context,
    mark: str,
    pid: int,
    mode: str,
    *,
    after_optics_generation: int = -1,
    timeout_s: float = 12,
) -> SessionAcceptance | None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        evidence = newest_session_acceptance(
            ctx.adb.logcat_since(mark, pid),
            mode,
            after_optics_generation=after_optics_generation,
        )
        if evidence is not None:
            return evidence
        time.sleep(0.5)
    return None


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


def video_target_precondition_error(tree) -> str | None:
    """Validate the visible preset without changing the photographer's persisted settings."""
    osd = [
        (label, VIDEO_OSD.fullmatch(label))
        for label in tree.all_labels()
        if VIDEO_OSD.fullmatch(label)
    ]
    if len(osd) != 1:
        return f"expected one video OSD spec, got {[label for label, _ in osd]}"
    label, match = osd[0]
    assert match is not None
    if match.group("codec") != "HEVC" or match.group("fps") != "29.97":
        return f"requires HEVC 29.97p, current OSD is {label}"
    if "HLG" not in tree.all_labels():
        return "requires HLG transfer; HLG is not present in the video OSD"
    return None


def selected_video_osd(tree):
    matches = [VIDEO_OSD.fullmatch(label) for label in tree.all_labels()]
    matches = [match for match in matches if match is not None]
    return matches[0] if len(matches) == 1 else None


def video_resolution_label(width: int, height: int) -> str:
    """Mirror the app's displayed resolution class for OSD/engine cross-validation."""
    if height * 4 == width * 3:
        if height >= 5760:
            return "8K 4:3"
        if height >= 2880:
            return "4K 4:3"
        if height >= 1920:
            return "2.5K 4:3"
        if height >= 1440:
            return "1080 4:3"
        return f"{width}×{height}"
    return {
        4320: "8K",
        2160: "4K",
        1440: "1440p",
        1080: "1080p",
        720: "720p",
    }.get(height, f"{width}×{height}")


def stop_recording_verified(
    ctx: Context,
    pid: int,
    *,
    timeout_s: float = 15.0,
    poll_s: float = 0.25,
    tap_retry_s: float = 3.0,
) -> bool:
    """Retry Stop until idle UI is visible; process changes are an unsafe suite abort."""
    deadline = time.monotonic() + timeout_s
    last_tap = 0.0
    last_state = "not checked"
    last_error: Exception | None = None
    observed_recording = False
    while time.monotonic() < deadline:
        try:
            current_pid = ctx.adb.pid()
            if current_pid != pid:
                raise UnsafeState(f"app process changed during REC cleanup: {pid} -> {current_pid}")
            tree = ctx.adb.ui()
            start = tree.find_desc_exact("Start recording")
            stop = tree.find_desc_exact("Stop recording")
            last_state = f"start={start is not None}, stop={stop is not None}"
            if start is not None and stop is None:
                return observed_recording
            now = time.monotonic()
            if stop is not None and now - last_tap >= tap_retry_s:
                observed_recording = True
                last_tap = now
                try:
                    ctx.adb.tap(*stop.center)
                except Exception as error:  # delivery may have succeeded despite transport failure
                    last_error = error
        except UnsafeState:
            raise
        except Exception as error:
            last_error = error
        time.sleep(poll_s)
    detail = f"could not prove recording stopped ({last_state})"
    if last_error is not None:
        detail += f"; last transport error: {type(last_error).__name__}: {last_error}"
    raise UnsafeState(detail)


def recording_capture_id(admitted) -> int:
    return int(admitted.group(1).rsplit("_", 1)[1])


def wait_recording_finalized(ctx: Context, mark: str, pid: int, capture_id: int) -> str:
    """Require codec/audio drain, muxer stop, and MediaStore publish before suite continuation."""
    pattern = (
        rf"RecordingFinalized: captureId={capture_id} saved=(true|false) "
        r"error=([A-Za-z0-9_.$-]+)"
    )
    line = ctx.adb.wait_log(mark, pattern, timeout_s=45, pid=pid)
    if line is None:
        raise UnsafeState(f"recording {capture_id} finalization was not observed")
    match = RECORDING_FINALIZED.search(line)
    if match is None or int(match.group(1)) != capture_id:
        raise UnsafeState(f"malformed recording terminal evidence: {line}")
    if match.group(2) != "true" or match.group(3) != "none":
        raise UnsafeState(
            f"recording {capture_id} did not finalize safely: "
            f"saved={match.group(2)} error={match.group(3)}"
        )
    return line


def require_decoded_video(info: dict, *, minimum_seconds: float) -> None:
    if info.get("probe") != "ffprobe":
        raise Incomplete("ffprobe is unavailable; frame decoding was not verified")
    seconds = info.get("video_seconds")
    assert isinstance(seconds, Fraction) and float(seconds) >= minimum_seconds, (
        f"decoded video duration is too short: {seconds!r}"
    )
    frames = info.get("frame_count")
    assert isinstance(frames, int) and frames >= 2, f"decoded too few frames: {frames!r}"


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


def mode_carousel_error(tree, expected: str) -> str | None:
    nodes = {description: tree.find_desc_exact(description) for description in CAPTURE_MODES}
    missing = [description for description, node in nodes.items() if node is None]
    if missing:
        return f"capture-mode controls missing: {missing}"

    present = [node for node in nodes.values() if node is not None]
    wrong_roles = [node.desc for node in present if not node.class_name.endswith("RadioButton")]
    if wrong_roles:
        return f"capture-mode controls are not radio buttons: {wrong_roles}"
    if any(not node.checkable for node in present):
        return "one or more capture-mode controls are not checkable"

    checked = [node.desc for node in present if node.checked]
    if checked != [expected]:
        return f"expected exactly {expected!r} checked, got {checked}"
    return None


def _overlap_area(first, second) -> int:
    left = max(first.bounds[0], second.bounds[0])
    top = max(first.bounds[1], second.bounds[1])
    right = min(first.bounds[2], second.bounds[2])
    bottom = min(first.bounds[3], second.bounds[3])
    return max(0, right - left) * max(0, bottom - top)


def _minimum_touch_px(metrics: DisplayMetrics) -> int:
    return math.ceil(48 * metrics.density_dpi / 160) - 2  # tolerate pixel rounding only


def camera_chrome_layout_errors(tree, metrics: DisplayMetrics) -> list[str]:
    """Pixel-level contract for the fixed Sony/iPhone-familiar shooting controls."""
    errors: list[str] = []

    def one(label: str, predicate) -> UiNode | None:
        matches = [node for node in tree.nodes if predicate(node)]
        if len(matches) != 1:
            errors.append(f"{label}: expected one node, got {len(matches)}")
            return None
        return matches[0]

    top = [
        ("Flash", one("Flash", lambda node: node.desc.startswith("Flash "))),
        ("Self timer", one("Self timer", lambda node: node.desc.startswith("Self timer "))),
        ("Aspect ratio", one("Aspect ratio", lambda node: node.desc.startswith("Aspect ratio "))),
        ("Grid", one("Grid", lambda node: node.desc in ("Grid on", "Grid off"))),
        ("Teleconverter", one("Teleconverter", lambda node: node.desc == "Teleconverter")),
        (
            "Shooting info",
            one(
                "Shooting info",
                lambda node: node.desc in ("Show shooting info", "Hide shooting info"),
            ),
        ),
        ("Open settings", one("Open settings", lambda node: node.desc == "Open settings")),
    ]
    fn = one("Open function menu", lambda node: node.desc == "Open function menu")
    focal = [(label, one(label, lambda node, label=label: node.desc == label)) for label in FOCAL_PRESETS]
    modes = [(label, one(label, lambda node, label=label: node.desc == label)) for label in CAPTURE_MODES]
    shutter = one(
        "Idle shutter",
        lambda node: node.desc in ("Take photo", "Start recording"),
    )
    gallery = one(
        "Gallery",
        lambda node: node.desc == "No capture to review" or node.desc.startswith("Review last "),
    )

    named_nodes = [*top, ("Open function menu", fn), *focal, *modes, ("Shutter", shutter), ("Gallery", gallery)]
    minimum_px = _minimum_touch_px(metrics)
    for label, node in named_nodes:
        if node is None:
            continue
        left, top_px, right, bottom = node.bounds
        if not (0 <= left < right <= metrics.width_px and 0 <= top_px < bottom <= metrics.height_px):
            errors.append(f"{label}: out of screen bounds {node.bounds}")
            continue
        if right - left < minimum_px or bottom - top_px < minimum_px:
            errors.append(
                f"{label}: touch bounds {right - left}x{bottom - top_px}px < {minimum_px}px"
            )

    def assert_row(row_name: str, row) -> None:
        present = [(label, node) for label, node in row if node is not None]
        centers = [node.center[0] for _, node in present]
        if centers != sorted(centers) or len(set(centers)) != len(centers):
            errors.append(f"{row_name}: controls are not strictly left-to-right")
        for index, (first_label, first) in enumerate(present):
            for second_label, second in present[index + 1:]:
                if _overlap_area(first, second) > 0:
                    errors.append(f"{row_name}: {first_label} overlaps {second_label}")

    assert_row("top bar", top)
    assert_row("focal rail", focal)
    assert_row("mode carousel", modes)
    assert_row("shutter row", [("Gallery", gallery), ("Shutter", shutter)])

    def below(upper_name: str, upper, lower_name: str, lower) -> None:
        upper_nodes = [node for _, node in upper if node is not None]
        lower_nodes = [node for _, node in lower if node is not None]
        if upper_nodes and lower_nodes and max(node.bounds[3] for node in upper_nodes) > min(
            node.bounds[1] for node in lower_nodes
        ):
            errors.append(f"{upper_name} is not above {lower_name}")

    below("top bar", top, "Fn row", [("Fn", fn)])
    below("Fn row", [("Fn", fn)], "focal rail", focal)
    below("focal rail", focal, "mode carousel", modes)
    below("mode carousel", modes, "shutter row", [("Gallery", gallery), ("Shutter", shutter)])

    if shutter is not None:
        center_tolerance = math.ceil(8 * metrics.density_dpi / 160)
        if abs(shutter.center[0] - metrics.width_px // 2) > center_tolerance:
            errors.append(
                f"idle shutter is not centered: x={shutter.center[0]}, screen={metrics.width_px}"
            )
    return errors


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
    mode = "VIDEO" if ctx.adb.ui().find(desc="Start recording") else "PHOTO"
    timeout_s = 20 if mode == "PHOTO" else 10
    evidence = wait_mode_three_a(ctx, mark, pid, mode, timeout_s=timeout_s)
    assert evidence, f"no {mode} 3A telemetry within {timeout_s} s — session not configured?"
    ctx.note(evidence.line.split("3A:")[-1].strip()[:90])


@test("camera_chrome_layout", "full")
def t_camera_chrome_layout(ctx: Context) -> None:
    """Core camera controls meet the PMA110 touch-size, ordering, overlap, and centering contract."""
    pid = ensure_foreground(ctx)
    mark = ctx.adb.log_mark()
    tree = ctx.adb.ui()
    if tree.find_desc_exact("Stop recording"):
        raise UnsafeState("layout contract cannot inspect an active recording")
    metrics = ctx.adb.display_metrics()
    errors = camera_chrome_layout_errors(tree, metrics)
    assert not errors, "camera chrome layout violations: " + "; ".join(errors)
    assert ctx.adb.preview_is_live(), "preview froze while inspecting camera chrome"
    ctx.adb.screenshot("camera_chrome_layout")
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"fatal log lines after layout inspection: {fatals[:2]}"
    ctx.note(
        f"{metrics.width_px}x{metrics.height_px}@{metrics.density_dpi}dpi; "
        "16 core controls in-bounds, >=48dp, ordered and non-overlapping"
    )


# ---------------------------------------------------------------- full: modes/lenses/TC

@test("mode_switch_roundtrip", "full", mutates_settings=True)
def t_modes(ctx: Context) -> None:
    """Photo↔Video flips reconfigure the session without camera errors."""
    pid = ensure_foreground(ctx)
    tree = ctx.adb.ui()
    if tree.find(desc="Stop recording"):
        raise UnsafeState("mode round-trip entered while recording was active")
    initial_mode = "VIDEO" if tree.find(desc="Start recording") else "PHOTO"
    assert initial_mode == "VIDEO" or shutter_node(ctx), "could not determine initial capture mode"
    expected_initial_mode = "Video mode" if initial_mode == "VIDEO" else "Photo mode"
    assert mode_carousel_error(tree, expected_initial_mode) is None, (
        mode_carousel_error(tree, expected_initial_mode)
    )
    test_mark = ctx.adb.log_mark()
    baseline = wait_mode_three_a(
        ctx,
        test_mark,
        pid,
        initial_mode,
        timeout_s=20 if initial_mode == "PHOTO" else 10,
    )
    assert baseline, f"initial {initial_mode} request did not publish generation-owned 3A"

    # A persisted Video launch must not silently turn this into a one-way Video→Photo test. First
    # establish a committed Photo baseline, then exercise both advertised transition directions.
    if initial_mode == "VIDEO":
        baseline_mark = ctx.adb.log_mark()
        ensure_photo_mode(ctx)
        baseline_acceptance = wait_session_acceptance(
            ctx,
            baseline_mark,
            pid,
            "PHOTO",
            after_optics_generation=baseline.optics_generation,
        )
        assert baseline_acceptance, "could not establish an accepted Photo baseline"
        baseline = wait_mode_three_a(
            ctx,
            baseline_mark,
            pid,
            "PHOTO",
            after_request_generation=baseline.request_generation,
            controller_id=baseline_acceptance.controller_id,
            optics_generation=baseline_acceptance.optics_generation,
        )
        assert baseline, "accepted Photo baseline did not produce an owned 3A result"

    mark = ctx.adb.log_mark()
    ensure_video_mode(ctx)
    video_tree = ctx.adb.ui()
    assert video_tree.find(desc="Start recording"), "video mode did not arm the REC button"
    assert mode_carousel_error(video_tree, "Video mode") is None, (
        mode_carousel_error(video_tree, "Video mode")
    )
    video_acceptance = wait_session_acceptance(
        ctx,
        mark,
        pid,
        "VIDEO",
        after_optics_generation=baseline.optics_generation,
    )
    assert video_acceptance, "Video route was not committed Ready by the camera engine"
    video_evidence = wait_mode_three_a(
        ctx,
        mark,
        pid,
        "VIDEO",
        after_request_generation=baseline.request_generation,
        controller_id=video_acceptance.controller_id,
        optics_generation=video_acceptance.optics_generation,
    )
    assert video_evidence, "accepted Video route did not produce an owned 3A result"

    recovery_mark = ctx.adb.log_mark()
    ensure_photo_mode(ctx)
    photo_tree = ctx.adb.ui()
    assert shutter_node(ctx), "photo mode did not restore the shutter"
    assert mode_carousel_error(photo_tree, "Photo mode") is None, (
        mode_carousel_error(photo_tree, "Photo mode")
    )
    photo_acceptance = wait_session_acceptance(
        ctx,
        recovery_mark,
        pid,
        "PHOTO",
        after_optics_generation=video_acceptance.optics_generation,
    )
    assert photo_acceptance, "Photo route was not committed Ready after Video"
    recovered = wait_mode_three_a(
        ctx,
        recovery_mark,
        pid,
        "PHOTO",
        after_request_generation=video_evidence.request_generation,
        controller_id=photo_acceptance.controller_id,
        optics_generation=photo_acceptance.optics_generation,
    )
    assert recovered, "accepted Photo route did not resume with its own newer 3A request"
    fatals = ctx.adb.fatal_lines(test_mark, pid)
    assert not fatals, f"errors during mode flips: {fatals[:2]}"
    ctx.note("photo→video→photo clean")


@test("lens_presets", "full", mutates_settings=True)
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


@test("teleconverter_roundtrip", "full", mutates_settings=True)
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

@test("photo_capture_valid_files", "full", mutates_settings=True, writes_media=True)
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


@test("tele_dng_capture", "full", mutates_settings=True, writes_media=True)
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


@test("video_record_validate", "full", mutates_settings=True, writes_media=True)
def t_video(ctx: Context) -> None:
    """A 65 s 29.97p Main10 HLG take decodes fully with healthy PTS and AAC sync."""
    pid = ensure_foreground(ctx)
    ensure_video_mode(ctx)
    preset_tree = ctx.adb.ui()
    preset_error = video_target_precondition_error(preset_tree)
    if preset_error is not None:
        raise Incomplete(f"strict video preset precondition failed: {preset_error}")
    osd = selected_video_osd(preset_tree)
    assert osd is not None
    before = {row.key for row in ctx.adb.media_store_rows()}
    mark = ctx.adb.log_mark()
    recording_may_be_active = True
    admitted = None
    try:
        # The start call itself is inside the cleanup boundary: a transport exception can arrive
        # after the tap reached the phone, so even a raised call may have started REC.
        ctx.adb.tap_ui(desc="Start recording")
        admitted_line = ctx.adb.wait_log(mark, RECORDING_SPEC.pattern, timeout_s=12, pid=pid)
        assert admitted_line, "recording did not publish its admitted encoder spec"
        admitted = RECORDING_SPEC.search(admitted_line)
        assert admitted is not None, f"malformed admitted encoder spec: {admitted_line}"
        assert admitted.group(2) == "HEVC", f"OSD/engine codec mismatch: {admitted_line}"
        source_width, source_height = int(admitted.group(3)), int(admitted.group(4))
        encoder_width, encoder_height = int(admitted.group(5)), int(admitted.group(6))
        assert video_resolution_label(source_width, source_height) == osd.group("resolution"), (
            f"OSD/engine resolution mismatch: OSD={osd.group('resolution')}, "
            f"source={source_width}x{source_height}"
        )
        assert sorted((source_width, source_height)) == sorted((encoder_width, encoder_height)), (
            f"source/encoder raster mismatch: {admitted_line}"
        )
        admitted_bitrate = int(admitted.group(7))
        assert admitted_bitrate // 1_000_000 == int(osd.group("mbps")), (
            f"OSD/engine bitrate mismatch: OSD={osd.group('mbps')}Mb, "
            f"engine={admitted_bitrate}"
        )
        assert admitted.group(9) == "HLG", f"OSD/engine transfer mismatch: {admitted_line}"
        assert abs(Fraction(admitted.group(8)) - Fraction(30_000, 1_001)) < Fraction(1, 1_000_000), (
            f"engine did not admit true 30000/1001 fps: {admitted.group(8)}"
        )
        if admitted.group(10) != "true":
            raise Incomplete("strict video preset requires recording audio to be enabled")

        # A long take distinguishes 29.97 from 30 and exercises sustained encoder/muxer ownership.
        for checkpoint in range(1, 7):
            time.sleep(10)
            assert ctx.adb.pid() == pid, f"app process changed during REC at {checkpoint * 10}s"
            assert ctx.adb.ui().find(desc="Stop recording"), (
                f"REC UI was lost at {checkpoint * 10}s"
            )
            if checkpoint in (2, 5):
                assert ctx.adb.preview_is_live(), f"preview stalled during REC at {checkpoint * 10}s"
        time.sleep(LONG_VIDEO_SECONDS - 60)
        stop_recording_verified(ctx, pid)
        wait_recording_finalized(ctx, mark, pid, recording_capture_id(admitted))
        recording_may_be_active = False
    finally:
        if recording_may_be_active:
            observed_recording = stop_recording_verified(ctx, pid)
            if admitted is None:
                late_line = ctx.adb.wait_log(mark, RECORDING_SPEC.pattern, timeout_s=3, pid=pid)
                admitted = RECORDING_SPEC.search(late_line) if late_line else None
            if admitted is None:
                # Even if the UI is idle, a returned/failed transport call may have admitted and
                # asynchronously stopped a recorder. Without its identity+terminal evidence, later
                # cases cannot safely touch modes, lenses, or lifecycle.
                detail = "REC UI was observed" if observed_recording else "REC start was attempted"
                raise UnsafeState(f"{detail}, but admission/finalization identity is unavailable")
            wait_recording_finalized(ctx, mark, pid, recording_capture_id(admitted))

    assert admitted is not None
    new = ctx.adb.wait_new_media_rows(before, min_new=1, timeout_s=45)
    vids = [row for row in new if row.collection == "video" and row.mime_type == "video/mp4"]
    assert vids, f"no new clip after REC (new={new})"
    assert len(vids) == 1 and len(new) == 1, f"REC published an unexpected row family: {new}"
    row = vids[0]
    assert_published_row(row)
    assert row.display_name == f"{admitted.group(1)}.mp4", (
        f"recording spec/MediaStore family mismatch: spec={admitted.group(1)}, row={row}"
    )
    local = ctx.adb.pull(
        f"{MEDIA_DIR}/{row.display_name}",
        ctx.evidence / row.display_name,
    )
    info = media.mp4_probe(local)
    ctx.note(f"{row.display_name}: {info}")
    if info.get("probe") != "ffprobe":
        raise Incomplete("ffprobe is unavailable; 29.97 HLG frame decoding was not verified")
    expected_width, expected_height = int(admitted.group(5)), int(admitted.group(6))
    violations = media.hlg_2997_errors(
        info,
        expected_width=expected_width,
        expected_height=expected_height,
        expected_audio=True,
    )
    assert not violations, "strict 29.97 HLG violations: " + "; ".join(violations)
    assert (row.width, row.height) == (expected_width, expected_height), (
        f"MediaStore/encoder dimensions differ: row={row}, spec={admitted_line}"
    )
    local_size = local.stat().st_size
    assert row.size_bytes == local_size == info.get("format_size"), (
        f"MediaStore/file/container sizes differ: row={row.size_bytes}, "
        f"file={local_size}, format={info.get('format_size')}"
    )
    assert row.duration_ms is not None
    assert abs(Fraction(row.duration_ms, 1_000) - info["video_seconds"]) <= Fraction(1, 2), (
        f"MediaStore/video duration differs: row={row.duration_ms}ms, "
        f"video={float(info['video_seconds']):.3f}s"
    )
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during REC: {fatals[:2]}"
    ensure_photo_mode(ctx)


# ---------------------------------------------------------------- full: AF + settings

@test("tap_af_hold_visible_and_reset", "full", mutates_settings=True)
def t_tap_af(ctx: Context) -> None:
    """Tap-AF stays visibly held after reticle fade, then its direct reset restores prior AF."""
    pid = ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    baseline_mark = ctx.adb.log_mark()
    baseline = wait_mode_three_a(ctx, baseline_mark, pid, "PHOTO", timeout_s=20)
    assert baseline, "no baseline Photo 3A before tap-AF"
    baseline_af = AF_MODE.search(baseline.line)
    assert baseline_af, f"baseline 3A omitted afMode: {baseline.line}"
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
    reset = ctx.adb.ui().find_desc_exact("Reset focus point")
    assert reset and reset.enabled, "tap-AF became an invisible hold after the reticle faded"
    reset_mark = ctx.adb.log_mark()
    ctx.adb.tap(*reset.center)
    assert ctx.adb.wait_log(reset_mark, r"TapFocus: cleared", timeout_s=5, pid=pid), (
        "reset affordance did not clear the engine tap point"
    )
    restored = ctx.adb.wait_log(
        reset_mark,
        rf"3A: .*afMode={baseline_af.group(1)}\b",
        timeout_s=8,
        pid=pid,
    )
    assert restored, f"reset did not restore prior afMode={baseline_af.group(1)}"
    assert ctx.adb.ui().find_desc_exact("Reset focus point") is None, (
        "tap-focus reset affordance remained after the point was cleared"
    )
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during tap-AF hold/reset: {fatals[:2]}"
    ctx.note(f"tap-AF held visibly, reset to afMode={baseline_af.group(1)}")


@test("settings_sheet_tabs", "full")
def t_settings(ctx: Context) -> None:
    """Every settings tab selects its page; modal semantics and Back behavior stay isolated."""
    pid = ensure_foreground(ctx)
    mark = ctx.adb.log_mark()
    metrics = ctx.adb.display_metrics()
    ctx.adb.tap_ui(desc="Open settings")
    opened = ctx.adb.ui()
    assert opened.find_desc_exact("Close settings"), "settings modal did not open"
    minimum_px = _minimum_touch_px(metrics)
    try:
        for tab, title in zip(SETTINGS_TABS, SETTINGS_TITLES, strict=True):
            tree = ctx.adb.ui()
            tab_nodes = {
                label: [
                    node for node in tree.nodes
                    if node.text.casefold() == label.casefold() and node.clickable
                ]
                for label in SETTINGS_TABS
            }
            invalid = {label: len(nodes) for label, nodes in tab_nodes.items() if len(nodes) != 1}
            assert not invalid, f"settings tab nodes are missing or duplicated: {invalid}"
            target = tab_nodes[tab][0]
            left, top, right, bottom = target.bounds
            assert target.enabled, f"settings tab {tab} is disabled"
            assert 0 <= left < right <= metrics.width_px and 0 <= top < bottom <= metrics.height_px, (
                f"settings tab {tab} is offscreen: {target.bounds}"
            )
            assert right - left >= minimum_px and bottom - top >= minimum_px, (
                f"settings tab {tab} touch bounds are {right - left}x{bottom - top}px; "
                f"minimum is {minimum_px}px"
            )

            if not target.selected:
                ctx.adb.tap(*target.center)
                deadline = time.time() + 5
                while time.time() < deadline:
                    tree = ctx.adb.ui()
                    selected = [
                        label for label in SETTINGS_TABS
                        if any(
                            node.text.casefold() == label.casefold()
                            and node.clickable
                            and node.selected
                            for node in tree.nodes
                        )
                    ]
                    if selected == [tab]:
                        break
                    time.sleep(0.3)
                assert selected == [tab], f"settings tab {tab} did not become the sole selection: {selected}"
            else:
                selected = [
                    label for label in SETTINGS_TABS
                    if tab_nodes[label][0].selected
                ]
                assert selected == [tab], f"settings selected state is ambiguous: {selected}"

            rail_right = max(tab_nodes[label][0].bounds[2] for label in SETTINGS_TABS)
            page_titles = [
                node for node in tree.nodes
                if node.text.casefold() == title.casefold()
                and not node.clickable
                and node.bounds[0] >= rail_right
            ]
            assert page_titles, f"settings tab {tab} did not show page title {title!r}"
            ctx.adb.screenshot(f"settings_{tab.lower()}")

        modal = ctx.adb.ui()
        background_descriptions = {
            *FOCAL_PRESETS,
            *CAPTURE_MODES,
            "Take photo",
            "Start recording",
            "Open function menu",
            "Open settings",
        }
        leaked = sorted(node.desc for node in modal.nodes if node.desc in background_descriptions)
        assert not leaked, f"settings modal leaked background camera actions: {leaked}"
    finally:
        ctx.adb.shell("input keyevent KEYCODE_BACK")
        time.sleep(1)

    restored = ctx.adb.ui()
    assert restored.find(desc="Open settings"), "Back did not restore camera chrome"
    assert restored.find_desc_exact("Close settings") is None, "Back left the settings modal open"
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during settings traversal: {fatals[:2]}"
    ctx.note("all 9 tabs selected their page; modal isolated; Back restored camera")


@test("function_menu_roundtrip", "full")
def t_function_menu(ctx: Context) -> None:
    """The visible Sony-style Fn entry opens actionable tiles and Back restores camera chrome."""
    pid = ensure_foreground(ctx)
    opener = ctx.adb.ui().find_desc_exact("Open function menu")
    assert opener and opener.enabled, "shooting screen has no visible, enabled Fn entry point"
    mark = ctx.adb.log_mark()
    ctx.adb.tap(*opener.center)
    menu = ctx.adb.ui()
    assert menu.find_desc_exact("Close function menu"), "Fn overlay did not expose its close control"
    tiles = [node for node in menu.nodes if node.desc in FN_TILE_LABELS and node.enabled]
    assert tiles, "Fn overlay exposed no enabled setting tile"
    ctx.adb.screenshot("function_menu")
    ctx.adb.shell("input keyevent KEYCODE_BACK")
    time.sleep(1)
    restored = ctx.adb.ui()
    assert restored.find_desc_exact("Open function menu"), "Back did not restore the Fn entry"
    assert restored.find_desc_exact("Close function menu") is None, "Back left the Fn overlay open"
    assert restored.find(desc="Open settings"), "camera chrome was not restored after Fn dismiss"
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during Fn open/dismiss: {fatals[:2]}"
    ctx.note(f"Fn opened with {len(tiles)} enabled tiles and Back restored camera")


@test("mode_persists_across_kill", "full", destructive=True, mutates_settings=True)
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

@test(
    "capture_then_kill_survives",
    "reliability",
    destructive=True,
    mutates_settings=True,
    writes_media=True,
)
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


@test(
    "rec_backgrounded_finalizes",
    "reliability",
    destructive=True,
    mutates_settings=True,
    writes_media=True,
)
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
    require_decoded_video(info, minimum_seconds=1.0)
    ctx.adb.launch()
    ensure_photo_mode(ctx)
    ctx.note(f"clip finalized: {row.display_name} {info.get('video_seconds', '?')}s")


@test(
    "rec_stop_then_kill_published",
    "reliability",
    destructive=True,
    mutates_settings=True,
    writes_media=True,
)
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
    require_decoded_video(info, minimum_seconds=1.0)
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
