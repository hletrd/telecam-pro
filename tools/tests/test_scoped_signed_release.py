from __future__ import annotations

import hashlib
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "run_scoped_signed_release",
    ROOT / "tools" / "run_scoped_signed_release.py",
)
assert SPEC is not None and SPEC.loader is not None
scoped = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = scoped
SPEC.loader.exec_module(scoped)


class ScopedSignedReleaseTest(unittest.TestCase):
    def test_credentials_require_exact_strong_fields_without_echoing_values(self) -> None:
        store = "store-password-with-entropy"
        key = "key-password-with-entropy"
        self.assertEqual(
            {"storePassword": store, "keyPassword": key},
            scoped.parse_scoped_credentials(
                f"# encrypted backup payload\nstorePassword={store}\nkeyPassword={key}\n".encode(),
            ),
        )
        invalid = (
            b"",
            b"storePassword=123456\nkeyPassword=123456\n",
            b"storePassword=long-enough-password\n",
            b"storePassword=long-enough-password\nstorePassword=duplicate-value\nkeyPassword=also-long-enough\n",
            b"storePassword=long-enough-password\nkeyPassword=also-long-enough\nunknown=value\n",
        )
        for payload in invalid:
            with self.subTest(payload_length=len(payload)):
                with self.assertRaises(scoped.ScopedReleaseError) as caught:
                    scoped.parse_scoped_credentials(payload)
                self.assertNotIn("123456", str(caught.exception))

    def test_prerequisite_is_fail_closed_until_owner_approval_and_exact_certificate(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "telecampro-upload.jks").write_bytes(b"not-a-real-keystore")
            (root / "keystore.properties").write_text(
                "storeFile=telecampro-upload.jks\nkeyAlias=telecampro\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(scoped.ScopedReleaseError, "prerequisite"):
                scoped.load_upload_key_prerequisite(root, {})

            (root / "keystore.properties").write_text(
                "storeFile=telecampro-upload.jks\n"
                "keyAlias=telecampro\n"
                "uploadKeyRotationApproved=true\n"
                "uploadKeyCertificateSha256=" + "a" * 64 + "\n",
                encoding="utf-8",
            )
            prerequisite = scoped.load_upload_key_prerequisite(root, {})
            self.assertEqual("a" * 64, prerequisite.certificate_sha256)

    def test_secret_values_exist_only_in_child_environment_and_are_cleared_on_success(self) -> None:
        certificate = b"public-certificate-der"
        calls: list[tuple[list[str], dict[str, str]]] = []
        with tempfile.TemporaryDirectory() as temp_dir:
            root = self.fixture(Path(temp_dir), hashlib.sha256(certificate).hexdigest())
            credentials = {
                "storePassword": "store-password-with-entropy",
                "keyPassword": "key-password-with-entropy",
            }
            ambient = {"PATH": os.environ.get("PATH", ""), "JAVA_HOME": ""}

            def run(command, **kwargs):
                calls.append((list(command), dict(kwargs["env"])))
                if "-exportcert" in command:
                    return subprocess.CompletedProcess(command, 0, stdout=certificate)
                return subprocess.CompletedProcess(command, 0, stdout=b"")

            scoped.run_scoped_signed_release(
                root=root,
                tasks=[":app:bundleRelease"],
                output=root / "out",
                credentials=credentials,
                base_environment=ambient,
                run=run,
            )

            self.assertEqual({}, credentials)
            self.assertNotIn(scoped.STORE_PASSWORD_ENV, ambient)
            self.assertNotIn(scoped.KEY_PASSWORD_ENV, ambient)
            all_arguments = [argument for command, _ in calls for argument in command]
            self.assertNotIn("store-password-with-entropy", all_arguments)
            self.assertNotIn("key-password-with-entropy", all_arguments)
            self.assertIn("-storepass:env", calls[0][0])
            self.assertIn(scoped.STORE_PASSWORD_ENV, calls[0][0])
            self.assertEqual(2, len(calls))

    def test_keytool_failure_clears_mutable_credentials_and_never_runs_build(self) -> None:
        certificate = b"public-certificate-der"
        with tempfile.TemporaryDirectory() as temp_dir:
            root = self.fixture(Path(temp_dir), hashlib.sha256(certificate).hexdigest())
            credentials = {
                "storePassword": "store-password-with-entropy",
                "keyPassword": "key-password-with-entropy",
            }
            calls = 0

            def run(command, **kwargs):
                nonlocal calls
                calls += 1
                return subprocess.CompletedProcess(command, 1, stdout=b"")

            with self.assertRaisesRegex(scoped.ScopedReleaseError, "verification failed"):
                scoped.run_scoped_signed_release(
                    root=root,
                    tasks=[":app:bundleRelease"],
                    output=None,
                    credentials=credentials,
                    base_environment={"PATH": os.environ.get("PATH", "")},
                    run=run,
                )
            self.assertEqual({}, credentials)
            self.assertEqual(1, calls)

    @staticmethod
    def fixture(root: Path, fingerprint: str) -> Path:
        (root / "tools").mkdir()
        (root / "tools" / "build_immutable_release.py").write_text("# fixture\n", encoding="utf-8")
        (root / "telecampro-upload.jks").write_bytes(b"not-a-real-keystore")
        (root / "keystore.properties").write_text(
            "storeFile=telecampro-upload.jks\n"
            "keyAlias=telecampro\n"
            "uploadKeyRotationApproved=true\n"
            f"uploadKeyCertificateSha256={fingerprint}\n",
            encoding="utf-8",
        )
        return root


if __name__ == "__main__":
    unittest.main()
