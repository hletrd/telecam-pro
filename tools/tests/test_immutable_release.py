from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


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
        (root / ".gitignore").write_text(
            "app/build/\nlocal.properties\nkeystore.properties\nrelease-key.jks\n",
            encoding="utf-8",
        )
        (root / "gradlew").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        (root / "gradlew").chmod(0o755)
        subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "Snapshot Test"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.email", "snapshot@example.invalid"], cwd=root, check=True)
        subprocess.run(["git", "add", "."], cwd=root, check=True)
        subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)
        return tracked

    def signing_fixture(self, root: Path, properties: bytes) -> None:
        (root / "keystore.properties").write_bytes(properties)
        (root / "release-key.jks").write_bytes(b"key-A")

    def test_output_must_live_in_wrapper_only_immutable_namespace(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)

            with self.assertRaisesRegex(RuntimeError, "must be one unique child"):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    Path(temp_dir) / "ordinary-output",
                    run=lambda command, cwd: subprocess.CompletedProcess(command, 0, "", ""),
                )

    def test_post_identity_worktree_mutation_cannot_reach_packaging(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            tracked = self.fixture(root)
            output = root / "app/build/immutable-release/test-output"
            observed: dict[str, str] = {}

            def mutate_after_snapshot(live_root: Path, snapshot: Path) -> None:
                self.assertEqual(snapshot.joinpath("app/src/main/tracked.txt").read_text(), "committed bytes\n")
                live_root.joinpath("app/src/main/tracked.txt").write_text(
                    "post-gate changed bytes\n", encoding="utf-8"
                )

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                observed["command"] = " ".join(command)
                self.assertFalse(any(argument.startswith("-PimmutableRelease") for argument in command))
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
            self.assertNotIn("-PimmutableRelease", observed["command"])
            evidence = json.loads(output.joinpath(release.RELEASE_EVIDENCE_NAME).read_text())
            self.assertEqual(
                {
                    "boundary": "sealed-export-frozen-outputs-v1",
                    "commit": commit,
                    "schema": 1,
                    "tree": tree,
                },
                {key: evidence[key] for key in ("boundary", "commit", "schema", "tree")},
            )
            self.assertEqual(
                [
                    "bundle/release/app-release.aab",
                    "logs/lint-results-release.html",
                ],
                [entry["path"] for entry in evidence["outputs"]],
            )
            self.assertEqual(
                {
                    entry["path"]: entry["sha256"]
                    for entry in evidence["outputs"]
                },
                {
                    "bundle/release/app-release.aab": hashlib.sha256(
                        b"committed bytes\n"
                    ).hexdigest(),
                    "logs/lint-results-release.html": hashlib.sha256(
                        b"immutable lint report\n"
                    ).hexdigest(),
                },
            )

    def test_snapshot_mutation_blocks_output_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = root / "app/build/immutable-release/test-output"

            def mutate_snapshot(_: Path, snapshot: Path) -> None:
                target = snapshot.joinpath("app/src/main/tracked.txt")
                target.chmod(0o644)
                target.write_text(
                    "changed snapshot bytes\n", encoding="utf-8"
                )

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                artifact = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(snapshot.joinpath("app/src/main/tracked.txt").read_bytes())
                return subprocess.CompletedProcess(command, 0, "", "")

            with self.assertRaisesRegex(RuntimeError, "immutable release source owner changed"):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    output,
                    run=package,
                    after_snapshot=mutate_snapshot,
                )
            self.assertFalse(output.exists())

    def test_transient_snapshot_mutation_blocks_b_derived_output_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = root / "app/build/immutable-release/test-output"

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                source = snapshot / "app/src/main/tracked.txt"
                source.chmod(0o644)
                source.write_text("transient B bytes\n", encoding="utf-8")
                artifact = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(source.read_bytes())
                source.write_text("committed bytes\n", encoding="utf-8")
                source.chmod(0o444)
                return subprocess.CompletedProcess(command, 0, "", "")

            with self.assertRaisesRegex(
                RuntimeError,
                "sealed immutable release source owner changed during compilation",
            ):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    output,
                    run=package,
                )
            self.assertFalse(output.exists())

    def test_release_local_inputs_join_the_permanent_mutation_seal(self) -> None:
        for relative in ("local.properties", "keystore.properties", "release-key.jks"):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir) / "fixture"
                root.mkdir()
                self.fixture(root)
                (root / "local.properties").write_text("sdk.dir=/safe/sdk\n", encoding="utf-8")
                (root / "keystore.properties").write_text(
                    "storeFile=release-key.jks\nstorePassword=secret-A\n",
                    encoding="utf-8",
                )
                (root / "release-key.jks").write_bytes(b"key-A")
                output = root / "app/build/immutable-release/test-output"

                def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                    target = snapshot / relative
                    target.chmod((target.stat().st_mode & 0o777) | 0o200)
                    target.write_bytes(b"secret-B")
                    artifact = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                    artifact.parent.mkdir(parents=True)
                    artifact.write_bytes(b"artifact")
                    return subprocess.CompletedProcess(command, 0, "", "")

                with self.assertRaisesRegex(
                    RuntimeError,
                    "sealed immutable release source owner changed",
                ) as raised:
                    release.build_immutable_release(
                        root,
                        [":app:bundleRelease"],
                        output,
                        run=package,
                    )
                self.assertNotIn("secret-A", str(raised.exception))
                self.assertNotIn("secret-B", str(raised.exception))
                self.assertFalse(output.exists())

    def test_release_local_inputs_join_the_transient_mutation_seal(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            (root / "local.properties").write_text("sdk.dir=/safe/sdk\n", encoding="utf-8")
            output = root / "app/build/immutable-release/test-output"

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                target = snapshot / "local.properties"
                sealed_mode = target.stat().st_mode & 0o777
                original = target.read_bytes()
                target.chmod(sealed_mode | 0o200)
                target.write_bytes(b"sdk.dir=/hostile/sdk\n")
                target.write_bytes(original)
                target.chmod(sealed_mode)
                artifact = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(b"artifact")
                return subprocess.CompletedProcess(command, 0, "", "")

            with self.assertRaisesRegex(
                RuntimeError,
                "sealed immutable release source owner changed",
            ):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    output,
                    run=package,
                )
            self.assertFalse(output.exists())

    def test_java_properties_store_file_syntax_resolves_one_exact_path(self) -> None:
        cases = {
            "equals": b"storeFile=release-key.jks\n",
            "colon": b"storeFile: release-key.jks\n",
            "whitespace": b"storeFile release-key.jks\n",
            "escaped-key": b"storeF\\u0069le=release-key.jks\n",
            "escaped-value": b"storeFile=release\\-key.jks\n",
            "continuation": b"storeFile=release-\\\n  key.jks\n",
            "trimmed-like-gradle": b"storeFile=  release-key.jks  \n",
        }
        for label, payload in cases.items():
            with self.subTest(label=label):
                self.assertEqual("release-key.jks", release.release_store_file(payload))

    def test_ambient_store_file_never_overrides_frozen_properties(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            self.signing_fixture(root, b"storeFile: release-key.jks\n")
            output = root / "app/build/immutable-release/test-output"
            commands: list[list[str]] = []

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                commands.append(command)
                self.assertEqual(b"key-A", snapshot.joinpath("release-key.jks").read_bytes())
                artifact = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(b"artifact")
                return subprocess.CompletedProcess(command, 0, "", "")

            with patch.dict(
                os.environ,
                {release.STORE_FILE_ENVIRONMENT: "/outside/ambient-key.jks"},
            ):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    output,
                    run=package,
                )

            self.assertEqual(1, len(commands))
            self.assertFalse(any(argument.startswith("-PimmutableRelease") for argument in commands[0]))
            self.assertNotIn("/outside/ambient-key.jks", " ".join(commands[0]))

    def test_default_runner_clears_ambient_store_file_only(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir, patch.dict(
            os.environ,
            {
                release.STORE_FILE_ENVIRONMENT: "/outside/ambient-key.jks",
                "TELECAMPRO_KEY_ALIAS": "alias-value",
            },
        ):
            result = release.run_checked(
                [
                    "sh",
                    "-c",
                    'test -z "$TELECAMPRO_STORE_FILE" && test "$TELECAMPRO_KEY_ALIAS" = alias-value',
                ],
                Path(temp_dir),
            )
            self.assertEqual(0, result.returncode)

    def test_environment_only_store_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            (root / "keystore.properties").write_text(
                "keyAlias=telecampro\nstorePassword=secret-A\n",
                encoding="utf-8",
            )
            outside = Path(temp_dir) / "outside.jks"
            outside.write_bytes(b"key-B")

            with patch.dict(os.environ, {release.STORE_FILE_ENVIRONMENT: str(outside)}):
                with self.assertRaisesRegex(RuntimeError, "exactly one storeFile") as raised:
                    release.build_immutable_release(
                        root,
                        [":app:bundleRelease"],
                        root / "app/build/immutable-release/test-output",
                        run=lambda command, cwd: subprocess.CompletedProcess(command, 0, "", ""),
                    )
            self.assertNotIn("secret-A", str(raised.exception))
            self.assertNotIn(str(outside), str(raised.exception))

    def test_ambiguous_or_non_relative_store_file_is_rejected(self) -> None:
        cases = {
            "duplicate": b"storeFile=release-key.jks\nstoreFile=other.jks\n",
            "absolute": b"storeFile=/outside/release-key.jks\n",
            "windows-absolute": b"storeFile=C\\:\\\\outside\\\\release-key.jks\n",
            "parent": b"storeFile=../release-key.jks\n",
            "dot": b"storeFile=./release-key.jks\n",
            "empty-component": b"storeFile=keys//release-key.jks\n",
            "missing": b"keyAlias=telecampro\n",
        }
        for label, payload in cases.items():
            with self.subTest(label=label):
                with self.assertRaisesRegex(RuntimeError, "storeFile"):
                    release.release_store_file(payload)

    def test_symlink_store_file_is_rejected_without_copying_target(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            (root / "keystore.properties").write_text(
                "storeFile=release-key.jks\n",
                encoding="utf-8",
            )
            target = Path(temp_dir) / "outside.jks"
            target.write_bytes(b"outside-key")
            (root / "release-key.jks").symlink_to(target)

            with self.assertRaisesRegex(RuntimeError, "safely read release local input"):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    root / "app/build/immutable-release/test-output",
                    run=lambda command, cwd: subprocess.CompletedProcess(command, 0, "", ""),
                )

    def test_transient_keystore_mutation_is_detected_after_restore(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            self.signing_fixture(root, b"storeFile=release-key.jks\n")
            output = root / "app/build/immutable-release/test-output"

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                target = snapshot / "release-key.jks"
                sealed_mode = target.stat().st_mode & 0o777
                target.chmod(sealed_mode | 0o200)
                target.write_bytes(b"key-B")
                target.write_bytes(b"key-A")
                target.chmod(sealed_mode)
                artifact = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(b"artifact")
                return subprocess.CompletedProcess(command, 0, "", "")

            with self.assertRaisesRegex(
                RuntimeError,
                "sealed immutable release source owner changed",
            ):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    output,
                    run=package,
                )
            self.assertFalse(output.exists())

    def test_permanent_release_output_mutation_blocks_complete_set_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = root / "app/build/immutable-release/test-output"

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                apk = snapshot / "app/build/outputs/apk/release/app-release.apk"
                aab = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                apk.parent.mkdir(parents=True)
                aab.parent.mkdir(parents=True)
                apk.write_bytes(b"apk-A")
                aab.write_bytes(b"aab-A")
                return subprocess.CompletedProcess(command, 0, "", "")

            def mutate_output(snapshot: Path) -> None:
                apk = snapshot / "app/build/outputs/apk/release/app-release.apk"
                apk.chmod(0o600)
                apk.write_bytes(b"apk-B")

            with self.assertRaisesRegex(RuntimeError, "generated-output owner changed"):
                release.build_immutable_release(
                    root,
                    [":app:assembleRelease", ":app:bundleRelease"],
                    output,
                    run=package,
                    after_outputs_frozen=mutate_output,
                )
            self.assertFalse(output.exists())

    def test_transient_release_output_mutation_blocks_complete_set_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = root / "app/build/immutable-release/test-output"

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                apk = snapshot / "app/build/outputs/apk/release/app-release.apk"
                aab = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                apk.parent.mkdir(parents=True)
                aab.parent.mkdir(parents=True)
                apk.write_bytes(b"apk-A")
                aab.write_bytes(b"aab-A")
                return subprocess.CompletedProcess(command, 0, "", "")

            def mutate_output(snapshot: Path) -> None:
                aab = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                sealed_mode = aab.stat().st_mode & 0o777
                aab.chmod(sealed_mode | 0o200)
                aab.write_bytes(b"aab-B")
                aab.write_bytes(b"aab-A")
                aab.chmod(sealed_mode)

            with self.assertRaisesRegex(RuntimeError, "generated-output owner changed"):
                release.build_immutable_release(
                    root,
                    [":app:assembleRelease", ":app:bundleRelease"],
                    output,
                    run=package,
                    after_outputs_frozen=mutate_output,
                )
            self.assertFalse(output.exists())

    def test_lint_only_build_publishes_documented_logs_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = root / "app/build/immutable-release/test-output"

            def lint(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                reports = snapshot / "app/build/reports"
                reports.mkdir(parents=True)
                (reports / "lint-results-release.txt").write_text("No issues found.\n", encoding="utf-8")
                resources = reports / "resources_config_map_file/release/resources.cfg"
                resources.parent.mkdir(parents=True)
                resources.write_text("stable release resources\n", encoding="utf-8")
                return subprocess.CompletedProcess(command, 0, "", "")

            release.build_immutable_release(root, [":app:lintRelease"], output, run=lint)

            self.assertEqual(
                output.joinpath("logs/lint-results-release.txt").read_text(),
                "No issues found.\n",
            )
            self.assertEqual(
                output.joinpath("logs/resources_config_map_file/release/resources.cfg").read_text(),
                "stable release resources\n",
            )

    def test_unexpected_release_report_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = root / "app/build/immutable-release/test-output"

            def lint(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                reports = snapshot / "app/build/reports"
                reports.mkdir(parents=True)
                (reports / "lint-results-release.txt").write_text("No issues found.\n", encoding="utf-8")
                (reports / "unrelated.html").write_text("unexpected\n", encoding="utf-8")
                return subprocess.CompletedProcess(command, 0, "", "")

            with self.assertRaisesRegex(RuntimeError, "unexpected immutable release report output"):
                release.build_immutable_release(root, [":app:lintRelease"], output, run=lint)
            self.assertFalse(output.exists())

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
                        root / "app/build/immutable-release/test-output",
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
                tracked.parent.chmod(0o755)
                tracked.unlink()
                os.mkfifo(tracked)

            with self.assertRaisesRegex(RuntimeError, "immutable release source owner changed"):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    root / "app/build/immutable-release/test-output",
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

            with self.assertRaisesRegex(RuntimeError, "immutable release source owner changed"):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    root / "app/build/immutable-release/test-output",
                    run=lambda command, cwd: subprocess.CompletedProcess(command, 0, "", ""),
                    after_snapshot=replace_parent,
                )


if __name__ == "__main__":
    unittest.main()
