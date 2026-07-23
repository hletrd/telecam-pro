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
import struct
import time
import math
from dataclasses import dataclass
from fractions import Fraction

from dtest.adb import (
    APP_ID,
    MAIN_ACTIVITY,
    MEDIA_DIR,
    MEDIA_RELATIVE_PATH,
    Adb,
    AdbError,
    DisplayMetrics,
    MediaRow,
    UiNode,
)
from dtest.framework import Context, Incomplete, Skip, UnsafeState, test
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
FN_NUMERIC_TILE_LABELS = {"Focus", "Shutter", "ISO", "WB", "EV", "Zoom"}
FN_DEFAULT_PHYSICAL_ORDER = (
    "AE", "Focus", "Shutter", "ISO", "WB", "Gamma", "Stabilization", "Audio",
)
FN_HELD_MAX_DEPTH_FRACTION = 0.40
SNAPSHOT_ACTIVITY = f"{APP_ID}/com.hletrd.findx9tele.ui.UiSnapshotActivity"
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
    # transfer= carries ColorTransfer.name: the O-Log2 "LOG" option was replaced by the
    # standard log profiles (2026-07-22); a stale alternation here made every recording
    # case time out with a misleading "no admitted spec" whenever a log profile persisted.
    r"fps=([0-9]+\.[0-9]+) transfer=(HLG|SLOG3|SLOG3_CINE|LOGC3|SDR) audio=(true|false)"
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
# The 3A line's trailing route facts: "(req=N tele=true|false effZoom=4.285714)".
TELE_STATE = re.compile(r"\btele=(true|false) effZoom=([0-9]+(?:\.[0-9]+)?)\)")
# The afocal converter turns the 70 mm tele into ~300 mm: 300/70 ≈ 4.2857 is the TELE
# effective-zoom floor (lens-local digital zoom can only raise it above that).
TELE_MIN_EFFECTIVE_ZOOM = 300 / 70 - 0.01
SESSION_ACCEPTED = re.compile(
    r"CameraSessionAccepted: controllerId=([0-9]+) opticsGeneration=([0-9]+) "
    r"sessionGeneration=([0-9]+) requestGeneration=([0-9]+) "
    r"mode=(PHOTO|VIDEO) cameraId=(\S+) ready=(true|false)"
)
LONG_VIDEO_SECONDS = 65
REC_SOAK_CYCLES = 5
REC_SOAK_SECONDS = 4
REC_SOAK_MAX_MBPS = 250
REC_SNAPSHOT_STORAGE_SECONDS = 90
REC_STORAGE_RESERVE_BYTES = 1024 ** 3
REC_SNAPSHOT_EXTRA_BYTES = 512 * 1024 ** 2
REC_STORAGE_VBR_MULTIPLIER = 2
REC_SNAPSHOT_PROMPT_SECONDS = 4.0
REC_SNAPSHOT_TERMINAL_SETTLE_SECONDS = 3.0
# One still-owned encoder interruption budget: the 500 ms preview-safe still exposure plus
# capture handoff overhead (device-measured 0.689 s + one 0.067 s recovery interval in a dark
# scene, 2026-07-23). Pathological stalls are still failures.
REC_SNAPSHOT_MAX_STILL_GAP_SECONDS = Fraction(3, 2)
REC_SNAPSHOT_MAX_STILL_GAP_INTERVALS = 3


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
    # The engine's accepted Camera2 id — the route-truth join key for the data-validity cases
    # (geometry expectations come from dumpsys per-id arrays, never from a hardcoded id).
    camera_id: str = "?"


@dataclass(frozen=True)
class SettledStillFamily:
    stem: str
    extensions: tuple[str, ...]

    @property
    def capture_id(self) -> int:
        return int(self.stem.rsplit("_", 1)[1])


@dataclass(frozen=True)
class StillTerminalEvidence:
    started_count: int
    families: tuple[SettledStillFamily, ...]
    first_started_after_s: float | None


@dataclass(frozen=True)
class PhotoSettingMarkers:
    timer_option: str
    drive_option: str


# ---------------------------------------------------------------- helpers

def ensure_foreground(ctx: Context) -> int:
    pid = ctx.adb.pid()
    if pid is None or ctx.adb.resumed_activity() != MAIN_ACTIVITY:
        if not ctx.can_launch:
            raise Skip("app is not already foreground; read-only case does not launch it")
        pid = ctx.adb.launch()
    ctx.adb.shell("input keyevent KEYCODE_WAKEUP")
    tree = ctx.adb.ui()
    if not (tree.find(desc="Open settings") or tree.find(desc="Photo mode")):
        if not ctx.can_launch:
            raise Skip("app is not already foreground; read-only case does not launch it")
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


def launch_ui_snapshot(
    ctx: Context,
    *,
    orientation: int,
    scenario: str = "default",
    rtl: bool = False,
):
    """Open the debug-only, HAL-free production-composable snapshot surface."""
    # Defense-in-depth parity with Adb.launch: this raw `am start` bypasses that guard, so the
    # helper itself must refuse unless the calling case declared + received destructive approval.
    if not ctx.can_launch:
        raise AdbError(
            "refusing snapshot activity launch without explicit destructive approval"
        )
    ctx.adb.shell(
        f"am start -W --activity-reorder-to-front -n {SNAPSHOT_ACTIVITY} "
        f"--ei device_orientation {orientation} "
        f"--ez snapshot_rtl {str(rtl).lower()} "
        f"--es snapshot_scenario {scenario}"
    )
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        tree = ctx.adb.ui()
        if tree.find_desc_exact("Open function menu") is not None:
            return tree
        time.sleep(0.25)
    raise AssertionError(
        f"snapshot scenario {scenario!r} orientation={orientation} did not expose camera chrome"
    )


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
        # ready= on the acceptance line is TIMING, not health: per the engine contract, Ready
        # commits only after the route's first successful real-camera-frame swap, so a cold-opened
        # route (photo→video crosses to another camera id) legitimately logs ready=false here and
        # flips Ready afterwards with no second acceptance line (device-observed both values for
        # the same VIDEO route, 2026-07-22). Acceptance is the evidence this parser owns; LIVENESS
        # is proven by the generation-owned 3A wait every caller performs immediately after.
        if match is None or match.group(5) != mode:
            continue
        evidence = SessionAcceptance(
            line=line,
            controller_id=int(match.group(1)),
            optics_generation=int(match.group(2)),
            session_generation=int(match.group(3)),
            request_generation=int(match.group(4)),
            camera_id=match.group(6),
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


def three_a_tele_state(evidence: ModeThreeAEvidence) -> tuple[bool, float]:
    """Parse the authoritative tele=/effZoom= route facts out of an owned 3A telemetry line."""
    match = TELE_STATE.search(evidence.line)
    assert match, f"3A telemetry omitted tele/effZoom state: {evidence.line}"
    return match.group(1) == "true", float(match.group(2))


def toggle_teleconverter(
    ctx: Context,
    pid: int,
    after: ModeThreeAEvidence,
) -> ModeThreeAEvidence:
    """Toggle TC and wait for the reopened route's owned acceptance + 3A, never a bare sleep.

    The compact OSD deliberately hides the focal/TELE tag (Sony-style, non-default state only),
    so OSD text is NOT a valid TC-state signal at app baseline; the 3A telemetry's tele=/effZoom=
    facts are the mode-independent evidence (device-root-caused 2026-07-23).
    """
    mark = ctx.adb.log_mark()
    ctx.adb.tap_ui(desc="Teleconverter")
    acceptance = wait_session_acceptance(
        ctx,
        mark,
        pid,
        "PHOTO",
        after_optics_generation=after.optics_generation,
        timeout_s=20,
    )
    assert acceptance, "TC toggle's reopened route was never accepted by the camera engine"
    evidence = wait_mode_three_a(
        ctx,
        mark,
        pid,
        "PHOTO",
        after_request_generation=after.request_generation,
        controller_id=acceptance.controller_id,
        optics_generation=acceptance.optics_generation,
        timeout_s=20,
    )
    assert evidence, "accepted TC route did not produce an owned 3A result"
    return evidence


def restore_teleconverter_off_verified(
    ctx: Context,
    pid: int,
    after: ModeThreeAEvidence,
) -> None:
    """Leave TELE during cleanup; an unproven TC state must abort the suite, not linger."""
    evidence = cleanup_transport_or_unsafe(
        "could not restore the teleconverter to its off state",
        lambda: toggle_teleconverter(ctx, pid, after),
    )
    tele, _ = three_a_tele_state(evidence)
    if tele:
        raise UnsafeState("teleconverter restore left TELE engaged")


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


PHOTO_DRIVE_OPTIONS = {"Single", "Burst", "AEB", "Timelapse"}
PHOTO_TIMER_OPTIONS = {"Off", "3s", "10s"}


def _bounds_contain(
    outer: tuple[int, int, int, int],
    inner: tuple[int, int, int, int],
) -> bool:
    return (
        outer[0] <= inner[0]
        and outer[1] <= inner[1]
        and inner[2] <= outer[2]
        and inner[3] <= outer[3]
    )


def selected_option_labels(tree, labels: set[str]) -> set[str]:
    """Return option labels whose own node OR enclosing checkable chip is checked.

    The settings chips publish checked= on the checkable chip CONTAINER while the visible
    label is a separate non-checkable text descendant (device-dumped 2026-07-23), so a flat
    text-node state read can never see a selection. The flattened UI tree has no ancestry,
    so containment stands in for it: only checkable+checked regions participate, which keeps
    the selected (non-checkable) tab-rail node from leaking selection onto page content.
    """
    checked_regions = [
        node.bounds for node in tree.nodes if node.checkable and node.checked
    ]
    selected = set()
    for node in tree.nodes:
        if node.text not in labels:
            continue
        if (
            node.selected
            or node.checked
            or any(_bounds_contain(region, node.bounds) for region in checked_regions)
        ):
            selected.add(node.text)
    return selected


def selected_photo_setting_options(tree) -> tuple[set[str], set[str]]:
    selected = selected_option_labels(tree, PHOTO_DRIVE_OPTIONS | PHOTO_TIMER_OPTIONS)
    return selected & PHOTO_DRIVE_OPTIONS, selected & PHOTO_TIMER_OPTIONS


def _settings_option(ctx: Context, label: str, *, max_scrolls: int = 12) -> None:
    """Select one exact Shooting-sheet chip, scrolling only inside the settings content pane."""
    metrics = ctx.adb.display_metrics()
    x = metrics.width_px * 4 // 5
    top = metrics.height_px // 3
    bottom = metrics.height_px * 4 // 5
    for _ in range(max_scrolls):
        tree = ctx.adb.ui()
        candidates = [
            node for node in tree.nodes
            if node.text.casefold() == label.casefold() and node.enabled
            and 0 <= node.center[0] < metrics.width_px
            and 0 <= node.center[1] < metrics.height_px
        ]
        if candidates:
            target = candidates[-1]
            ctx.adb.tap(*target.center)
            deadline = time.monotonic() + 4
            while time.monotonic() < deadline:
                # Selection state lives on the checkable chip container, not the tapped
                # text node — read it the same way selected_photo_setting_options does.
                # Verify against the node's exact text (candidates matched casefolded).
                if target.text in selected_option_labels(ctx.adb.ui(), {target.text}):
                    return
                time.sleep(0.25)
            raise AssertionError(f"settings option {label!r} did not become selected")
        ctx.adb.shell(f"input swipe {x} {bottom} {x} {top} 250")
        time.sleep(0.35)
    raise AssertionError(f"settings option {label!r} was not reachable")


def _close_settings_with_back(ctx: Context) -> None:
    """Dismiss settings without requiring a fake full-screen accessibility action."""
    tree = ctx.adb.ui()
    close_nodes = [node for node in tree.nodes if node.desc == "Close settings"]
    if len(close_nodes) != 1:
        raise AssertionError(f"settings sheet exposed {len(close_nodes)} close targets; expected one")
    ctx.adb.shell("input keyevent KEYCODE_BACK")
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        tree = ctx.adb.ui()
        if tree.find(desc="Open settings") and tree.find_desc_exact("Close settings") is None:
            time.sleep(0.8)  # allow the persisted-settings debounce to flush
            return
        time.sleep(0.25)
    raise AssertionError("settings sheet did not close back to the camera")


def set_photo_settings(ctx: Context, *, drive: str, timer: str) -> None:
    """Set exact Photo drive/timer chips through the idempotent settings UI."""
    if ctx.adb.ui().find_desc_exact("Close settings") is not None:
        _close_settings_with_back(ctx)
    ensure_photo_mode(ctx)
    ctx.adb.tap_ui(desc="Open settings")
    try:
        # The tab rail exposes labels ONLY via content-desc (text is always empty on device);
        # a text= query can never match it — see settings_tab_nodes, which matches desc too.
        ctx.adb.tap_ui(desc="Shoot")
        metrics = ctx.adb.display_metrics()
        x = metrics.width_px * 4 // 5
        top = metrics.height_px // 3
        bottom = metrics.height_px * 4 // 5
        # A prior sheet visit may restore this tab's scroll position. Rewind deterministically.
        for _ in range(8):
            ctx.adb.shell(f"input swipe {x} {top} {x} {bottom} 200")
            time.sleep(0.1)
        _settings_option(ctx, drive)
        _settings_option(ctx, timer)
    finally:
        if ctx.adb.ui().find_desc_exact("Close settings") is not None:
            _close_settings_with_back(ctx)


def read_photo_settings(ctx: Context) -> PhotoSettingMarkers:
    """Read exact selected Shooting-sheet chips; OSD absence must never be inferred as Single."""
    if ctx.adb.ui().find_desc_exact("Close settings") is not None:
        _close_settings_with_back(ctx)
    ensure_photo_mode(ctx)
    ctx.adb.tap_ui(desc="Open settings")
    try:
        # Same device fact as set_photo_settings: the tab rail is content-desc-only.
        ctx.adb.tap_ui(desc="Shoot")
        metrics = ctx.adb.display_metrics()
        x = metrics.width_px * 4 // 5
        top = metrics.height_px // 3
        bottom = metrics.height_px * 4 // 5
        for _ in range(8):
            ctx.adb.shell(f"input swipe {x} {top} {x} {bottom} 200")
            time.sleep(0.1)

        drives: set[str] = set()
        timers: set[str] = set()
        for _ in range(12):
            selected_drives, selected_timers = selected_photo_setting_options(ctx.adb.ui())
            drives.update(selected_drives)
            timers.update(selected_timers)
            if len(drives) > 1 or len(timers) > 1:
                raise AssertionError(
                    f"ambiguous selected Photo settings: drives={drives}, timers={timers}"
                )
            if len(drives) == 1 and len(timers) == 1:
                return PhotoSettingMarkers(next(iter(timers)), next(iter(drives)))
            ctx.adb.shell(f"input swipe {x} {bottom} {x} {top} 250")
            time.sleep(0.35)
        raise AssertionError(
            f"could not read selected Photo settings: drives={drives}, timers={timers}"
        )
    finally:
        if ctx.adb.ui().find_desc_exact("Close settings") is not None:
            _close_settings_with_back(ctx)


def restore_photo_settings(ctx: Context, expected: PhotoSettingMarkers) -> None:
    set_photo_settings(ctx, drive=expected.drive_option, timer=expected.timer_option)
    actual = read_photo_settings(ctx)
    if actual != expected:
        raise UnsafeState(f"Photo timer/drive restore mismatch: expected={expected}, actual={actual}")


def restore_photo_settings_verified(ctx: Context, expected: PhotoSettingMarkers) -> None:
    try:
        restore_photo_settings(ctx, expected)
    except UnsafeState:
        raise
    except Exception as error:
        raise UnsafeState(f"could not restore Photo timer/drive: {error}") from error


def expected_image_mime(display_name: str) -> str | None:
    extension = display_name.rsplit(".", 1)[-1].casefold()
    return {
        "heic": "image/heic",
        "heif": "image/heic",
        "jpg": "image/jpeg",
        "jpeg": "image/jpeg",
        "dng": "image/x-adobe-dng",
    }.get(extension)


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


def required_recording_free_bytes(
    bitrate_mbps: int,
    seconds: int,
    *,
    extra_bytes: int = 0,
) -> int:
    """Reserve 2× payload for VBR/containers plus 1 GiB that the suite may never consume."""
    assert bitrate_mbps > 0 and seconds > 0 and extra_bytes >= 0
    payload_bytes = math.ceil(bitrate_mbps * 1_000_000 * seconds / 8)
    return (
        payload_bytes * REC_STORAGE_VBR_MULTIPLIER
        + extra_bytes
        + REC_STORAGE_RESERVE_BYTES
    )


def require_recording_storage(
    ctx: Context,
    bitrate_mbps: int,
    seconds: int,
    *,
    label: str,
    extra_bytes: int = 0,
) -> None:
    required = required_recording_free_bytes(
        bitrate_mbps,
        seconds,
        extra_bytes=extra_bytes,
    )
    available = ctx.adb.free_bytes()
    if available < required:
        raise Incomplete(
            f"{label} requires {required / 1024 ** 3:.2f} GiB free, "
            f"but shared storage has {available / 1024 ** 3:.2f} GiB"
        )
    ctx.note(
        f"storage preflight: {available / 1024 ** 3:.2f} GiB free, "
        f"{required / 1024 ** 3:.2f} GiB required"
    )


def recording_admission_errors(admitted, osd) -> list[str]:
    """Cross-check the recorder's exact admitted packet against the visible persisted preset."""
    errors: list[str] = []
    expected_codec = {"HEVC": "HEVC", "H.264": "AVC", "APV": "APV"}[osd.group("codec")]
    if admitted.group(2) != expected_codec:
        errors.append(f"codec={admitted.group(2)}, OSD={osd.group('codec')}")

    source_width, source_height = int(admitted.group(3)), int(admitted.group(4))
    encoder_width, encoder_height = int(admitted.group(5)), int(admitted.group(6))
    admitted_resolution = video_resolution_label(source_width, source_height)
    if admitted_resolution != osd.group("resolution"):
        errors.append(f"source={admitted_resolution}, OSD={osd.group('resolution')}")
    if sorted((source_width, source_height)) != sorted((encoder_width, encoder_height)):
        errors.append(
            f"source={source_width}x{source_height}, "
            f"encoder={encoder_width}x{encoder_height} changes the admitted raster"
        )

    admitted_fps = Fraction(admitted.group(8))
    osd_fps = Fraction(osd.group("fps"))
    if abs(admitted_fps - osd_fps) > Fraction(1, 1_000):
        errors.append(f"fps={float(admitted_fps):.6f}, OSD={float(osd_fps):.3f}")

    admitted_bitrate = int(admitted.group(7))
    osd_mbps = int(osd.group("mbps"))
    # The app displays integer Mbps by truncating the exact encoder target.
    if admitted_bitrate // 1_000_000 != osd_mbps:
        errors.append(f"bitrate={admitted_bitrate}, OSD={osd_mbps}Mb/s")
    if admitted_bitrate > REC_SOAK_MAX_MBPS * 1_000_000:
        errors.append(
            f"bitrate={admitted_bitrate} exceeds {REC_SOAK_MAX_MBPS}Mb/s safety cap"
        )
    return errors


def wait_recording_running(ctx: Context, pid: int, *, timeout_s: float = 12.0) -> None:
    """Wait past optimistic admission until the first real encoder frame exposes REC semantics."""
    deadline = time.monotonic() + timeout_s
    last_state = "not checked"
    while time.monotonic() < deadline:
        if ctx.adb.pid() != pid:
            raise UnsafeState(f"app process changed before REC became ready: expected {pid}")
        tree = ctx.adb.ui()
        stop = tree.find_desc_exact("Stop recording")
        running = tree.find_desc_exact("Recording")
        last_state = f"stop={stop is not None}, recording={running is not None}"
        if stop is not None and running is not None:
            return
        time.sleep(0.25)
    raise AssertionError(f"REC never reached first-frame running state ({last_state})")


def assert_recording_continues(ctx: Context, pid: int, seconds: float) -> None:
    """Require uninterrupted REC semantics for the requested wall-clock interval."""
    started = time.monotonic()
    deadline = started + seconds
    preview_checked = False
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(0.75, remaining))
        assert ctx.adb.pid() == pid, "app process changed during REC soak interval"
        tree = ctx.adb.ui()
        assert tree.find_desc_exact("Stop recording"), "REC stopped before the requested interval"
        assert tree.find_desc_exact("Recording"), "first-frame REC semantics disappeared early"
        if not preview_checked and time.monotonic() - started >= seconds / 2:
            assert ctx.adb.preview_is_live(), "preview froze during REC soak interval"
            preview_checked = True
    final_tree = ctx.adb.ui()
    assert final_tree.find_desc_exact("Stop recording"), "REC was not active at the stop boundary"
    assert final_tree.find_desc_exact("Recording"), "REC tally vanished before the stop boundary"
    assert preview_checked, "REC interval ended before preview liveness could be verified"


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


def cleanup_transport_or_unsafe(label: str, action):
    """Turn any failed cleanup proof into a suite-stopping unsafe state."""
    try:
        return action()
    except UnsafeState:
        raise
    except Exception as error:
        raise UnsafeState(f"{label}: {type(error).__name__}: {error}") from error


def recover_recording_admission(ctx: Context, mark: str, pid: int, label: str):
    """Recover an admission during cleanup without allowing transport loss to continue the suite."""
    line = cleanup_transport_or_unsafe(
        f"{label} admission recovery transport failed",
        lambda: ctx.adb.wait_log(mark, RECORDING_SPEC.pattern, timeout_s=3, pid=pid),
    )
    return RECORDING_SPEC.search(line) if line else None


def wait_recording_finalized(ctx: Context, mark: str, pid: int, capture_id: int) -> str:
    """Require codec/audio drain, muxer stop, and MediaStore publish before suite continuation."""
    pattern = (
        rf"RecordingFinalized: captureId={capture_id} saved=(true|false) "
        r"error=([A-Za-z0-9_.$-]+)"
    )
    line = cleanup_transport_or_unsafe(
        f"recording {capture_id} finalization transport failed",
        lambda: ctx.adb.wait_log(mark, pattern, timeout_s=45, pid=pid),
    )
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


def _still_terminal_snapshot(log: str) -> tuple[int, tuple[SettledStillFamily, ...]]:
    """Parse every post-mark shutter start and unique terminal family from one logcat snapshot."""
    started_count = sum("ShutterLag: started" in line for line in log.splitlines())
    families: dict[str, tuple[str, ...]] = {}
    malformed = []
    for line in log.splitlines():
        if "CaptureFamily: settled" not in line:
            continue
        match = CAPTURE_SETTLED.search(line)
        if match is None:
            malformed.append(line)
            continue
        extensions = tuple(match.group(2).split(","))
        prior = families.setdefault(match.group(1), extensions)
        if prior != extensions:
            malformed.append(line)
    if malformed:
        raise UnsafeState(f"malformed or contradictory still terminal evidence: {malformed[:2]}")
    settled = tuple(
        SettledStillFamily(stem, extensions)
        for stem, extensions in sorted(families.items())
    )
    return started_count, settled


def wait_still_capture_terminals(
    ctx: Context,
    mark: str,
    pid: int,
    *,
    timeout_s: float = 60.0,
    settle_s: float = REC_SNAPSHOT_TERMINAL_SETTLE_SECONDS,
    poll_s: float = 0.5,
    action_started_at: float | None = None,
) -> StillTerminalEvidence:
    """Prove every observed snapshot request reached a stable CaptureFamily terminal.

    ADB can reconnect and replay ``input tap`` after the first delivery reached Android. Waiting for
    only the first family is therefore unsafe: a replayed press (or a broken multi-drive path) may
    still own Camera2/save resources. Require terminal families to account for every observed sensor
    start, then keep the complete terminal set stable past the reconnect replay window.
    """
    began = action_started_at if action_started_at is not None else time.monotonic()
    deadline = time.monotonic() + timeout_s
    stable_since: float | None = None
    last_fingerprint: tuple | None = None
    first_started_after_s: float | None = None
    last_state = "no shutter or terminal evidence"
    while time.monotonic() < deadline:
        log = cleanup_transport_or_unsafe(
            "mid-REC still terminal transport failed",
            lambda: ctx.adb.logcat_since(mark, pid),
        )
        started_count, families = _still_terminal_snapshot(log)
        now = time.monotonic()
        if started_count and first_started_after_s is None:
            first_started_after_s = now - began
        last_state = f"starts={started_count}, settled={[family.stem for family in families]}"
        fingerprint = (started_count, families)
        terminals_account_for_starts = bool(families) and len(families) >= started_count
        if terminals_account_for_starts and fingerprint == last_fingerprint:
            stable_since = stable_since or now
            if now - stable_since >= settle_s:
                return StillTerminalEvidence(started_count, families, first_started_after_s)
        else:
            stable_since = None
        last_fingerprint = fingerprint
        time.sleep(poll_s)
    raise UnsafeState(f"mid-REC still terminal state was not proven ({last_state})")


def require_decoded_video(
    info: dict,
    *,
    minimum_seconds: float,
    expected_fps: Fraction | None = None,
) -> None:
    if info.get("probe") != "ffprobe":
        raise Incomplete("ffprobe is unavailable; frame decoding was not verified")
    seconds = info.get("video_seconds")
    assert isinstance(seconds, Fraction) and float(seconds) >= minimum_seconds, (
        f"decoded video duration is too short: {seconds!r}"
    )
    frames = info.get("frame_count")
    assert isinstance(frames, int) and frames >= 2, f"decoded too few frames: {frames!r}"
    if expected_fps is None:
        return

    observed_fps = info.get("observed_fps")
    nominal_fps = info.get("nominal_fps")
    assert (
        isinstance(observed_fps, Fraction)
        and expected_fps * Fraction(98, 100)
        <= observed_fps
        <= expected_fps * Fraction(102, 100)
    ), f"decoded observed fps is unhealthy: {observed_fps!r}, expected about {expected_fps}"
    assert (
        isinstance(nominal_fps, Fraction)
        and expected_fps * Fraction(99, 100)
        <= nominal_fps
        <= expected_fps * Fraction(101, 100)
    ), f"container nominal fps differs from admission: {nominal_fps!r} vs {expected_fps}"
    minimum_frames = math.ceil(seconds * expected_fps * Fraction(95, 100))
    assert frames >= minimum_frames, (
        f"decoded only {frames} frames; expected at least {minimum_frames} "
        f"for decoded duration {float(seconds):.3f}s at {float(expected_fps):.3f}fps"
    )
    target_interval = Fraction(1, 1) / expected_fps
    minimum_interval = info.get("minimum_frame_interval")
    maximum_interval = info.get("maximum_frame_interval")
    assert (
        isinstance(minimum_interval, Fraction)
        and minimum_interval >= target_interval / 2
    ), f"decoded frame interval is implausibly short: {minimum_interval!r}"
    maximum_healthy_interval = target_interval * Fraction(3, 2)
    assert (
        isinstance(maximum_interval, Fraction)
        and maximum_interval <= maximum_healthy_interval
    ), (
        f"decoded frame cadence has a gap: {maximum_interval!r}, "
        f"expected <= {maximum_healthy_interval}"
    )


def midrec_still_cadence_errors(info: dict, *, expected_fps: Fraction) -> list[str]:
    """Cadence contract for a take containing exactly one in-stream still interruption.

    The mid-REC still shares the ONE camera stream, so the encoder feed legitimately gaps
    once while the still frame is exposed and handed off — requiring soak-grade continuous
    cadence here asserts something the single-stream design never promises (device evidence
    2026-07-23: one 0.689 s gap + one 0.067 s recovery interval, perfect 1001/30000 cadence
    everywhere else). Everything OUTSIDE one bounded contiguous interruption must hold the
    admitted cadence; a second interruption, an over-budget one, or a sparse decode still fails.
    """
    nominal = info.get("nominal_fps")
    errors = []
    # Same ±1% container-nominal band as require_decoded_video: this muxer derives
    # r_frame_rate from actual frame timing (device-observed 359/12 ≈ 29.92 for a 29.97
    # admission whose take contains the still gap), so exact equality over-pins it.
    if not (
        isinstance(nominal, Fraction)
        and expected_fps * Fraction(99, 100) <= nominal <= expected_fps * Fraction(101, 100)
    ):
        errors.append(f"nominal_fps={nominal!r}, expected about {expected_fps}")
    intervals = info.get("frame_intervals")
    if not isinstance(intervals, list) or not intervals or not all(
        isinstance(interval, Fraction) for interval in intervals
    ):
        return [*errors, "decoded frame intervals are unavailable"]

    target_interval = Fraction(1, 1) / expected_fps
    if min(intervals) < target_interval / 2:
        errors.append(
            f"minimum_frame_interval={min(intervals)!r}, expected >= {target_interval / 2}"
        )
    healthy_maximum = target_interval * Fraction(3, 2)
    runs: list[list[Fraction]] = []
    for previous, interval in zip([None, *intervals], intervals):
        if interval > healthy_maximum:
            if runs and previous is not None and previous > healthy_maximum:
                runs[-1].append(interval)
            else:
                runs.append([interval])
    if len(runs) > 1:
        errors.append(
            f"{len(runs)} separate cadence interruptions, expected at most the one still-owned gap"
        )
    if runs:
        widest = max(runs, key=lambda run: sum(run, Fraction(0)))
        if len(widest) > REC_SNAPSHOT_MAX_STILL_GAP_INTERVALS:
            errors.append(
                f"still-owned interruption spans {len(widest)} intervals, "
                f"expected <= {REC_SNAPSHOT_MAX_STILL_GAP_INTERVALS}"
            )
        if sum(widest, Fraction(0)) > REC_SNAPSHOT_MAX_STILL_GAP_SECONDS:
            errors.append(
                f"still-owned interruption is {float(sum(widest, Fraction(0))):.3f}s, "
                f"expected <= {float(REC_SNAPSHOT_MAX_STILL_GAP_SECONDS):.1f}s"
            )
    seconds = info.get("video_seconds")
    frames = info.get("frame_count")
    if isinstance(seconds, Fraction) and isinstance(frames, int):
        interruption_total = sum((sum(run, Fraction(0)) for run in runs), Fraction(0))
        minimum_frames = math.ceil(
            (seconds - interruption_total) * expected_fps * Fraction(95, 100)
        )
        if frames < minimum_frames:
            errors.append(
                f"decoded only {frames} frames; expected at least {minimum_frames} "
                "outside the still-owned interruption"
            )
    else:
        errors.append("decoded duration/frame count are unavailable")
    return errors


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


def existing_media_regressions(before: list[MediaRow], after: list[MediaRow]) -> list[str]:
    """Existing published-row metadata must remain stable while later operations append rows."""
    after_by_key = {row.key: row for row in after}
    errors = []
    for row in before:
        current = after_by_key.get(row.key)
        if current is None:
            errors.append(f"existing row disappeared: {row.key} {row.display_name}")
        elif current.metadata_fingerprint != row.metadata_fingerprint:
            errors.append(f"existing row changed: {row.key} {row.display_name}")
    return errors


def wait_expected_new_media_rows(
    ctx: Context,
    before: set[tuple[str, int]],
    expected_names: set[str],
    *,
    timeout_s: float = 45.0,
    settle_s: float = 1.0,
    poll_s: float = 0.5,
) -> list[MediaRow]:
    """Wait for several known capture families without accepting their cardinality by accident."""
    deadline = time.monotonic() + timeout_s
    stable_since: float | None = None
    last_fingerprint: tuple | None = None
    latest: list[MediaRow] = []
    while time.monotonic() < deadline:
        latest = [row for row in ctx.adb.media_store_rows() if row.key not in before]
        names = {row.display_name for row in latest}
        fingerprint = tuple(row.metadata_fingerprint for row in latest)
        complete = expected_names <= names and all(row.metadata_ready for row in latest)
        if complete and fingerprint == last_fingerprint:
            stable_since = stable_since or time.monotonic()
            if time.monotonic() - stable_since >= settle_s:
                return latest
        else:
            stable_since = None
        last_fingerprint = fingerprint
        time.sleep(poll_s)
    return latest


def exact_media_delta_error(
    before: set[tuple[str, int]],
    expected: set[tuple[str, int]],
    current: list[MediaRow],
) -> str | None:
    actual = {row.key for row in current if row.key not in before}
    if actual == expected:
        return None
    return f"expected new keys={sorted(expected)}, actual={sorted(actual)}"


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


def restore_capture_mode_verified(ctx: Context, initial_mode: str, pid: int) -> None:
    cleanup_transport_or_unsafe(
        f"could not verify {initial_mode} mode restoration",
        lambda: _restore_capture_mode_verified(ctx, initial_mode, pid),
    )


def _restore_capture_mode_verified(ctx: Context, initial_mode: str, pid: int) -> None:
    """Restore the exact entry mode and prove its UI, accepted session, 3A, and preview state."""
    expected_shutter = "Start recording" if initial_mode == "VIDEO" else "Take photo"
    expected_carousel = "Video mode" if initial_mode == "VIDEO" else "Photo mode"
    tree = ctx.adb.ui()
    if tree.find_desc_exact("Stop recording"):
        raise UnsafeState("refusing capture-mode restore while REC is active")
    transition_needed = tree.find_desc_exact(expected_shutter) is None
    transition_mark = ctx.adb.log_mark() if transition_needed else None
    if transition_needed:
        ctx.adb.tap_ui(desc=expected_carousel)

    deadline = time.monotonic() + 20
    last_error = "not checked"
    while time.monotonic() < deadline:
        if ctx.adb.pid() != pid:
            raise UnsafeState(f"app process changed while restoring {initial_mode}: expected {pid}")
        tree = ctx.adb.ui()
        carousel_error = mode_carousel_error(tree, expected_carousel)
        idle = tree.find_desc_exact(expected_shutter)
        stopped = tree.find_desc_exact("Stop recording") is None
        last_error = f"idle={idle is not None}, stopped={stopped}, carousel={carousel_error}"
        if idle is not None and stopped and carousel_error is None:
            break
        time.sleep(0.25)
    else:
        raise UnsafeState(f"could not restore {initial_mode} UI ({last_error})")

    if transition_mark is not None:
        acceptance = wait_session_acceptance(
            ctx,
            transition_mark,
            pid,
            initial_mode,
            timeout_s=20,
        )
        if acceptance is None:
            # Acceptance, not Ready: this parser deliberately does not check ready= (see the
            # SESSION_ACCEPTED comment); liveness is proven by the owned 3A wait just below.
            raise UnsafeState(f"restored {initial_mode} route was never accepted")
        evidence = wait_mode_three_a(
            ctx,
            transition_mark,
            pid,
            initial_mode,
            controller_id=acceptance.controller_id,
            optics_generation=acceptance.optics_generation,
            timeout_s=20 if initial_mode == "PHOTO" else 10,
        )
        if evidence is None:
            raise UnsafeState(f"restored {initial_mode} route produced no owned 3A result")
    if not ctx.adb.preview_is_live():
        raise UnsafeState(f"preview was not live after restoring {initial_mode}")


def _overlap_area(first, second) -> int:
    left = max(first.bounds[0], second.bounds[0])
    top = max(first.bounds[1], second.bounds[1])
    right = min(first.bounds[2], second.bounds[2])
    bottom = min(first.bounds[3], second.bounds[3])
    return max(0, right - left) * max(0, bottom - top)


def _minimum_touch_px(metrics: DisplayMetrics) -> int:
    return math.ceil(48 * metrics.density_dpi / 160) - 2  # tolerate pixel rounding only


def _described_action_errors(tree, node: UiNode, label: str, role_suffix: str = "Button") -> list[str]:
    """Require the described node itself to own its platform action and focus semantics."""
    errors: list[str] = []
    if not node.class_name.endswith(role_suffix):
        errors.append(f"{label}: described node has role {node.class_name!r}, expected {role_suffix}")
    # Compose omits the click action from the already-checked radio item. Its checked state is the
    # coherent action outcome; every other enabled control must remain directly clickable.
    if node.enabled and not node.clickable and not (node.checkable and node.checked):
        errors.append(f"{label}: enabled described node is not clickable")
    if not node.focusable:
        errors.append(f"{label}: described action node is not focusable")
    split = [
        peer for peer in tree.nodes
        if peer is not node
        and peer.bounds == node.bounds
        and (peer.clickable or peer.focusable)
        and (peer.desc != node.desc or peer.class_name != node.class_name)
    ]
    if split:
        errors.append(
            f"{label}: equal-bounds split semantics with "
            + ", ".join(f"{peer.desc!r}/{peer.class_name}" for peer in split)
        )
    return errors


def camera_chrome_layout_errors(
    tree,
    metrics: DisplayMetrics,
    *,
    detailed: bool | None = None,
    device_orientation: int | None = None,
) -> list[str]:
    """Pixel-level contract for the fixed Sony/iPhone-familiar shooting controls."""
    errors: list[str] = []

    def one(label: str, predicate) -> UiNode | None:
        matches = [node for node in tree.nodes if predicate(node)]
        if len(matches) != 1:
            errors.append(f"{label}: expected one node, got {len(matches)}")
            return None
        return matches[0]

    def optional_one(label: str, predicate) -> UiNode | None:
        matches = [node for node in tree.nodes if predicate(node)]
        if len(matches) > 1:
            errors.append(f"{label}: expected at most one node, got {len(matches)}")
            return None
        return matches[0] if matches else None

    if detailed is None:
        detailed = any(node.desc in ("Grid on", "Grid off") for node in tree.nodes)
    photo_mode = any(node.desc == "Take photo" for node in tree.nodes)
    optional_top_specs = [
        ("Flash", lambda node: node.desc.startswith("Flash ")),
        ("Self timer", lambda node: node.desc.startswith("Self timer ")),
        ("Aspect ratio", lambda node: node.desc.startswith("Aspect ratio ")),
        ("Grid", lambda node: node.desc in ("Grid on", "Grid off")),
    ]
    top = []
    for label, predicate in optional_top_specs:
        required = detailed and (label == "Grid" or photo_mode)
        top.append((label, one(label, predicate) if required else optional_one(label, predicate)))
    top.extend([
        ("Teleconverter", one("Teleconverter", lambda node: node.desc == "Teleconverter")),
        (
            "Shooting info",
            one(
                "Shooting info",
                lambda node: node.desc in ("Show shooting info", "Hide shooting info"),
            ),
        ),
        ("Open settings", one("Open settings", lambda node: node.desc == "Open settings")),
    ])
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
        if label != "Gallery":
            suffix = "RadioButton" if label in FOCAL_PRESETS or label in CAPTURE_MODES else "Button"
            errors.extend(_described_action_errors(tree, node, label, suffix))

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
    if detailed:
        below("Fn row", [("Fn", fn)], "focal rail", focal)
    elif fn is not None:
        focal_nodes = [node for _, node in focal if node is not None]
        vertically_aligned = any(
            min(fn.bounds[3], node.bounds[3]) > max(fn.bounds[1], node.bounds[1])
            for node in focal_nodes
        )
        if focal_nodes and not vertically_aligned:
            errors.append("compact Fn entry is not aligned with the focal rail")
        for node in focal_nodes:
            if _overlap_area(fn, node) > 0:
                errors.append(f"compact Fn entry overlaps {node.desc}")
    below("focal rail", focal, "mode carousel", modes)
    below("mode carousel", modes, "shutter row", [("Gallery", gallery), ("Shutter", shutter)])

    if shutter is not None:
        center_tolerance = math.ceil(8 * metrics.density_dpi / 160)
        if abs(shutter.center[0] - metrics.width_px // 2) > center_tolerance:
            errors.append(
                f"idle shutter is not centered: x={shutter.center[0]}, screen={metrics.width_px}"
            )
    if device_orientation is not None:
        errors.extend(fn_entry_layout_errors(tree, metrics, device_orientation))
    return errors


def _normalized_orientation(device_orientation: int) -> int:
    return device_orientation % 360


def _physical_point(
    x: int,
    y: int,
    metrics: DisplayMetrics,
    device_orientation: int,
) -> tuple[int, int]:
    """Map portrait-locked raw display coordinates into the held physical view."""
    orientation = _normalized_orientation(device_orientation)
    if orientation == 90:
        return y, metrics.width_px - x
    if orientation == 270:
        return metrics.height_px - y, x
    if orientation == 180:
        return metrics.width_px - x, metrics.height_px - y
    return x, y


def _physical_bounds(
    node: UiNode,
    metrics: DisplayMetrics,
    device_orientation: int,
) -> tuple[int, int, int, int]:
    left, top, right, bottom = node.bounds
    corners = [
        _physical_point(x, y, metrics, device_orientation)
        for x, y in ((left, top), (right, top), (left, bottom), (right, bottom))
    ]
    return (
        min(point[0] for point in corners),
        min(point[1] for point in corners),
        max(point[0] for point in corners),
        max(point[1] for point in corners),
    )


def raw_region_changed_pixel_count(
    before: bytes,
    after: bytes,
    bounds: tuple[int, int, int, int],
) -> int:
    """Count changed RGBA pixels in one raw Android screencap region."""
    if len(before) < 16 or len(after) < 16:
        raise ValueError("raw screencap is missing its 16-byte header")
    before_header = struct.unpack_from("<4I", before, 0)
    after_header = struct.unpack_from("<4I", after, 0)
    if before_header[:2] != after_header[:2]:
        raise ValueError(f"screencap dimensions changed: {before_header[:2]} -> {after_header[:2]}")
    width, height = before_header[:2]
    expected_bytes = 16 + width * height * 4
    if len(before) < expected_bytes or len(after) < expected_bytes:
        raise ValueError("raw screencap pixel payload is truncated")
    left, top, right, bottom = bounds
    left = min(width, max(0, left))
    right = min(width, max(left, right))
    top = min(height, max(0, top))
    bottom = min(height, max(top, bottom))
    changed = 0
    for y in range(top, bottom):
        row_offset = 16 + y * width * 4
        for x in range(left, right):
            offset = row_offset + x * 4
            if before[offset:offset + 4] != after[offset:offset + 4]:
                changed += 1
    return changed


def fn_entry_layout_errors(
    tree,
    metrics: DisplayMetrics,
    device_orientation: int,
) -> list[str]:
    """The direct Fn entry stays in the same physical bottom reach zone as its held tray."""
    errors: list[str] = []
    entries = [node for node in tree.nodes if node.desc == "Open function menu"]
    if len(entries) != 1:
        return [f"Fn entry: expected one action node, got {len(entries)}"]
    entry = entries[0]
    errors.extend(_described_action_errors(tree, entry, "Fn entry"))
    left, top, right, bottom = entry.bounds
    minimum_px = _minimum_touch_px(metrics)
    if not (0 <= left < right <= metrics.width_px and 0 <= top < bottom <= metrics.height_px):
        errors.append(f"Fn entry: out of screen bounds {entry.bounds}")
        return errors
    if right - left < minimum_px or bottom - top < minimum_px:
        errors.append(f"Fn entry: touch bounds {right - left}x{bottom - top}px < {minimum_px}px")

    orientation = _normalized_orientation(device_orientation)
    expected_raw_end = orientation == 270
    raw_third = metrics.width_px / 3
    if expected_raw_end and entry.center[0] < metrics.width_px - raw_third:
        errors.append(f"Fn entry: 270° must use the raw End edge, center={entry.center[0]}")
    if not expected_raw_end and entry.center[0] > raw_third:
        errors.append(
            f"Fn entry: {orientation}° must use the raw Start edge, center={entry.center[0]}"
        )

    _, physical_y = _physical_point(*entry.center, metrics, orientation)
    physical_height = metrics.width_px if orientation in (90, 270) else metrics.height_px
    if physical_y < physical_height * 2 / 3:
        errors.append(
            f"Fn entry: outside physical bottom reach zone at {orientation}° "
            f"(y={physical_y}, height={physical_height})"
        )
    return errors


def settings_tab_nodes(tree) -> dict[str, list[UiNode]]:
    """Return each settings-tab action by its exact merged accessibility name."""
    return {
        label: [
            node for node in tree.nodes
            if node.desc.casefold() == label.casefold()
        ]
        for label in SETTINGS_TABS
    }


def settings_modal_layout_errors(tree, metrics: DisplayMetrics) -> list[str]:
    """One close action and nine coherent named 48 dp tab targets."""
    errors: list[str] = []
    close_nodes = [node for node in tree.nodes if node.desc == "Close settings"]
    if len(close_nodes) != 1:
        errors.append(f"Settings close: expected one explicit action node, got {len(close_nodes)}")
    else:
        close = close_nodes[0]
        errors.extend(_described_action_errors(tree, close, "Settings close"))
        left, top, right, bottom = close.bounds
        minimum_px = _minimum_touch_px(metrics)
        if not (0 <= left < right <= metrics.width_px and 0 <= top < bottom <= metrics.height_px):
            errors.append(f"Settings close: out of screen bounds {close.bounds}")
        elif right - left < minimum_px or bottom - top < minimum_px:
            errors.append(
                f"Settings close: touch bounds {right - left}x{bottom - top}px < {minimum_px}px"
            )
        if close.bounds == (0, 0, metrics.width_px, metrics.height_px):
            errors.append("Settings close: full-screen scrim must be touch-only")

    minimum_px = _minimum_touch_px(metrics)
    tabs = settings_tab_nodes(tree)
    for label, nodes in tabs.items():
        if len(nodes) != 1:
            errors.append(f"Settings tab {label}: expected one named node, got {len(nodes)}")
            continue
        node = nodes[0]
        left, top, right, bottom = node.bounds
        if not (0 <= left < right <= metrics.width_px and 0 <= top < bottom <= metrics.height_px):
            errors.append(f"Settings tab {label}: out of screen bounds {node.bounds}")
        elif right - left < minimum_px or bottom - top < minimum_px:
            errors.append(
                f"Settings tab {label}: touch bounds {right - left}x{bottom - top}px < {minimum_px}px"
            )
        if not node.enabled:
            errors.append(f"Settings tab {label}: disabled")
        if not node.focusable:
            errors.append(f"Settings tab {label}: named node is not focusable")
        # Android omits the click action from the already-selected Tab. Its selected state is the
        # coherent outcome; every other tab must directly own its activation action.
        if not node.selected and not node.clickable:
            errors.append(f"Settings tab {label}: unselected named node is not clickable")
        split = [
            peer for peer in tree.nodes
            if peer is not node
            and peer.bounds == node.bounds
            and (peer.clickable or peer.focusable)
            and peer.desc != node.desc
        ]
        if split:
            errors.append(f"Settings tab {label}: equal-bounds split semantics")

    selected_tabs = [label for label, nodes in tabs.items() if len(nodes) == 1 and nodes[0].selected]
    if len(selected_tabs) != 1:
        errors.append(f"Settings tabs: expected one selected tab, got {selected_tabs}")
    return errors


def adjustment_layout_errors(tree, metrics: DisplayMetrics) -> list[str]:
    """Compact manual ruler owns one coherent close action inside the lower control band."""
    close_nodes = [node for node in tree.nodes if node.desc == "Close adjustment"]
    if len(close_nodes) != 1:
        return [f"Adjustment close: expected one action node, got {len(close_nodes)}"]
    close = close_nodes[0]
    errors = _described_action_errors(tree, close, "Adjustment close")
    left, top, right, bottom = close.bounds
    minimum_px = _minimum_touch_px(metrics)
    if not (0 <= left < right <= metrics.width_px and 0 <= top < bottom <= metrics.height_px):
        errors.append(f"Adjustment close: out of screen bounds {close.bounds}")
    elif right - left < minimum_px or bottom - top < minimum_px:
        errors.append(
            f"Adjustment close: touch bounds {right - left}x{bottom - top}px < {minimum_px}px"
        )
    if top < metrics.height_px // 2:
        errors.append(f"Adjustment close: escaped the lower control band {close.bounds}")
    if tree.find_desc_exact("Close function menu") is not None:
        errors.append("Adjustment ruler and Fn modal are visible at the same time")
    return errors


def snapshot_gamma_state_errors(tree, expected: str) -> list[str]:
    """Require one non-actionable debug probe with the exact current Gamma value."""
    prefix = "Snapshot Gamma "
    probes = [node for node in tree.nodes if node.desc.startswith(prefix)]
    if len(probes) != 1:
        return [f"Snapshot Gamma: expected one state probe, got {len(probes)}"]
    probe = probes[0]
    errors = []
    if probe.desc != f"{prefix}{expected}":
        errors.append(f"Snapshot Gamma: {probe.desc!r} != {expected!r}")
    if probe.clickable or probe.focusable:
        errors.append("Snapshot Gamma: debug state probe must not own an action or focus")
    return errors


def loupe_copy_errors(labels: set[str]) -> list[str]:
    """Reject second-stream/true-1x claims while allowing the real `1x lens` rail label."""
    errors = []
    for label in sorted(labels):
        normalized = label.casefold().replace("×", "x")
        if "pip" in normalized:
            errors.append(f"Loupe copy makes a PIP claim: {label!r}")
        if re.search(r"\b1\s*x\s*(overview|finder|feed|camera)\b", normalized) or re.search(
            r"\b(overview|finder|feed|camera)\s*1\s*x\b", normalized,
        ):
            errors.append(f"Loupe copy makes a true-1x claim: {label!r}")
    return errors


def loupe_layout_errors(tree, metrics: DisplayMetrics) -> list[str]:
    """Keep the non-interactive same-stream overview inside preview and off direct controls."""
    errors = []
    overviews = [node for node in tree.nodes if node.desc == "Loupe overview"]
    if len(overviews) != 1:
        return [f"Loupe overview: expected one region, got {len(overviews)}"]
    overview = overviews[0]
    if overview.clickable or overview.focusable:
        errors.append("Loupe overview: region must not own an action or focus")

    left, top, right, bottom = overview.bounds
    if not (0 <= left < right <= metrics.width_px and 0 <= top < bottom <= metrics.height_px):
        errors.append(f"Loupe overview: out of screen bounds {overview.bounds}")

    previews = [node for node in tree.nodes if node.class_name.endswith("TextureView")]
    if len(previews) != 1:
        errors.append(f"Loupe overview: expected one preview surface, got {len(previews)}")
    else:
        preview = previews[0]
        pl, pt, pr, pb = preview.bounds
        if not (pl <= left < right <= pr and pt <= top < bottom <= pb):
            errors.append(
                f"Loupe overview: bounds {overview.bounds} escape preview {preview.bounds}"
            )

    overlaps = [
        node for node in tree.nodes
        if node is not overview
        and (node.desc or node.text)
        and (node.clickable or node.focusable)
        and _overlap_area(overview, node) > 0
    ]
    if overlaps:
        labels = [node.desc or node.text for node in overlaps]
        errors.append(f"Loupe overview: overlaps named actionable chrome {labels}")
    return errors


def function_menu_layout_errors(
    tree,
    metrics: DisplayMetrics,
    *,
    device_orientation: int | None = None,
    expected_physical_order: tuple[str, ...] | None = None,
) -> list[str]:
    """Constrained-window and one-action-node contract for the production Fn tile grid."""
    errors: list[str] = []
    tiles = [node for node in tree.nodes if node.desc in FN_TILE_LABELS]
    if not 1 <= len(tiles) <= 8:
        errors.append(f"Fn tiles: expected 1..8 nodes, got {len(tiles)}")

    minimum_px = _minimum_touch_px(metrics)
    for tile in tiles:
        left, top, right, bottom = tile.bounds
        if not (0 <= left < right <= metrics.width_px and 0 <= top < bottom <= metrics.height_px):
            errors.append(f"Fn {tile.desc}: out of screen bounds {tile.bounds}")
            continue
        if right - left < minimum_px or bottom - top < minimum_px:
            errors.append(
                f"Fn {tile.desc}: touch bounds {right - left}x{bottom - top}px < {minimum_px}px"
            )
        errors.extend(_described_action_errors(tree, tile, f"Fn {tile.desc}"))

    for index, first in enumerate(tiles):
        for second in tiles[index + 1:]:
            if _overlap_area(first, second) > 0:
                errors.append(f"Fn tiles overlap: {first.desc} and {second.desc}")

    close_nodes = [node for node in tree.nodes if node.desc == "Close function menu"]
    if len(close_nodes) != 1:
        errors.append(f"Fn close: expected one explicit action node, got {len(close_nodes)}")
    else:
        close = close_nodes[0]
        left, top, right, bottom = close.bounds
        if not (0 <= left < right <= metrics.width_px and 0 <= top < bottom <= metrics.height_px):
            errors.append(f"Fn close: out of screen bounds {close.bounds}")
        elif right - left < minimum_px or bottom - top < minimum_px:
            errors.append(
                f"Fn close: touch bounds {right - left}x{bottom - top}px < {minimum_px}px"
            )
        errors.extend(_described_action_errors(tree, close, "Fn close"))
        if close.bounds == (0, 0, metrics.width_px, metrics.height_px):
            errors.append("Fn close: full-screen scrim must be touch-only")

    if device_orientation is not None and _normalized_orientation(device_orientation) in (90, 270):
        orientation = _normalized_orientation(device_orientation)
        edge_tolerance = math.ceil(40 * metrics.density_dpi / 160)
        action_nodes = [*tiles, *close_nodes]
        panel_nodes = [
            node for node in tree.nodes
            if node.class_name == "android.widget.ScrollView"
            and all(
                node.bounds[0] <= action.bounds[0]
                and node.bounds[1] <= action.bounds[1]
                and action.bounds[2] <= node.bounds[2]
                and action.bounds[3] <= node.bounds[3]
                for action in action_nodes
            )
        ]
        if len(panel_nodes) != 1:
            errors.append(
                "Fn tray: expected one enclosing android.widget.ScrollView panel, "
                f"got {len(panel_nodes)}"
            )
        else:
            panel = panel_nodes[0]
            if orientation == 90 and panel.bounds[0] > edge_tolerance:
                errors.append("Fn tray: 90° raw panel is not Start-anchored")
            if orientation == 270 and metrics.width_px - panel.bounds[2] > edge_tolerance:
                errors.append("Fn tray: 270° raw panel is not End-anchored")

            physical_panel_bounds = _physical_bounds(panel, metrics, orientation)
            physical_top = physical_panel_bounds[1]
            physical_bottom = physical_panel_bounds[3]
            if metrics.width_px - physical_bottom > edge_tolerance:
                errors.append(
                    f"Fn tray: {orientation}° is not in the physical bottom zone "
                    f"(gap={metrics.width_px - physical_bottom}px)"
                )
            physical_depth = physical_bottom - physical_top
            maximum_depth = math.ceil(metrics.width_px * FN_HELD_MAX_DEPTH_FRACTION)
            if physical_depth > maximum_depth:
                errors.append(
                    f"Fn tray: {orientation}° consumes {physical_depth}px of the "
                    f"{metrics.width_px}px physical short edge (max={maximum_depth}px)"
                )

        if expected_physical_order is not None:
            if len(tiles) != len(expected_physical_order):
                errors.append(
                    "Fn tray: physical-order check expected "
                    f"{len(expected_physical_order)} tiles, got {len(tiles)}"
                )
            elif len(tiles) != 8:
                errors.append(f"Fn tray: held 4x2 check requires 8 tiles, got {len(tiles)}")
            else:
                physical = [
                    (tile.desc, *_physical_point(*tile.center, metrics, orientation))
                    for tile in tiles
                ]
                by_y = sorted(physical, key=lambda item: (item[2], item[1]))
                top_row = sorted(by_y[:4], key=lambda item: item[1])
                bottom_row = sorted(by_y[4:], key=lambda item: item[1])
                if max(item[2] for item in top_row) >= min(item[2] for item in bottom_row):
                    errors.append("Fn tray: held tiles do not form two separated physical rows")
                actual_order = tuple(item[0] for item in (*top_row, *bottom_row))
                if actual_order != expected_physical_order:
                    errors.append(
                        f"Fn tray: physical order {actual_order} != {expected_physical_order}"
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
    # Acceptance, not Ready — the parser deliberately does not check ready= (see SESSION_ACCEPTED).
    assert video_acceptance, "Video route was never accepted by the camera engine"
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
    assert photo_acceptance, "Photo route was never accepted after Video"
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
    """TC toggle reopens onto the standalone tele and back; owned 3A telemetry proves each leg."""
    pid = ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    mark = ctx.adb.log_mark()
    baseline = wait_mode_three_a(ctx, mark, pid, "PHOTO", timeout_s=20)
    assert baseline, "no baseline Photo 3A before the TC toggle"
    was_tele, was_zoom = three_a_tele_state(baseline)
    toggled = toggle_teleconverter(ctx, pid, baseline)
    now_tele, now_zoom = three_a_tele_state(toggled)
    assert now_tele != was_tele, (
        f"TC toggle did not change the 3A tele state: {was_tele} -> {now_tele}"
    )
    if now_tele:
        assert now_zoom >= TELE_MIN_EFFECTIVE_ZOOM, (
            f"TELE route reports effZoom={now_zoom}, expected >= ~4.2857 (300 mm / 70 mm)"
        )
    back = toggle_teleconverter(ctx, pid, toggled)
    back_tele, _ = three_a_tele_state(back)
    assert back_tele == was_tele, "TC toggle did not return to the original state"
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during TC round-trip: {fatals[:2]}"
    ctx.note(
        f"TELE {was_tele}(effZoom={was_zoom})→{now_tele}(effZoom={now_zoom})→{back_tele} clean"
    )


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
    baseline = wait_mode_three_a(ctx, mark, pid, "PHOTO", timeout_s=20)
    assert baseline, "no baseline Photo 3A before TELE entry"
    tele_evidence = baseline
    entered_tele = False
    if not three_a_tele_state(baseline)[0]:
        tele_evidence = toggle_teleconverter(ctx, pid, baseline)
        entered_tele = True
    try:
        now_tele, now_zoom = three_a_tele_state(tele_evidence)
        assert now_tele, "could not enter TELE mode (3A telemetry still reports tele=false)"
        assert now_zoom >= TELE_MIN_EFFECTIVE_ZOOM, (
            f"TELE route reports effZoom={now_zoom}, expected >= ~4.2857 (300 mm / 70 mm)"
        )
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
            restore_teleconverter_off_verified(ctx, pid, tele_evidence)


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
                admitted = recover_recording_admission(ctx, mark, pid, "strict video")
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
    modal_errors = settings_modal_layout_errors(opened, metrics)
    assert not modal_errors, "settings modal layout violations: " + "; ".join(modal_errors)
    try:
        for tab, title in zip(SETTINGS_TABS, SETTINGS_TITLES, strict=True):
            tree = ctx.adb.ui()
            current_errors = settings_modal_layout_errors(tree, metrics)
            assert not current_errors, "settings modal layout violations: " + "; ".join(current_errors)
            tab_nodes = settings_tab_nodes(tree)
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
                            node.desc.casefold() == label.casefold()
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
    metrics = ctx.adb.display_metrics()
    layout_errors = function_menu_layout_errors(menu, metrics)
    assert not layout_errors, "Fn overlay layout violations: " + "; ".join(layout_errors)
    tiles = [node for node in menu.nodes if node.desc in FN_TILE_LABELS and node.enabled]
    assert tiles, "Fn overlay exposed no enabled setting tile"
    quick_tiles = [
        node for node in tiles
        if node.desc not in FN_NUMERIC_TILE_LABELS and node.clickable and node.focusable
    ]
    assert quick_tiles, "Fn overlay exposed no activatable in-place quick action"
    ctx.adb.screenshot("function_menu")
    ctx.adb.shell("input keyevent KEYCODE_BACK")
    time.sleep(1)
    restored = ctx.adb.ui()
    assert restored.find_desc_exact("Open function menu"), "Back did not restore the Fn entry"
    assert restored.find_desc_exact("Close function menu") is None, "Back left the Fn overlay open"
    assert restored.find(desc="Open settings"), "camera chrome was not restored after Fn dismiss"
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during Fn open/dismiss: {fatals[:2]}"
    ctx.note(
        f"Fn opened with {len(tiles)} enabled tiles and {len(quick_tiles)} in-place actions; "
        "all tiles own actions, are in-bounds, >=48dp, non-overlapping; Back restored camera"
    )


@test("debug_snapshot_ui_contract", "full", destructive=True)
def t_debug_snapshot_ui_contract(ctx: Context) -> None:
    """HAL-free portrait and held UI prove Fn, modal, MR, ruler, and Loupe contracts."""
    metrics = ctx.adb.display_metrics()
    try:
        for orientation in (0, 90, 270):
            launch_ui_snapshot(ctx, orientation=orientation)
            idle = ctx.adb.ui(f"snapshot_idle_{orientation}")
            idle_errors = camera_chrome_layout_errors(
                idle,
                metrics,
                detailed=False,
                device_orientation=orientation,
            )
            assert not idle_errors, (
                f"snapshot idle {orientation}° violations: " + "; ".join(idle_errors)
            )
            assert idle.find_desc_exact("Close adjustment") is None
            assert not any(label in {"MR1", "MR2", "MR3"} for label in idle.all_labels())
            gamma_errors = snapshot_gamma_state_errors(idle, "HLG")
            assert not gamma_errors, "; ".join(gamma_errors)
            ctx.adb.screenshot(f"snapshot_idle_{orientation}")

            ctx.adb.tap_ui(desc="Open function menu")
            menu = ctx.adb.ui(f"snapshot_fn_{orientation}")
            menu_errors = function_menu_layout_errors(
                menu,
                metrics,
                device_orientation=orientation,
                expected_physical_order=(
                    FN_DEFAULT_PHYSICAL_ORDER if orientation in (90, 270) else None
                ),
            )
            assert not menu_errors, (
                f"snapshot Fn {orientation}° violations: " + "; ".join(menu_errors)
            )
            if orientation == 0:
                gamma = menu.find_desc_exact("Gamma")
                assert gamma and gamma.enabled, "snapshot Gamma quick action is unavailable"
                before = ctx.adb.exec_out("screencap")
                ctx.adb.tap(*gamma.center)
                time.sleep(0.9)
                sticky = ctx.adb.ui("snapshot_fn_0_sticky")
                assert sticky.find_desc_exact("Close function menu"), (
                    "Gamma quick action dismissed the Fn modal"
                )
                assert sticky.find_desc_exact("Gamma"), "Gamma quick action disappeared after update"
                # One cycle tap from the snapshot's HLG start lands on S-Log3 (the O-Log2 option
                # was replaced by the standard log profiles, 2026-07-22 — see ControlCycles).
                gamma_errors = snapshot_gamma_state_errors(sticky, "S-Log3")
                assert not gamma_errors, "; ".join(gamma_errors)
                after = ctx.adb.exec_out("screencap")
                changed = raw_region_changed_pixel_count(before, after, gamma.bounds)
                assert changed >= 20, f"Gamma quick action produced no visible value update ({changed}px)"
                ctx.adb.screenshot("snapshot_fn_0_sticky")
            else:
                ctx.adb.screenshot(f"snapshot_fn_{orientation}")
            ctx.adb.shell("input keyevent KEYCODE_BACK")

            restored = ctx.adb.ui()
            assert restored.find_desc_exact("Open function menu"), "Back did not dismiss snapshot Fn"
            ctx.adb.tap_ui(desc="Open settings")
            settings = ctx.adb.ui(f"snapshot_settings_{orientation}")
            settings_errors = settings_modal_layout_errors(settings, metrics)
            assert not settings_errors, (
                f"snapshot Settings {orientation}° violations: " + "; ".join(settings_errors)
            )
            ctx.adb.screenshot(f"snapshot_settings_{orientation}")
            ctx.adb.shell("input keyevent KEYCODE_BACK")

            launch_ui_snapshot(ctx, orientation=orientation, scenario="memory")
            memory = ctx.adb.ui(f"snapshot_memory_{orientation}")
            memory_labels = memory.all_labels()
            assert sum(label == "MR1" for label in memory_labels) == 1, (
                f"active memory is not one compact MR1 tag: {sorted(memory_labels)}"
            )
            assert not ({"MR2", "MR3"} & memory_labels), "inactive MR banks leaked onto viewfinder"
            assert memory.find_desc_exact("Close adjustment") is None
            ctx.adb.screenshot(f"snapshot_memory_{orientation}")

            launch_ui_snapshot(ctx, orientation=orientation, scenario="adjustment")
            ctx.adb.tap_ui(desc="Open function menu")
            ctx.adb.tap_ui(desc="ISO")
            adjustment = ctx.adb.ui(f"snapshot_adjustment_{orientation}")
            adjustment_errors = adjustment_layout_errors(adjustment, metrics)
            assert not adjustment_errors, (
                f"snapshot adjustment {orientation}° violations: " +
                "; ".join(adjustment_errors)
            )
            assert "ISO 400" in adjustment.all_labels(), "ISO ruler omitted its truthful readout"
            ctx.adb.screenshot(f"snapshot_adjustment_{orientation}")

            launch_ui_snapshot(ctx, orientation=orientation, scenario="loupe")
            loupe = ctx.adb.ui(f"snapshot_loupe_{orientation}")
            loupe_layout = loupe_layout_errors(loupe, metrics)
            assert not loupe_layout, "; ".join(loupe_layout)
            assert "OVERVIEW" in loupe.all_labels(), "visible overview omitted the OVERVIEW truth tag"
            assert loupe.find_desc_exact("Close adjustment") is None, (
                "Loupe scenario retained a prior manual-adjustment ruler"
            )
            assert loupe.find_desc_exact("Close function menu") is None, (
                "Loupe scenario retained the Fn modal"
            )
            assert "ISO 400" not in loupe.all_labels(), "Loupe scenario retained the ISO ruler"
            gamma_errors = snapshot_gamma_state_errors(loupe, "HLG")
            assert not gamma_errors, "; ".join(gamma_errors)
            copy_errors = loupe_copy_errors(loupe.all_labels())
            assert not copy_errors, "; ".join(copy_errors)
            ctx.adb.screenshot(f"snapshot_loupe_{orientation}")

            if orientation in (90, 270):
                launch_ui_snapshot(ctx, orientation=orientation, rtl=True)
                rtl_idle = ctx.adb.ui(f"snapshot_idle_rtl_{orientation}")
                assert rtl_idle.find_desc_exact("Snapshot layout Rtl"), (
                    "RTL snapshot request did not reach the production-composable host"
                )
                # Locale-aware camera rows may legitimately mirror their reading order. The
                # regression under test is narrower: Fn's documented raw edge/physical-bottom
                # geometry must remain absolute, as must the opened tray's physical 4x2 order.
                rtl_errors = fn_entry_layout_errors(
                    rtl_idle,
                    metrics,
                    orientation,
                )
                assert not rtl_errors, (
                    f"snapshot RTL Fn entry {orientation}° violations: " +
                    "; ".join(rtl_errors)
                )
                ctx.adb.screenshot(f"snapshot_idle_rtl_{orientation}")
                ctx.adb.tap_ui(desc="Open function menu")
                rtl_menu = ctx.adb.ui(f"snapshot_fn_rtl_{orientation}")
                rtl_menu_errors = function_menu_layout_errors(
                    rtl_menu,
                    metrics,
                    device_orientation=orientation,
                    expected_physical_order=FN_DEFAULT_PHYSICAL_ORDER,
                )
                assert not rtl_menu_errors, (
                    f"snapshot RTL Fn {orientation}° violations: " +
                    "; ".join(rtl_menu_errors)
                )
                ctx.adb.screenshot(f"snapshot_fn_rtl_{orientation}")
    finally:
        # The fixture never opens Camera2. Restore the suite's normal foreground camera surface.
        ctx.adb.launch(wait_s=3)

    ctx.note(
        "HAL-free 0/90/270 snapshots: Fn physical order/reach + exact sticky Gamma, RTL held Fn, "
        "one Settings close, single MR1 tag, isolated ISO ruler, and truthful Loupe Overview"
    )


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


# ---------------------------------------------------------------- full: data validity / binning
# Cycle-7 phase-5 mandate: binned outputs must match advertised geometry EXACTLY and their data
# and metadata must be valid. Expectations come LIVE from `dumpsys media.camera` per accepted
# camera id (never a hardcoded id); the documented 4080x3064 / 4096x3072 pair (CLAUDE.md probe
# 2026-07-22) is reported as a cross-check. 200MP remosaic is settled NOT exposed on PMA110.

DOCUMENTED_BINNED_ARRAYS = frozenset({(4080, 3064), (4096, 3072)})
BINNED_ARRAY_MAX_PIXELS = 20_000_000  # any larger advertised array would resurrect the hi-res fact
CAMERA_STATIC_HEADER = re.compile(
    r"== Camera HAL device device@[0-9.]+/[A-Za-z0-9_]+/([0-9]+) \(v[0-9.]+\) "
    r"static information: =="
)
_STREAM_CONFIG_ROW = re.compile(r"\[([0-9]+) ([0-9]+) ([0-9]+) (INPUT|OUTPUT) \]")
_HAL_FORMAT_RAW16 = 32  # HAL_PIXEL_FORMAT_RAW16 == ImageFormat.RAW_SENSOR

# The 3A telemetry's sensor facts: the repeating request's applied ISO/exposure. In MANUAL these
# are exactly the requested still values, so EXIF parity compares against them.
THREE_A_SENSOR = re.compile(r"\biso=([0-9]+) expNs=([0-9]+)\b")
ISO_READOUT = re.compile(r"(?:Auto )?ISO ([0-9]+)")
TELE_DNG_TARGET_ISO = 800
TELE_DNG_TARGET_EXPOSURE_S = Fraction(1, 100)
# Manual ruler device facts (probed 2026-07-24): ticks are 12 dp apart, ~8 dp of the first motion
# is consumed as touch slop, and dragging LEFT reveals HIGHER values (content follows the finger).
RULER_TICK_DP = 12
RULER_SLOP_DP = 8

PHOTO_OUTPUT_LABELS = ("HEIF", "JPEG", "DNG")
ASPECT_LABELS = ("4:3", "16:9")
EXPOSURE_MODE_LABELS = ("P", "S", "ISO", "M")
EXPOSURE_STEP_EV = {"1/3 EV": 1.0 / 3.0, "1/2 EV": 0.5, "1 EV": 1.0}
VIDEO_CODEC_LABELS = ("HEVC", "H.264")
TRANSFER_LABELS = ("HLG", "S-Log3", "S-Log3.Cine", "LogC3", "SDR")
TRANSFER_SPEC_NAMES = {
    "HLG": "HLG",
    "S-Log3": "SLOG3",
    "S-Log3.Cine": "SLOG3_CINE",
    "LogC3": "LOGC3",
    "SDR": "SDR",
}
VIDEO_TRUTH_CLIP_SECONDS = 6
# H.273 SDR-class transfer names as ffprobe prints them. The QTI encoder writes "SDR transfer +
# BT2020 full range" into the container as CICP 14 (bt2020-10 — functionally BT.709), which is the
# documented log-profile policy; the regression this list guards is the ST2084/HLG mistag.
SDR_CLASS_TRANSFERS = frozenset({"bt709", "smpte170m", "bt470bg", "bt2020-10", "bt2020-12"})
HDR_MISTAG_TRANSFERS = frozenset({"smpte2084", "arib-std-b67"})


@dataclass(frozen=True)
class CameraGeometry:
    camera_id: str
    facing: str
    pixel_array: tuple[int, int]
    active_array: tuple[int, int]
    raw16_sizes: tuple[tuple[int, int], ...]
    physical_ids: tuple[str, ...]


def _static_key_values(body: str, name: str) -> list[str] | None:
    match = re.search(
        re.escape(name) + r" \([0-9a-f]+\): \w+\[[0-9]+\]\s*\n\s*\[([^\]]*)\]",
        body,
    )
    return match.group(1).split() if match else None


def parse_camera_geometry(text: str) -> dict[str, CameraGeometry]:
    """Per-camera advertised geometry from `dumpsys media.camera` static sections (read-only)."""
    sections = CAMERA_STATIC_HEADER.split(text)
    cameras: dict[str, CameraGeometry] = {}
    for index in range(1, len(sections) - 1, 2):
        camera_id, body = sections[index], sections[index + 1]
        facing = _static_key_values(body, "android.lens.facing")
        pixel = _static_key_values(body, "android.sensor.info.pixelArraySize")
        active = _static_key_values(body, "android.sensor.info.activeArraySize")
        if not facing or not pixel or len(pixel) < 2 or not active or len(active) < 4:
            continue
        stream_block = re.search(
            r"android\.scaler\.availableStreamConfigurations \([0-9a-f]+\): int32\[[0-9]+\]"
            r"\s*\n((?:\s*\[[^\]]*\]\s*\n)+)",
            body,
        )
        raw16 = set()
        if stream_block is not None:
            for fmt, width, height, direction in _STREAM_CONFIG_ROW.findall(stream_block.group(1)):
                if int(fmt) == _HAL_FORMAT_RAW16 and direction == "OUTPUT":
                    raw16.add((int(width), int(height)))
        physical = _static_key_values(body, "android.logicalMultiCamera.physicalIds")
        cameras[camera_id] = CameraGeometry(
            camera_id=camera_id,
            facing=facing[0],
            pixel_array=(int(pixel[0]), int(pixel[1])),
            active_array=(int(active[2]) - int(active[0]), int(active[3]) - int(active[1])),
            raw16_sizes=tuple(sorted(raw16)),
            physical_ids=tuple(physical) if physical else (),
        )
    return cameras


def camera_geometry(ctx: Context) -> dict[str, CameraGeometry]:
    cameras = parse_camera_geometry(ctx.adb.shell("dumpsys media.camera", timeout=120))
    assert len(cameras) >= 3, f"dumpsys media.camera exposed only {sorted(cameras)}"
    return cameras


def rear_logical_geometry(cameras: dict[str, CameraGeometry]) -> CameraGeometry:
    """The BACK logical multicamera — the documented non-TELE photo still route."""
    logical = [cam for cam in cameras.values() if cam.facing == "BACK" and cam.physical_ids]
    assert len(logical) == 1, (
        f"expected one BACK logical multicamera, got {[cam.camera_id for cam in logical]}"
    )
    return logical[0]


def front_camera_geometry(cameras: dict[str, CameraGeometry]) -> CameraGeometry:
    fronts = [cam for cam in cameras.values() if cam.facing == "FRONT"]
    assert len(fronts) == 1, f"expected one FRONT camera, got {[cam.camera_id for cam in fronts]}"
    return fronts[0]


def still_row_geometry_errors(
    rows: list[MediaRow],
    expected: tuple[int, int],
    route_label: str,
) -> list[str]:
    """Processed still rows must equal the route's advertised binned array up to pixel rotation.

    HEIF/JPEG outputs are pixel-rotated by captureRotationDegrees (portrait swaps W/H), so the
    dimension MULTISET is the rotation-independent geometry contract.
    """
    errors = []
    processed = [row for row in rows if row.mime_type in ("image/heic", "image/jpeg")]
    if not processed:
        errors.append(f"{route_label}: capture produced no processed still row")
    for row in processed:
        if sorted((row.width, row.height)) != sorted(expected):
            errors.append(
                f"{route_label}: {row.display_name} is {row.width}x{row.height}, "
                f"advertised binned array is {expected[0]}x{expected[1]}"
            )
    return errors


def media_row_file_parity_errors(row: MediaRow, local) -> list[str]:
    """MediaStore row <-> pulled file parity: pending flag, byte size, and pixel dimensions."""
    errors = []
    if row.is_pending:
        errors.append(f"{row.display_name}: row is still pending")
    actual_bytes = local.stat().st_size
    if actual_bytes != row.size_bytes:
        errors.append(
            f"{row.display_name}: row _size={row.size_bytes} but pulled file is {actual_bytes} bytes"
        )
    if row.mime_type == "image/jpeg":
        info = media.jpeg_info(local)
        if not info["exif"]:
            errors.append(f"{row.display_name}: JPEG lacks EXIF APP1")
        if (info["width"], info["height"]) != (row.width, row.height):
            errors.append(
                f"{row.display_name}: JPEG file {info['width']}x{info['height']} "
                f"!= row {row.width}x{row.height}"
            )
    elif row.mime_type == "image/heic":
        if not media.heic_valid(local):
            errors.append(f"{row.display_name}: HEIC structure invalid")
        dimensions = media.image_dimensions(local)
        if dimensions is None:
            raise Incomplete("sips is unavailable; HEIF file dimensions were not decoded")
        if dimensions != (row.width, row.height):
            errors.append(
                f"{row.display_name}: HEIF file {dimensions} != row {row.width}x{row.height}"
            )
    elif row.mime_type == "image/x-adobe-dng":
        if not media.dng_valid(local):
            errors.append(f"{row.display_name}: DNG structure invalid")
        else:
            info = media.dng_info(local)
            # The DNG is NOT pixel-rotated (EXIF orientation tag instead); MediaStore may index
            # either orientation, so compare as a multiset and only when the row carries dims.
            if row.width > 0 and sorted((row.width, row.height)) != sorted(
                (info["width"], info["height"])
            ):
                errors.append(
                    f"{row.display_name}: DNG file {info['width']}x{info['height']} "
                    f"!= row {row.width}x{row.height}"
                )
    return errors


def video_row_parity_errors(row: MediaRow, local, info: dict) -> list[str]:
    """MediaStore row <-> pulled clip parity against the decoded container facts."""
    errors = []
    size = local.stat().st_size
    if not (row.size_bytes == size == info.get("format_size")):
        errors.append(
            f"{row.display_name}: sizes differ row={row.size_bytes} file={size} "
            f"container={info.get('format_size')}"
        )
    if (row.width, row.height) != (info.get("width"), info.get("height")):
        errors.append(
            f"{row.display_name}: dims differ row={row.width}x{row.height} "
            f"decoded={info.get('width')}x{info.get('height')}"
        )
    seconds = info.get("video_seconds")
    if row.duration_ms is None or not isinstance(seconds, Fraction) or (
        abs(Fraction(row.duration_ms, 1_000) - seconds) > Fraction(1, 2)
    ):
        errors.append(
            f"{row.display_name}: duration differs row={row.duration_ms}ms decoded={seconds}"
        )
    return errors


def exposure_parity_error(
    label: str,
    exif_exposure: Fraction | None,
    requested_ns: int,
) -> str | None:
    """EXIF ExposureTime must match the requested sensor exposure within format rounding."""
    if exif_exposure is None:
        return f"{label}: EXIF omitted ExposureTime"
    requested = Fraction(requested_ns, 1_000_000_000)
    if abs(exif_exposure - requested) > max(requested / 100, Fraction(1, 8192)):
        return (
            f"{label}: EXIF ExposureTime {float(exif_exposure):.6f}s "
            f"vs requested {float(requested):.6f}s"
        )
    return None


def video_container_policy_errors(info: dict, spec_transfer: str) -> list[str]:
    """Container color truth per ColorProfiles.kt: HLG=BT2020-limited-HLG, SDR=BT709-limited,
    log profiles=BT2020-full + SDR-class transfer. The ST2084/PQ (or HLG) mistag on a log/SDR
    stream is the documented regression this validator exists to catch."""
    if info.get("probe") != "ffprobe":
        return ["ffprobe frame decoding was unavailable"]
    errors: list[str] = []

    def exact(field: str, expected: object) -> None:
        if info.get(field) != expected:
            errors.append(f"{field}={info.get(field)!r}, expected {expected!r}")

    transfer = info.get("transfer")
    if spec_transfer == "HLG":
        exact("profile", "Main 10")
        exact("pix_fmt", "yuv420p10le")
        exact("color_range", "tv")
        exact("color_space", "bt2020nc")
        exact("primaries", "bt2020")
        exact("transfer", "arib-std-b67")
    elif spec_transfer == "SDR":
        exact("profile", "Main")
        exact("pix_fmt", "yuv420p")
        exact("color_range", "tv")
        exact("color_space", "bt709")
        exact("primaries", "bt709")
        if transfer not in SDR_CLASS_TRANSFERS:
            errors.append(
                f"transfer={transfer!r}, expected an SDR-class transfer "
                f"{sorted(SDR_CLASS_TRANSFERS)}"
            )
    elif spec_transfer in ("SLOG3", "SLOG3_CINE", "LOGC3"):
        exact("profile", "Main 10")
        exact("pix_fmt", "yuv420p10le")
        exact("color_range", "pc")
        exact("color_space", "bt2020nc")
        exact("primaries", "bt2020")
        if transfer in HDR_MISTAG_TRANSFERS:
            errors.append(
                f"transfer={transfer!r}: the documented ST2084/HLG mistag on a log stream"
            )
        elif transfer not in SDR_CLASS_TRANSFERS:
            errors.append(
                f"transfer={transfer!r}, expected an SDR-class transfer "
                f"{sorted(SDR_CLASS_TRANSFERS)}"
            )
    else:
        raise ValueError(f"unknown transfer spec: {spec_transfer!r}")
    return errors


def shutter_readout_seconds(label: str) -> Fraction | None:
    """Parse a shutter ruler readout ('1/100s', '0.5s', '4.0s', '10s') into seconds."""
    match = re.fullmatch(r"(?:Auto )?1/([0-9]+)s", label)
    if match:
        return Fraction(1, int(match.group(1)))
    match = re.fullmatch(r"(?:Auto )?([0-9]+(?:\.[0-9]+)?)s", label)
    if match:
        return Fraction(match.group(1))
    return None


# ---- settings-chip drivers (chips live inside checkable containers; see selected_option_labels)


def _open_settings_tab(ctx: Context, tab: str) -> None:
    if ctx.adb.ui().find_desc_exact("Close settings") is not None:
        _close_settings_with_back(ctx)
    ctx.adb.tap_ui(desc="Open settings")
    # The tab rail exposes labels ONLY via content-desc (device fact shared with set_photo_settings).
    ctx.adb.tap_ui(desc=tab)


def _chip_option_nodes(tree, label: str) -> list[UiNode]:
    """Text nodes that sit inside a checkable chip container — never section headers/OSD text."""
    checkable_regions = [node.bounds for node in tree.nodes if node.checkable]
    return [
        node for node in tree.nodes
        if node.text == label
        and any(_bounds_contain(region, node.bounds) for region in checkable_regions)
    ]


def _settings_chip_states(
    ctx: Context,
    labels: set[str],
    *,
    max_scrolls: int = 8,
) -> dict[str, bool]:
    """Selected-state per chip on the OPEN settings tab, rewinding then scrolling into view."""
    metrics = ctx.adb.display_metrics()
    x = metrics.width_px * 4 // 5
    top = metrics.height_px // 3
    bottom = metrics.height_px * 4 // 5
    for _ in range(8):
        ctx.adb.shell(f"input swipe {x} {top} {x} {bottom} 200")
        time.sleep(0.1)
    for _ in range(max_scrolls):
        tree = ctx.adb.ui()
        states: dict[str, bool] = {}
        for label in labels:
            if len(_chip_option_nodes(tree, label)) == 1:
                states[label] = label in selected_option_labels(tree, {label})
        if set(states) == set(labels):
            return states
        ctx.adb.shell(f"input swipe {x} {bottom} {x} {top} 250")
        time.sleep(0.35)
    raise AssertionError(f"settings chips {sorted(labels)} were not all reachable")


def _set_chip(ctx: Context, label: str, want_selected: bool) -> None:
    tree = ctx.adb.ui()
    nodes = _chip_option_nodes(tree, label)
    assert len(nodes) == 1, f"chip {label!r}: expected one option node, got {len(nodes)}"
    node = nodes[0]
    if (label in selected_option_labels(tree, {label})) == want_selected:
        return
    assert node.enabled, f"chip {label!r} is disabled; cannot change it"
    ctx.adb.tap(*node.center)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if (label in selected_option_labels(ctx.adb.ui(), {label})) == want_selected:
            return
        time.sleep(0.3)
    raise AssertionError(f"chip {label!r} did not reach selected={want_selected}")


def read_photo_output_formats(ctx: Context) -> frozenset[str]:
    _open_settings_tab(ctx, "Shoot")
    try:
        states = _settings_chip_states(ctx, set(PHOTO_OUTPUT_LABELS))
        selected = frozenset(label for label, on in states.items() if on)
        assert selected & {"HEIF", "JPEG"}, f"no processed output selected: {states}"
        return selected
    finally:
        _close_settings_with_back(ctx)


def set_photo_output_formats(ctx: Context, desired: frozenset[str]) -> None:
    assert desired & {"HEIF", "JPEG"}, f"refusing an output set without a processed still: {sorted(desired)}"
    _open_settings_tab(ctx, "Shoot")
    try:
        _settings_chip_states(ctx, set(PHOTO_OUTPUT_LABELS))  # scrolls the chips into view
        for label in ("HEIF", "JPEG", "DNG"):  # enable first — DNG requires a processed sibling
            if label in desired:
                _set_chip(ctx, label, True)
        for label in ("DNG", "JPEG", "HEIF"):  # then release extras, DNG before processed
            if label not in desired:
                _set_chip(ctx, label, False)
    finally:
        _close_settings_with_back(ctx)
    actual = read_photo_output_formats(ctx)
    assert actual == desired, (
        f"output formats did not persist: wanted {sorted(desired)}, got {sorted(actual)}"
    )


def read_selected_aspect(ctx: Context) -> str:
    _open_settings_tab(ctx, "Shoot")
    try:
        states = _settings_chip_states(ctx, set(ASPECT_LABELS))
        selected = [label for label, on in states.items() if on]
        assert len(selected) == 1, f"ambiguous aspect selection: {states}"
        return selected[0]
    finally:
        _close_settings_with_back(ctx)


def set_selected_aspect(ctx: Context, label: str) -> None:
    _open_settings_tab(ctx, "Shoot")
    try:
        _settings_chip_states(ctx, set(ASPECT_LABELS))
        _set_chip(ctx, label, True)
    finally:
        _close_settings_with_back(ctx)


def read_exposure_mode_and_step(ctx: Context) -> tuple[str, str]:
    _open_settings_tab(ctx, "Exposure")
    try:
        states = _settings_chip_states(
            ctx,
            set(EXPOSURE_MODE_LABELS) | set(EXPOSURE_STEP_EV),
        )
        modes = [label for label in EXPOSURE_MODE_LABELS if states.get(label)]
        steps = [label for label in EXPOSURE_STEP_EV if states.get(label)]
        assert len(modes) == 1 and len(steps) == 1, f"ambiguous exposure mode/step: {states}"
        return modes[0], steps[0]
    finally:
        _close_settings_with_back(ctx)


def set_exposure_mode(ctx: Context, letter: str) -> None:
    _open_settings_tab(ctx, "Exposure")
    try:
        _settings_chip_states(ctx, set(EXPOSURE_MODE_LABELS))
        _set_chip(ctx, letter, True)
    finally:
        _close_settings_with_back(ctx)


def _transfer_anchor_nodes(tree) -> list[UiNode]:
    return [node for node in tree.nodes if node.text in TRANSFER_LABELS]


def _transfer_row_sweep(ctx: Context, y: int, *, toward_end: bool) -> None:
    metrics = ctx.adb.display_metrics()
    lo, hi = metrics.width_px // 4, metrics.width_px * 3 // 4
    if toward_end:
        ctx.adb.shell(f"input swipe {hi} {y} {lo} {y} 250")
    else:
        ctx.adb.shell(f"input swipe {lo} {y} {hi} {y} 250")
    time.sleep(0.35)


def _transfer_row_y(ctx: Context) -> int:
    """Vertical position of the horizontally-scrollable transfer chip row (SDR can be off-row)."""
    metrics = ctx.adb.display_metrics()
    x = metrics.width_px * 4 // 5
    top = metrics.height_px // 3
    bottom = metrics.height_px * 4 // 5
    for _ in range(8):
        anchors = _transfer_anchor_nodes(ctx.adb.ui())
        if anchors:
            return anchors[0].center[1]
        ctx.adb.shell(f"input swipe {x} {bottom} {x} {top} 250")
        time.sleep(0.35)
    raise AssertionError("transfer chips are not reachable on the Video tab")


def read_video_codec_and_transfer(ctx: Context) -> tuple[str, str]:
    _open_settings_tab(ctx, "Video")
    try:
        codec_states = _settings_chip_states(ctx, set(VIDEO_CODEC_LABELS))
        codecs = [label for label, on in codec_states.items() if on]
        assert len(codecs) == 1, f"ambiguous codec selection: {codec_states}"
        y = _transfer_row_y(ctx)
        for _ in range(4):
            _transfer_row_sweep(ctx, y, toward_end=False)  # rewind the row to its start
        seen: set[str] = set()
        selected: set[str] = set()
        for _ in range(5):
            tree = ctx.adb.ui()
            seen |= {node.text for node in _transfer_anchor_nodes(tree)}
            selected |= selected_option_labels(tree, set(TRANSFER_LABELS))
            if seen == set(TRANSFER_LABELS):
                break
            _transfer_row_sweep(ctx, y, toward_end=True)
        assert seen == set(TRANSFER_LABELS), f"transfer chips never fully enumerated: {sorted(seen)}"
        assert len(selected) == 1, f"ambiguous transfer selection: {sorted(selected)}"
        return codecs[0], next(iter(selected))
    finally:
        _close_settings_with_back(ctx)


def select_video_transfer(ctx: Context, label: str) -> None:
    assert label in TRANSFER_LABELS, label
    _open_settings_tab(ctx, "Video")
    try:
        metrics = ctx.adb.display_metrics()
        y = _transfer_row_y(ctx)
        for _ in range(4):
            _transfer_row_sweep(ctx, y, toward_end=False)
        for _ in range(8):
            tree = ctx.adb.ui()
            node = next(
                (
                    n for n in _chip_option_nodes(tree, label)
                    if 0 <= n.center[0] < metrics.width_px
                ),
                None,
            )
            if node is not None:
                _set_chip(ctx, label, True)
                return
            _transfer_row_sweep(ctx, y, toward_end=True)
        raise AssertionError(f"transfer chip {label!r} was not reachable")
    finally:
        _close_settings_with_back(ctx)


# ---- manual ruler drivers (Fn dial cluster)


def _ruler_node(tree, ruler_desc: str) -> UiNode | None:
    """The ruler Canvas surfaces as a SeekBar via progressSemantics — the Speed/Angle toggle can
    share the same content description, so the class filter is load-bearing."""
    nodes = [
        node for node in tree.nodes
        if node.desc == ruler_desc and node.class_name.endswith("SeekBar")
    ]
    return nodes[0] if len(nodes) == 1 else None


def open_manual_dial(ctx: Context, tile: str, ruler_desc: str) -> None:
    if ctx.adb.ui().find_desc_exact("Close adjustment") is not None:
        close_manual_dial(ctx)
    ctx.adb.tap_ui(desc="Open function menu")
    ctx.adb.tap_ui(desc=tile)
    deadline = time.monotonic() + 6
    while time.monotonic() < deadline:
        tree = ctx.adb.ui()
        if tree.find_desc_exact("Close adjustment") and _ruler_node(tree, ruler_desc):
            return
        time.sleep(0.3)
    raise AssertionError(f"Fn {tile} did not open the {ruler_desc!r} adjustment ruler")


def close_manual_dial(ctx: Context) -> None:
    node = ctx.adb.ui().find_desc_exact("Close adjustment")
    if node is not None:
        ctx.adb.tap(*node.center)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if ctx.adb.ui().find_desc_exact("Close adjustment") is None:
            return
        time.sleep(0.3)
    raise AssertionError("adjustment dial did not close")


def _iso_ruler_value(tree) -> int | None:
    values = {
        int(match.group(1))
        for label in tree.all_labels()
        if (match := ISO_READOUT.fullmatch(label)) is not None
    }
    return next(iter(values)) if len(values) == 1 else None


def _shutter_ruler_value(tree) -> Fraction | None:
    values = {
        seconds
        for label in tree.all_labels()
        if (seconds := shutter_readout_seconds(label)) is not None
    }
    return next(iter(values)) if len(values) == 1 else None


def _drive_ruler_to_stop(
    ctx: Context,
    *,
    ruler_desc: str,
    read_value,
    target,
    accept,
    step_ev: float,
    label: str,
    max_attempts: int = 12,
):
    """Closed-loop tick drags on an EV-snapped ruler until ``accept(current)`` holds.

    Returns (initial_value, final_value). Every iteration re-reads the readout and re-plans, so
    slop under-shoot self-corrects; the drag SIGN is also observed and flipped if the ruler
    direction ever inverts (defensive — probed direction is LEFT = higher).
    """
    metrics = ctx.adb.display_metrics()
    tick_px = math.ceil(RULER_TICK_DP * metrics.density_dpi / 160)
    slop_px = math.ceil(RULER_SLOP_DP * metrics.density_dpi / 160)
    left_increases = True
    initial = None
    previous = None
    previous_direction = 0
    for _ in range(max_attempts):
        tree = ctx.adb.ui()
        ruler = _ruler_node(tree, ruler_desc)
        assert ruler is not None, f"{label}: ruler control {ruler_desc!r} is not on screen"
        current = read_value(tree)
        assert current is not None, f"{label}: ruler readout is not readable"
        if initial is None:
            initial = current
        if previous is not None and previous_direction != 0 and current != previous:
            moved_up = current > previous
            if moved_up != (previous_direction > 0):
                left_increases = not left_increases
        if accept(current):
            return initial, current
        ev_delta = math.log2(float(target) / float(current))
        ticks = max(1, min(24, round(abs(ev_delta) / step_ev)))
        left, top, right, bottom = ruler.bounds
        y = (top + bottom) // 2
        span = right - left - 80
        assert span > tick_px, f"{label}: ruler bounds too narrow: {ruler.bounds}"
        dx = min(ticks * tick_px + slop_px, span)
        wants_higher = ev_delta > 0
        drag_left = wants_higher == left_increases
        if drag_left:
            start_x, end_x = left + 40 + dx, left + 40
        else:
            start_x, end_x = right - 40 - dx, right - 40
        previous = current
        previous_direction = 1 if wants_higher else -1
        ctx.adb.shell(f"input swipe {start_x} {y} {end_x} {y} {max(300, dx * 2)}")
        time.sleep(0.9)
    raise AssertionError(
        f"{label}: ruler did not reach the target after {max_attempts} drags "
        f"(initial={initial}, last={previous})"
    )


def _restore_iso_value(ctx: Context, target: int, step_ev: float) -> None:
    open_manual_dial(ctx, "ISO", "ISO")
    try:
        _drive_ruler_to_stop(
            ctx,
            ruler_desc="ISO",
            read_value=_iso_ruler_value,
            target=target,
            accept=lambda current: current == target,
            step_ev=step_ev,
            label="ISO restore",
        )
    finally:
        close_manual_dial(ctx)


def _restore_shutter_value(ctx: Context, target: Fraction, step_ev: float) -> None:
    open_manual_dial(ctx, "Shutter", "Shutter speed")
    try:
        _drive_ruler_to_stop(
            ctx,
            ruler_desc="Shutter speed",
            read_value=_shutter_ruler_value,
            target=target,
            accept=lambda current: current == target,
            step_ev=step_ev,
            label="Shutter restore",
        )
    finally:
        close_manual_dial(ctx)


def _shutter_mode_toggle(tree, mode_desc: str) -> UiNode | None:
    return next(
        (
            node for node in tree.nodes
            if node.desc == mode_desc and not node.class_name.endswith("SeekBar")
        ),
        None,
    )


def _restore_shutter_mode_angle(ctx: Context) -> None:
    open_manual_dial(ctx, "Shutter", "Shutter speed")
    try:
        toggle = _shutter_mode_toggle(ctx.adb.ui(), "Shutter angle")
        assert toggle is not None, "Shutter angle toggle is not visible"
        ctx.adb.tap(*toggle.center)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            node = _shutter_mode_toggle(ctx.adb.ui(), "Shutter angle")
            if node is not None and node.selected:
                return
            time.sleep(0.3)
        raise AssertionError("Shutter angle mode was not restored")
    finally:
        close_manual_dial(ctx)


def restore_lens_preset(ctx: Context, lens: str) -> None:
    ctx.adb.tap_ui(desc=lens)
    deadline = time.time() + 6
    error = "lens restore did not settle"
    while time.time() < deadline:
        error = focal_rail_error(ctx.adb.ui(), lens)
        if error is None:
            return
        time.sleep(0.4)
    raise AssertionError(error)


def switch_camera_verified(
    ctx: Context,
    pid: int,
    *,
    after_optics_generation: int,
    expect_facing: str,
    cameras: dict[str, CameraGeometry],
) -> tuple[SessionAcceptance, ModeThreeAEvidence]:
    """Cross the facing door and prove the accepted route faces the expected way (dumpsys join)."""
    mark = ctx.adb.log_mark()
    ctx.adb.tap_ui(desc="Switch camera")
    acceptance = wait_session_acceptance(
        ctx,
        mark,
        pid,
        "PHOTO",
        after_optics_generation=after_optics_generation,
        timeout_s=20,
    )
    assert acceptance, f"{expect_facing} switch was never accepted by the camera engine"
    geometry = cameras.get(acceptance.camera_id)
    assert geometry is not None, (
        f"accepted camera {acceptance.camera_id} has no dumpsys static section"
    )
    assert geometry.facing == expect_facing, (
        f"accepted camera {acceptance.camera_id} faces {geometry.facing}, expected {expect_facing}"
    )
    evidence = wait_mode_three_a(
        ctx,
        mark,
        pid,
        "PHOTO",
        controller_id=acceptance.controller_id,
        optics_generation=acceptance.optics_generation,
        timeout_s=20,
    )
    assert evidence, f"accepted {expect_facing} route produced no owned 3A result"
    return acceptance, evidence


def _pull_and_check_rows(ctx: Context, rows: list[MediaRow]) -> list[str]:
    errors: list[str] = []
    for row in rows:
        assert_published_row(row)
        local = ctx.adb.pull(f"{MEDIA_DIR}/{row.display_name}", ctx.evidence / row.display_name)
        errors.extend(media_row_file_parity_errors(row, local))
    return errors


@test("per_lens_still_geometry", "full", mutates_settings=True, writes_media=True)
def t_per_lens_still_geometry(ctx: Context) -> None:
    """Every accepted still route delivers exactly its advertised binned array (200MP stays dormant).

    Routing facts under test (CLAUDE.md): photo rear presets are ZOOM presets on the LOGICAL
    seamless camera — the still geometry for 0.6/1/3/10x is therefore the LOGICAL camera's binned
    array, not the per-physical-lens arrays; TELE pins standalone 4 (covered by tele_dng_parity);
    the front door opens the enumerated FRONT camera.
    """
    pid = ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    cameras = camera_geometry(ctx)
    logical = rear_logical_geometry(cameras)
    front = front_camera_geometry(cameras)
    arrays = sorted({cam.pixel_array for cam in cameras.values()})
    oversized = [
        cam.camera_id for cam in cameras.values()
        if cam.pixel_array[0] * cam.pixel_array[1] > BINNED_ARRAY_MAX_PIXELS
    ]
    assert not oversized, (
        f"cameras advertise beyond-binned arrays (revisit the dormant hi-res fact): {oversized}"
    )
    assert DOCUMENTED_BINNED_ARRAYS <= set(arrays), (
        f"documented binned arrays {sorted(DOCUMENTED_BINNED_ARRAYS)} not all advertised: {arrays}"
    )
    ctx.note(
        f"advertised arrays {arrays}; logical={logical.camera_id} {logical.pixel_array}, "
        f"front={front.camera_id} {front.pixel_array} "
        f"(documented pair {sorted(DOCUMENTED_BINNED_ARRAYS)})"
    )

    mark = ctx.adb.log_mark()
    baseline = wait_mode_three_a(ctx, mark, pid, "PHOTO", timeout_s=20)
    assert baseline, "no baseline Photo 3A before the geometry sweep"
    if three_a_tele_state(baseline)[0]:
        # Rear presets are the NON-TELE photo route; leave TELE (also the suite's baseline state).
        baseline = toggle_teleconverter(ctx, pid, baseline)
        assert not three_a_tele_state(baseline)[0], "could not leave TELE before the sweep"

    rail = ctx.adb.ui()
    checked = [
        description for description in FOCAL_PRESETS
        if (node := rail.find_desc_exact(description)) is not None and node.checked
    ]
    assert len(checked) == 1, f"expected one checked lens preset, got {checked}"
    initial_lens = checked[0]

    aspect_original = read_selected_aspect(ctx)
    aspect_changed = False
    on_front = False
    try:
        if aspect_original != "4:3":
            # 4:3 is the full-readout sentinel; a 16:9 crop cannot witness array geometry.
            set_selected_aspect(ctx, "4:3")
            aspect_changed = True
        for lens in FOCAL_PRESETS:
            ctx.adb.tap_ui(desc=lens)
            deadline = time.time() + 6
            error = "lens selection did not settle"
            while time.time() < deadline:
                error = focal_rail_error(ctx.adb.ui(), lens)
                if error is None:
                    break
                time.sleep(0.4)
            assert error is None, error
            # Presets are zoom moves on the SAME logical session (no reopen), so route sanity is
            # the freshest telemetry still reporting tele=false rather than a new acceptance.
            preset_mark = ctx.adb.log_mark()
            evidence = wait_mode_three_a(ctx, preset_mark, pid, "PHOTO", timeout_s=15)
            assert evidence, f"{lens}: no 3A telemetry after the preset"
            assert not three_a_tele_state(evidence)[0], f"{lens}: preset reports tele=true"
            new = capture_still(ctx)
            errors = still_row_geometry_errors(new, logical.pixel_array, lens)
            errors.extend(_pull_and_check_rows(ctx, new))
            assert not errors, f"{lens}: " + "; ".join(errors)
            ctx.note(
                f"{lens}: logical camera {logical.camera_id} delivered "
                f"{sorted({(row.width, row.height) for row in new})} == advertised "
                f"{logical.pixel_array} (pixel-rotated)"
            )
        acceptance, front_evidence = switch_camera_verified(
            ctx,
            pid,
            after_optics_generation=baseline.optics_generation,
            expect_facing="FRONT",
            cameras=cameras,
        )
        on_front = True
        assert not three_a_tele_state(front_evidence)[0], "front route reports tele=true"
        expected_front = cameras[acceptance.camera_id].pixel_array
        new = capture_still(ctx)
        errors = still_row_geometry_errors(new, expected_front, "front")
        errors.extend(_pull_and_check_rows(ctx, new))
        assert not errors, "front: " + "; ".join(errors)
        ctx.note(
            f"front: accepted camera {acceptance.camera_id} delivered "
            f"{sorted({(row.width, row.height) for row in new})} == advertised {expected_front}"
        )
        rear_acceptance, baseline = switch_camera_verified(
            ctx,
            pid,
            after_optics_generation=acceptance.optics_generation,
            expect_facing="BACK",
            cameras=cameras,
        )
        on_front = False
        assert rear_acceptance.camera_id == logical.camera_id, (
            f"rear return accepted camera {rear_acceptance.camera_id}, "
            f"expected the logical {logical.camera_id}"
        )
    finally:
        if on_front:
            cleanup_transport_or_unsafe(
                "could not leave the front camera",
                lambda: ctx.adb.tap_ui(desc="Switch camera"),
            )
            time.sleep(2)
            tree = cleanup_transport_or_unsafe("front-return UI state unavailable", ctx.adb.ui)
            if tree.find_desc_exact("Take photo") is None:
                raise UnsafeState("front-camera return could not be proven")
        cleanup_transport_or_unsafe(
            "could not restore the entry lens preset",
            lambda: restore_lens_preset(ctx, initial_lens),
        )
        if aspect_changed:
            cleanup_transport_or_unsafe(
                "could not restore the persisted aspect",
                lambda: set_selected_aspect(ctx, aspect_original),
            )
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during the geometry sweep: {fatals[:2]}"


@test("tele_dng_parity", "full", mutates_settings=True, writes_media=True)
def t_tele_dng_parity(ctx: Context) -> None:
    """The TELE DNG is the advertised 16-bit CFA plane and its EXIF matches the manual request."""
    pid = ensure_foreground(ctx)
    ensure_photo_mode(ctx)
    cameras = camera_geometry(ctx)
    mark = ctx.adb.log_mark()
    baseline = wait_mode_three_a(ctx, mark, pid, "PHOTO", timeout_s=20)
    assert baseline, "no baseline Photo 3A before TELE entry"
    if three_a_tele_state(baseline)[0]:
        # Own the acceptance evidence by re-entering; the case ends at TC off (suite baseline).
        baseline = toggle_teleconverter(ctx, pid, baseline)
        assert not three_a_tele_state(baseline)[0], "could not reach the TC-off baseline"
    tele_mark = ctx.adb.log_mark()
    tele_evidence = toggle_teleconverter(ctx, pid, baseline)
    now_tele, now_zoom = three_a_tele_state(tele_evidence)
    assert now_tele and now_zoom >= TELE_MIN_EFFECTIVE_ZOOM, (
        f"TELE entry failed (tele={now_tele}, effZoom={now_zoom})"
    )
    acceptance = newest_session_acceptance(ctx.adb.logcat_since(tele_mark, pid), "PHOTO")
    assert acceptance is not None, "TELE toggle produced no owned acceptance"
    tele_camera = cameras.get(acceptance.camera_id)
    assert tele_camera is not None and tele_camera.facing == "BACK", (
        f"accepted TELE camera {acceptance.camera_id} has no BACK dumpsys section"
    )
    assert tele_camera.raw16_sizes, (
        f"TELE camera {acceptance.camera_id} advertises no RAW16 output"
    )
    raw_plane = max(tele_camera.raw16_sizes, key=lambda size: size[0] * size[1])
    ctx.note(
        f"TELE route camera {acceptance.camera_id}: advertised RAW16={raw_plane}, "
        f"array={tele_camera.pixel_array} (documented pair {sorted(DOCUMENTED_BINNED_ARRAYS)}; "
        f"the 2026-07-10 release note said 4080x3064 — pin the live truth)"
    )

    formats_original: frozenset[str] | None = None
    formats_desired: frozenset[str] | None = None
    mode_original: str | None = None
    step_ev: float | None = None
    speed_toggled_from_angle = False
    manual_iso_original: int | None = None
    manual_shutter_original: Fraction | None = None
    dial_open = False
    try:
        formats_original = read_photo_output_formats(ctx)
        formats_desired = frozenset(formats_original | {"JPEG", "DNG"})
        if formats_desired != formats_original:
            set_photo_output_formats(ctx, formats_desired)
        mode_original, step_label = read_exposure_mode_and_step(ctx)
        step_ev = EXPOSURE_STEP_EV[step_label]
        if mode_original != "M":
            set_exposure_mode(ctx, "M")

        open_manual_dial(ctx, "ISO", "ISO")
        dial_open = True
        iso_initial, _ = _drive_ruler_to_stop(
            ctx,
            ruler_desc="ISO",
            read_value=_iso_ruler_value,
            target=TELE_DNG_TARGET_ISO,
            accept=lambda current: current == TELE_DNG_TARGET_ISO,
            step_ev=step_ev,
            label="ISO ruler",
        )
        if mode_original == "M":
            manual_iso_original = iso_initial

        open_manual_dial(ctx, "Shutter", "Shutter speed")
        angle = _shutter_mode_toggle(ctx.adb.ui(), "Shutter angle")
        if angle is not None and angle.selected:
            speed_toggle = _shutter_mode_toggle(ctx.adb.ui(), "Shutter speed")
            assert speed_toggle is not None, "Shutter speed toggle is not visible"
            ctx.adb.tap(*speed_toggle.center)
            speed_toggled_from_angle = True
            time.sleep(0.8)
        target_s = float(TELE_DNG_TARGET_EXPOSURE_S)
        shutter_initial, _ = _drive_ruler_to_stop(
            ctx,
            ruler_desc="Shutter speed",
            read_value=_shutter_ruler_value,
            target=TELE_DNG_TARGET_EXPOSURE_S,
            # The EV ladder is anchored at 1 s, so an exact 1/100 s stop only exists for some
            # steps; accept the NEAREST stop and read the exact ns from the engine's telemetry.
            accept=lambda current: abs(math.log2(float(current) / target_s)) <= step_ev * 0.55,
            step_ev=step_ev,
            label="Shutter ruler",
        )
        if mode_original == "M":
            manual_shutter_original = shutter_initial
        close_manual_dial(ctx)
        dial_open = False

        settle_mark = ctx.adb.log_mark()
        request = None
        deadline = time.time() + 10
        while time.time() < deadline:
            evidence = newest_mode_three_a(ctx.adb.logcat_since(settle_mark, pid), "PHOTO")
            if evidence is not None:
                sensor = THREE_A_SENSOR.search(evidence.line)
                if sensor is not None and int(sensor.group(1)) == TELE_DNG_TARGET_ISO:
                    request = sensor
                    break
            time.sleep(0.5)
        assert request is not None, "manual ISO 800 never reached the repeating-request telemetry"
        requested_exposure_ns = int(request.group(2))
        assert 6_000_000 <= requested_exposure_ns <= 14_000_000, (
            f"manual shutter landed at {requested_exposure_ns}ns, expected the ~1/100s stop"
        )

        new = capture_still(ctx)
        by_extension = {
            row.display_name.rsplit(".", 1)[-1].casefold(): row for row in new
        }
        assert "dng" in by_extension, (
            f"TELE capture produced no DNG despite the enabled format: {sorted(by_extension)}"
        )
        assert "jpg" in by_extension, (
            f"TELE capture produced no JPEG sibling: {sorted(by_extension)}"
        )
        errors: list[str] = []
        pulled = {}
        for row in new:
            assert_published_row(row)
            local = ctx.adb.pull(
                f"{MEDIA_DIR}/{row.display_name}", ctx.evidence / row.display_name
            )
            pulled[row.display_name] = local
            errors.extend(media_row_file_parity_errors(row, local))

        dng = media.dng_info(pulled[by_extension["dng"].display_name])
        if (dng["width"], dng["height"]) != raw_plane:
            errors.append(
                f"DNG plane {dng['width']}x{dng['height']} != advertised RAW16 {raw_plane}"
            )
        if dng["bits_per_sample"] != 16:
            errors.append(f"DNG BitsPerSample={dng['bits_per_sample']}, expected 16")
        if dng["samples_per_pixel"] != 1:
            errors.append(f"DNG SamplesPerPixel={dng['samples_per_pixel']}, expected 1 (CFA)")
        if not dng["cfa"]:
            errors.append(f"DNG photometric={dng['photometric']}, expected CFA 32803")
        if dng["iso"] != TELE_DNG_TARGET_ISO:
            errors.append(f"DNG EXIF ISO={dng['iso']}, requested {TELE_DNG_TARGET_ISO}")
        parity = exposure_parity_error("DNG", dng["exposure_time"], requested_exposure_ns)
        if parity is not None:
            errors.append(parity)
        jpeg = media.jpeg_exif_info(pulled[by_extension["jpg"].display_name])
        if jpeg["iso"] != TELE_DNG_TARGET_ISO:
            errors.append(f"JPEG EXIF ISO={jpeg['iso']}, requested {TELE_DNG_TARGET_ISO}")
        parity = exposure_parity_error("JPEG", jpeg["exposure_time"], requested_exposure_ns)
        if parity is not None:
            errors.append(parity)
        assert not errors, "TELE DNG parity violations: " + "; ".join(errors)
        ctx.note(
            f"DNG {dng['width']}x{dng['height']} 16-bit CFA; EXIF ISO {dng['iso']} / "
            f"{float(dng['exposure_time']):.6f}s vs requested {TELE_DNG_TARGET_ISO} / "
            f"{requested_exposure_ns}ns (JPEG EXIF in parity)"
        )
        fatals = ctx.adb.fatal_lines(mark, pid)
        assert not fatals, f"errors during TELE DNG parity: {fatals[:2]}"
    finally:
        if dial_open:
            cleanup_transport_or_unsafe(
                "could not close the adjustment dial", lambda: close_manual_dial(ctx)
            )
        if step_ev is not None:
            # Manual values are the photographer's own state only in M; in P/S/ISO the app-side
            # loop rewrites them continuously, so restoring the MODE is the complete restore.
            if manual_iso_original is not None and manual_iso_original != TELE_DNG_TARGET_ISO:
                cleanup_transport_or_unsafe(
                    "could not restore the manual ISO",
                    lambda: _restore_iso_value(ctx, manual_iso_original, step_ev),
                )
            if manual_shutter_original is not None:
                cleanup_transport_or_unsafe(
                    "could not restore the manual shutter",
                    lambda: _restore_shutter_value(ctx, manual_shutter_original, step_ev),
                )
        if speed_toggled_from_angle:
            cleanup_transport_or_unsafe(
                "could not restore the shutter ANGLE mode",
                lambda: _restore_shutter_mode_angle(ctx),
            )
        if mode_original is not None and mode_original != "M":
            cleanup_transport_or_unsafe(
                "could not restore the exposure mode",
                lambda: set_exposure_mode(ctx, mode_original),
            )
        if (
            formats_original is not None
            and formats_desired is not None
            and formats_desired != formats_original
        ):
            # Restore while still on the TELE route: the DNG chip is enabled only where RAW is.
            cleanup_transport_or_unsafe(
                "could not restore the photo output formats",
                lambda: set_photo_output_formats(ctx, formats_original),
            )
        restore_teleconverter_off_verified(ctx, pid, tele_evidence)


def record_container_truth_clip(
    ctx: Context,
    pid: int,
    *,
    transfer_label: str,
    seconds: int,
) -> None:
    """One short clip under the given transfer: admitted spec, decode, color policy, row parity."""
    spec_name = TRANSFER_SPEC_NAMES[transfer_label]
    before = {row.key for row in ctx.adb.media_store_rows()}
    mark = ctx.adb.log_mark()
    admitted = None
    recording_may_be_active = True
    try:
        ctx.adb.tap_ui(desc="Start recording")
        admitted_line = ctx.adb.wait_log(mark, RECORDING_SPEC.pattern, timeout_s=12, pid=pid)
        assert admitted_line, f"{transfer_label}: recorder did not publish an admitted spec"
        admitted = RECORDING_SPEC.search(admitted_line)
        assert admitted is not None, f"{transfer_label}: malformed admission: {admitted_line}"
        assert admitted.group(9) == spec_name, (
            f"{transfer_label}: admitted transfer={admitted.group(9)}, expected {spec_name} — "
            "the selected chip did not reach the recorder"
        )
        wait_recording_running(ctx, pid)
        assert_recording_continues(ctx, pid, seconds)
        observed = stop_recording_verified(ctx, pid)
        assert observed, f"{transfer_label}: REC ended before the requested Stop"
        wait_recording_finalized(ctx, mark, pid, recording_capture_id(admitted))
        recording_may_be_active = False
    finally:
        if recording_may_be_active:
            observed = stop_recording_verified(ctx, pid)
            if admitted is None:
                admitted = recover_recording_admission(
                    ctx, mark, pid, f"container truth {transfer_label}"
                )
            if admitted is None:
                detail = "REC UI was observed" if observed else "REC start was attempted"
                raise UnsafeState(
                    f"{transfer_label}: {detail}, but admission/finalization identity is unavailable"
                )
            wait_recording_finalized(ctx, mark, pid, recording_capture_id(admitted))

    new = ctx.adb.wait_new_media_rows(before, min_new=1, timeout_s=45)
    vids = [row for row in new if row.collection == "video" and row.mime_type == "video/mp4"]
    assert len(vids) == 1 and len(new) == 1, f"{transfer_label}: unexpected row family: {new}"
    row = vids[0]
    assert_published_row(row)
    assert row.display_name == f"{admitted.group(1)}.mp4", (
        f"{transfer_label}: spec/MediaStore family mismatch: {admitted.group(1)} vs {row}"
    )
    local = ctx.adb.pull(f"{MEDIA_DIR}/{row.display_name}", ctx.evidence / row.display_name)
    info = media.mp4_probe(local)
    require_decoded_video(
        info,
        minimum_seconds=seconds - 2.0,
        expected_fps=Fraction(admitted.group(8)),
    )
    errors = media.recording_contract_errors(
        info,
        expected_codec=admitted.group(2),
        expected_width=int(admitted.group(5)),
        expected_height=int(admitted.group(6)),
        expected_audio=admitted.group(10) == "true",
    )
    errors.extend(video_container_policy_errors(info, spec_name))
    errors.extend(video_row_parity_errors(row, local, info))
    assert not errors, f"{transfer_label}: " + "; ".join(errors)
    ctx.note(
        f"{transfer_label}: {row.display_name} profile={info.get('profile')} "
        f"range={info.get('color_range')} space={info.get('color_space')} "
        f"transfer={info.get('transfer')} primaries={info.get('primaries')}"
    )


@test("video_container_truth", "full", mutates_settings=True, writes_media=True)
def t_video_container_truth(ctx: Context) -> None:
    """SDR and HLG clips (plus a persisted log preset) carry the documented container color policy."""
    pid = ensure_foreground(ctx)
    entry_tree = ctx.adb.ui()
    if entry_tree.find_desc_exact("Stop recording"):
        raise UnsafeState("container truth entered while REC was active")
    initial_mode = "VIDEO" if entry_tree.find_desc_exact("Start recording") else "PHOTO"
    suite_mark = ctx.adb.log_mark()
    require_recording_storage(
        ctx,
        REC_SOAK_MAX_MBPS,
        3 * VIDEO_TRUTH_CLIP_SECONDS,
        label="container truth clips",
    )
    ensure_video_mode(ctx)
    codec, original_transfer = read_video_codec_and_transfer(ctx)
    if codec != "HEVC":
        restore_capture_mode_verified(ctx, initial_mode, pid)
        raise Incomplete(
            f"container truth requires the HEVC preset (Transfer is HEVC-only); codec is {codec}"
        )
    # End on the persisted transfer so the final leg restores it by construction; a persisted log
    # preset adds a third leg that guards the documented log-container policy directly.
    legs = [label for label in ("SDR", "HLG") if label != original_transfer]
    legs.append(original_transfer)
    try:
        for label in legs:
            select_video_transfer(ctx, label)
            record_container_truth_clip(
                ctx,
                pid,
                transfer_label=label,
                seconds=VIDEO_TRUTH_CLIP_SECONDS,
            )
    finally:
        cleanup_transport_or_unsafe(
            "could not restore the persisted video transfer",
            lambda: select_video_transfer(ctx, original_transfer),
        )
        restore_capture_mode_verified(ctx, initial_mode, pid)
    fatals = ctx.adb.fatal_lines(suite_mark, pid)
    assert not fatals, f"errors during container truth: {fatals[:2]}"
    ctx.note(f"legs {legs} verified; persisted transfer restored to {original_transfer}")


# ---------------------------------------------------------------- reliability (the mandate)

@test(
    "rec_teardown_soak",
    "reliability",
    mutates_settings=True,
    writes_media=True,
)
def t_rec_teardown_soak(ctx: Context) -> None:
    """Five finalized clips prove recorder teardown can hand ownership to the next admission."""
    pid = ensure_foreground(ctx)
    initial_tree = ctx.adb.ui()
    if initial_tree.find_desc_exact("Stop recording"):
        raise UnsafeState("REC soak entered while recording was already active")
    if initial_tree.find_desc_exact("Start recording"):
        initial_mode = "VIDEO"
    elif initial_tree.find_desc_exact("Take photo"):
        initial_mode = "PHOTO"
    else:
        raise UnsafeState("REC soak could not prove an idle capture mode")

    suite_mark = ctx.adb.log_mark()
    recorder_idle = True
    baseline_rows: list[MediaRow] | None = None
    baseline_keys: set[tuple[str, int]] = set()
    expected_names: set[str] = set()
    preserved: list[MediaRow] | None = None
    try:
        ensure_video_mode(ctx)
        osd = selected_video_osd(ctx.adb.ui())
        if osd is None:
            raise Incomplete("REC soak requires exactly one visible video OSD specification")
        bitrate_mbps = int(osd.group("mbps"))
        if bitrate_mbps <= 0 or bitrate_mbps > REC_SOAK_MAX_MBPS:
            raise Incomplete(
                f"REC soak refuses {bitrate_mbps}Mb/s "
                f"(allowed 1..{REC_SOAK_MAX_MBPS}Mb/s)"
            )
        require_recording_storage(
            ctx,
            bitrate_mbps,
            REC_SOAK_CYCLES * REC_SOAK_SECONDS,
            label=f"REC teardown soak ({REC_SOAK_CYCLES} clips)",
        )
        baseline_rows = ctx.adb.media_store_rows()
        if any(row.is_pending for row in baseline_rows):
            raise Incomplete("REC soak requires zero pre-existing app-owned pending rows")
        baseline_keys = {row.key for row in baseline_rows}
        preserved = baseline_rows
        admissions = []
        capture_ids: list[int] = []
        for cycle in range(1, REC_SOAK_CYCLES + 1):
            mark = ctx.adb.log_mark()
            admitted = None
            recording_may_be_active = True
            recorder_idle = False
            try:
                assert ctx.adb.pid() == pid, f"cycle {cycle}: app process changed before admission"
                start = ctx.adb.ui().find_desc_exact("Start recording")
                assert start is not None and start.enabled, (
                    f"cycle {cycle}: previous recorder did not re-arm the next admission"
                )
                # Query once, then tap immediately. Pull/probe/MediaStore work is deliberately deferred
                # until all five owners have handed off, so it cannot cool down a teardown race.
                ctx.adb.tap(*start.center)
                admitted_line = ctx.adb.wait_log(mark, RECORDING_SPEC.pattern, timeout_s=12, pid=pid)
                assert admitted_line, f"cycle {cycle}: recorder did not publish an admitted spec"
                admitted = RECORDING_SPEC.search(admitted_line)
                assert admitted is not None, f"cycle {cycle}: malformed admission: {admitted_line}"
                admission_errors = recording_admission_errors(admitted, osd)
                assert not admission_errors, (
                    f"cycle {cycle}: admitted recorder differs from safe OSD preset: "
                    + "; ".join(admission_errors)
                )
                wait_recording_running(ctx, pid)
                assert_recording_continues(ctx, pid, REC_SOAK_SECONDS)
                observed_recording = stop_recording_verified(ctx, pid)
                assert observed_recording, f"cycle {cycle}: REC ended before the requested Stop"
                wait_recording_finalized(ctx, mark, pid, recording_capture_id(admitted))
                recording_may_be_active = False
                recorder_idle = True
            finally:
                if recording_may_be_active:
                    observed_recording = stop_recording_verified(ctx, pid)
                    if admitted is None:
                        admitted = recover_recording_admission(
                            ctx,
                            mark,
                            pid,
                            f"REC teardown soak cycle {cycle}",
                        )
                    if admitted is None:
                        detail = "REC UI was observed" if observed_recording else "REC start was attempted"
                        raise UnsafeState(
                            f"cycle {cycle}: {detail}, but admission/finalization identity is unavailable"
                        )
                    if all(existing.group(1) != admitted.group(1) for existing in admissions):
                        admissions.append(admitted)
                        expected_names.add(f"{admitted.group(1)}.mp4")
                    wait_recording_finalized(ctx, mark, pid, recording_capture_id(admitted))
                    recorder_idle = True

            assert admitted is not None
            if all(existing.group(1) != admitted.group(1) for existing in admissions):
                admissions.append(admitted)
                expected_names.add(f"{admitted.group(1)}.mp4")
            capture_id = recording_capture_id(admitted)
            capture_ids.append(capture_id)
            assert capture_ids == sorted(set(capture_ids)), (
                f"recording capture IDs are not unique and increasing: {capture_ids}"
            )
            ctx.note(f"cycle {cycle}/{REC_SOAK_CYCLES}: finalized for immediate hand-off")

        assert ctx.adb.pid() == pid, "app process changed after the final soak owner"
        assert ctx.adb.ui().find_desc_exact("Start recording"), (
            "final soak owner did not re-arm REC"
        )

        assert len(admissions) == REC_SOAK_CYCLES
        rows = wait_expected_new_media_rows(ctx, baseline_keys, expected_names, settle_s=2.0)
        assert len(rows) == REC_SOAK_CYCLES, (
            f"REC soak expected {REC_SOAK_CYCLES} rows, got {rows}"
        )
        assert {row.display_name for row in rows} == expected_names, (
            f"REC soak admission/MediaStore names differ: expected={sorted(expected_names)}, "
            f"actual={sorted(row.display_name for row in rows)}"
        )
        assert all(row.collection == "video" and row.mime_type == "video/mp4" for row in rows), (
            f"REC soak published a non-MP4 row: {rows}"
        )
        assert all(not row.is_pending for row in rows), f"REC soak left pending rows: {rows}"
        by_name = {row.display_name: row for row in rows}
        assert len(by_name) == REC_SOAK_CYCLES, f"REC soak published duplicate names: {rows}"

        current = ctx.adb.media_store_rows()
        regressions = existing_media_regressions(baseline_rows, current)
        assert not regressions, "; ".join(regressions)
        delta_error = exact_media_delta_error(baseline_keys, {row.key for row in rows}, current)
        assert delta_error is None, f"REC soak published unexpected rows: {delta_error}"
        preserved = current

        for cycle, admitted in enumerate(admissions, start=1):
            row = by_name[f"{admitted.group(1)}.mp4"]
            assert_published_row(row)
            local = ctx.adb.pull(
                f"{MEDIA_DIR}/{row.display_name}",
                ctx.evidence / row.display_name,
            )
            info = media.mp4_probe(local)
            require_decoded_video(
                info,
                minimum_seconds=3.5,
                expected_fps=Fraction(admitted.group(8)),
            )
            expected_width, expected_height = int(admitted.group(5)), int(admitted.group(6))
            contract_errors = media.recording_contract_errors(
                info,
                expected_codec=admitted.group(2),
                expected_width=expected_width,
                expected_height=expected_height,
                expected_audio=admitted.group(10) == "true",
            )
            assert not contract_errors, (
                f"cycle {cycle}: decoded file differs from RecordingSpec: "
                + "; ".join(contract_errors)
            )
            assert (row.width, row.height) == (expected_width, expected_height), (
                f"cycle {cycle}: MediaStore/encoder dimensions differ: {row} vs {admitted.group(0)}"
            )
            assert row.size_bytes == local.stat().st_size == info.get("format_size"), (
                f"cycle {cycle}: MediaStore/file/container size mismatch"
            )
            assert row.duration_ms is not None
            assert abs(Fraction(row.duration_ms, 1_000) - info["video_seconds"]) <= Fraction(1, 2), (
                f"cycle {cycle}: MediaStore/video duration mismatch"
            )
            ctx.note(f"cycle {cycle}/{REC_SOAK_CYCLES}: {row.display_name} fully decoded")

        current = ctx.adb.media_store_rows()
        regressions = existing_media_regressions(preserved, current)
        assert not regressions, "; ".join(regressions)
        delta_error = exact_media_delta_error(baseline_keys, {row.key for row in rows}, current)
        assert delta_error is None, f"REC soak late rows after probes: {delta_error}"
        assert not [row for row in current if row.is_pending], "REC soak left a late pending row"
        preserved = current
    finally:
        if recorder_idle:
            restore_capture_mode_verified(ctx, initial_mode, pid)
            if baseline_rows is not None:
                restored_rows = cleanup_transport_or_unsafe(
                    "REC soak post-restore MediaStore transport failed",
                    ctx.adb.media_store_rows,
                )
                regressions = existing_media_regressions(preserved, restored_rows)
                pending = [row for row in restored_rows if row.is_pending]
                delta = [row for row in restored_rows if row.key not in baseline_keys]
                exact_names = (
                    len(delta) == len(expected_names)
                    and {row.display_name for row in delta} == expected_names
                )
                if regressions or pending or not exact_names:
                    detail = [*regressions]
                    if pending:
                        detail.append(f"pending rows after mode restore: {pending}")
                    if not exact_names:
                        detail.append(
                            "post-restore delta differs: "
                            f"expected={sorted(expected_names)}, "
                            f"actual={sorted(row.display_name for row in delta)}"
                        )
                    raise UnsafeState("REC soak post-restore media check failed: " + "; ".join(detail))

    fatals = ctx.adb.fatal_lines(suite_mark, pid)
    assert not fatals, f"errors during REC teardown soak: {fatals[:2]}"
    ctx.note(f"{REC_SOAK_CYCLES} consecutive recorder owners finalized cleanly")


@test(
    "recording_snapshot_preserves_video",
    "reliability",
    mutates_settings=True,
    writes_media=True,
)
def t_recording_snapshot(ctx: Context) -> None:
    """A still captured mid-REC publishes beside a fully finalized, decodable video owner."""
    pid = ensure_foreground(ctx)
    initial_tree = ctx.adb.ui()
    if initial_tree.find_desc_exact("Stop recording"):
        raise UnsafeState("recording snapshot entered while REC was already active")
    if initial_tree.find_desc_exact("Start recording"):
        initial_mode = "VIDEO"
    elif initial_tree.find_desc_exact("Take photo"):
        initial_mode = "PHOTO"
    else:
        raise UnsafeState("recording snapshot could not prove an idle capture mode")
    suite_mark = ctx.adb.log_mark()
    recorder_idle = True
    still_attempted = False
    still_terminal = False
    still_action_started_at: float | None = None
    photo_settings_original: PhotoSettingMarkers | None = None
    photo_settings_mutated = False
    initial_rows: list[MediaRow] | None = None
    initial_keys: set[tuple[str, int]] = set()
    expected_new_names: set[str] = set()
    expected_new_keys: set[tuple[str, int]] | None = None
    preserved: list[MediaRow] | None = None
    try:
        ensure_photo_mode(ctx)
        photo_settings_original = read_photo_settings(ctx)
        if photo_settings_original.drive_option == "Timelapse":
            raise Incomplete(
                "recording snapshot will not alter a pre-existing Timelapse drive because an "
                "active interval sequence cannot be distinguished safely from an idle preset"
            )
        pre_setting_rows = ctx.adb.media_store_rows()
        if any(row.is_pending for row in pre_setting_rows):
            raise Incomplete("recording snapshot requires no still save in flight before setup")
        time.sleep(2)
        stable_pre_setting_rows = ctx.adb.media_store_rows()
        if [row.metadata_fingerprint for row in stable_pre_setting_rows] != [
            row.metadata_fingerprint for row in pre_setting_rows
        ]:
            raise Incomplete("recording snapshot observed media activity before setup")
        # Exercise the exact regression, rather than depending on the photographer already using a
        # non-default preset. Both selections are idempotent setting-chip taps and are restored below.
        photo_settings_mutated = True
        set_photo_settings(ctx, drive="Burst", timer="10s")
        configured = read_photo_settings(ctx)
        assert configured == PhotoSettingMarkers("10s", "Burst"), (
            f"could not arm the snapshot timer/drive regression preset: {configured}"
        )
        ensure_video_mode(ctx)
        osd = selected_video_osd(ctx.adb.ui())
        if osd is None:
            raise Incomplete("recording snapshot requires exactly one visible video OSD specification")
        bitrate_mbps = int(osd.group("mbps"))
        if bitrate_mbps <= 0 or bitrate_mbps > REC_SOAK_MAX_MBPS:
            raise Incomplete(
                f"recording snapshot refuses {bitrate_mbps}Mb/s "
                f"(allowed 1..{REC_SOAK_MAX_MBPS}Mb/s)"
            )
        require_recording_storage(
            ctx,
            bitrate_mbps,
            REC_SNAPSHOT_STORAGE_SECONDS,
            label="recording snapshot",
            extra_bytes=REC_SNAPSHOT_EXTRA_BYTES,
        )
        initial_rows = ctx.adb.media_store_rows()
        if any(row.is_pending for row in initial_rows):
            raise Incomplete("recording snapshot requires zero pre-existing app-owned pending rows")
        preserved = initial_rows
        initial_keys = {row.key for row in initial_rows}
        mark = ctx.adb.log_mark()
        admitted = None
        snapshot_rows: list[MediaRow] = []
        terminal_evidence: StillTerminalEvidence | None = None
        recording_row: MediaRow | None = None
        recording_may_be_active = True
        recorder_idle = False
        try:
            ctx.adb.tap_ui(desc="Start recording")
            admitted_line = ctx.adb.wait_log(mark, RECORDING_SPEC.pattern, timeout_s=12, pid=pid)
            assert admitted_line, "recording snapshot did not receive a recorder admission"
            admitted = RECORDING_SPEC.search(admitted_line)
            assert admitted is not None, f"malformed recorder admission: {admitted_line}"
            admission_errors = recording_admission_errors(admitted, osd)
            assert not admission_errors, (
                "recording snapshot admission differs from safe OSD preset: "
                + "; ".join(admission_errors)
            )
            wait_recording_running(ctx, pid)
            expected_video_name = f"{admitted.group(1)}.mp4"
            expected_new_names.add(expected_video_name)

            row_deadline = time.time() + 12
            while time.time() < row_deadline:
                candidates = [
                    row for row in ctx.adb.media_store_rows()
                    if row.display_name == expected_video_name and row.collection == "video"
                ]
                if len(candidates) == 1:
                    recording_row = candidates[0]
                    break
                if len(candidates) > 1:
                    raise UnsafeState(f"duplicate active recording rows: {candidates}")
                time.sleep(0.5)
            assert recording_row is not None, "active recorder MediaStore row never appeared"
            assert recording_row.is_pending, (
                f"active recorder row published before Stop/finalization: {recording_row}"
            )

            snapshot_deadline = time.time() + 12
            snapshot = None
            while time.time() < snapshot_deadline:
                snapshot = ctx.adb.ui().find_desc_exact("Take photo while recording")
                if snapshot is not None and snapshot.enabled:
                    break
                time.sleep(0.5)
            assert snapshot is not None and snapshot.enabled, "mid-REC still control never became ready"

            snapshot_before = {row.key for row in ctx.adb.media_store_rows()}
            snapshot_mark = ctx.adb.log_mark()
            still_attempted = True
            still_action_started_at = time.monotonic()
            ctx.adb.tap(*snapshot.center)
            terminal_evidence = wait_still_capture_terminals(
                ctx,
                snapshot_mark,
                pid,
                action_started_at=still_action_started_at,
            )
            still_terminal = True
            for family in terminal_evidence.families:
                expected_new_names.update(
                    f"{family.stem}.{extension}" for extension in family.extensions
                )
            assert terminal_evidence.started_count == 1, (
                "mid-REC snapshot did not produce exactly one sensor start: "
                f"{terminal_evidence}"
            )
            assert terminal_evidence.first_started_after_s is not None
            assert terminal_evidence.first_started_after_s <= REC_SNAPSHOT_PROMPT_SECONDS, (
                "mid-REC snapshot obeyed the Photo self-timer instead of firing promptly: "
                f"{terminal_evidence.first_started_after_s:.2f}s"
            )
            assert len(terminal_evidence.families) == 1, (
                "mid-REC snapshot obeyed/replayed a multi-shot drive: "
                f"{terminal_evidence.families}"
            )
            settled = terminal_evidence.families[0]
            assert settled.capture_id > recording_capture_id(admitted), (
                "still/video capture sequence collided or moved backward: "
                f"video={recording_capture_id(admitted)}, still={settled.capture_id}"
            )
            expected_image_names = {
                f"{settled.stem}.{extension}" for extension in settled.extensions
            }
            snapshot_rows = wait_expected_new_media_rows(
                ctx,
                snapshot_before,
                expected_image_names,
                timeout_s=30,
                settle_s=1.0,
            )
            assert len(snapshot_rows) == len(expected_image_names), (
                f"mid-REC still row cardinality differs: expected={expected_image_names}, "
                f"actual={snapshot_rows}"
            )
            assert all(row.collection == "image" for row in snapshot_rows), (
                f"mid-REC still produced an invalid family: {snapshot_rows}"
            )
            assert {row.display_name for row in snapshot_rows} == expected_image_names, (
                f"mid-REC settled/MediaStore outputs differ: {snapshot_rows}"
            )
            assert all(not row.is_pending for row in snapshot_rows), (
                f"mid-REC still left pending rows: {snapshot_rows}"
            )
            assert ctx.adb.pid() == pid, "app process changed during mid-REC still"
            assert ctx.adb.preview_is_live(), "preview froze after mid-REC still"
            post_snapshot_tree = ctx.adb.ui()
            assert post_snapshot_tree.find_desc_exact("Stop recording"), (
                "mid-REC still disrupted the active recorder UI"
            )
            assert post_snapshot_tree.find_desc_exact("Recording"), (
                "mid-REC still removed first-frame REC semantics"
            )
            # Give the post-snapshot encoder path its own intentional interval; file length is not
            # allowed to depend on incidental MediaStore/UI polling latency.
            assert_recording_continues(ctx, pid, 3.0)

            observed_recording = stop_recording_verified(ctx, pid)
            assert observed_recording, "mid-REC snapshot take ended before the requested Stop"
            wait_recording_finalized(ctx, mark, pid, recording_capture_id(admitted))
            recording_may_be_active = False
            recorder_idle = True
        finally:
            if recording_may_be_active:
                observed_recording = stop_recording_verified(ctx, pid)
                if admitted is None:
                    admitted = recover_recording_admission(ctx, mark, pid, "recording snapshot")
                if admitted is None:
                    detail = "REC UI was observed" if observed_recording else "REC start was attempted"
                    raise UnsafeState(
                        f"recording snapshot {detail}, but terminal identity is unavailable"
                    )
                expected_new_names.add(f"{admitted.group(1)}.mp4")
                wait_recording_finalized(ctx, mark, pid, recording_capture_id(admitted))
                recorder_idle = True
            if still_attempted and not still_terminal:
                terminal_evidence = wait_still_capture_terminals(
                    ctx,
                    snapshot_mark,
                    pid,
                    action_started_at=still_action_started_at,
                )
                still_terminal = True
                for family in terminal_evidence.families:
                    expected_new_names.update(
                        f"{family.stem}.{extension}" for extension in family.extensions
                    )

        assert admitted is not None and recording_row is not None
        published_video = ctx.adb.wait_published_media_row(recording_row.key, timeout_s=45, settle_s=1.0)
        assert published_video is not None and published_video.metadata_ready, (
            f"recording row did not publish completely: {published_video}"
        )
        assert_published_row(published_video)
        assert published_video.display_name == f"{admitted.group(1)}.mp4"
        assert published_video.mime_type == "video/mp4", (
            f"recording snapshot video has wrong MIME: {published_video}"
        )

        final_rows = ctx.adb.media_store_rows()
        regressions = existing_media_regressions(initial_rows, final_rows)
        assert not regressions, "; ".join(regressions)
        expected_new_keys = {published_video.key, *(row.key for row in snapshot_rows)}
        actual_new_keys = {row.key for row in final_rows if row.key not in initial_keys}
        assert actual_new_keys == expected_new_keys, (
            f"recording snapshot published unexpected rows: expected={expected_new_keys}, "
            f"actual={actual_new_keys}"
        )
        assert not [row for row in final_rows if row.is_pending], "recording snapshot left pending rows"
        preserved = final_rows

        for row in snapshot_rows:
            assert_published_row(row)
            expected_mime = expected_image_mime(row.display_name)
            assert expected_mime is not None and row.mime_type == expected_mime, (
                f"mid-REC extension/MIME mismatch: {row.display_name} -> {row.mime_type}, "
                f"expected {expected_mime}"
            )
            local = ctx.adb.pull(f"{MEDIA_DIR}/{row.display_name}", ctx.evidence / row.display_name)
            assert local.stat().st_size == row.size_bytes, (
                f"mid-REC file/MediaStore size differs: {row.display_name}"
            )
            if row.mime_type == "image/jpeg":
                info = media.jpeg_info(local)
                assert info["exif"], f"mid-REC JPEG lacks EXIF: {row.display_name}"
                assert (info["width"], info["height"]) == (row.width, row.height), (
                    f"mid-REC JPEG MediaStore/file dimensions differ: {row} vs {info}"
                )
                assert info["bytes"] == row.size_bytes, (
                    f"mid-REC JPEG parser/file size differs: {row.display_name}"
                )
            elif row.mime_type == "image/heic":
                assert media.heic_valid(local), f"mid-REC HEIC invalid: {row.display_name}"
                dimensions = media.image_dimensions(local)
                if dimensions is None:
                    raise Incomplete("sips is unavailable; mid-REC HEIF dimensions were not decoded")
                assert dimensions == (row.width, row.height), (
                    f"mid-REC HEIF MediaStore/file dimensions differ: {row} vs {dimensions}"
                )
            elif row.mime_type == "image/x-adobe-dng":
                assert media.dng_valid(local), f"mid-REC DNG invalid: {row.display_name}"
            else:
                raise AssertionError(f"mid-REC still has unexpected MIME: {row}")

        video_local = ctx.adb.pull(
            f"{MEDIA_DIR}/{published_video.display_name}",
            ctx.evidence / published_video.display_name,
        )
        video_info = media.mp4_probe(video_local)
        # Duration/decode health first; cadence goes through the still-aware contract because
        # the mid-REC still legitimately interrupts the single camera stream exactly once.
        require_decoded_video(video_info, minimum_seconds=2.5)
        cadence_errors = midrec_still_cadence_errors(
            video_info,
            expected_fps=Fraction(admitted.group(8)),
        )
        assert not cadence_errors, (
            "mid-REC video cadence violations: " + "; ".join(cadence_errors)
        )
        expected_dimensions = (int(admitted.group(5)), int(admitted.group(6)))
        contract_errors = media.recording_contract_errors(
            video_info,
            expected_codec=admitted.group(2),
            expected_width=expected_dimensions[0],
            expected_height=expected_dimensions[1],
            expected_audio=admitted.group(10) == "true",
        )
        assert not contract_errors, (
            "mid-REC video differs from RecordingSpec: " + "; ".join(contract_errors)
        )
        assert (published_video.width, published_video.height) == expected_dimensions
        assert published_video.size_bytes == video_local.stat().st_size == video_info.get("format_size")
        assert published_video.duration_ms is not None
        assert abs(
            Fraction(published_video.duration_ms, 1_000) - video_info["video_seconds"]
        ) <= Fraction(1, 2)
        final_rows = ctx.adb.media_store_rows()
        regressions = existing_media_regressions(preserved, final_rows)
        assert not regressions, "; ".join(regressions)
        delta_error = exact_media_delta_error(initial_keys, expected_new_keys, final_rows)
        assert delta_error is None, f"recording snapshot published late rows: {delta_error}"
        assert not [row for row in final_rows if row.is_pending], (
            "recording snapshot left a late pending row"
        )
        preserved = final_rows
        ctx.note(
            f"mid-REC {len(snapshot_rows)} image output(s) + {published_video.display_name} decoded"
        )
    finally:
        safe_to_restore = recorder_idle and (not still_attempted or still_terminal)
        if safe_to_restore:
            if photo_settings_original is not None and photo_settings_mutated:
                restore_capture_mode_verified(ctx, "PHOTO", pid)
                restore_photo_settings_verified(ctx, photo_settings_original)
            restore_capture_mode_verified(ctx, initial_mode, pid)
            if initial_rows is not None:
                restored_rows = cleanup_transport_or_unsafe(
                    "recording snapshot post-restore MediaStore transport failed",
                    ctx.adb.media_store_rows,
                )
                regressions = existing_media_regressions(preserved, restored_rows)
                pending = [row for row in restored_rows if row.is_pending]
                delta = [row for row in restored_rows if row.key not in initial_keys]
                exact_names = (
                    len(delta) == len(expected_new_names)
                    and {row.display_name for row in delta} == expected_new_names
                )
                if regressions or pending or not exact_names:
                    detail = [*regressions]
                    if pending:
                        detail.append(f"pending rows after mode restore: {pending}")
                    if not exact_names:
                        detail.append(
                            "post-restore delta differs: "
                            f"expected={sorted(expected_new_names)}, "
                            f"actual={sorted(row.display_name for row in delta)}"
                        )
                    raise UnsafeState(
                        "recording snapshot post-restore media check failed: " + "; ".join(detail)
                    )

    fatals = ctx.adb.fatal_lines(suite_mark, pid)
    assert not fatals, f"errors during recording snapshot: {fatals[:2]}"


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
    started_seen_at = time.monotonic()
    time.sleep(0.6)  # aim inside the write/publish window
    ctx.adb.force_stop()
    # The idle-proof uiautomator dump inside force_stop adds ~2-3 s, so the true shutter→kill
    # delta is well above the 0.6 s sleep; report the measured value (a lower bound — the
    # shutter start itself was observed with up to ~1 s of logcat poll latency).
    kill_delta_s = time.monotonic() - started_seen_at
    ctx.note(f"killed {kill_delta_s:.1f}s after the observed shutter start (0.6s aim + idle proof)")
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
    pid = ensure_foreground(ctx)
    ensure_video_mode(ctx)
    before = {row.key for row in ctx.adb.media_store_rows()}
    mark = ctx.adb.log_mark()
    ctx.adb.tap_ui(desc="Start recording")
    # Sibling rigor (rec_teardown_soak/rec_stop_then_kill): the dropped-tap device fact means a
    # bare sleep can background an idle camera and then "fail" with a misleading no-clip message.
    admitted_line = ctx.adb.wait_log(mark, RECORDING_SPEC.pattern, timeout_s=12, pid=pid)
    assert admitted_line, "backgrounded REC did not publish an admitted encoder spec"
    admitted = RECORDING_SPEC.search(admitted_line)
    assert admitted is not None, f"malformed admitted encoder spec: {admitted_line}"
    wait_recording_running(ctx, pid)
    time.sleep(4)
    ctx.adb.home()
    ctx.note("HOME pressed mid-REC")
    wait_recording_finalized(ctx, mark, pid, recording_capture_id(admitted))
    new = ctx.adb.wait_new_media_rows(before, min_new=1, timeout_s=30)
    vids = [row for row in new if row.collection == "video" and row.mime_type == "video/mp4"]
    assert vids, f"backgrounded recording produced no clip (new={new})"
    assert len(vids) == 1 and len(new) == 1, f"backgrounded REC published an unexpected family: {new}"
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
    # An honest minimum for the nominal 4 s take (the soak uses the same 4 s → 3.5 s margin);
    # 1.0 s would pass a clip truncated to a fraction of the recorded interval.
    require_decoded_video(info, minimum_seconds=3.5)
    ctx.adb.launch()
    ensure_photo_mode(ctx)
    time.sleep(6)
    stale = ctx.adb.pending_rows()
    assert not stale, f"stuck IS_PENDING leftovers after backgrounded finalize: {stale}"
    fatals = ctx.adb.fatal_lines(mark, pid)
    assert not fatals, f"errors during backgrounded finalize: {fatals[:2]}"
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
