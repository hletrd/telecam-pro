from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "check_release_artifact.py"
SPEC = importlib.util.spec_from_file_location("check_release_artifact", SCRIPT)
assert SPEC and SPEC.loader
release = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release
SPEC.loader.exec_module(release)


class ReleaseArtifactIdentityTest(unittest.TestCase):
    TREE = "c" * 40

    def fixture(self, root: Path) -> tuple[Path, Path, str]:
        commit = "a" * 40
        (root / "app").mkdir()
        (root / "app/build.gradle.kts").write_text(
            'android { defaultConfig {\n'
            '    applicationId = "me.hletrd.telecampro"\n'
            '    minSdk = 33\n'
            '    targetSdk = 36\n'
            '    versionCode = 4\n'
            '    versionName = "1.0.2"\n'
            '} }\n'
        )
        (root / "releases").mkdir()
        provisional = root / "releases/provisional.aab"
        with zipfile.ZipFile(provisional, "w") as bundle:
            bundle.writestr(
                "base/root/META-INF/version-control-info.textproto",
                f'repositories {{\n  system: GIT\n  revision: "{commit}"\n}}\n',
            )
            bundle.writestr(
                release.PROVENANCE_MEMBER,
                "schema=2\nevidence=external-wrapper-required\n" +
                    f"commit={commit}\ntree={self.TREE}\n",
            )
            bundle.writestr("base/manifest/AndroidManifest.xml", b"fixture")
        digest = release.sha256_file(provisional)
        aab = root / f"releases/telecam-pro-1.0.2-{commit[:7]}-{digest[:12]}.aab"
        provisional.rename(aab)
        evidence = (
            root / "app/build/immutable-release" / f"{commit[:12]}-fixture" /
            release.RELEASE_EVIDENCE_NAME
        )
        evidence.parent.mkdir(parents=True)
        evidence.write_text(
            json.dumps(
                {
                    "schema": 1,
                    "boundary": release.RELEASE_EVIDENCE_BOUNDARY,
                    "commit": commit,
                    "tree": self.TREE,
                    "outputs": [
                        {
                            "path": "bundle/release/app-release.aab",
                            "sha256": digest,
                            "size": aab.stat().st_size,
                        }
                    ],
                },
                sort_keys=True,
            ) + "\n",
            encoding="utf-8",
        )
        attestation = root / "release-attestation.json"
        attestation.write_text(
            json.dumps(
                {
                    "schema_version": 2,
                    "status": "upload-ready",
                    "git_commit": commit,
                    "version_code": 4,
                    "version_name": "1.0.2",
                    "aab_path": str(aab.relative_to(root)),
                    "aab_sha256": digest,
                    "signer_sha256": release.EXPECTED_UPLOAD_CERT_SHA256,
                    "release_evidence_path": str(evidence.relative_to(root)),
                },
                sort_keys=True,
            )
            + "\n"
        )
        attestation.with_name(attestation.name + ".sha256").write_text(
            f"{release.sha256_file(attestation)}  {attestation.name}\n"
        )
        return attestation, aab, commit

    def refresh_attestation(self, attestation: Path, aab: Path) -> Path:
        document = json.loads(attestation.read_text())
        digest = release.sha256_file(aab)
        commit = document["git_commit"]
        renamed = aab.with_name(
            f"telecam-pro-1.0.2-{commit[:7]}-{digest[:12]}.aab"
        )
        aab.rename(renamed)
        document["aab_path"] = str(renamed.relative_to(attestation.parent))
        document["aab_sha256"] = digest
        evidence = attestation.parent / document["release_evidence_path"]
        receipt = json.loads(evidence.read_text(encoding="utf-8"))
        receipt["outputs"] = [
            {
                "path": "bundle/release/app-release.aab",
                "sha256": digest,
                "size": renamed.stat().st_size,
            }
        ]
        evidence.write_text(json.dumps(receipt, sort_keys=True) + "\n", encoding="utf-8")
        attestation.write_text(json.dumps(document, sort_keys=True) + "\n")
        attestation.with_name(attestation.name + ".sha256").write_text(
            f"{release.sha256_file(attestation)}  {attestation.name}\n"
        )
        return renamed

    @staticmethod
    def runner(
        commit: str,
        *,
        additional_signer: str | None = None,
        strict_returncode: int = 0,
    ):
        def run(command, cwd):
            del cwd
            if command[:3] == ["git", "rev-parse", "HEAD"]:
                return subprocess.CompletedProcess(command, 0, commit + "\n", "")
            if command[:2] == ["git", "rev-parse"] and command[2].endswith("^{tree}"):
                return subprocess.CompletedProcess(
                    command, 0, ReleaseArtifactIdentityTest.TREE + "\n", ""
                )
            if command[:2] == ["git", "status"]:
                return subprocess.CompletedProcess(command, 0, "", "")
            if command[:3] == ["git", "ls-files", "-z"]:
                return subprocess.CompletedProcess(command, 0, "", "")
            if command[0] == "jarsigner":
                return subprocess.CompletedProcess(command, strict_returncode, "jar verified\n", "")
            if command[0] == "keytool":
                if "-importcert" in command:
                    return subprocess.CompletedProcess(command, 0, "Certificate added\n", "")
                if "-rfc" in command:
                    return subprocess.CompletedProcess(
                        command,
                        0,
                        "-----BEGIN CERTIFICATE-----\nZmFrZQ==\n-----END CERTIFICATE-----\n",
                        "",
                    )
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
                if command[1] == "validate":
                    return subprocess.CompletedProcess(command, 0, "App Bundle validated\n", "")
                manifest = (
                    '<manifest package="me.hletrd.telecampro" android:versionCode="4" '
                    'android:versionName="1.0.2">\n'
                    '  <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="36" />\n'
                    '</manifest>\n'
                )
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

    def test_copied_direct_gradle_output_without_wrapper_receipt_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)
            document = json.loads(attestation.read_text())
            document["release_evidence_path"] = "releases/release-evidence.json"
            attestation.write_text(json.dumps(document, sort_keys=True) + "\n")
            attestation.with_name(attestation.name + ".sha256").write_text(
                f"{release.sha256_file(attestation)}  {attestation.name}\n"
            )

            failures = release.check_release_identity(root, attestation, run=self.runner(commit))

            self.assertTrue(
                any("immutable-release child namespace" in item for item in failures),
                failures,
            )
            self.assertTrue(any("release evidence is unreadable" in item for item in failures))

    def test_attestation_sidecar_and_head_are_both_authoritative(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)
            attestation.write_text(attestation.read_text() + " ")

            failures = release.check_release_identity(root, attestation, run=self.runner("b" * 40))

            self.assertTrue(any("sidecar" in item for item in failures))
            self.assertTrue(any("HEAD" in item for item in failures))

    def test_attestation_inputs_reject_path_swap_at_tool_boundary(self) -> None:
        for target_name in ("attestation", "sidecar"):
            with self.subTest(target=target_name), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, _, commit = self.fixture(root)
                target = (
                    attestation
                    if target_name == "attestation"
                    else attestation.with_name(attestation.name + ".sha256")
                )
                relative_target = target.relative_to(root)
                snapshot = release.snapshot_regular_bytes
                swapped = False

                def capture(snapshot_root, relative):
                    nonlocal swapped
                    captured = snapshot(snapshot_root, relative)
                    if not swapped and relative == relative_target:
                        target.rename(target.with_name(target.name + ".retired"))
                        target.write_bytes(b"B")
                        swapped = True
                    return captured

                with patch.object(release, "snapshot_regular_bytes", side_effect=capture):
                    failures = release.check_release_identity(
                        root, attestation, run=self.runner(commit)
                    )

                self.assertTrue(swapped)
                expected_label = (
                    "attestation" if target_name == "attestation" else "attestation sidecar"
                )
                self.assertEqual(
                    [f"{expected_label} source identity or digest changed during verification"],
                    failures,
                )

    def test_attestation_inputs_reject_symlinks_before_tools(self) -> None:
        for target_name in ("attestation", "sidecar"):
            with self.subTest(target=target_name), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, _, commit = self.fixture(root)
                target = (
                    attestation
                    if target_name == "attestation"
                    else attestation.with_name(attestation.name + ".sha256")
                )
                outside = root / f"outside-{target.name}"
                target.rename(outside)
                target.symlink_to(outside)
                tool_called = False
                base = self.runner(commit)

                def run(command, cwd):
                    nonlocal tool_called
                    tool_called = True
                    return base(command, cwd)

                failures = release.check_release_identity(root, attestation, run=run)

                self.assertFalse(tool_called)
                self.assertTrue(
                    any("no-follow regular file" in failure for failure in failures),
                    failures,
                )

    def test_attestation_inputs_reject_in_place_a_b_a_at_tool_boundary(self) -> None:
        for target_name in ("attestation", "sidecar"):
            with self.subTest(target=target_name), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, _, commit = self.fixture(root)
                target = (
                    attestation
                    if target_name == "attestation"
                    else attestation.with_name(attestation.name + ".sha256")
                )
                relative_target = target.relative_to(root)
                snapshot = release.snapshot_regular_bytes
                mutated = False

                def capture(snapshot_root, relative):
                    nonlocal mutated
                    captured = snapshot(snapshot_root, relative)
                    if not mutated and relative == relative_target:
                        original = target.read_bytes()
                        original_mtime_ns = target.stat().st_mtime_ns
                        target.write_bytes(b"B" * len(original))
                        target.write_bytes(original)
                        os.utime(
                            target,
                            ns=(original_mtime_ns + 1_000_000, original_mtime_ns + 1_000_000),
                        )
                        mutated = True
                    return captured

                with patch.object(release, "snapshot_regular_bytes", side_effect=capture):
                    failures = release.check_release_identity(
                        root, attestation, run=self.runner(commit)
                    )

                self.assertTrue(mutated)
                expected_label = (
                    "attestation" if target_name == "attestation" else "attestation sidecar"
                )
                self.assertEqual(
                    [f"{expected_label} source identity or digest changed during verification"],
                    failures,
                )

    def test_pinned_upload_cert_makes_strict_verification_clean(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)

            self.assertEqual(
                [], release.check_release_identity(root, attestation, run=self.runner(commit))
            )

    def test_unsigned_entry_strict_warning_is_blocking(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)

            failures = release.check_release_identity(
                root,
                attestation,
                run=self.runner(commit, strict_returncode=16),
            )

            self.assertTrue(any("strict jarsigner" in item for item in failures))

    def test_real_appended_unsigned_entry_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            jar = root / "signed.aab"
            store = root / "signer.p12"
            configured_home = os.environ.get("JAVA_HOME")
            candidate_bins = [
                Path(configured_home) / "bin" if configured_home else None,
                Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin"),
            ]
            java_bin = next(
                (path for path in candidate_bins if path is not None and path.is_dir()),
                None,
            )
            keytool = str(java_bin / "keytool") if java_bin else shutil.which("keytool")
            jarsigner = str(java_bin / "jarsigner") if java_bin else shutil.which("jarsigner")
            self.assertIsNotNone(keytool, "JDK 21 keytool is required for release-integrity tests")
            self.assertIsNotNone(jarsigner, "JDK 21 jarsigner is required for release-integrity tests")
            with zipfile.ZipFile(jar, "w") as bundle:
                bundle.writestr("base/manifest/AndroidManifest.xml", b"signed")
            subprocess.run(
                [
                    keytool, "-genkeypair", "-noprompt", "-alias", "upload",
                    "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", str(store),
                    "-storepass", "changeit", "-keypass", "changeit",
                    "-dname", "CN=Release Checker Test", "-validity", "2",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                [
                    jarsigner, "-keystore", str(store), "-storepass", "changeit",
                    "-keypass", "changeit", str(jar), "upload",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            with zipfile.ZipFile(jar, "a") as bundle:
                bundle.writestr("base/assets/unsigned-payload.bin", b"not authenticated")

            tool_path = str(Path(keytool).parent)
            with patch.dict(
                os.environ,
                {"PATH": tool_path + os.pathsep + os.environ.get("PATH", "")},
            ):
                failure = release.strict_jar_verification_failure(
                    root, jar, release.default_run
                )

            self.assertIsNotNone(failure)
            self.assertIn("strict jarsigner", failure)

    def test_packaged_source_commit_is_authoritative(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, aab, commit = self.fixture(root)
            with zipfile.ZipFile(aab, "a") as bundle:
                bundle.writestr(
                    "base/root/META-INF/version-control-info.textproto",
                    f'repositories {{ revision: "{"b" * 40}" }}\n',
                )

            failures = release.check_release_identity(root, attestation, run=self.runner(commit))

            self.assertTrue(any("packaged AGP source revision" in item for item in failures))

    def test_packaged_clean_source_tree_is_authoritative(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, aab, commit = self.fixture(root)
            with zipfile.ZipFile(aab, "a") as bundle:
                bundle.writestr(
                    release.PROVENANCE_MEMBER,
                    "schema=2\nevidence=external-wrapper-required\n" +
                        f"commit={commit}\ntree={'d' * 40}\n",
                )
            self.refresh_attestation(attestation, aab)

            failures = release.check_release_identity(
                root, attestation, run=self.runner(commit)
            )

            self.assertTrue(any("clean-source commit/tree" in item for item in failures))

    def test_missing_malformed_and_duplicate_source_provenance_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            _, aab, commit = self.fixture(root)
            self.assertEqual((commit, self.TREE), release.packaged_source_provenance(aab))

            malformed = root / "malformed.aab"
            with zipfile.ZipFile(malformed, "w") as bundle:
                bundle.writestr(
                    release.PROVENANCE_MEMBER,
                    f"schema=2\ncommit={commit}\ntree={self.TREE}\n",
                )
            self.assertIsNone(release.packaged_source_provenance(malformed))

            with zipfile.ZipFile(aab, "a") as bundle:
                bundle.writestr(
                    release.PROVENANCE_MEMBER,
                    "schema=2\nevidence=external-wrapper-required\n" +
                        f"commit={commit}\ntree={self.TREE}\n",
                )
            self.assertIsNone(release.packaged_source_provenance(aab))

    def test_unexpected_provenance_namespace_member_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            _, aab, _ = self.fixture(root)
            with zipfile.ZipFile(aab, "a") as bundle:
                bundle.writestr(
                    release.PROVENANCE_NAMESPACE + "stale.properties",
                    "stale=true\n",
                )

            self.assertIsNone(release.packaged_source_provenance(aab))

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

    def test_source_swap_after_early_and_late_tool_boundaries_fails_closed(self) -> None:
        for trigger in (("keytool", "-printcert"), ("bundletool", "dump")):
            with self.subTest(trigger=trigger), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, aab, commit = self.fixture(root)
                base = self.runner(commit)
                swapped = False
                inspected_paths: list[Path] = []

                def run(command, cwd):
                    nonlocal swapped
                    for argument in command:
                        if str(argument).endswith(".aab") or str(argument).startswith("--bundle="):
                            raw = str(argument).removeprefix("--bundle=")
                            inspected_paths.append(Path(raw))
                    if not swapped and command[0] == trigger[0] and trigger[1] in command:
                        original_bytes = aab.read_bytes()
                        aab.rename(aab.with_suffix(".retired"))
                        aab.write_bytes(original_bytes + b"source-path-swap")
                        swapped = True
                    return base(command, cwd)

                failures = release.check_release_identity(root, attestation, run=run)

                self.assertTrue(swapped)
                self.assertTrue(
                    any("source identity or digest changed" in item for item in failures),
                    failures,
                )
                self.assertTrue(inspected_paths)
                self.assertTrue(all(path != aab for path in inspected_paths))
                self.assertEqual(1, len({path for path in inspected_paths if path.suffix == ".aab"}))

    @staticmethod
    def is_external_boundary(command: list[str], boundary: str) -> bool:
        if boundary == "early":
            return command[:2] == ["keytool", "-printcert"] and "-rfc" not in command
        return command[:2] == ["bundletool", "dump"]

    def test_clean_head_change_at_early_and_late_tool_boundaries_fails_closed(self) -> None:
        for boundary in ("early", "late"):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, _, commit = self.fixture(root)
                base = self.runner(commit)
                current_head = commit
                changed = False

                def run(command, cwd):
                    nonlocal current_head, changed
                    if command[:3] == ["git", "rev-parse", "HEAD"]:
                        return subprocess.CompletedProcess(command, 0, current_head + "\n", "")
                    result = base(command, cwd)
                    if not changed and self.is_external_boundary(command, boundary):
                        current_head = "b" * 40
                        changed = True
                    return result

                failures = release.check_release_identity(root, attestation, run=run)

                self.assertTrue(changed)
                self.assertIn("HEAD changed during verification", failures)

    def test_dirty_tree_change_at_early_and_late_tool_boundaries_fails_closed(self) -> None:
        for boundary in ("early", "late"):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, _, commit = self.fixture(root)
                base = self.runner(commit)
                current_status = ""
                changed = False

                def run(command, cwd):
                    nonlocal current_status, changed
                    if command[:2] == ["git", "status"]:
                        return subprocess.CompletedProcess(command, 0, current_status, "")
                    result = base(command, cwd)
                    if not changed and self.is_external_boundary(command, boundary):
                        current_status = " M app/build.gradle.kts\n"
                        changed = True
                    return result

                failures = release.check_release_identity(root, attestation, run=run)

                self.assertTrue(changed)
                self.assertIn("working-tree status changed during verification", failures)

    def test_clean_head_change_after_terminal_status_is_detected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, _, commit = self.fixture(root)
            base = self.runner(commit)
            current_head = commit
            status_calls = 0

            def run(command, cwd):
                nonlocal current_head, status_calls
                if command[:3] == ["git", "rev-parse", "HEAD"]:
                    return subprocess.CompletedProcess(command, 0, current_head + "\n", "")
                result = base(command, cwd)
                if command[:2] == ["git", "status"]:
                    status_calls += 1
                    if status_calls == 2:
                        current_head = "b" * 40
                return result

            failures = release.check_release_identity(root, attestation, run=run)

            self.assertEqual(2, status_calls)
            self.assertIn("HEAD changed during verification", failures)

    def test_ignored_packageable_source_is_rejected_initially_and_if_added_late(self) -> None:
        for added_late in (False, True):
            with self.subTest(added_late=added_late), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, _, commit = self.fixture(root)
                base = self.runner(commit)
                ignored_calls = 0

                def run(command, cwd):
                    nonlocal ignored_calls
                    if command[:3] == ["git", "ls-files", "-z"]:
                        ignored_calls += 1
                        present = not added_late or ignored_calls == 2
                        output = (
                            "app/src/main/res/raw/release_secret.bin\0" if present else ""
                        )
                        return subprocess.CompletedProcess(command, 0, output, "")
                    return base(command, cwd)

                failures = release.check_release_identity(root, attestation, run=run)

                self.assertEqual(2, ignored_calls)
                expected = (
                    "ignored packageable source inputs changed during verification"
                    if added_late
                    else "release source roots contain ignored packageable inputs"
                )
                self.assertIn(expected, failures)

    def test_ignored_source_query_is_nul_safe_and_scoped_to_package_roots(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            (root / ".gitignore").write_text("*.secret\n", encoding="utf-8")
            ignored = root / "app/src/main/res/raw/release.secret"
            ignored.parent.mkdir(parents=True)
            ignored.write_bytes(b"package input")
            allowed = root / "releases/upload.secret"
            allowed.parent.mkdir()
            allowed.write_bytes(b"immutable output")

            result = release.ignored_packageable_sources(release.default_run, root)

            self.assertEqual(0, result.returncode)
            self.assertEqual("app/src/main/res/raw/release.secret\0", result.stdout)

    def test_source_version_path_swap_at_early_and_late_tool_boundaries_fails_closed(self) -> None:
        for boundary in ("early", "late"):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, _, commit = self.fixture(root)
                source = root / release.SOURCE_VERSION_PATH
                base = self.runner(commit)
                changed = False

                def run(command, cwd):
                    nonlocal changed
                    result = base(command, cwd)
                    if not changed and self.is_external_boundary(command, boundary):
                        original = source.read_bytes()
                        source.rename(source.with_name(source.name + ".retired"))
                        source.write_bytes(original)
                        changed = True
                    return result

                failures = release.check_release_identity(root, attestation, run=run)

                self.assertTrue(changed)
                self.assertIn(
                    "source-version input identity or digest changed during verification",
                    failures,
                )

    def test_source_version_in_place_a_b_a_at_tool_boundaries_fails_closed(self) -> None:
        for boundary in ("early", "late"):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, _, commit = self.fixture(root)
                source = root / release.SOURCE_VERSION_PATH
                base = self.runner(commit)
                changed = False

                def run(command, cwd):
                    nonlocal changed
                    result = base(command, cwd)
                    if not changed and self.is_external_boundary(command, boundary):
                        original = source.read_bytes()
                        original_mtime_ns = source.stat().st_mtime_ns
                        source.write_bytes(b"B" * len(original))
                        source.write_bytes(original)
                        os.utime(
                            source,
                            ns=(original_mtime_ns + 1_000_000, original_mtime_ns + 1_000_000),
                        )
                        changed = True
                    return result

                failures = release.check_release_identity(root, attestation, run=run)

                self.assertTrue(changed)
                self.assertIn(
                    "source-version input identity or digest changed during verification",
                    failures,
                )

    def test_private_aab_copy_rejects_permanent_and_transient_mutation(self) -> None:
        for restore in (False, True):
            with self.subTest(restore=restore), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, _, commit = self.fixture(root)
                base = self.runner(commit)
                mutated = False

                def run(command, cwd):
                    nonlocal mutated
                    if not mutated and command[:2] == ["keytool", "-printcert"]:
                        private = Path(command[-1])
                        original = private.read_bytes()
                        private.chmod(0o600)
                        private.write_bytes(b"private-B")
                        if restore:
                            private.write_bytes(original)
                        private.chmod(0o400)
                        mutated = True
                    return base(command, cwd)

                failures = release.check_release_identity(root, attestation, run=run)

                self.assertTrue(mutated)
                self.assertTrue(
                    any("private AAB inspection copy changed" in item for item in failures),
                    failures,
                )

    def test_artifact_symlink_and_special_file_inputs_fail_before_tools(self) -> None:
        for absolute_target in (False, True):
            with self.subTest(absolute_target=absolute_target), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, aab, commit = self.fixture(root)
                target = root / "outside.aab"
                target.write_bytes(aab.read_bytes())
                aab.unlink()
                aab.symlink_to(target if absolute_target else Path("../outside.aab"))

                failures = release.check_release_identity(root, attestation, run=self.runner(commit))

                self.assertTrue(any("no-follow regular file" in item for item in failures), failures)

        if hasattr(os, "mkfifo"):
            with tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                attestation, aab, commit = self.fixture(root)
                aab.unlink()
                os.mkfifo(aab)

                failures = release.check_release_identity(root, attestation, run=self.runner(commit))

                self.assertTrue(any("no-follow regular file" in item for item in failures), failures)

    def test_artifact_parent_symlink_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            attestation, aab, commit = self.fixture(root)
            original_releases = root / "original-releases"
            external_releases = root / "external-releases"
            (root / "releases").rename(original_releases)
            external_releases.mkdir()
            (external_releases / aab.name).write_bytes((original_releases / aab.name).read_bytes())
            (root / "releases").symlink_to(external_releases, target_is_directory=True)

            failures = release.check_release_identity(root, attestation, run=self.runner(commit))

            self.assertTrue(any("no-follow regular file" in item for item in failures), failures)


if __name__ == "__main__":
    unittest.main()
