#!/usr/bin/env python3
"""Build an evidence-grade debug APK from one private source-worktree snapshot."""

from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import secrets
import stat
import subprocess
import sys
import tempfile
from collections.abc import Callable, Sequence
from typing import NamedTuple

_TOOLS_DIR = pathlib.Path(__file__).resolve().parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))
from immutable_outputs import FrozenOutputSet


IMMUTABLE_DEBUG_SOURCE_OWNER = "immutable-debug-worktree-v1"
DEBUG_SOURCE_SCOPES = (
    "app/src/main",
    "app/src/debug",
    "app/build.gradle.kts",
    "app/compose_stability.conf",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
    "gradle/wrapper/gradle-wrapper.properties",
)
LOCAL_BUILD_INPUTS = ("local.properties",)
BUILD_OUTPUT_DIRECTORIES = (".gradle", ".kotlin", "build", "app/build")

Run = Callable[[Sequence[str], pathlib.Path], subprocess.CompletedProcess[str]]
AfterSnapshot = Callable[[pathlib.Path, pathlib.Path], None]
AfterOutputsFrozen = Callable[[pathlib.Path], None]


class _SealedPath(NamedTuple):
    path: pathlib.Path
    original_mode: int
    sealed_mode: int
    device: int
    inode: int
    file_type: int
    size: int
    mtime_ns: int
    ctime_ns: int


class DebugSnapshotSeal:
    """Read-only compiler-owner paths plus metadata that a content restore cannot reset."""

    def __init__(self, entries: Sequence[_SealedPath]):
        self._entries = tuple(entries)

    def verify(self) -> None:
        for entry in self._entries:
            try:
                current = entry.path.lstat()
            except OSError as error:
                raise RuntimeError(
                    f"sealed immutable debug source owner disappeared: {entry.path}"
                ) from error
            observed = (
                current.st_dev,
                current.st_ino,
                stat.S_IFMT(current.st_mode),
                stat.S_IMODE(current.st_mode),
                current.st_size,
                current.st_mtime_ns,
                current.st_ctime_ns,
            )
            expected = (
                entry.device,
                entry.inode,
                entry.file_type,
                entry.sealed_mode,
                entry.size,
                entry.mtime_ns,
                entry.ctime_ns,
            )
            if observed != expected:
                raise RuntimeError(
                    "sealed immutable debug source owner changed during compilation: "
                    f"{entry.path}"
                )

    def release(self) -> None:
        # Restore ancestors first so TemporaryDirectory cleanup can remove the private worktree even
        # after a failed build. Never chmod a replacement that appeared during an adversarial test.
        for entry in sorted(self._entries, key=lambda item: len(item.path.parts)):
            try:
                current = entry.path.lstat()
                if (current.st_dev, current.st_ino, stat.S_IFMT(current.st_mode)) != (
                    entry.device,
                    entry.inode,
                    entry.file_type,
                ):
                    continue
                os.chmod(entry.path, entry.original_mode, follow_symlinks=False)
            except OSError:
                pass


def run_checked(command: Sequence[str], cwd: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, text=True, check=True)


def git_value(root: pathlib.Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()


def _is_in_scope(relative: str) -> bool:
    return any(relative == scope or relative.startswith(scope + "/") for scope in DEBUG_SOURCE_SCOPES)


def _write_regular(destination: pathlib.Path, payload: bytes, mode: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(
        destination,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0),
        mode,
    )
    try:
        offset = 0
        while offset < len(payload):
            written = os.write(descriptor, payload[offset:])
            if written <= 0:
                raise RuntimeError(f"short write while snapshotting {destination}")
            offset += written
    finally:
        os.close(descriptor)


def _head_entries(root: pathlib.Path, commit: str) -> list[tuple[str, str]]:
    raw = subprocess.run(
        ["git", "ls-tree", "-rz", "--full-tree", commit],
        cwd=root,
        capture_output=True,
        check=True,
    ).stdout
    entries: list[tuple[str, str]] = []
    for record in raw.split(b"\0"):
        if not record:
            continue
        metadata, separator, raw_path = record.partition(b"\t")
        fields = metadata.split()
        if not separator or len(fields) != 3 or fields[1] != b"blob":
            raise RuntimeError("Git returned a malformed debug-snapshot tree entry")
        mode = fields[0].decode("ascii")
        relative = raw_path.decode("utf-8")
        if mode not in {"100644", "100755"}:
            raise RuntimeError(
                f"debug snapshot input is not a regular tracked file: {relative} (mode {mode})"
            )
        entries.append((mode, relative))
    return entries


def _read_current_regular(path: pathlib.Path, relative: str) -> tuple[bytes, int]:
    no_follow = getattr(os, "O_NOFOLLOW", None)
    if no_follow is None:
        raise RuntimeError("this host cannot enforce no-follow debug-source reads")
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode):
        raise RuntimeError(f"debug source input must be a regular file: {relative}")
    descriptor = os.open(path, os.O_RDONLY | no_follow | getattr(os, "O_CLOEXEC", 0))
    try:
        opened = os.fstat(descriptor)
        if (before.st_dev, before.st_ino) != (opened.st_dev, opened.st_ino):
            raise RuntimeError(f"debug source input changed before open: {relative}")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        after = os.fstat(descriptor)
        fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")
        if any(getattr(opened, field) != getattr(after, field) for field in fields):
            raise RuntimeError(f"debug source input changed while reading: {relative}")
        return b"".join(chunks), stat.S_IMODE(opened.st_mode)
    finally:
        os.close(descriptor)


def _current_scope_files(root: pathlib.Path) -> list[tuple[str, bytes, int]]:
    files: list[tuple[str, bytes, int]] = []
    for scope in DEBUG_SOURCE_SCOPES:
        source = root / scope
        if source.is_symlink() or not source.exists():
            raise RuntimeError(f"debug source scope is missing or unsafe: {scope}")
        candidates = sorted(source.rglob("*")) if source.is_dir() else [source]
        for candidate in candidates:
            relative = candidate.relative_to(root).as_posix()
            mode = candidate.lstat().st_mode
            if stat.S_ISDIR(mode):
                continue
            payload, permissions = _read_current_regular(candidate, relative)
            files.append((relative, payload, permissions))
    if not files:
        raise RuntimeError("no current debug source inputs were found")
    return files


def _compiler_owner_paths(root: pathlib.Path) -> list[pathlib.Path]:
    paths = {root}

    def include_with_ancestors(path: pathlib.Path) -> None:
        current = path
        while True:
            paths.add(current)
            if current == root:
                return
            current = current.parent

    for scope in DEBUG_SOURCE_SCOPES:
        source = root / scope
        include_with_ancestors(source)
        if source.is_dir():
            for candidate in source.rglob("*"):
                include_with_ancestors(candidate)
    for relative in LOCAL_BUILD_INPUTS:
        local_input = root / relative
        if local_input.exists():
            include_with_ancestors(local_input)
    return sorted(paths, key=lambda path: (len(path.parts), path.as_posix()))


def seal_debug_snapshot(root: pathlib.Path) -> DebugSnapshotSeal:
    """Seal compiler inputs and metadata-pin project dirs that Gradle requires writable."""
    for relative in BUILD_OUTPUT_DIRECTORIES:
        (root / relative).mkdir(parents=True, exist_ok=True)
    # Gradle 9.7 rejects a configured root/module whose directory itself is not writable, even when
    # its build output already exists. Keep only those two directories writable. They remain in the
    # seal manifest, so an unlink/rename replacement changes their ctime and is rejected after build.
    gradle_writable_project_directories = {root, root / "app"}

    entries: list[_SealedPath] = []
    try:
        for path in _compiler_owner_paths(root):
            before = path.lstat()
            file_type = stat.S_IFMT(before.st_mode)
            if file_type not in {stat.S_IFREG, stat.S_IFDIR}:
                raise RuntimeError(f"debug compiler owner path is unsafe: {path}")
            original_mode = stat.S_IMODE(before.st_mode)
            sealed_mode = (
                original_mode
                if path in gradle_writable_project_directories
                else original_mode & ~0o222
            )
            os.chmod(path, sealed_mode, follow_symlinks=False)
            sealed = path.lstat()
            if (sealed.st_dev, sealed.st_ino, stat.S_IFMT(sealed.st_mode)) != (
                before.st_dev,
                before.st_ino,
                file_type,
            ):
                raise RuntimeError(f"debug compiler owner changed while sealing: {path}")
            entries.append(
                _SealedPath(
                    path=path,
                    original_mode=original_mode,
                    sealed_mode=sealed_mode,
                    device=sealed.st_dev,
                    inode=sealed.st_ino,
                    file_type=file_type,
                    size=sealed.st_size,
                    mtime_ns=sealed.st_mtime_ns,
                    ctime_ns=sealed.st_ctime_ns,
                )
            )
    except BaseException:
        DebugSnapshotSeal(entries).release()
        raise
    return DebugSnapshotSeal(entries)


def _scope_digest(root: pathlib.Path) -> str:
    digest = hashlib.sha256()
    for relative, payload, _ in _current_scope_files(root):
        digest.update(hashlib.sha256(payload).hexdigest().encode("ascii"))
        digest.update(b"  ")
        digest.update(str(len(payload)).encode("ascii"))
        digest.update(b"  ")
        digest.update(relative.encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()


def snapshot_debug_worktree(root: pathlib.Path, snapshot: pathlib.Path) -> tuple[str, str]:
    """Materialize HEAD build machinery plus one frozen copy of every current debug APK input."""
    commit = git_value(root, "rev-parse", "HEAD")
    if len(commit) != 40:
        raise RuntimeError("Git did not return a canonical debug snapshot commit")
    subprocess.run(
        ["git", "clone", "--quiet", "--shared", "--no-checkout", str(root), str(snapshot)],
        cwd=snapshot.parent,
        check=True,
    )
    subprocess.run(["git", "reset", "--quiet", commit], cwd=snapshot, check=True)

    for mode, relative in _head_entries(root, commit):
        if _is_in_scope(relative):
            continue
        payload = subprocess.run(
            ["git", "show", f"{commit}:{relative}"],
            cwd=root,
            capture_output=True,
            check=True,
        ).stdout
        _write_regular(snapshot / relative, payload, 0o755 if mode == "100755" else 0o644)

    for relative, payload, permissions in _current_scope_files(root):
        _write_regular(snapshot / relative, payload, permissions & 0o777)
    for relative in LOCAL_BUILD_INPUTS:
        source = root / relative
        if source.exists():
            payload, permissions = _read_current_regular(source, relative)
            _write_regular(snapshot / relative, payload, permissions & 0o777)
    return commit, _scope_digest(snapshot)


def build_immutable_debug(
    root: pathlib.Path,
    output_root: pathlib.Path,
    *,
    run: Run = run_checked,
    after_snapshot: AfterSnapshot | None = None,
    after_outputs_frozen: AfterOutputsFrozen | None = None,
) -> tuple[str, pathlib.Path]:
    root = root.resolve()
    output_root = output_root.resolve()
    if os.path.lexists(output_root):
        raise RuntimeError(f"refusing to overwrite immutable debug output: {output_root}")
    with tempfile.TemporaryDirectory(prefix="telecam-debug-source-") as temp_dir:
        snapshot = pathlib.Path(temp_dir) / "source"
        commit, expected_digest = snapshot_debug_worktree(root, snapshot)
        seal = seal_debug_snapshot(snapshot)
        try:
            if after_snapshot is not None:
                after_snapshot(root, snapshot)
            run(
                [
                    "./gradlew",
                    ":app:assembleDebug",
                    f"-PimmutableDebugSourceOwner={IMMUTABLE_DEBUG_SOURCE_OWNER}",
                ],
                snapshot,
            )
            # Content equality alone cannot detect A -> B -> A. The seal also pins inode, ctime,
            # mtime, type, and permissions for every compiler input and replacement-relevant parent.
            seal.verify()
            if _scope_digest(snapshot) != expected_digest:
                raise RuntimeError("immutable debug source owner changed during compilation")
            built_outputs = snapshot / "app/build/outputs/apk/debug"
            frozen_outputs = FrozenOutputSet.capture_tree(
                built_outputs,
                allow_file=lambda relative: relative.as_posix() in {
                    "app-debug.apk",
                    "output-metadata.json",
                },
                label="immutable debug",
            )
            try:
                if not any(entry.relative.as_posix() == "app-debug.apk" for entry in frozen_outputs.entries):
                    raise RuntimeError("debug build did not produce app-debug.apk")
                if after_outputs_frozen is not None:
                    after_outputs_frozen(snapshot)
                output_root.parent.mkdir(parents=True, exist_ok=True)
                with tempfile.TemporaryDirectory(
                    prefix=f".{output_root.name}-staging-",
                    dir=output_root.parent,
                ) as staging_text:
                    staging = pathlib.Path(staging_text)
                    frozen_outputs.export_into(staging / "apk/debug")
                    frozen_outputs.verify()
                    seal.verify()
                    if os.path.lexists(output_root):
                        raise RuntimeError(
                            f"refusing to overwrite immutable debug output: {output_root}"
                        )
                    os.rename(staging, output_root)
                destination = output_root / "apk/debug/app-debug.apk"
            finally:
                frozen_outputs.close()
        finally:
            seal.release()
    return commit, destination


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parent.parent,
    )
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    commit = git_value(args.root.resolve(), "rev-parse", "HEAD")
    output = args.output or (
        args.root / "app/build/immutable-debug" / f"{commit[:12]}-{secrets.token_hex(4)}"
    )
    try:
        commit, apk = build_immutable_debug(args.root, output)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"immutable debug build failed: {error}", file=sys.stderr)
        return 1
    print(f"immutable debug build complete: commit={commit} apk={apk}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
