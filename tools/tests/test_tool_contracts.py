from __future__ import annotations

import hashlib
import io
import importlib.util
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zlib
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
    *,
    interpreter_args: tuple[str, ...] = (),
    environment: dict[str, str] | None = None,
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
            "tools/android_sdk.py",
            "tools/verify_host.py",
            "tools/build_immutable_debug.py",
            "tools/build_immutable_release.py",
            "README.md",
            "CLAUDE.md",
            "PRIVACY.md",
            "privacy-policy/index.html",
            "docs/ARCHITECTURE.md",
            "docs/FIELD_CHECKS.md",
            "docs/play-console-submit.md",
            "docs/assets/play/screenshots/tablet/asset-validity.json",
            "device-tests/README.md",
            "app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/camera/RotationMath.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/camera/ZoomSubmitPlan.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/gl/FrontMirrorConvention.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt",
            "app/src/debug/kotlin/me/hletrd/findx9tele/ui/CameraScreenPreview.kt",
            "app/src/debug/kotlin/me/hletrd/findx9tele/ui/UiSnapshotActivity.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/ui/ZoomGlideState.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt",
            "app/src/main/kotlin/me/hletrd/telecampro/ui/theme/Theme.kt",
            "app/src/main/res/values/strings.xml",
            "app/src/main/res/values-ko/strings.xml",
        ):
            shutil.copy2(REPO_ROOT / relative, staging / relative)
        # check_docs.py validates the newest completed plan. Overlay the live public plan set too,
        # otherwise a pre-commit test can validate stale HEAD while the direct documentation gate
        # correctly evaluates the current completion record.
        for source in (REPO_ROOT / "docs/plans").glob("*.md"):
            shutil.copy2(source, staging / source.relative_to(REPO_ROOT))
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
            [sys.executable, *interpreter_args, "tools/check_docs.py"],
            cwd=exported,
            env=environment,
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
    def test_host_and_documentation_gates_reject_optimized_python(self) -> None:
        commands = (
            ("tools/verify_host.py", "host verification gate"),
            ("tools/check_docs.py", "documentation gate"),
        )
        for script, diagnostic in commands:
            for label, args, environment in (
                ("flag", ("-O",), os.environ.copy()),
                ("environment", (), {**os.environ, "PYTHONOPTIMIZE": "1"}),
            ):
                with self.subTest(script=script, mode=label):
                    result = subprocess.run(
                        [sys.executable, *args, script],
                        cwd=REPO_ROOT,
                        env=environment,
                        capture_output=True,
                        text=True,
                        timeout=30,
                    )
                    self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                    self.assertIn(f"optimized Python is unsupported for the {diagnostic}", result.stderr)

    def test_documentation_gate_keeps_exact_millisecond_verdict_under_all_modes(self) -> None:
        def make_zsl_age_non_integral(root: Path) -> None:
            path = root / "app/src/main/kotlin/me/hletrd/telecampro/camera/ZslAdmission.kt"
            text = path.read_text(encoding="utf-8")
            self.assertIn("ZSL_MAX_FRAME_AGE_NS = 400_000_000L", text)
            path.write_text(
                text.replace(
                    "ZSL_MAX_FRAME_AGE_NS = 400_000_000L",
                    "ZSL_MAX_FRAME_AGE_NS = 400_000_001L",
                    1,
                ),
                encoding="utf-8",
            )

        normal, _ = run_documentation_gate_from_committed_export(make_zsl_age_non_integral)
        self.assertNotEqual(normal.returncode, 0, normal.stdout + normal.stderr)
        self.assertIn("ZSL frame age must be an exact millisecond fact", normal.stderr)

        optimized, _ = run_documentation_gate_from_committed_export(
            make_zsl_age_non_integral,
            interpreter_args=("-O",),
        )
        self.assertEqual(optimized.returncode, 2, optimized.stdout + optimized.stderr)
        self.assertIn("optimized Python is unsupported", optimized.stderr)

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
        self.assertIn("tablet screenshot bytes match the validity manifest", result.stdout)
        self.assertIn("committed submission sheet matches tablet screenshot readiness", result.stdout)
        self.assertIn(
            "committed tablet asset guidance keeps the deleted operator rail retired",
            result.stdout,
        )
        self.assertIn("PRIVACY.md discloses CAMERA", result.stdout)
        self.assertIn("ownerless legacy candidates without an own-captures-only claim", result.stdout)
        self.assertIn(
            "Architecture Module Map names every production Kotlin and Java module",
            result.stdout,
        )
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
        self.assertIn(
            "all committed backlog references are locally optional in clean clones",
            result.stdout,
        )
        self.assertIn(
            "FIELD_CHECKS provides a committed result ledger when private backlog is absent",
            result.stdout,
        )
        self.assertIn(
            "field dashboard open membership and prose count match the body",
            result.stdout,
        )
        self.assertIn(
            "field evidence never labels an unresolved profile difference confirmed",
            result.stdout,
        )
        self.assertIn(
            "build field and harness workflows share one clean-clone Android SDK authority",
            result.stdout,
        )
        self.assertIn("all active AGP references match the version catalog", result.stdout)
        self.assertIn("active pseudo-ZSL freshness references match executable truth", result.stdout)
        self.assertIn(
            "Loupe Overview authorities match the executable right-inset corner",
            result.stdout,
        )
        self.assertIn(
            "active open FIELD_CHECKS references name a runnable field-check identity",
            result.stdout,
        )
        self.assertIn(
            "snapshot host pins dark system bars through the production helper",
            result.stdout,
        )
        self.assertIn(
            "RotationMath keeps committed B1 video rotation evidence closed",
            result.stdout,
        )
        self.assertIn(
            "FrontMirrorConvention points to committed open A4 calibration",
            result.stdout,
        )
        self.assertIn(
            "REC border authority keeps the device-accepted platform radius unscaled",
            result.stdout,
        )
        self.assertIn(
            "live UI authority keeps the current 0.40 GuideLine weight",
            result.stdout,
        )
        self.assertRegex(result.stdout, r"\d+ private checks skipped")

    def test_documentation_gate_rejects_an_omitted_java_production_module(self) -> None:
        def add_undocumented_java_owner(root: Path) -> None:
            owner = root / "app/src/main/java/me/hletrd/telecampro/storage/OmittedOwner.java"
            owner.parent.mkdir(parents=True, exist_ok=True)
            owner.write_text(
                "package me.hletrd.telecampro.storage;\nfinal class OmittedOwner {}\n",
                encoding="utf-8",
            )

        result, _ = run_documentation_gate_from_committed_export(add_undocumented_java_owner)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  Architecture Module Map names every production Kotlin and Java module",
            result.stdout,
        )
        self.assertIn("OmittedOwner.java", result.stdout)

    def test_documentation_gate_rejects_retired_guide_weight_guidance(self) -> None:
        fixtures = (
            (
                "app/src/main/kotlin/me/hletrd/telecampro/ui/theme/Theme.kt",
                "Distinct from [GuideLine] (0.40)",
                "Distinct from [GuideLine] (0.55)",
            ),
            (
                "app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt",
                "0.40 GuideLine the thirds/frame-line rules",
                "0.55 GuideLine the thirds/frame-line rules",
            ),
            (
                "app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt",
                "CameraColors.GuideLine at 0.40",
                "other 0.55s in this file are the frame lines",
            ),
        )
        for relative, current, retired in fixtures:
            with self.subTest(relative=relative):
                def restore_retired_weight(
                    root: Path,
                    relative: str = relative,
                    current: str = current,
                    retired: str = retired,
                ) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, retired, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(restore_retired_weight)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  live UI authority keeps the current 0.40 GuideLine weight",
                    result.stdout,
                )

    def test_documentation_gate_rejects_retired_zoom_submit_guidance(self) -> None:
        fixtures = (
            (
                "docs/ARCHITECTURE.md",
                "Pure moving-tick suppression",
                "Pure HAL zoom-submit decision (throttle window + mid-gesture wide-aim clamp)",
            ),
            (
                "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt",
                "Still-truth-only zoom update for MOVING (non-submitted) ticks",
                "Still-truth-only zoom update for THROTTLED (non-submitted) ticks",
            ),
            (
                "CLAUDE.md",
                "700 ms end is state-only",
                "END edge always lands exact",
            ),
        )
        for relative, current, retired in fixtures:
            with self.subTest(relative=relative):
                def restore_retired_zoom_guidance(
                    root: Path,
                    relative: str = relative,
                    current: str = current,
                    retired: str = retired,
                ) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, retired, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(
                    restore_retired_zoom_guidance
                )
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  zoom authority rejects retired periodic-submit and fixed-edge model",
                    result.stdout,
                )

    def test_committed_export_rejects_stale_agp_zsl_and_field_reference_facts(self) -> None:
        fixtures = (
            (
                "docs/ARCHITECTURE.md",
                "AGP 9.3.2",
                "AGP 9.3.1",
                "FAIL  all active AGP references match the version catalog",
            ),
            (
                "CLAUDE.md",
                "age <= 400 ms",
                "age <= 250 ms",
                "FAIL  active pseudo-ZSL freshness references match executable truth",
            ),
            (
                "docs/ARCHITECTURE.md",
                "age <= 400 ms",
                "age < 400 ms",
                "FAIL  active pseudo-ZSL freshness references match executable truth",
            ),
            (
                "docs/ARCHITECTURE.md",
                "No logical-camera bisect is scheduled in the\nexhaustive committed `docs/FIELD_CHECKS.md` ledger",
                "A logical-camera bisect remains open in the\ncommitted `docs/FIELD_CHECKS.md`",
                "FAIL  active open FIELD_CHECKS references name a runnable field-check identity",
            ),
        )
        for relative, current, stale, failure in fixtures:
            with self.subTest(relative=relative, failure=failure):
                def make_stale(
                    root: Path,
                    relative: str = relative,
                    current: str = current,
                    stale: str = stale,
                ) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, stale, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(make_stale)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(failure, result.stdout)

    def test_committed_export_rejects_stale_loupe_corner_authority(self) -> None:
        def restore_bottom_left(root: Path) -> None:
            path = root / "CLAUDE.md"
            text = path.read_text(encoding="utf-8")
            marker = "bottom-right corner viewport"
            self.assertIn(marker, text)
            path.write_text(text.replace(marker, "bottom-left corner viewport", 1), encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(
            restore_bottom_left,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  Loupe Overview authorities match the executable right-inset corner",
            result.stdout,
        )

    def test_committed_export_rejects_bare_snapshot_edge_to_edge(self) -> None:
        def restore_bare_edge_to_edge(root: Path) -> None:
            path = root / "app/src/debug/kotlin/me/hletrd/findx9tele/ui/UiSnapshotActivity.kt"
            text = path.read_text(encoding="utf-8")
            marker = "        enableTeleCamEdgeToEdge()\n"
            self.assertIn(marker, text)
            path.write_text(text.replace(marker, "        enableEdgeToEdge()\n", 1), encoding="utf-8")

        result, _ = run_documentation_gate_from_committed_export(restore_bare_edge_to_edge)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  snapshot host pins dark system bars through the production helper",
            result.stdout,
        )

    def test_committed_export_rejects_stale_live_loupe_laws(self) -> None:
        def restore_stale_loupe_comment(root: Path) -> None:
            path = root / "app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt"
            text = path.read_text(encoding="utf-8")
            current_gate = (
                "user toggle + active punch-in + (TELE or unified zoom\n"
                "            // >= 3x). Photo additionally requires 4:3; Video ignores the unrelated still aspect."
            )
            stale_gate = "user toggle + Photo + 4:3 + TELE + active punch-in."
            self.assertIn(current_gate, text)
            self.assertIn("must not mirror to bottom-left under RTL system locales", text)
            path.write_text(
                text.replace(current_gate, stale_gate, 1).replace(
                    "must not mirror to bottom-left under RTL system locales",
                    "must not mirror to bottom-right under RTL system locales",
                    1,
                ),
                encoding="utf-8",
            )

        result, _ = run_documentation_gate_from_committed_export(restore_stale_loupe_comment)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  Loupe source and Compose-test guidance rejects the superseded Photo/TELE-only gate",
            result.stdout,
        )

    def test_committed_export_rejects_rotation_kdoc_status_drift(self) -> None:
        fixtures = (
            (
                "app/src/main/kotlin/me/hletrd/telecampro/camera/RotationMath.kt",
                "closed rotation end to end",
                "left rotation open",
                "FAIL  RotationMath keeps committed B1 video rotation evidence closed",
            ),
            (
                "app/src/main/kotlin/me/hletrd/telecampro/gl/FrontMirrorConvention.kt",
                "ROTATION term remains OPEN",
                "ROTATION term is CLOSED",
                "FAIL  FrontMirrorConvention points to committed open A4 calibration",
            ),
        )
        for relative, current, stale, failure in fixtures:
            with self.subTest(relative=relative):
                def drift_status(
                    root: Path,
                    relative: str = relative,
                    current: str = current,
                    stale: str = stale,
                ) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, stale, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(drift_status)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(failure, result.stdout)

    def test_committed_export_rejects_retired_rec_border_multiplier(self) -> None:
        def restore_multiplier(root: Path) -> None:
            path = root / "CLAUDE.md"
            text = path.read_text(encoding="utf-8")
            marker = "use the platform radius unscaled"
            self.assertIn(marker, text)
            path.write_text(text.replace(marker, "scale ×1.2", 1), encoding="utf-8")

        result, _ = run_documentation_gate_from_committed_export(restore_multiplier)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  REC border authority keeps the device-accepted platform radius unscaled",
            result.stdout,
        )

    def test_committed_export_rejects_privacy_fact_drift(self) -> None:
        fixtures = (
            (
                "camera make and model",
                "camera model",
                "FAIL  PRIVACY.md discloses capture metadata and no location",
            ),
            (
                "READ your library on this device",
                "uses your library",
                "FAIL  PRIVACY.md discloses on-device library read without transmission",
            ),
        )
        for current, stale, failure in fixtures:
            with self.subTest(failure=failure):
                def make_stale(root: Path, current: str = current, stale: str = stale) -> None:
                    path = root / "PRIVACY.md"
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, stale, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(make_stale)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(failure, result.stdout)

    def test_committed_export_rejects_missing_korean_policy_route(self) -> None:
        def remove_korean_route(root: Path) -> None:
            path = root / "privacy-policy/index.html"
            text = path.read_text(encoding="utf-8")
            marker = '<section id="ko" class="policy-language" lang="ko"'
            self.assertIn(marker, text)
            path.write_text(
                text.replace(marker, '<section class="policy-language" lang="ko"', 1),
                encoding="utf-8",
            )

        result, _ = run_documentation_gate_from_committed_export(remove_korean_route)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("FAIL  published privacy page exposes a Korean language section", result.stdout)

    def test_committed_export_rejects_launcher_brand_drift(self) -> None:
        def restore_gradient_brand(root: Path) -> None:
            path = root / "app/src/main/res/drawable/ic_launcher_background.xml"
            text = path.read_text(encoding="utf-8")
            self.assertIn("#FF0B0B0D", text)
            path.write_text(text.replace("#FF0B0B0D", "#FF1E7CFF", 1), encoding="utf-8")

        result, _ = run_documentation_gate_from_committed_export(restore_gradient_brand)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("FAIL  launcher uses the public mark black field", result.stdout)

    def test_committed_export_rejects_a_stale_release_minification_comment(self) -> None:
        def claim_release_minification_is_off(root: Path) -> None:
            path = root / "app/src/debug/kotlin/me/hletrd/findx9tele/ui/CameraScreenPreview.kt"
            text = path.read_text(encoding="utf-8")
            marker = "// Debug-only source set on purpose:"
            self.assertIn(marker, text)
            path.write_text(
                text.replace(marker, "// Debug-only source set on purpose: release keeps minify off.", 1),
                encoding="utf-8",
            )

        result, _ = run_documentation_gate_from_committed_export(
            claim_release_minification_is_off,
        )

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  current comments describe maximum-resolution discovery and enabled R8",
            result.stdout,
        )

    def test_committed_export_rejects_a_completed_plan_without_authoritative_host_evidence(self) -> None:
        baseline, _ = run_documentation_gate_from_committed_export()
        self.assertEqual(baseline.returncode, 0, baseline.stdout + baseline.stderr)

        def remove_authoritative_command(root: Path) -> None:
            completed = []
            pattern = re.compile(r"^(\d{4}-\d{2}-\d{2})-rpf-cycle(\d+)\.md$")
            for path in (root / "docs/plans").glob("*.md"):
                text = path.read_text(encoding="utf-8")
                match = pattern.fullmatch(path.name)
                if match and re.search(r"^Status:\s*complete\b", text, re.M):
                    completed.append(((match.group(1), int(match.group(2))), path))
            self.assertTrue(completed)
            path = max(completed, key=lambda item: item[0])[1]
            text = path.read_text(encoding="utf-8")
            self.assertIn("python3 tools/verify_host.py", text)
            path.write_text(
                text.replace("python3 tools/verify_host.py", "the narrower Gradle gate"),
                encoding="utf-8",
            )

        result, _ = run_documentation_gate_from_committed_export(remove_authoritative_command)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  latest completed implementation plan names the authoritative host gate",
            result.stdout,
        )

    def test_completed_plan_ordering_uses_date_then_numeric_cycle(self) -> None:
        cases = (
            ("2099-01-01-rpf-cycle9.md", "2099-01-01-rpf-cycle10.md"),
            ("2099-01-01-rpf-cycle99.md", "2099-01-01-rpf-cycle100.md"),
            ("2099-01-01-rpf-cycle100.md", "2099-01-02-rpf-cycle1.md"),
        )
        for older, newer in cases:
            with self.subTest(older=older, newer=newer):
                def add_ordering_fixture(root: Path, older: str = older, newer: str = newer) -> None:
                    plans = root / "docs/plans"
                    (plans / older).write_text(
                        "Status: complete\npython3 tools/verify_host.py\n",
                        encoding="utf-8",
                    )
                    (plans / newer).write_text("Status: complete\n", encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(add_ordering_fixture)

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  latest completed implementation plan names the authoritative host gate",
                    result.stdout,
                )
                self.assertIn(newer, result.stdout)

    def test_incomplete_newer_plan_does_not_replace_completed_evidence(self) -> None:
        def add_incomplete_plan(root: Path) -> None:
            (root / "docs/plans/2099-01-01-rpf-cycle100.md").write_text(
                "Status: in progress\n",
                encoding="utf-8",
            )

        result, _ = run_documentation_gate_from_committed_export(add_incomplete_plan)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_malformed_or_ambiguous_completed_plan_identity_fails_closed(self) -> None:
        def add_malformed(root: Path) -> None:
            (root / "docs/plans/2099-01-01-rpf-cycleoops.md").write_text(
                "Status: complete\npython3 tools/verify_host.py\n",
                encoding="utf-8",
            )

        malformed, _ = run_documentation_gate_from_committed_export(add_malformed)
        self.assertNotEqual(malformed.returncode, 0, malformed.stdout + malformed.stderr)
        self.assertIn(
            "FAIL  completed implementation plans carry sortable date and numeric-cycle identities",
            malformed.stdout,
        )

        def add_ambiguous(root: Path) -> None:
            for name in ("2099-01-01-rpf-cycle99.md", "2099-01-01-rpf-cycle099.md"):
                (root / f"docs/plans/{name}").write_text(
                    "Status: complete\npython3 tools/verify_host.py\n",
                    encoding="utf-8",
                )

        ambiguous, _ = run_documentation_gate_from_committed_export(add_ambiguous)
        self.assertNotEqual(ambiguous.returncode, 0, ambiguous.stdout + ambiguous.stderr)
        self.assertIn("FAIL  completed implementation plan identities are unique", ambiguous.stdout)

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

    def test_committed_export_rejects_backlog_only_field_recording(self) -> None:
        def remove_committed_ledger(root: Path) -> None:
            path = root / "docs/FIELD_CHECKS.md"
            text = path.read_text(encoding="utf-8")
            start = text.index("## Recording results")
            path.write_text(
                text[:start]
                + "## Recording results\n\nPut every outcome in required private `docs/BACKLOG.md`.\n",
                encoding="utf-8",
            )

        result, private_docs_present = run_documentation_gate_from_committed_export(
            remove_committed_ledger,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  all committed backlog references are locally optional in clean clones",
            result.stdout,
        )
        self.assertIn(
            "FAIL  FIELD_CHECKS provides a committed result ledger when private backlog is absent",
            result.stdout,
        )

    def test_committed_export_rejects_open_field_missing_from_dashboard(self) -> None:
        def remove_e2_from_dashboard(root: Path) -> None:
            path = root / "docs/FIELD_CHECKS.md"
            text = path.read_text(encoding="utf-8")
            marker = " · E2 ☐."
            self.assertIn(marker, text)
            path.write_text(text.replace(marker, ".", 1), encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(
            remove_e2_from_dashboard,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  field dashboard names every body check exactly and in order",
            result.stdout,
        )
        self.assertIn(
            "FAIL  field dashboard open membership and prose count match the body",
            result.stdout,
        )

    def test_committed_export_rejects_unbound_front_zsl_field_claim(self) -> None:
        def remove_front_zsl_identity(root: Path) -> None:
            path = root / "CLAUDE.md"
            text = path.read_text(encoding="utf-8")
            marker = "`docs/FIELD_CHECKS.md` A5"
            self.assertIn(marker, text)
            path.write_text(text.replace(marker, "`docs/FIELD_CHECKS.md`", 1), encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(
            remove_front_zsl_identity,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  active CLAUDE field-check claims bind to open ledger identities",
            result.stdout,
        )

    def test_committed_export_rejects_confirmed_ois_with_unresolved_body(self) -> None:
        def overclaim_ois(root: Path) -> None:
            path = root / "docs/FIELD_CHECKS.md"
            text = path.read_text(encoding="utf-8")
            marker = "C3. TC OIS (optional) — ✅ CLOSED"
            self.assertIn(marker, text)
            path.write_text(
                text.replace(marker, "C3. TC OIS (optional) — ✅ CONFIRMED WORKING", 1),
                encoding="utf-8",
            )

        result, private_docs_present = run_documentation_gate_from_committed_export(overclaim_ois)

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  field evidence never labels an unresolved profile difference confirmed",
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

    def test_committed_export_rejects_wrong_phone_png_geometry_after_digest_update(self) -> None:
        def replace_with_valid_wrong_geometry(root: Path) -> None:
            asset = root / "docs/assets/play/screenshots/01-main-viewfinder.png"
            signature = b"\x89PNG\r\n\x1a\n"

            def chunk(kind: bytes, data: bytes) -> bytes:
                return (
                    struct.pack(">I", len(data))
                    + kind
                    + data
                    + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
                )

            asset.write_bytes(
                signature
                + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00"))
                + chunk(b"IEND", b"")
            )
            manifest_path = root / "docs/assets/play/screenshots/asset-validity.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            relative = "docs/assets/play/screenshots/01-main-viewfinder.png"
            manifest["assets"][relative] = hashlib.sha256(asset.read_bytes()).hexdigest()
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(
            replace_with_valid_wrong_geometry,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  phone screenshot PNG bytes match the declared geometry and encoding",
            result.stdout,
        )

    def test_committed_export_rejects_missing_tablet_screenshot(self) -> None:
        def add_missing_asset(root: Path) -> None:
            path = root / "docs/assets/play/screenshots/tablet/asset-validity.json"
            manifest = json.loads(path.read_text(encoding="utf-8"))
            missing = "docs/assets/play/screenshots/tablet/99-missing.png"
            manifest["assets"][missing] = "0" * 64
            manifest["blocking_assets"].append(missing)
            path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(add_missing_asset)

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  tablet screenshot manifest owns every checked-in tablet PNG",
            result.stdout,
        )
        self.assertIn("99-missing.png", result.stdout)

    def test_committed_export_rejects_mismatched_tablet_screenshot_digest(self) -> None:
        def mismatch_digest(root: Path) -> None:
            path = root / "docs/assets/play/screenshots/tablet/asset-validity.json"
            manifest = json.loads(path.read_text(encoding="utf-8"))
            asset = "docs/assets/play/screenshots/tablet/03-focus.png"
            manifest["assets"][asset] = "0" * 64
            path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(mismatch_digest)

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("FAIL  tablet screenshot bytes match the validity manifest", result.stdout)
        self.assertIn("03-focus.png", result.stdout)

    def test_committed_export_rejects_wrong_tablet_png_geometry_after_digest_update(self) -> None:
        def replace_with_valid_wrong_geometry(root: Path) -> None:
            asset = root / "docs/assets/play/screenshots/tablet/03-focus.png"
            signature = b"\x89PNG\r\n\x1a\n"

            def chunk(kind: bytes, data: bytes) -> bytes:
                return (
                    struct.pack(">I", len(data))
                    + kind
                    + data
                    + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
                )

            asset.write_bytes(
                signature
                + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00\x00"))
                + chunk(b"IEND", b"")
            )
            manifest_path = root / "docs/assets/play/screenshots/tablet/asset-validity.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            relative = "docs/assets/play/screenshots/tablet/03-focus.png"
            manifest["assets"][relative] = hashlib.sha256(asset.read_bytes()).hexdigest()
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(
            replace_with_valid_wrong_geometry,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  tablet screenshot PNG bytes match the declared geometry and encoding",
            result.stdout,
        )

    def test_committed_export_rejects_stale_tablet_screenshot_copy(self) -> None:
        def drift_copy(root: Path) -> None:
            path = root / "app/src/main/res/values/strings.xml"
            text = path.read_text(encoding="utf-8")
            marker = '<string name="label_gamma">Gamma</string>'
            self.assertIn(marker, text)
            path.write_text(
                text.replace(marker, '<string name="label_gamma">Tone Map</string>', 1),
                encoding="utf-8",
            )

        result, private_docs_present = run_documentation_gate_from_committed_export(drift_copy)

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  tablet screenshot recapture copy matches current resources",
            result.stdout,
        )
        self.assertIn("label_gamma", result.stdout)

    def test_committed_export_rejects_unproved_tablet_screenshot_promotion(self) -> None:
        def claim_ready(root: Path) -> None:
            path = root / "docs/assets/play/screenshots/tablet/asset-validity.json"
            manifest = json.loads(path.read_text(encoding="utf-8"))
            manifest["submission_ready"] = True
            path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(claim_ready)

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  tablet screenshot manifest records a valid fail-closed provenance state",
            result.stdout,
        )
        self.assertIn(
            "FAIL  committed submission sheet matches tablet screenshot readiness",
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
