"""The upload-key approval gate shared by both release wrappers (SEC-02)."""

from __future__ import annotations

import hashlib
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "build_immutable_release.py"
SPEC = importlib.util.spec_from_file_location("build_immutable_release_gate", SCRIPT)
assert SPEC and SPEC.loader
release = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release
SPEC.loader.exec_module(release)
# The gate resolves the deny-list through this exact module object (tools/ is on sys.path).
policy = sys.modules["upload_key_policy"]

CERTIFICATE = b"approved-public-certificate-der"
FINGERPRINT = hashlib.sha256(CERTIFICATE).hexdigest()
FILE_PASSWORD = "File-store-password-Entropy-7!"
ENV_PASSWORD = "Env-store-password-Entropy-8!"
SIGNING_TASKS = (":app:lintRelease", ":app:assembleRelease", ":app:bundleRelease")


def write_properties(
    root: Path,
    *,
    approved: bool,
    password: str | None = FILE_PASSWORD,
    fingerprint: str = FINGERPRINT,
    key_password: str | None = None,
    alias: str | None = "telecampro",
) -> None:
    (root / "release-key.jks").write_bytes(b"not-a-real-keystore")
    lines = ["storeFile=release-key.jks"]
    if alias is not None:
        lines.append(f"keyAlias={alias}")
    if password is not None:
        lines.append(f"storePassword={password}")
    if key_password is not None:
        lines.append(f"keyPassword={key_password}")
    lines.append(f"uploadKeyRotationApproved={'true' if approved else 'false'}")
    lines.append(f"uploadKeyCertificateSha256={fingerprint}")
    (root / "keystore.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")


class Recorder:
    def __init__(self, certificate: bytes = CERTIFICATE, returncode: int = 0) -> None:
        self.calls: list[tuple[list[str], dict[str, str]]] = []
        self.certificate = certificate
        self.returncode = returncode

    def __call__(self, command, **kwargs):
        self.calls.append((list(command), dict(kwargs["env"])))
        return subprocess.CompletedProcess(command, self.returncode, stdout=self.certificate)


class UploadKeyGateTest(unittest.TestCase):
    def environment(self) -> dict[str, str]:
        return {"PATH": os.environ.get("PATH", ""), "JAVA_HOME": ""}

    def test_lint_only_tasks_need_no_signing_material(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run = Recorder()
            release.require_approved_upload_key(Path(temp), [":app:lintRelease"], {}, run)
            self.assertEqual([], run.calls)

    def test_every_non_lint_task_is_treated_as_signing_capable(self) -> None:
        for tasks in (
            [":app:bundleRelease"],
            [":app:assembleRelease"],
            ["bR"],
            ["build"],
            [":app:lintRelease", ":app:packageReleaseBundle"],
        ):
            with self.subTest(tasks=tasks):
                self.assertTrue(release.signing_capable(tasks))
        self.assertFalse(release.signing_capable([":app:lintRelease", "lintVitalRelease"]))

    def test_unapproved_key_refuses_before_keytool_or_build(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, approved=False)
            run = Recorder()
            with self.assertRaisesRegex(release.UploadKeyGateError, "upload key is blocked") as caught:
                release.require_approved_upload_key(root, SIGNING_TASKS, self.environment(), run)
            self.assertEqual([], run.calls)
            self.assertNotIn(FILE_PASSWORD, str(caught.exception))

    def test_missing_properties_refuses_signing_tasks(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(release.UploadKeyGateError, "unavailable or unsafe"):
                release.require_approved_upload_key(Path(temp), SIGNING_TASKS, {}, Recorder())

    def test_approved_key_verifies_the_password_gradle_will_use_via_env_only(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, approved=True)
            run = Recorder()
            environment = {**self.environment(), release.STORE_PASSWORD_ENV: ENV_PASSWORD}
            release.require_approved_upload_key(root, SIGNING_TASKS, environment, run)

            self.assertEqual(1, len(run.calls))
            command, child_environment = run.calls[0]
            self.assertIn("-exportcert", command)
            self.assertIn("-storepass:env", command)
            self.assertNotIn(FILE_PASSWORD, command)
            self.assertNotIn(ENV_PASSWORD, command)
            # Gradle's signingValue prefers keystore.properties over the environment.
            self.assertEqual(FILE_PASSWORD, child_environment[release.STORE_PASSWORD_ENV])
            self.assertEqual(ENV_PASSWORD, environment[release.STORE_PASSWORD_ENV])

    def test_environment_password_is_used_when_the_file_has_none(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, approved=True, password=None)
            run = Recorder()
            environment = {**self.environment(), release.STORE_PASSWORD_ENV: ENV_PASSWORD}
            release.require_approved_upload_key(root, SIGNING_TASKS, environment, run)
            self.assertEqual(ENV_PASSWORD, run.calls[0][1][release.STORE_PASSWORD_ENV])

            with self.assertRaisesRegex(release.UploadKeyGateError, "password is unavailable"):
                release.require_approved_upload_key(root, SIGNING_TASKS, self.environment(), run)

    def test_weak_effective_passwords_are_refused_before_keytool(self) -> None:
        # SEC3-2: approval plus a non-blocked fingerprint is not enough; a rotation to an equally
        # weak key must fail the documented wrapper too, not only the scoped helper.
        weak = "weak-store-pw"
        cases = {
            "store password from the file": (
                {"password": weak}, {}, "store password does not meet",
            ),
            "store password from the environment": (
                {"password": None}, {release.STORE_PASSWORD_ENV: weak}, "store password does not meet",
            ),
            "key password from the file": (
                {"key_password": weak}, {}, "key password does not meet",
            ),
            "key password from the environment": (
                {}, {release.KEY_PASSWORD_ENV: weak}, "key password does not meet",
            ),
        }
        for label, (properties, extra_environment, message) in cases.items():
            with self.subTest(label), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                write_properties(root, approved=True, **properties)
                run = Recorder()
                with self.assertRaisesRegex(release.UploadKeyGateError, message) as caught:
                    release.require_approved_upload_key(
                        root, SIGNING_TASKS, {**self.environment(), **extra_environment}, run,
                    )
                self.assertEqual([], run.calls)
                self.assertNotIn(weak, str(caught.exception))

    def test_strong_file_key_password_wins_over_a_weak_environment_one(self) -> None:
        # Gradle's signingValue: keystore.properties wins, so the env value is not what signs.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, approved=True, key_password="Key-password-with-Entropy-8!")
            run = Recorder()
            release.require_approved_upload_key(
                root, SIGNING_TASKS, {**self.environment(), release.KEY_PASSWORD_ENV: "weak"}, run,
            )
            self.assertEqual(1, len(run.calls))

    def test_prerequisite_alias_follows_gradle_precedence(self) -> None:
        # SEC3-5: with keyAlias=A in the file and TELECAMPRO_KEY_ALIAS=B in the environment, Gradle
        # signs with A, so the pre-check (used directly by the scoped helper) must verify A.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, approved=True, alias="file-alias")
            conflicting = {release.KEY_ALIAS_ENV: "env-alias"}
            self.assertEqual(
                "file-alias", release.load_upload_key_prerequisite(root, conflicting).alias,
            )
            run = Recorder()
            release.verify_upload_key_certificate(
                root, release.load_upload_key_prerequisite(root, conflicting),
                {**self.environment(), release.STORE_PASSWORD_ENV: FILE_PASSWORD}, run,
            )
            command = run.calls[0][0]
            self.assertEqual("file-alias", command[command.index("-alias") + 1])

            write_properties(root, approved=True, alias=None)
            self.assertEqual("env-alias", release.load_upload_key_prerequisite(root, conflicting).alias)
            write_properties(root, approved=True, alias="CHANGE_ME")
            self.assertEqual("env-alias", release.load_upload_key_prerequisite(root, conflicting).alias)
            with self.assertRaisesRegex(release.UploadKeyGateError, "keyAlias is missing or ambiguous"):
                release.load_upload_key_prerequisite(root, {})

            properties = root / "keystore.properties"
            properties.write_text(
                properties.read_text(encoding="utf-8") + "keyAlias=one\nkeyAlias=two\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(release.UploadKeyGateError, "keyAlias is missing or ambiguous"):
                release.load_upload_key_prerequisite(root, conflicting)

    def test_certificate_mismatch_or_keytool_failure_refuses(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, approved=True)
            with self.assertRaisesRegex(release.UploadKeyGateError, "does not match"):
                release.require_approved_upload_key(
                    root, SIGNING_TASKS, self.environment(), Recorder(certificate=b"blocked-key"),
                )
            with self.assertRaisesRegex(release.UploadKeyGateError, "verification failed") as caught:
                release.require_approved_upload_key(
                    root, SIGNING_TASKS, self.environment(), Recorder(returncode=1),
                )
            self.assertNotIn(FILE_PASSWORD, str(caught.exception))

    def test_blocked_fingerprint_is_refused_even_with_approval(self) -> None:
        # SEC2-2: approval is self-attested, so approving the blocked key's own PUBLIC fingerprint
        # must not pass. The refusal happens before keytool ever opens the keystore.
        blocked = sorted(policy.BLOCKED_UPLOAD_CERT_SHA256)[0]
        for spelling in (blocked, ":".join(blocked[i : i + 2].upper() for i in range(0, 64, 2))):
            with self.subTest(spelling=spelling), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                write_properties(root, approved=True, fingerprint=spelling)
                run = Recorder()
                with self.assertRaisesRegex(release.UploadKeyGateError, "blocked deny-list"):
                    release.require_approved_upload_key(root, SIGNING_TASKS, self.environment(), run)
                self.assertEqual([], run.calls)
                with self.assertRaisesRegex(release.UploadKeyGateError, "blocked deny-list"):
                    release.approved_upload_certificate_sha256(root)

    def test_keystore_exporting_a_blocked_certificate_is_refused(self) -> None:
        blocked_certificate = b"retired-upload-certificate-der"
        blocked = hashlib.sha256(blocked_certificate).hexdigest()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, approved=True)
            with (
                patch.object(policy, "BLOCKED_UPLOAD_CERT_SHA256", frozenset({blocked})),
                self.assertRaisesRegex(release.UploadKeyGateError, "exports a blocked certificate"),
            ):
                release.require_approved_upload_key(
                    root, SIGNING_TASKS, self.environment(), Recorder(certificate=blocked_certificate),
                )

    def test_approved_certificate_authority_is_shared_with_the_checker(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, approved=True)
            self.assertEqual(FINGERPRINT, release.approved_upload_certificate_sha256(root))
            write_properties(root, approved=False)
            with self.assertRaisesRegex(release.UploadKeyGateError, "upload key is blocked"):
                release.approved_upload_certificate_sha256(root)

    def test_main_refuses_before_any_build_when_the_gate_refuses(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, approved=False)
            argv = [
                "build_immutable_release.py",
                "--root",
                str(root),
                "--output",
                str(root / "app/build/immutable-release/out"),
                ":app:bundleRelease",
            ]
            with (
                patch.object(sys, "argv", argv),
                patch.object(release, "android_sdk_environment", return_value={}),
                patch.object(release, "build_immutable_release") as build,
                patch("sys.stderr") as stderr,
            ):
                self.assertEqual(1, release.main())
            build.assert_not_called()
            written = "".join(call.args[0] for call in stderr.write.call_args_list)
            self.assertIn("immutable release build refused", written)
            self.assertNotIn(FILE_PASSWORD, written)


if __name__ == "__main__":
    unittest.main()
