"""Host self-tests for the cycle-7 data-validity/binning validators (pure parsers only)."""

from __future__ import annotations

import struct
import sys
import tempfile
import unittest
from fractions import Fraction
from pathlib import Path

DEVICE_TESTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEVICE_TESTS))

import cases  # noqa: E402
from dtest import media  # noqa: E402


DUMPSYS_SNIPPET = """
== Camera HAL device device@1.1/vendor_qti/0 (v1.3) static information: ==
  Resource cost: 66
      android.lens.facing (80005): byte[1]
        [BACK ]
      android.logicalMultiCamera.physicalIds (1a0000): byte[8]
        [3 2 4 5 ]
      android.sensor.info.activeArraySize (f0000): int32[4]
        [0 0 4080 3064 ]
      android.sensor.info.pixelArraySize (f0006): int32[2]
        [4080 3064 ]
      android.scaler.availableStreamConfigurations (d000a): int32[16]
        [35 4080 3064 OUTPUT ]
        [32 4080 3064 OUTPUT ]
        [33 4080 3064 OUTPUT ]
        [32 4080 3064 INPUT ]
== Camera HAL device device@1.1/vendor_qti/1 (v1.3) static information: ==
  Resource cost: 33
      android.lens.facing (80005): byte[1]
        [FRONT ]
      android.sensor.info.activeArraySize (f0000): int32[4]
        [0 0 4096 3072 ]
      android.sensor.info.pixelArraySize (f0006): int32[2]
        [4096 3072 ]
      android.scaler.availableStreamConfigurations (d000a): int32[8]
        [35 4096 3072 OUTPUT ]
        [32 4096 3072 OUTPUT ]
"""


class CameraGeometryParserTest(unittest.TestCase):
    def test_parses_per_camera_sections(self) -> None:
        cameras = cases.parse_camera_geometry(DUMPSYS_SNIPPET)
        self.assertEqual(sorted(cameras), ["0", "1"])
        logical = cameras["0"]
        self.assertEqual(logical.facing, "BACK")
        self.assertEqual(logical.pixel_array, (4080, 3064))
        self.assertEqual(logical.active_array, (4080, 3064))
        self.assertEqual(logical.raw16_sizes, ((4080, 3064),))  # OUTPUT only, INPUT dropped
        self.assertEqual(logical.physical_ids, ("3", "2", "4", "5"))
        front = cameras["1"]
        self.assertEqual(front.facing, "FRONT")
        self.assertEqual(front.pixel_array, (4096, 3072))
        self.assertEqual(front.physical_ids, ())

    def test_route_pickers(self) -> None:
        cameras = cases.parse_camera_geometry(DUMPSYS_SNIPPET)
        self.assertEqual(cases.rear_logical_geometry(cameras).camera_id, "0")
        self.assertEqual(cases.front_camera_geometry(cameras).camera_id, "1")

    def test_malformed_section_is_skipped(self) -> None:
        broken = DUMPSYS_SNIPPET.replace("[FRONT ]", "[]")
        cameras = cases.parse_camera_geometry(broken)
        self.assertEqual(sorted(cameras), ["0"])


class ShutterReadoutTest(unittest.TestCase):
    def test_fractional_and_decimal_forms(self) -> None:
        self.assertEqual(cases.shutter_readout_seconds("1/100s"), Fraction(1, 100))
        self.assertEqual(cases.shutter_readout_seconds("Auto 1/50s"), Fraction(1, 50))
        self.assertEqual(cases.shutter_readout_seconds("0.5s"), Fraction(1, 2))
        self.assertEqual(cases.shutter_readout_seconds("4.0s"), Fraction(4))
        self.assertEqual(cases.shutter_readout_seconds("10s"), Fraction(10))

    def test_non_shutter_labels_do_not_parse(self) -> None:
        for label in ("1.0×", "ISO 800", "HEIF+JPEG", "10× lens", "1/100"):
            self.assertIsNone(cases.shutter_readout_seconds(label))


class ExposureParityTest(unittest.TestCase):
    def test_within_rounding_passes(self) -> None:
        self.assertIsNone(
            cases.exposure_parity_error("DNG", Fraction(9843133, 1_000_000_000), 9_843_133)
        )
        # ExifInterface stores a decimal string; sub-1% drift is format rounding.
        self.assertIsNone(
            cases.exposure_parity_error("JPEG", Fraction(9_850, 1_000_000), 9_843_133)
        )

    def test_off_by_a_stop_fails(self) -> None:
        error = cases.exposure_parity_error("DNG", Fraction(1, 50), 10_000_000)
        self.assertIsNotNone(error)
        self.assertIn("DNG", error)

    def test_missing_exposure_fails(self) -> None:
        self.assertIsNotNone(cases.exposure_parity_error("JPEG", None, 10_000_000))


def _color_info(**overrides) -> dict:
    info = {
        "probe": "ffprobe",
        "profile": "Main 10",
        "pix_fmt": "yuv420p10le",
        "color_range": "tv",
        "color_space": "bt2020nc",
        "primaries": "bt2020",
        "transfer": "arib-std-b67",
    }
    info.update(overrides)
    return info


class VideoContainerPolicyTest(unittest.TestCase):
    def test_hlg_policy_passes_and_pins_transfer(self) -> None:
        self.assertEqual(cases.video_container_policy_errors(_color_info(), "HLG"), [])
        errors = cases.video_container_policy_errors(
            _color_info(transfer="smpte2084"), "HLG"
        )
        self.assertTrue(any("transfer" in error for error in errors))

    def test_sdr_policy(self) -> None:
        sdr = _color_info(
            profile="Main",
            pix_fmt="yuv420p",
            color_space="bt709",
            primaries="bt709",
            transfer="bt709",
        )
        self.assertEqual(cases.video_container_policy_errors(sdr, "SDR"), [])
        errors = cases.video_container_policy_errors(
            dict(sdr, transfer="arib-std-b67"), "SDR"
        )
        self.assertTrue(any("SDR-class" in error for error in errors))

    def test_log_policy_guards_the_pq_mistag(self) -> None:
        log = _color_info(color_range="pc", transfer="bt2020-10")
        self.assertEqual(cases.video_container_policy_errors(log, "SLOG3_CINE"), [])
        mistag = cases.video_container_policy_errors(
            dict(log, transfer="smpte2084"), "SLOG3_CINE"
        )
        self.assertTrue(any("mistag" in error for error in mistag))
        limited = cases.video_container_policy_errors(
            dict(log, color_range="tv"), "LOGC3"
        )
        self.assertTrue(any("color_range" in error for error in limited))

    def test_unknown_spec_raises(self) -> None:
        with self.assertRaises(ValueError):
            cases.video_container_policy_errors(_color_info(), "P3")

    def test_structural_fallback_is_non_green(self) -> None:
        errors = cases.video_container_policy_errors({"probe": "structural"}, "HLG")
        self.assertTrue(errors)


def _ifd(entries: list[bytes], next_offset: int) -> bytes:
    return (
        struct.pack("<H", len(entries))
        + b"".join(entries)
        + struct.pack("<I", next_offset)
    )


def _short_entry(tag: int, value: int) -> bytes:
    return struct.pack("<HHI", tag, 3, 1) + struct.pack("<HH", value, 0)


def _long_entry(tag: int, value: int) -> bytes:
    return struct.pack("<HHI", tag, 4, 1) + struct.pack("<I", value)


def _rational_entry(tag: int, offset: int) -> bytes:
    return struct.pack("<HHI", tag, 5, 1) + struct.pack("<I", offset)


def synthetic_dng_bytes() -> bytes:
    """Little-endian TIFF: IFD0 RGB thumbnail + SubIFD 16-bit CFA plane + Exif IFD facts."""
    off_ifd0 = 8
    ifd0_entries = [
        _short_entry(0x0100, 256),
        _short_entry(0x0101, 192),
        _short_entry(0x0106, 2),
        _long_entry(0x014A, 0),  # patched below
        _long_entry(0x8769, 0),  # patched below
    ]
    off_sub = off_ifd0 + 2 + len(ifd0_entries) * 12 + 4
    sub_entries = [
        _short_entry(0x0100, 4096),
        _short_entry(0x0101, 3072),
        _short_entry(0x0102, 16),
        _short_entry(0x0115, 1),
        _short_entry(0x0106, 32803),
    ]
    off_exif = off_sub + 2 + len(sub_entries) * 12 + 4
    exif_entries = [
        _short_entry(0x8827, 800),
        _rational_entry(0x829A, 0),  # patched below
    ]
    off_data = off_exif + 2 + len(exif_entries) * 12 + 4
    ifd0_entries[3] = _long_entry(0x014A, off_sub)
    ifd0_entries[4] = _long_entry(0x8769, off_exif)
    exif_entries[1] = _rational_entry(0x829A, off_data)
    return (
        b"II*\x00"
        + struct.pack("<I", off_ifd0)
        + _ifd(ifd0_entries, 0)
        + _ifd(sub_entries, 0)
        + _ifd(exif_entries, 0)
        + struct.pack("<II", 9_843_133, 1_000_000_000)
    )


class TiffParserTest(unittest.TestCase):
    def test_selects_the_cfa_plane_over_the_thumbnail(self) -> None:
        info = media.tiff_image_info(synthetic_dng_bytes())
        self.assertEqual((info["width"], info["height"]), (4096, 3072))
        self.assertEqual(info["bits_per_sample"], 16)
        self.assertEqual(info["samples_per_pixel"], 1)
        self.assertTrue(info["cfa"])
        self.assertEqual(info["ifd_count"], 3)
        self.assertEqual(info["iso"], 800)
        self.assertEqual(info["exposure_time"], Fraction(9_843_133, 1_000_000_000))

    def test_truncated_payload_fails_loudly(self) -> None:
        blob = synthetic_dng_bytes()
        with self.assertRaises(ValueError):
            media.tiff_image_info(blob[:20])
        with self.assertRaises(ValueError):
            media.tiff_image_info(b"XX*\x00\x00\x00\x00\x00")

    def test_self_referential_ifd_terminates(self) -> None:
        # IFD0 whose next-IFD pointer loops back to itself must not spin.
        entries = [_short_entry(0x0100, 10), _short_entry(0x0101, 10)]
        blob = b"II*\x00" + struct.pack("<I", 8) + _ifd(entries, 8)
        info = media.tiff_image_info(blob)
        self.assertEqual((info["width"], info["height"]), (10, 10))


class JpegExifTest(unittest.TestCase):
    def test_reads_iso_and_exposure_from_app1(self) -> None:
        tiff = synthetic_dng_bytes()
        app1 = b"Exif\x00\x00" + tiff
        jpeg = (
            b"\xff\xd8"
            + b"\xff\xe1"
            + struct.pack(">H", len(app1) + 2)
            + app1
            + b"\xff\xd9"
        )
        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as handle:
            handle.write(jpeg)
            path = Path(handle.name)
        try:
            info = media.jpeg_exif_info(path)
            self.assertEqual(info["iso"], 800)
            self.assertEqual(info["exposure_time"], Fraction(9_843_133, 1_000_000_000))
        finally:
            path.unlink()

    def test_jpeg_without_app1_fails(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as handle:
            handle.write(b"\xff\xd8\xff\xd9")
            path = Path(handle.name)
        try:
            with self.assertRaises(ValueError):
                media.jpeg_exif_info(path)
        finally:
            path.unlink()


class RowFileParityTest(unittest.TestCase):
    def test_video_row_parity_checks_size_dims_duration(self) -> None:
        row = cases.MediaRow(
            collection="video",
            row_id=7,
            display_name="VID_TELECAM_F1_0000000000000_0000000001.mp4",
            mime_type="video/mp4",
            relative_path="DCIM/X9Tele/",
            is_pending=False,
            width=3840,
            height=2160,
            size_bytes=100,
            duration_ms=6_000,
            owner_package_name="me.hletrd.telecampro.debug",
        )
        with tempfile.NamedTemporaryFile(delete=False) as handle:
            handle.write(b"x" * 100)
            path = Path(handle.name)
        info = {
            "format_size": 100,
            "width": 3840,
            "height": 2160,
            "video_seconds": Fraction(6),
        }
        try:
            self.assertEqual(cases.video_row_parity_errors(row, path, info), [])
            wrong = dict(info, width=1920)
            self.assertTrue(cases.video_row_parity_errors(row, path, wrong))
            drifted = dict(info, video_seconds=Fraction(8))
            self.assertTrue(cases.video_row_parity_errors(row, path, drifted))
        finally:
            path.unlink()


if __name__ == "__main__":
    unittest.main()
