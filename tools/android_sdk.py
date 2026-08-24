"""Resolve the Android SDK authority used by repository CLI build entry points."""

from __future__ import annotations

import os
import re
from collections.abc import Mapping
from pathlib import Path


COMPILE_SDK = 37
BUILD_TOOLS = "36.0.0"


def _local_sdk_path(root: Path) -> Path | None:
    """Read the ordinary Android `sdk.dir` property without treating other keys as authority."""
    properties = root / "local.properties"
    if not properties.is_file():
        return None
    for raw_line in properties.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        match = re.match(r"sdk\.dir\s*[:=]\s*(.*)$", line)
        if match:
            value = re.sub(r"\\([\\: =])", r"\1", match.group(1).strip())
            if not value:
                raise RuntimeError("local.properties has an empty sdk.dir")
            path = Path(value).expanduser()
            return path if path.is_absolute() else (root / path).resolve()
    return None


def _missing_components(path: Path) -> tuple[str, ...]:
    missing = []
    platform_jars = (
        path / f"platforms/android-{COMPILE_SDK}/android.jar",
        path / f"platforms/android-{COMPILE_SDK}.0/android.jar",
    )
    if not any(candidate.is_file() for candidate in platform_jars):
        missing.append(f"SDK Platform {COMPILE_SDK}")
    if not (path / f"build-tools/{BUILD_TOOLS}").is_dir():
        missing.append(f"Build Tools {BUILD_TOOLS}")
    return tuple(missing)


def _validated(path: Path, source: str) -> Path:
    candidate = path.expanduser().resolve()
    missing = _missing_components(candidate)
    if missing:
        detail = ", ".join(missing)
        raise RuntimeError(f"Android SDK from {source} is incomplete at {candidate}: missing {detail}")
    return candidate


def android_sdk_environment(
    root: Path,
    *,
    environment: Mapping[str, str] | None = None,
    home: Path | None = None,
) -> dict[str, str]:
    """Return aligned SDK variables after honoring Gradle, environment, then host conventions."""
    root = root.resolve()
    values = os.environ if environment is None else environment

    local = _local_sdk_path(root)
    if local is not None:
        selected = _validated(local, "local.properties sdk.dir")
        return {"ANDROID_HOME": str(selected), "ANDROID_SDK_ROOT": str(selected)}

    configured = [
        (name, values.get(name, "").strip())
        for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT")
        if values.get(name, "").strip()
    ]
    if configured:
        distinct = {str(Path(value).expanduser().resolve()) for _, value in configured}
        if len(distinct) > 1:
            rendered = ", ".join(f"{name}={value}" for name, value in configured)
            raise RuntimeError(f"Android SDK environment variables disagree: {rendered}")
        name, value = configured[0]
        selected = _validated(Path(value), name)
        return {"ANDROID_HOME": str(selected), "ANDROID_SDK_ROOT": str(selected)}

    home = (Path.home() if home is None else home).expanduser()
    conventional = (
        home / "Library/Android/sdk",
        home / "Android/Sdk",
    )
    for candidate in conventional:
        if candidate.is_dir():
            selected = _validated(candidate, "conventional host path")
            return {"ANDROID_HOME": str(selected), "ANDROID_SDK_ROOT": str(selected)}

    searched = ", ".join(str(path) for path in conventional)
    raise RuntimeError(
        "Android SDK not found. Add sdk.dir=<absolute-sdk-path> to the ignored local.properties "
        "or export ANDROID_HOME (and ANDROID_SDK_ROOT) before running the command. "
        f"Required: SDK Platform {COMPILE_SDK} and Build Tools {BUILD_TOOLS}. "
        f"Conventional paths checked: {searched}"
    )
