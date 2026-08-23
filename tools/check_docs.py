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
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
FAILURES: list[str] = []
CHECKS = 0


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


def fenced(text: str, heading: str) -> str:
    """The first ``` block after a heading — the copy-paste payload."""
    start = text.index(heading)
    m = re.search(r"```\n(.*?)\n```", text[start:], re.S)
    assert m, f"no fenced block under {heading!r}"
    return m.group(1)


# ---- store copy: Play's hard limits, and the wrapping trap -------------------------------------
listing = read("docs/play-store-listing.md")

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

default_strings = {
    element.attrib["name"]: "".join(element.itertext())
    for element in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
    if element.tag == "string"
}
required_current_copy = screenshot_manifest.get("required_current_copy", {})
copy_mismatches = {
    key: (expected, default_strings.get(key))
    for key, expected in required_current_copy.items()
    if default_strings.get(key) != expected
}
check(not copy_mismatches, "phone screenshot recapture copy matches current resources", str(copy_mismatches))

blocking_assets = screenshot_manifest.get("blocking_assets", [])
required_recapture = screenshot_manifest.get("required_recapture", {})
obsolete_visible_copy = screenshot_manifest.get("obsolete_visible_copy", {})
asset_authorities = re.sub(
    r"\s+",
    " ",
    listing + "\n" + read("docs/play-console-submit.md"),
)
ready = screenshot_manifest.get("submission_ready") is True
provenance_ready = (
    re.fullmatch(r"[0-9a-f]{64}", required_recapture.get("immutable_source_manifest_digest") or "")
    and re.fullmatch(r"[0-9a-f]{64}", required_recapture.get("apk_sha256") or "")
)
stale_state_valid = (
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
    and "NOT SUBMISSION-READY" in listing
    and "NOT SUBMISSION-READY" in read("docs/play-console-submit.md")
)
ready_state_valid = (
    ready
    and not blocking_assets
    and not obsolete_visible_copy
    and bool(provenance_ready)
    and "NOT SUBMISSION-READY" not in asset_authorities
    and "SUBMISSION-READY" in asset_authorities
)
check(
    (stale_state_valid or ready_state_valid)
    and required_recapture.get("source_owner") == "immutable-debug-worktree-v1"
    and required_recapture.get("source_manifest_schema") == 2,
    "stale phone screenshots fail closed pending immutable PMA110 recapture",
)
check(
    ready or all(
        obsolete in asset_authorities
        for values in obsolete_visible_copy.values()
        for obsolete in values
    ),
    "stale phone screenshot semantics are explicit in both submission authorities",
)

short = fenced(listing, "## Short description")
check(len(short) <= 80, "short description <= 80 chars", f"{len(short)}")

full = fenced(listing, "## Full description")
check(len(full) <= 4000, "full description <= 4000 chars", f"{len(full)}")

notes = fenced(listing, "## Release notes")
inner = re.sub(r"</?(en-US|ko-KR)>", "", notes).strip()
check(len(inner) <= 500, "release notes <= 500 chars", f"{len(inner)}")

# ---- the Korean listing is held to the same limits and the same wrapping rule -------------------
# Play applies its limits per language, and a translation that overruns is rejected at paste time
# rather than at review. Indexed on the Korean headings because the English ones are substrings of
# them ("## Short description" matches inside "### Short description — ...").
ko_short = fenced(listing, "간단한 설명 (≤80자)")
check(len(ko_short) <= 80, "ko short description <= 80 chars", f"{len(ko_short)}")

ko_full = fenced(listing, "자세한 설명 (≤4000자)")
check(len(ko_full) <= 4000, "ko full description <= 4000 chars", f"{len(ko_full)}")

ko_notes = fenced(listing, "출시 노트 (≤500자)")
ko_inner = re.sub(r"</?ko-KR>", "", ko_notes).strip()
check(len(ko_inner) <= 500, "ko release notes <= 500 chars", f"{len(ko_inner)}")

# The English wrap detector keys on a lower-case continuation, which Hangul has no notion of. The
# language-neutral signal is structural: in the intended format every prose paragraph is ONE line,
# so any two consecutive non-empty prose lines mean the block was re-wrapped. Bullets legitimately
# run consecutively, and the language tags bracket the notes, so both are exempt.
def prose_line(s: str) -> bool:
    s = s.strip()
    return bool(s) and not s.startswith("•") and not s.startswith("<")

for label, block in (("ko full description", ko_full), ("ko release notes", ko_notes)):
    lines = block.split("\n")
    wrapped = [a for a, b in zip(lines, lines[1:]) if prose_line(a) and prose_line(b)]
    check(not wrapped, f"{label} is not hard-wrapped", f"{len(wrapped)} continuation lines")

# The two languages must state the same Android floor. A listing that promises a lower floor in one
# language than the other is a support problem in exactly the market that reads the wrong one.
ko_floor = re.findall(r"Android (\d+) 이상", listing)
check(bool(ko_floor), "the Korean copy states an Android floor")

# Play renders the description verbatim. A block re-wrapped for editor readability puts a break in
# the middle of every sentence, which is how the listing shipped shredded the first time.
# The signal is CONTINUATION, not line length: a hard wrap leaves a line whose successor picks the
# sentence up mid-flow (lowercase, not a new bullet). Length alone false-flagged a legitimately
# short bullet that happened to end in a URL.
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

declared = set(re.findall(r"android\.permission\.([A-Z_]+)", manifest))
declared -= {"INTERNET", "ACCESS_NETWORK_STATE"}  # removed at merge by tools:node="remove"

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

# ---- version facts must match the build, not a memory of it ------------------------------------
gradle = read("app/build.gradle.kts")
min_sdk = re.search(r"minSdk\s*=\s*(\d+)", gradle).group(1)

# The README carried three different Compose versions at once: the badge, its toolchain table, and
# the catalog pin. Make the catalog the source of truth and compare both public surfaces plus the
# project authority table against it.
catalog = read("gradle/libs.versions.toml")
catalog_compose = re.search(r'^composeBom\s*=\s*"([^"]+)"', catalog, re.MULTILINE)
assert catalog_compose, "composeBom missing from version catalog"
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

for rel in (
    "docs/play-store-listing.md", "docs/play-console-submit.md", "README.md",
    # Added after an audit found the stale floor in BOTH of these while this check was green: it
    # only looked at store-facing docs, and the as-built authority is exactly where a wrong floor
    # does the most damage.
    "docs/ARCHITECTURE.md", "CLAUDE.md",
):
    text = read(rel)
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
submit = read("docs/play-console-submit.md")

# The release board and operator sheet are independent prose authorities, so bind their current
# state with one deliberately boring machine-readable marker. "Target" names source intent;
# "artifact" names whether immutable upload bytes exist. Conflating those two is how the board
# called v1.0.2 a candidate while the sheet correctly said there were no current bytes.
version_name_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
assert version_name_match, "versionName missing from app/build.gradle.kts"
expected_release_target = f"v{version_name_match.group(1)}"


def release_state(text: str) -> tuple[str, str] | None:
    marker = re.search(
        r"<!--\s*release-state:\s*target=([^\s]+)\s+artifact=([^\s]+)\s*-->",
        text,
    )
    return marker.groups() if marker else None


backlog = read("docs/BACKLOG.md")
backlog_release_state = release_state(backlog)
submit_release_state = release_state(submit)
backlog_prose = re.sub(r"[^a-z0-9./=-]+", " ", backlog.casefold())
submit_prose = re.sub(r"[^a-z0-9./=-]+", " ", submit.casefold())
check(
    backlog_release_state == submit_release_state == (expected_release_target, "none"),
    "release authorities agree on target and no-current-artifact state",
    f"build={expected_release_target}, backlog={backlog_release_state}, submit={submit_release_state}",
)
check(
    "current source/release target" in backlog_prose
    and "no current artifact candidate exists" in backlog_prose
    and "source/release target" in submit_prose
    and "no current artifact candidate exists" in submit_prose,
    "active release prose distinguishes source target from artifact candidate",
)

# The active external-action board once preserved its July GitHub About copy as if it were current,
# including the now-false claim that DNG existed only in TELE. Historical investigation sections may
# retain old route facts; this guard scopes the prohibition to the live owner-action section.
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

# Direct Gradle release entry points intentionally fail closed: only the immutable-source wrapper
# can compile/package release bytes. Historical evidence may retain its old commands/paths when the
# containing heading explicitly says historical/superseded/archived. Active mentions of mutable
# app/build output are permitted only when the same local paragraph explicitly rejects that path as
# an identity; they must never promise it as the result of a current release command.
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
    lines = markdown_lines_with_history(read(rel))
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
    check(
        "tools/build_immutable_release.py" in read(rel),
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
    text = read(rel)
    running = re.findall(r"\(?`?main`? is now [\d,]+", text)
    check(not running, f"{rel} carries no running count cross-reference", f"{running}")

# ---- referenced repo paths must exist -----------------------------------------------------------
for rel in ("README.md", "docs/ARCHITECTURE.md", "docs/TESTING.md", "docs/FIELD_CHECKS.md"):
    text = read(rel)
    refs = re.findall(r"`((?:app|docs|tools|device-tests|gradle)/[A-Za-z0-9_./-]+\.(?:kt|md|py|txt|toml|kts))`", text)
    dead = [r for r in refs if "..." not in r and not (ROOT / r).exists()]
    check(not dead, f"{rel} references only files that exist", f"{dead}")

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
)
check(
    all(owner in architecture for owner in required_owners),
    "architecture maps every current review-critical owner",
    f"missing {[owner for owner in required_owners if owner not in architecture]}",
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
stage_text = architecture + read("docs/play-store-listing.md")
check(
    all(term in stage_text for term in (
        "HLG10 Camera2 source", "display-referred", "8-bit", "Main10",
    )),
    "camera/store authority names every non-SDR pipeline stage",
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
backlog = read("docs/BACKLOG.md")
check(
    re.search(r"<activity\b[^>]*\bandroid:screenOrientation=", manifest, re.S) is None
    and "lockPortraitOnHandsets" in main_activity
    and "manifest carries no" in claude
    and "manifest orientation restriction is" in backlog,
    "orientation authority joins absent manifest restriction to handset runtime lock",
)
check(
    "current TELE capture is eligible" not in architecture
    and "DNG is not TELE-only" in architecture,
    "architecture keeps DNG routing standalone-any-rear rather than TELE-only",
)
current_comments = read("app/src/main/kotlin/me/hletrd/telecampro/camera/CaptureCapabilities.kt")
build_script = read("app/build.gradle.kts")
check(
    "capability-gated read here misses" not in current_comments
    and "release keeps minify off" not in build_script,
    "current comments describe maximum-resolution discovery and enabled R8",
)
check(
    all(
        "python3 tools/verify_host.py" in authority
        for authority in (claude, architecture, read("docs/TESTING.md"))
    )
    and "device-harness self-tests" in architecture
    and "device-tests/tests" in read("docs/TESTING.md"),
    "all host-gate authorities document the consolidated non-device suite",
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
check(
    "snapshots the exact scoped source whether the checkout is" in device_test_readme
    and "clean or dirty" in device_test_readme
    and "Evidence runs must pass the wrapper's printed immutable APK" in device_test_readme
    and "requires clean committed source" not in device_test_readme
    and "match `app/build/outputs/apk/debug/app-debug.apk`" not in device_test_readme,
    "device harness requires the printed clean-or-dirty immutable debug artifact",
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
field_checks = read("docs/FIELD_CHECKS.md")
check(
    "rotationOverrideDeg = RotationMath.windowPreviewRotationDegrees(windowRotationDeg)" in gl_pipeline
    and "window-rotation term" in claude
    and "raw, inverted field" in claude
    and "one-call `rotationOverrideDeg`" in field_checks
    and "raw, inverted field" in field_checks
    and "SUPERSEDED — Loupe Overview" in backlog
    and "predates the later per-draw" in backlog,
    "Loupe Overview field criteria match the per-draw orientation authority",
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
obsolete_finder_contracts = (
    "TELE + Photo + 4:3 + loupe",
    "toggle && TELE && PHOTO && 4:3",
    "overview only ever draws under Photo + 4:3 + Teleconverter",
    "Mode is a finder-gate input (photo-only)",
    "finder PIP is 4:3-only",
)
check(
    not any(
        obsolete in "\n".join((camera_actions, camera_engine, gl_pipeline, loupe_compose_test))
        for obsolete in obsolete_finder_contracts
    )
    and "active loupe + (TELE or unified zoom >= 3x)" in loupe_compose_test
    and "Video ignoring still aspect" in loupe_compose_test,
    "Loupe source and Compose-test guidance rejects the superseded Photo/TELE-only gate",
)

# ---- coverage residual authority must stay machine-checked, not copied into prose --------------
testing_doc = read("docs/TESTING.md")
residual_manifest = read("tools/coverage/partition-a-residuals.txt")
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

print(f"\n{CHECKS} checks, {len(FAILURES)} failed")
sys.exit(1 if FAILURES else 0)
