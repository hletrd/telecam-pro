"""Upload-key policy shared by every release reader: the deny-list (SEC2-2) and the secret floor.

`tools/blocked-upload-certificates.txt` is the data; app/build.gradle.kts reads the same file. A
missing or malformed list is a hard error: a deny-list that silently loads empty would turn every
blocked certificate back into an acceptable signer.

The deny-list blocks the retired key's CERTIFICATE, not the weakness that got it retired. The
generated-secret floor below is therefore applied by both wrappers to the effective store and key
passwords (SEC3-2 / AGG3-32): it used to live in the scoped helper alone, so the documented
`build_immutable_release.py` path would sign with a freshly rotated but equally weak key.
"""

from __future__ import annotations

import pathlib
import re


BLOCKED_UPLOAD_CERTIFICATES_PATH = (
    pathlib.Path(__file__).resolve().parent / "blocked-upload-certificates.txt"
)
_SHA256 = re.compile(r"[0-9a-f]{64}")


def normalize_certificate_sha256(value: str) -> str:
    """Accept keytool's colon form or bare hex, either case; return lowercase bare hex or ''."""
    normalized = value.strip().replace(":", "").casefold()
    return normalized if _SHA256.fullmatch(normalized) else ""


def parse_blocked_upload_certificates(text: str) -> frozenset[str]:
    entries = set()
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if _SHA256.fullmatch(line) is None:
            raise ValueError(f"blocked upload certificate line {number} is not a lowercase SHA-256")
        entries.add(line)
    if not entries:
        raise ValueError("blocked upload certificate deny-list is empty")
    return frozenset(entries)


def load_blocked_upload_certificates(
    path: pathlib.Path = BLOCKED_UPLOAD_CERTIFICATES_PATH,
) -> frozenset[str]:
    return parse_blocked_upload_certificates(path.read_text(encoding="utf-8"))


BLOCKED_UPLOAD_CERT_SHA256 = load_blocked_upload_certificates()


def is_blocked_upload_certificate(value: str) -> bool:
    """Fail closed: an unparseable fingerprint is treated as blocked, never as acceptable."""
    normalized = normalize_certificate_sha256(value)
    return not normalized or normalized in BLOCKED_UPLOAD_CERT_SHA256


MIN_STRONG_PASSWORD_LENGTH = 20
MIN_STRONG_PASSWORD_CLASSES = 3
MAX_MONOTONIC_RUN = 5
# SEC4-5 / AGG4-42: length + classes + "not one repeated character" still accepted 18 x `a` + `A1`,
# `Aa1` x 7, and a dictionary word bracketing a digit walk. Generated output (for example
# `secrets.token_urlsafe(32)`, 43 characters over a 64-symbol alphabet) clears all three structural
# rules below with overwhelming probability; a human-memorable shape does not.
MIN_DISTINCT_CHARACTERS = 12
MAX_REPEAT_RUN = 3


def _has_monotonic_run(value: str) -> bool:
    """Reject human-memorable alphabetic/numeric walks without claiming entropy proof."""
    run = 1
    direction = 0
    previous: str | None = None
    for character in value.casefold():
        if previous is None or not (
            (previous.isascii() and previous.isalpha() and character.isascii() and character.isalpha())
            or (previous.isascii() and previous.isdigit() and character.isascii() and character.isdigit())
        ):
            run = 1
            direction = 0
        else:
            step = ord(character) - ord(previous)
            if step in {-1, 1}:
                if step == direction:
                    run += 1
                else:
                    direction = step
                    run = 2
                if run > MAX_MONOTONIC_RUN:
                    return True
            else:
                run = 1
                direction = 0
        previous = character
    return False


def _has_long_repeat_run(value: str) -> bool:
    """True when one character repeats more than MAX_REPEAT_RUN times in a row."""
    run = 0
    previous: str | None = None
    for character in value:
        run = run + 1 if character == previous else 1
        if run > MAX_REPEAT_RUN:
            return True
        previous = character
    return False


def _has_short_period(value: str) -> bool:
    """True when the whole value repeats a block of at most half its length (`Aa1Aa1...`)."""
    length = len(value)
    for period in range(1, length // 2 + 1):
        if all(value[index] == value[index + period] for index in range(length - period)):
            return True
    return False


def meets_generated_secret_floor(value: str) -> bool:
    """True only for a generated-looking secret; the answer never says WHY a value failed."""
    if len(value) < MIN_STRONG_PASSWORD_LENGTH or value != value.strip():
        return False
    classes = (
        any(character.islower() for character in value),
        any(character.isupper() for character in value),
        any(character.isdigit() for character in value),
        any(not character.isalnum() for character in value),
    )
    return (
        sum(classes) >= MIN_STRONG_PASSWORD_CLASSES
        and len(set(value)) >= MIN_DISTINCT_CHARACTERS
        and not _has_long_repeat_run(value)
        and not _has_short_period(value)
        and not _has_monotonic_run(value)
    )
