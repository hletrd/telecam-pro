from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
PROJECT_JAVA_HOME = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")


def gradle_environment() -> dict[str, str]:
    configured = os.environ.get("JAVA_HOME")
    candidates = [Path(configured)] if configured else []
    candidates.append(PROJECT_JAVA_HOME)
    javac = shutil.which("javac")
    if javac:
        candidates.append(Path(javac).resolve().parent.parent)
    home = next(
        (candidate for candidate in candidates if (candidate / "bin/java").is_file()),
        None,
    )
    if home is None:
        raise AssertionError("JDK 21 is required for release-source gate tests")
    return {
        **os.environ,
        "JAVA_HOME": str(home),
        "PATH": str(home / "bin") + os.pathsep + os.environ.get("PATH", ""),
    }


def run(command: list[str], cwd: Path, *, check: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        env=gradle_environment(),
        capture_output=True,
        text=True,
        timeout=90,
        check=check,
    )


class ReleaseSourceGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name) / "fixture"
        cls.output = Path(cls.temp.name) / "provenance"
        (cls.root / "app/src/main").mkdir(parents=True)
        (cls.root / "app/src/release").mkdir(parents=True)
        (cls.root / "app/src/main/tracked.txt").write_text("tracked\n", encoding="utf-8")
        (cls.root / "app/src/release/.keep").write_text("release\n", encoding="utf-8")
        (cls.root / ".gitignore").write_text(
            "*.jks\n*.pem\n*.log\n*secret*\n*credential*\ntelecampro-upload-*\n"
            "app/build/\nreleases/\n",
            encoding="utf-8",
        )
        subprocess.run(["git", "init", "-b", "main"], cwd=cls.root, check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "Gate Test"], cwd=cls.root, check=True)
        subprocess.run(["git", "config", "user.email", "gate@example.invalid"], cwd=cls.root, check=True)
        subprocess.run(["git", "add", "."], cwd=cls.root, check=True)
        subprocess.run(["git", "commit", "-m", "fixture"], cwd=cls.root, check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp.cleanup()

    def gate(
        self,
        *,
        commit: str | None = None,
        tree: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        command = [
            "./gradlew",
            "--console=plain",
            ":app:verifyCleanReleaseGitFixture",
            f"-PreleaseGateFixtureRepo={self.root}",
            f"-PreleaseGateFixtureOutput={self.output}",
        ]
        if commit is not None:
            command.append(f"-PimmutableReleaseCommit={commit}")
        if tree is not None:
            command.append(f"-PimmutableReleaseTree={tree}")
        return run(command, REPO_ROOT)

    def assert_gate_fails(self, expected_path: str) -> None:
        result = self.gate()
        output = result.stdout + result.stderr
        self.assertNotEqual(result.returncode, 0, output)
        self.assertIn(expected_path, output)

    def gate_fixture(self, root: Path, output: Path) -> subprocess.CompletedProcess[str]:
        return run(
            [
                "./gradlew",
                "--console=plain",
                ":app:verifyCleanReleaseGitFixture",
                f"-PreleaseGateFixtureRepo={root}",
                f"-PreleaseGateFixtureOutput={output}",
            ],
            REPO_ROOT,
        )

    def test_clean_dirty_and_hidden_release_inputs(self) -> None:
        clean = self.gate()
        self.assertEqual(clean.returncode, 0, clean.stdout + clean.stderr)
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.root, check=True, capture_output=True, text=True
        ).stdout.strip()
        tree = subprocess.run(
            ["git", "rev-parse", "HEAD^{tree}"], cwd=self.root, check=True, capture_output=True, text=True
        ).stdout.strip()
        self.assertEqual(
            (self.output / "telecam-release-provenance/source.properties").read_text(encoding="ascii"),
            f"schema=1\ncommit={head}\ntree={tree}\n",
        )
        supplied = self.gate(commit=head, tree=tree)
        self.assertEqual(supplied.returncode, 0, supplied.stdout + supplied.stderr)
        spoofed = self.gate(commit="f" * 40, tree=tree)
        self.assertNotEqual(spoofed.returncode, 0, spoofed.stdout + spoofed.stderr)
        self.assertIn("does not match its supplied identity", spoofed.stdout + spoofed.stderr)

        stale = self.output / "ignored-stale-sibling.properties"
        stale.write_text("must never be packaged\n", encoding="utf-8")
        self.assert_gate_fails("Generated release provenance namespace is not exact")
        stale.unlink()
        recovered = self.gate()
        self.assertEqual(recovered.returncode, 0, recovered.stdout + recovered.stderr)

        tracked = self.root / "app/src/main/tracked.txt"
        tracked.write_text("dirty\n", encoding="utf-8")
        self.assert_gate_fails("app/src/main/tracked.txt")
        tracked.write_text("tracked\n", encoding="utf-8")

        tracked.write_text("staged\n", encoding="utf-8")
        subprocess.run(["git", "add", str(tracked.relative_to(self.root))], cwd=self.root, check=True)
        self.assert_gate_fails("app/src/main/tracked.txt")
        subprocess.run(["git", "restore", "--staged", str(tracked.relative_to(self.root))], cwd=self.root, check=True)
        tracked.write_text("tracked\n", encoding="utf-8")

        untracked = self.root / "app/src/main/untracked.txt"
        untracked.write_text("untracked\n", encoding="utf-8")
        self.assert_gate_fails("app/src/main/untracked.txt")
        untracked.unlink()

        ignored_asset = self.root / "app/src/main/assets/runtime.log"
        ignored_asset.parent.mkdir(parents=True, exist_ok=True)
        ignored_asset.write_text("ignored\n", encoding="utf-8")
        self.assert_gate_fails("app/src/main/assets/runtime.log")
        ignored_asset.unlink()

        ignored_release = self.root / "app/src/release/res/raw/upload-key.jks"
        ignored_release.parent.mkdir(parents=True, exist_ok=True)
        ignored_release.write_text("ignored\n", encoding="utf-8")
        self.assert_gate_fails("app/src/release/res/raw/upload-key.jks")
        ignored_release.unlink()

        # Expected signing material and generated outputs remain outside the protected source roots.
        (self.root / "upload-key.jks").write_text("allowed\n", encoding="utf-8")
        (self.root / "app/build").mkdir(parents=True, exist_ok=True)
        (self.root / "app/build/runtime.log").write_text("allowed\n", encoding="utf-8")
        (self.root / "releases").mkdir(parents=True, exist_ok=True)
        (self.root / "releases/runtime.log").write_text("allowed\n", encoding="utf-8")
        allowed = self.gate()
        self.assertEqual(allowed.returncode, 0, allowed.stdout + allowed.stderr)

    def test_release_task_order_and_debug_isolation(self) -> None:
        release = run(
            [
                "./gradlew",
                "--console=plain",
                "--dry-run",
                ":app:compileReleaseKotlin",
                ":app:lintRelease",
                ":app:packageReleaseResources",
                ":app:packageRelease",
                ":app:assembleRelease",
                ":app:bundleRelease",
            ],
            REPO_ROOT,
        )
        self.assertEqual(release.returncode, 0, release.stdout + release.stderr)
        tasks = [line.split()[0] for line in release.stdout.splitlines() if line.startswith(":app:")]
        self.assertEqual(tasks.count(":app:verifyCleanReleaseGit"), 1, tasks)
        gate_index = tasks.index(":app:verifyCleanReleaseGit")
        prebuild_index = tasks.index(":app:preReleaseBuild")
        self.assertLess(gate_index, prebuild_index)
        for target in (
            ":app:compileReleaseKotlin",
            ":app:lintRelease",
            ":app:packageReleaseResources",
            ":app:packageRelease",
            ":app:assembleRelease",
            ":app:bundleRelease",
        ):
            self.assertLess(gate_index, tasks.index(target), target)

        debug = run(
            [
                "./gradlew",
                "--console=plain",
                "--dry-run",
                ":app:compileDebugKotlin",
                ":app:lintDebug",
                ":app:assembleDebug",
            ],
            REPO_ROOT,
        )
        self.assertEqual(debug.returncode, 0, debug.stdout + debug.stderr)
        self.assertNotIn(":app:verifyCleanReleaseGit", debug.stdout)

    def test_direct_release_gate_requires_immutable_snapshot(self) -> None:
        result = run(
            ["./gradlew", "--console=plain", ":app:verifyCleanReleaseGit"],
            REPO_ROOT,
        )
        output = result.stdout + result.stderr
        self.assertNotEqual(result.returncode, 0, output)
        self.assertIn("tools/build_immutable_release.py", output)

    def test_gradle_guard_rejects_relative_and_absolute_tracked_symlinks(self) -> None:
        for absolute in (False, True):
            with self.subTest(absolute=absolute), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir) / "fixture"
                (root / "app/src/main").mkdir(parents=True)
                (root / "app/src/release").mkdir(parents=True)
                target = root / "outside.txt"
                target.write_text("external bytes\n", encoding="utf-8")
                link = root / "app/src/main/packageable.txt"
                link.symlink_to(target if absolute else Path("../../../outside.txt"))
                subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
                subprocess.run(["git", "config", "user.name", "Gate Test"], cwd=root, check=True)
                subprocess.run(["git", "config", "user.email", "gate@example.invalid"], cwd=root, check=True)
                subprocess.run(["git", "add", "."], cwd=root, check=True)
                subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)

                result = self.gate_fixture(root, Path(temp_dir) / "output")
                combined = result.stdout + result.stderr

                self.assertNotEqual(0, result.returncode, combined)
                self.assertIn("not a regular tracked file", combined)
                self.assertIn("app/src/main/packageable.txt", combined)

    @unittest.skipUnless(hasattr(os, "mkfifo"), "FIFO fixture requires POSIX mkfifo")
    def test_gradle_guard_rejects_special_file_at_tracked_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            tracked = root / "app/src/main/packageable.txt"
            tracked.parent.mkdir(parents=True)
            (root / "app/src/release").mkdir(parents=True)
            tracked.write_text("indexed bytes\n", encoding="utf-8")
            subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Gate Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "gate@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)
            tracked.unlink()
            os.mkfifo(tracked)

            result = self.gate_fixture(root, Path(temp_dir) / "output")
            combined = result.stdout + result.stderr

            self.assertNotEqual(0, result.returncode, combined)
            self.assertIn("not a no-follow regular path", combined)
            self.assertIn("app/src/main/packageable.txt", combined)


if __name__ == "__main__":
    unittest.main()
