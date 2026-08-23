from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


DEVICE_TESTS = Path(__file__).resolve().parents[1]
REPO_ROOT = DEVICE_TESTS.parent
sys.path.insert(0, str(DEVICE_TESTS))

from dtest.adb import Adb, MEDIA_RELATIVE_PATH, UiTree  # noqa: E402
from dtest.contracts import (  # noqa: E402
    harness_source_manifest,
    inspect_apk_contract,
    parse_apk_contract,
    production_capture_subdir,
    source_manifest_sha256,
)
from dtest.selectors import OPEN_SETTINGS, START_RECORDING  # noqa: E402


BADGING = """\
package: name='me.hletrd.telecampro.debug' versionCode='4'
launchable-activity: name='me.hletrd.telecampro.MainActivity' label=''
"""
XMLTREE = """\
E: activity
  A: android:name(0x01010003)="me.hletrd.telecampro.ui.UiSnapshotActivity" (Raw: "me.hletrd.telecampro.ui.UiSnapshotActivity")
E: activity
  A: android:name(0x01010003)="me.hletrd.telecampro.MainActivity" (Raw: "me.hletrd.telecampro.MainActivity")
"""


class ApkContractTest(unittest.TestCase):
    def test_manifest_contract_uses_current_exact_components(self) -> None:
        contract = parse_apk_contract(BADGING, XMLTREE)
        self.assertEqual(contract.application_id, "me.hletrd.telecampro.debug")
        self.assertEqual(
            contract.launcher_component,
            "me.hletrd.telecampro.debug/me.hletrd.telecampro.MainActivity",
        )
        self.assertEqual(
            contract.snapshot_component,
            "me.hletrd.telecampro.debug/me.hletrd.telecampro.ui.UiSnapshotActivity",
        )

    def test_inspection_runs_both_tools_against_the_exact_apk(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "attested.apk"
            apk.write_bytes(b"apk")
            commands: list[list[str]] = []

            def run_text(command):
                commands.append(list(command))
                return BADGING if command[0] == "aapt-test" else XMLTREE

            contract = inspect_apk_contract(
                apk,
                run_text=run_text,
                aapt="aapt-test",
                aapt2="aapt2-test",
            )

            self.assertEqual(contract.application_id, "me.hletrd.telecampro.debug")
            self.assertEqual(commands[0][-1], str(apk))
            self.assertEqual(commands[1][-1], str(apk))


class SourceIdentityTest(unittest.TestCase):
    def test_sorted_source_manifest_changes_after_one_byte_change(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "dtest").mkdir()
            source = root / "dtest/runner.py"
            source.write_text("x = 1\n", encoding="utf-8")
            (root / "README.md").write_text("contract\n", encoding="utf-8")
            (root / "reports").mkdir()
            (root / "reports/evidence.py").write_text("ignored\n", encoding="utf-8")

            before = harness_source_manifest(root)
            before_digest = source_manifest_sha256(before)
            source.write_text("x = 2\n", encoding="utf-8")
            after = harness_source_manifest(root)

            self.assertEqual([entry["path"] for entry in before], ["README.md", "dtest/runner.py"])
            self.assertNotEqual(before_digest, source_manifest_sha256(after))


class ProductionContractTest(unittest.TestCase):
    def test_harness_media_directory_matches_production_constant(self) -> None:
        subdir = production_capture_subdir(REPO_ROOT)
        self.assertEqual(subdir, "TeleCamPro")
        self.assertEqual(MEDIA_RELATIVE_PATH, f"DCIM/{subdir}/")


class LocalizedSelectorTest(unittest.TestCase):
    @staticmethod
    def tree(*descriptions: str) -> UiTree:
        nodes = "".join(
            f'<node text="" content-desc="{desc}" bounds="[0,0][100,100]" />'
            for desc in descriptions
        )
        return UiTree(f"<hierarchy>{nodes}</hierarchy>")

    def test_stable_selector_resolves_english_and_korean_exactly(self) -> None:
        english = self.tree("Open settings", "Start recording")
        korean = self.tree("설정 열기", "녹화 시작")

        self.assertIsNotNone(english.find_selector(OPEN_SETTINGS, "en-US"))
        self.assertIsNotNone(english.find_selector(START_RECORDING, "en-US"))
        self.assertIsNotNone(korean.find_selector(OPEN_SETTINGS, "ko-KR"))
        self.assertIsNotNone(korean.find_selector(START_RECORDING, "ko-KR"))
        self.assertIsNone(korean.find_selector(OPEN_SETTINGS, "en-US"))

    def test_locale_attestation_uses_current_user_override_then_system_fallback(self) -> None:
        class LocaleAdb(Adb):
            def __init__(self, responses: dict[str, str], workdir: Path):
                super().__init__("test", workdir)
                self.responses = responses
                self.commands: list[str] = []

            def shell(self, command: str, timeout: int = 60) -> str:
                del timeout
                self.commands.append(command)
                return self.responses[command]

        with tempfile.TemporaryDirectory() as temp_dir:
            common = {
                "am get-current-user": "10",
                "getprop persist.sys.locale": "en-US",
            }
            override_command = (
                "cmd locale get-app-locales me.hletrd.telecampro.debug "
                "--user 10 2>/dev/null || true"
            )
            overridden = LocaleAdb(
                {**common, override_command: "Locales for app: [ko-KR]"},
                Path(temp_dir),
            )
            fallback = LocaleAdb(
                {**common, override_command: "Locales for app: []"},
                Path(temp_dir),
            )

            self.assertEqual(overridden.locale_state()["effective"], "ko-KR")
            self.assertEqual(fallback.locale_state()["effective"], "en-US")
            self.assertIn("--user 10", overridden.commands[1])


if __name__ == "__main__":
    unittest.main()
