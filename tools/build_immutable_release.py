#!/usr/bin/env python3
"""Build release tasks from an exact, private export of the current Git commit."""

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


Run = Callable[[Sequence[str], pathlib.Path], subprocess.CompletedProcess[str]]
AfterSnapshot = Callable[[pathlib.Path, pathlib.Path], None]
AfterOutputsFrozen = Callable[[pathlib.Path], None]

BUILD_OUTPUT_DIRECTORIES = (".gradle", ".kotlin", "build", "app/build")
RELEASE_OUTPUT_PREFIXES = (
    "apk/release/",
    "bundle/release/",
    "mapping/release/",
    "sdk-dependencies/release/",
)
RELEASE_OUTPUT_FILES = frozenset({"logs/manifest-merger-release-report.txt"})


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


class ReleaseSnapshotSeal:
    """Read-only release inputs plus metadata a transient content restore cannot reset."""

    def __init__(self, entries: Sequence[_SealedPath]):
        self._entries = tuple(entries)

    def verify(self) -> None:
        for entry in self._entries:
            try:
                current = entry.path.lstat()
            except OSError as error:
                raise RuntimeError(
                    f"sealed immutable release source owner disappeared: {entry.path}"
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
                    "sealed immutable release source owner changed during compilation: "
                    f"{entry.path}"
                )

    def release(self) -> None:
        # Restore ancestors first so TemporaryDirectory can remove the private checkout. Never chmod
        # an attacker-installed replacement whose identity differs from the sealed owner.
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
    result = subprocess.run(
        ["git", *arguments],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


def require_clean_commit(root: pathlib.Path) -> tuple[str, str]:
    status = subprocess.run(
        [
            "git",
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
            "--ignore-submodules=none",
        ],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    if status:
        raise RuntimeError(f"release source is not clean:\n{status.rstrip()}")
    commit = git_value(root, "rev-parse", "HEAD")
    tree = git_value(root, "rev-parse", "HEAD^{tree}")
    if len(commit) != 40 or len(tree) != 40:
        raise RuntimeError("Git did not return canonical release commit/tree identities")
    return commit, tree


def tracked_entries(root: pathlib.Path) -> list[tuple[str, str]]:
    """Return exact Git modes and paths; release inputs must be ordinary blob entries."""
    raw = subprocess.run(
        ["git", "ls-files", "--stage", "-z"],
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
        if not separator or len(fields) != 3:
            raise RuntimeError("Git returned a malformed tracked-source record")
        mode = fields[0].decode("ascii")
        relative = raw_path.decode("utf-8")
        if mode not in {"100644", "100755"}:
            raise RuntimeError(
                f"release source is not a regular tracked file: {relative} (mode {mode})"
            )
        entries.append((mode, relative))
    return entries


def sha256_regular_beneath(root: pathlib.Path, relative: str) -> str:
    """Hash one regular file through no-follow directory/file descriptors."""
    parts = pathlib.PurePosixPath(relative).parts
    if not parts or any(part in {"", ".", ".."} for part in parts):
        raise RuntimeError(f"release source path is unsafe: {relative!r}")
    directory_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_CLOEXEC", 0)
    no_follow = getattr(os, "O_NOFOLLOW", 0)
    descriptors: list[int] = []
    try:
        current = os.open(root, directory_flags | no_follow)
        descriptors.append(current)
        for component in parts[:-1]:
            current = os.open(component, directory_flags | no_follow, dir_fd=current)
            descriptors.append(current)
        file_fd = os.open(
            parts[-1],
            os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_CLOEXEC", 0) | no_follow,
            dir_fd=current,
        )
        descriptors.append(file_fd)
        attributes = os.fstat(file_fd)
        if not stat.S_ISREG(attributes.st_mode):
            raise RuntimeError(f"release source is not a regular file: {relative}")
        digest = hashlib.sha256()
        with os.fdopen(os.dup(file_fd), "rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        final_attributes = os.fstat(file_fd)
        identity_fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")
        if any(getattr(attributes, field) != getattr(final_attributes, field) for field in identity_fields):
            raise RuntimeError(f"release source changed while it was hashed: {relative}")
        return digest.hexdigest()
    except OSError as error:
        raise RuntimeError(f"could not safely read release source {relative}: {error}") from error
    finally:
        for descriptor in reversed(descriptors):
            try:
                os.close(descriptor)
            except OSError:
                pass


def read_regular_beneath(root: pathlib.Path, relative: str) -> tuple[bytes, int]:
    """Read one stable no-follow local input without exposing its content in errors."""
    parts = pathlib.PurePosixPath(relative).parts
    if not parts or any(part in {"", ".", ".."} for part in parts):
        raise RuntimeError(f"release local-input path is unsafe: {relative!r}")
    directory_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_CLOEXEC", 0)
    no_follow = getattr(os, "O_NOFOLLOW", 0)
    descriptors: list[int] = []
    try:
        current = os.open(root, directory_flags | no_follow)
        descriptors.append(current)
        for component in parts[:-1]:
            current = os.open(component, directory_flags | no_follow, dir_fd=current)
            descriptors.append(current)
        file_fd = os.open(
            parts[-1],
            os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_CLOEXEC", 0) | no_follow,
            dir_fd=current,
        )
        descriptors.append(file_fd)
        before = os.fstat(file_fd)
        if not stat.S_ISREG(before.st_mode):
            raise RuntimeError(f"release local input is not a regular file: {relative}")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(file_fd, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        after = os.fstat(file_fd)
        fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")
        if any(getattr(before, field) != getattr(after, field) for field in fields):
            raise RuntimeError(f"release local input changed while it was copied: {relative}")
        payload = b"".join(chunks)
        if len(payload) != after.st_size:
            raise RuntimeError(f"release local input read was incomplete: {relative}")
        return payload, stat.S_IMODE(after.st_mode)
    except OSError as error:
        raise RuntimeError(f"could not safely read release local input {relative}: {error}") from error
    finally:
        for descriptor in reversed(descriptors):
            try:
                os.close(descriptor)
            except OSError:
                pass


def write_regular_exclusive(path: pathlib.Path, payload: bytes, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(
        path,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0),
        mode & 0o777,
    )
    try:
        view = memoryview(payload)
        written = 0
        while written < len(view):
            count = os.write(descriptor, view[written:])
            if count <= 0:
                raise RuntimeError(f"could not copy release local input: {path.name}")
            written += count
    finally:
        os.close(descriptor)


def export_commit(root: pathlib.Path, destination: pathlib.Path, commit: str) -> dict[str, str]:
    subprocess.run(
        ["git", "clone", "--quiet", "--shared", "--no-checkout", str(root), str(destination)],
        cwd=destination.parent,
        check=True,
    )
    subprocess.run(
        ["git", "checkout", "--quiet", "--detach", commit],
        cwd=destination,
        check=True,
    )
    tracked = tracked_entries(destination)
    return {
        relative: sha256_regular_beneath(destination, relative)
        for _, relative in tracked
    }


def _release_owner_paths(
    root: pathlib.Path,
    tracked_paths: Sequence[str],
) -> list[pathlib.Path]:
    paths = {root}

    def include_with_ancestors(path: pathlib.Path) -> None:
        current = path
        while True:
            paths.add(current)
            if current == root:
                return
            current = current.parent

    for relative in tracked_paths:
        include_with_ancestors(root / relative)
    return sorted(paths, key=lambda path: (len(path.parts), path.as_posix()))


def seal_release_snapshot(
    root: pathlib.Path,
    tracked_paths: Sequence[str],
) -> ReleaseSnapshotSeal:
    """Seal every tracked compiler/package input while leaving only build roots writable."""
    for relative in BUILD_OUTPUT_DIRECTORIES:
        (root / relative).mkdir(parents=True, exist_ok=True)
    # Gradle requires the configured project directories themselves to remain writable even when
    # every output directory already exists. Their identity metadata is still sealed, so a rename or
    # replacement is detected before any artifact can leave the private checkout.
    gradle_writable_project_directories = {root, root / "app"}

    entries: list[_SealedPath] = []
    try:
        for path in _release_owner_paths(root, tracked_paths):
            before = path.lstat()
            file_type = stat.S_IFMT(before.st_mode)
            if file_type not in {stat.S_IFREG, stat.S_IFDIR}:
                raise RuntimeError(f"release compiler owner path is unsafe: {path}")
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
                raise RuntimeError(f"release compiler owner changed while sealing: {path}")
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
        ReleaseSnapshotSeal(entries).release()
        raise
    return ReleaseSnapshotSeal(entries)


def copy_local_build_inputs(root: pathlib.Path, snapshot: pathlib.Path) -> tuple[str, ...]:
    """Descriptor-copy local inputs and return every private path that must join the seal."""
    copied: list[str] = []
    frozen_properties: dict[str, bytes] = {}
    for name in ("local.properties", "keystore.properties"):
        source = root / name
        if os.path.lexists(source):
            payload, mode = read_regular_beneath(root, name)
            write_regular_exclusive(snapshot / name, payload, mode)
            copied.append(name)
            frozen_properties[name] = payload
    signing_payload = frozen_properties.get("keystore.properties")
    if signing_payload is not None:
        try:
            signing_text = signing_payload.decode("utf-8")
        except UnicodeDecodeError as error:
            raise RuntimeError("release signing properties are not UTF-8") from error
        store_file = next(
            (
                line.partition("=")[2].strip()
                for line in signing_text.splitlines()
                if line.partition("=")[0].strip() == "storeFile"
            ),
            "",
        )
        configured_store = pathlib.Path(store_file)
        if store_file:
            if configured_store.is_absolute():
                raise RuntimeError("absolute release keystore paths are outside the immutable owner")
            normalized_store = pathlib.PurePosixPath(os.path.normpath(store_file))
            if not normalized_store.parts or any(
                part in {"", ".", ".."} for part in normalized_store.parts
            ):
                raise RuntimeError("relative release keystore escapes the repository")
            relative_store = pathlib.Path(*normalized_store.parts)
            relative_text = relative_store.as_posix()
            payload, mode = read_regular_beneath(root, relative_text)
            write_regular_exclusive(snapshot / relative_store, payload, mode)
            copied.append(relative_text)
    return tuple(copied)


def verify_export(snapshot: pathlib.Path, expected: dict[str, str]) -> None:
    changed: list[str] = []
    for relative, digest in expected.items():
        try:
            actual = sha256_regular_beneath(snapshot, relative)
        except RuntimeError:
            changed.append(relative)
            continue
        if actual != digest:
            changed.append(relative)
    if changed:
        raise RuntimeError("immutable release snapshot changed during build: " + ", ".join(changed))
    status = subprocess.run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        cwd=snapshot,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    ignored_sources = subprocess.run(
        [
            "git", "ls-files", "--others", "--ignored", "--exclude-standard", "--",
            "app/src/main", "app/src/release",
        ],
        cwd=snapshot,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    if status or ignored_sources:
        details = "\n".join(part.rstrip() for part in (status, ignored_sources) if part)
        raise RuntimeError(f"release build created or changed source inputs:\n{details}")


def build_immutable_release(
    root: pathlib.Path,
    tasks: Sequence[str],
    output_root: pathlib.Path,
    *,
    run: Run = run_checked,
    after_snapshot: AfterSnapshot | None = None,
    after_outputs_frozen: AfterOutputsFrozen | None = None,
) -> tuple[str, str]:
    root = root.resolve()
    output_root = output_root.resolve()
    commit, tree = require_clean_commit(root)
    if os.path.lexists(output_root):
        raise RuntimeError(f"refusing to overwrite immutable release output: {output_root}")

    with tempfile.TemporaryDirectory(prefix="telecam-release-source-") as temp_dir:
        snapshot = pathlib.Path(temp_dir) / "source"
        expected = export_commit(root, snapshot, commit)
        local_inputs = copy_local_build_inputs(root, snapshot)
        seal = seal_release_snapshot(snapshot, (*expected, *local_inputs))
        try:
            if after_snapshot is not None:
                after_snapshot(root, snapshot)
            command = [
                "./gradlew",
                *tasks,
                f"-PimmutableReleaseCommit={commit}",
                f"-PimmutableReleaseTree={tree}",
            ]
            run(command, snapshot)
            # Digest equality cannot detect A -> B -> A. The seal additionally proves the exact
            # input/ancestor identities and their unforgeable ctime transitions stayed unchanged.
            seal.verify()
            verify_export(snapshot, expected)

            frozen_sets: list[tuple[FrozenOutputSet, pathlib.PurePosixPath]] = []
            built_outputs = snapshot / "app/build/outputs"
            reports = snapshot / "app/build/reports"
            try:
                if os.path.lexists(built_outputs):
                    frozen_sets.append((
                        FrozenOutputSet.capture_tree(
                            built_outputs,
                            allow_file=lambda relative: (
                                relative.as_posix() in RELEASE_OUTPUT_FILES or
                                relative.as_posix().startswith(RELEASE_OUTPUT_PREFIXES)
                            ),
                            label="immutable release",
                        ),
                        pathlib.PurePosixPath(),
                    ))
                if os.path.lexists(reports):
                    frozen_sets.append((
                        FrozenOutputSet.capture_tree(
                            reports,
                            allow_file=lambda relative: (
                                len(relative.parts) == 1 and
                                relative.name.startswith("lint-results-release.")
                            ),
                            label="immutable release report",
                        ),
                        pathlib.PurePosixPath("logs"),
                    ))
                if not frozen_sets:
                    raise RuntimeError("release build produced no allowlisted outputs")
                if after_outputs_frozen is not None:
                    after_outputs_frozen(snapshot)
                output_root.parent.mkdir(parents=True, exist_ok=True)
                with tempfile.TemporaryDirectory(
                    prefix=f".{output_root.name}-staging-",
                    dir=output_root.parent,
                ) as staging_text:
                    staging = pathlib.Path(staging_text)
                    for frozen, prefix in frozen_sets:
                        frozen.export_into(staging.joinpath(*prefix.parts))
                    for frozen, _ in frozen_sets:
                        frozen.verify()
                    seal.verify()
                    if os.path.lexists(output_root):
                        raise RuntimeError(
                            f"refusing to overwrite immutable release output: {output_root}"
                        )
                    os.rename(staging, output_root)
            finally:
                for frozen, _ in frozen_sets:
                    frozen.close()
        finally:
            seal.release()
    return commit, tree


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parent.parent,
    )
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument(
        "tasks",
        nargs="*",
        default=[":app:lintRelease", ":app:assembleRelease", ":app:bundleRelease"],
    )
    args = parser.parse_args()
    if args.output is None:
        commit = git_value(args.root.resolve(), "rev-parse", "HEAD")
        output = args.root / "app/build/immutable-release" / (
            f"{commit[:12]}-{secrets.token_hex(4)}"
        )
    else:
        output = args.output
    try:
        commit, tree = build_immutable_release(args.root, args.tasks, output)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"immutable release build failed: {error}", file=sys.stderr)
        return 1
    print(f"immutable release build complete: commit={commit} tree={tree} outputs={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
