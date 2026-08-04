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
    check(
        all(v == android_release for v in bad),
        f"{rel} states the real Android floor",
        f"says {bad}, minSdk {min_sdk} = Android {android_release}",
    )

# ---- the upload instruction must not name a superseded artifact -------------------------------
# It did: step 1 hard-coded a hash that went stale while the pin above it moved, so the sheet told
# the operator to upload a bundle its own superseded list forbids — caught mid-upload.
submit = read("docs/play-console-submit.md")

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

# The pin must agree with itself. The banner names the commit, the artifacts heading names it again,
# and the two drifted apart once — the banner moved to a new cut while the heading below it still
# said the old one, which is the shape that sends an operator to the wrong bundle.
banner = re.search(r"UPLOAD-READY \([\d-]+\)[^\n]*`main` at `([0-9a-f]{7})`", submit)
pin_start = submit.index("### Final v1 upload artifacts")
pin_section = submit[pin_start:submit.index("\n### ", pin_start + 1)]
heading = re.search(r"`main` at `([0-9a-f]{7})`", pin_section)
check(bool(banner and heading), "the pin states its commit in both places")
if banner and heading:
    check(banner.group(1) == heading.group(1), "banner and artifact heading name the same commit",
          f"{banner.group(1)} vs {heading.group(1)}")

# A pinned digest must never also appear in the do-not-upload list: that combination tells the
# operator both to upload and not to upload the same bytes. Scoped to the CURRENT pin's section —
# the historical sections quote their own digests, which are superseded on purpose.
pinned = set(re.findall(r"([0-9a-f]{64})", pin_section))
check(
    not any(p[:8] in superseded for p in pinned),
    "no pinned artifact is also listed as superseded",
    f"{[p[:8] for p in pinned if p[:8] in superseded]}",
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

print(f"\n{CHECKS} checks, {len(FAILURES)} failed")
sys.exit(1 if FAILURES else 0)
