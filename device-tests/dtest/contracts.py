"""Build and source contracts that make device evidence reproducible."""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence


class ContractError(RuntimeError):
    pass


@dataclass(frozen=True)
class ApkContract:
    application_id: str
    launcher_component: str
    snapshot_component: str


def _version_key(path: Path) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", path.parent.name))


def find_build_tool(name: str) -> str:
    candidates: list[Path] = []
    for root_text in (
        os.environ.get("ANDROID_SDK_ROOT"),
        os.environ.get("ANDROID_HOME"),
        str(Path.home() / "Library/Android/sdk"),
    ):
        if root_text:
            candidates.extend(Path(root_text).glob(f"build-tools/*/{name}"))
    if candidates:
        return str(max(candidates, key=_version_key))
    resolved = shutil.which(name)
    if resolved is None:
        raise ContractError(f"Android build tool {name!r} was not found")
    return resolved


def _run_text(command: Sequence[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, timeout=30)
    if result.returncode != 0:
        raise ContractError(
            f"{' '.join(command[:3])} failed: {(result.stderr or result.stdout)[:300]}"
        )
    return result.stdout


def normalize_component(application_id: str, activity_name: str) -> str:
    if activity_name.startswith("."):
        activity_name = application_id + activity_name
    elif "." not in activity_name:
        activity_name = f"{application_id}.{activity_name}"
    return f"{application_id}/{activity_name}"


def parse_apk_contract(badging: str, xmltree: str) -> ApkContract:
    package_match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    launcher_match = re.search(r"^launchable-activity: name='([^']+)'", badging, re.MULTILINE)
    if package_match is None or launcher_match is None:
        raise ContractError("APK badging omitted package or launchable activity")
    application_id = package_match.group(1)
    activities = re.findall(
        r'android:name\([^)]*\)="([^"]+)"(?: \(Raw: "[^"]+"\))?',
        xmltree,
    )
    snapshots = [name for name in activities if name.endswith(".ui.UiSnapshotActivity")]
    if len(snapshots) != 1:
        raise ContractError(
            f"debug APK must expose exactly one UiSnapshotActivity; found {snapshots}"
        )
    return ApkContract(
        application_id=application_id,
        launcher_component=normalize_component(application_id, launcher_match.group(1)),
        snapshot_component=normalize_component(application_id, snapshots[0]),
    )


def inspect_apk_contract(
    apk: Path,
    *,
    run_text: Callable[[Sequence[str]], str] = _run_text,
    aapt: str | None = None,
    aapt2: str | None = None,
) -> ApkContract:
    """Read identity from the same APK bytes whose SHA is attested."""
    if not apk.is_file():
        raise ContractError(f"APK does not exist: {apk}")
    badging = run_text([aapt or find_build_tool("aapt"), "dump", "badging", str(apk)])
    xmltree = run_text(
        [
            aapt2 or find_build_tool("aapt2"),
            "dump",
            "xmltree",
            "--file",
            "AndroidManifest.xml",
            str(apk),
        ]
    )
    return parse_apk_contract(badging, xmltree)


def production_capture_subdir(repo_root: Path) -> str:
    source = (
        repo_root
        / "app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt"
    ).read_text(encoding="utf-8")
    matches = re.findall(r'^\s*const val CAPTURE_SUBDIR\s*=\s*"([^"]+)"', source, re.MULTILINE)
    if len(matches) != 1:
        raise ContractError(f"expected one production CAPTURE_SUBDIR, found {matches}")
    return matches[0]


def harness_source_manifest(harness_root: Path) -> list[dict[str, object]]:
    """Hash every versionable harness input, excluding only generated artifacts/caches."""
    entries: list[dict[str, object]] = []
    for path in sorted(harness_root.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        relative = path.relative_to(harness_root)
        if any(part in {"reports", "__pycache__", ".pytest_cache"} for part in relative.parts):
            continue
        payload = path.read_bytes()
        entries.append(
            {
                "path": relative.as_posix(),
                "bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            }
        )
    if not entries:
        raise ContractError(f"no harness sources found under {harness_root}")
    return entries


def source_manifest_sha256(entries: list[dict[str, object]]) -> str:
    canonical = "".join(
        f"{entry['sha256']}  {entry['bytes']}  {entry['path']}\n" for entry in entries
    ).encode()
    return hashlib.sha256(canonical).hexdigest()
