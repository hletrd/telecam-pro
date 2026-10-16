"""Gradle's release package/sign task set and its two refusals (missing / IDE-injected signing).

Owner decision 2026-10-13: there is NO upload-key approval, deny-list, or password floor in Gradle;
see tools/tests/test_no_upload_key_gates.py. These tests only keep the task-name set honest.
"""

from __future__ import annotations

import os
import re
import unittest
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


class GradleReleaseSigningTaskTest(unittest.TestCase):
    """Release package/sign tasks refuse missing signing and IDE-injected signing (nothing else)."""

    def setUp(self) -> None:
        self.gradle = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")

    def release_signing_task_set(self) -> str:
        start = self.gradle.index("val releaseSigningTasks = setOf(")
        return self.gradle[start : self.gradle.index(")", start)]

    def test_every_release_package_and_sign_task_is_gated(self) -> None:
        tasks = set(re.findall(r'"([A-Za-z]+Release[A-Za-z]*)"', self.release_signing_task_set()))
        self.assertEqual(
            {
                "packageRelease",
                "packageReleaseBundle",
                "packageReleaseUniversalApk",
                "signReleaseBundle",
                # SEC4-1 / AGG4-39: AGP's bundle-to-APK signer and its extractor.
                "makeApkFromBundleForRelease",
                "extractApksFromBundleForRelease",
            },
            tasks,
        )

    # Release APK/bundle task-name prefixes AGP registers that are reviewed as NOT signing, with why.
    NON_SIGNING_APK_TASK_PREFIXES = {
        # ApkZipPackagingTask re-zips packageRelease's output, which is already gated and signed.
        "zipApksFor": "re-zips the gated packageRelease output",
        # A method name inside PackageForHostTest, not a task registration.
        "getApkFor": "not a task name",
    }

    def agp_jar(self) -> Path | None:
        catalog = (REPO_ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
        version = re.search(r'(?m)^agp = "([^"]+)"$', catalog)
        self.assertIsNotNone(version)
        gradle_home = Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
        jars = sorted(
            gradle_home.glob(
                f"caches/modules-2/files-2.1/com.android.tools.build/gradle/{version.group(1)}/*/"
                f"gradle-{version.group(1)}.jar"
            )
        )
        return jars[0] if jars else None

    def test_every_agp_release_apk_or_bundle_task_prefix_is_gated_or_reviewed(self) -> None:
        # SEC4-1: the refusal set is a NAME set, so pin it against the task names the pinned AGP registers.
        # The name prefixes are string constants in each task's CreationAction class.
        jar = self.agp_jar()
        if jar is None:
            # TE5-17 (AGG5-67): under the host gate, Gradle has just run, so a missing jar means the
            # cache layout, GRADLE_USER_HOME, or the AGP coordinates moved — exactly when this pin
            # must not go quiet. Outside the gate (a bare unittest run) a skip stays honest.
            if os.environ.get("TELECAM_HOST_GATE") == "1":
                self.fail("the pinned AGP jar is not in the Gradle cache under the host gate")
            self.skipTest("the pinned AGP jar is not in the Gradle cache (run any Gradle build first)")
        prefixes: set[str] = set()
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.endswith(".class") and "CreationAction" in name:
                    prefixes.update(
                        match.decode("ascii")
                        for match in re.findall(
                            rb"[a-z][A-Za-z]*(?:Apk|Apks|Bundle)[A-Za-z]*For", archive.read(name)
                        )
                    )
        self.assertIn("makeApkFromBundleFor", prefixes)
        # A regex that drifted to matching almost nothing must not pass vacuously (AGP 9.4.1: 4).
        self.assertGreaterEqual(len(prefixes), 3, sorted(prefixes))
        gated = self.release_signing_task_set()
        unreviewed = sorted(
            prefix
            for prefix in prefixes
            if f'"{prefix}Release"' not in gated and prefix not in self.NON_SIGNING_APK_TASK_PREFIXES
        )
        self.assertEqual([], unreviewed)

    def test_the_host_gate_arms_the_agp_pin_failure(self) -> None:
        # TE5-17 (AGG5-67): the fail-instead-of-skip branch is live only if the gate sets the flag.
        source = (REPO_ROOT / "tools/verify_host.py").read_text(encoding="utf-8")
        self.assertIn('HOST_GATE_ENVIRONMENT = "TELECAM_HOST_GATE"', source)
        self.assertIn('HOST_GATE_ENVIRONMENT: "1",', source)

    def test_missing_signing_refuses_every_release_package_task(self) -> None:
        # SEC3-6: a doFirst on assembleRelease runs after packageRelease already wrote the APK, so the
        # no-keystore refusal must attach to the package/sign tasks themselves.
        start = self.gradle.index("if (!hasReleaseSigning) {")
        refusal = self.gradle[start : self.gradle.index("\n}\n", start)]
        self.assertIn(
            'tasks.matching { it.name in releaseSigningTasks || it.name == "bundleRelease" '
            '|| it.name == "assembleRelease" }',
            refusal,
        )
        self.assertIn("Release signing is required for Play upload.", refusal)

    def test_injected_signing_properties_refuse_every_release_package_task(self) -> None:
        # SEC3-1: AGP replaces the keystore.properties signingConfig whenever android.injected.signing.*
        # is set, so release bytes would be signed by a keystore the sealed release never recorded.
        start = self.gradle.index("val injectedSigningPropertyNames")
        refusal = self.gradle[start : self.gradle.index("if (!hasReleaseSigning) {", start)]
        self.assertIn('providers.gradlePropertiesPrefixedBy("android.injected.signing.")', refusal)
        # Attached unconditionally, so it also holds with no keystore.properties.
        self.assertLess(start, self.gradle.index("if (!hasReleaseSigning) {"))
        self.assertIn(
            'tasks.matching { it.name in releaseSigningTasks || it.name == "bundleRelease" '
            '|| it.name == "assembleRelease" }',
            refusal,
        )
        self.assertIn('inputs.property("injectedSigningProperties"', refusal)
        self.assertIn("if (injectedNames.isNotEmpty())", refusal)
        self.assertIn("Release signing refused: IDE-injected signing properties are present", refusal)
        # Only names are captured; no provider value is ever read or echoed.
        self.assertNotIn(".values", refusal)
        self.assertNotIn("gradleProperty(", refusal)


if __name__ == "__main__":
    unittest.main()
