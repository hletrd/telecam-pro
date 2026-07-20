from __future__ import annotations

import copy
import struct
import sys
import tempfile
import unittest
from fractions import Fraction
from pathlib import Path
from types import SimpleNamespace


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
    DisplayMetrics,
    MediaRow,
    UiNode,
    UiTree,
    parse_df_available_bytes,
    parse_media_rows,
    relevant_global_fatal_lines,
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


class RecordingControlTree:
    def __init__(self, state: str):
        self.state = state

    def find_desc_exact(self, description: str):
        if self.state == "recording" and description == "Stop recording":
            return SimpleNamespace(center=(100, 100))
        if self.state == "idle" and description == "Start recording":
            return SimpleNamespace(center=(100, 100))
        return None


class StopRetryAdb:
    def __init__(self, *, state: str = "recording", drops: int = 1):
        self.state = state
        self.drops = drops
        self.tap_count = 0

    def pid(self) -> int:
        return 123

    def ui(self) -> RecordingControlTree:
        return RecordingControlTree(self.state)

    def tap(self, _x: int, _y: int) -> None:
        self.tap_count += 1
        if self.tap_count > self.drops:
            self.state = "idle"


class FinalizationAdb:
    def __init__(self, line: str | None):
        self.line = line

    def wait_log(self, _mark: str, _pattern: str, timeout_s: float, pid: int):
        del timeout_s, pid
        return self.line


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


class DfAdb(Adb):
    def __init__(self, workdir: Path, output: str):
        super().__init__("test-serial", workdir)
        self.output = output
        self.commands: list[str] = []

    def shell(self, cmd: str, timeout: int = 60) -> str:
        del timeout
        self.commands.append(cmd)
        return self.output


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

    def test_display_metrics_use_screencap_size_and_active_density_override(self) -> None:
        class MetricsAdb(Adb):
            def exec_out(self, cmd: str, timeout: int = 60) -> bytes:
                del timeout
                self.assert_command = cmd
                return struct.pack("<4I", 1440, 3168, 1, 0)

            def shell(self, cmd: str, timeout: int = 60) -> str:
                del timeout
                self.density_command = cmd
                return "Physical density: 510\nOverride density: 560"

        with tempfile.TemporaryDirectory() as temp_dir:
            adb = MetricsAdb("test-serial", Path(temp_dir))
            metrics = adb.display_metrics()

        self.assertEqual(metrics, DisplayMetrics(1440, 3168, 560))
        self.assertEqual(adb.assert_command, "screencap")
        self.assertEqual(adb.density_command, "wm density")

    def test_free_bytes_parses_one_df_k_available_column(self) -> None:
        output = (
            "Filesystem     1K-blocks      Used Available Use% Mounted on\n"
            "/dev/fuse       234567890 123456789 111111101  53% /storage/emulated\n"
        )
        expected = 111_111_101 * 1024
        self.assertEqual(parse_df_available_bytes(output), expected)
        with tempfile.TemporaryDirectory() as temp_dir:
            adb = DfAdb(Path(temp_dir), output)
            self.assertEqual(adb.free_bytes(), expected)
        self.assertEqual(adb.commands, ["df -k /sdcard"])

        for malformed in (
            "Filesystem 1K-blocks Used Capacity Mounted on\n/dev/fuse 10 5 50% /sdcard",
            "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/fuse 10 5 0 50% /sdcard",
            "Filesystem 1K-blocks Used Available Use% Mounted on\n",
        ):
            with self.subTest(malformed=malformed):
                with self.assertRaises(AdbError):
                    parse_df_available_bytes(malformed)


class LogcatSafetyTest(unittest.TestCase):
    def test_global_scan_keeps_app_and_camera_service_failures_only(self) -> None:
        log = "\n".join(
            (
                "ActivityManager: ANR in me.hletrd.telecampro.debug (me.hletrd.telecampro.debug/.MainActivity)",
                "libc: Fatal signal 6 (SIGABRT) in tid 123 (provider@2.4-se)",
                "Camera3-Device: Camera 0: Camera HAL reported serious device error",
                "CameraManagerGlobal: Camera service is unavailable",
                "Camera2ClientBase: Error condition 1 reported by HAL, requestId 42",
                "ActivityManager: ANR in com.example.unrelated",
                "CameraDevice-JV-0: Error clearing streaming request: Function not implemented (-38)",
            )
        )

        fatal = relevant_global_fatal_lines(log)

        self.assertEqual(len(fatal), 4)
        self.assertTrue(any("ANR in me.hletrd.telecampro.debug" in line for line in fatal))
        self.assertTrue(any("provider@2.4-se" in line for line in fatal))
        self.assertTrue(any("serious device error" in line for line in fatal))
        self.assertTrue(any("Camera service is unavailable" in line for line in fatal))
        self.assertFalse(any("com.example.unrelated" in line for line in fatal))
        self.assertFalse(any("Camera2ClientBase" in line for line in fatal))
        self.assertFalse(any("Function not implemented" in line for line in fatal))

    def test_fatal_scan_reads_app_pid_and_all_system_buffers(self) -> None:
        class RecordingLogAdb(Adb):
            def __init__(self, workdir: Path):
                super().__init__("test-serial", workdir)
                self.commands: list[tuple[str, ...]] = []

            def _run(self, *args: str, binary: bool = False, timeout: int = 60):
                del binary, timeout
                self.commands.append(args)
                if "--pid=123" in args:
                    return "AndroidRuntime: FATAL EXCEPTION: main"
                return "ActivityManager: ANR in me.hletrd.telecampro.debug"

        with tempfile.TemporaryDirectory() as temp_dir:
            adb = RecordingLogAdb(Path(temp_dir))
            fatal = adb.fatal_lines("1784538672.419", pid=123)

        self.assertEqual(len(fatal), 2)
        self.assertEqual(
            adb.commands,
            [
                (
                    "logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "-t",
                    "1784538672.419",
                    "--pid=123",
                ),
                (
                    "logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "-b",
                    "all",
                    "-t",
                    "1784538672.419",
                ),
            ],
        )

    def test_log_mark_uses_exact_epoch_milliseconds(self) -> None:
        class RecordingMarkAdb(Adb):
            def __init__(self, workdir: Path):
                super().__init__("test-serial", workdir)
                self.command: str | None = None

            def shell(self, cmd: str, timeout: int = 60) -> str:
                del timeout
                self.command = cmd
                return "1784538672.419"

        with tempfile.TemporaryDirectory() as temp_dir:
            adb = RecordingMarkAdb(Path(temp_dir))
            mark = adb.log_mark()

        self.assertEqual(mark, "1784538672.419")
        self.assertEqual(adb.command, "date +%s.%3N")


class UiSemanticsTest(unittest.TestCase):
    @staticmethod
    def camera_layout_xml(
        *,
        detailed: bool = True,
        short_settings: bool = False,
        overlap_top: bool = False,
        split_fn_action: bool = False,
    ) -> str:
        nodes = []

        def add(
            description: str,
            bounds: tuple[int, int, int, int],
            *,
            class_name: str = "android.widget.Button",
            checkable: bool = False,
            checked: bool = False,
        ) -> None:
            left, top, right, bottom = bounds
            nodes.append(
                f'<node text="" content-desc="{description}" class="{class_name}" '
                f'checkable="{str(checkable).lower()}" checked="{str(checked).lower()}" '
                f'selected="false" enabled="true" clickable="true" focusable="true" '
                f'bounds="[{left},{top}][{right},{bottom}]" />'
            )

        top_descriptions = (
            ("Flash Off", "Self timer Off", "Aspect ratio 4:3", "Grid off") if detailed else ()
        ) + ("Teleconverter", "Hide shooting info", "Open settings")
        for index, description in enumerate(top_descriptions):
            left = index * 52
            if overlap_top and index == 1:
                left = 0
            width = 30 if short_settings and description == "Open settings" else 48
            add(description, (left, 0, left + width, 48))

        fn_bounds = (0, 450, 48, 498) if detailed else (0, 520, 48, 568)
        add("Open function menu", fn_bounds)
        if split_fn_action:
            nodes[-1] = nodes[-1].replace('class="android.widget.Button"', 'class="android.view.View"').replace(
                'clickable="true"', 'clickable="false"',
            )
            nodes.append(
                '<node text="" content-desc="" class="android.widget.Button" '
                'checkable="false" checked="false" selected="false" enabled="true" '
                f'clickable="true" focusable="true" bounds="[{fn_bounds[0]},{fn_bounds[1]}]'
                f'[{fn_bounds[2]},{fn_bounds[3]}]" />'
            )
        for index, description in enumerate(cases.FOCAL_PRESETS):
            add(
                description,
                (60 + index * 52, 520, 108 + index * 52, 568),
                class_name="android.widget.RadioButton",
                checkable=True,
                checked=description == "1× lens",
            )
        for index, description in enumerate(cases.CAPTURE_MODES):
            add(
                description,
                (105 + index * 102, 580, 153 + index * 102, 628),
                class_name="android.widget.RadioButton",
                checkable=True,
                checked=description == "Photo mode",
            )
        add("No capture to review", (10, 650, 62, 702))
        add("Take photo", (142, 640, 218, 716))
        return f"<hierarchy>{''.join(nodes)}</hierarchy>"

    def test_camera_layout_contract_checks_touch_bounds_order_and_overlap(self) -> None:
        metrics = DisplayMetrics(360, 800, 160)
        self.assertEqual(
            cases.camera_chrome_layout_errors(
                UiTree(self.camera_layout_xml(detailed=True)), metrics, detailed=True,
            ),
            [],
        )
        self.assertEqual(
            cases.camera_chrome_layout_errors(
                UiTree(self.camera_layout_xml(detailed=False)), metrics, detailed=False,
            ),
            [],
        )

        short = cases.camera_chrome_layout_errors(
            UiTree(self.camera_layout_xml(short_settings=True)),
            metrics,
        )
        self.assertTrue(any("Open settings: touch bounds" in error for error in short))

        overlap = cases.camera_chrome_layout_errors(
            UiTree(self.camera_layout_xml(overlap_top=True)),
            metrics,
        )
        self.assertTrue(any("top bar" in error and "overlap" in error for error in overlap))

        split = cases.camera_chrome_layout_errors(
            UiTree(self.camera_layout_xml(split_fn_action=True)), metrics, detailed=True,
        )
        self.assertTrue(any("equal-bounds split semantics" in error for error in split))

    @staticmethod
    def function_menu_xml(
        *,
        count: int = 8,
        short_first: bool = False,
        overlap_second: bool = False,
        out_of_bounds_last: bool = False,
        split_first_action: bool = False,
        focusable: bool = True,
        full_screen_close: bool = False,
    ) -> str:
        labels = tuple(sorted(cases.FN_TILE_LABELS))[:count]
        nodes = []
        for index, description in enumerate(labels):
            column = index % 4
            row = index // 4
            left = 8 + column * 86
            top = 600 + row * 66
            if overlap_second and index == 1:
                left = 8
            width = 30 if short_first and index == 0 else 78
            right = left + width
            if out_of_bounds_last and index == len(labels) - 1:
                right = 370
            nodes.append(
                f'<node text="" content-desc="{description}" class="android.widget.Button" '
                f'checkable="false" checked="false" selected="false" enabled="true" '
                f'clickable="{str(not (split_first_action and index == 0)).lower()}" '
                f'focusable="{str(focusable).lower()}" bounds="[{left},{top}][{right},{top + 58}]" />'
            )
            if split_first_action and index == 0:
                nodes[-1] = nodes[-1].replace('class="android.widget.Button"', 'class="android.view.View"')
                nodes.append(
                    '<node text="" content-desc="" class="android.widget.Button" '
                    'checkable="false" checked="false" selected="false" enabled="true" '
                    f'clickable="true" focusable="true" bounds="[{left},{top}][{right},{top + 58}]" />'
                )
        close_bounds = "[0,0][360,800]" if full_screen_close else "[304,540][352,588]"
        nodes.append(
            '<node text="" content-desc="Close function menu" class="android.widget.Button" '
            'checkable="false" checked="false" selected="false" enabled="true" '
            f'clickable="true" focusable="true" bounds="{close_bounds}" />'
        )
        return f"<hierarchy>{''.join(nodes)}</hierarchy>"

    def test_function_menu_layout_contract_checks_count_size_bounds_and_overlap(self) -> None:
        metrics = DisplayMetrics(360, 800, 160)
        self.assertEqual(
            cases.function_menu_layout_errors(UiTree(self.function_menu_xml()), metrics),
            [],
        )

        too_many = cases.function_menu_layout_errors(
            UiTree(self.function_menu_xml(count=9)),
            metrics,
        )
        self.assertTrue(any("expected 1..8" in error for error in too_many))

        short = cases.function_menu_layout_errors(
            UiTree(self.function_menu_xml(short_first=True)),
            metrics,
        )
        self.assertTrue(any("touch bounds" in error for error in short))

        overlap = cases.function_menu_layout_errors(
            UiTree(self.function_menu_xml(overlap_second=True)),
            metrics,
        )
        self.assertTrue(any("overlap" in error for error in overlap))

        out_of_bounds = cases.function_menu_layout_errors(
            UiTree(self.function_menu_xml(out_of_bounds_last=True)),
            metrics,
        )
        self.assertTrue(any("out of screen bounds" in error for error in out_of_bounds))

        split = cases.function_menu_layout_errors(
            UiTree(self.function_menu_xml(split_first_action=True)), metrics,
        )
        self.assertTrue(any("equal-bounds split semantics" in error for error in split))

        unfocusable = cases.function_menu_layout_errors(
            UiTree(self.function_menu_xml(focusable=False)), metrics,
        )
        self.assertTrue(any("not focusable" in error for error in unfocusable))

        scrim_close = cases.function_menu_layout_errors(
            UiTree(self.function_menu_xml(full_screen_close=True)), metrics,
        )
        self.assertTrue(any("scrim must be touch-only" in error for error in scrim_close))

    @staticmethod
    def focal_xml(checked: str, *, radio_role: bool = True) -> str:
        nodes = []
        class_name = "android.widget.RadioButton" if radio_role else "android.widget.Button"
        for index, description in enumerate(cases.FOCAL_PRESETS):
            is_checked = str(description == checked).lower()
            nodes.append(
                f'<node text="" content-desc="{description}" class="{class_name}" '
                f'checkable="true" checked="{is_checked}" selected="false" enabled="true" '
                f'clickable="true" focusable="true" '
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
        self.assertTrue(selected.clickable)
        self.assertTrue(selected.focusable)

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

    @staticmethod
    def mode_xml(checked: str, *, radio_role: bool = True) -> str:
        class_name = "android.widget.RadioButton" if radio_role else "android.widget.Button"
        nodes = []
        for index, description in enumerate(cases.CAPTURE_MODES):
            is_checked = str(description == checked).lower()
            nodes.append(
                f'<node text="" content-desc="{description}" class="{class_name}" '
                f'checkable="true" checked="{is_checked}" selected="false" enabled="true" clickable="true" '
                f'bounds="[{index * 50},0][{index * 50 + 48},48]" />'
            )
        return f"<hierarchy>{''.join(nodes)}</hierarchy>"

    def test_mode_contract_requires_one_checked_radio(self) -> None:
        self.assertIsNone(
            cases.mode_carousel_error(UiTree(self.mode_xml("Photo mode")), "Photo mode")
        )
        self.assertIn(
            "not radio buttons",
            cases.mode_carousel_error(
                UiTree(self.mode_xml("Video mode", radio_role=False)),
                "Video mode",
            ),
        )
        self.assertIn(
            "expected exactly",
            cases.mode_carousel_error(UiTree(self.mode_xml("Video mode")), "Photo mode"),
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

    def test_photo_timer_and_drive_selected_options_are_exact(self) -> None:
        xml = (
            '<hierarchy>'
            '<node text="10s" content-desc="" class="android.widget.Button" '
            'checkable="true" checked="true" selected="true" enabled="true" '
            'bounds="[0,0][48,48]" />'
            '<node text="Burst" content-desc="" class="android.widget.Button" '
            'checkable="true" checked="true" selected="true" enabled="true" '
            'bounds="[0,50][100,70]" />'
            '<node text="Single" content-desc="" class="android.widget.Button" '
            'checkable="true" checked="false" selected="false" enabled="true" '
            'bounds="[0,80][100,100]" />'
            '</hierarchy>'
        )

        drives, timers = cases.selected_photo_setting_options(UiTree(xml))

        self.assertEqual(drives, {"Burst"})
        self.assertEqual(timers, {"10s"})

    def test_settings_scrim_dismiss_point_stays_outside_anchored_panel(self) -> None:
        scrim = UiNode(
            text="",
            desc="Close settings",
            class_name="android.view.View",
            bounds=(0, 0, 1440, 3200),
            checkable=False,
            checked=False,
            selected=False,
            enabled=True,
            clickable=True,
        )
        self.assertEqual(
            cases.settings_scrim_dismiss_point(DisplayMetrics(1440, 3200, 480), scrim),
            (720, 160),
        )

        landscape_scrim = UiNode(**{**scrim.__dict__, "bounds": (0, 0, 3200, 1440)})
        self.assertEqual(
            cases.settings_scrim_dismiss_point(
                DisplayMetrics(3200, 1440, 480),
                landscape_scrim,
            ),
            (320, 720),
        )

    def test_snapshot_extension_mime_mapping_is_exact(self) -> None:
        self.assertEqual(cases.expected_image_mime("capture.heic"), "image/heic")
        self.assertEqual(cases.expected_image_mime("capture.jpg"), "image/jpeg")
        self.assertEqual(cases.expected_image_mime("capture.dng"), "image/x-adobe-dng")
        self.assertIsNone(cases.expected_image_mime("capture.mp4"))

    @staticmethod
    def video_osd_tree(spec: str, transfer: str) -> UiTree:
        nodes = []
        for index, label in enumerate((spec, transfer)):
            nodes.append(
                f'<node text="{label}" content-desc="" class="android.widget.TextView" '
                'checkable="false" checked="false" selected="false" enabled="true" '
                f'bounds="[0,{index * 20}][400,{index * 20 + 20}]" />'
            )
        return UiTree(f"<hierarchy>{''.join(nodes)}</hierarchy>")

    def test_video_target_precondition_requires_visible_2997_hevc_hlg(self) -> None:
        self.assertIsNone(
            cases.video_target_precondition_error(
                self.video_osd_tree("4K 29.97p HEVC 84Mb", "HLG")
            )
        )
        self.assertIn(
            "requires HEVC 29.97p",
            cases.video_target_precondition_error(
                self.video_osd_tree("4K 30p HEVC 84Mb", "HLG")
            ),
        )
        self.assertIn(
            "requires HLG",
            cases.video_target_precondition_error(
                self.video_osd_tree("4K 29.97p HEVC 84Mb", "SDR")
            ),
        )

    def test_video_resolution_label_and_recording_spec_crosscheck_fields(self) -> None:
        self.assertEqual(cases.video_resolution_label(3840, 2160), "4K")
        self.assertEqual(cases.video_resolution_label(4096, 3072), "4K 4:3")
        line = (
            "RecordingSpec: admitted stem=VID_TELECAM_F1_1700000000000_0000000001 "
            "codec=HEVC source=3840x2160 encoder=2160x3840 bitrate=84000000 "
            "fps=29.970029970 transfer=HLG audio=true"
        )
        match = cases.RECORDING_SPEC.search(line)
        self.assertIsNotNone(match)
        self.assertEqual(match.group(7), "84000000")
        self.assertEqual(match.group(8), "29.970029970")
        self.assertEqual(match.group(9), "HLG")

    def test_mode_3a_rejects_outgoing_and_pre_video_photo_results(self) -> None:
        log = "\n".join(
            (
                "CameraController: 3A: controllerId=1 opticsGeneration=7 "
                "requestGeneration=40 mode=PHOTO aeState=2",
                "CameraEngine: CameraSessionAccepted: controllerId=2 opticsGeneration=8 "
                "sessionGeneration=12 requestGeneration=41 mode=VIDEO cameraId=4 ready=true",
                "CameraController: 3A: controllerId=1 opticsGeneration=7 "
                "requestGeneration=41 mode=VIDEO aeState=2",
                "CameraController: 3A: controllerId=2 opticsGeneration=8 "
                "requestGeneration=42 mode=VIDEO aeState=2",
                "CameraEngine: CameraSessionAccepted: controllerId=3 opticsGeneration=9 "
                "sessionGeneration=13 requestGeneration=43 mode=PHOTO cameraId=0 ready=false",
                "CameraEngine: CameraSessionAccepted: controllerId=3 opticsGeneration=10 "
                "sessionGeneration=14 requestGeneration=43 mode=PHOTO cameraId=0 ready=true",
                "CameraController: 3A: controllerId=2 opticsGeneration=8 "
                "requestGeneration=43 mode=PHOTO aeState=2",
                "CameraController: 3A: controllerId=3 opticsGeneration=10 "
                "requestGeneration=44 mode=PHOTO aeState=2",
            )
        )

        video_acceptance = cases.newest_session_acceptance(
            log,
            "VIDEO",
            after_optics_generation=7,
        )
        self.assertIsNotNone(video_acceptance)
        self.assertEqual(video_acceptance.controller_id, 2)
        self.assertEqual(video_acceptance.optics_generation, 8)
        self.assertEqual(
            cases.newest_mode_three_a(
                log,
                "VIDEO",
                after_request_generation=40,
                controller_id=video_acceptance.controller_id,
                optics_generation=video_acceptance.optics_generation,
            ).request_generation,
            42,
        )

        photo_acceptance = cases.newest_session_acceptance(
            log,
            "PHOTO",
            after_optics_generation=8,
        )
        self.assertIsNotNone(photo_acceptance)
        self.assertEqual(photo_acceptance.optics_generation, 10)
        self.assertIsNone(
            cases.newest_mode_three_a(
                log,
                "PHOTO",
                after_request_generation=42,
                controller_id=2,
                optics_generation=10,
            )
        )
        photo_evidence = cases.newest_mode_three_a(
            log,
            "PHOTO",
            after_request_generation=42,
            controller_id=photo_acceptance.controller_id,
            optics_generation=photo_acceptance.optics_generation,
        )
        self.assertIsNotNone(photo_evidence)
        self.assertEqual(photo_evidence.request_generation, 44)


class RecordingCleanupTest(unittest.TestCase):
    @staticmethod
    def admitted_spec(
        *,
        bitrate: int = 84_000_000,
        fps: str = "29.970029970",
        encoder: str = "2160x3840",
    ):
        line = (
            "RecordingSpec: admitted stem=VID_TELECAM_F1_1700000000000_0000000042 "
            f"codec=HEVC source=3840x2160 encoder={encoder} bitrate={bitrate} "
            f"fps={fps} transfer=HLG audio=true"
        )
        return cases.RECORDING_SPEC.search(line)

    def test_recording_storage_budget_keeps_double_payload_and_one_gib_free(self) -> None:
        payload = 84_000_000 * 20 // 8
        self.assertEqual(
            cases.required_recording_free_bytes(84, 20),
            payload * 2 + cases.REC_STORAGE_RESERVE_BYTES,
        )

        required = cases.required_recording_free_bytes(84, 4)
        context = SimpleNamespace(
            adb=SimpleNamespace(free_bytes=lambda: required - 1),
            note=lambda _message: None,
        )
        with self.assertRaisesRegex(framework.Incomplete, "requires .*shared storage"):
            cases.require_recording_storage(
                context,
                84,
                4,
                label="unit recording",
            )

    def test_recording_admission_must_match_osd_and_bitrate_cap(self) -> None:
        osd = cases.VIDEO_OSD.fullmatch("4K 29.97p HEVC 84Mb")
        admitted = self.admitted_spec()
        self.assertIsNotNone(osd)
        self.assertIsNotNone(admitted)
        self.assertEqual(cases.recording_admission_errors(admitted, osd), [])

        mismatch = self.admitted_spec(bitrate=85_000_000)
        errors = cases.recording_admission_errors(mismatch, osd)
        self.assertTrue(any("bitrate" in error and "OSD" in error for error in errors))

        rounded_only = self.admitted_spec(bitrate=84_999_999)
        self.assertEqual(cases.recording_admission_errors(rounded_only, osd), [])

        below_display_bucket = self.admitted_spec(bitrate=83_999_999)
        errors = cases.recording_admission_errors(below_display_bucket, osd)
        self.assertTrue(any("bitrate" in error and "OSD" in error for error in errors))

        raster_mismatch = self.admitted_spec(encoder="1920x1080")
        errors = cases.recording_admission_errors(raster_mismatch, osd)
        self.assertTrue(any("changes the admitted raster" in error for error in errors))

        over_cap = self.admitted_spec(bitrate=251_000_000)
        errors = cases.recording_admission_errors(over_cap, osd)
        self.assertTrue(any("safety cap" in error for error in errors))

    def test_short_video_decode_requires_admitted_fps_and_frame_cadence(self) -> None:
        healthy = {
            "probe": "ffprobe",
            "video_seconds": Fraction(4),
            "frame_count": 120,
            "observed_fps": Fraction(30),
            "nominal_fps": Fraction(30),
            "minimum_frame_interval": Fraction(1, 30),
            "maximum_frame_interval": Fraction(1, 30),
        }
        cases.require_decoded_video(
            healthy,
            minimum_seconds=3.5,
            expected_fps=Fraction(30),
        )

        slow = {**healthy, "observed_fps": Fraction(293, 10)}
        with self.assertRaisesRegex(AssertionError, "observed fps is unhealthy"):
            cases.require_decoded_video(
                slow,
                minimum_seconds=3.5,
                expected_fps=Fraction(30),
            )

        dropped_frame_gap = {**healthy, "maximum_frame_interval": Fraction(1, 15)}
        with self.assertRaisesRegex(AssertionError, "cadence has a gap"):
            cases.require_decoded_video(
                dropped_frame_gap,
                minimum_seconds=3.5,
                expected_fps=Fraction(30),
            )

        short_with_two_frames = {
            **healthy,
            "video_seconds": Fraction(13, 5),
            "frame_count": 2,
        }
        with self.assertRaisesRegex(AssertionError, "decoded only 2 frames"):
            cases.require_decoded_video(
                short_with_two_frames,
                minimum_seconds=2.5,
                expected_fps=Fraction(30),
            )

    def test_stop_retries_dropped_tap_until_idle_is_proven(self) -> None:
        adb = StopRetryAdb(drops=1)

        observed = cases.stop_recording_verified(
            SimpleNamespace(adb=adb),
            123,
            timeout_s=0.1,
            poll_s=0.001,
            tap_retry_s=0,
        )

        self.assertEqual(adb.state, "idle")
        self.assertEqual(adb.tap_count, 2)
        self.assertTrue(observed)

    def test_stop_unknown_state_is_unsafe(self) -> None:
        adb = StopRetryAdb(state="unknown")

        with self.assertRaisesRegex(framework.UnsafeState, "could not prove recording stopped"):
            cases.stop_recording_verified(
                SimpleNamespace(adb=adb),
                123,
                timeout_s=0.01,
                poll_s=0.001,
                tap_retry_s=0,
            )

    def test_terminal_publish_evidence_is_required_before_continuing(self) -> None:
        success = SimpleNamespace(
            adb=FinalizationAdb(
                "I CameraEngine: RecordingFinalized: captureId=42 saved=true error=none"
            )
        )
        self.assertIn(
            "saved=true",
            cases.wait_recording_finalized(success, "mark", 123, 42),
        )

        failed = SimpleNamespace(
            adb=FinalizationAdb(
                "I CameraEngine: RecordingFinalized: captureId=42 "
                "saved=false error=IllegalStateException"
            )
        )
        with self.assertRaisesRegex(framework.UnsafeState, "did not finalize safely"):
            cases.wait_recording_finalized(failed, "mark", 123, 42)

        missing = SimpleNamespace(adb=FinalizationAdb(None))
        with self.assertRaisesRegex(framework.UnsafeState, "was not observed"):
            cases.wait_recording_finalized(missing, "mark", 123, 42)

        transport_failure = SimpleNamespace(
            adb=SimpleNamespace(
                wait_log=lambda *_args, **_kwargs: (_ for _ in ()).throw(
                    AdbError("device offline")
                )
            )
        )
        with self.assertRaisesRegex(framework.UnsafeState, "transport failed.*device offline"):
            cases.wait_recording_finalized(transport_failure, "mark", 123, 42)

    def test_cleanup_transport_failure_is_always_unsafe(self) -> None:
        with self.assertRaisesRegex(framework.UnsafeState, "mode restore.*device offline"):
            cases.cleanup_transport_or_unsafe(
                "mode restore",
                lambda: (_ for _ in ()).throw(AdbError("device offline")),
            )

    def test_cleanup_non_transport_failure_is_always_unsafe(self) -> None:
        with self.assertRaisesRegex(
            framework.UnsafeState,
            "media restore: ValueError: malformed numeric field",
        ) as raised:
            cases.cleanup_transport_or_unsafe(
                "media restore",
                lambda: (_ for _ in ()).throw(ValueError("malformed numeric field")),
            )

        self.assertIsInstance(raised.exception.__cause__, ValueError)

    def test_cleanup_preserves_existing_unsafe_state(self) -> None:
        original = framework.UnsafeState("recorder ownership is unknown")

        with self.assertRaises(framework.UnsafeState) as raised:
            cases.cleanup_transport_or_unsafe(
                "mode restore",
                lambda: (_ for _ in ()).throw(original),
            )

        self.assertIs(raised.exception, original)

    def test_late_admission_transport_failure_aborts_the_suite(self) -> None:
        context = SimpleNamespace(
            adb=SimpleNamespace(
                wait_log=lambda *_args, **_kwargs: (_ for _ in ()).throw(
                    AdbError("device offline")
                )
            )
        )

        with self.assertRaisesRegex(
            framework.UnsafeState,
            "cleanup admission recovery transport failed.*device offline",
        ):
            cases.recover_recording_admission(context, "mark", 123, "cleanup")

    def test_still_terminal_wait_accounts_for_every_replayed_tap(self) -> None:
        log = "\n".join(
            (
                "CameraController: ShutterLag: started +20 ms",
                "CameraEngine: CaptureFamily: settled "
                "stem=IMG_TELECAM_F1_1700000000000_0000000043 outputs=heic,jpg",
                "CameraController: ShutterLag: started +18 ms",
                "CameraEngine: CaptureFamily: settled "
                "stem=IMG_TELECAM_F1_1700000002000_0000000044 outputs=heic,jpg",
            )
        )
        context = SimpleNamespace(adb=SimpleNamespace(logcat_since=lambda *_args: log))

        evidence = cases.wait_still_capture_terminals(
            context,
            "mark",
            123,
            timeout_s=0.05,
            settle_s=0,
            poll_s=0,
        )

        self.assertEqual(evidence.started_count, 2)
        self.assertEqual([family.capture_id for family in evidence.families], [43, 44])

    def test_still_terminal_wait_rejects_an_unsettled_delivered_press(self) -> None:
        context = SimpleNamespace(
            adb=SimpleNamespace(
                logcat_since=lambda *_args: "CameraController: ShutterLag: started +20 ms"
            )
        )

        with self.assertRaisesRegex(framework.UnsafeState, "terminal state was not proven"):
            cases.wait_still_capture_terminals(
                context,
                "mark",
                123,
                timeout_s=0.002,
                settle_s=0,
                poll_s=0,
            )

    def test_still_terminal_transport_failure_aborts_the_suite(self) -> None:
        context = SimpleNamespace(
            adb=SimpleNamespace(
                logcat_since=lambda *_args: (_ for _ in ()).throw(AdbError("offline"))
            )
        )

        with self.assertRaisesRegex(
            framework.UnsafeState,
            "still terminal transport failed.*offline",
        ):
            cases.wait_still_capture_terminals(
                context,
                "mark",
                123,
                timeout_s=0.01,
                settle_s=0,
                poll_s=0,
            )


class MediaStoreTest(unittest.TestCase):
    def test_existing_media_row_metadata_must_remain_present_and_stable(self) -> None:
        first = media_row(1)
        second = media_row(2, collection="video")
        self.assertEqual(cases.existing_media_regressions([first], [first, second]), [])

        changed = MediaRow(**{**first.__dict__, "size_bytes": first.size_bytes + 1})
        regressions = cases.existing_media_regressions([first, second], [changed])
        self.assertTrue(any("changed" in error and "('image', 1)" in error for error in regressions))
        self.assertTrue(any("disappeared" in error and "('video', 2)" in error for error in regressions))

    def test_expected_multi_family_wait_exposes_duplicates_and_late_delta(self) -> None:
        baseline = media_row(1)
        first = media_row(2, collection="video", family_sequence=42)
        duplicate = media_row(3, collection="video", family_sequence=42)
        with tempfile.TemporaryDirectory() as temp_dir:
            adb = SequenceMediaAdb(
                Path(temp_dir),
                [[baseline, first, duplicate], [baseline, first, duplicate]],
            )
            rows = cases.wait_expected_new_media_rows(
                SimpleNamespace(adb=adb),
                {baseline.key},
                {first.display_name},
                timeout_s=0.05,
                settle_s=0,
                poll_s=0,
            )

        self.assertEqual(rows, [first, duplicate])
        self.assertIsNotNone(
            cases.exact_media_delta_error(
                {baseline.key},
                {first.key},
                [baseline, first, duplicate],
            )
        )

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

    def test_known_video_row_can_publish_alongside_a_snapshot_family(self) -> None:
        pending_video = media_row(7, collection="video", pending=True, size_bytes=0)
        ready_video = media_row(7, collection="video")
        snapshot = media_row(8)
        with tempfile.TemporaryDirectory() as temp_dir:
            adb = SequenceMediaAdb(
                Path(temp_dir),
                [
                    [pending_video],
                    [ready_video, snapshot],
                    [ready_video, snapshot],
                ],
            )

            row = adb.wait_published_media_row(("video", 7), timeout_s=1, settle_s=0)

        self.assertEqual(row, ready_video)

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


class VideoProbeTest(unittest.TestCase):
    @staticmethod
    def ffprobe_fixture() -> tuple[dict, dict]:
        frame_count = 1_948
        header = {
            "streams": [
                {"index": 0, "codec_type": "video"},
                {
                    "index": 1,
                    "codec_type": "audio",
                    "codec_name": "aac",
                    "profile": None,
                    "sample_rate": "48000",
                    "channels": 2,
                    "duration": "65.000000",
                },
            ],
            "format": {"duration": "65.000000", "size": "700000000"},
        }
        scan = {
            "streams": [
                {
                    "index": 0,
                    "codec_type": "video",
                    "codec_name": "hevc",
                    "profile": "Main 10",
                    "pix_fmt": "yuv420p10le",
                    "width": 2160,
                    "height": 3840,
                    "r_frame_rate": "30000/1001",
                    "avg_frame_rate": "30000/1001",
                    "time_base": "1/30000",
                    "duration_ts": str(frame_count * 1001),
                    "nb_frames": str(frame_count),
                    "nb_read_frames": str(frame_count),
                    "color_range": "tv",
                    "color_space": "bt2020nc",
                    "color_transfer": "arib-std-b67",
                    "color_primaries": "bt2020",
                },
                {
                    "index": 1,
                    "codec_type": "audio",
                    "codec_name": "aac",
                    "profile": None,
                    "sample_rate": "48000",
                    "channels": 2,
                    "time_base": "1/48000",
                    "duration_ts": str(3_047 * 1_024),
                    "duration": "65.002667",
                    "nb_frames": "N/A",
                    "nb_read_frames": "3047",
                },
            ],
            "frames": (
                [
                    {
                        "media_type": "video",
                        "stream_index": 0,
                        "best_effort_timestamp": str(index * 1001),
                    }
                    for index in range(frame_count)
                ]
                + [
                    {
                        "media_type": "audio",
                        "stream_index": 1,
                        "best_effort_timestamp": str(index * 1024),
                        "nb_samples": 1024,
                    }
                    for index in range(3_047)
                ]
            ),
        }
        return header, scan

    def test_strict_hlg_probe_uses_decoded_pts_and_exact_signalling(self) -> None:
        header, scan = self.ffprobe_fixture()

        info = media.parse_ffprobe_payload(header, scan)

        self.assertEqual(info["nominal_fps"], Fraction(30_000, 1_001))
        self.assertEqual(info["observed_fps"], Fraction(30_000, 1_001))
        self.assertEqual(info["frame_count"], 1_948)
        self.assertEqual(
            media.hlg_2997_errors(
                info,
                expected_width=2160,
                expected_height=3840,
                expected_audio=True,
            ),
            [],
        )

    def test_strict_hlg_probe_rejects_pq_30p_and_missing_decode(self) -> None:
        header, scan = self.ffprobe_fixture()
        broken = copy.deepcopy(scan)
        broken["streams"][0]["color_transfer"] = "smpte2084"
        broken["streams"][0]["r_frame_rate"] = "30/1"
        info = media.parse_ffprobe_payload(header, broken)

        errors = media.hlg_2997_errors(
            info,
            expected_width=2160,
            expected_height=3840,
            expected_audio=True,
        )

        self.assertTrue(any("transfer" in error for error in errors))
        self.assertTrue(any("nominal_fps" in error for error in errors))
        self.assertEqual(
            media.hlg_2997_errors(
                {"probe": "structural"},
                expected_width=2160,
                expected_height=3840,
                expected_audio=True,
            ),
            ["ffprobe frame decoding was unavailable"],
        )

    def test_ffprobe_parser_rejects_non_monotonic_or_uncounted_frames(self) -> None:
        header, scan = self.ffprobe_fixture()
        non_monotonic = copy.deepcopy(scan)
        non_monotonic["frames"][2]["best_effort_timestamp"] = "1001"
        with self.assertRaisesRegex(ValueError, "not strictly increasing"):
            media.parse_ffprobe_payload(header, non_monotonic)

        uncounted = copy.deepcopy(scan)
        uncounted["streams"][0]["nb_read_frames"] = "1947"
        with self.assertRaisesRegex(ValueError, "decoded frame count mismatch"):
            media.parse_ffprobe_payload(header, uncounted)

    def test_strict_probe_rejects_dropped_video_gap_and_audio_offset(self) -> None:
        header, scan = self.ffprobe_fixture()
        dropped = copy.deepcopy(scan)
        for frame in dropped["frames"][1_000:1_948]:
            frame["best_effort_timestamp"] = str(int(frame["best_effort_timestamp"]) + 1001)
        dropped_info = media.parse_ffprobe_payload(header, dropped)
        dropped_errors = media.hlg_2997_errors(
            dropped_info,
            expected_width=2160,
            expected_height=3840,
            expected_audio=True,
        )
        self.assertTrue(any("maximum_frame_interval" in error for error in dropped_errors))

        delayed_audio = copy.deepcopy(scan)
        for frame in delayed_audio["frames"][1_948:]:
            frame["best_effort_timestamp"] = str(
                int(frame["best_effort_timestamp"]) + 48_000
            )
        delayed_info = media.parse_ffprobe_payload(header, delayed_audio)
        delayed_errors = media.hlg_2997_errors(
            delayed_info,
            expected_width=2160,
            expected_height=3840,
            expected_audio=True,
        )
        self.assertTrue(any("A/V start delta" in error for error in delayed_errors))
        self.assertTrue(any("A/V end delta" in error for error in delayed_errors))

    def test_ffprobe_parser_requires_counted_audio_frames(self) -> None:
        header, scan = self.ffprobe_fixture()
        scan["streams"][1]["nb_read_frames"] = "3046"

        with self.assertRaisesRegex(ValueError, "decoded audio count mismatch"):
            media.parse_ffprobe_payload(header, scan)


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

    def test_launch_requires_destructive_guard_because_startup_can_reclaim_pending(self) -> None:
        adb, temp_dir = self.make_adb(allowed=False, descriptions={"Take photo"})
        self.addCleanup(temp_dir.cleanup)

        with self.assertRaisesRegex(AdbError, "startup may reclaim"):
            adb.launch(wait_s=0)

        self.assertFalse(any("am start" in command for command in adb.commands))

    def test_launch_runs_only_with_explicit_destructive_guard(self) -> None:
        adb, temp_dir = self.make_adb(allowed=True, descriptions={"Take photo"})
        self.addCleanup(temp_dir.cleanup)

        self.assertEqual(adb.launch(wait_s=0), 123)

        self.assertTrue(any("am start" in command for command in adb.commands))

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

    def test_read_only_foreground_helper_skips_without_launching(self) -> None:
        adb, temp_dir = self.make_adb(allowed=False, descriptions=set())
        self.addCleanup(temp_dir.cleanup)
        context = framework.Context(adb, Path(temp_dir.name), can_launch=False)

        with self.assertRaisesRegex(
            framework.Skip,
            "app is not already foreground; read-only case does not launch it",
        ):
            cases.ensure_foreground(context)

        self.assertFalse(any("am start" in command for command in adb.commands))

    def test_declared_and_approved_launch_capability_restores_foreground(self) -> None:
        adb, temp_dir = self.make_adb(allowed=True, descriptions=set())
        self.addCleanup(temp_dir.cleanup)
        context = framework.Context(adb, Path(temp_dir.name), can_launch=True)

        def launch_without_wait(wait_s: float = 5.0) -> int:
            del wait_s
            adb.commands.append(f"am start -n {APP_ID}/com.hletrd.findx9tele.MainActivity")
            return 123
        adb.launch = launch_without_wait  # type: ignore[method-assign]

        self.assertEqual(cases.ensure_foreground(context), 123)

        self.assertTrue(any("am start" in command for command in adb.commands))


class FrameworkSafetyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_registry = list(framework._REGISTRY)
        framework._REGISTRY.clear()

    def tearDown(self) -> None:
        framework._REGISTRY[:] = self.original_registry

    def run_suite(
        self,
        *,
        allow_destructive: bool = False,
        allow_settings: bool = False,
        allow_media_writes: bool = False,
        name_filter: str | None = None,
    ) -> int:
        with tempfile.TemporaryDirectory() as temp_dir:
            return framework.run(
                FakeAdb(),
                ["smoke"],
                name_filter,
                Path(temp_dir),
                allow_destructive=allow_destructive,
                allow_settings=allow_settings,
                allow_media_writes=allow_media_writes,
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
        launch_capability = None

        @framework.test("guarded", "smoke", destructive=True)
        def guarded(ctx) -> None:
            nonlocal launch_capability
            launch_capability = ctx.can_launch

        self.assertEqual(self.run_suite(allow_destructive=True), 0)
        self.assertTrue(launch_capability)

    def test_settings_and_media_approvals_are_independent_and_both_required(self) -> None:
        called = False

        @framework.test(
            "guarded",
            "smoke",
            mutates_settings=True,
            writes_media=True,
        )
        def guarded(_ctx) -> None:
            nonlocal called
            called = True

        self.assertEqual(self.run_suite(allow_settings=True), 2)
        self.assertEqual(self.run_suite(allow_media_writes=True), 2)
        self.assertFalse(called)
        self.assertEqual(
            self.run_suite(allow_settings=True, allow_media_writes=True),
            0,
        )
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

    def test_unsafe_cleanup_aborts_every_later_case(self) -> None:
        later_called = False

        @framework.test("unsafe", "smoke")
        def unsafe(_ctx) -> None:
            raise framework.UnsafeState("REC state unknown")

        @framework.test("later", "smoke")
        def later(_ctx) -> None:
            nonlocal later_called
            later_called = True

        self.assertEqual(self.run_suite(), 1)
        self.assertFalse(later_called)


if __name__ == "__main__":
    unittest.main()
