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
import re
import select
import subprocess
import threading
import time
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
SOURCE_TIME_TOLERANCE_S = 1.0e-4
STDERR_RETAINED_BYTES = 65536
PTS_TIME_PATTERN = re.compile(
    rb"pts_time:(-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)"
)


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


@dataclass(frozen=True, slots=True)
class _DecodedWindow:
    frames: np.ndarray
    selected_indices: tuple[int, ...]
    source_frame_count: int
    # FFmpeg showinfo's timestamp for EVERY decoded source frame, not only the
    # retained pixel sample. This is linear scalar metadata, never full-frame buffering.
    source_pts_s: tuple[float, ...]


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


def _correlation_main_lobe(
    correlation: np.ndarray,
    allowed: np.ndarray,
    peak_y: int,
    peak_x: int,
) -> np.ndarray:
    """Return the peak-connected half-height region, including broad blur lobes."""
    eligible = allowed & (correlation >= correlation[peak_y, peak_x] * 0.5)
    component = np.zeros(correlation.shape, dtype=bool)
    component[peak_y, peak_x] = True
    while True:
        neighbors = np.zeros(correlation.shape, dtype=bool)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dx or dy:
                    neighbors |= np.roll(np.roll(component, dy, axis=0), dx, axis=1)
        new = eligible & neighbors & ~component
        if not np.any(new):
            return component
        component |= new


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
    confident = bool(np.any(sidelobes)) and peak >= PHASE_PEAK_FLOOR and peak_ratio >= PHASE_PEAK_RATIO_FLOOR
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
            fallback_y, fallback_x = np.unravel_index(
                int(np.argmax(fallback_search)), fallback.shape
            )
            fallback_peak = float(fallback[fallback_y, fallback_x])
            # Exclude the peak-connected half-height lobe, not a fixed pixel
            # radius: a 31 px motion kernel still has one true lobe, while an
            # actually independent secondary peak remains eligible as a veto.
            main_lobe = _correlation_main_lobe(fallback, allowed, fallback_y, fallback_x)
            sidelobes = allowed & ~main_lobe
            # A tiny search space can be entirely main lobe. An empty
            # population is no confidence evidence, not an infinite peak ratio.
            if fallback_peak >= PHASE_PEAK_FLOOR and np.any(sidelobes):
                second = float(fallback[sidelobes].max())
                fallback_ratio = fallback_peak / max(second, 1.0e-9)
                if fallback_ratio >= PHASE_PEAK_RATIO_FLOOR:
                    # The correlation maximum can sit on either edge of a
                    # long blur plateau. Its half-height lobe is centered on
                    # the translation even when the individual peak is not.
                    lobe_y, lobe_x = np.nonzero(main_lobe)
                    unwrapped_x = (lobe_x - fallback_x + width // 2) % width - width // 2
                    unwrapped_y = (lobe_y - fallback_y + height // 2) % height - height // 2
                    center_x = float(x_shifts[fallback_x]) + (
                        float(unwrapped_x.min()) + float(unwrapped_x.max())
                    ) / 2.0
                    center_y = float(y_shifts[fallback_y]) + (
                        float(unwrapped_y.min()) + float(unwrapped_y.max())
                    ) / 2.0
                    return TranslationEstimate(
                        dx=center_x,
                        dy=center_y,
                        peak=fallback_peak,
                        peak_ratio=fallback_ratio,
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
    if max_frames <= 0:
        raise ValueError("max_frames must be positive")
    try:
        reference = _frame_stack(reference_frames)
        candidate = _frame_stack(candidate_frames)
    except ValueError:
        return TrialAnalysis("UNJUDGEABLE", "INVALID_INPUT", math.nan, math.nan, False, 0.0, math.nan, ())
    if reference.shape != candidate.shape:
        return TrialAnalysis("UNJUDGEABLE", "FRAME_COUNT_MISMATCH", math.nan, math.nan, False, 0.0, math.nan, ())

    count = reference.shape[0]
    sample_count = min(max_frames, count)
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


def _stream_start_pts_s(video: str | Path, deadline: float) -> float:
    """Read the stream start used to turn a duration into a -copyts absolute stop."""
    remaining = deadline - time.monotonic()
    if remaining <= 0.0:
        raise TimeoutError("FFmpeg start-time probe exceeded its decode deadline")
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=start_time", "-of",
                "default=noprint_wrappers=1:nokey=1", str(Path(video)),
            ],
            check=True,
            capture_output=True,
            timeout=remaining,
        )
    except subprocess.TimeoutExpired as error:
        raise TimeoutError("FFmpeg start-time probe exceeded its decode deadline") from error
    except subprocess.CalledProcessError as error:
        message = error.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"FFmpeg start-time probe failed: {message}") from error
    text = result.stdout.decode("ascii", errors="replace").strip()
    if not text or text == "N/A":
        return 0.0
    start = float(text)
    return start if math.isfinite(start) else 0.0


def _decode_luminance_window(
    video: str | Path,
    *,
    crop: tuple[int, int, int, int],
    start_s: float = 0.0,
    duration_s: float | None = None,
    max_frames: int | None = None,
    timeout_s: float = 120.0,
) -> _DecodedWindow:
    """Decode a video window to ``(frame, y, x)`` normalized luminance.

    ``crop`` is ``(x, y, width, height)`` in source pixels. No resize or color-matrix
    assumption is hidden in this function: FFmpeg performs the source-to-gray decode.
    With ``max_frames`` set, decoding still covers the complete requested window but
    retains only a bounded online sample, including its first and last frames. The
    sampling stride doubles as needed; no full-window byte stream or float64 buffer is
    constructed. ``None`` is an explicitly unbounded direct-decode request.
    """
    x, y, width, height = crop
    if min(width, height) <= 0 or x < 0 or y < 0:
        raise ValueError("crop dimensions must be positive and its origin non-negative")
    if max_frames is not None and max_frames <= 0:
        raise ValueError("max_frames must be positive when supplied")
    if timeout_s <= 0.0:
        raise ValueError("timeout_s must be positive")
    deadline = time.monotonic() + timeout_s
    command = ["ffmpeg", "-hide_banner", "-loglevel", "info", "-copyts"]
    if start_s > 0.0:
        command += ["-ss", f"{start_s:.6f}"]
    command += ["-i", str(Path(video))]
    if duration_s is not None:
        if duration_s <= 0.0:
            raise ValueError("duration_s must be positive")
        # -copyts keeps source PTS, so an absolute -to is the requested window's
        # end; -t would instead cut a nonzero-start stream short on this FFmpeg.
        source_start_s = _stream_start_pts_s(video, deadline)
        command += ["-to", f"{max(start_s, source_start_s) + duration_s:.6f}"]
    command += [
        "-an", "-sn", "-dn",
        "-vf", f"crop={width}:{height}:{x}:{y},format=gray,showinfo",
        "-fps_mode", "passthrough",
        "-f", "rawvideo", "-pix_fmt", "gray", "-",
    ]
    frame_bytes = width * height
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )
    stderr_parts: list[bytes] = []
    stderr_size = 0
    source_pts_s: list[float] = []

    def retain_stderr(line: bytes) -> None:
        nonlocal stderr_size
        payload = line + b"\n"
        stderr_parts.append(payload)
        stderr_size += len(payload)
        while stderr_size > STDERR_RETAINED_BYTES and len(stderr_parts) > 1:
            stderr_size -= len(stderr_parts.pop(0))
        if stderr_size > STDERR_RETAINED_BYTES:
            stderr_parts[:] = [stderr_parts[0][-STDERR_RETAINED_BYTES:]]
            stderr_size = len(stderr_parts[0])

    def metadata_line(line: bytes) -> None:
        if b"showinfo" in line:
            match = PTS_TIME_PATTERN.search(line)
            if match is not None:
                source_pts_s.append(float(match.group(1)))
                return
        if line:
            retain_stderr(line)

    def drain_stderr() -> None:
        assert process.stderr is not None
        pending = bytearray()
        while chunk := process.stderr.read(65536):
            pending.extend(chunk)
            lines = re.split(br"[\r\n]", bytes(pending))
            pending = bytearray(lines.pop())
            for line in lines:
                metadata_line(line)
            # A broken/infinite diagnostic line must not defeat the memory bound.
            if len(pending) > STDERR_RETAINED_BYTES:
                retain_stderr(bytes(pending[-STDERR_RETAINED_BYTES:]))
                pending.clear()
        if pending:
            metadata_line(bytes(pending))

    stderr_thread = threading.Thread(target=drain_stderr, daemon=True)
    stderr_thread.start()
    selected: list[tuple[int, np.ndarray]] = []
    last: tuple[int, np.ndarray] | None = None
    stride = 1
    index = 0

    def read_frame() -> bytes | None:
        assert process.stdout is not None
        payload = bytearray()
        while len(payload) < frame_bytes:
            remaining = deadline - time.monotonic()
            if remaining <= 0.0:
                raise TimeoutError(f"FFmpeg luminance decode exceeded {timeout_s:.3f} seconds")
            ready, _, _ = select.select([process.stdout], [], [], remaining)
            if not ready:
                raise TimeoutError(f"FFmpeg luminance decode exceeded {timeout_s:.3f} seconds")
            chunk = process.stdout.read(frame_bytes - len(payload))
            if not chunk:
                return bytes(payload) if payload else None
            payload.extend(chunk)
        return bytes(payload)

    try:
        while True:
            payload = read_frame()
            if payload is None:
                break
            if len(payload) != frame_bytes:
                raise RuntimeError("FFmpeg returned a partial luminance frame")
            frame = np.frombuffer(payload, dtype=np.uint8).reshape(height, width)
            if max_frames is None:
                selected.append((index, frame))
            elif max_frames == 1:
                if index == 0:
                    selected.append((index, frame))
            else:
                last = (index, frame)
                if index == 0 or index % stride == 0:
                    selected.append((index, frame))
                    if len(selected) > max_frames - 1:
                        # Reserve one slot for the final frame. Retaining every
                        # other grid point keeps full-window coverage bounded.
                        stride *= 2
                        selected = [item for item in selected if item[0] % stride == 0]
            index += 1
        if max_frames is not None and max_frames > 1 and last is not None:
            if not selected or selected[-1][0] != last[0]:
                selected.append(last)
        remaining = deadline - time.monotonic()
        if remaining <= 0.0:
            raise TimeoutError(f"FFmpeg luminance decode exceeded {timeout_s:.3f} seconds")
        try:
            returncode = process.wait(timeout=remaining)
        except subprocess.TimeoutExpired as error:
            raise TimeoutError(f"FFmpeg luminance decode exceeded {timeout_s:.3f} seconds") from error
        stderr_thread.join(timeout=max(0.0, deadline - time.monotonic()))
        if stderr_thread.is_alive():
            raise TimeoutError(f"FFmpeg luminance decode exceeded {timeout_s:.3f} seconds")
        stderr = b"".join(stderr_parts)
    except BaseException:
        if process.poll() is None:
            process.kill()
        process.wait()
        stderr_thread.join(timeout=1.0)
        raise
    finally:
        if process.stdout is not None:
            process.stdout.close()
        if process.stderr is not None and not stderr_thread.is_alive():
            process.stderr.close()

    if returncode != 0:
        message = stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"FFmpeg luminance decode failed: {message}")
    if not selected:
        raise RuntimeError("FFmpeg returned no complete luminance frames")
    frames = np.stack([frame for _, frame in selected]).astype(np.float64) / 255.0
    return _DecodedWindow(
        frames=frames,
        selected_indices=tuple(index for index, _ in selected),
        source_frame_count=index,
        source_pts_s=tuple(source_pts_s),
    )


def decode_luminance_crop(
    video: str | Path,
    *,
    crop: tuple[int, int, int, int],
    start_s: float = 0.0,
    duration_s: float | None = None,
    max_frames: int | None = None,
    timeout_s: float = 120.0,
) -> np.ndarray:
    """Public array-returning wrapper around the bounded streaming decoder."""
    return _decode_luminance_window(
        video,
        crop=crop,
        start_s=start_s,
        duration_s=duration_s,
        max_frames=max_frames,
        timeout_s=timeout_s,
    ).frames


def analyze_videos(
    reference: str | Path,
    candidate: str | Path,
    *,
    crop: tuple[int, int, int, int],
    start_s: float = 0.0,
    duration_s: float | None = None,
    max_frames: int = DEFAULT_MAX_FRAMES,
    timeout_s: float = 120.0,
) -> TrialAnalysis:
    """Decode identical regions/windows, then sample matched source indices."""
    if max_frames <= 0:
        raise ValueError("max_frames must be positive")
    reference_window = _decode_luminance_window(
        reference, crop=crop, start_s=start_s, duration_s=duration_s,
        max_frames=max_frames, timeout_s=timeout_s,
    )
    candidate_window = _decode_luminance_window(
        candidate, crop=crop, start_s=start_s, duration_s=duration_s,
        max_frames=max_frames, timeout_s=timeout_s,
    )
    if reference_window.source_frame_count != candidate_window.source_frame_count:
        return TrialAnalysis(
            "UNJUDGEABLE", "FRAME_COUNT_MISMATCH", math.nan, math.nan,
            False, 0.0, math.nan, (),
        )
    if (
        len(reference_window.source_pts_s) != reference_window.source_frame_count
        or len(candidate_window.source_pts_s) != candidate_window.source_frame_count
    ):
        return TrialAnalysis(
            "UNJUDGEABLE", "SOURCE_TIME_METADATA_MISSING", math.nan, math.nan,
            False, 0.0, math.nan, (),
        )
    if any(
        not math.isfinite(reference_pts)
        or not math.isfinite(candidate_pts)
        or abs(reference_pts - candidate_pts) > SOURCE_TIME_TOLERANCE_S
        for reference_pts, candidate_pts in zip(
            reference_window.source_pts_s, candidate_window.source_pts_s, strict=True
        )
    ):
        return TrialAnalysis(
            "UNJUDGEABLE", "SOURCE_TIME_MISMATCH", math.nan, math.nan,
            False, 0.0, math.nan, (),
        )
    if reference_window.selected_indices != candidate_window.selected_indices:
        return TrialAnalysis(
            "UNJUDGEABLE", "SAMPLE_INDEX_MISMATCH", math.nan, math.nan,
            False, 0.0, math.nan, (),
        )
    return analyze_trial(reference_window.frames, candidate_window.frames, max_frames=max_frames)


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
