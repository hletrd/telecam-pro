"""The ONE blocked upload-certificate deny-list and its Gradle reader (SEC2-2 / AGG2-37)."""

from __future__ import annotations

import os
import re
import unittest
import zipfile
from pathlib import Path

from tools import upload_key_policy as policy


REPO_ROOT = Path(__file__).resolve().parents[2]


class BlockedUploadCertificateListTest(unittest.TestCase):
    def test_retired_upload_key_is_blocked_in_every_spelling(self) -> None:
        retired = "9dfdb903269238ef6de424052666b05814577b4b3bb43a5e3e3a05572660e584"
        self.assertIn(retired, policy.BLOCKED_UPLOAD_CERT_SHA256)
        colon = ":".join(retired[i : i + 2].upper() for i in range(0, 64, 2))
        for spelling in (retired, retired.upper(), colon, f"  {retired}\n"):
            with self.subTest(spelling=spelling):
                self.assertTrue(policy.is_blocked_upload_certificate(spelling))
        self.assertFalse(policy.is_blocked_upload_certificate("a" * 64))

    def test_unparseable_fingerprint_fails_closed(self) -> None:
        for value in ("", "not-hex", "a" * 63, "g" * 64):
            with self.subTest(value=value):
                self.assertTrue(policy.is_blocked_upload_certificate(value))

    def test_malformed_or_empty_list_is_an_error_not_an_empty_deny_list(self) -> None:
        with self.assertRaisesRegex(ValueError, "empty"):
            policy.parse_blocked_upload_certificates("# only a comment\n\n")
        with self.assertRaisesRegex(ValueError, "line 2"):
            policy.parse_blocked_upload_certificates("a" * 64 + "\nNOT-A-HASH\n")
        self.assertEqual(
            frozenset({"b" * 64}),
            policy.parse_blocked_upload_certificates("# c\n" + "b" * 64 + "  # trailing\n"),
        )

    def test_checker_no_longer_pins_a_signer_constant(self) -> None:
        checker = (REPO_ROOT / "tools/check_release_artifact.py").read_text(encoding="utf-8")
        self.assertNotIn("EXPECTED_UPLOAD_CERT_SHA256", checker)
        for blocked in policy.BLOCKED_UPLOAD_CERT_SHA256:
            self.assertNotIn(blocked, checker)
        self.assertIn("approved_upload_certificate_sha256(root)", checker)
        self.assertIn("is_blocked_upload_certificate(signer)", checker)


class GeneratedSecretFloorTest(unittest.TestCase):
    """SEC3-2: one floor, shared by the scoped helper and the immutable wrapper."""

    def test_weak_shapes_fail_and_generated_shapes_pass(self) -> None:
        for weak in (
            "123456",
            "0" * 20,
            "a" * 20,
            "abcdefghijklmnopqrst",
            "only-two-classes-long",
            " Strong-password-Value-7!",
            "Strong-password-Value-7! ",
            "Abcdefg-1234567-Value!",
        ):
            with self.subTest(weak=weak):
                self.assertFalse(policy.meets_generated_secret_floor(weak))
        for strong in ("Store-password-with-Entropy-7!", "Key-password-with-Entropy-8!"):
            with self.subTest(strong=strong):
                self.assertTrue(policy.meets_generated_secret_floor(strong))

    def test_low_entropy_shapes_fail(self) -> None:
        # SEC4-5 / AGG4-42: each of these passed the length + class + "not one character" floor.
        for weak in (
            "a" * 18 + "A1",  # long repeat run, 3 distinct characters
            "Aa1" * 7,  # whole-value period 3
            "Password1234Password",  # dictionary word around a short walk: 11 distinct
            "Xq7-Xq7-Xq7-Xq7-Xq7-",  # period 4, would otherwise clear every other rule
            "Zk4!mR9#vT2$wQ8%nnnnB",  # one run of four
        ):
            with self.subTest(weak=weak):
                self.assertFalse(policy.meets_generated_secret_floor(weak))
        # Each structural rule on its own: a value that clears everything but that rule.
        self.assertTrue(policy.meets_generated_secret_floor("Zk4!mR9#vT2$wQ8%nnnB"))
        self.assertFalse(policy._has_short_period("Zk4!mR9#vT2$wQ8%nnnB"))
        self.assertTrue(policy._has_short_period("Xq7-Xq7-Xq7-Xq7-Xq7-"))
        self.assertTrue(policy._has_long_repeat_run("abbbbc"))
        self.assertFalse(policy._has_long_repeat_run("abbbc"))
        self.assertEqual(12, policy.MIN_DISTINCT_CHARACTERS)
        self.assertFalse(policy.meets_generated_secret_floor("Aa1-Bb2-Cc3-Aa1-Bb2-C"))  # 10 distinct

    def test_documented_generator_output_clears_the_floor(self) -> None:
        # docs/play-console-submit.md tells the owner to generate each secret with
        # `secrets.token_urlsafe(32)`: 43 characters over [A-Za-z0-9_-]. Seeded so the test is
        # deterministic; the structural rules must not reject generated output except by rare
        # chance (a draw with fewer than three classes or a 6-step walk), never systematically.
        import random
        import string

        alphabet = string.ascii_letters + string.digits + "-_"
        generator = random.Random(20261002)
        samples = ["".join(generator.choice(alphabet) for _ in range(43)) for _ in range(2000)]
        rejected = [value for value in samples if not policy.meets_generated_secret_floor(value)]
        self.assertLess(len(rejected), 5)
        docs = (REPO_ROOT / "docs/play-console-submit.md").read_text(encoding="utf-8")
        self.assertIn("secrets.token_urlsafe(32)", docs)

    def test_both_wrappers_use_the_policy_module_rule(self) -> None:
        for name in ("build_immutable_release.py", "run_scoped_signed_release.py"):
            text = (REPO_ROOT / "tools" / name).read_text(encoding="utf-8")
            with self.subTest(name=name):
                self.assertIn("meets_generated_secret_floor", text)
                self.assertNotIn("def _has_monotonic_run", text)
                self.assertNotIn("MIN_STRONG_PASSWORD_LENGTH = ", text)


class GradleReleaseSigningRefusalTest(unittest.TestCase):
    """Plain `./gradlew bundleRelease` must refuse the same way the wrappers do."""

    def setUp(self) -> None:
        self.gradle = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        start = self.gradle.index("if (hasReleaseSigning) {\n    val approvalValue")
        self.block = self.gradle[start:]

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
        self.assertIn("tasks.matching { it.name in releaseSigningTasks }.configureEach {", self.block)
        self.assertIn("doFirst {", self.block)

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
        # SEC4-1: the gate is a NAME set, so pin it against the task names the pinned AGP registers.
        # The name prefixes are string constants in each task's CreationAction class.
        jar = self.agp_jar()
        if jar is None:
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
        gated = self.release_signing_task_set()
        unreviewed = sorted(
            prefix
            for prefix in prefixes
            if f'"{prefix}Release"' not in gated and prefix not in self.NON_SIGNING_APK_TASK_PREFIXES
        )
        self.assertEqual([], unreviewed)

    def test_gate_inputs_carry_the_alias_and_store_path(self) -> None:
        # SEC4-1: an alias switch inside the same keystore must re-execute every gated task.
        self.assertIn("val signingAlias = releaseKeyAlias!!", self.block)
        self.assertIn("val signingStorePath = releaseStoreFile!!", self.block)
        self.assertIn("|$signingAlias|$signingStorePath\",", self.block)

    def test_gradle_reads_the_shared_deny_list_and_verifies_in_process(self) -> None:
        self.assertIn('rootProject.file("tools/blocked-upload-certificates.txt")', self.block)
        self.assertIn('keystoreProps.getProperty("uploadKeyRotationApproved")', self.block)
        self.assertIn('keystoreProps.getProperty("uploadKeyCertificateSha256")', self.block)
        self.assertIn("KeyStore.getInstance(signingStoreFile", self.block)
        self.assertIn("if (actualCertificate in blocked)", self.block)
        self.assertIn("if (actualCertificate != approvedCertificate)", self.block)
        # A doFirst never runs for an up-to-date task: the gate state must be a task input.
        self.assertIn('inputs.property(\n            "uploadKeyApprovalState",', self.block)
        self.assertIn('inputs.file(signingStoreFile).withPropertyName("uploadKeystore")', self.block)
        # Refusal text stays value-free.
        self.assertNotIn("$signingStorePassword", self.block)

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
        # SEC3-1: AGP replaces the gated signingConfig whenever android.injected.signing.* is set, so
        # the gate would approve the keystore.properties key while AGP signed with the injected one.
        start = self.gradle.index("val injectedSigningPropertyNames")
        refusal = self.gradle[start : self.gradle.index("if (!hasReleaseSigning) {", start)]
        self.assertIn('providers.gradlePropertiesPrefixedBy("android.injected.signing.")', refusal)
        # Attached OUTSIDE `if (hasReleaseSigning)`, so it also holds with no keystore.properties.
        self.assertLess(start, self.gradle.index("if (hasReleaseSigning) {\n    val approvalValue"))
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
        # The approval gate's up-to-date input carries the injected names too.
        self.assertIn("|$injectedNames|\" +", self.block)

    def test_gradle_gate_applies_the_secret_floor_to_both_effective_passwords(self) -> None:
        # SEC4-2 / AGG4-38: plain Gradle refuses a weak rotated key like both wrappers do.
        self.assertIn("val storePasswordMeetsFloor = meetsGeneratedSecretFloor(signingStorePassword)", self.block)
        self.assertIn("val keyPasswordMeetsFloor = meetsGeneratedSecretFloor(releaseKeyPassword!!)", self.block)
        self.assertIn(
            'val releaseKeyPassword = signingValue("keyPassword", "TELECAMPRO_KEY_PASSWORD") ?: releaseStorePassword',
            self.gradle,
        )
        self.assertIn("if (!storePasswordMeetsFloor) {", self.block)
        self.assertIn("if (!keyPasswordMeetsFloor) {", self.block)
        self.assertIn("$storePasswordMeetsFloor|$keyPasswordMeetsFloor", self.block)
        # Floor before the keystore is opened with the password; value-free messages.
        self.assertLess(
            self.block.index("if (!keyPasswordMeetsFloor) {"),
            self.block.index("KeyStore.getInstance(signingStoreFile"),
        )
        self.assertNotIn("$signingStorePassword", self.block)
        self.assertNotIn("$releaseKeyPassword", self.block)


if __name__ == "__main__":
    unittest.main()
