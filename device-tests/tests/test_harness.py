from __future__ import annotations

import copy
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
    MediaRow,
    UiTree,
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
