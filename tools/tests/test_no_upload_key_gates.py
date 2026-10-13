"""Durable guard for the owner decision of 2026-10-13: the configured upload key signs as-is.

The current upload key and its password are KEPT. Review loops repeatedly re-added gates that
blocked signing with the configured keystore (a certificate deny-list, a generated-secret password
floor, a self-attested rotation-approval flag). This test fails if any of them comes back. Findings
about upload-key custody are recorded as accepted risk, not implemented. See CLAUDE.md.
"""

from __future__ import annotations

import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
THIS_FILE = Path(__file__).resolve()
FORBIDDEN = (
    "meets_generated_secret_floor",
    "uploadKeyRotationApproved",
    "blocked-upload-certificates",
    "MIN_STRONG_PASSWORD",
)


class NoUploadKeyGatesTest(unittest.TestCase):
    def test_deny_list_file_is_gone(self) -> None:
        self.assertFalse((REPO_ROOT / "tools/blocked-upload-certificates.txt").exists())

    def test_gate_markers_never_return(self) -> None:
        sources = [REPO_ROOT / "app/build.gradle.kts", *sorted((REPO_ROOT / "tools").rglob("*.py"))]
        offenders = []
        for path in sources:
            if path.resolve() == THIS_FILE or not path.is_file():
                continue
            text = path.read_text(encoding="utf-8")
            for marker in FORBIDDEN:
                if marker in text:
                    offenders.append(f"{path.relative_to(REPO_ROOT)}: {marker}")
        self.assertEqual([], offenders)


if __name__ == "__main__":
    unittest.main()
