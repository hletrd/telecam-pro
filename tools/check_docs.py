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

import pathlib
import re
import sys

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

short = fenced(listing, "## Short description")
check(len(short) <= 80, "short description <= 80 chars", f"{len(short)}")

full = fenced(listing, "## Full description")
check(len(full) <= 4000, "full description <= 4000 chars", f"{len(full)}")

notes = fenced(listing, "## Release notes")
inner = re.sub(r"</?en-US>", "", notes).strip()
check(len(inner) <= 500, "release notes <= 500 chars", f"{len(inner)}")

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

# ---- version facts must match the build, not a memory of it ------------------------------------
gradle = read("app/build.gradle.kts")
min_sdk = re.search(r"minSdk\s*=\s*(\d+)", gradle).group(1)

for rel in ("docs/play-store-listing.md", "docs/play-console-submit.md", "README.md"):
    text = read(rel)
    # "Requires Android 16" style claims are what the audit caught; tie them to the real floor.
    android_release = {33: "13", 34: "14", 35: "15", 36: "16"}[int(min_sdk)]
    # Ignore occurrences inside quotation marks: the listing doc records the WRONG old wording in
    # its audit note ("Requires Android 16" — the floor is minSdk 33), and flagging a doc for
    # quoting its own corrected mistake is the checker being fooled by its own subject matter.
    bad = re.findall(r'(?<!")Requires Android (\d+)', text)
    check(
        all(v == android_release for v in bad),
        f"{rel} states the real Android floor",
        f"says {bad}, minSdk {min_sdk} = Android {android_release}",
    )

print(f"\n{CHECKS} checks, {len(FAILURES)} failed")
sys.exit(1 if FAILURES else 0)
