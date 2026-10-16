from __future__ import annotations

import ast
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from unittest.mock import patch


DEVICE_TESTS = Path(__file__).resolve().parents[1]
REPO_ROOT = DEVICE_TESTS.parent
sys.path.insert(0, str(DEVICE_TESTS))

from dtest.adb import Adb, MEDIA_RELATIVE_PATH, UiTree  # noqa: E402
from dtest import selectors  # noqa: E402
from dtest import contracts  # noqa: E402
from dtest.contracts import (  # noqa: E402
    ContractError,
    DebugSourceIdentity,
    ProvenDebugSourceContract,
    SourceManifestEntry,
    current_debug_source_contract,
    current_debug_source_identity,
    harness_source_manifest,
    inspect_apk_source_identity,
    inspect_apk_contract,
    parse_apk_contract,
    parse_debug_source_manifest,
    production_capture_subdir,
    render_debug_source_manifest,
    require_apk_source_match,
    source_manifest_sha256,
)
from dtest.selectors import FULL_ACTION_SELECTORS, OPEN_SETTINGS, START_RECORDING  # noqa: E402
import cases  # noqa: E402, F401 — materialize the executable registry
from dtest import framework  # noqa: E402


BADGING = """\
package: name='me.hletrd.telecampro.debug' versionCode='4'
launchable-activity: name='me.hletrd.telecampro.MainActivity' label=''
"""
XMLTREE = """\
E: activity
  A: android:name(0x01010003)="me.hletrd.telecampro.ui.UiSnapshotActivity" (Raw: "me.hletrd.telecampro.ui.UiSnapshotActivity")
E: activity
  A: android:name(0x01010003)="me.hletrd.telecampro.MainActivity" (Raw: "me.hletrd.telecampro.MainActivity")
"""


class ApkContractTest(unittest.TestCase):
    def test_manifest_contract_uses_current_exact_components(self) -> None:
        contract = parse_apk_contract(BADGING, XMLTREE)
        self.assertEqual(contract.application_id, "me.hletrd.telecampro.debug")
        self.assertEqual(
            contract.launcher_component,
            "me.hletrd.telecampro.debug/me.hletrd.telecampro.MainActivity",
        )
        self.assertEqual(
            contract.snapshot_component,
            "me.hletrd.telecampro.debug/me.hletrd.telecampro.ui.UiSnapshotActivity",
        )

    def test_inspection_runs_both_tools_against_the_exact_apk(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "attested.apk"
            apk.write_bytes(b"apk")
            commands: list[list[str]] = []

            def run_text(command):
                commands.append(list(command))
                return BADGING if command[0] == "aapt-test" else XMLTREE

            contract = inspect_apk_contract(
                apk,
                run_text=run_text,
                aapt="aapt-test",
                aapt2="aapt2-test",
            )

            self.assertEqual(contract.application_id, "me.hletrd.telecampro.debug")
            self.assertEqual(commands[0][-1], str(apk))
            self.assertEqual(commands[1][-1], str(apk))


class DocumentedCaseRegistryTest(unittest.TestCase):
    def test_readme_case_matrix_exactly_matches_executable_registry(self) -> None:
        readme = (DEVICE_TESTS / "README.md").read_text(encoding="utf-8")
        matrix = readme.split("## Tiers and cases", 1)[1].split(
            "## Device facts the harness encodes",
            1,
        )[0]
        documented = [
            (tier, name, summary.strip())
            for tier, name, summary in re.findall(
                r"^\| (smoke|full|reliability) \| `([^`]+)` \| (.+) \|$",
                matrix,
                re.MULTILINE,
            )
        ]
        executable = [(case.tier, case.name) for case in framework._REGISTRY]

        self.assertEqual(executable, [(tier, name) for tier, name, _ in documented])
        self.assertTrue(documented)
        self.assertTrue(all(summary for _, _, summary in documented))
        self.assertTrue(all(case.doc for case in framework._REGISTRY))


class SourceIdentityTest(unittest.TestCase):
    @staticmethod
    def identity(
        *,
        commit: str = "a" * 40,
        dirty: bool = False,
        payload: bytes = b"source\n",
    ) -> DebugSourceIdentity:
        entry = SourceManifestEntry(
            "app/src/main/source.kt",
            len(payload),
            hashlib.sha256(payload).hexdigest(),
        )
        canonical = f"{entry.sha256}  {entry.bytes}  {entry.path}\n".encode()
        return DebugSourceIdentity(
            commit,
            dirty,
            hashlib.sha256(canonical).hexdigest(),
            (entry,),
            contracts.IMMUTABLE_DEBUG_SOURCE_OWNER,
        )

    @staticmethod
    def apk(path: Path, identity: DebugSourceIdentity | None, *, member: str | None = None) -> None:
        with zipfile.ZipFile(path, "w") as archive:
            if identity is not None:
                archive.writestr(
                    member or contracts.DEBUG_PROVENANCE_MEMBER,
                    render_debug_source_manifest(identity),
                )

    @staticmethod
    def proven(identity: DebugSourceIdentity) -> ProvenDebugSourceContract:
        return ProvenDebugSourceContract(identity, "TeleCamPro")

    def test_sorted_source_manifest_changes_after_one_byte_change(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "dtest").mkdir()
            source = root / "dtest/runner.py"
            source.write_text("x = 1\n", encoding="utf-8")
            (root / "README.md").write_text("contract\n", encoding="utf-8")
            (root / "reports").mkdir()
            (root / "reports/evidence.py").write_text("ignored\n", encoding="utf-8")

            before = harness_source_manifest(root)
            before_digest = source_manifest_sha256(before)
            source.write_text("x = 2\n", encoding="utf-8")
            after = harness_source_manifest(root)

            self.assertEqual([entry["path"] for entry in before], ["README.md", "dtest/runner.py"])
            self.assertNotEqual(before_digest, source_manifest_sha256(after))

    def test_harness_manifest_rejects_symlinked_input(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            target = root / "target.py"
            target.write_text("VALUE = 1\n", encoding="utf-8")
            link = root / "cases.py"
            link.symlink_to(target)

            with self.assertRaisesRegex(ContractError, "must not be a symlink: cases.py"):
                harness_source_manifest(root)

    def test_packaged_manifest_clean_match_and_attestation_identity(self) -> None:
        identity = self.identity()
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "debug.apk"
            self.apk(apk, identity)
            self.assertEqual(parse_debug_source_manifest(render_debug_source_manifest(identity)), identity)
            self.assertEqual(inspect_apk_source_identity(apk), identity)
            with patch.object(
                contracts,
                "current_debug_source_contract",
                return_value=self.proven(identity),
            ):
                proven = require_apk_source_match(apk, Path(temp_dir))
            self.assertEqual(proven.identity, identity.identity)
            self.assertIn(identity.content_sha256, proven.as_attestation()["identity"])
            self.assertEqual(proven.source.source_owner, contracts.IMMUTABLE_DEBUG_SOURCE_OWNER)
            self.assertEqual(proven.capture_subdir, "TeleCamPro")

    def test_mutable_debug_manifest_is_not_evidence_grade(self) -> None:
        identity = self.identity()
        mutable = DebugSourceIdentity(
            identity.commit,
            identity.dirty,
            identity.content_sha256,
            identity.files,
            contracts.MUTABLE_DEBUG_SOURCE_OWNER,
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "debug.apk"
            self.apk(apk, mutable)
            with patch.object(
                contracts,
                "current_debug_source_contract",
                return_value=self.proven(identity),
            ):
                with self.assertRaisesRegex(ContractError, "not evidence-grade"):
                    require_apk_source_match(apk, Path(temp_dir))

    def test_stale_commit_and_dirty_content_mismatch_fail_closed(self) -> None:
        packaged = self.identity(commit="a" * 40)
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "debug.apk"
            self.apk(apk, packaged)
            stale_current = self.identity(commit="b" * 40)
            with patch.object(
                contracts,
                "current_debug_source_contract",
                return_value=self.proven(stale_current),
            ):
                with self.assertRaisesRegex(ContractError, "stale/mismatched.*commit"):
                    require_apk_source_match(apk, Path(temp_dir))

            dirty_packaged = self.identity(dirty=True, payload=b"dirty A\n")
            self.apk(apk, dirty_packaged)
            dirty_current = self.identity(dirty=True, payload=b"dirty B\n")
            with patch.object(
                contracts,
                "current_debug_source_contract",
                return_value=self.proven(dirty_current),
            ):
                with self.assertRaisesRegex(ContractError, "stale/mismatched.*content"):
                    require_apk_source_match(apk, Path(temp_dir))

    def test_missing_duplicate_and_malformed_provenance_fail_closed(self) -> None:
        identity = self.identity()
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "debug.apk"
            self.apk(apk, None)
            with self.assertRaisesRegex(ContractError, "exactly one"):
                inspect_apk_source_identity(apk)

            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(contracts.DEBUG_PROVENANCE_MEMBER, b"schema=999\n")
            with self.assertRaisesRegex(ContractError, "schema/header"):
                inspect_apk_source_identity(apk)

            with zipfile.ZipFile(apk, "w") as archive:
                manifest = render_debug_source_manifest(identity)
                archive.writestr(contracts.DEBUG_PROVENANCE_MEMBER, manifest)
                archive.writestr(
                    contracts.DEBUG_PROVENANCE_NAMESPACE + "stale.manifest", manifest
                )
            with self.assertRaisesRegex(ContractError, "exactly one"):
                inspect_apk_source_identity(apk)

    def test_current_manifest_distinguishes_clean_and_exact_dirty_content(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "src/source.kt"
            source.parent.mkdir()
            source.write_text("clean\n", encoding="utf-8")
            build = root / "build.gradle.kts"
            build.write_text("plugins {}\n", encoding="utf-8")
            subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)

            clean = current_debug_source_identity(root, scopes=("src", "build.gradle.kts"))
            self.assertFalse(clean.dirty)
            source.write_text("dirty one\n", encoding="utf-8")
            dirty_one = current_debug_source_identity(root, scopes=("src", "build.gradle.kts"))
            source.write_text("dirty two\n", encoding="utf-8")
            dirty_two = current_debug_source_identity(root, scopes=("src", "build.gradle.kts"))
            self.assertTrue(dirty_one.dirty)
            self.assertTrue(dirty_two.dirty)
            self.assertNotEqual(clean.content_sha256, dirty_one.content_sha256)
            self.assertNotEqual(dirty_one.content_sha256, dirty_two.content_sha256)

    def test_current_manifest_rejects_leaf_and_parent_swaps(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "src/source.kt"
            source.parent.mkdir()
            source.write_text("source-A\n", encoding="utf-8")
            replacement = root / "replacement.kt"
            replacement.write_text("source-B\n", encoding="utf-8")
            build = root / "build.gradle.kts"
            build.write_text("plugins {}\n", encoding="utf-8")
            subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)

            real_read = os.read
            swapped = False

            def swap_leaf(fd, size):
                nonlocal swapped
                chunk = real_read(fd, size)
                if chunk and not swapped:
                    swapped = True
                    source.rename(root / "src/saved.kt")
                    replacement.rename(source)
                return chunk

            with patch.object(contracts.os, "read", side_effect=swap_leaf):
                with self.assertRaisesRegex(ContractError, "changed while reading"):
                    current_debug_source_identity(root, scopes=("src", "build.gradle.kts"))

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source_dir = root / "src"
            source_dir.mkdir()
            (source_dir / "source.kt").write_text("source-A\n", encoding="utf-8")
            outside = root / "outside"
            outside.mkdir()
            (outside / "source.kt").write_text("outside\n", encoding="utf-8")
            build = root / "build.gradle.kts"
            build.write_text("plugins {}\n", encoding="utf-8")
            subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "add", "src", "build.gradle.kts"], cwd=root, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)

            real_read = os.read
            swapped = False

            def swap_parent(fd, size):
                nonlocal swapped
                chunk = real_read(fd, size)
                if chunk and not swapped:
                    swapped = True
                    source_dir.rename(root / "saved-src")
                    source_dir.symlink_to(outside, target_is_directory=True)
                return chunk

            with patch.object(contracts.os, "read", side_effect=swap_parent):
                with self.assertRaisesRegex(ContractError, "scope changed while reading"):
                    current_debug_source_identity(root, scopes=("src", "build.gradle.kts"))

    def test_current_manifest_rejects_member_addition_during_freeze(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source_dir = root / "src"
            source_dir.mkdir()
            (source_dir / "source.kt").write_text("source-A\n", encoding="utf-8")
            build = root / "build.gradle.kts"
            build.write_text("plugins {}\n", encoding="utf-8")
            subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)

            real_read = os.read
            added = False

            def add_member(fd, size):
                nonlocal added
                chunk = real_read(fd, size)
                if chunk and not added:
                    added = True
                    (source_dir / "added.kt").write_text("added\n", encoding="utf-8")
                return chunk

            with patch.object(contracts.os, "read", side_effect=add_member):
                with self.assertRaisesRegex(ContractError, "member set changed"):
                    current_debug_source_identity(root, scopes=("src", "build.gradle.kts"))

    def test_current_manifest_derives_dirty_truth_without_index_or_ignore_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "src/source.kt"
            source.parent.mkdir()
            source.write_text("committed\n", encoding="utf-8")
            build = root / "build.gradle.kts"
            build.write_text("plugins {}\n", encoding="utf-8")
            (root / ".gitignore").write_text("src/*.tmp\n", encoding="utf-8")
            subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)

            # Stage B in the mutable index, then restore A in the compiler-owned worktree. Ignore an
            # extra packageable file too. Neither mutable Git input is allowed to redefine the exact
            # bytes Gradle sees versus the immutable HEAD tree.
            source.write_text("index-only B\n", encoding="utf-8")
            subprocess.run(["git", "add", "src/source.kt"], cwd=root, check=True)
            source.write_text("committed\n", encoding="utf-8")
            (root / "src/ignored.tmp").write_text("packageable\n", encoding="utf-8")

            identity = current_debug_source_identity(root, scopes=("src", "build.gradle.kts"))

            self.assertTrue(identity.dirty)
            self.assertIn("src/ignored.tmp", {entry.path for entry in identity.files})

    def test_current_manifest_rejects_head_advance_during_freeze(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "src/source.kt"
            source.parent.mkdir()
            source.write_text("committed\n", encoding="utf-8")
            (root / "build.gradle.kts").write_text("plugins {}\n", encoding="utf-8")
            subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)
            real_committed = contracts._committed_debug_source_entries
            advanced = False

            def advance_head(repo_root, commit, scopes):
                nonlocal advanced
                result = real_committed(repo_root, commit, scopes)
                (root / "unscoped.txt").write_text("new commit\n", encoding="utf-8")
                subprocess.run(["git", "add", "unscoped.txt"], cwd=root, check=True)
                subprocess.run(["git", "commit", "-m", "advance"], cwd=root, check=True, capture_output=True)
                advanced = True
                return result

            with patch.object(
                contracts,
                "_committed_debug_source_entries",
                side_effect=advance_head,
            ):
                with self.assertRaisesRegex(ContractError, "Git HEAD changed"):
                    current_debug_source_identity(root, scopes=("src", "build.gradle.kts"))
            self.assertTrue(advanced)


class ProductionContractTest(unittest.TestCase):
    def test_harness_media_directory_matches_production_constant(self) -> None:
        contract = current_debug_source_contract(REPO_ROOT)
        subdir = contract.capture_subdir
        self.assertEqual(subdir, "TeleCamPro")
        self.assertEqual(MEDIA_RELATIVE_PATH, f"DCIM/{subdir}/")
        self.assertEqual(production_capture_subdir(REPO_ROOT), subdir)

    def test_capture_subdir_is_owned_by_the_same_frozen_source_read(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for scope in contracts.DEBUG_SOURCE_SCOPES:
                path = root / scope
                if scope in {"app/src/main", "app/src/debug"}:
                    path.mkdir(parents=True, exist_ok=True)
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(f"fixture {scope}\n", encoding="utf-8")
            production = root / contracts.CAPTURE_SUBDIR_SOURCE
            production.parent.mkdir(parents=True, exist_ok=True)
            production.write_text(
                'object MediaStoreWriter {\n    const val CAPTURE_SUBDIR = "FrozenA"\n}\n',
                encoding="utf-8",
            )
            subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=root, check=True, capture_output=True)

            frozen = current_debug_source_contract(root)
            production.write_text(
                'object MediaStoreWriter {\n    const val CAPTURE_SUBDIR = "LaterB"\n}\n',
                encoding="utf-8",
            )

            self.assertEqual(frozen.capture_subdir, "FrozenA")
            self.assertEqual(frozen.source.commit, subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=root,
                capture_output=True,
                text=True,
                check=True,
            ).stdout.strip())

    def test_capture_subdir_contract_rejects_shell_or_path_syntax(self) -> None:
        for value in ("../escape", "TeleCam Pro", "TeleCamPro;rm"):
            with self.subTest(value=value), self.assertRaisesRegex(
                ContractError,
                "CAPTURE_SUBDIR is unsafe",
            ):
                contracts._capture_subdir_from_frozen_source(
                    f'const val CAPTURE_SUBDIR = "{value}"\n'.encode()
                )


class LocalizedSelectorTest(unittest.TestCase):
    @staticmethod
    def tree(*descriptions: str) -> UiTree:
        nodes = "".join(
            f'<node text="" content-desc="{desc}" bounds="[0,0][100,100]" />'
            for desc in descriptions
        )
        return UiTree(f"<hierarchy>{nodes}</hierarchy>")

    def test_stable_selector_resolves_english_and_korean_exactly(self) -> None:
        english = self.tree("Open settings", "Start recording")
        korean = self.tree("설정 열기", "녹화 시작")

        self.assertIsNotNone(english.find_selector(OPEN_SETTINGS, "en-US"))
        self.assertIsNotNone(english.find_selector(START_RECORDING, "en-US"))
        self.assertIsNotNone(korean.find_selector(OPEN_SETTINGS, "ko-KR"))
        self.assertIsNotNone(korean.find_selector(START_RECORDING, "ko-KR"))
        self.assertIsNone(korean.find_selector(OPEN_SETTINGS, "en-US"))

    def test_every_full_action_identity_resolves_in_english_and_korean(self) -> None:
        identities = [selector.identity for selector in FULL_ACTION_SELECTORS]
        self.assertEqual(len(identities), len(set(identities)))
        for selector in FULL_ACTION_SELECTORS:
            for locale in ("en-US", "ko-KR"):
                labels = selector.labels_for(locale)
                self.assertTrue(labels, selector.identity)
                tree = self.tree(*labels)
                self.assertIsNotNone(tree.find_selector(selector, locale), selector.identity)

    def test_core_selector_labels_match_android_resources_in_both_languages(self) -> None:
        pairs = [
            (selectors.OPEN_SETTINGS, "a11y_open_settings"),
            (selectors.CLOSE_SETTINGS, "a11y_close_settings"),
            (selectors.OPEN_FUNCTION_MENU, "a11y_open_function_menu"),
            (selectors.CLOSE_FUNCTION_MENU, "a11y_close_function_menu"),
            (selectors.CLOSE_ADJUSTMENT, "a11y_close_adjustment"),
            (selectors.SWITCH_CAMERA, "a11y_switch_camera"),
            (selectors.RESET_FOCUS_POINT, "a11y_reset_focus_point"),
            (selectors.TAKE_PHOTO, "a11y_take_photo"),
            (selectors.START_RECORDING, "a11y_start_recording"),
            (selectors.STOP_RECORDING, "a11y_stop_recording"),
            (selectors.RECORDING, "a11y_recording"),
            (selectors.TAKE_PHOTO_WHILE_RECORDING, "a11y_take_photo_while_recording"),
            (selectors.TELECONVERTER, "label_teleconverter"),
            (selectors.GAMMA, "label_gamma"),
            (selectors.SHUTTER_SPEED, "a11y_shutter_speed"),
        ]
        tab_names = (
            "my", "shoot", "exposure", "focus", "lens", "video", "image", "assist", "setup"
        )
        pairs.extend(zip(selectors.SETTINGS_TABS, (f"settings_tab_{name}" for name in tab_names)))
        page_resources = (
            "section_my_menu",
            *(f"settings_tab_{name}" for name in tab_names[1:]),
        )
        pairs.extend(zip(selectors.SETTINGS_PAGE_TITLES, page_resources))
        fn_resources = {
            "Focus": "settings_tab_focus",
            "Shutter": "label_shutter",
            "Zoom": "label_zoom",
            "Stabilization": "label_stabilization",
            "Drive": "label_drive",
            "Meter": "label_meter",
            "Peaking": "label_peaking",
            "Zebra": "label_zebra",
            "Gamma": "label_gamma",
            "Directionality": "label_directionality",
            "Grid": "label_grid",
            "Level": "label_level",
            "Loupe": "label_loupe",
            "Tele": "label_tele",
            "Frame": "label_frame",
        }
        pairs.extend((selectors.FN_TILES[name], resource) for name, resource in fn_resources.items())

        for locale, directory in (("en-US", "values"), ("ko-KR", "values-ko")):
            root = ET.parse(REPO_ROOT / f"app/src/main/res/{directory}/strings.xml").getroot()
            resources = {node.attrib["name"]: "".join(node.itertext()) for node in root.findall("string")}
            for selector, resource_name in pairs:
                self.assertEqual(
                    (resources[resource_name],),
                    selector.labels_for(locale),
                    f"{selector.identity}/{locale} drifted from {resource_name}",
                )

    def test_state_changing_cases_do_not_reintroduce_raw_localized_selectors(self) -> None:
        source_path = DEVICE_TESTS / "cases.py"
        tree = ast.parse(source_path.read_text(encoding="utf-8"), filename=str(source_path))
        forbidden = {
            label
            for selector in FULL_ACTION_SELECTORS
            for label in selector.labels_for("en-US")
            if any(character.isalpha() for character in label)
        }
        violations: list[tuple[int, str, str]] = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
                continue
            if node.func.attr not in {"tap_ui", "find", "find_desc_exact"}:
                continue
            values = [
                argument.value for argument in node.args
                if isinstance(argument, ast.Constant) and isinstance(argument.value, str)
            ]
            values += [
                keyword.value.value for keyword in node.keywords
                if keyword.arg in {"desc", "text"}
                and isinstance(keyword.value, ast.Constant)
                and isinstance(keyword.value.value, str)
            ]
            violations.extend(
                (node.lineno, node.func.attr, value) for value in values if value in forbidden
            )
        self.assertEqual([], violations)

    def test_locale_attestation_uses_current_user_override_then_system_fallback(self) -> None:
        class LocaleAdb(Adb):
            def __init__(self, responses: dict[str, str], workdir: Path):
                super().__init__("test", workdir)
                self.responses = responses
                self.commands: list[str] = []

            def shell(self, command: str, timeout: int = 60) -> str:
                del timeout
                self.commands.append(command)
                return self.responses[command]

        with tempfile.TemporaryDirectory() as temp_dir:
            common = {
                "am get-current-user": "10",
                "getprop persist.sys.locale": "en-US",
            }
            override_command = (
                "cmd locale get-app-locales me.hletrd.telecampro.debug "
                "--user 10 2>/dev/null || true"
            )
            overridden = LocaleAdb(
                {**common, override_command: "Locales for app: [ko-KR]"},
                Path(temp_dir),
            )
            fallback = LocaleAdb(
                {**common, override_command: "Locales for app: []"},
                Path(temp_dir),
            )

            self.assertEqual(overridden.locale_state()["effective"], "ko-KR")
            self.assertEqual(fallback.locale_state()["effective"], "en-US")
            self.assertIn("--user 10", overridden.commands[1])


if __name__ == "__main__":
    unittest.main()
