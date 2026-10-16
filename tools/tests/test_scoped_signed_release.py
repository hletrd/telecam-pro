from __future__ import annotations

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

STORE = "store-secret"
KEY = "key-secret"


class ScopedSignedReleaseTest(unittest.TestCase):
    def test_credentials_require_exact_fields_without_echoing_values(self) -> None:
        self.assertEqual(
            {"storePassword": STORE, "keyPassword": KEY},
            scoped.parse_scoped_credentials(
                f"# encrypted backup payload\nstorePassword={STORE}\nkeyPassword={KEY}\n".encode(),
            ),
        )
        # Owner decision 2026-10-13: no password-strength floor, so a short value is accepted.
        self.assertEqual(
            {"storePassword": "123456", "keyPassword": "123456"},
            scoped.parse_scoped_credentials(b"storePassword=123456\nkeyPassword=123456\n"),
        )
        invalid = (
            b"",
            b"storePassword=\nkeyPassword=123456\n",
            b"storePassword=123456\n",
            b"storePassword=123456\nstorePassword=654321\nkeyPassword=123456\n",
            b"storePassword=123456\nkeyPassword=123456\nunknown=value\n",
        )
        for payload in invalid:
            with self.subTest(payload_length=len(payload)):
                with self.assertRaises(scoped.ScopedReleaseError) as caught:
                    scoped.parse_scoped_credentials(payload)
                self.assertNotIn("123456", str(caught.exception))

    def test_prerequisite_needs_only_store_file_and_alias(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "telecampro-upload.jks").write_bytes(b"not-a-real-keystore")
            (root / "keystore.properties").write_text(
                "storeFile=telecampro-upload.jks\n", encoding="utf-8",
            )
            with self.assertRaisesRegex(scoped.ScopedReleaseError, "prerequisite"):
                scoped.load_upload_key_prerequisite(root, {})

            (root / "keystore.properties").write_text(
                "storeFile=telecampro-upload.jks\nkeyAlias=telecampro\n", encoding="utf-8",
            )
            prerequisite = scoped.load_upload_key_prerequisite(root, {})
            self.assertEqual("telecampro", prerequisite.alias)
            self.assertEqual(root.resolve() / "telecampro-upload.jks", prerequisite.store_path)

    def test_secret_values_exist_only_in_child_environment_and_are_cleared_on_success(self) -> None:
        calls: list[tuple[list[str], dict[str, str]]] = []
        with tempfile.TemporaryDirectory() as temp_dir:
            root = self.fixture(Path(temp_dir))
            credentials = {"storePassword": STORE, "keyPassword": KEY}
            ambient = {
                "PATH": os.environ.get("PATH", ""),
                "JAVA_HOME": "",
                "PYTHONPATH": "/x",
                "MY_UNRELATED_TOKEN": "value",
                scoped.STORE_FILE_ENV: "/outside/ambient-key.jks",
            }

            def run(command, **kwargs):
                calls.append((list(command), dict(kwargs["env"])))
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
            self.assertNotIn(STORE, all_arguments)
            self.assertNotIn(KEY, all_arguments)
            # Only the inner wrapper runs; there is no keytool pre-gate.
            self.assertEqual(1, len(calls))
            # SR5-3 (AGG5-17): the inner wrapper is the evidence boundary; it runs isolated from
            # PYTHON* variables and site customization, with the sealed allowlist only.
            inner_command, inner_environment = calls[0]
            self.assertEqual([sys.executable, "-I", "-S"], inner_command[:3])
            self.assertEqual(STORE, inner_environment[scoped.STORE_PASSWORD_ENV])
            self.assertEqual(KEY, inner_environment[scoped.KEY_PASSWORD_ENV])
            self.assertEqual("telecampro", inner_environment[scoped.KEY_ALIAS_ENV])
            self.assertNotIn("PYTHONPATH", inner_environment)
            self.assertNotIn("MY_UNRELATED_TOKEN", inner_environment)
            self.assertNotIn(scoped.STORE_FILE_ENV, inner_environment)

    def test_ambient_jvm_launcher_options_refuse_before_the_build(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = self.fixture(Path(temp_dir))
            credentials = {"storePassword": STORE, "keyPassword": KEY}
            calls: list[list[str]] = []

            def run(command, **kwargs):
                calls.append(list(command))
                return subprocess.CompletedProcess(command, 0, stdout=b"")

            with self.assertRaisesRegex(scoped.ScopedReleaseError, "JAVA_TOOL_OPTIONS"):
                scoped.run_scoped_signed_release(
                    root=root,
                    tasks=[":app:bundleRelease"],
                    output=root / "out",
                    credentials=credentials,
                    base_environment={
                        "PATH": os.environ.get("PATH", ""),
                        "JAVA_TOOL_OPTIONS": "-javaagent:/tmp/agent.jar",
                    },
                    run=run,
                )
            self.assertEqual([], calls)
            self.assertEqual({}, credentials)

    def test_build_failure_clears_mutable_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = self.fixture(Path(temp_dir))
            credentials = {"storePassword": STORE, "keyPassword": KEY}
            calls = 0

            def run(command, **kwargs):
                nonlocal calls
                calls += 1
                return subprocess.CompletedProcess(command, 1, stdout=b"")

            with self.assertRaisesRegex(scoped.ScopedReleaseError, "build failed"):
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
    def fixture(root: Path) -> Path:
        (root / "tools").mkdir()
        (root / "tools" / "build_immutable_release.py").write_text("# fixture\n", encoding="utf-8")
        (root / "telecampro-upload.jks").write_bytes(b"not-a-real-keystore")
        (root / "keystore.properties").write_text(
            "storeFile=telecampro-upload.jks\nkeyAlias=telecampro\n",
            encoding="utf-8",
        )
        return root


if __name__ == "__main__":
    unittest.main()
