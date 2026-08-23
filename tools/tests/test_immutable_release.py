from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "build_immutable_release.py"
SPEC = importlib.util.spec_from_file_location("build_immutable_release", SCRIPT)
assert SPEC and SPEC.loader
release = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release
SPEC.loader.exec_module(release)


class ImmutableReleaseBuildTest(unittest.TestCase):
    def fixture(self, root: Path) -> Path:
        tracked = root / "app/src/main/tracked.txt"
        tracked.parent.mkdir(parents=True)
        tracked.write_text("committed bytes\n", encoding="utf-8")
        (root / ".gitignore").write_text("app/build/\n", encoding="utf-8")
        (root / "gradlew").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        (root / "gradlew").chmod(0o755)
        subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "Snapshot Test"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.email", "snapshot@example.invalid"], cwd=root, check=True)
        subprocess.run(["git", "add", "."], cwd=root, check=True)
        subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)
        return tracked

    def test_post_identity_worktree_mutation_cannot_reach_packaging(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            tracked = self.fixture(root)
            output = Path(temp_dir) / "immutable-output"
            observed: dict[str, str] = {}

            def mutate_after_snapshot(live_root: Path, snapshot: Path) -> None:
                self.assertEqual(snapshot.joinpath("app/src/main/tracked.txt").read_text(), "committed bytes\n")
                live_root.joinpath("app/src/main/tracked.txt").write_text(
                    "post-gate changed bytes\n", encoding="utf-8"
                )

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                observed["command"] = " ".join(command)
                artifact = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(snapshot.joinpath("app/src/main/tracked.txt").read_bytes())
                lint = snapshot / "app/build/reports/lint-results-release.html"
                lint.parent.mkdir(parents=True)
                lint.write_text("immutable lint report\n", encoding="utf-8")
                return subprocess.CompletedProcess(command, 0, "", "")

            commit, tree = release.build_immutable_release(
                root,
                [":app:bundleRelease"],
                output,
                run=package,
                after_snapshot=mutate_after_snapshot,
            )

            self.assertEqual(tracked.read_text(), "post-gate changed bytes\n")
            self.assertEqual(
                output.joinpath("bundle/release/app-release.aab").read_text(),
                "committed bytes\n",
            )
            self.assertEqual(
                output.joinpath("logs/lint-results-release.html").read_text(),
                "immutable lint report\n",
            )
            self.assertIn(f"-PimmutableReleaseCommit={commit}", observed["command"])
            self.assertIn(f"-PimmutableReleaseTree={tree}", observed["command"])

    def test_snapshot_mutation_blocks_output_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = Path(temp_dir) / "immutable-output"

            def mutate_snapshot(_: Path, snapshot: Path) -> None:
                snapshot.joinpath("app/src/main/tracked.txt").write_text(
                    "changed snapshot bytes\n", encoding="utf-8"
                )

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                artifact = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(snapshot.joinpath("app/src/main/tracked.txt").read_bytes())
                return subprocess.CompletedProcess(command, 0, "", "")

            with self.assertRaisesRegex(RuntimeError, "snapshot changed during build"):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    output,
                    run=package,
                    after_snapshot=mutate_snapshot,
                )
            self.assertFalse(output.exists())

    def test_lint_only_build_publishes_documented_logs_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = Path(temp_dir) / "immutable-output"

            def lint(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                reports = snapshot / "app/build/reports"
                reports.mkdir(parents=True)
                (reports / "lint-results-release.txt").write_text("No issues found.\n", encoding="utf-8")
                (reports / "unrelated.html").write_text("mutable report\n", encoding="utf-8")
                return subprocess.CompletedProcess(command, 0, "", "")

            release.build_immutable_release(root, [":app:lintRelease"], output, run=lint)

            self.assertEqual(
                output.joinpath("logs/lint-results-release.txt").read_text(),
                "No issues found.\n",
            )
            self.assertFalse(output.joinpath("logs/unrelated.html").exists())

    def test_tracked_relative_and_absolute_symlinks_are_rejected(self) -> None:
        for absolute in (False, True):
            with self.subTest(absolute=absolute), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir) / "fixture"
                root.mkdir()
                target = root / "outside.txt"
                target.write_text("external bytes\n", encoding="utf-8")
                link = root / "app/src/main/packageable.txt"
                link.parent.mkdir(parents=True)
                link.symlink_to(target if absolute else Path("../../../outside.txt"))
                (root / ".gitignore").write_text("app/build/\n", encoding="utf-8")
                (root / "gradlew").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
                (root / "gradlew").chmod(0o755)
                subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
                subprocess.run(["git", "config", "user.name", "Snapshot Test"], cwd=root, check=True)
                subprocess.run(["git", "config", "user.email", "snapshot@example.invalid"], cwd=root, check=True)
                subprocess.run(["git", "add", "."], cwd=root, check=True)
                subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)

                with self.assertRaisesRegex(RuntimeError, "not a regular tracked file"):
                    release.build_immutable_release(
                        root,
                        [":app:bundleRelease"],
                        Path(temp_dir) / "output",
                        run=lambda command, cwd: subprocess.CompletedProcess(command, 0, "", ""),
                    )

    @unittest.skipUnless(hasattr(os, "mkfifo"), "FIFO fixture requires POSIX mkfifo")
    def test_snapshot_special_file_swap_is_rejected_without_blocking(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)

            def replace_with_fifo(_: Path, snapshot: Path) -> None:
                tracked = snapshot / "app/src/main/tracked.txt"
                tracked.unlink()
                os.mkfifo(tracked)

            with self.assertRaisesRegex(RuntimeError, "snapshot changed during build"):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    Path(temp_dir) / "output",
                    run=lambda command, cwd: subprocess.CompletedProcess(command, 0, "", ""),
                    after_snapshot=replace_with_fifo,
                )

    def test_snapshot_parent_symlink_swap_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)

            def replace_parent(_: Path, snapshot: Path) -> None:
                source = snapshot / "app/src"
                retained = snapshot / "app/src-retained"
                source.rename(retained)
                source.symlink_to(retained, target_is_directory=True)

            with self.assertRaisesRegex(RuntimeError, "snapshot changed during build"):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    Path(temp_dir) / "output",
                    run=lambda command, cwd: subprocess.CompletedProcess(command, 0, "", ""),
                    after_snapshot=replace_parent,
                )


if __name__ == "__main__":
    unittest.main()
