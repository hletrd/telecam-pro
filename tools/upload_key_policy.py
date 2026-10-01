"""The one blocked upload-certificate deny-list shared by every release reader (SEC2-2).

`tools/blocked-upload-certificates.txt` is the data; app/build.gradle.kts reads the same file. A
missing or malformed list is a hard error: a deny-list that silently loads empty would turn every
blocked certificate back into an acceptable signer.
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
