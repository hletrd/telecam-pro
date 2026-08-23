from __future__ import annotations

import importlib.util
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def load_builder():
    source = REPO_ROOT / "tools/build_immutable_debug.py"
    spec = importlib.util.spec_from_file_location("telecam_immutable_debug", source)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load immutable debug builder")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


builder = load_builder()


def initialize_fixture(root: Path) -> Path:
    values = {
        "app/src/main/source.txt": "source-A\n",
        "app/src/debug/debug.txt": "debug\n",
        "app/build.gradle.kts": "// fixture\n",
        "app/compose_stability.conf": "// fixture\n",
        "build.gradle.kts": "// fixture\n",
        "settings.gradle.kts": "// fixture\n",
        "gradle.properties": "fixture=true\n",
        "gradle/libs.versions.toml": "[versions]\n",
        "gradle/wrapper/gradle-wrapper.properties": "distributionUrl=fixture\n",
    }
    for relative, payload in values.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(payload, encoding="utf-8")
    gradlew = root / "gradlew"
    gradlew.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    gradlew.chmod(0o755)
    subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
    subprocess.run(["git", "config", "user.name", "Debug Builder Test"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.email", "debug@example.invalid"], cwd=root, check=True)
    subprocess.run(["git", "add", "."], cwd=root, check=True)
    subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)
    return root / "app/src/main/source.txt"


class ImmutableDebugBuildTest(unittest.TestCase):
    def test_original_mutation_after_snapshot_cannot_reach_compilation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            source = initialize_fixture(root)
            output = Path(temp_dir) / "output"
            observed: dict[str, object] = {}

            def mutate_original(original: Path, snapshot: Path) -> None:
                self.assertEqual(original, root.resolve())
                self.assertEqual((snapshot / "app/src/main/source.txt").read_text(), "source-A\n")
                source.write_text("source-B\n", encoding="utf-8")

            def compile_snapshot(command, cwd):
                observed["command"] = list(command)
                observed["compiled"] = (cwd / "app/src/main/source.txt").read_text()
                apk = cwd / "app/build/outputs/apk/debug/app-debug.apk"
                apk.parent.mkdir(parents=True)
                apk.write_text(str(observed["compiled"]), encoding="utf-8")
                return subprocess.CompletedProcess(command, 0)

            _, apk = builder.build_immutable_debug(
                root,
                output,
                run=compile_snapshot,
                after_snapshot=mutate_original,
            )

            self.assertEqual(source.read_text(encoding="utf-8"), "source-B\n")
            self.assertEqual(observed["compiled"], "source-A\n")
            self.assertEqual(apk.read_text(encoding="utf-8"), "source-A\n")
            self.assertIn(
                "-PimmutableDebugSourceOwner=immutable-debug-worktree-v1",
                observed["command"],
            )

    def test_snapshot_rejects_symlinked_debug_input(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            source = initialize_fixture(root)
            outside = Path(temp_dir) / "outside.txt"
            outside.write_text("outside\n", encoding="utf-8")
            source.unlink()
            source.symlink_to(outside)

            with self.assertRaisesRegex(RuntimeError, "regular file"):
                builder.snapshot_debug_worktree(root, Path(temp_dir) / "snapshot")

    def test_gradle_generator_packages_the_evidence_owner_marker(self) -> None:
        environment = {**os.environ}
        java_home = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
        if java_home.is_dir():
            environment["JAVA_HOME"] = str(java_home)
            environment["PATH"] = str(java_home / "bin") + os.pathsep + environment.get("PATH", "")
        result = subprocess.run(
            [
                "./gradlew",
                "--console=plain",
                ":app:generateDebugSourceProvenance",
                "-PimmutableDebugSourceOwner=immutable-debug-worktree-v1",
            ],
            cwd=REPO_ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=120,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        manifest = (
            REPO_ROOT
            / "app/build/generated/debug-source-provenance/telecam-debug-provenance/source.manifest"
        ).read_text(encoding="utf-8")
        self.assertTrue(
            manifest.startswith(
                "schema=2\nsource_owner=immutable-debug-worktree-v1\ncommit=",
            ),
            manifest[:200],
        )


if __name__ == "__main__":
    unittest.main()
