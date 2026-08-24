from __future__ import annotations

import hashlib
import io
import importlib.util
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
MOTION_SOURCE = (
    REPO_ROOT
    / "app/src/main/kotlin/me/hletrd/telecampro/gl/MotionInversion.kt"
)


def load_verify_host():
    source = REPO_ROOT / "tools/verify_host.py"
    spec = importlib.util.spec_from_file_location("telecam_verify_host", source)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load tools/verify_host.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class MotionBisectionIsolationTest(unittest.TestCase):
    def test_success_failure_and_term_leave_production_source_byte_identical(self) -> None:
        before = MOTION_SOURCE.read_bytes()
        before_sha = hashlib.sha256(before).hexdigest()

        for mode, expected_status in (("success", 0), ("failure", 42), ("term", 143)):
            result = subprocess.run(
                ["bash", "tools/bisect_motion_signs.sh", "unused-test-serial"],
                cwd=REPO_ROOT,
                env={**os.environ, "BISECT_MOTION_TEST_MODE": mode},
                capture_output=True,
                text=True,
                timeout=20,
            )
            self.assertEqual(result.returncode, expected_status, result.stderr)
            self.assertEqual(hashlib.sha256(MOTION_SOURCE.read_bytes()).hexdigest(), before_sha)
            self.assertEqual(MOTION_SOURCE.read_bytes(), before)


class FleetOwnershipTest(unittest.TestCase):
    def test_global_process_matching_is_absent(self) -> None:
        source = (REPO_ROOT / "tools/adb_fleet.sh").read_text(encoding="utf-8")
        self.assertNotIn("pkill", source)
        self.assertIn("owned_proxy_alive", source)
        self.assertIn("Stop only the repository-owned proxies", source)

    def test_forged_pid_record_is_refused_without_signalling_that_pid(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            state = Path(temp_dir)
            record = state / "6112.pid"
            record.write_text(
                f"{os.getpid()}|forged|{REPO_ROOT / 'tools/adb_proxy.py'}|"
                "172.30.50.112|6112:5555\n",
                encoding="utf-8",
            )
            result = subprocess.run(
                ["bash", "tools/adb_fleet.sh", "--stop-owned"],
                cwd=REPO_ROOT,
                env={**os.environ, "ADB_FLEET_STATE_DIR": str(state)},
                capture_output=True,
                text=True,
                timeout=10,
            )

            self.assertEqual(result.returncode, 2)
            self.assertIn("refusing invalid ownership record", result.stderr)
            os.kill(os.getpid(), 0)  # the unrelated test process is still alive


class DeviceProbeParityTest(unittest.TestCase):
    def test_still_probe_calls_the_shipping_shape_first_selector(self) -> None:
        source = (
            REPO_ROOT
            / "app/src/androidTest/kotlin/me/hletrd/telecampro/camera/StillSizeProbeTest.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("val picked = pickStillSize(", source)

    def test_encoder_probe_uses_the_exact_production_component_axis(self) -> None:
        source = (
            REPO_ROOT
            / "app/src/androidTest/kotlin/me/hletrd/telecampro/video/EncoderProfileLevelProbeTest.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("EncoderCaps.load().candidatesFor", source)
        self.assertIn("MediaCodecList.REGULAR_CODECS", source)
        self.assertIn("MediaCodec.createByCodecName", source)
        self.assertNotIn("createEncoderByType", source)


class ConsolidatedHostGateTest(unittest.TestCase):
    def test_gate_runs_every_non_device_quality_suite(self) -> None:
        source = (REPO_ROOT / "tools/verify_host.py").read_text(encoding="utf-8")
        for required in (
            ":app:assembleDebug",
            ":app:testDebugUnitTest",
            ":app:lintDebug",
            ":app:verifyPartitionACoverage",
            "tools/tests",
            "tools/coverage/tests",
            "device-tests/tests",
            "tools/check_docs.py",
            ":app:lintRelease",
            ":app:assembleRelease",
            ":app:bundleRelease",
            "tools/build_immutable_release.py",
        ):
            self.assertIn(required, source)
        self.assertIn('"JAVA_HOME": str(home)', source)

    def test_diff_gate_rejects_staged_and_unstaged_whitespace_errors(self) -> None:
        command = load_verify_host().repository_diff_check_command()
        self.assertEqual(command, ["git", "diff", "--check", "HEAD", "--"])

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            tracked = root / "tracked.txt"
            tracked.write_text("clean\n", encoding="utf-8")
            subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Gate Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "gate@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "add", "tracked.txt"], cwd=root, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)

            tracked.write_text("staged trailing space \n", encoding="utf-8")
            subprocess.run(["git", "add", "tracked.txt"], cwd=root, check=True)
            staged = subprocess.run(command, cwd=root, capture_output=True, text=True)
            self.assertNotEqual(staged.returncode, 0, staged.stdout + staged.stderr)
            self.assertIn("trailing whitespace", staged.stdout + staged.stderr)

            subprocess.run(["git", "restore", "--staged", "tracked.txt"], cwd=root, check=True)
            unstaged = subprocess.run(command, cwd=root, capture_output=True, text=True)
            self.assertNotEqual(unstaged.returncode, 0, unstaged.stdout + unstaged.stderr)
            self.assertIn("trailing whitespace", unstaged.stdout + unstaged.stderr)

    def test_documentation_gate_runs_from_committed_export_without_private_docs(self) -> None:
        def extract(payload: bytes, destination: Path) -> None:
            destination.mkdir()
            with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
                archive.extractall(destination, filter="data")

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            staging = root / "staging"
            exported = root / "exported"
            baseline = subprocess.run(
                ["git", "archive", "HEAD"],
                cwd=REPO_ROOT,
                check=True,
                capture_output=True,
            ).stdout
            extract(baseline, staging)

            # The test must pass before this change itself is committed. Overlay only the tracked
            # checker under test, commit that complete public tree, and test a second export. No
            # ignored/private document is copied from the maintainer workspace.
            shutil.copy2(REPO_ROOT / "tools/check_docs.py", staging / "tools/check_docs.py")
            subprocess.run(["git", "init", "-b", "main"], cwd=staging, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Docs Export Test"], cwd=staging, check=True)
            subprocess.run(["git", "config", "user.email", "docs@example.invalid"], cwd=staging, check=True)
            subprocess.run(["git", "add", "-f", "."], cwd=staging, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=staging, check=True, capture_output=True)

            committed = subprocess.run(
                ["git", "archive", "HEAD"],
                cwd=staging,
                check=True,
                capture_output=True,
            ).stdout
            extract(committed, exported)
            for private_doc in (
                "docs/play-store-listing.md",
                "docs/BACKLOG.md",
                "docs/TESTING.md",
                "docs/UX_POLICY.md",
            ):
                self.assertFalse((exported / private_doc).exists(), private_doc)

            result = subprocess.run(
                [sys.executable, "tools/check_docs.py"],
                cwd=exported,
                capture_output=True,
                text=True,
                timeout=30,
            )

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("phone screenshot bytes match the validity manifest", result.stdout)
            self.assertIn("PRIVACY.md discloses CAMERA", result.stdout)
            self.assertIn("Architecture Module Map names every production Kotlin module", result.stdout)
            self.assertRegex(result.stdout, r"\d+ private checks skipped")


if __name__ == "__main__":
    unittest.main()
