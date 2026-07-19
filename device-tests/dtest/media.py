"""Pulled-file validators. Stdlib parsing for stills; ffprobe (if present) for video."""

from __future__ import annotations

import json
import shutil
import struct
import subprocess
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
        if marker == 0xD9 or marker == 0xDA:
            break
        seg_len = struct.unpack_from(">H", data, i + 2)[0]
        if marker == 0xE1 and data[i + 4 : i + 8] == b"Exif":
            has_exif = True
        if marker in (0xC0, 0xC1, 0xC2, 0xC3):  # SOF0-3
            h, w = struct.unpack_from(">HH", data, i + 5)
        i += 2 + seg_len
    return {"width": w, "height": h, "exif": has_exif, "bytes": len(data)}


def heic_valid(path: Path) -> bool:
    data = path.read_bytes()
    return len(data) > 100_000 and data[4:8] == b"ftyp" and (b"heic" in data[8:24] or b"mif1" in data[8:24])


def dng_valid(path: Path) -> bool:
    data = path.read_bytes()
    return len(data) > 1_000_000 and data[:4] in (b"II*\x00", b"MM\x00*")


def mp4_probe(path: Path) -> dict:
    """ffprobe if available; otherwise a structural ftyp+moov check."""
    if shutil.which("ffprobe"):
        out = subprocess.run(
            [
                "ffprobe", "-v", "error", "-show_streams", "-show_format",
                "-of", "json", str(path),
            ],
            capture_output=True,
            timeout=60,
        )
        if out.returncode != 0:
            raise ValueError(f"ffprobe failed: {out.stderr.decode(errors='replace')[:200]}")
        j = json.loads(out.stdout)
        v = next((s for s in j["streams"] if s["codec_type"] == "video"), None)
        a = next((s for s in j["streams"] if s["codec_type"] == "audio"), None)
        if v is None:
            raise ValueError("no video stream")
        return {
            "probe": "ffprobe",
            "codec": v.get("codec_name"),
            "profile": v.get("profile"),
            "width": v.get("width"),
            "height": v.get("height"),
            "transfer": v.get("color_transfer"),
            "primaries": v.get("color_primaries"),
            "duration": float(j["format"].get("duration", 0)),
            "audio": a.get("codec_name") if a else None,
        }
    data = path.read_bytes()
    if data[4:8] != b"ftyp":
        raise ValueError("not an MP4")
    if b"moov" not in data:
        raise ValueError("MP4 lacks moov (unfinalized)")
    return {"probe": "structural", "codec": None, "duration": -1.0, "bytes": len(data)}
