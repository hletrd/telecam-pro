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
sys.path.insert(0, str(Path(__file__).resolve().parent))
from fixture_git import init_fixture_repo  # noqa: E402


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
    untracked: dict[str, str] | None = None,
    foreign_repository: bool = False,
) -> tuple[subprocess.CompletedProcess[str], tuple[str, ...]]:
    """Runs check_docs on a committed export of HEAD plus the overlays below.

    With [untracked], the files are written into the committed STAGING work tree after the commit
    and the gate runs there (a real git work tree), proving untracked notes cannot change it.
    With [foreign_repository], the export is unpacked inside an unrelated git work tree that tracks
    none of its files (DBG4-4: an empty listing from the wrong repository must not pass vacuously).
    """
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
            "tools/run_scoped_signed_release.py",
            "tools/upload_key_policy.py",
            "app/build.gradle.kts",
            "keystore.properties.example",
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
            "app/src/main/kotlin/me/hletrd/telecampro/gl/FlipRenderer.kt",
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

        init_fixture_repo(staging)
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
        if foreign_repository:
            outer = root / "outer"
            outer.mkdir()
            init_fixture_repo(outer)
            exported = outer / "exported"
        extract(committed, exported)
        private_docs_present = tuple(
            relative for relative in PRIVATE_EXPORT_DOCS if (exported / relative).exists()
        )
        gate_root = exported
        if untracked is not None:
            for relative, text in untracked.items():
                (staging / relative).parent.mkdir(parents=True, exist_ok=True)
                (staging / relative).write_text(text, encoding="utf-8")
            gate_root = staging
        result = subprocess.run(
            [sys.executable, *interpreter_args, "tools/check_docs.py"],
            cwd=gate_root,
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
    def test_v101_korean_count_is_derived_from_named_version_code_3_source(self) -> None:
        revision = "bcbeaf0c"

        def historical_text(relative: str) -> str:
            result = subprocess.run(
                ["git", "show", f"{revision}:{relative}"],
                cwd=REPO_ROOT,
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return result.stdout

        build = historical_text("app/build.gradle.kts")
        korean = ET.fromstring(
            historical_text("app/src/main/res/values-ko/strings.xml"),
        )
        self.assertRegex(build, r"(?m)^\s*versionCode = 3$")
        self.assertEqual(sum(element.tag == "string" for element in korean), 126)

        submit = (REPO_ROOT / "docs/play-console-submit.md").read_text(encoding="utf-8")
        self.assertIn("126 strings became resources", submit)
        self.assertIn(f"versionCode-3 pin `{revision}`", submit)

    def test_documentation_gate_rejects_v101_korean_count_or_source_drift(self) -> None:
        def drift(root: Path, old: str, new: str) -> None:
            path = root / "docs/play-console-submit.md"
            text = path.read_text(encoding="utf-8")
            self.assertIn(old, text)
            path.write_text(text.replace(old, new, 1), encoding="utf-8")

        for old, new in (
            ("126 strings became resources", "131 strings became resources"),
            ("versionCode-3 pin `bcbeaf0c`", "versionCode-3 pin `fe6a8a0`"),
        ):
            with self.subTest(old=old):
                result, _ = run_documentation_gate_from_committed_export(
                    lambda root, old=old, new=new: drift(root, old, new),
                )
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  v1.0.1 Korean count names reproducible versionCode-3 source evidence",
                    result.stdout,
                )

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

    def test_default_gate_adds_release_lint_on_a_clean_tree(self) -> None:
        # QA4-3 / AGG4-78: release lint needs no signing material, only a clean committed tree.
        verify_host = load_verify_host()
        self.assertEqual(":app:lintRelease", verify_host.RELEASE_LINT_TASK)
        self.assertIn(":app:lintRelease", verify_host.default_gradle_tasks(True))
        self.assertNotIn(":app:lintRelease", verify_host.default_gradle_tasks(False))
        self.assertEqual(
            verify_host.default_gradle_tasks(False),
            verify_host.default_gradle_tasks(True)[:-1],
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            init_fixture_repo(root)
            (root / "tracked.txt").write_text("a\n", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(
                ["git", "-c", "user.name=a", "-c", "user.email=a@example.invalid", "commit", "-qm", "x"],
                cwd=root,
                check=True,
            )
            self.assertTrue(verify_host.worktree_is_clean(root))
            (root / "untracked.txt").write_text("b\n", encoding="utf-8")
            self.assertFalse(verify_host.worktree_is_clean(root))
            (root / "untracked.txt").unlink()
            (root / "tracked.txt").write_text("changed\n", encoding="utf-8")
            self.assertFalse(verify_host.worktree_is_clean(root))
        self.assertFalse(verify_host.worktree_is_clean(Path(tempfile.gettempdir()) / "no-such-repo-x"))
        source = (REPO_ROOT / "tools/verify_host.py").read_text(encoding="utf-8")
        self.assertIn('run(["./gradlew", *default_gradle_tasks(clean)], env)', source)

    def test_gate_ends_with_a_terminal_release_lint_verdict(self) -> None:
        # RG5-15 / TE5-20 (AGG5-19): a dirty-tree run must not read as "release lint green".
        verify_host = load_verify_host()
        self.assertEqual("release lint: SKIPPED (dirty tree)", verify_host.release_lint_summary(False))
        self.assertEqual("release lint: RAN (:app:lintRelease)", verify_host.release_lint_summary(True))
        source = (REPO_ROOT / "tools/verify_host.py").read_text(encoding="utf-8")
        main_body = source[source.index("def main() -> int:"):]
        # The verdict is the LAST thing main prints, after every gate step has passed.
        self.assertIn(
            "    print(release_lint_summary(clean), flush=True)\n    return 0\n",
            main_body,
        )

    def test_diff_gate_rejects_staged_and_unstaged_whitespace_errors(self) -> None:
        command = load_verify_host().repository_diff_check_command()
        self.assertEqual(command, ["git", "diff", "--check", "HEAD", "--"])

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            tracked = root / "tracked.txt"
            tracked.write_text("clean\n", encoding="utf-8")
            init_fixture_repo(root)
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
                "AGP 9.4.1",
                "AGP 9.3.2",
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

    def test_committed_export_rejects_unqualified_upright_loupe_claim(self) -> None:
        def restore_upright_claim(root: Path) -> None:
            path = root / "CLAUDE.md"
            text = path.read_text(encoding="utf-8")
            marker = "The Loupe Overview omits the afocal term per draw"
            self.assertIn(marker, text)
            path.write_text(
                text.replace(marker, "The Loupe Overview draws UPRIGHT", 1),
                encoding="utf-8",
            )

        result, _ = run_documentation_gate_from_committed_export(restore_upright_claim)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  committed Loupe Overview criteria match the per-draw orientation authority",
            result.stdout,
        )

    def test_committed_export_rejects_stale_loupe_source_and_pipeline_ownership_claims(self) -> None:
        fixtures = (
            (
                "app/src/main/kotlin/me/hletrd/telecampro/gl/FlipRenderer.kt",
                "making it raw and inverted relative to the main view",
                "the operator wants UPRIGHT",
                "FAIL  committed Loupe Overview criteria match the per-draw orientation authority",
            ),
            (
                "app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt",
                "It is not an upright or pre-converter-world reference.",
                "The operator wants UPRIGHT.",
                "FAIL  committed Loupe Overview criteria match the per-draw orientation authority",
            ),
            (
                "docs/ARCHITECTURE.md",
                "restores its baseline only while it still owns that generation",
                "always restores its baseline packet",
                "FAIL  pipeline rollback and REC authorities preserve independent packet ownership",
            ),
        )
        for relative, current, stale, failure in fixtures:
            with self.subTest(relative=relative):
                def regress(root: Path) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, stale, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(regress)

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(failure, result.stdout)

    def test_committed_export_rejects_appops_gap_marked_open(self) -> None:
        def restore_open_gap(root: Path) -> None:
            path = root / "docs/play-console-submit.md"
            text = path.read_text(encoding="utf-8")
            marker = "This gap is closed in the current build"
            self.assertIn(marker, text)
            path.write_text(
                text.replace(marker, "This is an open UX gap", 1),
                encoding="utf-8",
            )

        result, _ = run_documentation_gate_from_committed_export(restore_open_gap)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  historical AppOps matrix does not contradict the current blocked-camera disclosure",
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
            pattern = re.compile(r"^(\d{4}-\d{2}-\d{2})-rp[fl]-cycle(\d+)\.md$")
            for path in [
                *(root / "docs/plans").glob("*.md"),
                *(root / "docs/plans/archive").glob("*.md"),
            ]:
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

    def test_archived_and_rpl_completed_plans_count_as_history(self) -> None:
        def add_archived_rpl_plan(root: Path) -> None:
            (root / "docs/plans/archive/2099-01-01-rpl-cycle1.md").write_text(
                "Status: complete\n",
                encoding="utf-8",
            )

        result, _ = run_documentation_gate_from_committed_export(add_archived_rpl_plan)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  latest completed implementation plan names the authoritative host gate",
            result.stdout,
        )
        self.assertIn("2099-01-01-rpl-cycle1.md", result.stdout)

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
            # Remove only E2, keeping its neighbours intact, so this mutation proves one missing
            # open body check; the assertion fails fast if a ledger edit leaves the fixture stale.
            marker = " · E1 ☐ · E2 ☐ · E3 ☐ ·"
            self.assertIn(marker, text)
            path.write_text(text.replace(marker, " · E1 ☐ · E3 ☐ ·", 1), encoding="utf-8")

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

    def test_committed_export_rejects_a_host_only_entry_dashboarded_as_passed(self) -> None:
        # MRG4-6: a change with no device procedure is listed as `⊘ HOST-ONLY`; the dashboard may
        # neither drop it nor report it green.
        def mark_host_only_passed(root: Path) -> None:
            path = root / "docs/FIELD_CHECKS.md"
            text = path.read_text(encoding="utf-8")
            marker = " · F1 ⊘ ·"
            self.assertIn(marker, text)
            path.write_text(text.replace(marker, " · F1 ✅ ·", 1), encoding="utf-8")

        result, private_docs_present = run_documentation_gate_from_committed_export(
            mark_host_only_passed,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
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
                "5. **Lens** — device-enumerated lens presets (0.6x/1x/3x/10x on PMA110), TELE mode, the phone +\n"
                "   teleconverter declaration (two dropdowns, the converter list narrowed to that phone, plus the\n"
                "   custom magnification field and converter-host captions), stabilization mode, and OIS."
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

    def test_committed_export_rejects_ordinary_nontranslatable_prose(self) -> None:
        def mark_prose_nontranslatable(root: Path) -> None:
            path = root / "app/src/main/res/values/strings.xml"
            text = path.read_text(encoding="utf-8")
            marker = '<string name="settings_tab_shoot">Shoot</string>'
            self.assertIn(marker, text)
            path.write_text(
                text.replace(
                    marker,
                    '<string name="settings_tab_shoot" translatable="false">Shoot</string>',
                    1,
                ),
                encoding="utf-8",
            )

        result, private_docs_present = run_documentation_gate_from_committed_export(
            mark_prose_nontranslatable,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  translation exceptions match the closed abbreviation and identity allow list",
            result.stdout,
        )

    def test_committed_export_rejects_default_only_translatable_resource(self) -> None:
        def add_default_only_resource(root: Path) -> None:
            path = root / "app/src/main/res/values/strings.xml"
            text = path.read_text(encoding="utf-8")
            marker = "</resources>"
            self.assertIn(marker, text)
            path.write_text(
                text.replace(
                    marker,
                    '    <string name="unapproved_default_only">English only</string>\n</resources>',
                    1,
                ),
                encoding="utf-8",
            )

        result, private_docs_present = run_documentation_gate_from_committed_export(
            add_default_only_resource,
        )

        self.assertEqual(private_docs_present, ())
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  every translatable string and plural has a Korean peer",
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

    def test_committed_export_rejects_truncated_phone_png_after_digest_update(self) -> None:
        def truncate_png(root: Path) -> None:
            relative = "docs/assets/play/screenshots/01-main-viewfinder.png"
            asset = root / relative
            data = asset.read_bytes()
            asset.write_bytes(data[:-20])
            manifest_path = root / "docs/assets/play/screenshots/asset-validity.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["assets"][relative] = hashlib.sha256(asset.read_bytes()).hexdigest()
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        result, _ = run_documentation_gate_from_committed_export(truncate_png)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  phone screenshot PNG bytes match the declared geometry and encoding",
            result.stdout,
        )

    def test_committed_export_rejects_bad_png_crc_after_digest_update(self) -> None:
        def corrupt_crc(root: Path) -> None:
            relative = "docs/assets/play/screenshots/01-main-viewfinder.png"
            asset = root / relative
            data = bytearray(asset.read_bytes())
            data[-1] ^= 0x01
            asset.write_bytes(data)
            manifest_path = root / "docs/assets/play/screenshots/asset-validity.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["assets"][relative] = hashlib.sha256(asset.read_bytes()).hexdigest()
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        result, _ = run_documentation_gate_from_committed_export(corrupt_crc)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  phone screenshot PNG bytes match the declared geometry and encoding",
            result.stdout,
        )

    def test_committed_export_rejects_missing_png_iend_after_digest_update(self) -> None:
        def remove_iend(root: Path) -> None:
            relative = "docs/assets/play/screenshots/01-main-viewfinder.png"
            asset = root / relative
            data = asset.read_bytes()
            self.assertEqual(data[-8:-4], b"IEND")
            asset.write_bytes(data[:-12])
            manifest_path = root / "docs/assets/play/screenshots/asset-validity.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["assets"][relative] = hashlib.sha256(asset.read_bytes()).hexdigest()
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        result, _ = run_documentation_gate_from_committed_export(remove_iend)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  phone screenshot PNG bytes match the declared geometry and encoding",
            result.stdout,
        )

    def test_committed_export_rejects_invalid_png_ihdr_methods_after_digest_update(self) -> None:
        for field_offset in (10, 11, 12):
            with self.subTest(field_offset=field_offset):
                def invalidate_ihdr(root: Path) -> None:
                    relative = "docs/assets/play/screenshots/01-main-viewfinder.png"
                    asset = root / relative
                    data = bytearray(asset.read_bytes())
                    self.assertEqual(data[12:16], b"IHDR")
                    ihdr = bytearray(data[16:29])
                    ihdr[field_offset] = 1
                    data[16:29] = ihdr
                    data[29:33] = struct.pack(
                        ">I",
                        zlib.crc32(b"IHDR" + ihdr) & 0xFFFFFFFF,
                    )
                    asset.write_bytes(data)
                    manifest_path = root / "docs/assets/play/screenshots/asset-validity.json"
                    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                    manifest["assets"][relative] = hashlib.sha256(asset.read_bytes()).hexdigest()
                    manifest_path.write_text(
                        json.dumps(manifest, indent=2) + "\n",
                        encoding="utf-8",
                    )

                result, _ = run_documentation_gate_from_committed_export(invalidate_ihdr)

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  phone screenshot PNG bytes match the declared geometry and encoding",
                    result.stdout,
                )

    def test_committed_export_rejects_illegal_png_palettes_after_digest_update(self) -> None:
        def chunk(kind: bytes, payload: bytes) -> bytes:
            return (
                struct.pack(">I", len(payload))
                + kind
                + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
            )

        cases = (
            ("post-idat", "docs/assets/play/screenshots/01-main-viewfinder.png", False, chunk(b"PLTE", b"\0\0\0")),
            ("duplicate", "docs/assets/play/screenshots/01-main-viewfinder.png", True, chunk(b"PLTE", b"\0\0\0") * 2),
            ("malformed", "docs/assets/play/screenshots/01-main-viewfinder.png", True, chunk(b"PLTE", b"\0\0")),
            ("forbidden-rgba", "docs/assets/play/screenshots/tablet/02-shooting.png", True, chunk(b"PLTE", b"\0\0\0")),
        )
        for label, relative, after_ihdr, palette_chunks in cases:
            with self.subTest(label=label):
                def mutate(root: Path) -> None:
                    asset = root / relative
                    data = asset.read_bytes()
                    if after_ihdr:
                        insertion = 8 + 12 + struct.unpack(">I", data[8:12])[0]
                    else:
                        insertion = data.rfind(b"\0\0\0\0IEND")
                    self.assertGreaterEqual(insertion, 8)
                    asset.write_bytes(data[:insertion] + palette_chunks + data[insertion:])
                    manifest_path = asset.parent / "asset-validity.json"
                    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                    manifest["assets"][relative] = hashlib.sha256(asset.read_bytes()).hexdigest()
                    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(mutate)

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertNotIn("Traceback", result.stderr)
                expected_label = "tablet" if "tablet/" in relative else "phone"
                self.assertIn(
                    f"FAIL  {expected_label} screenshot PNG bytes match the declared geometry and encoding",
                    result.stdout,
                )

    def test_committed_export_rejects_one_byte_overlong_png_without_traceback(self) -> None:
        def replace_with_overlong_raster(root: Path) -> None:
            relative = "docs/assets/play/screenshots/01-main-viewfinder.png"
            asset = root / relative

            def chunk(kind: bytes, payload: bytes) -> bytes:
                return (
                    struct.pack(">I", len(payload))
                    + kind
                    + payload
                    + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
                )

            asset.write_bytes(
                b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00\x00"))
                + chunk(b"IEND", b"")
            )
            manifest_path = asset.parent / "asset-validity.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["assets"][relative] = hashlib.sha256(asset.read_bytes()).hexdigest()
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        result, _ = run_documentation_gate_from_committed_export(replace_with_overlong_raster)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertIn(
            "FAIL  phone screenshot PNG bytes match the declared geometry and encoding",
            result.stdout,
        )

    def test_committed_export_rejects_illegal_png_chunk_types_and_ancillary_order(self) -> None:
        def chunk(kind: bytes, payload: bytes) -> bytes:
            return (
                struct.pack(">I", len(payload))
                + kind
                + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
            )

        cases = (
            ("non-letter", chunk(b"12x4", b""), False),
            ("reserved-bit", chunk(b"abcd", b""), False),
            ("late-trns", chunk(b"tRNS", b"\0" * 6), False),
            (
                "trns-before-plte",
                chunk(b"tRNS", b"\0" * 6) + chunk(b"PLTE", b"\0\0\0"),
                True,
            ),
            ("late-srgb", chunk(b"sRGB", b"\0"), False),
            ("late-iccp", chunk(b"iCCP", b"test\0\0" + zlib.compress(b"profile")), False),
            ("malformed-srgb", chunk(b"sRGB", b"\4"), True),
            ("malformed-iccp", chunk(b"iCCP", b"test\0\1" + zlib.compress(b"profile")), True),
        )
        for label, injected, after_ihdr in cases:
            with self.subTest(label=label):
                def mutate(root: Path) -> None:
                    relative = "docs/assets/play/screenshots/02-pro-settings.png"
                    asset = root / relative
                    data = asset.read_bytes()
                    insertion = (
                        8 + 12 + struct.unpack(">I", data[8:12])[0]
                        if after_ihdr
                        else data.rfind(b"\0\0\0\0IEND")
                    )
                    self.assertGreaterEqual(insertion, 8)
                    asset.write_bytes(data[:insertion] + injected + data[insertion:])
                    manifest_path = asset.parent / "asset-validity.json"
                    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                    manifest["assets"][relative] = hashlib.sha256(asset.read_bytes()).hexdigest()
                    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(mutate)

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertNotIn("Traceback", result.stderr)
                self.assertIn(
                    "FAIL  phone screenshot PNG bytes match the declared geometry and encoding",
                    result.stdout,
                )

    def test_committed_export_validates_truecolor_transparency_sample_range(self) -> None:
        def chunk(kind: bytes, payload: bytes) -> bytes:
            return (
                struct.pack(">I", len(payload))
                + kind
                + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
            )

        cases = (
            ("maximum-8-bit-sample", (255, 255, 255), True),
            ("first-out-of-range-sample", (256, 0, 0), False),
            ("maximum-16-bit-storage-sample", (0, 0, 65535), False),
        )
        for label, samples, should_pass in cases:
            with self.subTest(label=label):
                def mutate(root: Path) -> None:
                    relative = "docs/assets/play/screenshots/02-pro-settings.png"
                    asset = root / relative
                    data = asset.read_bytes()
                    insertion = 8 + 12 + struct.unpack(">I", data[8:12])[0]
                    transparency = chunk(b"tRNS", struct.pack(">HHH", *samples))
                    asset.write_bytes(data[:insertion] + transparency + data[insertion:])
                    manifest_path = asset.parent / "asset-validity.json"
                    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                    manifest["assets"][relative] = hashlib.sha256(asset.read_bytes()).hexdigest()
                    manifest_path.write_text(
                        json.dumps(manifest, indent=2) + "\n",
                        encoding="utf-8",
                    )

                result, _ = run_documentation_gate_from_committed_export(mutate)

                self.assertNotIn("Traceback", result.stderr)
                if should_pass:
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                else:
                    self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn(
                        "FAIL  phone screenshot PNG bytes match the declared geometry and encoding",
                        result.stdout,
                    )

    def test_committed_export_rejects_release_capture_trace_contract_regressions(self) -> None:
        def mutate_trace(root: Path, mutation: str) -> None:
            path = root / "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt"
            text = path.read_text(encoding="utf-8")
            if mutation == "unwrap":
                needle = "traceText?.takeIf {"
                self.assertIn(needle, text)
                text = text.replace(needle, "traceText!!.takeIf {", 1)
            elif mutation == "release":
                needle = "me.hletrd.telecampro.BuildConfig.DEBUG,"
                self.assertIn(needle, text)
                text = text.replace(needle, "true,", 1)
            else:
                needle = "traceAdmission.registration && recurringDiagnosticAllowed("
                self.assertIn(needle, text)
                text = text.replace(needle, "traceAdmission.registration && (", 1)
            path.write_text(text, encoding="utf-8")

        for mutation in ("release", "unwrap", "budget"):
            with self.subTest(mutation=mutation):
                result, _ = run_documentation_gate_from_committed_export(
                    lambda root: mutate_trace(root, mutation),
                )

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  release capture tracing is build-gated and nullable-safe at the production callback",
                    result.stdout,
                )

    def test_committed_export_rejects_unclassified_repeatable_debug_logs(self) -> None:
        fixtures = (
            (
                "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt",
                "import me.hletrd.telecampro.camera.DiagnosticLog as Log",
                "import android.util.Log",
            ),
            (
                "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt",
                "import me.hletrd.telecampro.camera.DiagnosticLog as Log",
                "import android.util.Log",
            ),
            (
                "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt",
                "    // ---- Photo ----",
                '    private fun unclassifiedDebugMutation() { android.util.Log.i("Mutation", "unbudgeted") }\n\n' +
                "    // ---- Photo ----",
            ),
            (
                "app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt",
                "        val admitted = if (terminal) {\n"
                "            me.hletrd.telecampro.camera.evidenceDiagnosticAllowed("
                "me.hletrd.telecampro.BuildConfig.DEBUG)\n"
                "        } else {\n"
                "            me.hletrd.telecampro.camera.recurringDiagnosticAllowed("
                "me.hletrd.telecampro.BuildConfig.DEBUG)\n"
                "        }\n",
                "        val admitted = me.hletrd.telecampro.BuildConfig.DEBUG\n",
            ),
        )
        for relative, current, stale in fixtures:
            with self.subTest(relative=relative, stale=stale):
                def mutate(root: Path) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, stale, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(mutate)

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  every production debug log site has an executable quota classification",
                    result.stdout,
                )

    def test_committed_export_requires_a_structural_guard_on_raw_debug_rows(self) -> None:
        # AR6-4 / AGG6-37: a gate TOKEN within 1,200 characters used to classify a raw row as
        # budgeted. Each mutation below kept such a token nearby and passed the lexical scan.
        engine = "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt"
        telemetry = "app/src/main/kotlin/me/hletrd/telecampro/camera/DiagnosticTelemetry.kt"
        destroyed = (
            '            android.util.Log.i("CameraEngine", "PreviewSurface: DESTROYED '
            '(current=${System.identityHashCode(previewSurface)})")\n        }\n'
        )
        fixtures = (
            # An unguarded raw row AFTER an unrelated guarded block in the same function.
            (
                engine,
                destroyed,
                destroyed + '        android.util.Log.i("CameraEngine", "unguarded after a guard")\n',
            ),
            # The gate named only in a comment.
            (
                engine,
                "    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) {\n"
                "        if (recurringDiagnosticAllowed(BuildConfig.DEBUG)) {\n",
                "    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) {\n"
                "        if (BuildConfig.DEBUG) { // recurringDiagnosticAllowed(BuildConfig.DEBUG)\n",
            ),
            # The gate ORed away, and the gate negated.
            (
                telemetry,
                "        if (recurringDiagnosticAllowed(debugEnabled = true, recurring)) {\n"
                "            android.util.Log.d(tag, message)",
                "        if (debugEnabled || recurringDiagnosticAllowed(debugEnabled = true, recurring)) {\n"
                "            android.util.Log.d(tag, message)",
            ),
            (
                telemetry,
                "        if (recurringDiagnosticAllowed(debugEnabled = true, recurring)) {\n"
                "            android.util.Log.d(tag, message)",
                "        if (!recurringDiagnosticAllowed(debugEnabled = true, recurring)) {\n"
                "            android.util.Log.d(tag, message)",
            ),
        )
        for relative, current, stale in fixtures:
            with self.subTest(relative=relative, stale=stale):
                def mutate(root: Path) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, stale, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(mutate)

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  every production debug log site has an executable quota classification",
                    result.stdout,
                )

    def test_committed_export_rejects_a_double_charged_gated_debug_row(self) -> None:
        # AGG5-9: a pre-gated row routed back through the `DiagnosticLog as Log` door is charged
        # twice. Reverting any one of the single-charge sites must fail the gate.
        fixtures = (
            (
                "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt",
                'android.util.Log.i(TAG, "ZoomTrace: submit=$ratio t=$zoomSubmitNowMs")',
                'Log.i(TAG, "ZoomTrace: submit=$ratio t=$zoomSubmitNowMs")',
            ),
            (
                "app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt",
                'android.util.Log.i(\n                "FocusConfidence",',
                'Log.i(\n                "FocusConfidence",',
            ),
        )
        for relative, current, stale in fixtures:
            with self.subTest(relative=relative):
                def mutate(root: Path) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, stale, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(mutate)

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  a recurring-gated debug row is charged once, not again through the "
                    "DiagnosticLog door",
                    result.stdout,
                )

    def test_committed_export_rejects_bypassed_completed_dng_transfer_composition(self) -> None:
        def mutate(root: Path) -> None:
            path = root / "app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt"
            text = path.read_text(encoding="utf-8")
            current = "dngPublishQueued = transferCompletedDngFromCameraCallback("
            self.assertIn(current, text)
            path.write_text(
                text.replace(current, "dngPublishQueued = transferCompletedDngPublication(", 1),
                encoding="utf-8",
            )

        result, _ = run_documentation_gate_from_committed_export(mutate)

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "FAIL  CameraEngine retains the tested completed-DNG transfer composition",
            result.stdout,
        )

    def test_committed_export_rejects_unscoped_or_unapproved_signing_procedures(self) -> None:
        fixtures = (
            (
                "docs/play-console-submit.md",
                "gpg --batch --quiet --decrypt telecampro-upload-passwords.txt.gpg |",
                "export TELECAMPRO_STORE_PASSWORD=plaintext",
            ),
            (
                "tools/build_immutable_release.py",
                '"-storepass:env",',
                '"-storepass",',
            ),
            (
                "tools/build_immutable_release.py",
                "        require_approved_upload_key(args.root, args.tasks, os.environ)\n",
                "        pass\n",
            ),
            (
                "tools/run_scoped_signed_release.py",
                "verify_upload_key_certificate(root, prerequisite, child_environment, run)",
                "None",
            ),
            (
                "docs/play-console-submit.md",
                "upload key is **SECURITY-BLOCKED**",
                "upload key is ready",
            ),
        )
        for relative, current, stale in fixtures:
            with self.subTest(relative=relative, stale=stale):
                def regress(root: Path) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, stale, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(regress)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  signed release procedure scopes secrets and requires owner-approved key replacement",
                    result.stdout,
                )

    def test_committed_export_rejects_a_secret_floor_constant_edited_on_one_side(self) -> None:
        # SEC4-2 / AGG4-38: the Gradle floor is a port; a one-sided edit must turn the gate red.
        for relative, current, stale in (
            ("app/build.gradle.kts", "val uploadKeyMinDistinctCharacters = 12", "val uploadKeyMinDistinctCharacters = 2"),
            ("tools/upload_key_policy.py", "MAX_REPEAT_RUN = 3", "MAX_REPEAT_RUN = 30"),
            (
                "app/build.gradle.kts",
                "            if (!keyPasswordMeetsFloor) {",
                "            if (false) {",
            ),
        ):
            with self.subTest(relative=relative, stale=stale):
                def regress(root: Path) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, stale, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(regress)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(
                    "FAIL  Gradle release gate enforces the same generated-secret floor",
                    result.stdout,
                )

    # SEC4-3 / AGG4-40: every value below is SYNTHETIC and deliberately implausible (a length in the
    # hundreds, a letters-only class, a generic channel). None restates a real credential's property,
    # and none is spliced onto the production anchor sentence; each is appended as its own paragraph.
    SYNTHETIC_PASSWORD_LEAKS = (
        "SYNTHETIC-FIXTURE: the example passphrase is 300 characters long.",
        "SYNTHETIC-FIXTURE: the example passphrase (letters only) is fictional.",
        "SYNTHETIC-FIXTURE: the example passphrase was transmitted in the clear.",
    )
    PASSWORD_RULE = "tracked docs state no password length, character class, or delivery channel"

    def test_committed_export_rejects_password_property_phrasing(self) -> None:
        # SEC2-1: a password's length, character class, or delivery channel is itself secret. The
        # synthetic marker exempts a sentence ONLY inside the allowlisted test source, never in a doc.
        for leak in self.SYNTHETIC_PASSWORD_LEAKS:
            with self.subTest(leak=leak):
                def regress(root: Path) -> None:
                    path = root / "docs/play-console-submit.md"
                    path.write_text(path.read_text(encoding="utf-8") + f"\n{leak}\n", encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(regress)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(f"FAIL  {self.PASSWORD_RULE}", result.stdout)
                self.assertIn("docs/play-console-submit.md", result.stdout)

    def test_committed_export_scans_tracked_text_sources_outside_markdown(self) -> None:
        # SEC4-3: the rule used to read `.md` only, so a test fixture restated a property unseen.
        leak = self.SYNTHETIC_PASSWORD_LEAKS[0]
        unmarked = leak.replace("SYNTHETIC-FIXTURE: ", "")
        for relative, text in (
            ("tools/release_notes.txt", f"{unmarked}\n"),
            ("tools/extra_release_helper.py", f"# {unmarked}\n"),
            ("gradle/extra.toml", f"# {unmarked}\n"),
            ("app/extra.gradle.kts", f"// {unmarked}\n"),
            # SR5-4 / RG5-14 (AGG5-18): every published UTF-8 text file, not a suffix allowlist.
            (
                "app/src/main/kotlin/me/hletrd/telecampro/LeakFixture.kt",
                f"package me.hletrd.telecampro\n\n// {unmarked}\ninternal val leakFixture = 1\n",
            ),
            ("privacy-policy/leak.html", f"<html><body><p>{unmarked}</p></body></html>\n"),
            (
                "app/src/main/res/values/leak_strings.xml",
                f'<resources><!-- {unmarked} --></resources>\n',
            ),
            ("tools/leak.sh", f"#!/bin/sh\n# {unmarked}\n"),
            ("tools/leak.json", f'{{"note": "{unmarked}"}}\n'),
            ("tools/leak.yml", f"note: {unmarked}\n"),
            ("tools/leak.properties", f"# {unmarked}\n"),
            ("tools/NOTES.md", f"{unmarked}\n"),
            # The marker exempts nothing outside the allowlisted fixture file.
            ("tools/tests/test_other_fixture.py", f'LEAK = "{leak}"\n'),
        ):
            with self.subTest(relative=relative):
                def add(root: Path) -> None:
                    (root / relative).parent.mkdir(parents=True, exist_ok=True)
                    (root / relative).write_text(text, encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(add)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(f"FAIL  {self.PASSWORD_RULE}", result.stdout)
                self.assertIn(relative, result.stdout)

    def test_binary_files_are_skipped_by_content_not_by_name(self) -> None:
        # AGG5-18: the scan decides "text" by content. A NUL-bearing payload that happens to contain
        # a matching sentence is a binary and is skipped without crashing the gate.
        leak = self.SYNTHETIC_PASSWORD_LEAKS[0].replace("SYNTHETIC-FIXTURE: ", "")

        def add(root: Path) -> None:
            (root / "tools/blob.txt").write_bytes(b"\x00\x01" + leak.encode("utf-8") + b"\xff\xfe")

        result, _ = run_documentation_gate_from_committed_export(add)
        self.assertNotIn("Traceback", result.stderr)
        self.assertNotIn("tools/blob.txt", result.stdout)
        self.assertIn(f"ok    {self.PASSWORD_RULE}", result.stdout)

    def test_non_utf8_text_is_scanned_not_skipped(self) -> None:
        # SR6-7 / RG6-13 (AGG6-33): an undecodable byte used to drop the whole file from the scan.
        leak = self.SYNTHETIC_PASSWORD_LEAKS[0].replace("SYNTHETIC-FIXTURE: ", "")
        for relative, payload in (
            ("tools/latin1.properties", b"# caf\xe9\n# " + leak.encode("ascii") + b"\n"),
            ("tools/utf16-note.txt", ("\ufeff" + leak + "\n").encode("utf-16-le")),
        ):
            with self.subTest(relative=relative):
                def add(root: Path, relative: str = relative, payload: bytes = payload) -> None:
                    (root / relative).write_bytes(payload)

                result, _ = run_documentation_gate_from_committed_export(add)
                self.assertNotIn("Traceback", result.stderr)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(f"FAIL  {self.PASSWORD_RULE}", result.stdout)
                self.assertIn(relative, result.stdout)

    def test_new_unstaged_files_are_scanned_but_ignored_ones_are_not(self) -> None:
        # REG4-3 / DBG4-4: the pre-commit gate must see a published file the author has not added
        # yet. `/docs/*.md` and `.context/` are ignored (private by default; a public doc there is
        # force-added, which stages it), so those stay out exactly as DOC3-1 wants.
        note = "SYNTHETIC-FIXTURE note: the example passphrase is 300 characters long.\n"
        unmarked = note.replace("SYNTHETIC-FIXTURE note: ", "")
        result, _ = run_documentation_gate_from_committed_export(
            untracked={
                "docs/licenses/new-runbook.md": unmarked,
                "tools/new_release_note.txt": unmarked,
                "docs/private-draft.md": unmarked,
                ".context/reviews/untracked-note.md": unmarked,
            },
        )
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"FAIL  {self.PASSWORD_RULE}", result.stdout)
        self.assertIn("docs/licenses/new-runbook.md", result.stdout)
        self.assertIn("tools/new_release_note.txt", result.stdout)
        self.assertNotIn("docs/private-draft.md", result.stdout)
        self.assertNotIn("untracked-note.md", result.stdout)

    def test_export_inside_a_foreign_repository_falls_back_to_the_glob(self) -> None:
        # DBG4-4: `git ls-files` in another work tree lists nothing and used to pass vacuously.
        leak = self.SYNTHETIC_PASSWORD_LEAKS[2]

        def regress(root: Path) -> None:
            path = root / "docs/play-console-submit.md"
            path.write_text(path.read_text(encoding="utf-8") + f"\n{leak}\n", encoding="utf-8")

        result, _ = run_documentation_gate_from_committed_export(regress, foreign_repository=True)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"FAIL  {self.PASSWORD_RULE}", result.stdout)
        self.assertIn("docs/play-console-submit.md", result.stdout)

    def test_untracked_review_notes_cannot_change_the_password_property_verdict(self) -> None:
        # DOC3-1: reviewer notes under .context/ are gitignored scratch; only published files count.
        note = "SYNTHETIC-FIXTURE note: the example passphrase was transmitted in the clear.\n"
        unmarked = note.replace("SYNTHETIC-FIXTURE note: ", "")
        result, _ = run_documentation_gate_from_committed_export(
            untracked={".context/reviews/untracked-note.md": unmarked},
        )
        self.assertNotIn("untracked-note.md", result.stdout)

        def track(root: Path) -> None:
            (root / ".context/reviews").mkdir(parents=True, exist_ok=True)
            (root / ".context/reviews/tracked-note.md").write_text(unmarked, encoding="utf-8")

        tracked, _ = run_documentation_gate_from_committed_export(track)
        self.assertNotEqual(tracked.returncode, 0, tracked.stdout + tracked.stderr)
        self.assertIn("tracked-note.md", tracked.stdout)

    def test_committed_export_rejects_doc_scoping_and_symbol_regressions(self) -> None:
        # AGG4-53 / AGG4-56 / AGG4-60: each regression is the exact pre-fix text.
        for relative, current, stale, rule in (
            (
                "docs/ARCHITECTURE.md",
                "`gl.setFrontMirrorConvention(front, streamPreMirrored)`",
                "`gl.setFrontStreamPreMirrored`",
                "architecture DeviceProfile table cites only seams that exist in source",
            ),
            (
                "docs/ARCHITECTURE.md",
                "`CameraEngine.rawForcesStandalone`",
                "`CameraCaps.rawForcesStandalone`",
                "architecture DeviceProfile table cites only seams that exist in source",
            ),
            (
                "README.md",
                "On the Find X9 Ultra, wanting RAW",
                "Wanting RAW",
                "README and CLAUDE scope the RAW law",
            ),
            (
                "CLAUDE.md",
                "used by exactly two draws",
                "with exactly one caller",
                "README and CLAUDE scope the RAW law",
            ),
        ):
            with self.subTest(relative=relative, stale=stale):
                def regress(root: Path) -> None:
                    path = root / relative
                    text = path.read_text(encoding="utf-8")
                    self.assertIn(current, text)
                    path.write_text(text.replace(current, stale, 1), encoding="utf-8")

                result, _ = run_documentation_gate_from_committed_export(regress)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(f"FAIL  {rule}", result.stdout)

    def test_stacked_kdoc_scan_enforces_and_ignores_plain_file_headers(self) -> None:
        # DOC4-6 / AGG4-55: every known site was re-homed, so a new stacked pair fails the gate,
        # while a plain `/* … */` file header above a KDoc documents no declaration and stays legal.
        relative = "app/src/main/kotlin/me/hletrd/telecampro/StackedKdocFixture.kt"
        header_relative = "app/src/main/kotlin/me/hletrd/telecampro/PlainHeaderFixture.kt"
        source = (
            "package me.hletrd.telecampro\n\n"
            "/** Detached rationale that documents nothing. */\n\n"
            "/** The block Dokka actually attaches. */\n"
            "internal val stackedKdocFixture = 1\n"
        )
        header_source = (
            "package me.hletrd.telecampro\n\n"
            "/*\n * File-level rationale that documents no single declaration.\n */\n\n"
            "/** The declaration's own KDoc. */\n"
            "internal val plainHeaderFixture = 1\n"
        )

        def add(root: Path) -> None:
            (root / relative).write_text(source, encoding="utf-8")
            (root / header_relative).write_text(header_source, encoding="utf-8")

        enforced, _ = run_documentation_gate_from_committed_export(add)
        self.assertNotEqual(enforced.returncode, 0, enforced.stdout + enforced.stderr)
        self.assertIn("FAIL  no stacked KDoc pair leaves a rationale detached", enforced.stdout)
        self.assertIn(f"{relative}:3", enforced.stdout)
        self.assertNotIn(f"{header_relative}:", enforced.stdout)

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
