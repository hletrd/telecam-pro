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


def read_private(rel: str) -> str | None:
    assert rel in PRIVATE_DOCS, f"unregistered private document: {rel}"
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
    assert m, f"no fenced block under {heading!r}"
    return m.group(1)


def language_fenced(text: str, heading: str, language: str) -> str:
    """The first language-tagged fence after a heading."""
    start = text.index(heading)
    m = re.search(rf"```{re.escape(language)}\n(.*?)\n```", text[start:], re.S)
    assert m, f"no {language!r} fenced block under {heading!r}"
    return m.group(1)


# ---- store copy: Play's hard limits, and the wrapping trap -------------------------------------
listing = read_private("docs/play-store-listing.md")

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
    and required_recapture.get("source_manifest_schema") == 2,
    "phone screenshot manifest records a valid fail-closed recapture state",
)

if listing is None:
    skip_private("Play listing copy and screenshot-authority checks", "docs/play-store-listing.md")
else:
    asset_authorities = re.sub(
        r"\s+",
        " ",
        listing + "\n" + read("docs/play-console-submit.md"),
    )
    stale_state_valid = (
        stale_manifest_valid
        and "NOT SUBMISSION-READY" in listing
        and "NOT SUBMISSION-READY" in read("docs/play-console-submit.md")
    )
    ready_state_valid = (
        ready_manifest_valid
        and "NOT SUBMISSION-READY" not in asset_authorities
        and "SUBMISSION-READY" in asset_authorities
    )
    check(
        stale_state_valid or ready_state_valid,
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
for rel in ("README.md", "docs/ARCHITECTURE.md", "docs/TESTING.md", "docs/FIELD_CHECKS.md"):
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

# The as-built Module Map omitted two central concurrency owners while still naming every other
# production Kotlin file. Treat filenames as the explicit inventory: grouped leaf rows remain fine,
# but adding a production module without naming it in the map is now a checked documentation defect.
module_map = architecture.split("## Module Map", 1)[1].split("\n---", 1)[0]
production_modules = sorted((ROOT / "app/src/main/kotlin").rglob("*.kt"))
missing_module_rows = [
    path.relative_to(ROOT).as_posix()
    for path in production_modules
    if f"`{path.name}`" not in module_map
]
check(
    not missing_module_rows,
    "Architecture Module Map names every production Kotlin module",
    str(missing_module_rows),
)

# Release signing has exactly one FILE input and three optional secret VALUE overrides. The file
# path once had its own environment fallback, and then a caller-authored Gradle override. Both bypass
# the wrapper's descriptor owner. Bind build logic, wrapper, example, and public/as-built
# documentation so that neither resolver can return under a renamed key or stale instruction.
release_wrapper = read("tools/build_immutable_release.py")
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
build_script = read("app/build.gradle.kts")
check(
    "capability-gated read here misses" not in current_comments
    and "release keeps minify off" not in build_script,
    "current comments describe maximum-resolution discovery and enabled R8",
)
check(
    all("python3 tools/verify_host.py" in authority for authority in (claude, architecture))
    and "device-harness self-tests" in architecture,
    "committed host-gate authorities document the consolidated non-device suite",
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

# Exact UI names come from resources, not from a second hand-maintained spelling in prose. The
# screenshot validity manifest already treats the superseded pair as blocking; current architecture
# UX policy, QA runbook, and device selectors must therefore use the same resource-backed names.
architecture_ui_layout = architecture.split("**UI layout (ProSheet.kt):**", 1)[1].split(
    "**Quick Fn controls (ManualDials.kt):**",
    1,
)[0]
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
if listing is None:
    skip_private("active tablet asset guidance keeps the deleted operator rail retired", "docs/play-store-listing.md")
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
        "active tablet asset guidance keeps the deleted operator rail retired",
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
field_checks = read("docs/FIELD_CHECKS.md")
check(
    "rotationOverrideDeg = RotationMath.windowPreviewRotationDegrees(windowRotationDeg)" in gl_pipeline
    and "window-rotation term" in claude
    and "raw, inverted field" in claude
    and "one-call `rotationOverrideDeg`" in field_checks
    and "raw, inverted field" in field_checks,
    "committed Loupe Overview criteria match the per-draw orientation authority",
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
