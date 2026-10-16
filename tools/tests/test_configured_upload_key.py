"""Resolution of the CONFIGURED upload key, shared by both release wrappers and the checker.

Owner decision 2026-10-13: the configured keystore signs as-is. These tests pin that the helpers only
RESOLVE it (store file, alias, certificate digest) and never refuse it for policy reasons.
"""

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
SPEC = importlib.util.spec_from_file_location("build_immutable_release_key", SCRIPT)
assert SPEC and SPEC.loader
release = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release
SPEC.loader.exec_module(release)

CERTIFICATE = b"configured-public-certificate-der"
FINGERPRINT = hashlib.sha256(CERTIFICATE).hexdigest()
FILE_PASSWORD = "file-store-password"
ENV_PASSWORD = "env-store-password"


def write_properties(
    root: Path,
    *,
    password: str | None = FILE_PASSWORD,
    alias: str | None = "telecampro",
) -> None:
    (root / "release-key.jks").write_bytes(b"not-a-real-keystore")
    lines = ["storeFile=release-key.jks"]
    if alias is not None:
        lines.append(f"keyAlias={alias}")
    if password is not None:
        lines.append(f"storePassword={password}")
    (root / "keystore.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")


class Recorder:
    def __init__(self, certificate: bytes = CERTIFICATE, returncode: int = 0) -> None:
        self.calls: list[tuple[list[str], dict[str, str]]] = []
        self.certificate = certificate
        self.returncode = returncode

    def __call__(self, command, **kwargs):
        self.calls.append((list(command), dict(kwargs["env"])))
        return subprocess.CompletedProcess(command, self.returncode, stdout=self.certificate)


class ConfiguredUploadKeyTest(unittest.TestCase):
    def environment(self) -> dict[str, str]:
        return {"PATH": os.environ.get("PATH", ""), "JAVA_HOME": ""}

    def test_configured_certificate_uses_the_password_gradle_will_use_via_env_only(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root)
            run = Recorder()
            environment = {**self.environment(), release.STORE_PASSWORD_ENV: ENV_PASSWORD}
            self.assertEqual(
                FINGERPRINT, release.configured_upload_certificate_sha256(root, environment, run),
            )
            self.assertEqual(1, len(run.calls))
            command, child_environment = run.calls[0]
            self.assertIn("-exportcert", command)
            self.assertIn("-storepass:env", command)
            self.assertNotIn(FILE_PASSWORD, command)
            self.assertNotIn(ENV_PASSWORD, command)
            # Gradle's signingValue prefers keystore.properties over the environment.
            self.assertEqual(FILE_PASSWORD, child_environment[release.STORE_PASSWORD_ENV])

    def test_short_human_passwords_are_accepted(self) -> None:
        # Owner decision 2026-10-13: no password-strength floor. A short password must still resolve.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, password="abc")
            self.assertEqual(
                FINGERPRINT,
                release.configured_upload_certificate_sha256(root, self.environment(), Recorder()),
            )

    def test_keytool_receives_only_the_sealed_allowlist(self) -> None:
        # SR5-3 (AGG5-17): keytool holds the store password in-process; it must not inherit an
        # ambient JVM agent channel or unrelated variables.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root)
            run = Recorder()
            environment = {**self.environment(), "PYTHONPATH": "/x", "MY_UNRELATED_TOKEN": "value"}
            release.configured_upload_certificate_sha256(root, environment, run)
            _, child_environment = run.calls[0]
            self.assertNotIn("PYTHONPATH", child_environment)
            self.assertNotIn("MY_UNRELATED_TOKEN", child_environment)

            for name in release.JVM_LAUNCHER_INJECTION_ENVIRONMENT:
                with self.subTest(name=name):
                    run = Recorder()
                    with self.assertRaisesRegex(release.UploadKeyGateError, name) as caught:
                        release.configured_upload_certificate_sha256(
                            root, {**self.environment(), name: "-javaagent:/tmp/agent.jar"}, run,
                        )
                    self.assertEqual([], run.calls)
                    self.assertNotIn("/tmp/agent.jar", str(caught.exception))

    def test_environment_password_is_used_when_the_file_has_none(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, password=None)
            run = Recorder()
            environment = {**self.environment(), release.STORE_PASSWORD_ENV: ENV_PASSWORD}
            release.configured_upload_certificate_sha256(root, environment, run)
            self.assertEqual(ENV_PASSWORD, run.calls[0][1][release.STORE_PASSWORD_ENV])

            with self.assertRaisesRegex(release.UploadKeyGateError, "password is unavailable"):
                release.configured_upload_certificate_sha256(root, self.environment(), run)

    def test_missing_properties_cannot_resolve_a_certificate(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(release.UploadKeyGateError, "unavailable or unsafe"):
                release.configured_upload_certificate_sha256(Path(temp), self.environment(), Recorder())

    def test_keytool_failure_is_reported_without_the_password(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root)
            with self.assertRaisesRegex(release.UploadKeyGateError, "could not be read") as caught:
                release.configured_upload_certificate_sha256(
                    root, self.environment(), Recorder(returncode=1),
                )
            self.assertNotIn(FILE_PASSWORD, str(caught.exception))

    def test_prerequisite_alias_follows_gradle_precedence(self) -> None:
        # SEC3-5: with keyAlias=A in the file and TELECAMPRO_KEY_ALIAS=B in the environment, Gradle
        # signs with A, so the resolved alias (used directly by the scoped helper) must be A.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, alias="file-alias")
            conflicting = {release.KEY_ALIAS_ENV: "env-alias"}
            self.assertEqual(
                "file-alias", release.load_upload_key_prerequisite(root, conflicting).alias,
            )
            run = Recorder()
            release.export_upload_certificate_sha256(
                root, release.load_upload_key_prerequisite(root, conflicting),
                {**self.environment(), release.STORE_PASSWORD_ENV: FILE_PASSWORD}, run,
            )
            command = run.calls[0][0]
            self.assertEqual("file-alias", command[command.index("-alias") + 1])

            write_properties(root, alias=None)
            self.assertEqual("env-alias", release.load_upload_key_prerequisite(root, conflicting).alias)
            write_properties(root, alias="CHANGE_ME")
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

    def test_main_builds_without_any_upload_key_precheck(self) -> None:
        # Owner decision 2026-10-13: the wrapper hands the configured keystore to Gradle; it does not
        # open keytool or refuse the key before the sealed build.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_properties(root, password="abc")
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
                patch.object(release, "build_immutable_release", return_value=("a" * 40, "b" * 40)) as build,
                patch.object(release.subprocess, "run", side_effect=AssertionError("no keytool")),
                patch("sys.stdout"),
            ):
                self.assertEqual(0, release.main())
            build.assert_called_once()


if __name__ == "__main__":
    unittest.main()
