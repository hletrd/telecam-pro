#!/usr/bin/env python3
"""Regression check for the release-facing documents.

These files have no tests because they are prose — but they DO have behaviour: store copy must fit
Play's character limits, the paste-ready blocks must stay unwrapped (Play renders line breaks
literally, so a re-wrapped block ships a listing broken mid-sentence), the two hand-maintained
privacy documents must agree on the permission set (they drifted once and shipped three undisclosed
permissions), and the version facts must match the build file rather than a stale memory of it.

Every rule here exists because the corresponding mistake actually happened in this repository.
Run before touching docs/play-store-listing.md, PRIVACY.md, or privacy-policy/index.html.
"""

from __future__ import annotations

import hashlib
import datetime
import json
import pathlib
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

try:
    from tools.release_permissions import EXPECTED_RELEASE_PERMISSIONS
except ModuleNotFoundError:  # Direct `python3 tools/check_docs.py` execution.
    from release_permissions import EXPECTED_RELEASE_PERMISSIONS

if sys.flags.optimize != 0:
    print("optimized Python is unsupported for the documentation gate", file=sys.stderr)
    raise SystemExit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
FAILURES: list[str] = []
CHECKS = 0
PRIVATE_SKIPS = 0

# These are intentional local/operator authorities ignored by git. Public committed checks must
# never depend on them being present, while a maintainer checkout that provides them must retain the
# complete stricter suite.
PRIVATE_DOCS = {
    "docs/play-store-listing.md",
    "docs/BACKLOG.md",
    "docs/TESTING.md",
    "docs/UX_POLICY.md",
    "docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md",
}
COMMITTED_AUTHORITY_DOCS = (
    "README.md",
    "CLAUDE.md",
    "docs/ARCHITECTURE.md",
    "docs/FIELD_CHECKS.md",
    "docs/play-console-submit.md",
    "docs/play-data-safety.md",
    "device-tests/README.md",
)


def check(ok: bool, label: str, detail: str = "") -> None:
    global CHECKS
    CHECKS += 1
    if ok:
        print(f"  ok    {label}")
    else:
        print(f"  FAIL  {label}{(' — ' + detail) if detail else ''}")
        FAILURES.append(label)


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


# Every production Log site receives one executable quota classification. Error/warning rows are
# the reserved fault class. Informational rows must either have a recurring-budget admission in the
# same source block or match an explicit startup/session edge below. Adding a new bare Log.i/Log.d,
# or deleting a recurring guard, therefore fails this gate instead of being hidden by occurrence
# counts elsewhere in the file.
DEBUG_ONE_SHOT_INFO_ANCHORS: dict[str, tuple[str, ...]] = {
    "app/src/main/kotlin/me/hletrd/telecampro/gl/FlipRenderer.kt": ("sourceHlg ->",),
    "app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt": ("frontMirror:",),
    "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt": (
        "CameraSessionAccepted:",
        "LensInventory:",
        "PreviewSurface:",
        "RecordingSpec:",
        "RecordingStored:",
        "RecordingAllocationRetired:",
        "MediaRecovery",
    ),
    "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt": (
        "Session configured",
        "DynamicRangeProfiles:",
        "High-speed session configured",
        "SENSOR_TIMESTAMP source=",
    ),
    "app/src/main/kotlin/me/hletrd/telecampro/camera/StartupTrace.kt": (
        "internal var emit: (String) -> Unit",
    ),
    "app/src/main/kotlin/me/hletrd/telecampro/camera/VendorTagInspector.kt": ("Log.",),
    "app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt": ("audioScene=",),
}
DEBUG_RESERVED_INFO_ANCHORS: dict[str, tuple[str, ...]] = {
    "app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt": ("FrameGap:",),
}


def debug_log_classification_inventory() -> tuple[dict[str, int], list[str]]:
    counts = {"recurring_budgeted": 0, "one_shot_session": 0, "reserved_fault": 0}
    unclassified: list[str] = []
    invocation = re.compile(r"(?:android\.util\.)?Log\.(?P<level>[vdiwe])\s*\(")
    source_root = ROOT / "app/src/main/kotlin"
    for path in sorted(source_root.rglob("*.kt")):
        relative = path.relative_to(ROOT).as_posix()
        source = path.read_text(encoding="utf-8")
        previous_end = 0
        for match in invocation.finditer(source):
            level = match.group("level")
            before = source[max(previous_end, match.start() - 1_200):match.start()]
            around = source[max(0, match.start() - 1_800):match.start() + 1_400]
            previous_end = match.end()
            if relative.endswith("/VendorTagInspector.kt"):
                counts["one_shot_session"] += 1
                continue
            if level in {"e", "w"}:
                counts["reserved_fault"] += 1
                continue
            if (
                "recurringDiagnosticAllowed" in before
                or "tapFocusDiagnosticAllowed" in before
                or "processDiagnosticLogBudget.tryAcquire" in before
            ):
                counts["recurring_budgeted"] += 1
                continue
            if any(anchor in around for anchor in DEBUG_RESERVED_INFO_ANCHORS.get(relative, ())):
                counts["reserved_fault"] += 1
                continue
            if any(anchor in around for anchor in DEBUG_ONE_SHOT_INFO_ANCHORS.get(relative, ())):
                counts["one_shot_session"] += 1
                continue
            line = source.count("\n", 0, match.start()) + 1
            unclassified.append(f"{relative}:{line}:Log.{level}")
    return counts, unclassified


def read_private(rel: str) -> str | None:
    if rel not in PRIVATE_DOCS:
        raise ValueError(f"unregistered private document: {rel}")
    path = ROOT / rel
    return path.read_text(encoding="utf-8") if path.is_file() else None


def read_if_available(rel: str) -> str | None:
    return read_private(rel) if rel in PRIVATE_DOCS else read(rel)


def skip_private(label: str, *required: str) -> None:
    global PRIVATE_SKIPS
    PRIVATE_SKIPS += 1
    missing = [rel for rel in required if not (ROOT / rel).is_file()]
    print(f"  skip  {label} — private docs absent: {', '.join(missing)}")


def fenced(text: str, heading: str) -> str:
    """The first ``` block after a heading — the copy-paste payload."""
    start = text.index(heading)
    m = re.search(r"```\n(.*?)\n```", text[start:], re.S)
    if m is None:
        raise ValueError(f"no fenced block under {heading!r}")
    return m.group(1)


def language_fenced(text: str, heading: str, language: str) -> str:
    """The first language-tagged fence after a heading."""
    start = text.index(heading)
    m = re.search(rf"```{re.escape(language)}\n(.*?)\n```", text[start:], re.S)
    if m is None:
        raise ValueError(f"no {language!r} fenced block under {heading!r}")
    return m.group(1)


def png_metadata(relative: str) -> tuple[int, int, int, int] | None:
    """Validate the bounded screenshot PNG profile and return its pixel contract."""
    data = (ROOT / relative).read_bytes()
    if len(data) < 57 or data[:8] != b"\x89PNG\r\n\x1a\n":
        return None

    offset = 8
    ihdr: tuple[int, int, int, int] | None = None
    compressed = bytearray()
    saw_plte = False
    saw_idat = False
    idat_ended = False
    saw_iend = False
    seen_ancillary: set[bytes] = set()
    while offset < len(data):
        if offset + 12 > len(data):
            return None
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        kind = data[offset + 4 : offset + 8]
        end = offset + 12 + length
        if end > len(data):
            return None
        payload = data[offset + 8 : offset + 8 + length]
        expected_crc = struct.unpack(">I", data[offset + 8 + length : end])[0]
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != expected_crc:
            return None
        if any(not (ord("A") <= byte <= ord("Z") or ord("a") <= byte <= ord("z")) for byte in kind):
            return None
        # PNG reserves bit 5 of the third type byte for future standardization; it must be zero.
        if kind[2] & 0x20:
            return None
        if offset == 8 and kind != b"IHDR":
            return None
        if kind == b"IHDR":
            if ihdr is not None or length != 13:
                return None
            width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB",
                payload,
            )
            if (
                width <= 0
                or height <= 0
                or bit_depth != 8
                or color_type not in (2, 6)
                or compression != 0
                or filtering != 0
                or interlace != 0
            ):
                return None
            ihdr = width, height, bit_depth, color_type
        elif kind == b"PLTE":
            if (
                ihdr is None
                or saw_plte
                or saw_idat
                or saw_iend
                # PNG's relative-order grammar places tRNS after PLTE. A truecolor image may omit
                # PLTE entirely, but once transparency has appeared no palette may follow it.
                or b"tRNS" in seen_ancillary
                or ihdr[3] == 6
                or length == 0
                or length > 256 * 3
                or length % 3 != 0
            ):
                return None
            saw_plte = True
        elif kind == b"IDAT":
            if ihdr is None or idat_ended or saw_iend:
                return None
            saw_idat = True
            compressed.extend(payload)
        elif kind == b"IEND":
            if length != 0 or ihdr is None or not saw_idat or saw_iend:
                return None
            saw_iend = True
            offset = end
            break
        elif kind == b"iCCP":
            separator = payload.find(b"\0")
            profile_name = payload[:separator] if separator >= 0 else b""
            compressed_profile = payload[separator + 2 :] if separator >= 0 else b""
            if (
                ihdr is None
                or saw_plte
                or saw_idat
                or kind in seen_ancillary
                or b"sRGB" in seen_ancillary
                or not 1 <= len(profile_name) <= 79
                or profile_name[:1] == b" "
                or profile_name[-1:] == b" "
                or b"  " in profile_name
                or any(byte < 32 or 126 < byte < 161 for byte in profile_name)
                or separator + 1 >= len(payload)
                or payload[separator + 1] != 0
                or not compressed_profile
            ):
                return None
            profile_inflater = zlib.decompressobj()
            try:
                profile = profile_inflater.decompress(compressed_profile, 4 * 1024 * 1024 + 1)
            except zlib.error:
                return None
            if (
                len(profile) > 4 * 1024 * 1024
                or not profile_inflater.eof
                or profile_inflater.unused_data
                or profile_inflater.unconsumed_tail
            ):
                return None
            seen_ancillary.add(kind)
        elif kind == b"sRGB":
            if (
                ihdr is None
                or saw_plte
                or saw_idat
                or kind in seen_ancillary
                or b"iCCP" in seen_ancillary
                or length != 1
                or payload[0] > 3
            ):
                return None
            seen_ancillary.add(kind)
        elif kind == b"sBIT":
            expected_length = 3 if ihdr is not None and ihdr[3] == 2 else 4
            if (
                ihdr is None
                or saw_plte
                or saw_idat
                or kind in seen_ancillary
                or length != expected_length
                or any(value == 0 or value > ihdr[2] for value in payload)
            ):
                return None
            seen_ancillary.add(kind)
        elif kind == b"tRNS":
            samples = struct.unpack(">HHH", payload) if length == 6 else ()
            if (
                ihdr is None
                or saw_idat
                or kind in seen_ancillary
                or ihdr[3] != 2
                or length != 6
                # Truecolor transparency stores three 16-bit samples even when the image has a
                # smaller bit depth. The unused high bits must be zero; accepting 0x0100 for an
                # 8-bit channel blesses a PNG that strict consumers may reject or interpret
                # differently from the release-evidence checker.
                or any(sample >= (1 << ihdr[2]) for sample in samples)
            ):
                return None
            seen_ancillary.add(kind)
        else:
            if saw_idat:
                idat_ended = True
            # The release screenshot profile deliberately rejects every unvalidated extension,
            # critical or ancillary, rather than blessing bytes a stricter consumer may reject.
            return None
        offset = end

    if not saw_iend or offset != len(data) or ihdr is None:
        return None
    width, height, bit_depth, color_type = ihdr
    channels = 3 if color_type == 2 else 4
    row_bytes = width * channels
    expected_size = height * (row_bytes + 1)
    if expected_size > 128 * 1024 * 1024:
        return None
    inflater = zlib.decompressobj()
    try:
        output_limit = expected_size + 1
        pixels = inflater.decompress(bytes(compressed), output_limit)
        remaining = output_limit - len(pixels)
        if remaining > 0:
            pixels += inflater.flush(remaining)
    except (ValueError, zlib.error):
        return None
    if (
        len(pixels) != expected_size
        or not inflater.eof
        or inflater.unused_data
        or inflater.unconsumed_tail
        or any(pixels[row * (row_bytes + 1)] > 4 for row in range(height))
    ):
        return None
    return width, height, bit_depth, color_type


# ---- store copy: Play's hard limits, and the wrapping trap -------------------------------------
listing = read_private("docs/play-store-listing.md")
submit = read("docs/play-console-submit.md")
check(
    "This is an open UX gap" not in submit
    and "Historical pre-fix observation" in submit
    and "This gap is closed in the current build" in submit,
    "historical AppOps matrix does not contradict the current blocked-camera disclosure",
)

# The phone screenshot set is device evidence, not generic artwork. Its manifest pins the exact
# checked-in bytes and the source copy those frames must show. A changed PNG or resource string must
# invalidate the set until a PMA110 recapture records immutable APK/source-manifest provenance.
screenshot_manifest_path = ROOT / "docs/assets/play/screenshots/asset-validity.json"
screenshot_manifest = json.loads(screenshot_manifest_path.read_text(encoding="utf-8"))
phone_screenshots = sorted(
    path.relative_to(ROOT).as_posix()
    for path in (ROOT / "docs/assets/play/screenshots").glob("*.png")
)
manifest_assets = screenshot_manifest.get("assets", {})
check(
    screenshot_manifest.get("schema_version") == 1
    and sorted(manifest_assets) == phone_screenshots,
    "phone screenshot manifest owns every checked-in phone PNG",
)
digest_mismatches = [
    relative
    for relative, expected in manifest_assets.items()
    if hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() != expected
]
check(
    not digest_mismatches,
    "phone screenshot bytes match the validity manifest",
    str(digest_mismatches),
)
phone_recapture = screenshot_manifest.get("required_recapture", {})
phone_png_contract = (
    *(phone_recapture.get("pixel_size") or []),
    phone_recapture.get("png_bit_depth"),
    phone_recapture.get("png_color_type"),
)
phone_geometry_mismatches = {
    relative: png_metadata(relative)
    for relative in phone_screenshots
    if png_metadata(relative) != phone_png_contract
}
check(
    phone_png_contract == (1440, 2880, 8, 2) and not phone_geometry_mismatches,
    "phone screenshot PNG bytes match the declared geometry and encoding",
    str(phone_geometry_mismatches),
)

default_strings = {
    element.attrib["name"]: "".join(element.itertext())
    for element in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
    if element.tag == "string"
}
korean_strings = {
    element.attrib["name"]: "".join(element.itertext())
    for element in ET.parse(ROOT / "app/src/main/res/values-ko/strings.xml").getroot()
    if element.tag == "string"
}
approved_nontranslatable_resources = {
    "app_name",
    "label_iso",
    "label_wb",
    "label_ss",
    "label_ev",
    "label_af",
    "label_nr",
    "label_fps",
    "label_fn",
    "label_open_gate",
    "phone_oppo_find_x9_ultra",
    "phone_oppo_find_x9_pro",
    "phone_vivo_x200_ultra",
    "phone_vivo_x300_ultra",
    "converter_hasselblad_300",
    "converter_hasselblad_230",
    "converter_zeiss_200",
    "converter_zeiss_400",
}
default_resource_root = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
korean_resource_root = ET.parse(ROOT / "app/src/main/res/values-ko/strings.xml").getroot()
actual_nontranslatable_resources = {
    element.attrib["name"]
    for element in default_resource_root
    if element.tag in {"string", "plurals"}
    and element.attrib.get("translatable") == "false"
}
check(
    actual_nontranslatable_resources == approved_nontranslatable_resources,
    "translation exceptions match the closed abbreviation and identity allow list",
    str(sorted(actual_nontranslatable_resources ^ approved_nontranslatable_resources)),
)
korean_resource_names = {
    (element.tag, element.attrib["name"])
    for element in korean_resource_root
    if element.tag in {"string", "plurals"}
}
missing_korean_resources = sorted(
    (element.tag, element.attrib["name"])
    for element in default_resource_root
    if element.tag in {"string", "plurals"}
    and element.attrib.get("translatable") != "false"
    and (element.tag, element.attrib["name"]) not in korean_resource_names
)
check(
    not missing_korean_resources,
    "every translatable string and plural has a Korean peer",
    str(missing_korean_resources),
)

# The v1.0.1 release note is retained as historical evidence, so its count must identify the source
# it was derived from rather than float as an unauditable prose number. The focused tooling test
# additionally opens this exact local Git revision and proves versionCode + XML entry count; this
# committed-export check keeps the two public authorities bound even when `.git` is deliberately
# absent from an immutable source export.
V101_KOREAN_SOURCE_COMMIT = "bcbeaf0c"
V101_KOREAN_STRING_COUNT = 126
v101_korean_count_phrase = f"{V101_KOREAN_STRING_COUNT} strings became resources"
check(
    v101_korean_count_phrase in submit
    and f"versionCode-3 pin `{V101_KOREAN_SOURCE_COMMIT}`" in submit
    and f"contains exactly {V101_KOREAN_STRING_COUNT}\n>   `<string>` entries" in submit
    and f"v1.0.1 shipped {V101_KOREAN_STRING_COUNT} Korean strings" in read("CLAUDE.md")
    and "131 strings became resources" not in submit,
    "v1.0.1 Korean count names reproducible versionCode-3 source evidence",
)
required_current_copy = screenshot_manifest.get("required_current_copy", {})
copy_mismatches = {
    key: (expected, default_strings.get(key))
    for key, expected in required_current_copy.items()
    if default_strings.get(key) != expected
}
check(not copy_mismatches, "phone screenshot recapture copy matches current resources", str(copy_mismatches))

blocking_assets = screenshot_manifest.get("blocking_assets", [])
required_recapture = phone_recapture
obsolete_visible_copy = screenshot_manifest.get("obsolete_visible_copy", {})
ready = screenshot_manifest.get("submission_ready") is True
provenance_ready = (
    re.fullmatch(r"[0-9a-f]{64}", required_recapture.get("immutable_source_manifest_digest") or "")
    and re.fullmatch(r"[0-9a-f]{64}", required_recapture.get("apk_sha256") or "")
)
stale_manifest_valid = (
    not ready
    and blocking_assets == [
        "docs/assets/play/screenshots/02-pro-settings.png",
        "docs/assets/play/screenshots/06-video-settings.png",
    ]
    and not provenance_ready
    and obsolete_visible_copy == {
        "docs/assets/play/screenshots/02-pro-settings.png": ["Shooting", "JPEG Quality"],
        "docs/assets/play/screenshots/06-video-settings.png": [
            "Transfer", "Applied to the SDR stream",
        ],
    }
)
ready_manifest_valid = (
    ready
    and not blocking_assets
    and not obsolete_visible_copy
    and bool(provenance_ready)
)
check(
    (stale_manifest_valid or ready_manifest_valid)
    and required_recapture.get("source_owner") == "immutable-debug-worktree-v1"
    and required_recapture.get("source_manifest_schema") == 2
    and required_recapture.get("device_model") == "PMA110"
    and required_recapture.get("locale") == "en-US"
    and required_recapture.get("orientation") == "portrait"
    and required_recapture.get("crop_box") == [0, 168, 1440, 3048]
    and phone_png_contract == (1440, 2880, 8, 2),
    "phone screenshot manifest records a valid fail-closed recapture state",
)

# Tablet screenshots need the same committed ownership as phone screenshots. The checked-in files
# predate immutable debug provenance, so their honest default is blocked even though their labels
# currently agree with the source resources. A future promotion needs all three source identities;
# an asset commit records stored bytes, not the APK that drew them.
tablet_screenshot_root = ROOT / "docs/assets/play/screenshots/tablet"
tablet_manifest_path = tablet_screenshot_root / "asset-validity.json"
tablet_manifest = json.loads(tablet_manifest_path.read_text(encoding="utf-8"))
tablet_screenshots = sorted(
    path.relative_to(ROOT).as_posix()
    for path in tablet_screenshot_root.glob("*.png")
)
tablet_manifest_assets = tablet_manifest.get("assets", {})
check(
    tablet_manifest.get("schema_version") == 1
    and tablet_manifest.get("asset_set") == "play-tablet-screenshots"
    and sorted(tablet_manifest_assets) == tablet_screenshots,
    "tablet screenshot manifest owns every checked-in tablet PNG",
)
tablet_digest_mismatches = [
    relative
    for relative, expected in tablet_manifest_assets.items()
    if not (ROOT / relative).is_file()
    or hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() != expected
]
check(
    not tablet_digest_mismatches,
    "tablet screenshot bytes match the validity manifest",
    str(tablet_digest_mismatches),
)
tablet_recapture = tablet_manifest.get("required_recapture", {})
tablet_png_contract = (
    *(tablet_recapture.get("pixel_size") or []),
    tablet_recapture.get("png_bit_depth"),
    tablet_recapture.get("png_color_type"),
)
tablet_geometry_mismatches = {
    relative: png_metadata(relative)
    for relative in tablet_screenshots
    if png_metadata(relative) != tablet_png_contract
}
check(
    tablet_png_contract == (1920, 1200, 8, 6) and not tablet_geometry_mismatches,
    "tablet screenshot PNG bytes match the declared geometry and encoding",
    str(tablet_geometry_mismatches),
)

tablet_required_current_copy = tablet_manifest.get("required_current_copy", {})
tablet_copy_mismatches = {
    key: (expected, default_strings.get(key))
    for key, expected in tablet_required_current_copy.items()
    if default_strings.get(key) != expected
}
check(
    bool(tablet_required_current_copy) and not tablet_copy_mismatches,
    "tablet screenshot recapture copy matches current resources",
    str(tablet_copy_mismatches),
)

tablet_blocking_assets = tablet_manifest.get("blocking_assets", [])
tablet_ready = tablet_manifest.get("submission_ready") is True
tablet_provenance_ready = all(
    re.fullmatch(pattern, tablet_recapture.get(field) or "")
    for field, pattern in (
        ("source_commit", r"[0-9a-f]{40}"),
        ("immutable_source_manifest_digest", r"[0-9a-f]{64}"),
        ("apk_sha256", r"[0-9a-f]{64}"),
    )
)
tablet_blocked_manifest_valid = (
    not tablet_ready
    and tablet_blocking_assets == tablet_screenshots
    and not tablet_provenance_ready
)
tablet_ready_manifest_valid = (
    tablet_ready
    and not tablet_blocking_assets
    and tablet_provenance_ready
)
tablet_history = tablet_manifest.get("historical_capture", {})
tablet_history_is_honest = (
    tablet_history.get("asset_commit") == "8d5f461913a124e11b7b62ae4c64c2e69faad25f"
    and tablet_history.get("source_commit") is None
    and tablet_history.get("immutable_source_manifest_digest") is None
    and tablet_history.get("apk_sha256") is None
    and "not immutable capture provenance" in tablet_history.get("note", "")
)
check(
    (tablet_blocked_manifest_valid or tablet_ready_manifest_valid)
    and tablet_history_is_honest
    and tablet_recapture.get("source_owner") == "immutable-debug-worktree-v1"
    and tablet_recapture.get("source_manifest_schema") == 2
    and tablet_recapture.get("device_class") == "sw600dp+ Android tablet"
    and tablet_recapture.get("locale") == "en-US"
    and tablet_recapture.get("orientation") == "landscape"
    and tablet_png_contract == (1920, 1200, 8, 6),
    "tablet screenshot manifest records a valid fail-closed provenance state",
)

# Launcher, Play, feature, and README assets must carry one recognizable public mark. The launcher
# used to ship an unrelated cyan-gradient lens/chevron while every public surface used this
# black/blue telescope and barrel.
launcher_bg = read("app/src/main/res/drawable/ic_launcher_background.xml")
launcher_fg = read("app/src/main/res/drawable/ic_launcher_foreground.xml")
launcher_mono = read("app/src/main/res/drawable/ic_launcher_monochrome.xml")
store_icon = read("docs/assets/play/icon-512.svg")
feature_graphic = read("docs/assets/play/feature-graphic.svg")
readme_logo = read("docs/assets/logo.svg")
for label, vector in (
    ("launcher background", launcher_bg),
    ("launcher foreground", launcher_fg),
    ("launcher monochrome", launcher_mono),
):
    ET.fromstring(vector)
    check(
        'android:width="108dp"' in vector and 'android:height="108dp"' in vector,
        f"{label} keeps the adaptive-icon 108dp viewport",
    )
check(
    "#FF0B0B0D" in launcher_bg and "gradient" not in launcher_bg.casefold(),
    "launcher uses the public mark black field",
)
check(
    all(marker in launcher_fg for marker in ("#FF4C9AFF", "M48.6,70.2", "M44.55,82.35"))
    and all(marker in store_icon for marker in ("#4C9AFF", 'x="72" y="104"', 'x="66" y="122"'))
    and all(marker in feature_graphic for marker in ("#4C9AFF", 'x="72" y="104"', 'x="66" y="122"')),
    "launcher and public assets share the blue telescope barrel mark",
)
check(
    "M48.6,70.2" in launcher_mono and "M44.55,82.35" in launcher_mono,
    "themed launcher keeps the telescope barrel silhouette",
)
check(
    'aria-label="TeleCam Pro logo"' in readme_logo and "300mm tick marks" not in readme_logo,
    "README logo metadata names the current TeleCam Pro brand",
)

def screenshot_authority_matches_manifest(authority: str) -> bool:
    section_match = re.search(
        r"Phone screenshots(.*?)(?=\n(?:#{2,6}\s+)?Tablet screenshots|\n## |\Z)",
        authority,
        re.S,
    )
    if section_match is None:
        return False
    section = section_match.group(1)
    if stale_manifest_valid:
        return "NOT SUBMISSION-READY" in section
    if ready_manifest_valid:
        return (
            "NOT SUBMISSION-READY" not in section
            and "SUBMISSION-READY" in section
        )
    return False


def stale_screenshot_semantics_are_explicit(authority: str) -> bool:
    authority = re.sub(r"\s+", " ", authority)
    return ready or all(
        obsolete in authority
        for values in obsolete_visible_copy.values()
        for obsolete in values
    )


def tablet_screenshot_authority_matches_manifest(authority: str) -> bool:
    section_match = re.search(
        r"### Tablet screenshots(.*?)(?=\n### |\n## |\Z)",
        authority,
        re.S,
    )
    if section_match is None:
        return False
    section = section_match.group(1)
    names_every_asset = all(path.rsplit("/", 1)[-1] in section for path in tablet_screenshots)
    names_manifest = "screenshots/tablet/asset-validity.json" in section
    if tablet_blocked_manifest_valid:
        return names_every_asset and names_manifest and "NOT SUBMISSION-READY" in section
    if tablet_ready_manifest_valid:
        return (
            names_every_asset
            and names_manifest
            and "NOT SUBMISSION-READY" not in section
            and "SUBMISSION-READY" in section
        )
    return False


# The console sheet is tracked and is the operator's upload authority, so its fail-closed state must
# remain checked in a clean committed export even though the unrelated store listing is private.
check(
    screenshot_authority_matches_manifest(submit),
    "committed submission sheet matches phone screenshot readiness",
)
check(
    stale_screenshot_semantics_are_explicit(submit),
    "committed submission sheet explains every stale phone screenshot",
)
check(
    tablet_screenshot_authority_matches_manifest(submit),
    "committed submission sheet matches tablet screenshot readiness",
)

if listing is None:
    skip_private("Play listing copy and screenshot-authority checks", "docs/play-store-listing.md")
else:
    check(
        screenshot_authority_matches_manifest(listing),
        "private Play listing matches phone screenshot readiness",
    )
    check(
        stale_screenshot_semantics_are_explicit(listing),
        "private Play listing explains every stale phone screenshot",
    )

    short = fenced(listing, "## Short description")
    check(len(short) <= 80, "short description <= 80 chars", f"{len(short)}")
    full = fenced(listing, "## Full description")
    check(len(full) <= 4000, "full description <= 4000 chars", f"{len(full)}")
    notes = fenced(listing, "## Release notes")
    inner = re.sub(r"</?(en-US|ko-KR)>", "", notes).strip()
    check(len(inner) <= 500, "release notes <= 500 chars", f"{len(inner)}")

    # Play applies its limits per language. Indexed on Korean headings because English headings are
    # substrings of them ("## Short description" occurs in "### Short description — ...").
    ko_short = fenced(listing, "간단한 설명 (≤80자)")
    check(len(ko_short) <= 80, "ko short description <= 80 chars", f"{len(ko_short)}")
    ko_full = fenced(listing, "자세한 설명 (≤4000자)")
    check(len(ko_full) <= 4000, "ko full description <= 4000 chars", f"{len(ko_full)}")
    ko_notes = fenced(listing, "출시 노트 (≤500자)")
    ko_inner = re.sub(r"</?ko-KR>", "", ko_notes).strip()
    check(len(ko_inner) <= 500, "ko release notes <= 500 chars", f"{len(ko_inner)}")

    def prose_line(s: str) -> bool:
        s = s.strip()
        return bool(s) and not s.startswith("•") and not s.startswith("<")

    for label, block in (("ko full description", ko_full), ("ko release notes", ko_notes)):
        lines = block.split("\n")
        wrapped = [a for a, b in zip(lines, lines[1:]) if prose_line(a) and prose_line(b)]
        check(not wrapped, f"{label} is not hard-wrapped", f"{len(wrapped)} continuation lines")

    ko_floor = re.findall(r"Android (\d+) 이상", listing)
    check(bool(ko_floor), "the Korean copy states an Android floor")

    for label, block in (("full description", full), ("release notes", notes)):
        lines = block.split("\n")
        wrapped = [
            a for a, b in zip(lines, lines[1:])
            if a.strip() and b[:1].islower() and not b.lstrip().startswith("•")
        ]
        check(not wrapped, f"{label} is not hard-wrapped", f"{len(wrapped)} continuation lines")

# ---- the two privacy documents must agree ------------------------------------------------------
privacy_md = read("PRIVACY.md")
privacy_html = read("privacy-policy/index.html")
manifest = read("app/src/main/AndroidManifest.xml")

check('<section id="en"' in privacy_html and 'lang="en"' in privacy_html,
      "published privacy page exposes an English language section")
check('<section id="ko"' in privacy_html and 'lang="ko"' in privacy_html,
      "published privacy page exposes a Korean language section")
check('hreflang="en"' in privacy_html and 'hreflang="ko"' in privacy_html,
      "published privacy page links both policy languages")

declared = set(re.findall(r"android\.permission\.([A-Z_]+)", manifest))
declared -= {"INTERNET", "ACCESS_NETWORK_STATE"}  # removed at merge by tools:node="remove"
expected_declared = {
    permission.removeprefix("android.permission.")
    for permission in EXPECTED_RELEASE_PERMISSIONS
}
check(
    declared == expected_declared,
    "source manifest permissions match the closed release and privacy authority",
    str(sorted(declared ^ expected_declared)),
)

# Both documents describe permissions in prose, so match on the user-facing groups they name.
GROUPS = {
    "CAMERA": ("Camera",),
    "RECORD_AUDIO": ("Microphone",),
    "READ_MEDIA_IMAGES": ("Photos and videos",),
    "READ_MEDIA_VIDEO": ("Photos and videos",),
    "READ_MEDIA_VISUAL_USER_SELECTED": ("Photos and videos",),
}
for perm in sorted(declared):
    names = GROUPS.get(perm)
    if not names:
        check(False, f"{perm} has no known privacy-doc wording", "add it to GROUPS in this script")
        continue
    for doc_label, doc in (("PRIVACY.md", privacy_md), ("privacy-policy/index.html", privacy_html)):
        check(any(n in doc for n in names), f"{doc_label} discloses {perm}")

# Product scope is part of the public privacy contract too. The Markdown authority already covered
# tablets while the published HTML silently narrowed itself back to phones, even though the app's
# large-screen path and permissions apply to both device classes.
privacy_scope = "Android phones and tablets"
for doc_label, doc in (("PRIVACY.md", privacy_md), ("privacy-policy/index.html", privacy_html)):
    check(privacy_scope in doc, f"{doc_label} covers Android phones and tablets")

# Owner-null MediaStore rows are deliberately restorable as unverified legacy-format candidates.
# Both privacy authorities must retain that boundary and must not revive the contradictory promise
# that the gallery reads only captures the current installation saved itself.
owner_null_disclosure = (
    "package-owned",
    "ownerless",
    "cannot verify who created it",
    "origin unverified",
    "limits deletion to that file",
)
own_captures_only = "looks only for captures it saved itself"
normalized_privacy_docs = tuple(
    re.sub(r"\s+", " ", doc).casefold()
    for doc in (privacy_md, privacy_html)
)
check(
    all(
        all(phrase in doc for phrase in owner_null_disclosure)
        and own_captures_only not in doc
        for doc in normalized_privacy_docs
    ),
    "privacy docs disclose ownerless legacy candidates without an own-captures-only claim",
)

privacy_presentations = {
    "PRIVACY.md": re.sub(r"\s+", " ", privacy_md).casefold(),
    "privacy-policy/index.html": re.sub(r"\s+", " ", privacy_html).casefold(),
    "Korean published policy": re.sub(r"\s+", " ", privacy_html),
    "English in-app policy": default_strings["privacy_fallback_body"].casefold(),
    "Korean in-app policy": korean_strings["privacy_fallback_body"],
}
privacy_metadata_facts = {
    "PRIVACY.md": ("camera make and model", "lens", "exposure", "time of the shot", "no location"),
    "privacy-policy/index.html": (
        "camera make and model", "lens", "exposure", "time of the shot", "no location",
    ),
    "Korean published policy": ("카메라 제조사와 모델", "렌즈", "노출", "촬영 시간", "위치 정보는 들어가지"),
    "English in-app policy": ("camera", "lens", "exposure", "time metadata", "no location"),
    "Korean in-app policy": ("카메라", "렌즈", "노출", "촬영 시간", "위치 정보는 포함되지"),
}
privacy_library_facts = {
    "PRIVACY.md": ("read your library on this device", "does not send anything anywhere"),
    "privacy-policy/index.html": (
        "read your library on this device", "does not send anything anywhere",
    ),
    "Korean published policy": ("이 기기에서 라이브러리를 읽을 수 있지만", "어떤 데이터도 전송하지 않습니다"),
    "English in-app policy": (
        "reads your library on this device only", "does not send anything anywhere",
    ),
    "Korean in-app policy": ("기기에서만 라이브러리를 읽으며", "어디에도 전송하지 않습니다"),
}
for label, presentation in privacy_presentations.items():
    check(
        all(fact in presentation for fact in privacy_metadata_facts[label]),
        f"{label} discloses capture metadata and no location",
    )
    check(
        all(fact in presentation for fact in privacy_library_facts[label]),
        f"{label} discloses on-device library read without transmission",
    )

# ---- version facts must match the build, not a memory of it ------------------------------------
gradle = read("app/build.gradle.kts")
min_sdk = re.search(r"minSdk\s*=\s*(\d+)", gradle).group(1)

# The README carried three different Compose versions at once: the badge, its toolchain table, and
# the catalog pin. Make the catalog the source of truth and compare both public surfaces plus the
# project authority table against it.
catalog = read("gradle/libs.versions.toml")
catalog_agp = re.search(r'^agp\s*=\s*"([^"]+)"', catalog, re.MULTILINE)
if catalog_agp is None:
    raise ValueError("agp missing from version catalog")
agp_version = catalog_agp.group(1)
catalog_compose = re.search(r'^composeBom\s*=\s*"([^"]+)"', catalog, re.MULTILINE)
if catalog_compose is None:
    raise ValueError("composeBom missing from version catalog")
compose_version = catalog_compose.group(1)
readme = read("README.md")
readme_compose_table = re.search(r"^\| Compose BOM \| ([^|]+) \|$", readme, re.MULTILINE)
readme_compose_badge = re.search(r"Jetpack%20Compose-([0-9]{4}\.[0-9]{2})-", readme)
claude_compose_table = re.search(r"^\| Compose BOM \| ([^|]+) \|", read("CLAUDE.md"), re.MULTILINE)
check(
    bool(readme_compose_table and readme_compose_table.group(1).strip() == compose_version),
    "README Compose table matches version catalog",
    f"catalog={compose_version}, table={readme_compose_table.group(1).strip() if readme_compose_table else '?'}",
)
check(
    bool(readme_compose_badge and compose_version.startswith(readme_compose_badge.group(1) + ".")),
    "README Compose badge matches version catalog series",
    f"catalog={compose_version}, badge={readme_compose_badge.group(1) if readme_compose_badge else '?'}",
)
check(
    bool(claude_compose_table and claude_compose_table.group(1).strip() == compose_version),
    "CLAUDE Compose table matches version catalog",
    f"catalog={compose_version}, table={claude_compose_table.group(1).strip() if claude_compose_table else '?'}",
)

architecture_version_doc = read("docs/ARCHITECTURE.md")
agp_consumers = {
    "README": re.search(r"^\| AGP \| ([^|]+) \|$", readme, re.MULTILINE),
    "CLAUDE": re.search(r"^\| AGP \| ([^|]+) \|", read("CLAUDE.md"), re.MULTILINE),
    "Architecture": re.search(r"\bAGP ([0-9]+(?:\.[0-9]+)+),", architecture_version_doc),
}
check(
    all(match and match.group(1).strip() == agp_version for match in agp_consumers.values()),
    "all active AGP references match the version catalog",
    ", ".join(
        f"{label}={match.group(1).strip() if match else '?'}"
        for label, match in agp_consumers.items()
    ),
)

zsl_source = read("app/src/main/kotlin/me/hletrd/telecampro/camera/ZslAdmission.kt")
zsl_age_ns_match = re.search(r"ZSL_MAX_FRAME_AGE_NS\s*=\s*([0-9_]+)L", zsl_source)
if zsl_age_ns_match is None:
    raise ValueError("ZSL_MAX_FRAME_AGE_NS missing")
zsl_age_ns = int(zsl_age_ns_match.group(1).replace("_", ""))
if zsl_age_ns % 1_000_000 != 0:
    raise ValueError("ZSL frame age must be an exact millisecond fact")
zsl_age_ms = zsl_age_ns // 1_000_000
zsl_rejection_match = re.search(
    r"ageNs\s*(>=|>)\s*ZSL_MAX_FRAME_AGE_NS",
    zsl_source,
)
if zsl_rejection_match is None:
    raise ValueError("ZSL frame-age rejection comparator missing")
zsl_doc_comparator = "<" if zsl_rejection_match.group(1) == ">=" else "<="
zsl_consumers = {
    "CLAUDE": re.search(r"age\s*(<=|<)\s*(\d+) ms", read("CLAUDE.md")),
    "Architecture": re.search(r"age\s*(<=|<)\s*(\d+) ms", architecture_version_doc),
}
check(
    all(
        match and match.group(1) == zsl_doc_comparator and int(match.group(2)) == zsl_age_ms
        for match in zsl_consumers.values()
    ) and bool(
        re.search(
            rf"mid-clip snapshot serve a frame up to {zsl_age_ms} ms old",
            read("app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt"),
        ),
    ),
    "active pseudo-ZSL freshness references match executable truth",
    ", ".join(
        f"{label}={match.group(1) + match.group(2) + 'ms' if match else '?'}"
        for label, match in zsl_consumers.items()
    ),
)

normalized_architecture = re.sub(r"\s+", " ", architecture_version_doc)
unresolved_field_references = [
    match.group(0).strip()
    for pattern in (
        r"[^.]{0,160}\b(?:remains|still) open\b[^.]{0,160}FIELD_CHECKS\.md",
        r"[^.]{0,160}FIELD_CHECKS\.md[^.]{0,160}\b(?:remains|still) open\b",
    )
    for match in re.finditer(pattern, normalized_architecture, re.I)
    if re.search(r"\b[A-E]\d\b", match.group(0)) is None
]
check(
    not unresolved_field_references,
    "active open FIELD_CHECKS references name a runnable field-check identity",
    " | ".join(unresolved_field_references),
)

for rel in (
    "docs/play-store-listing.md", "docs/play-console-submit.md", "README.md",
    # Added after an audit found the stale floor in BOTH of these while this check was green: it
    # only looked at store-facing docs, and the as-built authority is exactly where a wrong floor
    # does the most damage.
    "docs/ARCHITECTURE.md", "CLAUDE.md",
):
    text = read_if_available(rel)
    if text is None:
        skip_private(f"{rel} states the real Android floor", rel)
        continue
    # "Requires Android 16" style claims are what the audit caught; tie them to the real floor.
    android_release = {33: "13", 34: "14", 35: "15", 36: "16"}[int(min_sdk)]
    # Ignore occurrences inside quotation marks: the listing doc records the WRONG old wording in
    # its audit note ("Requires Android 16" — the floor is minSdk 33), and flagging a doc for
    # quoting its own corrected mistake is the checker being fooled by its own subject matter.
    bad = re.findall(r'(?<!")Requires Android (\d+)', text)
    # The Korean copy states the same fact as "Android 13 이상이 필요합니다"; without this it could
    # drift below the English floor unnoticed, since nothing else in the file is in Hangul.
    bad += re.findall(r"Android (\d+) 이상", text)
    check(
        all(v == android_release for v in bad),
        f"{rel} states the real Android floor",
        f"says {bad}, minSdk {min_sdk} = Android {android_release}",
    )

# ---- the upload instruction must not name a superseded artifact -------------------------------
# It did: step 1 hard-coded a hash that went stale while the pin above it moved, so the sheet told
# the operator to upload a bundle its own superseded list forbids — caught mid-upload.
# The release board and operator sheet are independent prose authorities, so bind their current
# state with one deliberately boring machine-readable marker. "Target" names source intent;
# "artifact" names whether immutable upload bytes exist. Conflating those two is how the board
# called v1.0.2 a candidate while the sheet correctly said there were no current bytes.
version_name_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
if version_name_match is None:
    raise ValueError("versionName missing from app/build.gradle.kts")
expected_release_target = f"v{version_name_match.group(1)}"


def release_state(text: str) -> tuple[str, str] | None:
    marker = re.search(
        r"<!--\s*release-state:\s*target=([^\s]+)\s+artifact=([^\s]+)\s*-->",
        text,
    )
    return marker.groups() if marker else None


backlog = read_private("docs/BACKLOG.md")
backlog_release_state = release_state(backlog) if backlog is not None else None
submit_release_state = release_state(submit)
submit_prose = re.sub(r"[^a-z0-9./=-]+", " ", submit.casefold())
check(
    submit_release_state == (expected_release_target, "none"),
    "committed submission authority matches target and no-current-artifact state",
    f"build={expected_release_target}, submit={submit_release_state}",
)
check(
    "source/release target" in submit_prose
    and "no current artifact candidate exists" in submit_prose,
    "committed submission prose distinguishes source target from artifact candidate",
)
if backlog is None:
    skip_private("release board agrees with committed submission authority", "docs/BACKLOG.md")
else:
    backlog_prose = re.sub(r"[^a-z0-9./=-]+", " ", backlog.casefold())
    check(
        backlog_release_state == submit_release_state,
        "release authorities agree on target and no-current-artifact state",
        f"backlog={backlog_release_state}, submit={submit_release_state}",
    )
    check(
        "current source/release target" in backlog_prose
        and "no current artifact candidate exists" in backlog_prose,
        "private release board distinguishes source target from artifact candidate",
    )

# The active external-action board once preserved its July GitHub About copy as if it were current,
# including the now-false claim that DNG existed only in TELE. Historical investigation sections may
# retain old route facts; this guard scopes the prohibition to the live owner-action section.
if backlog is None:
    skip_private("active GitHub About record rejects superseded route copy", "docs/BACKLOG.md")
else:
    owner_actions_match = re.search(
        r"\*\*Owner actions outside this repo.*?:\*\*(.*?)\n## Residual Field Checks",
        backlog,
        re.S,
    )
    owner_actions = owner_actions_match.group(1) if owner_actions_match else ""
    check(
        bool(owner_actions_match)
        and "DNG only exists in TELE mode" not in owner_actions
        and "Now: *\"Manual camera for the OPPO" not in owner_actions
        and "DNG stills in tele mode” is **SUPERSEDED history**" in owner_actions
        and "RAW/DNG on any lens advertising it" in owner_actions,
        "active GitHub About record rejects the superseded TELE-only DNG claim",
    )

# Direct Gradle release entry points are developer-only: only the immutable-source wrapper may
# publish release evidence. Historical evidence may retain its old commands/paths when the containing
# heading explicitly says historical/superseded/archived. Active mentions of mutable app/build output
# are permitted only when the same local paragraph explicitly rejects that path as an identity; they
# must never promise it as the result of a current release command.
HISTORICAL_HEADING = re.compile(r"historical|superseded|archived", re.I)


def markdown_lines_with_history(text: str) -> list[tuple[str, bool]]:
    active_headings: dict[int, str] = {}
    result: list[tuple[str, bool]] = []
    in_fence = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
        heading = None if in_fence else re.match(r"^(#{1,6})\s+(.+)$", line)
        if heading:
            level = len(heading.group(1))
            active_headings = {
                depth: value for depth, value in active_headings.items() if depth < level
            }
            active_headings[level] = heading.group(2)
        historical = any(HISTORICAL_HEADING.search(value) for value in active_headings.values())
        result.append((line, historical))
    return result


release_docs = (
    "docs/play-store-listing.md",
    "docs/play-console-submit.md",
    "docs/BACKLOG.md",
    "docs/TESTING.md",
    "README.md",
    "CLAUDE.md",
)
bare_release_gradle: list[str] = []
stale_release_promises: list[str] = []
for rel in release_docs:
    text = read_if_available(rel)
    if text is None:
        skip_private(f"{rel} active release-path audit", rel)
        continue
    lines = markdown_lines_with_history(text)
    for index, (line, historical) in enumerate(lines):
        if historical:
            continue
        if re.search(
            r"(?:^|[ `$])(?:\./)?gradlew[^\n]*(?:lint|assemble|bundle|package)Release",
            line,
        ):
            bare_release_gradle.append(f"{rel}:{index + 1}")
        if re.search(r"app/build/outputs/(?:apk|bundle|logs)/release", line):
            context = " ".join(
                item[0] for item in lines[max(0, index - 2):min(len(lines), index + 3)]
            ).casefold()
            if not any(
                qualification in context
                for qualification in (
                    "mutable", "reject", "do not upload", "never upload", "not an immutable",
                )
            ):
                stale_release_promises.append(f"{rel}:{index + 1}")
check(
    not bare_release_gradle,
    "active docs use no bare Gradle release entry point",
    f"{bare_release_gradle}",
)
check(
    not stale_release_promises,
    "active docs make no stale app/build release-output promise",
    f"{stale_release_promises}",
)
for rel in ("docs/play-store-listing.md", "docs/play-console-submit.md", "docs/BACKLOG.md"):
    text = read_if_available(rel)
    if text is None:
        skip_private(f"{rel} routes active release builds through the immutable wrapper", rel)
    else:
        check(
            "tools/build_immutable_release.py" in text,
            f"{rel} routes active release builds through the immutable wrapper",
        )

# Only the do-not-upload bullets define "superseded". Reading every parenthesised short digest in the
# file instead swept in ordinary prose — the device matrix names the CURRENT artifact that way — and
# reported the live pin as superseded.
superseded = set()
for block in re.findall(r"Superseded candidates \(do NOT upload\):(.*?)(?:\n\n|\n#)", submit, re.S):
    superseded |= set(re.findall(r"`([0-9a-f]{8})…`", block))
# The signing CERTIFICATE fingerprint is a different kind of hash and is meant to recur — it is the
# proof that the upload key did not change. Only ARTIFACT digests are at risk of going stale here.
cert = set(re.findall(r"certificate\s+SHA-256[^`]*`([0-9a-f]{8})", submit, re.I))
sequence = submit[submit.index("## Manual Console Sequence"):]
named = set(re.findall(r"`([0-9a-f]{8})…`", sequence)) - cert
check(not (named & superseded), "console sequence names no superseded artifact", f"{named & superseded}")
check(not named, "console sequence hard-codes no artifact hash", f"{named}")

# Upload-ready and not-ready sheets have deliberately different contracts. A ready sheet must pin
# one commit/digest section. A not-ready sheet must not accidentally retain that blessing and must
# route the operator through the immutable identity checker instead of mutable Gradle output.
upload_ready = "✅ UPLOAD-READY" in submit
not_ready = "🚫 NOT UPLOAD-READY" in submit
check(upload_ready != not_ready, "submission sheet has exactly one release readiness state")
if upload_ready:
    banner = re.search(r"UPLOAD-READY \([\d-]+\)[^\n]*`main` at `([0-9a-f]{7})`", submit)
    pin_match = re.search(r"^### .*\bupload artifacts\b", submit, re.M)
    check(bool(banner and pin_match), "the ready pin states its commit in both places")
    if banner and pin_match:
        pin_start = pin_match.start()
        next_heading = submit.find("\n### ", pin_start + 1)
        pin_section = submit[pin_start:next_heading if next_heading >= 0 else len(submit)]
        heading = re.search(r"`main` at `([0-9a-f]{7})`", pin_section)
        check(bool(heading and banner.group(1) == heading.group(1)),
              "banner and artifact heading name the same commit")
        pinned = set(re.findall(r"([0-9a-f]{64})", pin_section))
        check(
            not any(p[:8] in superseded for p in pinned),
            "no pinned artifact is also listed as superseded",
            f"{[p[:8] for p in pinned if p[:8] in superseded]}",
        )
else:
    check(not_ready, "submission sheet explicitly says not upload-ready")
    check(
        "python3 tools/check_release_artifact.py" in sequence,
        "not-ready console sequence requires immutable artifact checker",
    )
    check(
        "app/build/outputs" in sequence and "reject" in sequence.casefold(),
        "not-ready console sequence rejects mutable Gradle output",
    )

# Only the current pin may claim to be the newest signed cut. A historical section that keeps that
# claim in its HEADING outlives its own truth silently — this one survived two later signed cuts
# while still titled "Last SIGNED artifacts".
stale_claims = [
    h for h in re.findall(r"^#{3,4} (.+)$", submit, re.M)
    if re.search(r"\b(last|latest|current)\b.{0,20}\b(signed|artifact|cut)\b", h, re.I)
    and "SUPERSEDED" not in h.upper()
]
check(not stale_claims, "no historical heading claims to be the current cut", f"{stale_claims}")

# ---- no doc may carry a running cross-reference to a mutable count ------------------------------
# A dated record keeps the number it measured — that is evidence. What cannot be maintained is a
# parenthetical like "(main is now 1329)" inside such a record: it drifts the moment a test lands,
# and one had already gone stale by 35 tests when this check was written.
for rel in ("docs/play-console-submit.md", "docs/BACKLOG.md", "README.md"):
    text = read_if_available(rel)
    if text is None:
        skip_private(f"{rel} carries no running count cross-reference", rel)
        continue
    running = re.findall(r"\(?`?main`? is now [\d,]+", text)
    check(not running, f"{rel} carries no running count cross-reference", f"{running}")

# ---- referenced repo paths must exist -----------------------------------------------------------
claude = read("CLAUDE.md")
private_context_fragments = (
    "**optional in clean clones**",
    "its absence must not block work",
    "self-contained fallback authority",
)
check(
    all(fragment in claude for fragment in private_context_fragments),
    "CLAUDE marks absent private context optional with committed fallbacks",
    f"missing {[fragment for fragment in private_context_fragments if fragment not in claude]}",
)

for rel in (*COMMITTED_AUTHORITY_DOCS, "docs/TESTING.md"):
    text = read_if_available(rel)
    if text is None:
        skip_private(f"{rel} references only files that exist", rel)
        continue
    refs = re.findall(r"`((?:app|docs|tools|device-tests|gradle)/[A-Za-z0-9_./-]+\.(?:kt|md|py|txt|toml|kts))`", text)
    missing_private_refs = sorted({r for r in refs if r in PRIVATE_DOCS and not (ROOT / r).exists()})
    dead = [
        r for r in refs
        if "..." not in r and r not in PRIVATE_DOCS and not (ROOT / r).exists()
    ]
    check(not dead, f"{rel} references only files that exist", f"{dead}")
    if missing_private_refs:
        skip_private(f"{rel} private references resolve in maintainer checkout", *missing_private_refs)

# The private backlog may carry richer maintainer history, but every committed mention must remain
# usable when that file is absent. Qualify at paragraph scope so one global disclaimer cannot make a
# distant imperative such as "record results in BACKLOG" look clone-safe.
unqualified_backlog_refs: list[str] = []
for rel in COMMITTED_AUTHORITY_DOCS:
    for paragraph_index, paragraph in enumerate(re.split(r"\n\s*\n", read(rel)), start=1):
        if "docs/BACKLOG.md" not in paragraph:
            continue
        normalized = re.sub(r"\s+", " ", paragraph).casefold()
        if "optional" not in normalized or not any(
            qualifier in normalized for qualifier in ("when present", "clean clone")
        ):
            unqualified_backlog_refs.append(f"{rel}:paragraph-{paragraph_index}")
check(
    not unqualified_backlog_refs,
    "all committed backlog references are locally optional in clean clones",
    str(unqualified_backlog_refs),
)

field_checks = read("docs/FIELD_CHECKS.md")
field_results = field_checks.split("## Recording results", 1)[1]
check(
    "This committed file is the clean-clone field-results ledger" in field_results
    and "Record a new result in the matching" in field_results
    and "optional private `docs/BACKLOG.md`" in field_results
    and "its absence never blocks recording evidence here" in field_results,
    "FIELD_CHECKS provides a committed result ledger when private backlog is absent",
)

# The dashboard is an index, not a second hand-maintained opinion. Every body check must appear once,
# and every OPEN/HALF DONE body obligation must be represented by an open/partial dashboard symbol.
status_match = re.search(r"^\*\*Status \([^)]+\):\*\* (.+)\.$", field_checks, re.MULTILINE)
dashboard_entries = (
    re.findall(r"\b([A-Z]\d+)\s+(✅|◐|☐|◯)", status_match.group(1))
    if status_match else []
)
body_headings = re.findall(r"^### ([A-Z]\d+)\. (.+)$", field_checks, re.MULTILINE)
dashboard_ids = [identity for identity, _ in dashboard_entries]
body_ids = [identity for identity, _ in body_headings]
normalized_dashboard_entries = [
    (identity, "☐" if symbol == "◯" else symbol)
    for identity, symbol in dashboard_entries
]
expected_body_entries = [
    (
        identity,
        "◐" if "◐ HALF DONE" in heading else "☐" if "◯ OPEN" in heading else "✅",
    )
    for identity, heading in body_headings
]
open_dashboard_ids = [
    identity for identity, symbol in dashboard_entries if symbol in {"◐", "☐", "◯"}
]
open_body_ids = [
    identity for identity, heading in body_headings
    if "◯ OPEN" in heading or "◐ HALF DONE" in heading
]

# Top-level authority may create a manual evidence obligation outside this ledger. Every active
# "remains a field check" claim must point to one exact OPEN/HALF-DONE body identity, or the
# supposedly exhaustive dashboard can go green while shipping work remains unscheduled.
normalized_claude = re.sub(r"\s+", " ", read("CLAUDE.md"))
active_field_claims = re.findall(
    r"\bremains a field check\b.{0,180}",
    normalized_claude,
    re.I,
)
claim_bindings = [
    re.search(r"docs/FIELD_CHECKS\.md`?\s+([A-Z]\d+)", claim)
    for claim in active_field_claims
]
check(
    bool(active_field_claims)
    and all(
        binding is not None and binding.group(1) in open_body_ids
        for binding in claim_bindings
    ),
    "active CLAUDE field-check claims bind to open ledger identities",
    " | ".join(active_field_claims),
)
check(
    bool(status_match)
    and dashboard_ids == body_ids
    and len(dashboard_ids) == len(set(dashboard_ids)),
    "field dashboard names every body check exactly and in order",
    f"dashboard={dashboard_ids} body={body_ids}",
)
remain_words = {
    "One": 1, "Two": 2, "Three": 3, "Four": 4, "Five": 5,
    "Six": 6, "Seven": 7, "Eight": 8, "Nine": 9, "Ten": 10,
}
remain_match = re.search(r"^(One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten) remain:", field_checks, re.MULTILINE)
check(
    normalized_dashboard_entries == expected_body_entries
    and open_dashboard_ids == open_body_ids
    and bool(remain_match)
    and remain_words[remain_match.group(1)] == len(open_body_ids),
    "field dashboard open membership and prose count match the body",
    f"dashboard={normalized_dashboard_entries} body={expected_body_entries} count={remain_match.group(1) if remain_match else '?'}",
)

# A current PASS/CONFIRMED heading may not contain an unresolved evidence qualifier in its own
# section. Historical/superseded text belongs under an explicitly closed or historical heading.
confirmed_with_unresolved_body: list[str] = []
heading_matches = list(re.finditer(r"^### ([A-Z]\d+)\. (.+)$", field_checks, re.MULTILINE))
for index, heading in enumerate(heading_matches):
    title = heading.group(2)
    if not re.search(r"✅.*(?:PASSED|CONFIRMED)", title):
        continue
    end = heading_matches[index + 1].start() if index + 1 < len(heading_matches) else len(field_checks)
    body = field_checks[heading.end():end]
    if re.search(r"\b(?:never verified|not verified|unverified|not demonstrated)\b", body, re.I):
        confirmed_with_unresolved_body.append(heading.group(1))
check(
    not confirmed_with_unresolved_body
    and "C3. TC OIS (optional) — ✅ CLOSED" in field_checks
    and "no observable difference" in field_checks
    and "not demonstrated" in field_checks,
    "field evidence never labels an unresolved profile difference confirmed",
    str(confirmed_with_unresolved_body),
)

# The specific shape that drifted: a doc naming a minSdk value that is not the build's.
for rel in ("README.md", "CLAUDE.md", "docs/ARCHITECTURE.md"):
    text = read(rel)
    # Two shapes carry the floor: the "compileSdk / targetSdk / minSdk | A / B / C" table row, where
    # only C is the floor, and prose "minSdk NN". Reading the row left-to-right takes compileSdk and
    # reports a false failure — which this check did on its first run.
    claimed = {m[2] for m in re.findall(r"compileSdk / targetSdk / minSdk \|\s*\**(\d{2})\**\s*/\s*\**(\d{2})\**\s*/\s*\**(\d{2})\**", text)}
    claimed |= set(re.findall(r"minSdk\s*=?\s*\**(\d{2})\**(?!\s*/)", text))
    wrong = {c for c in claimed if c != min_sdk}
    check(not wrong, f"{rel} names no wrong minSdk", f"says {sorted(wrong)}, build says {min_sdk}")

# ---- current camera/architecture ownership must not revive retired seams ------------------------
architecture = read("docs/ARCHITECTURE.md")

# Optional private context may be linked from committed authorities only when the rendered prose
# says it is optional and identifies the committed fallback. Otherwise a permitted clean clone
# presents a dead normative link and tells contributors to block on a file they cannot have.
architecture_overview = architecture.split("## Overview", 1)[1].split("\n---", 1)[0]
check(
    "committed clean-clone authority" in architecture_overview
    and "[`CLAUDE.md`](../CLAUDE.md)" in architecture_overview
    and "optional private" in architecture_overview
    and "[`UX_POLICY.md`](UX_POLICY.md) adds maintainer examples when present" in architecture_overview,
    "Architecture qualifies the optional private UX policy and names committed fallbacks",
)

# The as-built Module Map omitted two central concurrency owners while still naming every other
# production Kotlin or Java file. Treat filenames as the explicit inventory: grouped leaf rows
# remain fine, but adding a production module without naming it in the map is a checked defect.
module_map = architecture.split("## Module Map", 1)[1].split("\n---", 1)[0]
production_modules = sorted(
    path
    for source_root, extension in (("kotlin", "*.kt"), ("java", "*.java"))
    for path in (ROOT / "app/src/main" / source_root).rglob(extension)
)
missing_module_rows = [
    path.relative_to(ROOT).as_posix()
    for path in production_modules
    if f"`{path.name}`" not in module_map
]
check(
    not missing_module_rows,
    "Architecture Module Map names every production Kotlin and Java module",
    str(missing_module_rows),
)

# Release signing has exactly one FILE input and three optional secret VALUE overrides. The file
# path once had its own environment fallback, and then a caller-authored Gradle override. Both bypass
# the wrapper's descriptor owner. Bind build logic, wrapper, example, and public/as-built
# documentation so that neither resolver can return under a renamed key or stale instruction.
release_wrapper = read("tools/build_immutable_release.py")
scoped_release_helper = read("tools/run_scoped_signed_release.py")
keystore_example = read("keystore.properties.example")
signing_environment_values = set(re.findall(r'"(TELECAMPRO_[A-Z_]+)"', gradle))
check(
    signing_environment_values == {
        "TELECAMPRO_KEY_ALIAS",
        "TELECAMPRO_STORE_PASSWORD",
        "TELECAMPRO_KEY_PASSWORD",
    }
    and 'STORE_FILE_ENVIRONMENT = "TELECAMPRO_STORE_FILE"' in release_wrapper
    and "environment.pop(STORE_FILE_ENVIRONMENT, None)" in release_wrapper
    and "-PimmutableRelease" not in release_wrapper
    and "create_release_authority" not in release_wrapper
    and 'signingValue("storeFile", "TELECAMPRO_STORE_FILE")' not in gradle
    and "val releaseStoreFile = configuredReleaseStoreFile" in gradle
    and "evidence=external-wrapper-required" in gradle
    and "Caller-supplied immutable release claims are unsupported" in gradle
    and 'ATTESTATION_SCHEMA = 2' in read("tools/check_release_artifact.py")
    and '"release_evidence_path"' in read("tools/check_release_artifact.py")
    and "AAB is not the unique bundle output recorded by release evidence" in read(
        "tools/check_release_artifact.py"
    )
    and "storeFile` is REQUIRED" in keystore_example
    and "there is deliberately no" in keystore_example
    and "TELECAMPRO_STORE_FILE" in keystore_example
    and "ambient\n`TELECAMPRO_STORE_FILE` cannot override it" in readme
    and "`TELECAMPRO_STORE_FILE` is cleared" in architecture,
    "release signing distinguishes the one frozen file path from secret environment values",
    f"Gradle signing environment values={sorted(signing_environment_values)}",
)
play_console_submit = read("docs/play-console-submit.md")
check(
    "SECURITY-BLOCKED" in play_console_submit
    and "six-digit password was\n> transmitted in plaintext" in play_console_submit
    and "owner explicitly\n> approves a strong-key rotation or completes Google's upload-key reset" in play_console_submit
    and "uploadKeyRotationApproved` must remain absent/false" in play_console_submit
    and "python3 tools/run_scoped_signed_release.py --check-prerequisites" in play_console_submit
    and "gpg --batch --quiet --decrypt telecampro-upload-passwords.txt.gpg |" in play_console_submit
    and "python3 tools/run_scoped_signed_release.py --output" in play_console_submit
    and "export TELECAMPRO_STORE_PASSWORD" not in play_console_submit
    and "export TELECAMPRO_KEY_PASSWORD" not in play_console_submit
    and '-storepass "$TELECAMPRO_STORE_PASSWORD"' not in play_console_submit
    and '"-storepass:env"' in scoped_release_helper
    and '"-storepass",' not in scoped_release_helper
    and "credentials.clear()" in scoped_release_helper
    and "child_environment.pop(name, None)" in scoped_release_helper
    and scoped_release_helper.index("load_upload_key_prerequisite(args.root, os.environ)") <
        scoped_release_helper.index("sys.stdin.buffer.read")
    and "MIN_STRONG_PASSWORD_LENGTH = 16" in scoped_release_helper
    and "uploadKeyRotationApproved=false" in keystore_example
    and "uploadKeyCertificateSha256=<64 lowercase hex characters>" in keystore_example
    and "export TELECAMPRO_STORE_PASSWORD" not in keystore_example
    and "export TELECAMPRO_KEY_PASSWORD" not in keystore_example,
    "signed release procedure scopes secrets and requires owner-approved key replacement",
)


def markdown_heading_anchor(heading: str) -> str:
    """Match the repository's GitHub-style anchors for its current ASCII-heavy H2 headings."""
    without_markup = re.sub(r"[^\w\s-]", "", heading.casefold())
    return "#" + re.sub(r"\s", "-", without_markup)


toc_match = re.search(r"\*\*Table of Contents\*\*\n(.*?)\n---", architecture, re.S)
toc_anchors = re.findall(r"\]\((#[^)]+)\)", toc_match.group(1) if toc_match else "")
h2_headings = re.findall(r"^## (.+)$", architecture, re.MULTILINE)
expected_h2_anchors = [markdown_heading_anchor(heading) for heading in h2_headings]
check(
    bool(toc_match)
    and toc_anchors == expected_h2_anchors
    and len(toc_anchors) == len(set(toc_anchors)),
    "architecture TOC covers every H2 exactly and in order",
    f"toc={toc_anchors} h2={expected_h2_anchors}",
)
still_pipeline = read(
    "app/src/main/kotlin/me/hletrd/telecampro/capture/StillCapturePipeline.kt",
)
crop_geometry = read("app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt")
check(
    "CameraEngine.centerCrop" not in architecture
    and "centerCropBox(srcW, srcH, w, h)" in architecture
    and "StillCapturePipeline applies that rectangle" in architecture
    and "internal fun centerCropBox(" in crop_geometry
    and "centerCropBox(d.width, d.height, ar.w, ar.h)" in still_pipeline
    and "Bitmap.createBitmap(d, x, y, cropW, cropH, m, true)" in still_pipeline,
    "architecture names the current crop geometry and one-pass pipeline owners",
)
required_owners = (
    "CameraStatus.kt", "DeviceProfile.kt", "FrontMirrorConvention.kt", "MotionInversion.kt",
    "EncoderSizeLadder.kt", "CameraScreenPolicy.kt", "LocalizedStatus.kt",
    "RecordingPreNativeAllocation.kt", "RecordingStorageDispatcher.kt",
    "LaunchMediaRecoveryCoordinator.kt", "RetainedStillDiscardDispatcher.kt",
    "LatestHeavyWorkLane.kt", "MediaReview.kt",
)
check(
    all(owner in architecture for owner in required_owners),
    "architecture maps every current review-critical owner",
    f"missing {[owner for owner in required_owners if owner not in architecture]}",
)
retained_discard_dispatcher = read(
    "app/src/main/kotlin/me/hletrd/telecampro/camera/RetainedStillDiscardDispatcher.kt",
)
media_store_writer = read(
    "app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt",
)
pending_discard_journal = read(
    "app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt",
)
check(
    "withFamilyJournalAuthority" in media_store_writer
    and "unrelated families remain free" in media_store_writer
    and re.search(
        r"val page = synchronized\(databaseLock\).*?\n\s*}\n"
        r"\s*// Import completion.*?\n\s*runCatching \{ removeLegacyEntries\(page\.keys\.toSet\(\)\) }",
        pending_discard_journal,
        re.S,
    )
    is not None
    and "reference-counted exact-family authority" in architecture
    and "releases database ownership before best-effort" in architecture,
    "architecture binds family and discard isolation to executable lock boundaries",
)
media_review = read("app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt")
review_lane = read(
    "app/src/main/kotlin/me/hletrd/telecampro/ui/review/LatestHeavyWorkLane.kt",
)
check(
    "RETAINED_STILL_DISCARD_WORKER_COUNT = 2" in retained_discard_dispatcher
    and "RETAINED_STILL_DISCARD_BACKLOG_CAPACITY = 8" in retained_discard_dispatcher
    and "process-lifetime finite provider lane (two daemon workers + eight backlog slots)" in architecture
    and "MAX_DISCARD_RECOVERY_ROWS = 64" in media_store_writer
    and "discardAfterKey" in media_store_writer
    and "indexed 64-entry durable-DISCARD pages" in architecture
    and '"$URI_COLUMN ASC"' in pending_discard_journal
    and "queryLimit = batchLimit + 1" in pending_discard_journal
    and "LatestReviewSetupLane" in media_review
    and "REVIEW_PROCESS_WORKER_COUNT = 4" in review_lane
    and "REVIEW_LANE_WORKER_COUNT = 2" in review_lane
    and "REVIEW_WORK_TERMINAL_TIMEOUT_MS = 5_000L" in review_lane
    and "Submission.CapacityExhausted" in media_review
    and "Submission.TimedOut" in media_review
    and "RequestStage" in review_lane
    and "one shared four-daemon pool" in architecture
    and "5 s started-call deadline retryable" in architecture
    and "player.setDataSource" in media_review
    and "after transfer to Compose it is GC-owned" in architecture,
    "architecture describes current retained-discard, paged recovery, and review-work ownership",
)
retired_claims = (
    "Absorbed the former `FrontMirrorConvention.kt`",
    "This is the ONLY model-string branch",
    "also owns the dormant O-Log2 de-log assist",
    "cached independent workers",
)
check(
    not any(claim in architecture for claim in retired_claims),
    "architecture carries no retired ownership claim",
)
check(
    all(term in architecture for term in (
        "HLG10 Camera2 source", "display-referred", "8-bit", "Main10",
    )),
    "committed camera/store authority names every non-SDR pipeline stage",
)
if listing is None:
    skip_private("Play listing names every non-SDR pipeline stage", "docs/play-store-listing.md")
else:
    check(
        all(
            term in architecture + listing
            for term in ("HLG10 Camera2 source", "display-referred", "8-bit", "Main10")
        ),
        "camera/store authorities jointly name every non-SDR pipeline stage",
    )
production_comments = "\n".join(
    read(rel) for rel in (
        "app/src/main/kotlin/me/hletrd/telecampro/gl/Shaders.kt",
        "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt",
        "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt",
    )
)
check(
    "only its INVERSE stays" not in production_comments
    and "plumbing below stays DORMANT" not in production_comments
    and "de-log O-Log2" not in production_comments,
    "production comments carry no affirmative retired native-log plumbing",
)

# ---- current orientation/route/build comments must agree with implementation -------------------
manifest = read("app/src/main/AndroidManifest.xml")
main_activity = read("app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt")
claude = read("CLAUDE.md")
check(
    re.search(r"<activity\b[^>]*\bandroid:screenOrientation=", manifest, re.S) is None
    and "lockPortraitOnHandsets" in main_activity
    and "manifest carries no" in claude,
    "committed orientation authority joins absent manifest restriction to handset runtime lock",
)
ui_snapshot_activity = read(
    "app/src/debug/kotlin/me/hletrd/findx9tele/ui/UiSnapshotActivity.kt"
)
check(
    "internal fun ComponentActivity.enableTeleCamEdgeToEdge()" in main_activity
    and "statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)" in main_activity
    and "navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)" in main_activity
    and main_activity.count("enableTeleCamEdgeToEdge()") >= 2
    and "import me.hletrd.telecampro.enableTeleCamEdgeToEdge" in ui_snapshot_activity
    and "enableTeleCamEdgeToEdge()" in ui_snapshot_activity
    and re.search(r"(?m)^\s*enableEdgeToEdge\(\s*\)\s*$", ui_snapshot_activity) is None,
    "snapshot host pins dark system bars through the production helper",
)
if backlog is None:
    skip_private("release board records the removed orientation restriction", "docs/BACKLOG.md")
else:
    check(
        "manifest orientation restriction is" in backlog,
        "release board records the removed orientation restriction",
    )
check(
    "current TELE capture is eligible" not in architecture
    and "DNG is not TELE-only" in architecture,
    "architecture keeps DNG routing standalone-any-rear rather than TELE-only",
)
current_comments = read("app/src/main/kotlin/me/hletrd/telecampro/camera/CaptureCapabilities.kt")
route_comments = "\n".join(
    read(rel) for rel in (
        "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt",
        "app/src/main/kotlin/me/hletrd/telecampro/ui/ZoomMath.kt",
        "app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt",
    )
)
check(
    "unified preset in\n     * photo, lens-local 1× in video" not in route_comments
    and "Photo zoom is unified/main-relative" not in route_comments
    and "seamless (photo) camera" not in route_comments
    and "logical BACK is unified" in route_comments
    and "every standalone route" in route_comments
    and "RAW/DNG Photo" in route_comments,
    "active zoom comments keep coordinate ownership route-based",
)
zoom_submit_plan = read("app/src/main/kotlin/me/hletrd/telecampro/camera/ZoomSubmitPlan.kt")
camera_engine = read("app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt")
camera_controller = read("app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt")
trace_admission_call = re.search(
    r"traceAdmission\s*=\s*captureFamilyTraceAdmission\((.*?)\)",
    camera_engine,
    re.S,
)
debug_log_classes, unclassified_debug_logs = debug_log_classification_inventory()
check(
    not unclassified_debug_logs
    and all(debug_log_classes[classification] > 0 for classification in (
        "recurring_budgeted",
        "one_shot_session",
        "reserved_fault",
    )),
    "every production debug log site has an executable quota classification",
    ", ".join(unclassified_debug_logs[:8]),
)
check(
    'if (tapFocusDiagnosticAllowed(BuildConfig.DEBUG, edgeOwned = true))' in camera_controller
    and 'Log.i(TAG, "Touch AF: scanning region $meteringPoint")' in camera_controller
    and 'if (tapFocusDiagnosticAllowed(BuildConfig.DEBUG, edgeOwned = tapPublication != null))'
    in camera_engine
    and 'Log.i("CameraEngine", "TapFocus: cleared")' in camera_engine,
    "tap-focus scan and owned reset retain budgeted calibration evidence",
)
check(
    bool(
        trace_admission_call
        and "BuildConfig.DEBUG" in trace_admission_call.group(1)
        and "traceText!!" not in camera_engine
        and "traceAdmission.registration && recurringDiagnosticAllowed(" in camera_engine
        and "traceSettlement && recurringDiagnosticAllowed(" in camera_engine
        and "traceAdmission.registration && !registrationAlreadyTraced &&" in camera_engine
        and "traceSettlement = traceAdmission.settlement" in camera_engine
    ),
    "release capture tracing is build-gated and nullable-safe at the production callback",
)
camera_view_model = read("app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt")
zoom_glide_state = read("app/src/main/kotlin/me/hletrd/telecampro/ui/ZoomGlideState.kt")
zoom_submit_authority = "\n".join(
    (claude, architecture, zoom_submit_plan, camera_controller, camera_view_model, zoom_glide_state)
)
check(
    "Pure moving-tick suppression" in architecture
    and "resolveZoomGestureEdgeTarget" in zoom_submit_plan
    and "resolveZoomBoostFlipApply" in zoom_submit_plan
    and "resolveZoomGestureEdgeTarget(" in camera_engine
    and "submitExactWhenFpsUnchanged" in camera_controller
    and "700 ms end is state-only" in claude
    and "no-FPS-change route the 700 ms end becomes state-only" in architecture
    and "throttle window + mid-gesture wide-aim clamp" not in zoom_submit_authority
    and "A gesture costs TWO swaps" not in zoom_submit_authority
    and "Each gesture EDGE" not in zoom_submit_authority
    and "Still-truth-only zoom update for THROTTLED" not in zoom_submit_authority
    and "restamped the submit throttle" not in zoom_submit_authority
    and "one throttle window after the LAST flush" not in zoom_submit_authority
    and "quiet for a full throttle window" not in zoom_submit_authority,
    "zoom authority rejects retired periodic-submit and fixed-edge model",
)
build_script = read("app/build.gradle.kts")
debug_preview_source = read("app/src/debug/kotlin/me/hletrd/findx9tele/ui/CameraScreenPreview.kt")
check(
    "capability-gated read here misses" not in current_comments
    and "isMinifyEnabled = false" not in debug_preview_source
    and "release keeps minify off" not in debug_preview_source.lower()
    and re.search(r"release\s+(?:keeps|has|uses).*minif(?:y|ication)\s+(?:off|disabled)", debug_preview_source, re.I) is None,
    "current comments describe maximum-resolution discovery and enabled R8",
)
check(
    all("python3 tools/verify_host.py" in authority for authority in (claude, architecture))
    and "device-harness self-tests" in architecture,
    "committed host-gate authorities document the consolidated non-device suite",
)
completed_plans = []
malformed_completed_plans = []
plan_identity_pattern = re.compile(r"^(\d{4}-\d{2}-\d{2})-rpf-cycle(\d+)\.md$")
for plan_path in (ROOT / "docs/plans").glob("*.md"):
    plan_text = plan_path.read_text(encoding="utf-8")
    if re.search(r"^Status:\s*complete\b", plan_text, re.M):
        match = plan_identity_pattern.fullmatch(plan_path.name)
        if match is None:
            malformed_completed_plans.append(plan_path)
            continue
        try:
            plan_date = datetime.date.fromisoformat(match.group(1))
        except ValueError:
            malformed_completed_plans.append(plan_path)
            continue
        completed_plans.append(((plan_date, int(match.group(2))), plan_path, plan_text))
check(
    not malformed_completed_plans,
    "completed implementation plans carry sortable date and numeric-cycle identities",
    ", ".join(path.name for path in sorted(malformed_completed_plans)),
)
identity_counts: dict[tuple[datetime.date, int], int] = {}
for identity, _, _ in completed_plans:
    identity_counts[identity] = identity_counts.get(identity, 0) + 1
duplicate_plan_identities = sorted(identity for identity, count in identity_counts.items() if count > 1)
check(
    not duplicate_plan_identities,
    "completed implementation plan identities are unique",
    ", ".join(f"{date.isoformat()}/cycle{cycle}" for date, cycle in duplicate_plan_identities),
)
latest_completed_plan = max(completed_plans, key=lambda item: item[0]) if completed_plans else None
check(
    latest_completed_plan is not None
    and "python3 tools/verify_host.py" in latest_completed_plan[2],
    "latest completed implementation plan names the authoritative host gate",
    latest_completed_plan[1].relative_to(ROOT).as_posix() if latest_completed_plan else "none",
)
sdk_authority = read("tools/android_sdk.py")
verify_host_source = read("tools/verify_host.py")
debug_wrapper_source = read("tools/build_immutable_debug.py")
release_wrapper_source = read("tools/build_immutable_release.py")
check(
    "### Android SDK setup" in readme
    and "sdk.dir" in readme
    and "ANDROID_HOME" in readme
    and "$HOME/Library/Android/sdk" in readme
    and "$HOME/Android/Sdk" in readme
    and "Platform 37" in readme
    and "Build Tools 36.0.0" in readme
    and "README.md` § **Android SDK setup**" in claude
    and "README.md` § **Android SDK setup**" in field_checks
    and 'README.md "Android SDK setup"' in read("device-tests/README.md")
    and "def android_sdk_environment(" in sdk_authority
    and "local.properties" in sdk_authority
    and "ANDROID_HOME" in sdk_authority
    and "Library/Android/sdk" in sdk_authority
    and "Android/Sdk" in sdk_authority
    and all(
        "android_sdk_environment" in source
        for source in (verify_host_source, debug_wrapper_source, release_wrapper_source)
    ),
    "build field and harness workflows share one clean-clone Android SDK authority",
)
testing_doc = read_private("docs/TESTING.md")
if testing_doc is None:
    skip_private("TESTING documents the consolidated non-device suite", "docs/TESTING.md")
else:
    check(
        "python3 tools/verify_host.py" in testing_doc and "device-tests/tests" in testing_doc,
        "TESTING documents the consolidated non-device suite",
    )
check(
    "python3 tools/build_immutable_debug.py" in architecture
    and "source_owner=immutable-debug-worktree-v1" in architecture
    and "source_owner=mutable-development-worktree" in architecture
    and "IMMUTABLE_DEBUG_SOURCE_OWNER = \"immutable-debug-worktree-v1\"" in read(
        "tools/build_immutable_debug.py"
    )
    and 'orElse("mutable-development-worktree")' in build_script,
    "device-evidence debug authority distinguishes immutable and developer source owners",
)
device_verification_match = re.search(
    r"\*\*Device verification:\*\*\s*```bash\n(.*?)\n```",
    architecture,
    re.S,
)
device_verification = device_verification_match.group(1) if device_verification_match else ""
check(
    bool(device_verification_match)
    and "BUILD_RESULT=\"$(python3 tools/build_immutable_debug.py)\"" in device_verification
    and "EVIDENCE_APK=\"${BUILD_RESULT##* apk=}\"" in device_verification
    and 'dump badging "$EVIDENCE_APK"' in device_verification
    and 'install -r "$EVIDENCE_APK"' in device_verification
    and "app/build/outputs/apk/debug/app-debug.apk" not in device_verification,
    "architecture device-evidence commands inspect and install the wrapper-printed immutable APK",
)
device_test_readme = read("device-tests/README.md")
field_checks = read("docs/FIELD_CHECKS.md")
device_runner = read("device-tests/run.py")
qa_runbook_path = ROOT / ".claude/agents/qa-adversary.md"
# The runbook is intentionally local/ignored. Validate it whenever this checkout provides it, but
# keep the tracked documentation gate runnable from a clean public clone where `.claude/` is absent.
qa_runbook = qa_runbook_path.read_text(encoding="utf-8") if qa_runbook_path.is_file() else None
claude_build_loop = language_fenced(claude, "## Build / deploy / verify loop", "bash")
field_install_block = language_fenced(
    field_checks,
    "Install the exact immutable debug build first",
    "bash",
)
qa_evidence_block = (
    language_fenced(qa_runbook, "When device work is allowed", "bash")
    if qa_runbook is not None
    else ""
)
runner_usage = device_runner.split("Usage:", 1)[1].split("Requires:", 1)[0]
runner_usage_commands = [
    line.strip() for line in runner_usage.splitlines() if line.strip().startswith("python3 ")
]
check(
    all(
        "BUILD_RESULT=\"$(python3 tools/build_immutable_debug.py)\"" in block
        and "EVIDENCE_APK=\"${BUILD_RESULT##* apk=}\"" in block
        and "app/build/outputs/apk/debug/app-debug.apk" not in block
        for block in (claude_build_loop, field_install_block)
    )
    and 'install -r "$EVIDENCE_APK"' in claude_build_loop
    and 'install -r -t "$EVIDENCE_APK"' in field_install_block
    and (
        qa_runbook is None
        or (
            "BUILD_RESULT=\"$(python3 tools/build_immutable_debug.py)\"" in qa_evidence_block
            and "EVIDENCE_APK=\"${BUILD_RESULT##* apk=}\"" in qa_evidence_block
            and 'dump badging "$EVIDENCE_APK"' in qa_evidence_block
            and 'install -r "$EVIDENCE_APK"' in qa_runbook
            and "app/build/outputs/apk/debug/app-debug.apk" not in qa_runbook
        )
    )
    and len(runner_usage_commands) == 3
    and all('--apk "$EVIDENCE_APK"' in command for command in runner_usage_commands)
    and "tools/build_immutable_debug.py" in device_runner.split("Reports land", 1)[0]
    and re.search(r'ap\.add_argument\(\s*"--apk".*?required=True', device_runner, re.S)
    and "default=DEFAULT_APK" not in device_runner
    and "def default_apk_path" not in device_runner
    and "evidence_install_command(source_apk)" in device_runner
    and "app/build/outputs/apk/debug/app-debug.apk" not in device_runner
    and "For local development only" in read("README.md"),
    "all active device-evidence authorities and runner diagnostics require the immutable debug APK",
)
check(
    "BUILD_RESULT=\"$(python3 tools/build_immutable_debug.py)\"" in device_test_readme
    and "EVIDENCE_APK=\"${BUILD_RESULT##* apk=}\"" in device_test_readme
    and "snapshots the exact scoped source whether the checkout is" in device_test_readme
    and "clean or dirty" in device_test_readme
    and "Evidence runs must pass the wrapper's printed immutable APK" in device_test_readme
    and "default APK" not in device_test_readme
    and "requires clean committed source" not in device_test_readme
    and "match `app/build/outputs/apk/debug/app-debug.apk`" not in device_test_readme,
    "device harness requires the printed clean-or-dirty immutable debug artifact",
)

# Keep the harness's non-coverage inventory honest about the distinction between an asserted case,
# a diagnostic instrumented probe, and device evidence that is already closed. The old text said an
# instrumented pinch test was future work even though the probe existed, then called manually closed
# front mirror/rotation signs verification-pending.
pinch_probe = read(
    "app/src/androidTest/kotlin/me/hletrd/telecampro/PinchGestureProbeTest.kt"
)
known_noncoverage = device_test_readme.split("## Known non-coverage (deliberate)", 1)[1]
check(
    "PinchGestureProbeTest" in known_noncoverage
    and "diagnostic rather than a pass/fail test" in known_noncoverage
    and "pinch feel remains" in known_noncoverage
    and "Instrumented Espresso tests could add this later" not in known_noncoverage
    and "automated subject-text mirror assertion" in known_noncoverage
    and "already device-verified" in known_noncoverage
    and "not sign verification" in known_noncoverage
    and "verification-pending" not in known_noncoverage
    and "MotionEvent.ACTION_POINTER_DOWN" in pinch_probe
    and "injectInputEvent" in pinch_probe
    and "Front stills were confirmed unreversed on device" in field_checks
    and "front capture-ROTATION sign is DEVICE-VERIFIED" in architecture,
    "device harness non-coverage distinguishes probes from closed device evidence",
)

# Exact UI names come from resources, not from a second hand-maintained spelling in prose. The
# screenshot validity manifest already treats the superseded pair as blocking; current architecture
# UX policy, QA runbook, and device selectors must therefore use the same resource-backed names.
architecture_ui_layout = architecture.split("**UI layout (ProSheet.kt):**", 1)[1].split(
    "**Quick Fn controls (ManualDials.kt):**",
    1,
)[0]
check(
    "device-enumerated lens presets (0.6x/1x/3x/10x on PMA110)" in architecture_ui_layout
    and "5. **Lens** — 0.6x/1x/3x/10x selection" not in architecture_ui_layout,
    "Architecture scopes the fixed lens list to PMA110 and documents enumeration",
)
ux_policy_path = ROOT / "docs/UX_POLICY.md"
# Like the local QA runbook, UX_POLICY is intentionally ignored as internal working context. Enforce
# it when present without making a clean public checkout depend on an untracked file.
ux_policy = ux_policy_path.read_text(encoding="utf-8") if ux_policy_path.is_file() else None
device_selectors = read("device-tests/dtest/selectors.py")
shoot_label = default_strings.get("settings_tab_shoot")
still_quality_label = default_strings.get("label_still_quality")
check(
    shoot_label == "Shoot"
    and still_quality_label == "Still Quality"
    and f"2. **{shoot_label}**" in architecture_ui_layout
    and still_quality_label in architecture_ui_layout
    and "**Shooting**" not in architecture_ui_layout
    and "JPEG quality" not in architecture_ui_layout
    and "Shooting-tab" not in architecture
    and (
        ux_policy is None
        or (
            f"`{shoot_label}`/My Menu" in ux_policy
            and "Shooting/My Menu" not in ux_policy
        )
    )
    and (
        qa_runbook is None
        or (
            f"My, {shoot_label}, Exposure, Focus, Lens, Video" in qa_runbook
            and "My, Shooting, Exposure" not in qa_runbook
        )
    )
    and f'selector("page_shoot", "{shoot_label}", "촬영")' in device_selectors
    and 'selector("page_shooting", "Shooting"' not in device_selectors,
    "all active UI authorities and device selectors use resource-backed Shoot and Still Quality labels",
)

# The wide operator rail was deliberately removed. Historical prose may explain that decision, but
# comments adjacent to the live full-width layout must never instruct future edits to reserve rail
# columns or constrain the top bar to a side rail.
camera_screen = read("app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt")
camera_screen_policy = read("app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreenPolicy.kt")
manual_dials = read("app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ManualDials.kt")
stale_operator_rail_guidance = (
    "bounded by BOTH reserved columns",
    "Inset by BOTH reserved columns",
    "same seam violation the two columns exist to stop",
    "bar belongs to the RAIL's column",
    "Constrained to the rail",
)
check(
    not any(phrase in camera_screen for phrase in stale_operator_rail_guidance)
    and "private fun TopBarContainer" in camera_screen
    and "modifier.fillMaxWidth().padding(horizontal = 12.dp)" in camera_screen
    and "landscapeOperator" not in camera_screen
    and "TopBarScope" not in camera_screen,
    "CameraScreen keeps one full-width top-bar layout and no deleted operator-rail guidance",
)
committed_tablet_guidance_match = re.search(
    r"### Tablet screenshots(.*?)(?=\n## Device Catalog)",
    submit,
    re.S,
)
committed_tablet_guidance = (
    committed_tablet_guidance_match.group(1) if committed_tablet_guidance_match else ""
)
check(
    bool(committed_tablet_guidance_match)
    and "operator RAIL layout" not in committed_tablet_guidance
    and "controls in their own column" not in committed_tablet_guidance
    and "Camera controls keep the same homes" in committed_tablet_guidance
    and "settings panel may dock as a side sheet" in committed_tablet_guidance,
    "committed tablet asset guidance keeps the deleted operator rail retired",
)
if listing is None:
    skip_private("private tablet asset guidance keeps the deleted operator rail retired", "docs/play-store-listing.md")
else:
    tablet_guidance_match = re.search(
        r"### Tablet screenshots(.*?)\nBoth tablet slots",
        listing,
        re.S,
    )
    tablet_guidance = tablet_guidance_match.group(1) if tablet_guidance_match else ""
    check(
        bool(tablet_guidance_match)
        and "operator RAIL layout" not in tablet_guidance
        and "controls in their own column" not in tablet_guidance
        and "Camera controls keep the same homes" in tablet_guidance
        and "settings panel may dock as a side sheet" in tablet_guidance,
        "private tablet asset guidance keeps the deleted operator rail retired",
    )
stale_handset_rotation_guidance = (
    "Since the activity stopped locking orientation",
    "sideways phone already gets a rotated LAYOUT",
    "window now turns with the device",
)
check(
    not any(
        phrase in camera_screen or phrase in camera_screen_policy or phrase in manual_dials
        for phrase in stale_handset_rotation_guidance
    )
    and "Handsets remain portrait-locked" in camera_screen_policy
    and "Handsets remain portrait-locked" in camera_screen
    and "handsets remain portrait-locked" in manual_dials,
    "active UI guidance keeps handsets portrait-locked and limits window rotation to large screens",
)
view_model = read("app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt")
camera_engine = read("app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt")
check(
    "process-wide finite" in claude
    and "pre-native allocator" in claude
    and "itself. Stop/pause/release/timeout retire the attempt" in claude
    and "process-wide pre-native allocator" in architecture
    and "It dispatches but never performs pending-row allocation" in architecture
    and "Admission itself runs on the RECORDER EXECUTOR" not in claude
    and "MediaStore pending insert" not in architecture
    and "ProcessRecordingPreNativeAllocator.dispatch(allocationTask)" in camera_engine
    and "recorderExecutor.execute" in camera_engine,
    "recording authorities describe allocator then recorder ownership",
)
check(
    "Attempts BACK/FRONT/EXTERNAL route inventory before first open" in architecture
    and "bounded eventual convergence" in architecture
    and "Route inventory is attempted before the first Camera2 open" in view_model
    and "Resolves the complete BACK/FRONT/EXTERNAL route inventory before first open" not in architecture
    and "scheduleRouteInventoryRetry()" in camera_engine,
    "route authorities distinguish pre-open attempt from bounded convergence",
)
check(
    "Photo, TC off, RAW/DNG wanted" in architecture
    and "Standalone rear lens" in architecture
    and "route inputs" in architecture,
    "zoom-route table keeps RAW/DNG on its standalone lens-local home",
)
if backlog is None:
    skip_private("native-log history preserves the surviving debug EGL flag", "docs/BACKLOG.md")
else:
    check(
        "The `nativelog` filename survives only as the separate debug EGL precision gate" in backlog
        and "tenBitExperimentEnabled" in backlog
        and "getExternalFilesDir(null), \"nativelog\"" in read(
            "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt"
        ),
        "native-log history preserves the surviving debug EGL flag without reviving vendor plumbing",
    )
readme = read("README.md")
check(
    "flip corrected everywhere" not in readme
    and "main viewfinder, stills, and video" in readme
    and "Loupe Overview" in readme
    and "raw delivered" in readme,
    "README scopes afocal correction around the honest same-stream overview exception",
)
loupe_exception_anchor = "#loupe-overview-afocal-exception"
unqualified_afocal_claim = re.compile(
    r"\b(?:all\s+)?(?:the\s+)?(?:live\s+)?preview\b.{0,100}"
    r"\b(?:saved|results?|outputs?)\b.{0,60}\bcorrect",
    re.IGNORECASE | re.DOTALL,
)
check(
    unqualified_afocal_claim.search(claude.split("## Non-negotiable constraints", 1)[0]) is None
    and unqualified_afocal_claim.search(architecture.split("## Module Map", 1)[0]) is None
    and re.search(r"main viewfinder and\s+saved still/video results must be corrected", claude)
    and re.search(r"main viewfinder and\s+saved still/video results must be corrected", architecture)
    and loupe_exception_anchor in claude
    and loupe_exception_anchor in architecture
    and 'id="loupe-overview-afocal-exception"' in read("docs/FIELD_CHECKS.md")
    and "raw, inverted field" in claude
    and "raw, inverted field" in architecture,
    "top-level afocal authorities scope correction around the Loupe Overview exception",
)

# The overview once shared renderer rotation state with the main draw. It now owns an explicit
# per-draw window-only override, which intentionally leaves the same converter-fed stream raw and
# inverted. Join executable code, current authority, rerunnable field criteria, and the preserved
# historical backlog label so none can independently revive the old "structurally impossible" rule.
gl_pipeline = read("app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt")
flip_renderer = read("app/src/main/kotlin/me/hletrd/telecampro/gl/FlipRenderer.kt")
field_checks = read("docs/FIELD_CHECKS.md")
rotation_math = read("app/src/main/kotlin/me/hletrd/telecampro/camera/RotationMath.kt")
front_mirror = read("app/src/main/kotlin/me/hletrd/telecampro/gl/FrontMirrorConvention.kt")
check(
    "FIELD_CHECKS B1" in rotation_math
    and "docs/FIELD_CHECKS.md" in rotation_math
    and "closed rotation end to end" in rotation_math
    and "still an open Residual Field Check" not in rotation_math
    and "B1. Landscape video playback orientation — ✅ PASSED" in field_checks
    and "rotation is now closed end to end" in field_checks,
    "RotationMath keeps committed B1 video rotation evidence closed",
)
check(
    "ROTATION term remains OPEN" in front_mirror
    and "docs/FIELD_CHECKS.md A4" in front_mirror
    and "large-screen 90°/270°" in front_mirror
    and "A4. Front tap-AF window-rotation axis — ◯ OPEN" in field_checks,
    "FrontMirrorConvention points to committed open A4 calibration",
)
rec_border_start = claude.index("- **The REC tally border must follow")
rec_border_end = claude.index("\n- **Analysis readback", rec_border_start)
rec_border_authority = claude[rec_border_start:rec_border_end]
check(
    "platform radius unscaled" in rec_border_authority
    and "former ×1.2 multiplier" in rec_border_authority
    and "device-rejected" in rec_border_authority
    and "Use the radius the panel REPORTS, unscaled" in camera_screen
    and "scale ×1.2" not in rec_border_authority
    and "radius by 1.2" not in rec_border_authority,
    "REC border authority keeps the device-accepted platform radius unscaled",
)
check(
    "rotationOverrideDeg = RotationMath.windowPreviewRotationDegrees(windowRotationDeg)" in gl_pipeline
    and "raw and 180° inverted relative to the corrected main" in gl_pipeline
    and "not an upright or pre-converter-world reference" in gl_pipeline
    and "same converter-fed stream" in flip_renderer
    and "making it raw and inverted relative to the main view" in flip_renderer
    and "window-rotation term" in claude
    and "raw, inverted field" in claude
    and "The Loupe Overview draws UPRIGHT" not in claude
    and all(
        stale not in "\n".join((claude, architecture, flip_renderer, gl_pipeline))
        for stale in (
            "operator wants UPRIGHT",
            "UPRIGHT, deliberately NOT carrying",
            "world the right way up in the corner",
            "world sits the right way up in the corner",
            "upright overview stands in for the PRE-CONVERTER world",
        )
    )
    and "one-call `rotationOverrideDeg`" in field_checks
    and "raw, inverted field" in field_checks,
    "committed Loupe Overview criteria match the per-draw orientation authority",
)
check(
    all(
        phrase in claude and phrase in architecture
        for phrase in (
            "monotonic pipeline-publication generation",
            "complete REC packet",
            "selected frame/capture rate",
            "exact ordered encoder candidates",
        )
    )
    and "Rollback restores its baseline packet only" in claude
    and "restores its baseline only while it still owns that generation" in architecture
    and "A failed source-precision reopen restores the complete Engine tuple" not in architecture,
    "pipeline rollback and REC authorities preserve independent packet ownership",
)
if backlog is None:
    skip_private("Loupe history labels the superseded orientation rule", "docs/BACKLOG.md")
else:
    check(
        "SUPERSEDED — Loupe Overview" in backlog and "predates the later per-draw" in backlog,
        "Loupe history labels the superseded orientation rule",
    )
camera_state = read("app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt")
finder_tests = read("app/src/test/kotlin/me/hletrd/telecampro/camera/TeleFinderVisibilityTest.kt")
camera_actions = read("app/src/main/kotlin/me/hletrd/telecampro/ui/CameraActions.kt")
camera_engine = read("app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt")
loupe_compose_test = read(
    "app/src/test/kotlin/me/hletrd/telecampro/ui/controls/LoupeOverviewGateTest.kt"
)
check(
    "FINDER_MIN_ZOOM = 3f" in claude
    and "Video deliberately ignores the unrelated still-aspect setting" in claude
    and "FINDER_MIN_ZOOM = 3f" in architecture
    and "Video ignores the unrelated still-aspect setting" in architecture
    and "const val FINDER_MIN_ZOOM = 3f" in camera_state
    and "past the zoom floor the finder is offered without a converter" in finder_tests
    and "video shows the finder for ANY photo aspect" in finder_tests,
    "Loupe Overview authorities preserve video and converterless 3x admission",
)
check(
    "bottom-right corner viewport" in claude
    and "bottom-right corner viewport" in architecture
    and "Bottom-right corner in GL's bottom-left-origin pixel space" in gl_pipeline
    and "inset from the right" in camera_state
    and "x = boxWidth - width - shortEdge * sideMargin" in camera_state,
    "Loupe Overview authorities match the executable right-inset corner",
)
obsolete_finder_contracts = (
    "TELE + Photo + 4:3 + loupe",
    "toggle && TELE && PHOTO && 4:3",
    "user toggle + Photo + 4:3 + TELE + active punch-in",
    "overview only ever draws under Photo + 4:3 + Teleconverter",
    "Mode is a finder-gate input (photo-only)",
    "finder PIP is 4:3-only",
)
check(
    not any(
        obsolete in "\n".join(
            (camera_actions, camera_engine, gl_pipeline, loupe_compose_test, camera_screen)
        )
        for obsolete in obsolete_finder_contracts
    )
    and "active loupe + (TELE or unified zoom >= 3x)" in loupe_compose_test
    and "Video ignoring still aspect" in loupe_compose_test
    and "active punch-in + (TELE or unified zoom" in camera_screen
    and ">= 3x). Photo additionally requires 4:3; Video ignores" in camera_screen
    and "must not mirror to bottom-left under RTL system locales" in camera_screen
    and "must not mirror to bottom-right under RTL system locales" not in camera_screen,
    "Loupe source and Compose-test guidance rejects the superseded Photo/TELE-only gate",
)

# GuideLine's quiet 0.40 restyle is executable truth. Keep nearby comparative rationale aligned so
# a future visual pass cannot restore the retired 0.55 weight by following stale live comments.
ui_theme = read("app/src/main/kotlin/me/hletrd/telecampro/ui/theme/Theme.kt")
ui_overlays = read("app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt")
guide_authority = "\n".join((ui_theme, camera_screen, ui_overlays))
check(
    "val GuideLine = Color.White.copy(alpha = 0.40f)" in ui_theme
    and "These were 0.30 and 0.35 — five hundredths apart" in ui_theme
    and "Distinct from [GuideLine] (0.40)" in ui_theme
    and "0.40 GuideLine the thirds/frame-line rules" in camera_screen
    and "CameraColors.GuideLine at 0.40" in ui_overlays
    and "GuideLine] (0.55)" not in guide_authority
    and "0.55 GuideLine" not in guide_authority
    and "other 0.55s in this file are the frame lines" not in guide_authority,
    "live UI authority keeps the current 0.40 GuideLine weight",
)

# ---- coverage residual authority must stay machine-checked, not copied into prose --------------
residual_manifest = read("tools/coverage/partition-a-residuals.txt")
if testing_doc is None:
    skip_private("TESTING points to the exact checked Partition-A residual authority", "docs/TESTING.md")
    skip_private("TESTING carries no obsolete hand-maintained residual inventory", "docs/TESTING.md")
else:
    check(
        "tools/coverage/partition-a-residuals.txt" in testing_doc
        and "unexpected miss" in testing_doc
        and "resolved-but-still-listed" in testing_doc
        and "count drift" in testing_doc,
        "TESTING points to the exact checked Partition-A residual authority",
    )
    check(
        "cycle-7 close (10 lines" not in testing_doc
        and "`storage/SettingsStore` 1" not in testing_doc,
        "TESTING carries no obsolete hand-maintained residual inventory",
    )
manifest_rows = [
    line for line in residual_manifest.splitlines()
    if line.strip() and not line.lstrip().startswith("#")
]
check(
    bool(manifest_rows)
    and all(len(line.split("\t")) == 4 for line in manifest_rows)
    and all(
        line.split("\t", 3)[3].startswith(
            ("framework-bound:", "proven-unreachable:", "race-only:"),
        )
        for line in manifest_rows
    ),
    "Partition-A residual authority carries class count source and reviewed reason",
)

# ---- Device Catalog is one classified matrix, not mutually inconsistent counts -----------------
catalog_start = submit.index("## Device Catalog")
catalog_end = submit.index("\n## ", catalog_start + 4)
device_catalog = submit[catalog_start:catalog_end]
for classification in (
    "Physical capture", "Preview/layout-only", "Emulator", "Unvalidated equivalent",
):
    check(classification in device_catalog, f"Device Catalog includes {classification}")
for model in (
    "PMA110", "SM-S918N", "TB331FC", "TB336ZU", "21061110AG",
    "sdk_gphone64_arm64", "CPH2841",
):
    check(model in device_catalog, f"Device Catalog classifies {model}")
check(
    "Synthetic camera" in device_catalog and "no optics" in device_catalog,
    "emulator evidence disclaims optics/HAL validation",
)
check(
    "Not measured" in device_catalog and "not capture-verified" in device_catalog,
    "CPH2841 remains explicitly unvalidated",
)
check(
    "do not attest current HEAD" in device_catalog or "does not attest current HEAD" in device_catalog,
    "historical device evidence does not attest current HEAD",
)
check(
    "Only the PMA110 is device-verified" not in device_catalog
    and "Two devices are capture-verified" not in device_catalog,
    "active Device Catalog carries no obsolete device count",
)

print(f"\n{CHECKS} checks, {len(FAILURES)} failed, {PRIVATE_SKIPS} private checks skipped")
sys.exit(1 if FAILURES else 0)
