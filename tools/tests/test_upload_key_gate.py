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

CERTIFICATE = b"approved-public-certificate-der"
FINGERPRINT = hashlib.sha256(CERTIFICATE).hexdigest()
FILE_PASSWORD = "File-store-password-Entropy-7!"
ENV_PASSWORD = "Env-store-password-Entropy-8!"
SIGNING_TASKS = (":app:lintRelease", ":app:assembleRelease", ":app:bundleRelease")


def write_properties(root: Path, *, approved: bool, password: str | None = FILE_PASSWORD) -> None:
    (root / "release-key.jks").write_bytes(b"not-a-real-keystore")
    lines = ["storeFile=release-key.jks", "keyAlias=telecampro"]
    if password is not None:
        lines.append(f"storePassword={password}")
    lines.append(f"uploadKeyRotationApproved={'true' if approved else 'false'}")
    lines.append(f"uploadKeyCertificateSha256={FINGERPRINT}")
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
