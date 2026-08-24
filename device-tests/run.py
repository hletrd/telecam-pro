#!/usr/bin/env python3
"""TeleCam Pro on-device functional test runner.

Usage:
  python3 device-tests/run.py --apk "$EVIDENCE_APK" --serial 127.0.0.1:5599 --tier smoke
  python3 device-tests/run.py --apk "$EVIDENCE_APK" --serial 127.0.0.1:5599 --tier full --allow-settings
  python3 device-tests/run.py --apk "$EVIDENCE_APK" --serial 127.0.0.1:5599 --tier all -k capture

Requires: adb on PATH with the PMA110 connected (wireless-debugging loopback proxy is
fine), and EVIDENCE_APK set to the exact path printed by tools/build_immutable_debug.py.
ffprobe is required for a green video result; structural fallback is non-green.
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
import runpy
import secrets
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
from contextlib import contextmanager
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
_FORBIDDEN_SERIALIZED_CHILD_OPTION = "--telecam-internal-harness-child-proof"
_OPTIMIZED_PYTHON_ERROR = (
    "device evidence requires assertions: optimized Python (-O/PYTHONOPTIMIZE) is forbidden"
)
_MAX_HARNESS_FILE_BYTES = 16 * 1024 * 1024
_MAX_HARNESS_TOTAL_BYTES = 128 * 1024 * 1024
_MAX_HARNESS_FILES = 4_096
_READ_CHUNK_BYTES = 1024 * 1024


@dataclass(frozen=True)
class _HarnessChildProof:
    digest: str
    snapshot_root: Path
    source_root: Path
    nonce: str


@dataclass(frozen=True)
class ApkInspectionSnapshot:
    source_path: Path
    private_path: Path
    sha256: str
    private_seal: "PrivateRegularFileSeal"

    def verify(self, phase: str) -> None:
        self.private_seal.verify(phase)


@dataclass(frozen=True)
class PrivateRegularFileSeal:
    """One read-only private artifact identity retained across every independent inspector."""

    path: Path
    device: int
    inode: int
    mode: int
    size: int
    modified_ns: int
    changed_ns: int
    sha256: str

    @classmethod
    def create(cls, path: Path, expected_sha256: str) -> "PrivateRegularFileSeal":
        os.chmod(path, 0o400, follow_symlinks=False)
        attributes = path.lstat()
        size, digest = _digest_regular_absolute_no_follow(path)
        if digest != expected_sha256 or size != attributes.st_size:
            raise ContractError("private APK snapshot changed while it was sealed")
        return cls(
            path=path,
            device=attributes.st_dev,
            inode=attributes.st_ino,
            mode=stat.S_IMODE(attributes.st_mode),
            size=attributes.st_size,
            modified_ns=attributes.st_mtime_ns,
            changed_ns=attributes.st_ctime_ns,
            sha256=digest,
        )

    def verify(self, phase: str) -> None:
        try:
            attributes = self.path.lstat()
            size, digest = _digest_regular_absolute_no_follow(self.path)
        except (OSError, ContractError) as error:
            raise ContractError(f"private APK snapshot changed {phase}: {error}") from error
        observed = (
            attributes.st_dev,
            attributes.st_ino,
            stat.S_IFMT(attributes.st_mode),
            stat.S_IMODE(attributes.st_mode),
            attributes.st_size,
            attributes.st_mtime_ns,
            attributes.st_ctime_ns,
            size,
            digest,
        )
        expected = (
            self.device,
            self.inode,
            stat.S_IFREG,
            self.mode,
            self.size,
            self.modified_ns,
            self.changed_ns,
            self.size,
            self.sha256,
        )
        if observed != expected:
            raise ContractError(f"private APK snapshot changed {phase}")


def _open_regular_absolute_no_follow(path: Path) -> tuple[int, list[int], os.stat_result]:
    """Pin one regular leaf with no-follow semantics beneath a canonical parent directory."""
    absolute = Path(os.path.abspath(path))
    if not absolute.is_absolute() or not absolute.parts:
        raise ContractError(f"APK path is unsafe: {path}")
    descriptors: list[int] = []
    try:
        expected = os.stat(absolute, follow_symlinks=False)
        if stat.S_ISLNK(expected.st_mode) or not stat.S_ISREG(expected.st_mode):
            raise ContractError(f"APK must be a no-follow regular file: {absolute}")
        # macOS exposes /var and /tmp through stable system symlinks. Canonicalize the parent once,
        # then pin that directory and no-follow-open only the artifact leaf. All later use is through
        # the file descriptor/private copy, and the original leaf identity is rechecked after copy.
        parent = absolute.parent.resolve(strict=True)
        current = os.open(parent, _open_flags(directory=True))
        descriptors.append(current)
        file_fd = os.open(absolute.name, _open_flags(), dir_fd=current)
        descriptors.append(file_fd)
        attributes = os.fstat(file_fd)
        if not stat.S_ISREG(attributes.st_mode) or not _same_file_identity(expected, attributes):
            raise ContractError(f"APK changed before no-follow open: {absolute}")
        return file_fd, descriptors, attributes
    except OSError as error:
        for descriptor in reversed(descriptors):
            try:
                os.close(descriptor)
            except OSError:
                pass
        raise ContractError(f"APK must be a stable no-follow regular file: {absolute}: {error}") from error


def _digest_regular_absolute_no_follow(path: Path) -> tuple[int, str]:
    file_fd, descriptors, before = _open_regular_absolute_no_follow(path)
    digest = hashlib.sha256()
    total = 0
    try:
        while True:
            chunk = os.read(file_fd, _READ_CHUNK_BYTES)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
        after = os.fstat(file_fd)
        current = os.stat(Path(os.path.abspath(path)), follow_symlinks=False)
        if (
            not _same_file_identity(before, after)
            or not _same_file_identity(after, current)
            or before.st_size != after.st_size
            or before.st_mtime_ns != after.st_mtime_ns
            or before.st_ctime_ns != after.st_ctime_ns
            or total != after.st_size
        ):
            raise ContractError(f"regular file changed while reading: {path}")
        return total, digest.hexdigest()
    finally:
        for descriptor in reversed(descriptors):
            try:
                os.close(descriptor)
            except OSError:
                pass


@contextmanager
def apk_inspection_snapshot(source_path: Path):
    """Copy/hash one pinned APK inode and expose only the private copy to inspectors."""
    source_path = Path(os.path.abspath(source_path))
    file_fd, descriptors, before = _open_regular_absolute_no_follow(source_path)
    with tempfile.TemporaryDirectory(prefix="telecam-device-apk-") as temp_dir:
        private_path = Path(temp_dir) / "inspected.apk"
        digest = hashlib.sha256()
        try:
            output_fd = os.open(
                private_path,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0),
                0o600,
            )
            try:
                while True:
                    chunk = os.read(file_fd, _READ_CHUNK_BYTES)
                    if not chunk:
                        break
                    digest.update(chunk)
                    _write_all(output_fd, chunk)
                os.fsync(output_fd)
            finally:
                os.close(output_fd)
            after = os.fstat(file_fd)
            current = os.stat(source_path, follow_symlinks=False)
            if (
                not _same_file_identity(before, after)
                or not _same_file_identity(after, current)
                or before.st_size != after.st_size
                or before.st_mtime_ns != after.st_mtime_ns
                or before.st_ctime_ns != after.st_ctime_ns
                or private_path.stat().st_size != after.st_size
            ):
                raise ContractError("APK changed while its private inspection snapshot was copied")
            expected_sha256 = digest.hexdigest()
            seal = PrivateRegularFileSeal.create(private_path, expected_sha256)
            yield ApkInspectionSnapshot(source_path, private_path, expected_sha256, seal)
        finally:
            for descriptor in reversed(descriptors):
                try:
                    os.close(descriptor)
                except OSError:
                    pass


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
    before_child: Callable[[Path], None] | None = None,
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
        forwarded = list(sys.argv[1:] if argv is None else argv)
        if before_child is not None:
            before_child(snapshot_root)
        # Fork preserves one unforgeable-in-argv object capability held by this already-running
        # outer orchestrator. The child executes the copied run.py through runpy without an exec
        # boundary, so a direct caller cannot mint child mode with CLI/env/proof bytes. A direct
        # snapshot invocation merely enters this outer path and creates its own fresh immutable copy.
        authority = object()
        pid = os.fork()
        if pid == 0:
            try:
                os.environ.pop(_LEGACY_HARNESS_SNAPSHOT_ENV, None)
                os.environ["TELECAM_HARNESS_SOURCE_ROOT"] = str(source_root)
                sys.argv = [str(snapshot_root / "run.py"), *forwarded]
                runpy.run_path(
                    str(snapshot_root / "run.py"),
                    run_name="__main__",
                    init_globals={
                        "_TELECAM_OUTER_AUTHORITY": authority,
                        "_TELECAM_OUTER_AUTHORITY_CONFIRM": authority,
                        "_TELECAM_OUTER_PROOF": proof,
                    },
                )
            except SystemExit as exit_signal:
                code = exit_signal.code if isinstance(exit_signal.code, int) else 1
                os._exit(code)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
            os._exit(0)
        _, status = os.waitpid(pid, 0)
        return os.waitstatus_to_exitcode(status)
    finally:
        shutil.rmtree(temporary_root, ignore_errors=True)


_CHILD_PROOF: _HarnessChildProof | None = None
if __name__ == "__main__":
    # cases.py owns device verdicts with assertions. This runs before the outer immutable snapshot
    # and runs again in the fork/runpy child, so neither boundary can attest with stripped checks.
    if sys.flags.optimize != 0:
        print(_OPTIMIZED_PYTHON_ERROR, file=sys.stderr)
        raise SystemExit(2)
    outer_authority = globals().get("_TELECAM_OUTER_AUTHORITY")
    outer_confirmation = globals().get("_TELECAM_OUTER_AUTHORITY_CONFIRM")
    outer_proof = globals().get("_TELECAM_OUTER_PROOF")
    if outer_authority is None or outer_authority is not outer_confirmation or outer_proof is None:
        if _FORBIDDEN_SERIALIZED_CHILD_OPTION in sys.argv:
            raise RuntimeError(
                "serialized harness child authority is forbidden; only inherited fork authority is accepted"
            )
        raise SystemExit(_run_from_immutable_harness_snapshot())
    _CHILD_PROOF = outer_proof
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
    ProvenDebugSourceContract,
    inspect_apk_contract,
    require_apk_source_match,
    source_manifest_sha256,
)
from dtest.framework import TIERS, run  # noqa: E402
import cases  # noqa: E402, F401  — registers all test cases

EXPECTED_MODEL = "PMA110"
EXPECTED_API = 36
REPO_ROOT = SOURCE_HARNESS_ROOT.parent


def reports_root_path(source_harness_root: Path = SOURCE_HARNESS_ROOT) -> Path:
    return source_harness_root / "reports"


def evidence_install_command(source_apk: Path) -> str:
    """Copy-paste remediation bound to the exact immutable APK supplied for this run."""
    return f"adb install -r {shlex.quote(str(source_apk))}"


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


def frozen_workspace_identity(source: DebugSourceIdentity) -> dict[str, object]:
    """Attestation projection of the same scoped checkout snapshot proven against the APK."""
    return {
        "head": source.commit,
        "dirty": source.dirty,
        "status": (
            ["packageable debug inputs differ from the attested HEAD tree"]
            if source.dirty
            else []
        ),
        "identity_basis": "descriptor-frozen packageable inputs versus immutable HEAD tree",
    }


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


class ReportRootOwner:
    """One no-follow report directory inode retained through finalization and rollback."""

    def __init__(self, path: Path, descriptor: int, identity: os.stat_result):
        self.path = path
        self.descriptor = descriptor
        self.identity = identity
        self._closed = False

    @classmethod
    def open(cls, report_dir: Path) -> "ReportRootOwner":
        path = Path(os.path.abspath(report_dir))
        expected = os.stat(path, follow_symlinks=False)
        if not stat.S_ISDIR(expected.st_mode):
            raise ContractError(f"report root must be a non-symlink directory: {path}")
        descriptor = _open_directory_no_follow(None, str(path), Path("."))
        identity = os.fstat(descriptor)
        if not _same_file_identity(expected, identity):
            os.close(descriptor)
            raise ContractError("report root changed before ownership")
        return cls(path, descriptor, identity)

    def verify_path(self, phase: str) -> None:
        try:
            current = os.stat(self.path, follow_symlinks=False)
        except OSError as error:
            raise ContractError(f"report root changed {phase}: {error}") from error
        if not _same_file_identity(self.identity, current):
            raise ContractError(f"report root changed {phase}")

    def duplicate(self) -> int:
        if self._closed:
            raise ContractError("report root owner is closed")
        return os.dup(self.descriptor)

    def close(self) -> None:
        if not self._closed:
            os.close(self.descriptor)
            self._closed = True

    def __enter__(self) -> "ReportRootOwner":
        return self

    def __exit__(self, _type, _value, _traceback) -> None:
        self.close()


def artifact_manifest(
    report_dir: Path,
    *,
    allow_attestation_outputs: bool = False,
    root_owner: ReportRootOwner | None = None,
    allowed_reserved_outputs: frozenset[str] | None = None,
) -> list[dict[str, object]]:
    """Freeze one exact no-follow regular-file identity for the completed report tree."""
    owned_here = root_owner is None
    owner = root_owner or ReportRootOwner.open(report_dir)
    root_fd = owner.duplicate()
    root_identity = os.fstat(root_fd)
    allowed_outputs = allowed_reserved_outputs
    if allowed_outputs is None:
        allowed_outputs = (
            frozenset({ATTESTATION_NAME, ATTESTATION_SHA_NAME})
            if allow_attestation_outputs
            else frozenset()
        )
    artifacts: list[dict[str, object]] = []

    def hash_file(parent_fd: int, name: str, relative: Path, expected: os.stat_result) -> None:
        try:
            fd = os.open(name, _open_flags(), dir_fd=parent_fd)
        except OSError as error:
            raise ContractError(
                f"report artifact must be a stable non-symlink file: {relative.as_posix()}"
            ) from error
        try:
            before = os.fstat(fd)
            if not stat.S_ISREG(before.st_mode) or not _same_file_identity(expected, before):
                raise ContractError(f"report artifact changed before open: {relative.as_posix()}")
            digest = hashlib.sha256()
            total = 0
            while True:
                chunk = os.read(fd, _READ_CHUNK_BYTES)
                if not chunk:
                    break
                digest.update(chunk)
                total += len(chunk)
            after = os.fstat(fd)
            current = _entry_stat(parent_fd, name)
            if (
                not _same_file_identity(before, after)
                or not _same_file_identity(after, current)
                or before.st_size != after.st_size
                or before.st_mtime_ns != after.st_mtime_ns
                or before.st_ctime_ns != after.st_ctime_ns
                or total != after.st_size
            ):
                raise ContractError(f"report artifact changed while reading: {relative.as_posix()}")
            artifacts.append(
                {"path": relative.as_posix(), "bytes": total, "sha256": digest.hexdigest()}
            )
        finally:
            os.close(fd)

    def walk(directory_fd: int, relative_directory: Path) -> None:
        with os.scandir(directory_fd) as iterator:
            initial_names = sorted(entry.name for entry in iterator)
        for name in initial_names:
            relative = relative_directory / name
            if relative_directory == Path() and name in {ATTESTATION_NAME, ATTESTATION_SHA_NAME}:
                if name in allowed_outputs:
                    continue
                raise ContractError(f"reserved attestation output already exists: {name}")
            try:
                observed = _entry_stat(directory_fd, name)
            except OSError as error:
                raise ContractError(
                    f"report artifact disappeared during freeze: {relative.as_posix()}"
                ) from error
            if stat.S_ISLNK(observed.st_mode):
                raise ContractError(f"report artifact must not be a symlink: {relative.as_posix()}")
            if stat.S_ISDIR(observed.st_mode):
                child_fd = _open_directory_no_follow(directory_fd, name, relative)
                opened = os.fstat(child_fd)
                try:
                    if not _same_file_identity(observed, opened):
                        raise ContractError(
                            f"report directory changed before open: {relative.as_posix()}"
                        )
                    walk(child_fd, relative)
                    current = _entry_stat(directory_fd, name)
                    if not _same_file_identity(opened, current):
                        raise ContractError(
                            f"report directory changed while reading: {relative.as_posix()}"
                        )
                finally:
                    os.close(child_fd)
                continue
            if not stat.S_ISREG(observed.st_mode):
                raise ContractError(f"report artifact must be a regular file: {relative.as_posix()}")
            hash_file(directory_fd, name, relative, observed)
        with os.scandir(directory_fd) as iterator:
            final_names = sorted(entry.name for entry in iterator)
        if final_names != initial_names:
            raise ContractError(
                f"report artifact set changed during freeze under {relative_directory.as_posix() or '.'}"
            )

    try:
        walk(root_fd, Path())
        if not _same_file_identity(root_identity, os.fstat(root_fd)):
            raise ContractError("report root descriptor changed during evidence freeze")
        owner.verify_path("during evidence freeze")
    finally:
        os.close(root_fd)
        if owned_here:
            owner.close()
    return sorted(artifacts, key=lambda item: str(item["path"]))


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


@dataclass
class ReservedReportOutput:
    name: str
    descriptor: int
    identity: os.stat_result
    payload: bytes

    def verify(self, root: ReportRootOwner, phase: str) -> None:
        opened = os.fstat(self.descriptor)
        try:
            current = os.stat(self.name, dir_fd=root.descriptor, follow_symlinks=False)
        except OSError as error:
            raise ContractError(f"reserved output changed {phase}: {self.name}: {error}") from error
        if (
            not stat.S_ISREG(opened.st_mode)
            or not _same_file_identity(self.identity, opened)
            or not _same_file_identity(opened, current)
            or opened.st_size != len(self.payload)
            or opened.st_mtime_ns != self.identity.st_mtime_ns
            or opened.st_ctime_ns != self.identity.st_ctime_ns
        ):
            raise ContractError(f"reserved output changed {phase}: {self.name}")
        os.lseek(self.descriptor, 0, os.SEEK_SET)
        observed = bytearray()
        while len(observed) < len(self.payload):
            chunk = os.read(self.descriptor, len(self.payload) - len(observed))
            if not chunk:
                break
            observed.extend(chunk)
        if bytes(observed) != self.payload or os.read(self.descriptor, 1):
            raise ContractError(f"reserved output content changed {phase}: {self.name}")

    def close(self) -> None:
        if self.descriptor >= 0:
            os.close(self.descriptor)
            self.descriptor = -1


def _write_report_output_exclusive(
    root: ReportRootOwner,
    name: str,
    payload: bytes,
) -> ReservedReportOutput:
    try:
        fd = os.open(
            name,
            os.O_RDWR | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0) | _open_flags(),
            0o600,
            dir_fd=root.descriptor,
        )
        try:
            _write_all(fd, payload)
            os.fsync(fd)
            identity = os.fstat(fd)
        except BaseException:
            os.close(fd)
            raise
    except FileExistsError as error:
        raise ContractError(f"refusing to overwrite reserved attestation output: {name}") from error
    return ReservedReportOutput(name, fd, identity, payload)


def _rollback_attestation_outputs(
    root: ReportRootOwner,
    outputs: Sequence[ReservedReportOutput],
) -> list[str]:
    """Unlink every root-level name for only the exact output inodes this attempt created."""
    errors: list[str] = []
    for output in reversed(tuple(outputs)):
        try:
            with os.scandir(root.descriptor) as iterator:
                aliases = [
                    entry.name
                    for entry in iterator
                    if _same_file_identity(entry.stat(follow_symlinks=False), output.identity)
                ]
            for name in aliases:
                os.unlink(name, dir_fd=root.descriptor)
            remaining = os.fstat(output.descriptor).st_nlink
            if remaining != 0:
                errors.append(f"{output.name}: exact output inode still has {remaining} link(s)")
        except OSError as error:
            errors.append(f"{output.name}: {error}")
    return errors


def write_attestation(
    report_dir: Path,
    document: dict[str, object],
    *,
    expected_artifacts: list[dict[str, object]] | None = None,
) -> tuple[Path, Path]:
    """Write the attestation pair only around one stable, exact report artifact set."""
    payload = (json.dumps(document, indent=2, sort_keys=True) + "\n").encode()
    checksum = hashlib.sha256(payload).hexdigest()
    sidecar_payload = f"{checksum}  {ATTESTATION_NAME}\n".encode()
    created: list[ReservedReportOutput] = []
    with ReportRootOwner.open(report_dir) as root:
        try:
            expected = (
                expected_artifacts
                if expected_artifacts is not None
                else artifact_manifest(report_dir, root_owner=root)
            )
            if artifact_manifest(report_dir, root_owner=root) != expected:
                raise ContractError("report artifact set changed before attestation write")

            # JSON is provisional and not independently consumable as a green terminal record. The
            # checksum sidecar is written only after the exact evidence set and JSON inode are proven.
            attestation_output = _write_report_output_exclusive(
                root,
                ATTESTATION_NAME,
                payload,
            )
            created.append(attestation_output)
            attestation_output.verify(root, "after JSON write")
            if artifact_manifest(
                report_dir,
                root_owner=root,
                allowed_reserved_outputs=frozenset({ATTESTATION_NAME}),
            ) != expected:
                raise ContractError("report artifact set changed before sidecar commit")

            sidecar_output = _write_report_output_exclusive(
                root,
                ATTESTATION_SHA_NAME,
                sidecar_payload,
            )
            created.append(sidecar_output)
            attestation_output.verify(root, "after sidecar write")
            sidecar_output.verify(root, "after sidecar write")
            if artifact_manifest(
                report_dir,
                root_owner=root,
                allowed_reserved_outputs=frozenset(
                    {ATTESTATION_NAME, ATTESTATION_SHA_NAME}
                ),
            ) != expected:
                raise ContractError("report artifact set changed during attestation write")
            root.verify_path("before attestation publication")
            return report_dir / ATTESTATION_NAME, report_dir / ATTESTATION_SHA_NAME
        except BaseException as error:
            rollback_errors = _rollback_attestation_outputs(root, created)
            if rollback_errors:
                raise ContractError(
                    "attestation finalization failed and exact-output rollback was incomplete: "
                    + "; ".join(rollback_errors)
                ) from error
            raise
        finally:
            for output in created:
                output.close()


def run_locked_device(
    args: argparse.Namespace,
    tiers: list[str],
    expected_apk: Path,
    source_apk: Path,
    expected_sha: str,
    apk_contract: ApkContract,
    packaged_source: ProvenDebugSourceContract,
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
        print(
            f"{apk_contract.application_id} is not installed — deploy the exact evidence APK first "
            f"({evidence_install_command(source_apk)})",
            file=sys.stderr,
        )
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
        workspace = frozen_workspace_identity(packaged_source.source)
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
    try:
        frozen_artifacts = artifact_manifest(report_dir)
    except ContractError as error:
        print(f"could not freeze run evidence: {error}", file=sys.stderr)
        return 2
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
            "host_path": str(source_apk),
            "inspected_private_snapshot": True,
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
        "artifacts": frozen_artifacts,
    }
    try:
        attestation, sidecar = write_attestation(
            report_dir,
            document,
            expected_artifacts=frozen_artifacts,
        )
    except (ContractError, OSError) as error:
        print(f"could not write run attestation: {error}", file=sys.stderr)
        return 2
    print(f"Run ID: {allocation.run_id}")
    print(f"Attestation: {attestation}")
    print(f"Attestation SHA-256: {sidecar}")
    print(f"APK source identity: {packaged_source.identity}")
    if verification_errors:
        print("Evidence verification failed: " + "; ".join(verification_errors), file=sys.stderr)
    return final_exit_code


def _run_snapshotted_cli(
    args: argparse.Namespace,
    tiers: list[str],
    snapshot: ApkInspectionSnapshot,
) -> int:
    expected_apk = snapshot.private_path
    source_apk = snapshot.source_path
    try:
        expected_sha = snapshot.sha256
        snapshot.verify("before inspection")
        apk_contract = inspect_apk_contract(
            expected_apk,
            verify_artifact=snapshot.verify,
        )
        packaged_source = require_apk_source_match(expected_apk, REPO_ROOT)
        snapshot.verify("after ZIP/source inspection")
        production_subdir = packaged_source.capture_subdir
        require_harness_identity_unchanged(
            IMPORTED_HARNESS_IDENTITY,
            HARNESS_ROOT,
            phase="before device preflight",
        )
        snapshot.verify("before device preflight")
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
                source_apk,
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


def _run_authorized_child_cli() -> int:
    """Parse and execute one already-authorized immutable-snapshot child invocation."""
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--serial", required=True, help="adb serial, e.g. 127.0.0.1:5599")
    ap.add_argument("--tier", action="append", choices=[*TIERS, "all"], default=None,
                    help="tier(s) to run; repeatable; default smoke")
    ap.add_argument("-k", dest="filter", default=None, help="substring filter on case names")
    ap.add_argument(
        "--apk",
        type=Path,
        required=True,
        help="wrapper-emitted immutable debug APK that must match the installed base.apk",
    )
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

    source_apk = Path(os.path.abspath(args.apk))
    try:
        with apk_inspection_snapshot(source_apk) as snapshot:
            return _run_snapshotted_cli(args, tiers, snapshot)
    except (ContractError, OSError) as error:
        print(f"could not establish APK snapshot: {error}", file=sys.stderr)
        return 2


def main() -> int:
    """Refuse imported/live execution; only the inherited snapshot child may run cases."""
    if _CHILD_PROOF is None:
        print(
            "refusing device harness execution without inherited private-snapshot authority; "
            "invoke device-tests/run.py as a script",
            file=sys.stderr,
        )
        return 2
    return _run_authorized_child_cli()


if __name__ == "__main__":
    raise SystemExit(main())
