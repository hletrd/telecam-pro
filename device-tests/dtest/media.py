"""Pulled-file validators. Stdlib parsing for stills; ffprobe (if present) for video."""

from __future__ import annotations

import json
import re
import shutil
import struct
import subprocess
from fractions import Fraction
from pathlib import Path


def jpeg_info(path: Path) -> dict:
    """Dimensions from SOFn + whether an EXIF APP1 segment exists."""
    data = path.read_bytes()
    if data[:2] != b"\xff\xd8":
        raise ValueError("not a JPEG")
    has_exif = False
    w = h = 0
    i = 2
    while i + 4 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker == 0xFF:
            i += 1  # 0xFF fill byte(s) may pad before the real marker byte — skip singly
            continue
        if marker == 0xD9 or marker == 0xDA:
            break
        if marker == 0x01 or 0xD0 <= marker <= 0xD7:
            i += 2  # TEM/RSTn are length-less; reading a bogus segment length here mis-walks
            continue
        seg_len = struct.unpack_from(">H", data, i + 2)[0]
        if marker == 0xE1 and data[i + 4 : i + 8] == b"Exif":
            has_exif = True
        if marker in (0xC0, 0xC1, 0xC2, 0xC3):  # SOF0-3
            h, w = struct.unpack_from(">HH", data, i + 5)
        i += 2 + seg_len
    return {"width": w, "height": h, "exif": has_exif, "bytes": len(data)}


def heic_valid(path: Path) -> bool:
    data = path.read_bytes()
    return len(data) > 100_000 and heic_structure_valid(path)


def heic_structure_valid(path: Path) -> bool:
    """ftyp/brand-only HEIC check, no size heuristic: a near-black exposure legitimately
    compresses to tens of KB (device fact 2026-07-24 — a dark TELE manual shot was 56 KB),
    so parity validators pair this with a full sips dimension decode instead of a size floor."""
    data = path.read_bytes()
    return (
        len(data) > 16
        and data[4:8] == b"ftyp"
        and (b"heic" in data[8:24] or b"mif1" in data[8:24])
    )


def sips_dimensions(output: str) -> tuple[int, int] | None:
    width = re.search(r"^\s*pixelWidth:\s*(\d+)\s*$", output, re.MULTILINE)
    height = re.search(r"^\s*pixelHeight:\s*(\d+)\s*$", output, re.MULTILINE)
    if width is None or height is None:
        return None
    return int(width.group(1)), int(height.group(1))


def image_dimensions(path: Path) -> tuple[int, int] | None:
    """Decode dimensions with macOS ImageIO via sips; HEIF tile streams fool ffprobe."""
    sips = shutil.which("sips")
    if sips is None:
        return None
    result = subprocess.run(
        [sips, "-g", "pixelWidth", "-g", "pixelHeight", str(path)],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if result.returncode != 0:
        raise ValueError(f"sips failed: {result.stderr[:200]}")
    return sips_dimensions(result.stdout)


def dng_valid(path: Path) -> bool:
    data = path.read_bytes()
    return len(data) > 1_000_000 and data[:4] in (b"II*\x00", b"MM\x00*")


# ---- TIFF / EXIF structure (stdlib) ----------------------------------------------------------
# A DNG is TIFF/EP: Android's DngCreator writes the CFA raw image plus EXIF metadata across IFD0,
# optional SubIFDs (tag 0x14A) and the Exif IFD (tag 0x8769). The parser below walks all of them
# defensively (bounds-checked offsets, visited set, entry caps) because a truncated pull must fail
# loudly, never loop or index out of bounds. The same walker parses the TIFF payload of a JPEG
# APP1 Exif segment, so DNG and JPEG parity checks share one tag decoder.

TIFF_TAG_NEW_SUBFILE_TYPE = 0x00FE
TIFF_TAG_IMAGE_WIDTH = 0x0100
TIFF_TAG_IMAGE_LENGTH = 0x0101
TIFF_TAG_BITS_PER_SAMPLE = 0x0102
TIFF_TAG_PHOTOMETRIC = 0x0106
TIFF_TAG_SAMPLES_PER_PIXEL = 0x0115
TIFF_TAG_SUB_IFDS = 0x014A
TIFF_TAG_EXIF_IFD = 0x8769
EXIF_TAG_EXPOSURE_TIME = 0x829A
EXIF_TAG_ISO = 0x8827  # PhotographicSensitivity (a.k.a. ISOSpeedRatings)
TIFF_PHOTOMETRIC_CFA = 32803

_TIFF_TYPE_SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8}
_MAX_IFDS = 64
_MAX_ENTRIES_PER_IFD = 4096


def _tiff_values(endian: str, kind: int, count: int, raw: bytes) -> list:
    """Decode one IFD entry's value payload into python scalars/Fractions."""
    if kind in (1, 6, 7):
        return list(raw[:count])
    if kind == 2:
        return [raw[:count].split(b"\x00", 1)[0].decode(errors="replace")]
    if kind in (3, 8):
        return list(struct.unpack(f"{endian}{count}{'H' if kind == 3 else 'h'}", raw[: 2 * count]))
    if kind in (4, 9, 11):
        code = {4: "I", 9: "i", 11: "f"}[kind]
        return list(struct.unpack(f"{endian}{count}{code}", raw[: 4 * count]))
    if kind in (5, 10):
        code = "I" if kind == 5 else "i"
        flat = struct.unpack(f"{endian}{2 * count}{code}", raw[: 8 * count])
        return [
            Fraction(flat[2 * index], flat[2 * index + 1]) if flat[2 * index + 1] else None
            for index in range(count)
        ]
    if kind == 12:
        return list(struct.unpack(f"{endian}{count}d", raw[: 8 * count]))
    raise ValueError(f"unsupported TIFF entry type {kind}")


def _tiff_ifd(data: bytes, endian: str, offset: int) -> tuple[dict[int, list], int]:
    """Parse one IFD at ``offset`` into {tag: values}; return it with the next-IFD offset."""
    if offset <= 0 or offset + 2 > len(data):
        raise ValueError(f"IFD offset {offset} escapes the TIFF payload ({len(data)} bytes)")
    (count,) = struct.unpack_from(f"{endian}H", data, offset)
    if count == 0 or count > _MAX_ENTRIES_PER_IFD:
        raise ValueError(f"implausible IFD entry count {count} at offset {offset}")
    end = offset + 2 + count * 12
    if end + 4 > len(data):
        raise ValueError(f"IFD at {offset} is truncated ({count} entries, {len(data)} bytes)")
    entries: dict[int, list] = {}
    for index in range(count):
        tag, kind, value_count = struct.unpack_from(f"{endian}HHI", data, offset + 2 + index * 12)
        size = _TIFF_TYPE_SIZES.get(kind)
        if size is None or value_count > len(data):
            continue  # unknown/absurd entry: skip the tag, keep walking the rest
        total = size * value_count
        inline = data[offset + 2 + index * 12 + 8 : offset + 2 + index * 12 + 12]
        if total <= 4:
            raw = inline
        else:
            (value_offset,) = struct.unpack(f"{endian}I", inline)
            if value_offset + total > len(data):
                continue
            raw = data[value_offset : value_offset + total]
        entries[tag] = _tiff_values(endian, kind, value_count, raw)
    (next_offset,) = struct.unpack_from(f"{endian}I", data, end)
    return entries, next_offset


def tiff_ifds(data: bytes) -> list[dict[int, list]]:
    """All IFDs of a TIFF payload: the IFD0 chain plus SubIFDs and the Exif IFD, in walk order."""
    if len(data) < 8 or data[:4] not in (b"II*\x00", b"MM\x00*"):
        raise ValueError("not a TIFF payload")
    endian = "<" if data[:2] == b"II" else ">"
    (first,) = struct.unpack_from(f"{endian}I", data, 4)
    pending = [first]
    visited: set[int] = set()
    ifds: list[dict[int, list]] = []
    while pending and len(ifds) < _MAX_IFDS:
        offset = pending.pop(0)
        if offset in visited or offset <= 0:
            continue
        visited.add(offset)
        entries, next_offset = _tiff_ifd(data, endian, offset)
        ifds.append(entries)
        if next_offset:
            pending.append(next_offset)
        for child_tag in (TIFF_TAG_SUB_IFDS, TIFF_TAG_EXIF_IFD):
            for child in entries.get(child_tag, []):
                if isinstance(child, int):
                    pending.append(child)
    if not ifds:
        raise ValueError("TIFF payload contains no parseable IFD")
    return ifds


def _first_int(entries: dict[int, list], tag: int) -> int | None:
    values = entries.get(tag)
    if not values or not isinstance(values[0], int):
        return None
    return values[0]


def _exif_facts(ifds: list[dict[int, list]]) -> tuple[int | None, Fraction | None]:
    """First ISO/ExposureTime found across the IFDs (TIFF/EP puts them in IFD0, EXIF in ExifIFD)."""
    iso = None
    exposure = None
    for scan in ifds:
        iso = iso if iso is not None else _first_int(scan, EXIF_TAG_ISO)
        if exposure is None:
            values = scan.get(EXIF_TAG_EXPOSURE_TIME)
            if values and isinstance(values[0], Fraction):
                exposure = values[0]
    return iso, exposure


def tiff_image_info(data: bytes) -> dict:
    """Structure + EXIF facts of a TIFF/DNG payload.

    The *raw* image IFD is selected as the CFA-photometric IFD when present (a DNG's actual
    sensor plane), else the largest-area IFD — DngCreator layouts differ on whether IFD0 is the
    full image or a thumbnail with the raw in a SubIFD, and the selector must not care.
    """
    ifds = tiff_ifds(data)
    candidates = []
    for entries in ifds:
        width = _first_int(entries, TIFF_TAG_IMAGE_WIDTH)
        height = _first_int(entries, TIFF_TAG_IMAGE_LENGTH)
        if width and height:
            candidates.append((entries, width, height))
    if not candidates:
        raise ValueError("TIFF payload advertises no image dimensions")
    cfa = [
        item for item in candidates
        if _first_int(item[0], TIFF_TAG_PHOTOMETRIC) == TIFF_PHOTOMETRIC_CFA
    ]
    entries, width, height = cfa[0] if cfa else max(candidates, key=lambda item: item[1] * item[2])

    iso, exposure = _exif_facts(ifds)
    return {
        "width": width,
        "height": height,
        "bits_per_sample": _first_int(entries, TIFF_TAG_BITS_PER_SAMPLE),
        "samples_per_pixel": _first_int(entries, TIFF_TAG_SAMPLES_PER_PIXEL),
        "photometric": _first_int(entries, TIFF_TAG_PHOTOMETRIC),
        "cfa": bool(cfa),
        "ifd_count": len(ifds),
        "iso": iso,
        "exposure_time": exposure,
    }


def dng_info(path: Path) -> dict:
    return tiff_image_info(path.read_bytes())


def jpeg_exif_info(path: Path) -> dict:
    """ISO + ExposureTime from a JPEG's APP1 Exif segment via the shared TIFF walker."""
    data = path.read_bytes()
    if data[:2] != b"\xff\xd8":
        raise ValueError("not a JPEG")
    i = 2
    while i + 4 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker == 0xFF:
            i += 1
            continue
        if marker in (0xD9, 0xDA):
            break
        if marker == 0x01 or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        seg_len = struct.unpack_from(">H", data, i + 2)[0]
        if marker == 0xE1 and data[i + 4 : i + 10] == b"Exif\x00\x00":
            # No dimension requirement here: a JPEG's EXIF TIFF payload often omits
            # ImageWidth/Length (SOF owns the raster), so only the metadata facts are read.
            iso, exposure = _exif_facts(tiff_ifds(data[i + 10 : i + 2 + seg_len]))
            return {"iso": iso, "exposure_time": exposure}
        i += 2 + seg_len
    raise ValueError("JPEG has no APP1 Exif segment")


def _fraction(value: object, field: str) -> Fraction:
    if value in (None, "", "N/A", "0/0"):
        raise ValueError(f"ffprobe omitted {field}")
    try:
        parsed = Fraction(str(value))
    except (ValueError, ZeroDivisionError) as error:
        raise ValueError(f"invalid ffprobe {field}: {value!r}") from error
    if parsed <= 0:
        raise ValueError(f"non-positive ffprobe {field}: {value!r}")
    return parsed


def _optional_int(value: object) -> int | None:
    if value in (None, "", "N/A"):
        return None
    return int(str(value))


def _stream_seconds(stream: dict, *, required_ticks: bool) -> Fraction | None:
    duration_ts = _optional_int(stream.get("duration_ts"))
    time_base = stream.get("time_base")
    if duration_ts is not None and time_base not in (None, "", "N/A", "0/0"):
        return Fraction(duration_ts) * _fraction(time_base, "stream time_base")
    if required_ticks:
        raise ValueError("ffprobe omitted duration_ts/time_base")
    duration = stream.get("duration")
    if duration in (None, "", "N/A"):
        return None
    return _fraction(duration, "stream duration")


def parse_ffprobe_payload(header: dict, scan: dict) -> dict:
    """Turn ffprobe header + decoded-frame JSON into strict, rational metadata."""
    header_streams = header.get("streams") or []
    header_videos = [stream for stream in header_streams if stream.get("codec_type") == "video"]
    header_audios = [stream for stream in header_streams if stream.get("codec_type") == "audio"]
    scan_videos = [
        stream for stream in (scan.get("streams") or [])
        if stream.get("codec_type") == "video"
    ]
    scan_audios = [
        stream for stream in (scan.get("streams") or [])
        if stream.get("codec_type") == "audio"
    ]
    if len(header_videos) != 1 or len(scan_videos) != 1:
        raise ValueError(
            f"expected one video stream, got header={len(header_videos)} scan={len(scan_videos)}"
        )
    video = scan_videos[0]
    if video.get("index") != header_videos[0].get("index"):
        raise ValueError("ffprobe decoded a different video stream than it reported in the header")

    frames = scan.get("frames") or []
    video_index = int(video["index"])
    video_frames = [
        frame for frame in frames
        if frame.get("media_type") == "video"
        and int(frame.get("stream_index", -1)) == video_index
    ]
    frame_pts = [
        int(frame["best_effort_timestamp"])
        for frame in video_frames
        if frame.get("best_effort_timestamp") not in (None, "", "N/A")
    ]
    if len(frame_pts) < 2:
        raise ValueError(f"ffprobe decoded too few timestamped video frames: {len(frame_pts)}")
    if any(current <= previous for previous, current in zip(frame_pts, frame_pts[1:])):
        raise ValueError("decoded video PTS is not strictly increasing")

    read_frames = _optional_int(video.get("nb_read_frames"))
    if read_frames is None or read_frames != len(frame_pts):
        raise ValueError(
            f"decoded frame count mismatch: nb_read_frames={read_frames}, pts={len(frame_pts)}"
        )
    declared_frames = _optional_int(video.get("nb_frames"))
    if declared_frames is not None and declared_frames != read_frames:
        raise ValueError(
            f"declared/decoded frame count mismatch: nb_frames={declared_frames}, "
            f"nb_read_frames={read_frames}"
        )

    time_base = _fraction(video.get("time_base"), "video time_base")
    frame_delta_ticks = [current - previous for previous, current in zip(frame_pts, frame_pts[1:])]
    pts_span = Fraction(frame_pts[-1] - frame_pts[0]) * time_base
    observed_fps = Fraction(read_frames - 1, 1) / pts_span
    video_seconds = _stream_seconds(video, required_ticks=True)
    assert video_seconds is not None
    median_delta_ticks = sorted(frame_delta_ticks)[len(frame_delta_ticks) // 2]
    video_start = Fraction(frame_pts[0]) * time_base
    video_end = Fraction(frame_pts[-1] + median_delta_ticks) * time_base

    audio = None
    if len(header_audios) == 1:
        if len(scan_audios) != 1 or scan_audios[0].get("index") != header_audios[0].get("index"):
            raise ValueError("ffprobe did not decode the reported audio stream")
        stream = scan_audios[0]
        audio_index = int(stream["index"])
        audio_frames = [
            frame for frame in frames
            if frame.get("media_type") == "audio"
            and int(frame.get("stream_index", -1)) == audio_index
        ]
        audio_pts = [
            int(frame["best_effort_timestamp"])
            for frame in audio_frames
            if frame.get("best_effort_timestamp") not in (None, "", "N/A")
        ]
        if not audio_pts or len(audio_pts) != len(audio_frames):
            raise ValueError(
                f"ffprobe decoded audio without complete PTS: frames={len(audio_frames)}, "
                f"pts={len(audio_pts)}"
            )
        if any(current <= previous for previous, current in zip(audio_pts, audio_pts[1:])):
            raise ValueError("decoded audio PTS is not strictly increasing")
        audio_read_frames = _optional_int(stream.get("nb_read_frames"))
        if audio_read_frames is None or audio_read_frames != len(audio_frames):
            raise ValueError(
                f"decoded audio count mismatch: nb_read_frames={audio_read_frames}, "
                f"frames={len(audio_frames)}"
            )
        sample_rate = _optional_int(stream.get("sample_rate"))
        samples = [_optional_int(frame.get("nb_samples")) for frame in audio_frames]
        if sample_rate is None or sample_rate <= 0 or any(sample is None or sample <= 0 for sample in samples):
            raise ValueError("decoded audio omitted sample_rate/nb_samples")
        audio_time_base = _fraction(stream.get("time_base"), "audio time_base")
        audio_start = Fraction(audio_pts[0]) * audio_time_base
        audio_end = Fraction(audio_pts[-1]) * audio_time_base + Fraction(samples[-1], sample_rate)
        audio = {
            "codec": stream.get("codec_name"),
            "profile": stream.get("profile"),
            "sample_rate": sample_rate,
            "channels": _optional_int(stream.get("channels")),
            "seconds": _stream_seconds(stream, required_ticks=False),
            "frame_count": audio_read_frames,
            "start": audio_start,
            "end": audio_end,
        }
    elif scan_audios:
        raise ValueError("ffprobe decoded an audio stream absent from the header")

    format_info = header.get("format") or {}
    format_duration = format_info.get("duration")
    format_size = _optional_int(format_info.get("size"))
    return {
        "probe": "ffprobe",
        "video_stream_count": len(header_videos),
        "audio_stream_count": len(header_audios),
        "codec": video.get("codec_name"),
        "profile": video.get("profile"),
        "pix_fmt": video.get("pix_fmt"),
        "width": _optional_int(video.get("width")),
        "height": _optional_int(video.get("height")),
        "nominal_fps": _fraction(video.get("r_frame_rate"), "r_frame_rate"),
        "average_fps": _fraction(video.get("avg_frame_rate"), "avg_frame_rate"),
        "observed_fps": observed_fps,
        "frame_count": read_frames,
        "video_seconds": video_seconds,
        "video_start": video_start,
        "video_end": video_end,
        "minimum_frame_interval": Fraction(min(frame_delta_ticks)) * time_base,
        "maximum_frame_interval": Fraction(max(frame_delta_ticks)) * time_base,
        # The full per-frame interval sequence, for cases whose cadence contract must
        # localize an expected in-stream interruption (mid-REC still) instead of only
        # bounding the global extremes.
        "frame_intervals": [Fraction(delta) * time_base for delta in frame_delta_ticks],
        "color_range": video.get("color_range"),
        "color_space": video.get("color_space"),
        "transfer": video.get("color_transfer"),
        "primaries": video.get("color_primaries"),
        "audio": audio,
        "format_duration": (
            None if format_duration in (None, "", "N/A")
            else _fraction(format_duration, "format duration")
        ),
        "format_size": format_size,
    }


_FFPROBE_CODEC_BY_RECORDING_CODEC = {
    "HEVC": "hevc",
    "AVC": "h264",
    "APV": "apv",
}


def _audio_contract_errors(
    info: dict,
    *,
    expected_audio: bool,
    expected_sample_rate: int | None = None,
    expected_channels: int | None = None,
) -> list[str]:
    """Return AAC stream and decoded A/V-boundary violations."""
    audio_count = info.get("audio_stream_count")
    audio = info.get("audio")
    if not expected_audio:
        if audio_count != 0:
            return [f"audio_stream_count={audio_count!r}, expected no audio"]
        return []

    if audio_count != 1 or not isinstance(audio, dict):
        return [f"audio_stream_count={audio_count!r}, expected one AAC stream"]

    errors = []
    audio_exact = {"codec": "aac"}
    if expected_sample_rate is not None:
        audio_exact["sample_rate"] = expected_sample_rate
    if expected_channels is not None:
        audio_exact["channels"] = expected_channels
    for field, expected in audio_exact.items():
        if audio.get(field) != expected:
            errors.append(f"audio.{field}={audio.get(field)!r}, expected {expected!r}")

    video_start = info.get("video_start")
    video_end = info.get("video_end")
    audio_start = audio.get("start")
    audio_end = audio.get("end")
    if not all(
        isinstance(value, Fraction)
        for value in (video_start, video_end, audio_start, audio_end)
    ):
        errors.append("decoded A/V start/end timestamps are unavailable")
        return errors

    start_delta = abs(audio_start - video_start)
    end_delta = abs(audio_end - video_end)
    if start_delta > Fraction(1, 4):
        errors.append(f"A/V start delta={float(start_delta):.3f}s, expected <=0.25s")
    if end_delta > Fraction(1, 4):
        errors.append(f"A/V end delta={float(end_delta):.3f}s, expected <=0.25s")
    return errors


def recording_contract_errors(
    info: dict,
    *,
    expected_codec: str,
    expected_width: int,
    expected_height: int,
    expected_audio: bool,
) -> list[str]:
    """Validate decoded MP4 metadata against an admitted RecordingSpec."""
    if info.get("probe") != "ffprobe":
        return ["ffprobe frame decoding was unavailable"]

    try:
        ffprobe_codec = _FFPROBE_CODEC_BY_RECORDING_CODEC[expected_codec]
    except KeyError as error:
        raise ValueError(f"unsupported RecordingSpec codec: {expected_codec!r}") from error

    errors = []
    exact = {
        "video_stream_count": 1,
        "codec": ffprobe_codec,
        "width": expected_width,
        "height": expected_height,
    }
    for field, expected in exact.items():
        if info.get(field) != expected:
            errors.append(f"{field}={info.get(field)!r}, expected {expected!r}")
    errors.extend(_audio_contract_errors(info, expected_audio=expected_audio))
    return errors


def hlg_2997_errors(
    info: dict,
    *,
    expected_width: int,
    expected_height: int,
    expected_audio: bool,
) -> list[str]:
    """Return every violation of the long-form HEVC Main10/HLG 29.97 recording contract."""
    if info.get("probe") != "ffprobe":
        return ["ffprobe frame decoding was unavailable"]

    errors = []
    exact = {
        "video_stream_count": 1,
        "codec": "hevc",
        "profile": "Main 10",
        "pix_fmt": "yuv420p10le",
        "width": expected_width,
        "height": expected_height,
        "color_range": "tv",
        "color_space": "bt2020nc",
        "transfer": "arib-std-b67",
        "primaries": "bt2020",
    }
    for field, expected in exact.items():
        if info.get(field) != expected:
            errors.append(f"{field}={info.get(field)!r}, expected {expected!r}")

    target = Fraction(30_000, 1_001)
    if info.get("nominal_fps") != target:
        errors.append(f"nominal_fps={info.get('nominal_fps')!r}, expected {target}")
    for field in ("average_fps", "observed_fps"):
        rate = info.get(field)
        if not isinstance(rate, Fraction) or not (Fraction(299, 10) <= rate < Fraction(5997, 200)):
            errors.append(f"{field}={rate!r}, expected 29.90 <= fps < 29.985")
    target_interval = Fraction(1_001, 30_000)
    minimum_interval = info.get("minimum_frame_interval")
    maximum_interval = info.get("maximum_frame_interval")
    if not isinstance(minimum_interval, Fraction) or minimum_interval < target_interval / 2:
        errors.append(
            f"minimum_frame_interval={minimum_interval!r}, expected >= {target_interval / 2}s"
        )
    if not isinstance(maximum_interval, Fraction) or maximum_interval > target_interval * 3 / 2:
        errors.append(
            f"maximum_frame_interval={maximum_interval!r}, expected <= {target_interval * 3 / 2}s"
        )

    seconds = info.get("video_seconds")
    if not isinstance(seconds, Fraction) or not (Fraction(60) <= seconds <= Fraction(90)):
        errors.append(f"video_seconds={seconds!r}, expected 60..90 seconds")
    frames = info.get("frame_count")
    if not isinstance(frames, int) or frames < 1_790:
        errors.append(f"frame_count={frames!r}, expected at least 1790 decoded frames")

    # FFmpeg 8 reports this Android AAC-LC track's profile as unknown in MP4 even though
    # codec_name/sample-rate/channel layout are authoritative, so do not invent a profile
    # assertion from an absent field.
    errors.extend(
        _audio_contract_errors(
            info,
            expected_audio=expected_audio,
            expected_sample_rate=48_000 if expected_audio else None,
            expected_channels=2 if expected_audio else None,
        )
    )
    return errors


def mp4_probe(path: Path) -> dict:
    """Decode every video frame with ffprobe; structural fallback is deliberately non-green."""
    ffprobe = shutil.which("ffprobe")
    if ffprobe:
        def run_probe(args: list[str], timeout: int) -> dict:
            out = subprocess.run(
                [ffprobe, "-v", "error", *args, "-of", "json", str(path)],
                capture_output=True,
                timeout=timeout,
            )
            stderr = out.stderr.decode(errors="replace").strip()
            if out.returncode != 0 or stderr:
                raise ValueError(
                    f"ffprobe failed: {stderr[:300] or f'exit {out.returncode}'}"
                )
            return json.loads(out.stdout)

        header = run_probe(["-show_streams", "-show_format"], timeout=60)
        scan = run_probe(
            [
                "-count_frames",
                "-show_streams",
                "-show_frames",
                "-show_entries",
                "stream=index,codec_type,codec_name,profile,pix_fmt,width,height,"
                "r_frame_rate,avg_frame_rate,time_base,duration_ts,duration,nb_frames,"
                "nb_read_frames,color_range,color_space,color_transfer,color_primaries,"
                "sample_rate,channels:"
                "frame=media_type,stream_index,best_effort_timestamp,nb_samples",
            ],
            timeout=300,
        )
        return parse_ffprobe_payload(header, scan)
    data = path.read_bytes()
    if data[4:8] != b"ftyp":
        raise ValueError("not an MP4")
    if b"moov" not in data:
        raise ValueError("MP4 lacks moov (unfinalized)")
    return {"probe": "structural", "bytes": len(data)}
