from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


DEVICE_TESTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEVICE_TESTS))

import run as runner  # noqa: E402
import cases  # noqa: E402
from dtest import framework  # noqa: E402
from dtest import media  # noqa: E402
from dtest.adb import (  # noqa: E402
    APP_ID,
    MEDIA_RELATIVE_PATH,
    Adb,
    AdbError,
    MediaRow,
    UiTree,
    parse_media_rows,
)


class FakeAdb:
    serial = "test-serial"


class FakeTree:
    def __init__(self, descriptions: set[str]):
        self.descriptions = descriptions

    def find(self, *, desc: str | None = None, text: str | None = None):
        del text
        if desc in self.descriptions:
            return object()
        return None


class GuardAdb(Adb):
    def __init__(self, workdir: Path, *, allow_destructive: bool, descriptions: set[str]):
        super().__init__("test-serial", workdir, allow_destructive=allow_destructive)
        self.descriptions = descriptions
        self.commands: list[str] = []

    def pid(self) -> int | None:
        return 123

    def ui(self) -> FakeTree:
        return FakeTree(self.descriptions)

    def shell(self, cmd: str, timeout: int = 60) -> str:
        del timeout
        self.commands.append(cmd)
        return ""


def media_row(
    row_id: int,
    *,
    collection: str = "image",
    pending: bool = False,
    size_bytes: int = 1024,
    family_sequence: int | None = None,
    extension: str | None = None,
) -> MediaRow:
    sequence = row_id if family_sequence is None else family_sequence
    prefix = "IMG" if collection == "image" else "VID"
    suffix = extension or ("heic" if collection == "image" else "mp4")
    return MediaRow(
        collection=collection,
        row_id=row_id,
        display_name=f"{prefix}_TELECAM_F1_1700000000000_{sequence:010d}.{suffix}",
        mime_type=(
            "image/x-adobe-dng" if suffix == "dng"
            else "image/heic" if collection == "image"
            else "video/mp4"
        ),
        relative_path=MEDIA_RELATIVE_PATH,
        is_pending=pending,
        width=3072,
        height=4096,
        size_bytes=size_bytes,
        duration_ms=None if collection == "image" else 5000,
        owner_package_name=APP_ID,
    )


class MediaQueryAdb(Adb):
    def __init__(self, workdir: Path, *, output_override: str | None = None):
        super().__init__("test-serial", workdir)
        self.commands: list[str] = []
        self.output_override = output_override

    def shell(self, cmd: str, timeout: int = 60) -> str:
        del timeout
        self.commands.append(cmd)
        if self.output_override is not None:
            return self.output_override
        if "/images/" in cmd:
            return (
                "Row: 0 _id=41, _display_name=IMG_41.heic, mime_type=image/heic, "
                "relative_path=DCIM/X9Tele/, is_pending=0, width=3072, height=4096, "
                "_size=123456, owner_package_name=me.hletrd.telecampro.debug"
            )
        return (
            "Row: 0 _id=7, _display_name=VID_7.mp4, mime_type=video/mp4, "
            "relative_path=DCIM/X9Tele/, is_pending=1, width=2160, height=3840, "
            "_size=7654321, owner_package_name=me.hletrd.telecampro.debug, duration=5000"
        )


class SequenceMediaAdb(Adb):
    def __init__(self, workdir: Path, rows: list[list[MediaRow]]):
        super().__init__("test-serial", workdir)
        self.rows = iter(rows)
        self.last: list[MediaRow] = []

    def media_store_rows(self) -> list[MediaRow]:
        self.last = next(self.rows, self.last)
        return self.last


class RunnerHelpersTest(unittest.TestCase):
    def test_base_apk_path_prefers_base_split(self) -> None:
        output = "\n".join(
            (
                "package:/data/app/example/split_config.arm64_v8a.apk",
                "package:/data/app/example/base.apk",
            )
        )

        self.assertEqual(runner.base_apk_path(output), "/data/app/example/base.apk")

    def test_base_apk_path_rejects_missing_package_rows(self) -> None:
        self.assertIsNone(runner.base_apk_path(""))

    def test_sha256_file_streams_the_exact_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture = Path(temp_dir) / "fixture.apk"
            fixture.write_bytes(b"TeleCam Pro")

            self.assertEqual(
                runner.sha256_file(fixture),
                "0941d239425b174d99d3eb516e36fcff357b668a7ad24e5e481531f59a5ec28f",
            )


class UiSemanticsTest(unittest.TestCase):
    @staticmethod
    def focal_xml(checked: str, *, radio_role: bool = True) -> str:
        nodes = []
        class_name = "android.widget.RadioButton" if radio_role else "android.widget.Button"
        for index, description in enumerate(cases.FOCAL_PRESETS):
            is_checked = str(description == checked).lower()
            nodes.append(
                f'<node text="" content-desc="{description}" class="{class_name}" '
                f'checkable="true" checked="{is_checked}" selected="false" enabled="true" '
                f'bounds="[{index * 10},0][{index * 10 + 10},10]" />'
            )
        return f"<hierarchy>{''.join(nodes)}</hierarchy>"

    def test_ui_tree_exports_radio_checked_state(self) -> None:
        tree = UiTree(self.focal_xml("3× lens"))

        selected = tree.find_desc_exact("3× lens")
        self.assertIsNotNone(selected)
        self.assertEqual(selected.class_name, "android.widget.RadioButton")
        self.assertTrue(selected.checkable)
        self.assertTrue(selected.checked)
        self.assertFalse(selected.selected)

    def test_focal_contract_requires_exactly_expected_checked_radio(self) -> None:
        self.assertIsNone(cases.focal_rail_error(UiTree(self.focal_xml("10× lens")), "10× lens"))
        self.assertIn(
            "not radio buttons",
            cases.focal_rail_error(
                UiTree(self.focal_xml("10× lens", radio_role=False)),
                "10× lens",
            ),
        )
        self.assertIn(
            "expected exactly",
            cases.focal_rail_error(UiTree(self.focal_xml("1× lens")), "3× lens"),
        )

    def test_still_precondition_detects_every_multi_drive_osd_tag(self) -> None:
        for label in ("BURST", "AEB±2", "TL 5s"):
            with self.subTest(label=label):
                xml = (
                    f'<hierarchy><node text="{label}" content-desc="" class="android.widget.TextView" '
                    'checkable="false" checked="false" selected="false" enabled="true" '
                    'bounds="[0,0][100,20]" /></hierarchy>'
                )
                self.assertEqual(cases.active_multi_drive_label(UiTree(xml)), label)

        self.assertIsNone(cases.active_multi_drive_label(UiTree("<hierarchy />")))


class MediaStoreTest(unittest.TestCase):
    def test_sips_dimension_parser_reads_full_heif_canvas(self) -> None:
        output = "/tmp/image.heic:\n  pixelWidth: 3072\n  pixelHeight: 4096\n"
        self.assertEqual(media.sips_dimensions(output), (3072, 4096))
        self.assertIsNone(media.sips_dimensions("pixelWidth: <nil>"))

    def test_content_query_parser_keeps_pending_dimensions_and_duration(self) -> None:
        output = (
            "Row: 0 _id=7, _display_name=VID_7.mp4, mime_type=video/mp4, "
            "relative_path=DCIM/X9Tele/, is_pending=1, width=2160, height=3840, "
            "_size=7654321, owner_package_name=me.hletrd.telecampro.debug, duration=5000"
        )

        rows = parse_media_rows("video", output)

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].key, ("video", 7))
        self.assertTrue(rows[0].is_pending)
        self.assertEqual((rows[0].width, rows[0].height), (2160, 3840))
        self.assertEqual(rows[0].duration_ms, 5000)

    def test_media_query_includes_owned_pending_rows_in_both_collections(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            adb = MediaQueryAdb(Path(temp_dir))

            rows = adb.media_store_rows()

        self.assertEqual([row.key for row in rows], [("image", 41), ("video", 7)])
        self.assertFalse(rows[0].is_pending)
        self.assertTrue(rows[1].is_pending)
        self.assertTrue(all(r"android\:query-arg-match-pending:i:1" in cmd for cmd in adb.commands))
        self.assertTrue(all(MEDIA_RELATIVE_PATH in cmd and APP_ID in cmd for cmd in adb.commands))

    def test_new_rows_wait_for_publish_and_stable_family(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            adb = SequenceMediaAdb(
                Path(temp_dir),
                [
                    [media_row(1), media_row(2, pending=True, size_bytes=0)],
                    [
                        media_row(1),
                        media_row(2),
                        media_row(3, family_sequence=2, extension="dng"),
                    ],
                    [
                        media_row(1),
                        media_row(2),
                        media_row(3, family_sequence=2, extension="dng"),
                    ],
                ],
            )

            rows = adb.wait_new_media_rows(
                {("image", 1)},
                timeout_s=1,
                settle_s=0,
            )

        self.assertEqual([row.key for row in rows], [("image", 2), ("image", 3)])
        self.assertTrue(all(not row.is_pending and row.size_bytes > 0 for row in rows))

    def test_media_query_rejects_cli_parse_errors_and_unexpected_output(self) -> None:
        for output in (
            "[ERROR] Binding not well formed",
            "warning: provider returned partial output",
            "",
            "Row: malformed",
            "No result found.\nRow: 0 _id=7",
        ):
            with self.subTest(output=output), tempfile.TemporaryDirectory() as temp_dir:
                adb = MediaQueryAdb(Path(temp_dir), output_override=output)
                with self.assertRaisesRegex(AdbError, "unexpected output"):
                    adb.media_store_rows()

    def test_media_row_parser_rejects_missing_projected_fields(self) -> None:
        output = (
            "Row: 0 _id=7, _display_name=VID_7.mp4, mime_type=video/mp4, "
            "relative_path=DCIM/X9Tele/, width=2160, height=3840, _size=7654321, "
            "owner_package_name=me.hletrd.telecampro.debug, duration=5000"
        )

        with self.assertRaisesRegex(AdbError, "missing fields .*is_pending"):
            parse_media_rows("video", output)

    def test_wait_rejects_multiple_or_noncanonical_new_families(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            adb = SequenceMediaAdb(Path(temp_dir), [[media_row(2), media_row(3)]])
            with self.assertRaisesRegex(AdbError, "multiple new capture families"):
                adb.wait_new_media_rows(set(), timeout_s=0.1, settle_s=0)

        malformed = media_row(4)
        malformed = MediaRow(
            **{**malformed.__dict__, "display_name": "IMG_legacy.heic"},
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            adb = SequenceMediaAdb(Path(temp_dir), [[malformed]])
            with self.assertRaisesRegex(AdbError, "non-canonical"):
                adb.wait_new_media_rows(set(), timeout_s=0.1, settle_s=0)


class ForceStopGuardTest(unittest.TestCase):
    def make_adb(self, *, allowed: bool, descriptions: set[str]) -> tuple[GuardAdb, tempfile.TemporaryDirectory]:
        temp_dir = tempfile.TemporaryDirectory()
        return (
            GuardAdb(Path(temp_dir.name), allow_destructive=allowed, descriptions=descriptions),
            temp_dir,
        )

    def test_force_stop_requires_destructive_guard(self) -> None:
        adb, temp_dir = self.make_adb(allowed=False, descriptions={"Take photo"})
        self.addCleanup(temp_dir.cleanup)

        with self.assertRaisesRegex(AdbError, "explicit destructive approval"):
            adb.force_stop()

        self.assertNotIn(f"am force-stop {APP_ID}", adb.commands)

    def test_force_stop_refuses_active_recording(self) -> None:
        adb, temp_dir = self.make_adb(allowed=True, descriptions={"Stop recording"})
        self.addCleanup(temp_dir.cleanup)

        with self.assertRaisesRegex(AdbError, "recording is active"):
            adb.force_stop()

        self.assertNotIn(f"am force-stop {APP_ID}", adb.commands)

    def test_force_stop_refuses_unknown_ui_state(self) -> None:
        adb, temp_dir = self.make_adb(allowed=True, descriptions=set())
        self.addCleanup(temp_dir.cleanup)

        with self.assertRaisesRegex(AdbError, "idle camera state cannot be proven"):
            adb.force_stop()

        self.assertNotIn(f"am force-stop {APP_ID}", adb.commands)

    def test_force_stop_allows_proven_idle_video(self) -> None:
        adb, temp_dir = self.make_adb(allowed=True, descriptions={"Start recording"})
        self.addCleanup(temp_dir.cleanup)

        adb.force_stop()

        self.assertIn(f"am force-stop {APP_ID}", adb.commands)


class FrameworkSafetyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_registry = list(framework._REGISTRY)
        framework._REGISTRY.clear()

    def tearDown(self) -> None:
        framework._REGISTRY[:] = self.original_registry

    def run_suite(self, *, allow_destructive: bool = False, name_filter: str | None = None) -> int:
        with tempfile.TemporaryDirectory() as temp_dir:
            return framework.run(
                FakeAdb(),
                ["smoke"],
                name_filter,
                Path(temp_dir),
                allow_destructive=allow_destructive,
            )

    def test_destructive_case_is_not_invoked_without_guard(self) -> None:
        called = False

        @framework.test("guarded", "smoke", destructive=True)
        def guarded(_ctx) -> None:
            nonlocal called
            called = True

        self.assertEqual(self.run_suite(), 2)
        self.assertFalse(called)

    def test_destructive_case_runs_only_with_explicit_guard(self) -> None:
        called = False

        @framework.test("guarded", "smoke", destructive=True)
        def guarded(_ctx) -> None:
            nonlocal called
            called = True

        self.assertEqual(self.run_suite(allow_destructive=True), 0)
        self.assertTrue(called)

    def test_empty_filter_is_non_green(self) -> None:
        @framework.test("present", "smoke")
        def present(_ctx) -> None:
            pass

        self.assertEqual(self.run_suite(name_filter="missing"), 2)

    def test_required_incomplete_case_is_non_green_even_with_a_pass(self) -> None:
        @framework.test("pass", "smoke")
        def passing(_ctx) -> None:
            pass

        @framework.test("required", "smoke")
        def required(_ctx) -> None:
            raise framework.Incomplete("required evidence missing")

        self.assertEqual(self.run_suite(), 2)

    def test_failed_recording_case_cannot_feed_a_later_force_stop(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            adb = GuardAdb(
                Path(temp_dir),
                allow_destructive=True,
                descriptions={"Start recording"},
            )

            @framework.test("start_then_fail", "smoke")
            def start_then_fail(_ctx) -> None:
                adb.descriptions = {"Stop recording"}
                raise AssertionError("simulated failure after REC start")

            @framework.test("later_force_stop", "smoke", destructive=True)
            def later_force_stop(_ctx) -> None:
                adb.force_stop()

            exit_code = framework.run(
                adb,
                ["smoke"],
                None,
                Path(temp_dir) / "report",
                allow_destructive=True,
            )

        self.assertEqual(exit_code, 1)
        self.assertNotIn(f"am force-stop {APP_ID}", adb.commands)


if __name__ == "__main__":
    unittest.main()
