from __future__ import annotations

import hashlib
import io
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
import xml.etree.ElementTree as ET
from collections.abc import Callable
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
PRIVATE_EXPORT_DOCS = (
    "docs/play-store-listing.md",
    "docs/BACKLOG.md",
    "docs/TESTING.md",
    "docs/UX_POLICY.md",
)
MOTION_SOURCE = (
    REPO_ROOT
    / "app/src/main/kotlin/me/hletrd/telecampro/gl/MotionInversion.kt"
)


def run_documentation_gate_from_committed_export(
    mutate: Callable[[Path], None] | None = None,
) -> tuple[subprocess.CompletedProcess[str], tuple[str, ...]]:
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

        # The tests must pass before these changes are committed. Overlay only the tracked checker
        # and policy under test; no ignored/private document is copied from the maintainer workspace.
        for relative in (
            "tools/check_docs.py",
            "CLAUDE.md",
            "privacy-policy/index.html",
            "docs/ARCHITECTURE.md",
            "device-tests/README.md",
        ):
            shutil.copy2(REPO_ROOT / relative, staging / relative)
        if mutate is not None:
            mutate(staging)

        subprocess.run(["git", "init", "-b", "main"], cwd=staging, check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "Docs Export Test"], cwd=staging, check=True)
        subprocess.run(
            ["git", "config", "user.email", "docs@example.invalid"],
            cwd=staging,
            check=True,
        )
        subprocess.run(["git", "add", "-f", "."], cwd=staging, check=True)
        subprocess.run(
            ["git", "commit", "-m", "fixture"],
            cwd=staging,
            check=True,
            capture_output=True,
        )

        committed = subprocess.run(
            ["git", "archive", "HEAD"],
            cwd=staging,
            check=True,
            capture_output=True,
        ).stdout
        extract(committed, exported)
        private_docs_present = tuple(
            relative for relative in PRIVATE_EXPORT_DOCS if (exported / relative).exists()
        )
        result = subprocess.run(
            [sys.executable, "tools/check_docs.py"],
            cwd=exported,
            capture_output=True,
            text=True,
            timeout=30,
        )
        return result, private_docs_present


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


class BackupPolicyContractTest(unittest.TestCase):
    def test_private_recovery_state_is_excluded_from_backup_and_device_transfer(self) -> None:
        android = "http://schemas.android.com/apk/res/android"
        application = ET.parse(REPO_ROOT / "app/src/main/AndroidManifest.xml").getroot().find(
            "application"
        )
        if application is None:
            self.fail("AndroidManifest.xml must declare an application")
        self.assertEqual(application.attrib[f"{{{android}}}allowBackup"], "false")
        self.assertEqual(
            application.attrib[f"{{{android}}}dataExtractionRules"],
            "@xml/data_extraction_rules",
        )
        self.assertEqual(
            application.attrib[f"{{{android}}}fullBackupContent"],
            "@xml/backup_rules",
        )

        current = ET.parse(
            REPO_ROOT / "app/src/main/res/xml/data_extraction_rules.xml"
        ).getroot()
        required_exclusions = {("database", "."), ("sharedpref", ".")}
        for section_name in ("cloud-backup", "device-transfer"):
            section = current.find(section_name)
            if section is None:
                self.fail(f"data_extraction_rules.xml must declare {section_name}")
            exclusions = {
                (element.attrib.get("domain"), element.attrib.get("path"))
                for element in section.findall("exclude")
            }
            self.assertTrue(
                required_exclusions.issubset(exclusions),
                f"{section_name} must exclude ordinary database and preference state",
            )

        legacy = ET.parse(REPO_ROOT / "app/src/main/res/xml/backup_rules.xml").getroot()
        legacy_exclusions = {
            (element.attrib.get("domain"), element.attrib.get("path"))
            for element in legacy.findall("exclude")
        }
        self.assertTrue(required_exclusions.issubset(legacy_exclusions))


class ConsolidatedHostGateTest(unittest.TestCase):
    def test_gate_runs_every_non_device_quality_suite(self) -> None:
        source = (REPO_ROOT / "tools/verify_host.py").read_text(encoding="utf-8")
        for required in (
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
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
        for authority_path in ("CLAUDE.md", "docs/ARCHITECTURE.md"):
            authority = (REPO_ROOT / authority_path).read_text(encoding="utf-8")
            self.assertIn(":app:assembleDebugAndroidTest", authority)
            self.assertIn("does not run", authority)
            self.assertIn("prove device behavior", authority)

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
        result, private_docs_present = run_documentation_gate_from_committed_export()

        self.assertEqual(private_docs_present, ())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("phone screenshot bytes match the validity manifest", result.stdout)
        self.assertIn("committed submission sheet matches phone screenshot readiness", result.stdout)
        self.assertIn("PRIVACY.md discloses CAMERA", result.stdout)
        self.assertIn("ownerless legacy candidates without an own-captures-only claim", result.stdout)
        self.assertIn("Architecture Module Map names every production Kotlin module", result.stdout)
        self.assertIn(
            "CLAUDE marks absent private context optional with committed fallbacks",
            result.stdout,
        )
        self.assertIn(
            "Architecture qualifies the optional private UX policy and names committed fallbacks",
            result.stdout,
        )
        self.assertIn(
            "Architecture scopes the fixed lens list to PMA110 and documents enumeration",
            result.stdout,
        )
        self.assertRegex(result.stdout, r"\d+ private checks skipped")

    def test_committed_export_rejects_mandatory_absent_private_context(self) -> None:
        def require_private_context(root: Path) -> None:
            path = root / "CLAUDE.md"
            text = path.read_text(encoding="utf-8")
            marker = "**optional in clean clones**"
            self.assertIn(marker, text)
            path.write_text(
                text.replace(marker, "**required in clean clones**", 1),
                encoding="utf-8",
            )

        result, private_docs_present = run_documentation_gate_from_committed_export(
            require_private_context,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  CLAUDE marks absent private context optional with committed fallbacks",
            result.stdout,
        )

    def test_committed_export_rejects_an_unqualified_optional_ux_policy_link(self) -> None:
        def remove_optional_qualifier(root: Path) -> None:
            path = root / "docs/ARCHITECTURE.md"
            text = path.read_text(encoding="utf-8")
            marker = (
                "This paragraph plus\n[`CLAUDE.md`](../CLAUDE.md) is the committed clean-clone "
                "authority; the optional private\n[`UX_POLICY.md`](UX_POLICY.md) adds maintainer "
                "examples when present."
            )
            self.assertIn(marker, text)
            path.write_text(
                text.replace(marker, "See [`UX_POLICY.md`](UX_POLICY.md).", 1),
                encoding="utf-8",
            )

        result, private_docs_present = run_documentation_gate_from_committed_export(
            remove_optional_qualifier,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  Architecture qualifies the optional private UX policy and names committed fallbacks",
            result.stdout,
        )

    def test_committed_export_rejects_an_unqualified_fixed_lens_tab_list(self) -> None:
        def restore_fixed_list(root: Path) -> None:
            path = root / "docs/ARCHITECTURE.md"
            text = path.read_text(encoding="utf-8")
            marker = (
                "5. **Lens** — device-enumerated lens presets (0.6x/1x/3x/10x on PMA110), TELE mode,\n"
                "   stabilization mode, and OIS."
            )
            self.assertIn(marker, text)
            path.write_text(
                text.replace(
                    marker,
                    "5. **Lens** — 0.6x/1x/3x/10x selection, TELE mode, stabilization mode, and OIS.",
                    1,
                ),
                encoding="utf-8",
            )

        result, private_docs_present = run_documentation_gate_from_committed_export(restore_fixed_list)

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  Architecture scopes the fixed lens list to PMA110 and documents enumeration",
            result.stdout,
        )

    def test_committed_export_rejects_ready_runbook_for_stale_screenshots(self) -> None:
        def mark_runbook_ready(root: Path) -> None:
            path = root / "docs/play-console-submit.md"
            text = path.read_text(encoding="utf-8")
            marker = "**NOT SUBMISSION-READY**"
            self.assertIn(marker, text)
            path.write_text(text.replace(marker, "**SUBMISSION-READY**", 1), encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(mark_runbook_ready)

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  committed submission sheet matches phone screenshot readiness",
            result.stdout,
        )

    def test_committed_export_rejects_stale_runbook_for_ready_screenshots(self) -> None:
        def mark_manifest_ready(root: Path) -> None:
            path = root / "docs/assets/play/screenshots/asset-validity.json"
            manifest = json.loads(path.read_text(encoding="utf-8"))
            manifest["submission_ready"] = True
            manifest["blocking_assets"] = []
            manifest["obsolete_visible_copy"] = {}
            manifest["required_recapture"]["immutable_source_manifest_digest"] = "0" * 64
            manifest["required_recapture"]["apk_sha256"] = "1" * 64
            path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(mark_manifest_ready)

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  committed submission sheet matches phone screenshot readiness",
            result.stdout,
        )

    def test_committed_export_rejects_a_missing_current_pinch_probe(self) -> None:
        def erase_pinch_probe(root: Path) -> None:
            path = root / "device-tests/README.md"
            text = path.read_text(encoding="utf-8")
            marker = "`PinchGestureProbeTest` injects a real two-pointer gesture"
            self.assertIn(marker, text)
            path.write_text(
                text.replace(marker, "An instrumented test could be added later", 1),
                encoding="utf-8",
            )

        result, private_docs_present = run_documentation_gate_from_committed_export(erase_pinch_probe)

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  device harness non-coverage distinguishes probes from closed device evidence",
            result.stdout,
        )

    def test_committed_export_rejects_reopening_verified_front_signs(self) -> None:
        def reopen_front_signs(root: Path) -> None:
            path = root / "device-tests/README.md"
            text = path.read_text(encoding="utf-8")
            marker = "The PMA110 mirror and capture-rotation signs are\n  already device-verified"
            self.assertIn(marker, text)
            path.write_text(
                text.replace(
                    marker,
                    "The PMA110 mirror and capture-rotation signs stay\n  verification-pending",
                    1,
                ),
                encoding="utf-8",
            )

        result, private_docs_present = run_documentation_gate_from_committed_export(reopen_front_signs)

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  device harness non-coverage distinguishes probes from closed device evidence",
            result.stdout,
        )


if __name__ == "__main__":
    unittest.main()
