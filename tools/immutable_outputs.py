"""Descriptor-owned generated-output snapshots for immutable build wrappers."""

from __future__ import annotations

import hashlib
import os
import pathlib
import stat
from collections.abc import Callable, Iterable
from dataclasses import dataclass


_READ_CHUNK_BYTES = 1024 * 1024


def _identity(attributes: os.stat_result) -> tuple[int, int, int, int, int, int, int]:
    return (
        attributes.st_dev,
        attributes.st_ino,
        stat.S_IFMT(attributes.st_mode),
        stat.S_IMODE(attributes.st_mode),
        attributes.st_size,
        attributes.st_mtime_ns,
        attributes.st_ctime_ns,
    )


@dataclass(frozen=True)
class FrozenOutput:
    relative: pathlib.PurePosixPath
    payload: bytes
    mode: int


@dataclass
class _OwnedDescriptor:
    descriptor: int
    identity: tuple[int, int, int, int, int, int, int]
    original_mode: int
    is_directory: bool


class FrozenOutputSet:
    """One read-only output tree retained by descriptor until atomic publication."""

    def __init__(
        self,
        entries: Iterable[FrozenOutput],
        owners: Iterable[_OwnedDescriptor],
    ) -> None:
        self.entries = tuple(entries)
        self._owners = tuple(owners)
        self._closed = False

    @classmethod
    def capture_tree(
        cls,
        root: pathlib.Path,
        *,
        allow_file: Callable[[pathlib.PurePosixPath], bool],
        label: str,
    ) -> "FrozenOutputSet":
        """Freeze every allowed regular file under ``root`` and reject every other member."""
        directory_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_CLOEXEC", 0)
        no_follow = getattr(os, "O_NOFOLLOW", 0)
        owners: list[_OwnedDescriptor] = []
        outputs: list[FrozenOutput] = []

        def own_directory(descriptor: int) -> _OwnedDescriptor:
            try:
                before = os.fstat(descriptor)
                if not stat.S_ISDIR(before.st_mode):
                    raise RuntimeError(f"{label} output owner is not a directory")
                original_mode = stat.S_IMODE(before.st_mode)
                os.fchmod(descriptor, original_mode & ~0o222)
                sealed = os.fstat(descriptor)
                owner = _OwnedDescriptor(descriptor, _identity(sealed), original_mode, True)
                owners.append(owner)
                return owner
            except BaseException:
                os.close(descriptor)
                raise

        def own_file(parent_fd: int, name: str, relative: pathlib.PurePosixPath) -> None:
            if not allow_file(relative):
                raise RuntimeError(f"unexpected {label} output: {relative.as_posix()}")
            descriptor = os.open(
                name,
                os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_CLOEXEC", 0) | no_follow,
                dir_fd=parent_fd,
            )
            try:
                before = os.fstat(descriptor)
                if not stat.S_ISREG(before.st_mode):
                    raise RuntimeError(f"{label} output is not a regular file: {relative.as_posix()}")
                original_mode = stat.S_IMODE(before.st_mode)
                os.fchmod(descriptor, original_mode & ~0o222)
                sealed = os.fstat(descriptor)
                digest = hashlib.sha256()
                chunks: list[bytes] = []
                while True:
                    chunk = os.read(descriptor, _READ_CHUNK_BYTES)
                    if not chunk:
                        break
                    digest.update(chunk)
                    chunks.append(chunk)
                final = os.fstat(descriptor)
                if _identity(final) != _identity(sealed):
                    raise RuntimeError(
                        f"{label} output changed while it was frozen: {relative.as_posix()}"
                    )
                payload = b"".join(chunks)
                if len(payload) != final.st_size or hashlib.sha256(payload).digest() != digest.digest():
                    raise RuntimeError(
                        f"{label} output read was incomplete: {relative.as_posix()}"
                    )
                owners.append(
                    _OwnedDescriptor(descriptor, _identity(final), original_mode, False)
                )
                outputs.append(FrozenOutput(relative, payload, original_mode & 0o777))
                descriptor = -1
            finally:
                if descriptor >= 0:
                    os.close(descriptor)

        def walk(directory_fd: int, relative_dir: pathlib.PurePosixPath) -> None:
            with os.scandir(directory_fd) as iterator:
                names = sorted(entry.name for entry in iterator)
            for name in names:
                relative = relative_dir / name
                observed = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
                if stat.S_ISLNK(observed.st_mode):
                    raise RuntimeError(f"{label} output must not be a symlink: {relative.as_posix()}")
                if stat.S_ISDIR(observed.st_mode):
                    child_fd = os.open(name, directory_flags | no_follow, dir_fd=directory_fd)
                    opened = os.fstat(child_fd)
                    if _identity(opened) != _identity(observed):
                        os.close(child_fd)
                        raise RuntimeError(
                            f"{label} output directory changed before open: {relative.as_posix()}"
                        )
                    own_directory(child_fd)
                    walk(child_fd, relative)
                elif stat.S_ISREG(observed.st_mode):
                    own_file(directory_fd, name, relative)
                else:
                    raise RuntimeError(
                        f"{label} output is not a regular file or directory: {relative.as_posix()}"
                    )

        try:
            root_fd = os.open(root, directory_flags | no_follow)
            own_directory(root_fd)
            walk(root_fd, pathlib.PurePosixPath())
            if not outputs:
                raise RuntimeError(f"no {label} outputs were produced")
            frozen = cls(sorted(outputs, key=lambda item: item.relative.as_posix()), owners)
            frozen.verify()
            return frozen
        except BaseException:
            cls((), owners).close()
            raise

    def verify(self) -> None:
        if self._closed:
            raise RuntimeError("generated-output owner is already closed")
        for owner in self._owners:
            if _identity(os.fstat(owner.descriptor)) != owner.identity:
                raise RuntimeError("sealed generated-output owner changed before publication")

    def export_into(self, destination_root: pathlib.Path) -> None:
        """Write captured bytes exclusively; no generated pathname is reopened."""
        for entry in self.entries:
            destination = destination_root.joinpath(*entry.relative.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            descriptor = os.open(
                destination,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0),
                entry.mode,
            )
            try:
                view = memoryview(entry.payload)
                written = 0
                while written < len(view):
                    count = os.write(descriptor, view[written:])
                    if count <= 0:
                        raise RuntimeError(
                            f"could not export generated output: {entry.relative.as_posix()}"
                        )
                    written += count
                os.fsync(descriptor)
            finally:
                os.close(descriptor)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        # Restore directory ancestors first so the private temporary checkout can be removed.
        for owner in sorted(self._owners, key=lambda item: not item.is_directory):
            try:
                os.fchmod(owner.descriptor, owner.original_mode)
            except OSError:
                pass
        for owner in reversed(self._owners):
            try:
                os.close(owner.descriptor)
            except OSError:
                pass
