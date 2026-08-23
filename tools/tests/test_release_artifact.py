from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
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
        payload = b"signed-aab-fixture"
        digest = hashlib.sha256(payload).hexdigest()
        (root / "app").mkdir()
        (root / "app/build.gradle.kts").write_text(
            'android { defaultConfig {\n    versionCode = 4\n    versionName = "1.0.2"\n} }\n'
        )
        (root / "releases").mkdir()
        aab = root / f"releases/telecam-pro-1.0.2-{commit[:7]}-{digest[:12]}.aab"
        aab.write_bytes(payload)
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
    def runner(commit: str, *, additional_signer: str | None = None):
        def run(command, cwd):
            del cwd
            if command[:3] == ["git", "rev-parse", "HEAD"]:
                return subprocess.CompletedProcess(command, 0, commit + "\n", "")
            if command[:2] == ["git", "status"]:
                return subprocess.CompletedProcess(command, 0, "", "")
            if command[0] == "jarsigner":
                if "-strict" in command:
                    return subprocess.CompletedProcess(command, 1, "", "self-signed certificate")
                return subprocess.CompletedProcess(command, 0, "jar verified\n", "")
            if command[0] == "keytool":
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
                manifest = '<manifest android:versionCode="4" android:versionName="1.0.2" />\n'
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

    def test_self_signed_upload_key_uses_non_strict_crypto_verification(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)

            self.assertEqual(
                [], release.check_release_identity(root, attestation, run=self.runner(commit))
            )

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
