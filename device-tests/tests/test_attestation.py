from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


DEVICE_TESTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEVICE_TESTS))

import run as runner  # noqa: E402
from dtest.adb import MAIN_ACTIVITY, DisplayMetrics  # noqa: E402


class RunAttestationTest(unittest.TestCase):
    def test_git_identity_records_head_and_exact_dirty_rows(self) -> None:
        responses = [
            SimpleNamespace(stdout="0123456789abcdef\n"),
            SimpleNamespace(stdout=" M device-tests/run.py\n?? local-note.txt\n"),
        ]
        with patch.object(runner.subprocess, "run", side_effect=responses) as run_command:
            identity = runner.git_identity(Path("/repo"))

        self.assertEqual(
            identity,
            {
                "head": "0123456789abcdef",
                "dirty": True,
                "status": [" M device-tests/run.py", "?? local-note.txt"],
            },
        )
        self.assertEqual(run_command.call_count, 2)
        self.assertEqual(run_command.call_args_list[0].args[0], ["git", "rev-parse", "HEAD"])
        self.assertEqual(
            run_command.call_args_list[1].args[0],
            ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        )

    def test_device_state_records_display_foreground_and_restored_settings(self) -> None:
        class StateAdb:
            def __init__(self):
                self.commands: list[str] = []

            @staticmethod
            def display_metrics() -> DisplayMetrics:
                return DisplayMetrics(1440, 3168, 560)

            @staticmethod
            def resumed_activity() -> str:
                return MAIN_ACTIVITY

            def shell(self, command: str) -> str:
                self.commands.append(command)
                return {
                    "settings get system font_scale": "0.8",
                    "settings get system accelerometer_rotation": "0",
                    "settings get system user_rotation": "0",
                }[command]

        adb = StateAdb()

        self.assertEqual(
            runner.device_state(adb),  # type: ignore[arg-type]
            {
                "foreground_component": MAIN_ACTIVITY,
                "display": {"width_px": 1440, "height_px": 3168, "density_dpi": 560},
                "settings": {
                    "font_scale": "0.8",
                    "accelerometer_rotation": "0",
                    "user_rotation": "0",
                },
            },
        )
        self.assertEqual(
            adb.commands,
            [f"settings get system {name}" for name in runner.RESTORED_SETTINGS],
        )

    def test_restoration_errors_require_main_and_unchanged_settings(self) -> None:
        before = {
            "foreground_component": MAIN_ACTIVITY,
            "settings": {
                "font_scale": "0.8",
                "accelerometer_rotation": "0",
                "user_rotation": "0",
            },
        }
        after = copy.deepcopy(before)
        self.assertEqual(runner.restoration_errors(before, after), [])
        self.assertEqual(runner.attested_exit_code(0, []), 0)

        after["foreground_component"] = "com.android.launcher/.Launcher"
        after["settings"]["font_scale"] = "1.0"  # type: ignore[index]
        errors = runner.restoration_errors(before, after)
        self.assertTrue(any("MainActivity" in error for error in errors))
        self.assertTrue(any("font_scale" in error for error in errors))
        self.assertEqual(runner.attested_exit_code(0, errors), 2)
        self.assertEqual(runner.attested_exit_code(1, errors), 1)
        self.assertEqual(
            runner.restoration_errors(before, None),
            ["post-run device state could not be collected"],
        )

    def test_attestation_hashes_sorted_artifacts_and_excludes_itself(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            report_dir = Path(temp_dir)
            evidence = report_dir / "evidence"
            evidence.mkdir()
            (report_dir / "report.md").write_text("report\n", encoding="utf-8")
            (evidence / "frame.bin").write_bytes(b"frame")
            (report_dir / runner.ATTESTATION_NAME).write_text("stale", encoding="utf-8")
            (report_dir / runner.ATTESTATION_SHA_NAME).write_text("stale", encoding="utf-8")

            manifest = runner.artifact_manifest(report_dir)
            self.assertEqual(
                [artifact["path"] for artifact in manifest],
                ["evidence/frame.bin", "report.md"],
            )
            self.assertEqual(manifest[0]["sha256"], hashlib.sha256(b"frame").hexdigest())

            document = {"schema_version": 1, "artifacts": manifest}
            attestation, sidecar = runner.write_attestation(report_dir, document)
            payload = attestation.read_bytes()
            self.assertEqual(json.loads(payload), document)
            self.assertEqual(
                sidecar.read_text(encoding="utf-8"),
                f"{hashlib.sha256(payload).hexdigest()}  {runner.ATTESTATION_NAME}\n",
            )
            self.assertEqual(runner.artifact_manifest(report_dir), manifest)

    def test_report_summary_exposes_restoration_failure_and_final_exit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            report_dir = Path(temp_dir)
            report = report_dir / "report.md"
            report.write_text("# Device test report\n", encoding="utf-8")

            runner.append_attestation_summary(
                report_dir,
                final_exit_code=2,
                errors=["font_scale changed from '0.8' to '1.0'"],
            )

            rendered = report.read_text(encoding="utf-8")
            self.assertIn("restoration: **FAIL**", rendered)
            self.assertIn("final CLI exit code: `2`", rendered)
            self.assertIn(runner.ATTESTATION_NAME, rendered)
            self.assertIn("font_scale changed", rendered)


if __name__ == "__main__":
    unittest.main()
