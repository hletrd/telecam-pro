#!/usr/bin/env python3
"""Build release tasks from an exact, private export of the current Git commit."""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
import pathlib
import re
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
RELEASE_REPORT_PREFIXES = ("resources_config_map_file/release/",)
STORE_FILE_ENVIRONMENT = "TELECAMPRO_STORE_FILE"
IMMUTABLE_STORE_FILE_PROPERTY = "immutableReleaseStoreFile"
IMMUTABLE_AUTHORITY_PATH_PROPERTY = "immutableReleaseAuthorityPath"
IMMUTABLE_AUTHORITY_NONCE_PROPERTY = "immutableReleaseAuthorityNonce"


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


class ReleaseLocalInputs(NamedTuple):
    sealed_paths: tuple[str, ...]
    store_file: str | None


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


def _authority_text(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii")


def create_release_authority(
    path: pathlib.Path,
    snapshot: pathlib.Path,
    commit: str,
    tree: str,
    store_file: str | None,
) -> str:
    """Create one private, single-invocation authorization record outside project inputs."""
    nonce = secrets.token_hex(32)
    store_digest = (
        sha256_regular_beneath(snapshot, store_file)
        if store_file is not None
        else ""
    )
    payload = (
        "schema=1\n"
        f"nonce={nonce}\n"
        f"root={_authority_text(str(snapshot.resolve()))}\n"
        f"commit={commit}\n"
        f"tree={tree}\n"
        f"storeFile={_authority_text(store_file or '')}\n"
        f"storeSha256={store_digest}\n"
    ).encode("ascii")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        view = memoryview(payload)
        written = 0
        while written < len(view):
            count = os.write(descriptor, view[written:])
            if count <= 0:
                raise RuntimeError("could not write immutable release authority")
            written += count
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    return nonce


def run_checked(command: Sequence[str], cwd: pathlib.Path) -> subprocess.CompletedProcess[str]:
    environment = dict(os.environ)
    # The immutable wrapper owns the only effective file path. Password/key-alias environment
    # values remain available, but an ambient store path can never point Gradle outside the seal.
    environment.pop(STORE_FILE_ENVIRONMENT, None)
    return subprocess.run(command, cwd=cwd, env=environment, text=True, check=True)


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


def _java_property_logical_lines(text: str) -> list[str]:
    """Join physical lines using the continuation rules of ``Properties.load(InputStream)``."""
    logical: list[str] = []
    current = ""
    continuing = False
    # Properties.LineReader recognizes CR, LF, and CRLF—not Python's wider Unicode splitlines set.
    for physical in re.split(r"\r\n|\n|\r", text):
        segment = physical.lstrip(" \t\f") if continuing else physical
        current += segment
        trailing_slashes = len(current) - len(current.rstrip("\\"))
        if trailing_slashes % 2 == 1:
            current = current[:-1]
            continuing = True
            continue
        logical.append(current)
        current = ""
        continuing = False
    if continuing or current:
        logical.append(current)
    return logical


def _decode_java_property_token(raw: str) -> str:
    decoded: list[str] = []
    index = 0
    escapes = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}
    while index < len(raw):
        char = raw[index]
        if char != "\\":
            decoded.append(char)
            index += 1
            continue
        index += 1
        if index >= len(raw):
            # An odd terminal slash is consumed as a continuation marker before this decoder.
            raise RuntimeError("release signing properties contain an incomplete escape")
        escaped = raw[index]
        index += 1
        if escaped == "u":
            digits = raw[index:index + 4]
            if len(digits) != 4 or re.fullmatch(r"[0-9A-Fa-f]{4}", digits) is None:
                raise RuntimeError("release signing properties contain an invalid Unicode escape")
            decoded.append(chr(int(digits, 16)))
            index += 4
        else:
            decoded.append(escapes.get(escaped, escaped))
    return "".join(decoded)


def parse_java_properties(payload: bytes) -> list[tuple[str, str]]:
    """Parse the Java-properties syntax needed to share one exact signing-path decision."""
    text = payload.decode("iso-8859-1")
    entries: list[tuple[str, str]] = []
    for logical in _java_property_logical_lines(text):
        stripped = logical.lstrip(" \t\f")
        if not stripped or stripped[0] in "#!":
            continue
        escaped = False
        separator = len(stripped)
        separator_kind: str | None = None
        for index, char in enumerate(stripped):
            if escaped:
                escaped = False
                continue
            if char == "\\":
                escaped = True
                continue
            if char in "=:" or char in " \t\f":
                separator = index
                separator_kind = char
                break
        raw_key = stripped[:separator]
        value_start = separator
        if separator_kind is not None:
            if separator_kind in " \t\f":
                while value_start < len(stripped) and stripped[value_start] in " \t\f":
                    value_start += 1
                if value_start < len(stripped) and stripped[value_start] in "=:":
                    value_start += 1
            else:
                value_start += 1
            while value_start < len(stripped) and stripped[value_start] in " \t\f":
                value_start += 1
        raw_value = stripped[value_start:]
        entries.append((
            _decode_java_property_token(raw_key),
            _decode_java_property_token(raw_value),
        ))
    return entries


def release_store_file(properties_payload: bytes) -> str:
    matches = [value for key, value in parse_java_properties(properties_payload) if key == "storeFile"]
    if len(matches) != 1:
        raise RuntimeError("release signing properties must contain exactly one storeFile")
    # Gradle applies String.trim() after Properties.load(); resolve the same effective value.
    value = matches[0].strip()
    if (
        not value or
        any(ord(char) < 0x20 or 0xD800 <= ord(char) <= 0xDFFF for char in value) or
        "\\" in value or
        value.startswith("/") or
        re.match(r"^[A-Za-z]:", value) is not None
    ):
        raise RuntimeError("release storeFile must be one repository-relative path")
    raw_parts = value.split("/")
    if any(part in {"", ".", ".."} for part in raw_parts):
        raise RuntimeError("release storeFile must be one normalized repository-relative path")
    relative = pathlib.PurePosixPath(*raw_parts)
    if relative.is_absolute():
        raise RuntimeError("release storeFile must be repository-relative")
    return relative.as_posix()


def copy_local_build_inputs(root: pathlib.Path, snapshot: pathlib.Path) -> ReleaseLocalInputs:
    """Descriptor-copy local inputs and resolve the sole effective release store-file path."""
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
    store_file: str | None = None
    if signing_payload is not None:
        store_file = release_store_file(signing_payload)
        relative_store = pathlib.Path(*pathlib.PurePosixPath(store_file).parts)
        payload, mode = read_regular_beneath(root, store_file)
        write_regular_exclusive(snapshot / relative_store, payload, mode)
        copied.append(store_file)
    return ReleaseLocalInputs(tuple(copied), store_file)


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
        seal = seal_release_snapshot(snapshot, (*expected, *local_inputs.sealed_paths))
        try:
            if after_snapshot is not None:
                after_snapshot(root, snapshot)
            authority_path = pathlib.Path(temp_dir) / "release-authority.properties"
            authority_nonce = create_release_authority(
                authority_path,
                snapshot,
                commit,
                tree,
                local_inputs.store_file,
            )
            command = [
                "./gradlew",
                *tasks,
                f"-PimmutableReleaseCommit={commit}",
                f"-PimmutableReleaseTree={tree}",
                f"-P{IMMUTABLE_AUTHORITY_PATH_PROPERTY}={authority_path}",
                f"-P{IMMUTABLE_AUTHORITY_NONCE_PROPERTY}={authority_nonce}",
            ]
            if local_inputs.store_file is not None:
                command.append(
                    f"-P{IMMUTABLE_STORE_FILE_PROPERTY}={local_inputs.store_file}"
                )
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
                                or relative.as_posix().startswith(RELEASE_REPORT_PREFIXES)
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
