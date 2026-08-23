#!/usr/bin/env python3
"""TeleCam Pro on-device functional test runner.

Usage:
  python3 device-tests/run.py --serial 127.0.0.1:5599 --tier smoke
  python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full --allow-settings
  python3 device-tests/run.py --serial 127.0.0.1:5599 --tier all -k capture

Requires: adb on PATH with the PMA110 connected (wireless-debugging loopback proxy is
fine). ffprobe is required for a green video result; structural fallback is non-green.
Reports land in device-tests/reports/<UTC timestamp>-<run token>/ (gitignored).
"""

from __future__ import annotations

import argparse
import errno
import fcntl
import hashlib
import json
import os
import re
import secrets
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Callable, Sequence


HARNESS_ROOT = Path(__file__).resolve().parent
SOURCE_HARNESS_ROOT = Path(
    os.environ.get("TELECAM_HARNESS_SOURCE_ROOT", str(HARNESS_ROOT))
).resolve()
_HARNESS_GENERATED_PARTS = {"reports", "__pycache__", ".pytest_cache"}
_LEGACY_HARNESS_SNAPSHOT_ENV = "TELECAM_HARNESS_SNAPSHOT"
_HARNESS_CHILD_FD_OPTION = "--telecam-internal-harness-child-fd"
_HARNESS_CHILD_PROOF_SCHEMA = 1
_MAX_HARNESS_FILE_BYTES = 16 * 1024 * 1024
_MAX_HARNESS_TOTAL_BYTES = 128 * 1024 * 1024
_MAX_HARNESS_FILES = 4_096
_MAX_CHILD_PROOF_BYTES = 4_096
_READ_CHUNK_BYTES = 1024 * 1024


@dataclass(frozen=True)
class _HarnessChildProof:
    digest: str
    snapshot_root: Path
    source_root: Path
    nonce: str


def _same_file_identity(left: os.stat_result, right: os.stat_result) -> bool:
    return (left.st_dev, left.st_ino, stat.S_IFMT(left.st_mode)) == (
        right.st_dev,
        right.st_ino,
        stat.S_IFMT(right.st_mode),
    )


def _open_flags(*, directory: bool = False) -> int:
    no_follow = getattr(os, "O_NOFOLLOW", None)
    if no_follow is None:
        raise RuntimeError("this host cannot enforce no-follow harness reads")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | no_follow
    if directory:
        flags |= getattr(os, "O_DIRECTORY", 0)
    return flags


def _entry_stat(parent_fd: int, name: str) -> os.stat_result:
    return os.stat(name, dir_fd=parent_fd, follow_symlinks=False)


def _open_directory_no_follow(parent_fd: int | None, name: str, relative: Path) -> int:
    try:
        fd = os.open(name, _open_flags(directory=True), dir_fd=parent_fd)
    except OSError as error:
        raise RuntimeError(
            f"harness source directory must be a stable non-symlink: {relative.as_posix()}"
        ) from error
    opened = os.fstat(fd)
    if not stat.S_ISDIR(opened.st_mode):
        os.close(fd)
        raise RuntimeError(f"harness source must be a directory: {relative.as_posix()}")
    return fd


def _read_regular_file_no_follow(
    parent_fd: int,
    name: str,
    relative: Path,
    expected: os.stat_result,
) -> bytes:
    try:
        fd = os.open(name, _open_flags(), dir_fd=parent_fd)
    except OSError as error:
        raise RuntimeError(
            f"harness source must be a stable non-symlink file: {relative.as_posix()}"
        ) from error
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            raise RuntimeError(f"harness source must be a regular file: {relative.as_posix()}")
        if not _same_file_identity(expected, before):
            raise RuntimeError(f"harness source changed before open: {relative.as_posix()}")
        if before.st_size < 0 or before.st_size > _MAX_HARNESS_FILE_BYTES:
            raise RuntimeError(
                f"harness source file exceeds {_MAX_HARNESS_FILE_BYTES} bytes: "
                f"{relative.as_posix()}"
            )
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(fd, min(_READ_CHUNK_BYTES, _MAX_HARNESS_FILE_BYTES + 1 - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            if total > _MAX_HARNESS_FILE_BYTES:
                raise RuntimeError(
                    f"harness source file grew beyond {_MAX_HARNESS_FILE_BYTES} bytes: "
                    f"{relative.as_posix()}"
                )
        after = os.fstat(fd)
        current = _entry_stat(parent_fd, name)
        if not _same_file_identity(before, after) or not _same_file_identity(after, current):
            raise RuntimeError(f"harness source changed while reading: {relative.as_posix()}")
        if (
            before.st_size != after.st_size
            or before.st_mtime_ns != after.st_mtime_ns
            or before.st_ctime_ns != after.st_ctime_ns
            or total != after.st_size
        ):
            raise RuntimeError(f"harness source changed while reading: {relative.as_posix()}")
        return b"".join(chunks)
    finally:
        os.close(fd)


def _walk_harness_regular_files(
    harness_root: Path,
    expected_root_identity: os.stat_result | None = None,
) -> list[tuple[Path, bytes]]:
    """Read one descriptor-pinned regular-file tree without following any in-tree link."""
    root_path = Path(os.path.abspath(harness_root))
    try:
        expected_root = os.stat(root_path, follow_symlinks=False)
    except OSError as error:
        raise RuntimeError(f"harness source root is unavailable: {root_path}") from error
    if not stat.S_ISDIR(expected_root.st_mode):
        raise RuntimeError(f"harness source root must be a non-symlink directory: {root_path}")
    if expected_root_identity is not None and not _same_file_identity(
        expected_root_identity,
        expected_root,
    ):
        raise RuntimeError(f"harness source root changed before snapshot: {root_path}")
    root_fd = _open_directory_no_follow(None, str(root_path), Path("."))
    root_identity = os.fstat(root_fd)
    if not _same_file_identity(expected_root, root_identity):
        os.close(root_fd)
        raise RuntimeError(f"harness source root changed before open: {root_path}")
    files: list[tuple[Path, bytes]] = []
    total_bytes = 0

    def walk(directory_fd: int, relative_directory: Path) -> None:
        nonlocal total_bytes
        with os.scandir(directory_fd) as iterator:
            names = sorted(entry.name for entry in iterator)
        for name in names:
            relative = relative_directory / name
            if name in _HARNESS_GENERATED_PARTS:
                continue
            try:
                observed = _entry_stat(directory_fd, name)
            except OSError as error:
                raise RuntimeError(
                    f"harness source entry changed during enumeration: {relative.as_posix()}"
                ) from error
            if stat.S_ISLNK(observed.st_mode):
                raise RuntimeError(f"harness source must not be a symlink: {relative.as_posix()}")
            if stat.S_ISDIR(observed.st_mode):
                child_fd = _open_directory_no_follow(directory_fd, name, relative)
                opened = os.fstat(child_fd)
                try:
                    if not _same_file_identity(observed, opened):
                        raise RuntimeError(
                            f"harness source directory changed before open: {relative.as_posix()}"
                        )
                    walk(child_fd, relative)
                    current = _entry_stat(directory_fd, name)
                    if not _same_file_identity(opened, current):
                        raise RuntimeError(
                            f"harness source directory changed while reading: {relative.as_posix()}"
                        )
                finally:
                    os.close(child_fd)
                continue
            if not stat.S_ISREG(observed.st_mode):
                raise RuntimeError(f"harness source must be a regular file: {relative.as_posix()}")
            payload = _read_regular_file_no_follow(directory_fd, name, relative, observed)
            files.append((relative, payload))
            total_bytes += len(payload)
            if len(files) > _MAX_HARNESS_FILES or total_bytes > _MAX_HARNESS_TOTAL_BYTES:
                raise RuntimeError("harness source tree exceeds its bounded snapshot budget")

    try:
        walk(root_fd, Path())
        current_root = os.stat(root_path, follow_symlinks=False)
        if not _same_file_identity(root_identity, current_root):
            raise RuntimeError(f"harness source root changed while reading: {root_path}")
    finally:
        os.close(root_fd)
    if not files:
        raise RuntimeError(f"no harness sources found under {harness_root}")
    return files


def _canonical_non_symlink_directory(path: Path) -> tuple[Path, os.stat_result]:
    requested = Path(os.path.abspath(path))
    try:
        observed = os.stat(requested, follow_symlinks=False)
        resolved = requested.resolve(strict=True)
        canonical = os.stat(resolved, follow_symlinks=False)
    except OSError as error:
        raise RuntimeError(f"harness source root is unavailable: {requested}") from error
    if not stat.S_ISDIR(observed.st_mode) or not _same_file_identity(observed, canonical):
        raise RuntimeError(f"harness source root must be a non-symlink directory: {requested}")
    return resolved, canonical


def _write_all(fd: int, payload: bytes) -> None:
    offset = 0
    while offset < len(payload):
        written = os.write(fd, payload[offset:])
        if written <= 0:
            raise RuntimeError("short write while creating immutable harness snapshot")
        offset += written


def _write_snapshot_file(snapshot_fd: int, relative: Path, payload: bytes) -> None:
    parts = relative.parts
    if not parts or any(part in {"", ".", ".."} for part in parts):
        raise RuntimeError(f"unsafe harness snapshot path: {relative.as_posix()}")
    directory_fd = os.dup(snapshot_fd)
    try:
        for part in parts[:-1]:
            try:
                os.mkdir(part, mode=0o700, dir_fd=directory_fd)
            except FileExistsError:
                pass
            child_fd = _open_directory_no_follow(directory_fd, part, relative.parent)
            os.close(directory_fd)
            directory_fd = child_fd
        flags = (
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_CLOEXEC", 0)
            | _open_flags()
        )
        output_fd = os.open(parts[-1], flags, 0o600, dir_fd=directory_fd)
        try:
            _write_all(output_fd, payload)
        finally:
            os.close(output_fd)
    finally:
        os.close(directory_fd)


def _bootstrap_harness_source_manifest(harness_root: Path) -> list[dict[str, object]]:
    """Hash harness bytes before importing any executable harness module."""
    return [
        {
            "path": relative.as_posix(),
            "bytes": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
        }
        for relative, payload in _walk_harness_regular_files(harness_root)
    ]


def _copy_harness_snapshot(
    source_root: Path,
    snapshot_root: Path,
    *,
    expected_source_identity: os.stat_result | None = None,
) -> list[dict[str, object]]:
    """Copy descriptor-pinned regular inputs and manifest the exact copied bytes."""
    files = _walk_harness_regular_files(source_root, expected_source_identity)
    snapshot_root.mkdir(mode=0o700, parents=True)
    snapshot_fd = _open_directory_no_follow(None, str(snapshot_root), Path("."))
    try:
        for relative, payload in files:
            _write_snapshot_file(snapshot_fd, relative, payload)
    finally:
        os.close(snapshot_fd)
    return [
        {
            "path": relative.as_posix(),
            "bytes": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
        }
        for relative, payload in files
    ]


def _child_proof_payload(proof: _HarnessChildProof) -> bytes:
    return (
        json.dumps(
            {
                "schema": _HARNESS_CHILD_PROOF_SCHEMA,
                "digest": proof.digest,
                "snapshot_root": str(proof.snapshot_root),
                "source_root": str(proof.source_root),
                "nonce": proof.nonce,
            },
            sort_keys=True,
        )
        + "\n"
    ).encode("utf-8")


def _read_child_proof(fd: int) -> _HarnessChildProof:
    try:
        descriptor = os.fstat(fd)
        if not stat.S_ISFIFO(descriptor.st_mode):
            raise RuntimeError("harness child proof must arrive through a one-shot pipe")
        payload = bytearray()
        while len(payload) <= _MAX_CHILD_PROOF_BYTES:
            chunk = os.read(fd, min(1024, _MAX_CHILD_PROOF_BYTES + 1 - len(payload)))
            if not chunk:
                break
            payload.extend(chunk)
        if len(payload) > _MAX_CHILD_PROOF_BYTES:
            raise RuntimeError("harness child proof exceeds its bounded size")
    finally:
        os.close(fd)
    try:
        document = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RuntimeError("harness child proof is malformed") from error
    if not isinstance(document, dict) or set(document) != {
        "schema", "digest", "snapshot_root", "source_root", "nonce"
    }:
        raise RuntimeError("harness child proof fields are not exact")
    digest = str(document["digest"])
    nonce = str(document["nonce"])
    if document["schema"] != _HARNESS_CHILD_PROOF_SCHEMA:
        raise RuntimeError("harness child proof schema is unsupported")
    if re.fullmatch(r"[0-9a-f]{64}", digest) is None:
        raise RuntimeError("harness child proof digest is malformed")
    if re.fullmatch(r"[0-9a-f]{32}", nonce) is None:
        raise RuntimeError("harness child proof nonce is malformed")
    return _HarnessChildProof(
        digest=digest,
        snapshot_root=Path(str(document["snapshot_root"])).resolve(),
        source_root=Path(str(document["source_root"])).resolve(),
        nonce=nonce,
    )


def _consume_child_fd_argument(argv: list[str]) -> int | None:
    positions = [index for index, value in enumerate(argv) if value == _HARNESS_CHILD_FD_OPTION]
    if not positions:
        return None
    if len(positions) != 1:
        raise RuntimeError("harness child proof option must appear exactly once")
    index = positions[0]
    if index + 1 >= len(argv):
        raise RuntimeError("harness child proof descriptor is missing")
    raw = argv[index + 1]
    if re.fullmatch(r"[0-9]+", raw) is None:
        raise RuntimeError("harness child proof descriptor is malformed")
    del argv[index:index + 2]
    return int(raw)


def _validate_child_execution_root(proof: _HarnessChildProof) -> None:
    if proof.snapshot_root != HARNESS_ROOT or proof.source_root != SOURCE_HARNESS_ROOT:
        raise RuntimeError("harness child proof names the wrong execution/source root")
    if proof.snapshot_root == proof.source_root:
        raise RuntimeError("harness child execution root must differ from mutable source")
    if proof.snapshot_root.name != f"harness-{proof.digest}":
        raise RuntimeError("harness child execution root is not digest-qualified")
    parent = proof.snapshot_root.parent
    parent_stat = parent.stat()
    if parent_stat.st_uid != os.geteuid() or stat.S_IMODE(parent_stat.st_mode) & 0o077:
        raise RuntimeError("harness child execution root is not privately parent-owned")


def _run_from_immutable_harness_snapshot(
    *,
    source_root: Path = HARNESS_ROOT,
    argv: Sequence[str] | None = None,
    run_command: Callable[..., subprocess.CompletedProcess] = subprocess.run,
    temporary_parent: Path | None = None,
) -> int:
    """Execute the CLI from the exact private bytes recorded by its attestation."""
    temporary_root = Path(
        tempfile.mkdtemp(
            prefix="telecam-device-harness-",
            dir=str(temporary_parent) if temporary_parent is not None else None,
        )
    )
    staging_root = temporary_root / "staging"
    read_fd: int | None = None
    write_fd: int | None = None
    try:
        source_root, source_identity = _canonical_non_symlink_directory(source_root)
        entries = _copy_harness_snapshot(
            source_root,
            staging_root,
            expected_source_identity=source_identity,
        )
        canonical = "".join(
            f"{entry['sha256']}  {entry['bytes']}  {entry['path']}\n" for entry in entries
        ).encode()
        digest = hashlib.sha256(canonical).hexdigest()
        snapshot_root = temporary_root / f"harness-{digest}"
        staging_root.rename(snapshot_root)
        proof = _HarnessChildProof(
            digest=digest,
            snapshot_root=snapshot_root.resolve(),
            source_root=source_root,
            nonce=secrets.token_hex(16),
        )
        read_fd, write_fd = os.pipe()
        _write_all(write_fd, _child_proof_payload(proof))
        os.close(write_fd)
        write_fd = None
        environment = os.environ.copy()
        environment.pop(_LEGACY_HARNESS_SNAPSHOT_ENV, None)
        environment["TELECAM_HARNESS_SOURCE_ROOT"] = str(source_root)
        forwarded = list(sys.argv[1:] if argv is None else argv)
        completed = run_command(
            [
                sys.executable,
                str(snapshot_root / "run.py"),
                _HARNESS_CHILD_FD_OPTION,
                str(read_fd),
                *forwarded,
            ],
            env=environment,
            pass_fds=(read_fd,),
            check=False,
        )
        return completed.returncode
    finally:
        if write_fd is not None:
            os.close(write_fd)
        if read_fd is not None:
            os.close(read_fd)
        shutil.rmtree(temporary_root, ignore_errors=True)


_CHILD_PROOF: _HarnessChildProof | None = None
if __name__ == "__main__":
    child_fd = _consume_child_fd_argument(sys.argv)
    if child_fd is None:
        raise SystemExit(_run_from_immutable_harness_snapshot())
    _CHILD_PROOF = _read_child_proof(child_fd)
    _validate_child_execution_root(_CHILD_PROOF)


# This is intentionally evaluated before dtest/cases imports. A green run must verify these exact
# bytes both immediately before case dispatch and after restoration, so imports cannot execute one
# source revision while the attestation names another.
IMPORTED_HARNESS_SOURCES = _bootstrap_harness_source_manifest(HARNESS_ROOT)
_IMPORTED_MANIFEST_CANONICAL = "".join(
    f"{entry['sha256']}  {entry['bytes']}  {entry['path']}\n"
    for entry in IMPORTED_HARNESS_SOURCES
).encode()
_IMPORTED_MANIFEST_DIGEST = hashlib.sha256(_IMPORTED_MANIFEST_CANONICAL).hexdigest()
if _CHILD_PROOF is not None and _CHILD_PROOF.digest != _IMPORTED_MANIFEST_DIGEST:
    raise RuntimeError(
        "harness snapshot digest mismatch: "
        f"expected={_CHILD_PROOF.digest} actual={_IMPORTED_MANIFEST_DIGEST}"
    )

sys.path.insert(0, str(HARNESS_ROOT))

from dtest.adb import APP_ID, MAIN_ACTIVITY, Adb  # noqa: E402
from dtest.contracts import (  # noqa: E402
    ApkContract,
    ContractError,
    DebugSourceIdentity,
    inspect_apk_contract,
    production_capture_subdir,
    require_apk_source_match,
    source_manifest_sha256,
)
from dtest.framework import TIERS, run  # noqa: E402
import cases  # noqa: E402, F401  — registers all test cases

EXPECTED_MODEL = "PMA110"
EXPECTED_API = 36
REPO_ROOT = SOURCE_HARNESS_ROOT.parent


def default_apk_path(source_harness_root: Path = SOURCE_HARNESS_ROOT) -> Path:
    return source_harness_root.parent / "app/build/outputs/apk/debug/app-debug.apk"


def reports_root_path(source_harness_root: Path = SOURCE_HARNESS_ROOT) -> Path:
    return source_harness_root / "reports"


DEFAULT_APK = default_apk_path()
ATTESTATION_NAME = "run-attestation.json"
ATTESTATION_SHA_NAME = "run-attestation.sha256"
RESTORED_SETTINGS = ("font_scale", "accelerometer_rotation", "user_rotation")
REPORT_ALLOCATION_ATTEMPTS = 16


@dataclass(frozen=True)
class PhysicalDeviceIdentity:
    canonical_key: str
    source: str


@dataclass(frozen=True)
class HarnessExecutionIdentity:
    source_manifest: tuple[tuple[str, int, str], ...]
    source_manifest_sha256: str

    def as_attestation(self) -> dict[str, object]:
        return {
            "source_manifest": [
                {"path": path, "bytes": size, "sha256": sha256}
                for path, size, sha256 in self.source_manifest
            ],
            "source_manifest_sha256": self.source_manifest_sha256,
        }


def harness_execution_identity(
    harness_root: Path,
    *,
    manifest: Sequence[dict[str, object]] | None = None,
) -> HarnessExecutionIdentity:
    rows = list(manifest) if manifest is not None else _bootstrap_harness_source_manifest(harness_root)
    frozen = tuple(
        (str(row["path"]), int(row["bytes"]), str(row["sha256"]))
        for row in rows
    )
    canonical = [
        {"path": path, "bytes": size, "sha256": sha256}
        for path, size, sha256 in frozen
    ]
    return HarnessExecutionIdentity(frozen, source_manifest_sha256(canonical))


IMPORTED_HARNESS_IDENTITY = harness_execution_identity(
    HARNESS_ROOT,
    manifest=IMPORTED_HARNESS_SOURCES,
)


def require_harness_identity_unchanged(
    expected: HarnessExecutionIdentity,
    harness_root: Path,
    *,
    phase: str,
) -> None:
    current = harness_execution_identity(harness_root)
    if current != expected:
        raise ContractError(
            "device harness source drifted "
            f"{phase}: imported={expected.source_manifest_sha256} "
            f"current={current.source_manifest_sha256}"
        )


@dataclass(frozen=True)
class ReportAllocation:
    run_id: str
    directory: Path


def allocate_report_directory(
    reports_root: Path,
    *,
    timestamp: str | None = None,
    token_factory: Callable[[], str] = lambda: secrets.token_hex(6),
    max_attempts: int = REPORT_ALLOCATION_ATTEMPTS,
) -> ReportAllocation:
    """Atomically reserve one report directory; never share an existing run's evidence tree."""
    if max_attempts <= 0:
        raise ContractError("report allocation requires at least one attempt")
    stamp = timestamp or datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
    if re.fullmatch(r"[0-9]{8}-[0-9]{6}", stamp) is None:
        raise ContractError(f"report timestamp is malformed: {stamp!r}")
    try:
        reports_root.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        raise ContractError(f"could not create reports root {reports_root}: {error}") from error

    collisions: list[str] = []
    for _ in range(max_attempts):
        token = token_factory().strip().lower()
        if re.fullmatch(r"[0-9a-f]{12}", token) is None:
            raise ContractError(f"report allocation token is malformed: {token!r}")
        run_id = f"{stamp}-{token}"
        directory = reports_root / run_id
        try:
            directory.mkdir()
        except FileExistsError:
            collisions.append(run_id)
            continue
        except OSError as error:
            raise ContractError(f"could not allocate report directory {directory}: {error}") from error
        return ReportAllocation(run_id=run_id, directory=directory)
    raise ContractError(
        "could not allocate a unique report directory after "
        f"{max_attempts} atomic attempts; collisions={collisions}",
    )


class DeviceRunLockError(ContractError):
    pass


class PhysicalDeviceIdentityError(ContractError):
    pass


def host_global_device_lock_root() -> Path:
    """One per-user host namespace shared by every checkout and worktree."""
    return Path.home() / ".cache" / "telecampro-device-tests" / "locks"


def _adb_read_text(
    serial: str,
    arguments: Sequence[str],
    *,
    run_command: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> str:
    try:
        result = run_command(
            ["adb", "-s", serial, *arguments],
            capture_output=True,
            text=True,
            timeout=15,
        )
    except subprocess.TimeoutExpired as error:
        raise PhysicalDeviceIdentityError(
            f"device {serial!r} identity probe timed out: {' '.join(arguments)}"
        ) from error
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip() or f"exit {result.returncode}"
        raise PhysicalDeviceIdentityError(
            f"device {serial!r} identity probe failed: {detail}"
        )
    return result.stdout.strip()


def canonical_physical_device_identity(values: dict[str, str]) -> PhysicalDeviceIdentity:
    """Derive a non-secret attested key from the strongest stable device-side identity available."""
    for source in ("ro.serialno", "ro.boot.serialno"):
        raw = values.get(source, "").strip()
        if not raw or raw.lower() in {"null", "unknown", "none"}:
            continue
        if any(character.isspace() or ord(character) < 0x20 for character in raw):
            continue
        # Both properties normally expose the same hardware serial. Keep one canonical namespace so
        # a platform that hides only the framework alias cannot split one handset into two locks.
        digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()
        return PhysicalDeviceIdentity(
            canonical_key=f"physical-serial-sha256:{digest}",
            source=source,
        )
    raise PhysicalDeviceIdentityError(
        "device exposed no stable physical identity (ro.serialno/ro.boot.serialno)"
    )


def probe_physical_device_identity(
    serial: str,
    *,
    run_command: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> PhysicalDeviceIdentity:
    """Read-only reachability + identity probe; callers lock before any mutating ADB action."""
    state = _adb_read_text(serial, ["get-state"], run_command=run_command)
    if state != "device":
        raise PhysicalDeviceIdentityError(
            f"device {serial!r} not ready: state={state!r}; hint: adb connect {serial}"
        )
    for source, command in (
        ("ro.serialno", ["shell", "getprop", "ro.serialno"]),
        ("ro.boot.serialno", ["shell", "getprop", "ro.boot.serialno"]),
    ):
        raw = _adb_read_text(serial, command, run_command=run_command)
        try:
            return canonical_physical_device_identity({source: raw})
        except PhysicalDeviceIdentityError:
            continue
    raise PhysicalDeviceIdentityError(
        "device exposed no stable physical identity (ro.serialno/ro.boot.serialno)"
    )


class DeviceRunLock:
    """Host-global process lock for one canonical physical device identity."""

    def __init__(
        self,
        handle,
        path: Path,
        connection_alias: str,
        physical_identity: PhysicalDeviceIdentity,
        run_id: str,
    ):
        self._handle = handle
        self.path = path
        self.connection_alias = connection_alias
        self.physical_identity = physical_identity
        self.run_id = run_id
        self._released = False

    @classmethod
    def acquire(
        cls,
        lock_root: Path,
        connection_alias: str,
        physical_identity: PhysicalDeviceIdentity,
        run_id: str,
    ) -> DeviceRunLock:
        try:
            lock_root.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            raise DeviceRunLockError(f"could not create device lock directory: {error}") from error
        identity_key = hashlib.sha256(
            physical_identity.canonical_key.encode("utf-8")
        ).hexdigest()
        path = lock_root / f"{identity_key}.lock"
        try:
            handle = path.open("a+", encoding="utf-8")
        except OSError as error:
            raise DeviceRunLockError(f"could not open device lock {path}: {error}") from error
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            if error.errno not in {errno.EACCES, errno.EAGAIN}:
                handle.close()
                raise DeviceRunLockError(
                    f"could not acquire physical device lock {path}: {error}",
                ) from error
            try:
                handle.seek(0)
                holder = handle.read().strip() or "holder metadata unavailable"
            finally:
                handle.close()
            raise DeviceRunLockError(
                "physical device is already owned by another harness run: " + holder,
            ) from error

        try:
            handle.seek(0)
            handle.truncate()
            json.dump(
                {
                    "pid": os.getpid(),
                    "run_id": run_id,
                    "connection_alias": connection_alias,
                    "physical_device_key": physical_identity.canonical_key,
                    "physical_identity_source": physical_identity.source,
                    "acquired_at_utc": utc_now(),
                },
                handle,
                sort_keys=True,
            )
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        except OSError as error:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            finally:
                handle.close()
            raise DeviceRunLockError(f"could not record device lock ownership: {error}") from error
        return cls(handle, path, connection_alias, physical_identity, run_id)

    def release(self) -> None:
        if self._released:
            return
        try:
            fcntl.flock(self._handle.fileno(), fcntl.LOCK_UN)
        except OSError as error:
            raise DeviceRunLockError(
                "could not release physical device lock for "
                f"run {self.run_id} ({self.connection_alias!r}): {error}",
            ) from error
        finally:
            self._handle.close()
            self._released = True

    def __enter__(self) -> DeviceRunLock:
        return self

    def __exit__(self, _type, _value, _traceback) -> None:
        self.release()


def write_run_identity(
    allocation: ReportAllocation,
    *,
    serial: str,
    physical_identity: PhysicalDeviceIdentity | None = None,
) -> None:
    """Record the allocated run before lock acquisition or any ADB interaction can fail."""
    payload = {
        "schema_version": 2,
        "run_id": allocation.run_id,
        "connection_alias": serial,
        "physical_device_key": (
            physical_identity.canonical_key if physical_identity is not None else None
        ),
        "physical_identity_source": (
            physical_identity.source if physical_identity is not None else None
        ),
        "allocated_at_utc": utc_now(),
    }
    try:
        (allocation.directory / "run-identity.json").write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except OSError as error:
        raise ContractError(
            f"could not record run identity under {allocation.directory}: {error}",
        ) from error


def write_run_failure(
    allocation: ReportAllocation,
    *,
    serial: str,
    phase: str,
    error: str,
    physical_identity: PhysicalDeviceIdentity | None = None,
) -> None:
    """Persist a non-green preflight/ownership failure in the uniquely owned report directory."""
    payload = {
        "schema_version": 2,
        "run_id": allocation.run_id,
        "connection_alias": serial,
        "physical_device_key": (
            physical_identity.canonical_key if physical_identity is not None else None
        ),
        "physical_identity_source": (
            physical_identity.source if physical_identity is not None else None
        ),
        "phase": phase,
        "error": error,
        "recorded_at_utc": utc_now(),
    }
    try:
        (allocation.directory / "run-failure.json").write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except OSError as write_error:
        print(
            f"also could not record run failure under {allocation.directory}: {write_error}",
            file=sys.stderr,
        )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def base_apk_path(pm_path_output: str) -> str | None:
    paths = [line.removeprefix("package:") for line in pm_path_output.splitlines() if line.startswith("package:")]
    return next((path for path in paths if path.endswith("/base.apk")), paths[0] if paths else None)


def installed_apk_sha256(sha256sum_output: str) -> str | None:
    """Parse ``sha256sum`` output defensively; empty/malformed output must refuse, not traceback."""
    fields = sha256sum_output.split(maxsplit=1)
    if not fields or re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]) is None:
        return None
    return fields[0].lower()


def require_installed_apk_match(expected_sha256: str, installed_output: str) -> str:
    """Return the installed digest only when it proves byte identity with the host APK."""
    actual = installed_apk_sha256(installed_output)
    if actual is None:
        raise ContractError("installed base.apk SHA-256 output is missing or malformed")
    if actual != expected_sha256:
        raise ContractError(
            f"stale/mismatched install: host={expected_sha256}, installed={actual}"
        )
    return actual


def utc_now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def git_identity(repo_root: Path = REPO_ROOT) -> dict[str, object]:
    """Return the exact source revision and a reviewable working-tree state."""
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    status = subprocess.run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.splitlines()
    return {"head": head, "dirty": bool(status), "status": status}


def device_state(adb: Adb) -> dict[str, object]:
    """Capture the foreground, display, and operator state that a run must restore."""
    metrics = adb.display_metrics()
    return {
        "foreground_component": adb.resumed_activity(),
        "display": {
            "width_px": metrics.width_px,
            "height_px": metrics.height_px,
            "density_dpi": metrics.density_dpi,
        },
        "settings": {
            name: adb.shell(f"settings get system {name}")
            for name in RESTORED_SETTINGS
        },
        "locale": adb.locale_state(),
    }


def restoration_errors(
    before: dict[str, object],
    after: dict[str, object] | None,
    *,
    expected_main_activity: str = MAIN_ACTIVITY,
) -> list[str]:
    """Require a returned MainActivity and unchanged operator-controlled settings."""
    if after is None:
        return ["post-run device state could not be collected"]
    errors = []
    if after.get("foreground_component") != expected_main_activity:
        errors.append(
            "foreground component was not restored to MainActivity: "
            f"{after.get('foreground_component')!r}"
        )
    if after.get("display") != before.get("display"):
        errors.append(
            f"display changed from {before.get('display')!r} to {after.get('display')!r}"
        )
    before_settings = before.get("settings")
    after_settings = after.get("settings")
    if not isinstance(before_settings, dict) or not isinstance(after_settings, dict):
        errors.append("pre/post settings were not available for restoration comparison")
        return errors
    for name in RESTORED_SETTINGS:
        if after_settings.get(name) != before_settings.get(name):
            errors.append(
                f"{name} changed from {before_settings.get(name)!r} "
                f"to {after_settings.get(name)!r}"
            )
    if after.get("locale") != before.get("locale"):
        errors.append(f"locale changed from {before.get('locale')!r} to {after.get('locale')!r}")
    return errors


def attested_exit_code(case_exit_code: int, errors: list[str]) -> int:
    """Preserve case failures while making an otherwise-green restoration failure non-green."""
    return case_exit_code if case_exit_code != 0 else (2 if errors else 0)


def artifact_manifest(report_dir: Path) -> list[dict[str, object]]:
    """Hash every regular report artifact except the self-referential attestation pair."""
    excluded = {ATTESTATION_NAME, ATTESTATION_SHA_NAME}
    artifacts = []
    for path in sorted(report_dir.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        relative = path.relative_to(report_dir).as_posix()
        if relative in excluded:
            continue
        artifacts.append(
            {
                "path": relative,
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )
    return artifacts


def append_attestation_summary(
    report_dir: Path,
    *,
    final_exit_code: int,
    errors: list[str],
    source_identity: str,
) -> None:
    report = report_dir / "report.md"
    if not report.is_file():
        return
    status = "PASS" if not errors else "FAIL"
    lines = [
        "",
        "## Run attestation",
        "",
        f"- evidence verification: **{status}**",
        f"- final CLI exit code: `{final_exit_code}`",
        f"- APK source identity: `{source_identity}`",
        f"- metadata: `{ATTESTATION_NAME}` (`{ATTESTATION_SHA_NAME}`)",
    ]
    if errors:
        lines.append("- verification errors: " + "; ".join(errors))
    with report.open("a", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")


def write_attestation(report_dir: Path, document: dict[str, object]) -> tuple[Path, Path]:
    """Write canonical-enough JSON plus a SHA-256 integrity sidecar."""
    attestation = report_dir / ATTESTATION_NAME
    payload = (json.dumps(document, indent=2, sort_keys=True) + "\n").encode()
    attestation.write_bytes(payload)
    checksum = hashlib.sha256(payload).hexdigest()
    sidecar = report_dir / ATTESTATION_SHA_NAME
    sidecar.write_text(f"{checksum}  {ATTESTATION_NAME}\n", encoding="utf-8")
    return attestation, sidecar


def run_locked_device(
    args: argparse.Namespace,
    tiers: list[str],
    expected_apk: Path,
    expected_sha: str,
    apk_contract: ApkContract,
    packaged_source: DebugSourceIdentity,
    production_subdir: str,
    harness_identity: HarnessExecutionIdentity,
    physical_identity: PhysicalDeviceIdentity,
    allocation: ReportAllocation,
) -> int:
    """Run every mutating ADB operation while owning the canonical physical-device lock."""
    report_dir = allocation.directory
    # The endpoint could reconnect to another handset between the initial read-only probe and lock
    # acquisition. Re-prove identity under the canonical lock before constructing the mutating Adb
    # facade, and bind the exact imported harness bytes immediately before case dispatch below.
    locked_identity = probe_physical_device_identity(args.serial)
    if locked_identity != physical_identity:
        raise PhysicalDeviceIdentityError(
            "ADB endpoint changed physical identity before locked execution: "
            f"expected={physical_identity.canonical_key} actual={locked_identity.canonical_key}"
        )

    media_relative_path = f"DCIM/{production_subdir}/"
    adb = Adb(
        args.serial,
        report_dir / "evidence",
        allow_destructive=args.allow_destructive,
        application_id=apk_contract.application_id,
        main_activity=apk_contract.launcher_component,
        snapshot_activity=apk_contract.snapshot_component,
        media_relative_path=media_relative_path,
    )
    model = adb.shell("getprop ro.product.model")
    api_text = adb.shell("getprop ro.build.version.sdk")
    if model != EXPECTED_MODEL or api_text != str(EXPECTED_API):
        print(
            f"refusing device {args.serial}: expected {EXPECTED_MODEL}/API {EXPECTED_API}, "
            f"got {model or '?'} / API {api_text or '?'}",
            file=sys.stderr,
        )
        return 2

    installed = adb.shell(f"pm path {apk_contract.application_id} || true")
    installed_apk = base_apk_path(installed)
    if installed_apk is None:
        print(f"{apk_contract.application_id} is not installed — deploy first "
              "(adb install -r app/build/outputs/apk/debug/app-debug.apk)", file=sys.stderr)
        return 2

    try:
        actual_sha = require_installed_apk_match(
            expected_sha,
            adb.shell(f"sha256sum {shlex.quote(installed_apk)}"),
        )
    except ContractError as error:
        print(f"refusing {error}", file=sys.stderr)
        return 2

    try:
        workspace = git_identity()
        before_state = device_state(adb)
        build_fingerprint = adb.shell("getprop ro.build.fingerprint")
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"could not capture run identity/state: {error}", file=sys.stderr)
        return 2

    require_harness_identity_unchanged(
        harness_identity,
        HARNESS_ROOT,
        phase="before case dispatch",
    )
    started_at = utc_now()
    case_exit_code = run(
        adb,
        tiers,
        args.filter,
        report_dir,
        allow_destructive=args.allow_destructive,
        allow_settings=args.allow_settings,
        allow_media_writes=args.allow_media_writes,
        allow_partial=args.allow_partial,
    )
    state_error = None
    try:
        after_state = device_state(adb)
    except (OSError, RuntimeError) as error:
        after_state = None
        state_error = f"{type(error).__name__}: {error}"
    restore_errors = restoration_errors(
        before_state,
        after_state,
        expected_main_activity=apk_contract.launcher_component,
    )
    if state_error is not None:
        restore_errors.append(state_error)
    verification_errors = list(restore_errors)
    try:
        require_harness_identity_unchanged(
            harness_identity,
            HARNESS_ROOT,
            phase="after case execution",
        )
    except ContractError as error:
        verification_errors.append(str(error))
    try:
        final_identity = probe_physical_device_identity(args.serial)
        if final_identity != physical_identity:
            verification_errors.append(
                "ADB endpoint changed physical identity during execution: "
                f"expected={physical_identity.canonical_key} actual={final_identity.canonical_key}"
            )
    except PhysicalDeviceIdentityError as error:
        verification_errors.append(str(error))
    final_exit_code = attested_exit_code(case_exit_code, verification_errors)
    append_attestation_summary(
        report_dir,
        final_exit_code=final_exit_code,
        errors=verification_errors,
        source_identity=packaged_source.identity,
    )
    document: dict[str, object] = {
        "schema_version": 4,
        "run_id": allocation.run_id,
        "started_at_utc": started_at,
        "completed_at_utc": utc_now(),
        "invocation": {
            "serial": args.serial,
            "tiers": tiers,
            "filter": args.filter,
            "apk": str(expected_apk),
            "evidence_mode": "partial" if args.allow_partial else "complete-required",
            "approvals": {
                "destructive": args.allow_destructive,
                "settings": args.allow_settings,
                "media_writes": args.allow_media_writes,
            },
        },
        "source": packaged_source.as_attestation(),
        "workspace": workspace,
        "harness": {
            **harness_identity.as_attestation(),
            "identity_basis": "private digest-qualified snapshot bytes imported and executed",
            "pre_run_verified": True,
            "post_run_verified": not any(
                error.startswith("device harness source drifted")
                for error in verification_errors
            ),
        },
        "device": {
            "serial": args.serial,
            "connection_alias": args.serial,
            "physical_device_key": physical_identity.canonical_key,
            "physical_identity_source": physical_identity.source,
            "model": model,
            "api": int(api_text),
            "build_fingerprint": build_fingerprint,
        },
        "apk": {
            "host_path": str(expected_apk),
            "installed_path": installed_apk,
            "host_sha256": expected_sha,
            "installed_sha256": actual_sha,
            "application_id": apk_contract.application_id,
            "launcher_component": apk_contract.launcher_component,
            "snapshot_component": apk_contract.snapshot_component,
            "media_relative_path": media_relative_path,
        },
        "state": {
            "before": before_state,
            "after": after_state,
            "restoration_errors": restore_errors,
            "verification_errors": verification_errors,
        },
        "result": {
            "case_exit_code": case_exit_code,
            "final_exit_code": final_exit_code,
            "restoration": "pass" if not restore_errors else "fail",
            "evidence_verification": "pass" if not verification_errors else "fail",
        },
        "artifacts": artifact_manifest(report_dir),
    }
    try:
        attestation, sidecar = write_attestation(report_dir, document)
    except OSError as error:
        print(f"could not write run attestation: {error}", file=sys.stderr)
        return 2
    print(f"Run ID: {allocation.run_id}")
    print(f"Attestation: {attestation}")
    print(f"Attestation SHA-256: {sidecar}")
    print(f"APK source identity: {packaged_source.identity}")
    if verification_errors:
        print("Evidence verification failed: " + "; ".join(verification_errors), file=sys.stderr)
    return final_exit_code


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--serial", required=True, help="adb serial, e.g. 127.0.0.1:5599")
    ap.add_argument("--tier", action="append", choices=[*TIERS, "all"], default=None,
                    help="tier(s) to run; repeatable; default smoke")
    ap.add_argument("-k", dest="filter", default=None, help="substring filter on case names")
    ap.add_argument("--apk", type=Path, default=DEFAULT_APK,
                    help="exact host debug APK that must match the installed base.apk")
    ap.add_argument("--allow-destructive", action="store_true",
                    help="allow cases that force-stop the app; requires explicit operator approval")
    ap.add_argument("--allow-settings", action="store_true",
                    help="allow cases that change persisted shooting settings; requires explicit approval")
    ap.add_argument("--allow-media-writes", action="store_true",
                    help="allow cases that create photos or videos; requires explicit approval")
    ap.add_argument("--allow-partial", action="store_true",
                    help="permit approval-gated skips and attest an intentionally partial tier")
    args = ap.parse_args()

    tiers = args.tier or ["smoke"]
    if "all" in tiers:
        tiers = list(TIERS)

    expected_apk = args.apk.resolve()
    try:
        expected_sha = sha256_file(expected_apk)
        apk_contract = inspect_apk_contract(expected_apk)
        packaged_source = require_apk_source_match(expected_apk, REPO_ROOT)
        if sha256_file(expected_apk) != expected_sha:
            raise ContractError("APK changed while its manifest contract was being inspected")
        production_subdir = production_capture_subdir(REPO_ROOT)
        require_harness_identity_unchanged(
            IMPORTED_HARNESS_IDENTITY,
            HARNESS_ROOT,
            phase="before device preflight",
        )
    except (ContractError, OSError) as error:
        print(f"could not establish APK/harness contract: {error}", file=sys.stderr)
        return 2
    if apk_contract.application_id != APP_ID:
        print(
            f"refusing non-debug APK identity {apk_contract.application_id!r}; expected {APP_ID!r}",
            file=sys.stderr,
        )
        return 2
    reports_root = reports_root_path()
    try:
        allocation = allocate_report_directory(reports_root)
        write_run_identity(allocation, serial=args.serial)
    except ContractError as error:
        print(f"could not allocate/record device-test report: {error}", file=sys.stderr)
        return 2

    try:
        physical_identity = probe_physical_device_identity(args.serial)
        write_run_identity(
            allocation,
            serial=args.serial,
            physical_identity=physical_identity,
        )
    except (PhysicalDeviceIdentityError, ContractError) as error:
        write_run_failure(
            allocation,
            serial=args.serial,
            phase="physical-device-identity",
            error=str(error),
        )
        print(
            f"device identity failed for run {allocation.run_id}: {error}\n"
            f"failure report: {allocation.directory / 'run-failure.json'}",
            file=sys.stderr,
        )
        return 2

    try:
        with DeviceRunLock.acquire(
            host_global_device_lock_root(),
            args.serial,
            physical_identity,
            allocation.run_id,
        ):
            return run_locked_device(
                args,
                tiers,
                expected_apk,
                expected_sha,
                apk_contract,
                packaged_source,
                production_subdir,
                IMPORTED_HARNESS_IDENTITY,
                physical_identity,
                allocation,
            )
    except DeviceRunLockError as error:
        write_run_failure(
            allocation,
            serial=args.serial,
            phase="device-lock",
            error=str(error),
            physical_identity=physical_identity,
        )
        print(
            f"device-test ownership failed for run {allocation.run_id}: {error}\n"
            f"failure report: {allocation.directory / 'run-failure.json'}",
            file=sys.stderr,
        )
        return 2
    except (PhysicalDeviceIdentityError, ContractError) as error:
        write_run_failure(
            allocation,
            serial=args.serial,
            phase="locked-preflight",
            error=str(error),
            physical_identity=physical_identity,
        )
        print(
            f"device-test preflight failed for run {allocation.run_id}: {error}\n"
            f"failure report: {allocation.directory / 'run-failure.json'}",
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
