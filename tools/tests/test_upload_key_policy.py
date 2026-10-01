"""The ONE blocked upload-certificate deny-list and its Gradle reader (SEC2-2 / AGG2-37)."""

from __future__ import annotations

import re
import unittest
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
        tasks = set(re.findall(r'"((?:package|sign)Release[A-Za-z]*)"', self.release_signing_task_set()))
        self.assertEqual(
            {"packageRelease", "packageReleaseBundle", "packageReleaseUniversalApk", "signReleaseBundle"},
            tasks,
        )
        self.assertIn("tasks.matching { it.name in releaseSigningTasks }.configureEach {", self.block)
        self.assertIn("doFirst {", self.block)

    def test_gradle_reads_the_shared_deny_list_and_verifies_in_process(self) -> None:
        self.assertIn('rootProject.file("tools/blocked-upload-certificates.txt")', self.block)
        self.assertIn('keystoreProps.getProperty("uploadKeyRotationApproved")', self.block)
        self.assertIn('keystoreProps.getProperty("uploadKeyCertificateSha256")', self.block)
        self.assertIn("KeyStore.getInstance(signingStoreFile", self.block)
        self.assertIn("if (actualCertificate in blocked)", self.block)
        self.assertIn("if (actualCertificate != approvedCertificate)", self.block)
        # A doFirst never runs for an up-to-date task: the gate state must be a task input.
        self.assertIn('inputs.property("uploadKeyApprovalState"', self.block)
        self.assertIn('inputs.file(signingStoreFile).withPropertyName("uploadKeystore")', self.block)
        # Refusal text stays value-free.
        self.assertNotIn("$signingStorePassword", self.block)

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
        self.assertIn("|$injectedNames\"", self.block)


if __name__ == "__main__":
    unittest.main()
