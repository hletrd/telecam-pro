from __future__ import annotations

import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "immutable_outputs.py"
SPEC = importlib.util.spec_from_file_location("immutable_outputs_test_subject", SOURCE)
assert SPEC and SPEC.loader
outputs = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = outputs
SPEC.loader.exec_module(outputs)


class FrozenOutputSetTest(unittest.TestCase):
    def test_symlink_special_and_unexpected_members_fail_closed(self) -> None:
        cases = ["symlink", "unexpected"]
        if hasattr(os, "mkfifo"):
            cases.append("special")
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir) / "outputs"
                root.mkdir()
                (root / "artifact.apk").write_bytes(b"apk")
                if case == "symlink":
                    (root / "alias.apk").symlink_to(root / "artifact.apk")
                elif case == "special":
                    os.mkfifo(root / "pipe")
                else:
                    (root / "unexpected.txt").write_text("unexpected\n", encoding="utf-8")

                with self.assertRaisesRegex(
                    RuntimeError,
                    "symlink|regular file or directory|unexpected",
                ):
                    outputs.FrozenOutputSet.capture_tree(
                        root,
                        allow_file=lambda relative: relative.as_posix() == "artifact.apk",
                        label="test",
                    )

                self.assertEqual((root / "artifact.apk").read_bytes(), b"apk")

    def test_export_uses_captured_bytes_and_exclusive_destinations(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            root = base / "outputs"
            root.mkdir()
            artifact = root / "artifact.apk"
            artifact.write_bytes(b"captured")
            frozen = outputs.FrozenOutputSet.capture_tree(
                root,
                allow_file=lambda relative: relative.as_posix() == "artifact.apk",
                label="test",
            )
            try:
                destination = base / "published"
                frozen.export_into(destination)
                self.assertEqual((destination / "artifact.apk").read_bytes(), b"captured")
                with self.assertRaises(FileExistsError):
                    frozen.export_into(destination)
            finally:
                frozen.close()


if __name__ == "__main__":
    unittest.main()
