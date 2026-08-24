from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path


TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

from android_sdk import BUILD_TOOLS, COMPILE_SDK, android_sdk_environment  # noqa: E402


def complete_sdk(path: Path) -> Path:
    platform = path / f"platforms/android-{COMPILE_SDK}"
    platform.mkdir(parents=True)
    (platform / "android.jar").write_bytes(b"fixture")
    (path / f"build-tools/{BUILD_TOOLS}").mkdir(parents=True)
    return path.resolve()


class AndroidSdkAuthorityTest(unittest.TestCase):
    def test_environment_authority_aligns_both_gradle_variables(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            sdk = complete_sdk(root / "sdk")

            resolved = android_sdk_environment(
                root,
                environment={"ANDROID_HOME": str(sdk)},
                home=root / "unused-home",
            )

            self.assertEqual({"ANDROID_HOME": str(sdk), "ANDROID_SDK_ROOT": str(sdk)}, resolved)

    def test_local_properties_is_the_first_gradle_authority(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            local_sdk = complete_sdk(root / "local sdk")
            environment_sdk = complete_sdk(root / "environment-sdk")
            (root / "local.properties").write_text(
                f"sdk.dir={str(local_sdk).replace(' ', r'\ ')}\n",
                encoding="utf-8",
            )

            resolved = android_sdk_environment(
                root,
                environment={"ANDROID_HOME": str(environment_sdk)},
                home=root / "unused-home",
            )

            self.assertEqual(str(local_sdk), resolved["ANDROID_HOME"])

    def test_conventional_macos_path_is_used_only_without_explicit_authority(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            home = root / "home"
            sdk = complete_sdk(home / "Library/Android/sdk")

            resolved = android_sdk_environment(root, environment={}, home=home)

            self.assertEqual(str(sdk), resolved["ANDROID_SDK_ROOT"])

    def test_missing_sdk_reports_every_supported_remediation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            with self.assertRaisesRegex(RuntimeError, "sdk.dir=<absolute-sdk-path>") as raised:
                android_sdk_environment(root, environment={}, home=root / "empty-home")

            message = str(raised.exception)
            self.assertIn("export ANDROID_HOME", message)
            self.assertIn(f"SDK Platform {COMPILE_SDK}", message)
            self.assertIn(f"Build Tools {BUILD_TOOLS}", message)

    def test_conflicting_environment_authorities_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            with self.assertRaisesRegex(RuntimeError, "environment variables disagree"):
                android_sdk_environment(
                    root,
                    environment={
                        "ANDROID_HOME": str(root / "one"),
                        "ANDROID_SDK_ROOT": str(root / "two"),
                    },
                    home=root / "empty-home",
                )


if __name__ == "__main__":
    unittest.main()
