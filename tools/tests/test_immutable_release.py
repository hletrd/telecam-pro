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
sys.path.insert(0, str(Path(__file__).resolve().parent))
from fixture_git import init_fixture_repo  # noqa: E402


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
        init_fixture_repo(root)
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

    def test_output_accepts_one_non_empty_direct_child(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = root / "app/build/immutable-release/test-output"

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                artifact = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(b"artifact")
                return subprocess.CompletedProcess(command, 0, "", "")

            release.build_immutable_release(
                root,
                [":app:bundleRelease"],
                output,
                run=package,
            )

            self.assertTrue(output.joinpath("bundle/release/app-release.aab").is_file())
            self.assertTrue(output.joinpath(release.RELEASE_EVIDENCE_NAME).is_file())

    def test_output_rejects_grandchild_of_immutable_namespace(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = root / "app/build/immutable-release/nested/test-output"

            with self.assertRaisesRegex(RuntimeError, "must be one unique child"):
                release.build_immutable_release(
                    root,
                    [":app:bundleRelease"],
                    output,
                    run=lambda command, cwd: subprocess.CompletedProcess(command, 0, "", ""),
                )

            self.assertFalse(output.exists())

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
                    "schema": release.RELEASE_EVIDENCE_SCHEMA,
                    "source_authority": release.RELEASE_SOURCE_AUTHORITY,
                    "tree": tree,
                },
                {
                    key: evidence[key]
                    for key in (
                        "boundary",
                        "commit",
                        "schema",
                        "source_authority",
                        "tree",
                    )
                },
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
                    "storeFile=release-key.jks\nstorePassword=Synthetic-Seal-A-9qZ!\n",
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
                self.assertNotIn("Synthetic-Seal-A-9qZ!", str(raised.exception))
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

    def test_frozen_signing_properties_are_rechecked_against_the_secret_floor(self) -> None:
        # SEC4-2 / AGG4-38: the floor re-runs on the COPIED keystore.properties, before Gradle.
        # Synthetic values only.
        weak = "a" * 18 + "A1"
        strong = "Zk4!mR9#vT2$wQ8%nnnB"
        cases = (
            (f"storeFile=release-key.jks\nstorePassword={weak}\n", {}, "store password"),
            (
                f"storeFile=release-key.jks\nstorePassword={strong}\nkeyPassword={weak}\n",
                {},
                "key password",
            ),
            # No file value: the environment value is the effective one (Gradle's precedence).
            ("storeFile=release-key.jks\n", {"TELECAMPRO_STORE_PASSWORD": weak}, "store password"),
            # A strong keyPassword cannot hide a weak storePassword.
            (
                f"storeFile=release-key.jks\nstorePassword={weak}\nkeyPassword={strong}\n",
                {},
                "store password",
            ),
        )
        for properties, environment, refused in cases:
            with self.subTest(properties=properties, environment=sorted(environment)):
                with tempfile.TemporaryDirectory() as temp_dir, patch.dict(
                    os.environ,
                    {"TELECAMPRO_STORE_PASSWORD": "", "TELECAMPRO_KEY_PASSWORD": "", **environment},
                ):
                    root = Path(temp_dir) / "fixture"
                    root.mkdir()
                    self.fixture(root)
                    self.signing_fixture(root, properties.encode("ascii"))
                    output = root / "app/build/immutable-release/test-output"
                    commands: list[list[str]] = []

                    def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                        commands.append(command)
                        return subprocess.CompletedProcess(command, 0, "", "")

                    with self.assertRaisesRegex(release.UploadKeyGateError, refused) as raised:
                        release.build_immutable_release(root, [":app:bundleRelease"], output, run=package)
                    self.assertNotIn(weak, str(raised.exception))
                    self.assertEqual([], commands)
                    self.assertFalse(output.exists())

    def test_frozen_floor_recheck_passes_strong_values_and_skips_lint(self) -> None:
        strong = "Zk4!mR9#vT2$wQ8%nnnB"
        with patch.dict(os.environ, {"TELECAMPRO_STORE_PASSWORD": "", "TELECAMPRO_KEY_PASSWORD": ""}):
            release.require_frozen_secret_floor(
                f"storePassword={strong}\n".encode("ascii"), [":app:bundleRelease"], os.environ
            )
            release.require_frozen_secret_floor(b"storePassword=weak\n", [":app:lintRelease"], os.environ)
            release.require_frozen_secret_floor(None, [":app:bundleRelease"], os.environ)
            with self.assertRaises(release.UploadKeyGateError):
                release.require_frozen_secret_floor(
                    b"storePassword=weak\n", [":app:bundleRelease"], os.environ
                )

    def test_frozen_floor_recheck_reads_the_copy_not_the_live_file(self) -> None:
        # The live file is swapped to a weak value after the copy; only the frozen bytes count.
        strong = "Zk4!mR9#vT2$wQ8%nnnB"
        with tempfile.TemporaryDirectory() as temp_dir, patch.dict(
            os.environ, {"TELECAMPRO_STORE_PASSWORD": "", "TELECAMPRO_KEY_PASSWORD": ""}
        ):
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            self.signing_fixture(
                root, f"storeFile=release-key.jks\nstorePassword={strong}\n".encode("ascii")
            )
            snapshot = Path(temp_dir) / "snapshot"
            snapshot.mkdir()
            inputs = release.copy_local_build_inputs(root, snapshot)
            (root / "keystore.properties").write_bytes(b"storeFile=release-key.jks\nstorePassword=weak\n")
            self.assertIn(strong.encode("ascii"), inputs.signing_properties or b"")
            self.assertEqual(inputs.signing_properties, (snapshot / "keystore.properties").read_bytes())
            release.require_frozen_secret_floor(inputs.signing_properties, [":app:bundleRelease"], os.environ)

    def test_child_environment_is_an_allowlist(self) -> None:
        # SEC4-4 / AGG4-41: ambient Gradle/JVM channels never reach the sealed child.
        ambient = {
            "PATH": "/bin",
            "HOME": "/home/operator",
            "LC_ALL": "C",
            "JAVA_HOME": "/jdk",
            "ANDROID_HOME": "/sdk",
            "TELECAMPRO_STORE_PASSWORD": "synthetic-store",
            "TELECAMPRO_KEY_PASSWORD": "synthetic-key",
            "TELECAMPRO_KEY_ALIAS": "synthetic-alias",
            release.STORE_FILE_ENVIRONMENT: "/outside/ambient-key.jks",
            "GRADLE_OPTS": "-Dorg.gradle.project.android.injected.signing.key.alias=x",
            "JAVA_TOOL_OPTIONS": "-javaagent:/tmp/agent.jar",
            "_JAVA_OPTIONS": "-javaagent:/tmp/agent.jar",
            "ORG_GRADLE_PROJECT_android.injected.signing.store.file": "/tmp/k.jks",
            "MY_UNRELATED_TOKEN": "value",
        }
        child = release.release_child_environment(ambient)
        self.assertEqual(
            {
                "PATH", "HOME", "LC_ALL", "JAVA_HOME", "ANDROID_HOME", "TELECAMPRO_STORE_PASSWORD",
                "TELECAMPRO_KEY_PASSWORD", "TELECAMPRO_KEY_ALIAS",
            },
            set(child),
        )

    def test_default_runner_drops_ambient_gradle_channels(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir, patch.dict(
            os.environ,
            {"GRADLE_OPTS": "-Xmx1g", "JAVA_TOOL_OPTIONS": "-Dx=y", "ORG_GRADLE_PROJECT_foo": "bar"},
        ):
            result = release.run_checked(
                [
                    "sh",
                    "-c",
                    'test -z "$GRADLE_OPTS" && test -z "$JAVA_TOOL_OPTIONS" && '
                    'test -z "$(env | grep ORG_GRADLE_PROJECT_)"',
                ],
                Path(temp_dir),
            )
            self.assertEqual(0, result.returncode)

    def gradle_home_case(self, setup) -> tuple[list[list[str]], BaseException | None]:
        with tempfile.TemporaryDirectory() as temp_dir:
            gradle_home = Path(temp_dir) / "gradle-home"
            gradle_home.mkdir()
            setup(gradle_home)
            root = Path(temp_dir) / "fixture"
            root.mkdir()
            self.fixture(root)
            output = root / "app/build/immutable-release/test-output"
            commands: list[list[str]] = []

            def package(command: list[str], snapshot: Path) -> subprocess.CompletedProcess[str]:
                commands.append(command)
                artifact = snapshot / "app/build/outputs/bundle/release/app-release.aab"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(b"artifact")
                return subprocess.CompletedProcess(command, 0, "", "")

            error: BaseException | None = None
            with patch.dict(os.environ, {"GRADLE_USER_HOME": str(gradle_home)}):
                try:
                    release.build_immutable_release(root, [":app:bundleRelease"], output, run=package)
                except RuntimeError as raised:
                    error = raised
            if error is None:
                evidence = json.loads(output.joinpath(release.RELEASE_EVIDENCE_NAME).read_text())
                self.assertEqual(commands[0], evidence["gradle_command"])
                self.assertIn("GRADLE_USER_HOME", evidence["gradle_environment_names"])
                self.assertNotIn(str(gradle_home), json.dumps(evidence["gradle_environment_names"]))
            return commands, error

    def test_sealed_run_refuses_init_scripts_and_foreign_user_properties(self) -> None:
        def init_script(home: Path) -> None:
            (home / "init.d").mkdir()
            (home / "init.d/inject.gradle.kts").write_text("// unsealed\n", encoding="utf-8")

        def project_property(home: Path) -> None:
            (home / "gradle.properties").write_text(
                "org.gradle.caching=true\nfoo.bar=1\nsystemProp.http.proxyPassword=x\n", encoding="utf-8"
            )

        def agent_jvmargs(home: Path) -> None:
            (home / "gradle.properties").write_text(
                "org.gradle.jvmargs=-Xmx2g -javaagent:/tmp/agent.jar\n", encoding="utf-8"
            )

        for setup, expected in (
            (init_script, "init.d is not empty"),
            (
                project_property,
                "keys a sealed build cannot carry: foo.bar, systemProp.http.proxyPassword. Remove or comment out",
            ),
            (agent_jvmargs, "org.gradle.jvmargs (agent or project property)"),
        ):
            with self.subTest(case=setup.__name__):
                commands, error = self.gradle_home_case(setup)
                self.assertIsNotNone(error)
                self.assertIn(expected, str(error))
                self.assertNotIn("/tmp/agent.jar", str(error))
                self.assertEqual([], commands)

    def test_sealed_run_passes_fixed_flags_with_an_inert_user_home(self) -> None:
        def inert(home: Path) -> None:
            (home / "init.d").mkdir()
            (home / "gradle.properties").write_text(
                "org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8\norg.gradle.daemon=true\n",
                encoding="utf-8",
            )

        commands, error = self.gradle_home_case(inert)
        self.assertIsNone(error)
        self.assertEqual(
            ["./gradlew", "--no-build-cache", "--no-configuration-cache", "--no-daemon", ":app:bundleRelease"],
            commands[0],
        )

    def test_sealed_run_admits_keys_the_sealed_flags_neutralize_and_proxies(self) -> None:
        # MRG4-10: these were refused although the fixed flags already override the first two and a
        # proxy only routes dependency downloads.
        def developer_home(home: Path) -> None:
            (home / "gradle.properties").write_text(
                "org.gradle.caching=true\n"
                "org.gradle.configuration-cache=true\n"
                "systemProp.http.proxyHost=proxy.example\n"
                "systemProp.http.proxyPort=3128\n"
                "systemProp.http.nonProxyHosts=localhost\n"
                "systemProp.https.proxyHost=proxy.example\n"
                "systemProp.https.proxyPort=3128\n"
                "systemProp.https.nonProxyHosts=localhost\n",
                encoding="utf-8",
            )

        commands, error = self.gradle_home_case(developer_home)
        self.assertIsNone(error)
        self.assertEqual(
            ["./gradlew", "--no-build-cache", "--no-configuration-cache", "--no-daemon", ":app:bundleRelease"],
            commands[0],
        )

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
            self.assertNotIn("Synthetic-Seal-A-9qZ!", str(raised.exception))
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
                init_fixture_repo(root)
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


class GradleTaskArgumentTest(unittest.TestCase):
    """SEC3-1: the positional task argv must never smuggle Gradle options into a sealed build."""

    INJECTED = (
        ["-Pandroid.injected.signing.store.file=blocked.jks"],
        [":app:bundleRelease", "-Pandroid.injected.signing.key.alias=old"],
        ["-I", "evil.gradle"],
        ["-Ievil.gradle"],
        ["--init-script", "evil.gradle"],
        ["--init-script=evil.gradle"],
        [":app:bundleRelease", "-x", ":app:lintVitalRelease"],
        ["-Dorg.gradle.jvmargs=-Xmx1g"],
        [":app:bundleRelease", "--offline"],
        [":app:bundleRelease", ""],
        ["app bundleRelease"],
        ["::app:bundleRelease"],
        [":app:bundleRelease:"],
        [],
    )

    def test_options_and_malformed_paths_are_refused(self) -> None:
        for tasks in self.INJECTED:
            with self.subTest(tasks=tasks):
                with self.assertRaisesRegex(RuntimeError, "Gradle task") as caught:
                    release.validate_gradle_tasks(tasks)
                self.assertNotIn("evil.gradle", str(caught.exception))
                self.assertNotIn("blocked.jks", str(caught.exception))

    def test_plain_task_paths_and_abbreviations_are_accepted(self) -> None:
        release.validate_gradle_tasks([":app:lintRelease", ":app:assembleRelease", ":app:bundleRelease"])
        release.validate_gradle_tasks(["bundleRelease", "app:bR", "lint-Vital_Release"])

    def test_build_refuses_options_before_any_export_or_gradle_run(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            calls: list[list[str]] = []
            for tasks in (["-Pandroid.injected.signing.store.file=x"], ["--init-script", "x"], ["-x", "lint"]):
                with self.subTest(tasks=tasks):
                    with self.assertRaisesRegex(RuntimeError, "not a plain Gradle task path"):
                        release.build_immutable_release(
                            root,
                            [":app:bundleRelease", *tasks],
                            root / "app/build/immutable-release/out",
                            run=lambda command, cwd: calls.append(command),
                        )
            self.assertEqual([], calls)

    def test_main_refuses_options_after_the_argv_separator(self) -> None:
        # argparse alone rejects `-P…` as an unknown option, but `--` would hand it to `tasks`.
        argv = ["build_immutable_release.py", "--", ":app:bundleRelease", "-Pandroid.injected.signing.key.alias=a"]
        with (
            patch.object(sys, "argv", argv),
            patch.object(release, "android_sdk_environment") as sdk,
            patch.object(release, "require_approved_upload_key") as gate,
            patch.object(release, "build_immutable_release") as build,
            patch("sys.stderr") as stderr,
        ):
            self.assertEqual(1, release.main())
        sdk.assert_not_called()
        gate.assert_not_called()
        build.assert_not_called()
        written = "".join(call.args[0] for call in stderr.write.call_args_list)
        self.assertIn("immutable release build refused", written)
        self.assertNotIn("alias=a", written)


if __name__ == "__main__":
    unittest.main()
