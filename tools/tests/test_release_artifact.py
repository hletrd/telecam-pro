from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check_release_artifact.py"
SPEC = importlib.util.spec_from_file_location("check_release_artifact", SCRIPT)
assert SPEC and SPEC.loader
release = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release
SPEC.loader.exec_module(release)


class ReleaseArtifactIdentityTest(unittest.TestCase):
    def fixture(self, root: Path) -> tuple[Path, Path, str]:
        commit = "a" * 40
        (root / "app").mkdir()
        (root / "app/build.gradle.kts").write_text(
            'android { defaultConfig {\n'
            '    applicationId = "me.hletrd.telecampro"\n'
            '    minSdk = 33\n'
            '    targetSdk = 36\n'
            '    versionCode = 4\n'
            '    versionName = "1.0.2"\n'
            '} }\n'
        )
        (root / "releases").mkdir()
        provisional = root / "releases/provisional.aab"
        with zipfile.ZipFile(provisional, "w") as bundle:
            bundle.writestr(
                "base/root/META-INF/version-control-info.textproto",
                f'repositories {{\n  system: GIT\n  revision: "{commit}"\n}}\n',
            )
            bundle.writestr("base/manifest/AndroidManifest.xml", b"fixture")
        digest = release.sha256_file(provisional)
        aab = root / f"releases/telecam-pro-1.0.2-{commit[:7]}-{digest[:12]}.aab"
        provisional.rename(aab)
        attestation = root / "release-attestation.json"
        attestation.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "status": "upload-ready",
                    "git_commit": commit,
                    "version_code": 4,
                    "version_name": "1.0.2",
                    "aab_path": str(aab.relative_to(root)),
                    "aab_sha256": digest,
                    "signer_sha256": release.EXPECTED_UPLOAD_CERT_SHA256,
                },
                sort_keys=True,
            )
            + "\n"
        )
        attestation.with_name(attestation.name + ".sha256").write_text(
            f"{release.sha256_file(attestation)}  {attestation.name}\n"
        )
        return attestation, aab, commit

    @staticmethod
    def runner(
        commit: str,
        *,
        additional_signer: str | None = None,
        strict_returncode: int = 0,
    ):
        def run(command, cwd):
            del cwd
            if command[:3] == ["git", "rev-parse", "HEAD"]:
                return subprocess.CompletedProcess(command, 0, commit + "\n", "")
            if command[:2] == ["git", "status"]:
                return subprocess.CompletedProcess(command, 0, "", "")
            if command[0] == "jarsigner":
                return subprocess.CompletedProcess(command, strict_returncode, "jar verified\n", "")
            if command[0] == "keytool":
                if "-importcert" in command:
                    return subprocess.CompletedProcess(command, 0, "Certificate added\n", "")
                if "-rfc" in command:
                    return subprocess.CompletedProcess(
                        command,
                        0,
                        "-----BEGIN CERTIFICATE-----\nZmFrZQ==\n-----END CERTIFICATE-----\n",
                        "",
                    )
                fingerprint = ":".join(
                    release.EXPECTED_UPLOAD_CERT_SHA256[i : i + 2].upper()
                    for i in range(0, 64, 2)
                )
                output = f"SHA256: {fingerprint}\n"
                if additional_signer is not None:
                    extra = ":".join(
                        additional_signer[i : i + 2].upper()
                        for i in range(0, 64, 2)
                    )
                    output += f"SHA256: {extra}\n"
                return subprocess.CompletedProcess(command, 0, output, "")
            if command[0] == "bundletool":
                if command[1] == "validate":
                    return subprocess.CompletedProcess(command, 0, "App Bundle validated\n", "")
                manifest = (
                    '<manifest package="me.hletrd.telecampro" android:versionCode="4" '
                    'android:versionName="1.0.2">\n'
                    '  <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="36" />\n'
                    '</manifest>\n'
                )
                return subprocess.CompletedProcess(command, 0, manifest, "")
            raise AssertionError(command)

        return run

    def test_clean_digest_qualified_identity_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)
            self.assertEqual(
                [], release.check_release_identity(root, attestation, run=self.runner(commit))
            )

    def test_mutable_output_and_changed_bytes_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, aab, commit = self.fixture(root)
            mutable = root / "app/build/outputs/bundle/release/app-release.aab"
            mutable.parent.mkdir(parents=True)
            mutable.write_bytes(aab.read_bytes() + b"changed")
            document = json.loads(attestation.read_text())
            document["aab_path"] = str(mutable.relative_to(root))
            attestation.write_text(json.dumps(document, sort_keys=True) + "\n")
            attestation.with_name(attestation.name + ".sha256").write_text(
                f"{release.sha256_file(attestation)}  {attestation.name}\n"
            )

            failures = release.check_release_identity(root, attestation, run=self.runner(commit))

            self.assertTrue(any("mutable app/build/outputs" in item for item in failures))
            self.assertTrue(any("AAB SHA-256" in item for item in failures))

    def test_attestation_sidecar_and_head_are_both_authoritative(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)
            attestation.write_text(attestation.read_text() + " ")

            failures = release.check_release_identity(root, attestation, run=self.runner("b" * 40))

            self.assertTrue(any("sidecar" in item for item in failures))
            self.assertTrue(any("HEAD" in item for item in failures))

    def test_pinned_upload_cert_makes_strict_verification_clean(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)

            self.assertEqual(
                [], release.check_release_identity(root, attestation, run=self.runner(commit))
            )

    def test_unsigned_entry_strict_warning_is_blocking(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)

            failures = release.check_release_identity(
                root,
                attestation,
                run=self.runner(commit, strict_returncode=16),
            )

            self.assertTrue(any("strict jarsigner" in item for item in failures))

    @unittest.skipUnless(os.environ.get("JAVA_HOME"), "JAVA_HOME with JDK tools required")
    def test_real_appended_unsigned_entry_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            jar = root / "signed.aab"
            store = root / "signer.p12"
            java_bin = Path(os.environ["JAVA_HOME"]) / "bin"
            keytool = java_bin / "keytool"
            jarsigner = java_bin / "jarsigner"
            self.assertTrue(keytool.is_file() and jarsigner.is_file())
            with zipfile.ZipFile(jar, "w") as bundle:
                bundle.writestr("base/manifest/AndroidManifest.xml", b"signed")
            subprocess.run(
                [
                    str(keytool), "-genkeypair", "-noprompt", "-alias", "upload",
                    "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", str(store),
                    "-storepass", "changeit", "-keypass", "changeit",
                    "-dname", "CN=Release Checker Test", "-validity", "2",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                [
                    str(jarsigner), "-keystore", str(store), "-storepass", "changeit",
                    "-keypass", "changeit", str(jar), "upload",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            with zipfile.ZipFile(jar, "a") as bundle:
                bundle.writestr("base/assets/unsigned-payload.bin", b"not authenticated")

            failure = release.strict_jar_verification_failure(root, jar, release.default_run)

            self.assertIsNotNone(failure)
            self.assertIn("strict jarsigner", failure)

    def test_packaged_source_commit_is_authoritative(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, aab, commit = self.fixture(root)
            with zipfile.ZipFile(aab, "a") as bundle:
                bundle.writestr(
                    "base/root/META-INF/version-control-info.textproto",
                    f'repositories {{ revision: "{"b" * 40}" }}\n',
                )

            failures = release.check_release_identity(root, attestation, run=self.runner(commit))

            self.assertTrue(any("packaged AGP source revision" in item for item in failures))

    def test_additional_signer_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)

            failures = release.check_release_identity(
                root,
                attestation,
                run=self.runner(commit, additional_signer="b" * 64),
            )

            self.assertTrue(any("signer certificate" in item for item in failures))


if __name__ == "__main__":
    unittest.main()
