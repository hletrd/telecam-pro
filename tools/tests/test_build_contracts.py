"""Build-script contracts that the host gate relies on but cannot observe from a cached run."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


class KotlinWarningsAreFatalTest(unittest.TestCase):
    def test_kotlin_compiler_warnings_fail_every_compile(self) -> None:
        # QA4-1 / AGG4-76: UP-TO-DATE compile tasks print no `w:` lines, so only a fatal-warnings
        # flag lets a cached green gate still attest a warning-free tree.
        gradle = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        block = re.search(r"(?ms)^kotlin \{\n.*?^\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("allWarningsAsErrors.set(true)", block.group(0))
        # No per-task or per-source-set escape hatch turns it back off.
        self.assertNotIn("allWarningsAsErrors.set(false)", gradle)
        self.assertNotIn("allWarningsAsErrors = false", gradle)
        self.assertNotIn("suppressWarnings", gradle)


if __name__ == "__main__":
    unittest.main()
