#!/usr/bin/env python3
"""Device-independent luminance A/B metrics for teleconverter stabilization trials.

The analyzer compares the same decoded chart region at the same time window. It reports
residual image translation and directional blur; it deliberately does not turn converter
magnification into a numeric OIS-gain claim. Weak texture, exposure mismatch, ambiguous
phase correlation, and mismatched sequences return ``UNJUDGEABLE`` instead of a win.

CLI example::

    python3 device-tests/tc_ois_ab.py reference.mp4 candidate.mp4 \
        --crop 120,80,640,480 --start 1.0 --duration 2.0

Frames are decoded to normalized grayscale by FFmpeg. All image measurements use NumPy.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Sequence

import numpy as np


EXPOSURE_TOLERANCE = 0.15
TEXTURE_ENERGY_FLOOR = 1.0e-5
PHASE_PEAK_FLOOR = 0.04
PHASE_PEAK_RATIO_FLOOR = 2.0
BLUR_ATTENUATION_FLOOR = 0.45
BLUR_ANISOTROPY_FLOOR = 1.8
DEFAULT_MAX_SHIFT = 64
DEFAULT_MAX_FRAMES = 30


@dataclass(frozen=True, slots=True)
class TranslationEstimate:
    """Candidate content displacement in reference-frame pixels (x right, y down)."""

    dx: float
    dy: float
    peak: float
    peak_ratio: float
    confident: bool


@dataclass(frozen=True, slots=True)
class BlurMetrics:
    """Directional gradient attenuation relative to the equally exposed control."""

    directional: bool
    axis_degrees: float
    attenuation: float
    anisotropy: float


@dataclass(frozen=True, slots=True)
class FrameAnalysis:
    verdict: str
    reason: str
    exposure_ratio: float
    texture_energy: float
    translation: TranslationEstimate
    blur: BlurMetrics


@dataclass(frozen=True, slots=True)
class TrialAnalysis:
    verdict: str
    reason: str
    dx: float
    dy: float
    directional_blur: bool
    directional_blur_fraction: float
    median_attenuation: float
    frames: tuple[FrameAnalysis, ...]


def _luminance(image: np.ndarray) -> np.ndarray:
    array = np.asarray(image)
    if array.ndim != 2 or array.size == 0:
        raise ValueError("luminance input must be a non-empty 2-D array")
    if np.issubdtype(array.dtype, np.integer):
        array = array.astype(np.float64) / np.iinfo(array.dtype).max
    else:
        array = array.astype(np.float64, copy=False)
        finite = array[np.isfinite(array)]
        if finite.size and finite.max(initial=0.0) > 2.0:
            # Host-side synthetic callers commonly use 8- or 10-bit float luminance.
            scale = 255.0 if finite.max() <= 255.0 else 1023.0
            array = array / scale
    if not np.isfinite(array).all():
        raise ValueError("luminance input contains non-finite samples")
    return array


def _texture_energy(image: np.ndarray) -> float:
    gx = np.diff(image, axis=1)
    gy = np.diff(image, axis=0)
    return float((np.mean(gx * gx) + np.mean(gy * gy)) / 2.0)


def _neutral_translation() -> TranslationEstimate:
    return TranslationEstimate(0.0, 0.0, 0.0, 0.0, False)


def _neutral_blur() -> BlurMetrics:
    return BlurMetrics(False, 0.0, 1.0, 1.0)


def _parabolic_offset(left: float, center: float, right: float) -> float:
    denominator = left - 2.0 * center + right
    if denominator >= -1.0e-12:
        return 0.0
    return float(np.clip(0.5 * (left - right) / denominator, -1.0, 1.0))


def estimate_translation(
    reference: np.ndarray,
    candidate: np.ndarray,
    *,
    max_shift: int = DEFAULT_MAX_SHIFT,
) -> TranslationEstimate:
    """Estimate translation with windowed, normalized FFT phase correlation.

    A positive result means the candidate's content moved right/down relative to the
    reference. Sub-pixel refinement is a bounded three-point parabolic fit. The search
    is limited to ``max_shift`` so an unrelated distant peak cannot become a result.
    """
    try:
        ref = _luminance(reference)
        cand = _luminance(candidate)
    except ValueError:
        return _neutral_translation()
    if ref.shape != cand.shape or min(ref.shape) < 8:
        return _neutral_translation()
    if _texture_energy(ref) < TEXTURE_ENERGY_FLOOR:
        return _neutral_translation()

    height, width = ref.shape
    window = np.outer(np.hanning(height), np.hanning(width))
    ref_window = (ref - ref.mean()) * window
    cand_window = (cand - cand.mean()) * window
    # Content moved +d has candidate(x) = reference(x - d). This spectrum's
    # inverse transform therefore peaks at +d, in image-content coordinates.
    cross = np.fft.fft2(cand_window) * np.conj(np.fft.fft2(ref_window))
    magnitude = np.abs(cross)
    cross = np.divide(cross, magnitude, out=np.zeros_like(cross), where=magnitude > 1.0e-12)
    correlation = np.fft.ifft2(cross).real

    x_shifts = np.arange(width)
    x_shifts[x_shifts > width // 2] -= width
    y_shifts = np.arange(height)
    y_shifts[y_shifts > height // 2] -= height
    bound = max(1, min(max_shift, (width - 1) // 2, (height - 1) // 2))
    allowed = (np.abs(y_shifts)[:, None] <= bound) & (np.abs(x_shifts)[None, :] <= bound)
    searchable = np.where(allowed, correlation, -np.inf)
    peak_y, peak_x = np.unravel_index(int(np.argmax(searchable)), searchable.shape)
    peak = float(correlation[peak_y, peak_x])

    # Exclude the main peak's immediate neighborhood from the sidelobe measurement.
    yy, xx = np.indices(correlation.shape)
    wrapped_dy = (yy - peak_y + height // 2) % height - height // 2
    wrapped_dx = (xx - peak_x + width // 2) % width - width // 2
    sidelobes = allowed & ((np.abs(wrapped_dx) > 2) | (np.abs(wrapped_dy) > 2))
    second = float(correlation[sidelobes].max(initial=-np.inf))
    peak_ratio = math.inf if second <= 1.0e-9 and peak > 0.0 else peak / max(second, 1.0e-9)

    offset_x = _parabolic_offset(
        float(correlation[peak_y, (peak_x - 1) % width]),
        peak,
        float(correlation[peak_y, (peak_x + 1) % width]),
    )
    offset_y = _parabolic_offset(
        float(correlation[(peak_y - 1) % height, peak_x]),
        peak,
        float(correlation[(peak_y + 1) % height, peak_x]),
    )
    confident = peak >= PHASE_PEAK_FLOOR and peak_ratio >= PHASE_PEAK_RATIO_FLOOR
    if not confident:
        # A long motion kernel turns the whitened phase peak into a ridge. A
        # normalized, non-whitened correlation keeps its center at the true
        # displacement; a wide exclusion zone prevents that broad lobe itself
        # from counting as confidence. Periodic/ambiguous texture still refuses.
        ref_centered = ref - ref.mean()
        cand_centered = cand - cand.mean()
        fallback_spectrum = np.fft.fft2(cand_centered) * np.conj(np.fft.fft2(ref_centered))
        normalization = math.sqrt(float(np.sum(ref_centered**2) * np.sum(cand_centered**2)))
        if normalization > 1.0e-12:
            fallback = np.fft.ifft2(fallback_spectrum).real / normalization
            fallback_search = np.where(allowed, fallback, -np.inf)
            peak_y, peak_x = np.unravel_index(int(np.argmax(fallback_search)), fallback.shape)
            peak = float(fallback[peak_y, peak_x])
            wrapped_dy = (yy - peak_y + height // 2) % height - height // 2
            wrapped_dx = (xx - peak_x + width // 2) % width - width // 2
            sidelobes = allowed & ((np.abs(wrapped_dx) > 8) | (np.abs(wrapped_dy) > 8))
            second = float(fallback[sidelobes].max(initial=-np.inf))
            peak_ratio = peak / max(second, 1.0e-9)
            if peak >= PHASE_PEAK_FLOOR and peak_ratio >= PHASE_PEAK_RATIO_FLOOR:
                offset_x = _parabolic_offset(
                    float(fallback[peak_y, (peak_x - 1) % width]),
                    peak,
                    float(fallback[peak_y, (peak_x + 1) % width]),
                )
                offset_y = _parabolic_offset(
                    float(fallback[(peak_y - 1) % height, peak_x]),
                    peak,
                    float(fallback[(peak_y + 1) % height, peak_x]),
                )
                return TranslationEstimate(
                    dx=float(x_shifts[peak_x]) + offset_x,
                    dy=float(y_shifts[peak_y]) + offset_y,
                    peak=peak,
                    peak_ratio=peak_ratio,
                    confident=True,
                )
    return TranslationEstimate(
        dx=float(x_shifts[peak_x]) + offset_x,
        dy=float(y_shifts[peak_y]) + offset_y,
        peak=peak,
        peak_ratio=float(peak_ratio),
        confident=confident,
    )


def _aligned_regions(
    reference: np.ndarray,
    candidate: np.ndarray,
    translation: TranslationEstimate,
) -> tuple[np.ndarray, np.ndarray]:
    dx = int(round(translation.dx))
    dy = int(round(translation.dy))
    height, width = reference.shape
    x0, x1 = max(0, -dx), min(width, width - dx)
    y0, y1 = max(0, -dy), min(height, height - dy)
    if x1 - x0 < 16 or y1 - y0 < 16:
        return reference, candidate
    ref = reference[y0:y1, x0:x1]
    cand = candidate[y0 + dy : y1 + dy, x0 + dx : x1 + dx]
    # Borders carry crop/window discontinuities rather than chart texture.
    border = 4
    return ref[border:-border, border:-border], cand[border:-border, border:-border]


def measure_directional_blur(
    reference: np.ndarray,
    candidate: np.ndarray,
    translation: TranslationEstimate | None = None,
) -> BlurMetrics:
    """Compare directional gradient energy after removing whole-frame translation.

    Motion blur attenuates derivatives along its axis while leaving the perpendicular
    axis comparatively intact. Comparing candidate/control energy at twelve angles
    distinguishes that signature from isotropic softness or a simple exposure change.
    """
    try:
        ref = _luminance(reference)
        cand = _luminance(candidate)
    except ValueError:
        return _neutral_blur()
    if ref.shape != cand.shape or min(ref.shape) < 16:
        return _neutral_blur()
    if _texture_energy(ref) < TEXTURE_ENERGY_FLOOR:
        return _neutral_blur()
    if translation is None:
        translation = estimate_translation(ref, cand)
    ref, cand = _aligned_regions(ref, cand, translation)
    if min(ref.shape) < 8:
        return _neutral_blur()

    ref_y, ref_x = np.gradient(ref)
    cand_y, cand_x = np.gradient(cand)
    angles = np.arange(0.0, 180.0, 15.0)
    ratios: list[float] = []
    for angle in angles:
        radians = math.radians(float(angle))
        cosine, sine = math.cos(radians), math.sin(radians)
        ref_directional = ref_x * cosine + ref_y * sine
        cand_directional = cand_x * cosine + cand_y * sine
        ref_energy = float(np.mean(ref_directional * ref_directional))
        cand_energy = float(np.mean(cand_directional * cand_directional))
        ratios.append(cand_energy / max(ref_energy, 1.0e-12))

    ratio_array = np.asarray(ratios)
    weakest = int(np.argmin(ratio_array))
    minimum = max(float(ratio_array[weakest]), 1.0e-12)
    maximum = max(float(ratio_array.max()), 1.0e-12)
    anisotropy = math.sqrt(maximum / minimum)
    directional = minimum <= BLUR_ATTENUATION_FLOOR and anisotropy >= BLUR_ANISOTROPY_FLOOR
    return BlurMetrics(
        directional=directional,
        axis_degrees=float(angles[weakest]),
        attenuation=math.sqrt(minimum),
        anisotropy=anisotropy,
    )


def analyze_frame_pair(reference: np.ndarray, candidate: np.ndarray) -> FrameAnalysis:
    """Analyze one aligned chart pair, refusing pairs that cannot support a verdict."""
    try:
        ref = _luminance(reference)
        cand = _luminance(candidate)
    except ValueError:
        return FrameAnalysis("UNJUDGEABLE", "INVALID_INPUT", math.nan, 0.0, _neutral_translation(), _neutral_blur())
    if ref.shape != cand.shape or min(ref.shape) < 16:
        return FrameAnalysis("UNJUDGEABLE", "INVALID_INPUT", math.nan, 0.0, _neutral_translation(), _neutral_blur())

    texture = min(_texture_energy(ref), _texture_energy(cand))
    reference_median = float(np.median(ref))
    exposure_ratio = float(np.median(cand) / reference_median) if reference_median > 1.0e-9 else math.nan
    if texture < TEXTURE_ENERGY_FLOOR:
        return FrameAnalysis("UNJUDGEABLE", "INSUFFICIENT_TEXTURE", exposure_ratio, texture, _neutral_translation(), _neutral_blur())
    if not math.isfinite(exposure_ratio) or not (
        1.0 - EXPOSURE_TOLERANCE <= exposure_ratio <= 1.0 + EXPOSURE_TOLERANCE
    ):
        return FrameAnalysis("UNJUDGEABLE", "EXPOSURE_MISMATCH", exposure_ratio, texture, _neutral_translation(), _neutral_blur())

    translation = estimate_translation(ref, cand)
    if not translation.confident:
        return FrameAnalysis("UNJUDGEABLE", "AMBIGUOUS_MOTION", exposure_ratio, texture, translation, _neutral_blur())
    blur = measure_directional_blur(ref, cand, translation)
    return FrameAnalysis("JUDGEABLE", "OK", exposure_ratio, texture, translation, blur)


def _frame_stack(frames: np.ndarray | Sequence[np.ndarray]) -> np.ndarray:
    array = np.asarray(frames)
    if array.ndim == 2:
        array = array[None, ...]
    if array.ndim != 3 or array.shape[0] == 0:
        raise ValueError("frame sequence must be a non-empty 2-D image or 3-D stack")
    return array


def analyze_trial(
    reference_frames: np.ndarray | Sequence[np.ndarray],
    candidate_frames: np.ndarray | Sequence[np.ndarray],
    *,
    max_frames: int = DEFAULT_MAX_FRAMES,
) -> TrialAnalysis:
    """Aggregate a bounded, evenly spaced sample of a matched frame sequence."""
    try:
        reference = _frame_stack(reference_frames)
        candidate = _frame_stack(candidate_frames)
    except ValueError:
        return TrialAnalysis("UNJUDGEABLE", "INVALID_INPUT", math.nan, math.nan, False, 0.0, math.nan, ())
    if reference.shape != candidate.shape:
        return TrialAnalysis("UNJUDGEABLE", "FRAME_COUNT_MISMATCH", math.nan, math.nan, False, 0.0, math.nan, ())

    count = reference.shape[0]
    sample_count = min(max(1, max_frames), count)
    indices = np.linspace(0, count - 1, sample_count, dtype=int)
    frames = tuple(analyze_frame_pair(reference[index], candidate[index]) for index in indices)
    if any(frame.reason == "EXPOSURE_MISMATCH" for frame in frames):
        return TrialAnalysis("UNJUDGEABLE", "EXPOSURE_MISMATCH", math.nan, math.nan, False, 0.0, math.nan, frames)

    valid = tuple(frame for frame in frames if frame.verdict == "JUDGEABLE")
    required = max(1, math.ceil(len(frames) * 0.60))
    if len(valid) < required:
        reasons = [frame.reason for frame in frames if frame.verdict == "UNJUDGEABLE"]
        reason = max(set(reasons), key=reasons.count) if reasons else "INSUFFICIENT_EVIDENCE"
        return TrialAnalysis("UNJUDGEABLE", reason, math.nan, math.nan, False, 0.0, math.nan, frames)

    blur_votes = sum(frame.blur.directional for frame in valid)
    blur_fraction = blur_votes / len(valid)
    return TrialAnalysis(
        verdict="JUDGEABLE",
        reason="OK",
        dx=float(np.median([frame.translation.dx for frame in valid])),
        dy=float(np.median([frame.translation.dy for frame in valid])),
        directional_blur=blur_fraction >= 0.5,
        directional_blur_fraction=blur_fraction,
        median_attenuation=float(np.median([frame.blur.attenuation for frame in valid])),
        frames=frames,
    )


def decode_luminance_crop(
    video: str | Path,
    *,
    crop: tuple[int, int, int, int],
    start_s: float = 0.0,
    duration_s: float | None = None,
    max_frames: int | None = None,
) -> np.ndarray:
    """Decode a video window to ``(frame, y, x)`` normalized luminance.

    ``crop`` is ``(x, y, width, height)`` in source pixels. No resize or color-matrix
    assumption is hidden in this function: FFmpeg performs the source-to-gray decode.
    The complete requested window is decoded by default. ``max_frames`` is an explicit
    decode-truncation guard for direct callers; video analysis leaves it unset and
    applies its own sampling cap only after the complete window is available.
    """
    x, y, width, height = crop
    if min(width, height) <= 0 or x < 0 or y < 0:
        raise ValueError("crop dimensions must be positive and its origin non-negative")
    if max_frames is not None and max_frames <= 0:
        raise ValueError("max_frames must be positive when supplied")
    command = ["ffmpeg", "-hide_banner", "-loglevel", "error"]
    if start_s > 0.0:
        command += ["-ss", f"{start_s:.6f}"]
    command += ["-i", str(Path(video))]
    if duration_s is not None:
        if duration_s <= 0.0:
            raise ValueError("duration_s must be positive")
        command += ["-t", f"{duration_s:.6f}"]
    command += [
        "-an", "-sn", "-dn",
        "-vf", f"crop={width}:{height}:{x}:{y},format=gray",
    ]
    if max_frames is not None:
        command += ["-frames:v", str(max_frames)]
    command += [
        "-f", "rawvideo", "-pix_fmt", "gray", "-",
    ]
    completed = subprocess.run(command, capture_output=True, check=False, timeout=120)
    if completed.returncode != 0:
        message = completed.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"FFmpeg luminance decode failed: {message}")
    frame_bytes = width * height
    if not completed.stdout or len(completed.stdout) % frame_bytes:
        raise RuntimeError("FFmpeg returned no complete luminance frames")
    frames = np.frombuffer(completed.stdout, dtype=np.uint8).reshape(-1, height, width)
    return frames.astype(np.float64) / 255.0


def analyze_videos(
    reference: str | Path,
    candidate: str | Path,
    *,
    crop: tuple[int, int, int, int],
    start_s: float = 0.0,
    duration_s: float | None = None,
    max_frames: int = DEFAULT_MAX_FRAMES,
) -> TrialAnalysis:
    """Decode identical regions/windows, then sample across the complete sequences."""
    reference_frames = decode_luminance_crop(
        reference, crop=crop, start_s=start_s, duration_s=duration_s
    )
    candidate_frames = decode_luminance_crop(
        candidate, crop=crop, start_s=start_s, duration_s=duration_s
    )
    return analyze_trial(reference_frames, candidate_frames, max_frames=max_frames)


def _parse_crop(value: str) -> tuple[int, int, int, int]:
    try:
        parts = tuple(int(part) for part in value.split(","))
    except ValueError as error:
        raise argparse.ArgumentTypeError("crop must be x,y,width,height") from error
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("crop must be x,y,width,height")
    return parts


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path, help="sharp/control or reference clip")
    parser.add_argument("candidate", type=Path, help="trial clip")
    parser.add_argument("--crop", required=True, type=_parse_crop, metavar="X,Y,W,H")
    parser.add_argument("--start", type=float, default=0.0, metavar="SECONDS")
    parser.add_argument("--duration", type=float, metavar="SECONDS")
    parser.add_argument("--max-frames", type=int, default=DEFAULT_MAX_FRAMES)
    args = parser.parse_args(argv)
    result = analyze_videos(
        args.reference,
        args.candidate,
        crop=args.crop,
        start_s=args.start,
        duration_s=args.duration,
        max_frames=args.max_frames,
    )
    print(json.dumps(asdict(result), indent=2, allow_nan=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
