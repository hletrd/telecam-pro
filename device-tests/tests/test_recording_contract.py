from __future__ import annotations

import sys
import unittest
from fractions import Fraction
from pathlib import Path

DEVICE_TESTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEVICE_TESTS))

from dtest import media  # noqa: E402


def valid_info(*, codec: str = "hevc", audio: bool = True) -> dict:
    return {
        "probe": "ffprobe",
        "video_stream_count": 1,
        "audio_stream_count": 1 if audio else 0,
        "codec": codec,
        "width": 2160,
        "height": 3840,
        "video_start": Fraction(0),
        "video_end": Fraction(4),
        "audio": (
            {
                "codec": "aac",
                "sample_rate": 44_100,
                "channels": 1,
                "start": Fraction(1, 100),
                "end": Fraction(399, 100),
            }
            if audio
            else None
        ),
    }


class RecordingContractTests(unittest.TestCase):
    def test_recording_spec_codecs_map_to_ffprobe_names(self) -> None:
        for recording_codec, ffprobe_codec in (
            ("HEVC", "hevc"),
            ("AVC", "h264"),
            ("APV", "apv"),
        ):
            with self.subTest(recording_codec=recording_codec):
                self.assertEqual(
                    media.recording_contract_errors(
                        valid_info(codec=ffprobe_codec),
                        expected_codec=recording_codec,
                        expected_width=2160,
                        expected_height=3840,
                        expected_audio=True,
                    ),
                    [],
                )

    def test_video_stream_codec_and_encoder_dimensions_are_exact(self) -> None:
        info = valid_info(codec="h264", audio=False)
        info.update(video_stream_count=2, width=3840, height=2160)

        errors = media.recording_contract_errors(
            info,
            expected_codec="HEVC",
            expected_width=2160,
            expected_height=3840,
            expected_audio=False,
        )

        self.assertIn("video_stream_count=2, expected 1", errors)
        self.assertIn("codec='h264', expected 'hevc'", errors)
        self.assertIn("width=3840, expected 2160", errors)
        self.assertIn("height=2160, expected 3840", errors)

    def test_audio_enabled_requires_one_aac_stream(self) -> None:
        missing = valid_info(audio=False)
        self.assertEqual(
            media.recording_contract_errors(
                missing,
                expected_codec="HEVC",
                expected_width=2160,
                expected_height=3840,
                expected_audio=True,
            ),
            ["audio_stream_count=0, expected one AAC stream"],
        )

        wrong_codec = valid_info()
        wrong_codec["audio"]["codec"] = "opus"
        errors = media.recording_contract_errors(
            wrong_codec,
            expected_codec="HEVC",
            expected_width=2160,
            expected_height=3840,
            expected_audio=True,
        )
        self.assertIn("audio.codec='opus', expected 'aac'", errors)

    def test_audio_enabled_requires_reasonable_start_and_end_alignment(self) -> None:
        info = valid_info()
        info["audio"]["start"] = Fraction(1, 2)
        info["audio"]["end"] = Fraction(7, 2)

        errors = media.recording_contract_errors(
            info,
            expected_codec="HEVC",
            expected_width=2160,
            expected_height=3840,
            expected_audio=True,
        )

        self.assertIn("A/V start delta=0.500s, expected <=0.25s", errors)
        self.assertIn("A/V end delta=0.500s, expected <=0.25s", errors)

        info["audio"]["start"] = None
        errors = media.recording_contract_errors(
            info,
            expected_codec="HEVC",
            expected_width=2160,
            expected_height=3840,
            expected_audio=True,
        )
        self.assertIn("decoded A/V start/end timestamps are unavailable", errors)

    def test_audio_disabled_requires_zero_audio_streams(self) -> None:
        errors = media.recording_contract_errors(
            valid_info(),
            expected_codec="HEVC",
            expected_width=2160,
            expected_height=3840,
            expected_audio=False,
        )

        self.assertEqual(errors, ["audio_stream_count=1, expected no audio"])

    def test_ffprobe_and_recording_codec_are_required(self) -> None:
        self.assertEqual(
            media.recording_contract_errors(
                {"probe": "structural"},
                expected_codec="HEVC",
                expected_width=2160,
                expected_height=3840,
                expected_audio=False,
            ),
            ["ffprobe frame decoding was unavailable"],
        )
        with self.assertRaisesRegex(ValueError, "unsupported RecordingSpec codec"):
            media.recording_contract_errors(
                valid_info(audio=False),
                expected_codec="VP9",
                expected_width=2160,
                expected_height=3840,
                expected_audio=False,
            )


if __name__ == "__main__":
    unittest.main()
