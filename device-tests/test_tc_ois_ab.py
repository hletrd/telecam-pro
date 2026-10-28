"""Synthetic, device-independent tests for the TC stabilization A/B analyzer."""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

DEVICE_TESTS = Path(__file__).resolve().parent
sys.path.insert(0, str(DEVICE_TESTS))

from tc_ois_ab import (  # noqa: E402
    analyze_frame_pair,
    analyze_trial,
    analyze_videos,
    decode_luminance_crop,
    estimate_translation,
    measure_directional_blur,
)


def _chart() -> np.ndarray:
    """A non-periodic, two-dimensional chart with detail on both axes."""
    rng = np.random.default_rng(20261028)
    noise = rng.normal(size=(256, 288))
    textured = (
        noise
        + 0.45 * np.roll(noise, 1, axis=0)
        + 0.45 * np.roll(noise, -1, axis=1)
        + 0.20 * np.roll(np.roll(noise, 2, axis=0), -2, axis=1)
    )
    textured -= textured.min()
    textured /= textured.max()
    return 0.05 + 0.60 * textured


def _crop_pair(dx: int, dy: int) -> tuple[np.ndarray, np.ndarray]:
    chart = _chart()
    # Moving the crop origin opposite the requested vector keeps the chart's
    # content moving by +(dx, dy) inside the fixed candidate frame.
    reference = chart[48:176, 56:216]
    candidate = chart[48 - dy : 176 - dy, 56 - dx : 216 - dx]
    return reference, candidate


def _motion_blur(image: np.ndarray, length: int, axis: str) -> np.ndarray:
    kernel = np.full(length, 1.0 / length)
    radius = length // 2
    if axis == "x":
        padded = np.pad(image, ((0, 0), (radius, radius)), mode="reflect")
        return np.apply_along_axis(lambda row: np.convolve(row, kernel, mode="valid"), 1, padded)
    if axis == "y":
        padded = np.pad(image, ((radius, radius), (0, 0)), mode="reflect")
        return np.apply_along_axis(lambda row: np.convolve(row, kernel, mode="valid"), 0, padded)
    raise ValueError(axis)


def _write_gray_video(path: Path, frames: np.ndarray, fps: int = 2) -> None:
    height, width = frames.shape[-2:]
    subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "gray", "-s", f"{width}x{height}",
            "-r", str(fps), "-i", "-", "-frames:v", str(len(frames)),
            "-c:v", "ffv1", str(path),
        ],
        input=frames.astype(np.uint8).tobytes(),
        check=True,
    )


@pytest.mark.parametrize(
    ("dx", "dy"),
    [(12, -7), (-11, 6), (0, 9), (14, 0), (-4, -5)],
)
def test_translation_recovers_sign_and_magnitude_within_one_pixel(dx: int, dy: int) -> None:
    reference, candidate = _crop_pair(dx, dy)
    estimate = estimate_translation(reference, candidate)
    assert estimate.dx == pytest.approx(dx, abs=1.0)
    assert estimate.dy == pytest.approx(dy, abs=1.0)
    assert estimate.confident


def test_known_feature_motion_uses_content_coordinates() -> None:
    reference = np.zeros((96, 128), dtype=np.float64)
    candidate = np.zeros_like(reference)
    reference[40:48, 50:58] = 1.0
    # The same square moves 12 px right and 7 px up in the candidate.
    candidate[33:41, 62:70] = 1.0
    estimate = estimate_translation(reference, candidate)
    assert estimate.dx == pytest.approx(12.0, abs=1.0)
    assert estimate.dy == pytest.approx(-7.0, abs=1.0)


def test_translation_and_directional_blur_are_measured_together() -> None:
    reference, candidate = _crop_pair(12, -7)
    blurred = _motion_blur(candidate, length=17, axis="x")
    result = analyze_frame_pair(reference, blurred)
    assert result.verdict == "JUDGEABLE"
    assert result.translation.dx == pytest.approx(12.0, abs=1.0)
    assert result.translation.dy == pytest.approx(-7.0, abs=1.0)
    assert result.blur.directional
    assert result.blur.axis_degrees == pytest.approx(0.0, abs=10.0)


@pytest.mark.parametrize("axis", ["x", "y"])
def test_directional_blur_is_distinguished_from_sharp_control(axis: str) -> None:
    reference, _ = _crop_pair(0, 0)
    blurred = _motion_blur(reference, length=17, axis=axis)
    sharp_metrics = measure_directional_blur(reference, reference)
    blur_metrics = measure_directional_blur(reference, blurred)
    assert not sharp_metrics.directional
    assert blur_metrics.directional
    assert blur_metrics.attenuation < sharp_metrics.attenuation
    expected_axis = 0.0 if axis == "x" else 90.0
    assert blur_metrics.axis_degrees == pytest.approx(expected_axis, abs=10.0)


def test_equally_exposed_sharp_control_is_not_a_stabilization_win() -> None:
    reference, _ = _crop_pair(0, 0)
    result = analyze_frame_pair(reference, reference)
    assert result.verdict == "JUDGEABLE"
    assert not result.blur.directional
    assert abs(result.translation.dx) <= 1.0
    assert abs(result.translation.dy) <= 1.0


def test_exposure_mismatch_is_unjudgeable() -> None:
    reference, candidate = _crop_pair(12, -7)
    result = analyze_frame_pair(reference, candidate * 1.4)
    assert result.verdict == "UNJUDGEABLE"
    assert result.reason == "EXPOSURE_MISMATCH"
    assert result.exposure_ratio == pytest.approx(1.4, rel=0.01)


def test_insufficient_texture_is_unjudgeable() -> None:
    flat = np.zeros((128, 160), dtype=np.float64)
    result = analyze_frame_pair(flat, flat)
    assert result.verdict == "UNJUDGEABLE"
    assert result.reason == "INSUFFICIENT_TEXTURE"
    assert result.texture_energy == 0.0


def test_trial_aggregates_per_frame_measurements() -> None:
    reference_a, candidate_a = _crop_pair(12, -7)
    reference_b, candidate_b = _crop_pair(10, -6)
    result = analyze_trial(
        np.stack((reference_a, reference_b)),
        np.stack((candidate_a, candidate_b)),
    )
    assert result.verdict == "JUDGEABLE"
    assert result.dx == pytest.approx(11.0, abs=1.0)
    assert result.dy == pytest.approx(-6.5, abs=1.0)
    assert len(result.frames) == 2


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="FFmpeg is required for decode coverage")
def test_ffmpeg_decodes_requested_luminance_crop(tmp_path: Path) -> None:
    video = tmp_path / "chart.mkv"
    yy, xx = np.mgrid[:80, :96]
    frames = np.stack([(xx + 2 * yy + frame) % 256 for frame in range(4)]).astype(np.uint8)
    subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "gray", "-s", "96x80", "-r", "2",
            "-i", "-", "-frames:v", "4", "-c:v", "ffv1", str(video),
        ],
        input=frames.tobytes(),
        check=True,
    )
    decoded = decode_luminance_crop(video, crop=(8, 12, 48, 40))
    assert decoded.shape == (4, 40, 48)
    expected = frames[:, 12:52, 8:56].astype(np.float64) / 255.0
    np.testing.assert_allclose(decoded, expected, atol=1.0 / 255.0)


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="FFmpeg is required for decode coverage")
def test_duration_window_is_decoded_before_sampling(tmp_path: Path) -> None:
    reference_video = tmp_path / "reference.mkv"
    candidate_video = tmp_path / "candidate.mkv"
    reference, _ = _crop_pair(0, 0)
    candidates = [_crop_pair(dx, 0)[1] for dx in (0, 0, 0, 12)]
    reference_frames = np.stack([reference] * 4)
    candidate_frames = np.stack(candidates)
    _write_gray_video(reference_video, np.rint(reference_frames * 255.0))
    _write_gray_video(candidate_video, np.rint(candidate_frames * 255.0))

    # Two samples across the complete 2 s / four-frame window are the first
    # (0 px) and last (+12 px) frames. Decoding only the first two would read 0.
    result = analyze_videos(
        reference_video,
        candidate_video,
        crop=(0, 0, 160, 128),
        duration_s=2.0,
        max_frames=2,
    )
    assert result.verdict == "JUDGEABLE"
    assert len(result.frames) == 2
    assert result.dx == pytest.approx(6.0, abs=1.0)
    assert result.dy == pytest.approx(0.0, abs=1.0)
