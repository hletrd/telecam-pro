#!/usr/bin/env python3
"""Fail closed unless a release AAB matches an immutable upload attestation.

The ordinary Gradle output path is mutable and is never accepted as an upload artifact. A release
cut must first be copied to a digest-qualified path (normally under the gitignored ``releases/``
directory), then described by a JSON attestation and a SHA-256 sidecar. This checker joins that
attestation to the current clean HEAD, source version, AAB bytes, packaged manifest, and the one
recorded Play upload certificate.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from typing import Callable, Sequence


EXPECTED_UPLOAD_CERT_SHA256 = (
    "9dfdb903269238ef6de424052666b05814577b4b3bb43a5e3e3a05572660e584"
)
ATTESTATION_SCHEMA = 2
RELEASE_EVIDENCE_NAME = "release-evidence.json"
RELEASE_EVIDENCE_BOUNDARY = "sealed-export-frozen-outputs-v1"
RELEASE_EVIDENCE_SCHEMA = 2
RELEASE_SOURCE_AUTHORITY = "sealed-wrapper-export-v1"
PROVENANCE_NAMESPACE = "base/assets/telecam-release-provenance/"
PROVENANCE_MEMBER = PROVENANCE_NAMESPACE + "source.properties"
SOURCE_VERSION_PATH = pathlib.PurePath("app/build.gradle.kts")
PROTECTED_SOURCE_ROOTS = ("app/src/main", "app/src/release")


@dataclass(frozen=True)
class SourceVersion:
    version_code: int
    version_name: str
    application_id: str
    min_sdk: int
    target_sdk: int


@dataclass(frozen=True)
class RegularFileIdentity:
    device: int
    inode: int
    mode: int
    size: int
    modified_ns: int
    changed_ns: int
    sha256: str


@dataclass(frozen=True)
class PrivateArtifactSeal:
    path: pathlib.Path
    identity: RegularFileIdentity

    @classmethod
    def create(cls, path: pathlib.Path, expected_sha256: str) -> "PrivateArtifactSeal":
        os.chmod(path, 0o400, follow_symlinks=False)
        identity = regular_file_identity(path.parent, pathlib.PurePath(path.name))
        if identity.sha256 != expected_sha256:
            raise OSError("private AAB inspection copy changed while it was sealed")
        return cls(path, identity)

    def verify(self, phase: str) -> None:
        current = regular_file_identity(self.path.parent, pathlib.PurePath(self.path.name))
        if current != self.identity:
            raise OSError(f"private AAB inspection copy changed {phase}")


@dataclass(frozen=True)
class RepositorySnapshot:
    """One best-effort Git observation of live checkout drift, not source authority."""

    head: str
    dirty_records: tuple[str, ...]
    ignored_protected_sources: tuple[str, ...]


def _open_regular_beneath(root: pathlib.Path, relative: pathlib.PurePath) -> tuple[int, list[int]]:
    parts = relative.parts
    if not parts or any(part in {"", ".", ".."} for part in parts):
        raise OSError(f"unsafe artifact path: {relative}")
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
        if not stat.S_ISREG(os.fstat(file_fd).st_mode):
            raise OSError(f"artifact is not a regular file: {relative}")
        return file_fd, descriptors
    except BaseException:
        for descriptor in reversed(descriptors):
            try:
                os.close(descriptor)
            except OSError:
                pass
        raise


def _close_descriptors(descriptors: Sequence[int]) -> None:
    for descriptor in reversed(descriptors):
        try:
            os.close(descriptor)
        except OSError:
            pass


def snapshot_regular_file(
    root: pathlib.Path,
    relative: pathlib.PurePath,
    destination: pathlib.Path,
) -> RegularFileIdentity:
    """Copy one no-follow regular inode and hash the exact bytes copied."""
    file_fd, descriptors = _open_regular_beneath(root, relative)
    digest = hashlib.sha256()
    try:
        attributes = os.fstat(file_fd)
        output_fd = os.open(
            destination,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0),
            0o600,
        )
        try:
            with os.fdopen(os.dup(file_fd), "rb") as source, os.fdopen(output_fd, "wb") as output:
                output_fd = -1
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
                    output.write(chunk)
        finally:
            if output_fd >= 0:
                os.close(output_fd)
        final_attributes = os.fstat(file_fd)
        identity_fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")
        if any(
            getattr(attributes, field) != getattr(final_attributes, field)
            for field in identity_fields
        ):
            raise OSError(f"artifact changed while its private snapshot was copied: {relative}")
        return RegularFileIdentity(
            device=attributes.st_dev,
            inode=attributes.st_ino,
            mode=stat.S_IMODE(attributes.st_mode),
            size=attributes.st_size,
            modified_ns=attributes.st_mtime_ns,
            changed_ns=attributes.st_ctime_ns,
            sha256=digest.hexdigest(),
        )
    finally:
        _close_descriptors(descriptors)


def regular_file_identity(
    root: pathlib.Path,
    relative: pathlib.PurePath,
) -> RegularFileIdentity:
    file_fd, descriptors = _open_regular_beneath(root, relative)
    digest = hashlib.sha256()
    try:
        attributes = os.fstat(file_fd)
        with os.fdopen(os.dup(file_fd), "rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        final_attributes = os.fstat(file_fd)
        identity_fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")
        if any(
            getattr(attributes, field) != getattr(final_attributes, field)
            for field in identity_fields
        ):
            raise OSError(f"artifact changed while it was revalidated: {relative}")
        return RegularFileIdentity(
            device=attributes.st_dev,
            inode=attributes.st_ino,
            mode=stat.S_IMODE(attributes.st_mode),
            size=attributes.st_size,
            modified_ns=attributes.st_mtime_ns,
            changed_ns=attributes.st_ctime_ns,
            sha256=digest.hexdigest(),
        )
    finally:
        _close_descriptors(descriptors)


def snapshot_regular_bytes(
    root: pathlib.Path,
    relative: pathlib.PurePath,
) -> tuple[RegularFileIdentity, bytes]:
    """Capture and hash one no-follow regular inode through the same open descriptor."""
    file_fd, descriptors = _open_regular_beneath(root, relative)
    digest = hashlib.sha256()
    chunks: list[bytes] = []
    try:
        attributes = os.fstat(file_fd)
        with os.fdopen(os.dup(file_fd), "rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
                chunks.append(chunk)
        final_attributes = os.fstat(file_fd)
        identity_fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")
        if any(getattr(attributes, field) != getattr(final_attributes, field) for field in identity_fields):
            raise OSError(f"artifact changed while its bytes were captured: {relative}")
        return (
            RegularFileIdentity(
                device=attributes.st_dev,
                inode=attributes.st_ino,
                mode=stat.S_IMODE(attributes.st_mode),
                size=attributes.st_size,
                modified_ns=attributes.st_mtime_ns,
                changed_ns=attributes.st_ctime_ns,
                sha256=digest.hexdigest(),
            ),
            b"".join(chunks),
        )
    finally:
        _close_descriptors(descriptors)


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_sha256(value: str) -> str | None:
    normalized = value.replace(":", "").strip().casefold()
    return normalized if re.fullmatch(r"[0-9a-f]{64}", normalized) else None


def parse_source_version(build_script: str) -> SourceVersion:
    code = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", build_script, re.MULTILINE)
    name = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', build_script, re.MULTILINE)
    application_id = re.findall(
        r'^\s*applicationId\s*=\s*"([^"]+)"\s*$', build_script, re.MULTILINE
    )
    min_sdk = re.findall(r"^\s*minSdk\s*=\s*(\d+)\s*$", build_script, re.MULTILINE)
    target_sdk = re.findall(r"^\s*targetSdk\s*=\s*(\d+)\s*$", build_script, re.MULTILINE)
    if any(len(values) != 1 for values in (code, name, application_id, min_sdk, target_sdk)):
        raise ValueError(
            "expected one version/application/sdk identity, found "
            f"{code!r}/{name!r}/{application_id!r}/{min_sdk!r}/{target_sdk!r}"
        )
    return SourceVersion(
        int(code[0]), name[0], application_id[0], int(min_sdk[0]), int(target_sdk[0])
    )


def packaged_source_commits(aab_path: pathlib.Path) -> set[str]:
    """Read AGP's packaged VCS provenance without trusting a filename or operator sidecar."""
    member = "base/root/META-INF/version-control-info.textproto"
    try:
        with zipfile.ZipFile(aab_path) as bundle:
            if bundle.namelist().count(member) != 1:
                return set()
            info = bundle.getinfo(member)
            if info.file_size > 16_384:
                return set()
            text = bundle.read(info).decode("utf-8")
    except (KeyError, OSError, UnicodeDecodeError, zipfile.BadZipFile):
        return set()
    revisions = re.findall(r'^\s*revision:\s*"([0-9a-f]{40})"\s*$', text, re.MULTILINE)
    return {revisions[0]} if len(revisions) == 1 else set()


def packaged_source_provenance(aab_path: pathlib.Path) -> tuple[str, str] | None:
    """Read neutral commit/tree identity; this asset is not immutable-build evidence by itself."""
    try:
        with zipfile.ZipFile(aab_path) as bundle:
            namespace_members = [
                name
                for name in bundle.namelist()
                if name.startswith(PROVENANCE_NAMESPACE) and not name.endswith("/")
            ]
            if namespace_members != [PROVENANCE_MEMBER]:
                return None
            info = bundle.getinfo(PROVENANCE_MEMBER)
            if info.file_size > 1024:
                return None
            lines = bundle.read(info).decode("ascii").splitlines()
    except (KeyError, OSError, UnicodeDecodeError, zipfile.BadZipFile):
        return None
    if (
        len(lines) != 4
        or lines[0] != "schema=2"
        or lines[1] != "evidence=external-wrapper-required"
    ):
        return None
    commit_match = re.fullmatch(r"commit=([0-9a-f]{40})", lines[2])
    tree_match = re.fullmatch(r"tree=([0-9a-f]{40})", lines[3])
    if commit_match is None or tree_match is None:
        return None
    return commit_match.group(1), tree_match.group(1)


def strict_jar_verification_failure(
    root: pathlib.Path,
    aab_path: pathlib.Path,
    run: Callable[[Sequence[str], pathlib.Path], subprocess.CompletedProcess[str]],
    verify_artifact: Callable[[str], None] = lambda _phase: None,
) -> str | None:
    """Trust the pinned public cert, then require strict signature coverage for every JAR entry."""
    cert_result = run(
        ["keytool", "-printcert", "-rfc", "-jarfile", str(aab_path)], root
    )
    verify_artifact("after strict certificate inspection")
    pem_blocks = re.findall(
        r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
        cert_result.stdout + "\n" + cert_result.stderr,
        re.DOTALL,
    )
    if cert_result.returncode != 0 or len(pem_blocks) != 1:
        return "could not extract the unique AAB signer certificate"
    with tempfile.TemporaryDirectory(prefix="telecam-release-check-") as temp_dir:
        temp = pathlib.Path(temp_dir)
        cert_path = temp / "upload.pem"
        store_path = temp / "truststore.p12"
        cert_path.write_text(pem_blocks[0] + "\n", encoding="ascii")
        password = "public-cert-only"
        imported = run(
            [
                "keytool", "-importcert", "-noprompt", "-storetype", "PKCS12",
                "-alias", "upload", "-keystore", str(store_path), "-storepass", password,
                "-file", str(cert_path),
            ],
            root,
        )
        verify_artifact("after temporary trust-store creation")
        if imported.returncode != 0:
            return "could not create the temporary upload-certificate truststore"
        verified = run(
            [
                "jarsigner", "-verify", "-strict", "-keystore", str(store_path),
                "-storepass", password, str(aab_path), "upload",
            ],
            root,
        )
        verify_artifact("after strict JAR verification")
        if verified.returncode != 0:
            return "strict jarsigner verification failed (unsigned or invalid entry/certificate)"
    return None


def parse_sidecar(text: str, expected_name: str) -> str | None:
    match = re.fullmatch(r"([0-9A-Fa-f]{64})\s+\*?(.+)\n?", text)
    if match is None or pathlib.Path(match.group(2)).name != expected_name:
        return None
    return match.group(1).casefold()


def default_run(command: Sequence[str], cwd: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=120)


def _is_protected_source(path: str) -> bool:
    normalized = path.removesuffix("/")
    return any(
        normalized == root or normalized.startswith(root + "/")
        for root in PROTECTED_SOURCE_ROOTS
    )


def parse_repository_snapshot(output: str) -> RepositorySnapshot:
    """Parse one NUL-delimited porcelain-v2 status stream without path ambiguity."""
    if not output.endswith("\0"):
        raise ValueError("Git status output was not NUL-terminated")
    records = output.split("\0")[:-1]
    head_values: list[str] = []
    dirty_records: list[str] = []
    ignored_protected_sources: list[str] = []
    for record in records:
        if record.startswith("# branch.oid "):
            head_values.append(record.removeprefix("# branch.oid "))
        elif record.startswith(("# branch.head ", "# branch.upstream ", "# branch.ab ")):
            # branch.head/upstream/ab are Git-owned metadata but do not affect release identity.
            continue
        elif record.startswith(("1 ", "u ", "? ")):
            if len(record) < 3:
                raise ValueError("Git status emitted a malformed worktree record")
            dirty_records.append(record)
        elif record.startswith("! "):
            path = record[2:]
            if not path or path.startswith("/") or path == ".." or path.startswith("../"):
                raise ValueError("Git status emitted an unsafe ignored path")
            if _is_protected_source(path):
                ignored_protected_sources.append(path)
        else:
            raise ValueError("Git status emitted an unknown porcelain-v2 record")
    if len(head_values) != 1 or not re.fullmatch(r"[0-9a-f]{40}", head_values[0]):
        raise ValueError("Git status did not emit one canonical branch.oid")
    return RepositorySnapshot(
        head=head_values[0],
        dirty_records=tuple(dirty_records),
        ignored_protected_sources=tuple(ignored_protected_sources),
    )


def repository_snapshot(
    run: Callable[[Sequence[str], pathlib.Path], subprocess.CompletedProcess[str]],
    root: pathlib.Path,
) -> RepositorySnapshot:
    """Observe live checkout drift in one Git process without claiming filesystem atomicity."""
    try:
        result = run(
            [
                "git",
                "status",
                "--porcelain=v2",
                "--branch",
                "-z",
                "--untracked-files=all",
                "--ignored=matching",
                "--no-renames",
            ],
            root,
        )
    except (OSError, subprocess.SubprocessError, UnicodeError) as error:
        raise ValueError(f"Git status failed: {error}") from error
    if result.returncode != 0:
        raise ValueError("Git status returned a non-zero exit status")
    if not isinstance(result.stdout, str):
        raise ValueError("Git status did not return text output")
    return parse_repository_snapshot(result.stdout)


def cleanup_private_artifact(artifact_temp: tempfile.TemporaryDirectory[str]) -> None:
    """Remove private inspection files before the terminal repository snapshot begins."""
    artifact_temp.cleanup()


def check_release_identity(
    root: pathlib.Path,
    attestation_path: pathlib.Path,
    *,
    run: Callable[[Sequence[str], pathlib.Path], subprocess.CompletedProcess[str]] = default_run,
) -> list[str]:
    failures: list[str] = []
    requested_root = pathlib.Path(os.path.abspath(root))
    root = requested_root.resolve()
    requested_attestation = pathlib.Path(os.path.abspath(attestation_path))
    try:
        relative_attestation = requested_attestation.relative_to(requested_root)
    except ValueError:
        try:
            relative_attestation = requested_attestation.relative_to(root)
        except ValueError:
            return ["attestation and its sidecar must live under the repository root"]
    relative_sidecar = relative_attestation.with_name(relative_attestation.name + ".sha256")
    attestation_path = root / relative_attestation

    try:
        attestation_identity, attestation_bytes = snapshot_regular_bytes(
            root, relative_attestation
        )
    except OSError as error:
        return [f"attestation is not a readable no-follow regular file: {error}"]
    try:
        sidecar_identity, sidecar_bytes = snapshot_regular_bytes(root, relative_sidecar)
    except OSError as error:
        return [f"attestation sidecar is not a readable no-follow regular file: {error}"]

    try:
        sidecar_text = sidecar_bytes.decode("utf-8")
    except UnicodeDecodeError:
        sidecar_text = ""
    expected_attestation_sha = parse_sidecar(sidecar_text, attestation_path.name)
    actual_attestation_sha = attestation_identity.sha256
    if expected_attestation_sha != actual_attestation_sha:
        failures.append("attestation SHA-256 sidecar does not match the JSON bytes")

    try:
        document = json.loads(attestation_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        return failures + [f"attestation JSON is unreadable: {error}"]
    if not isinstance(document, dict):
        return failures + ["attestation root must be an object"]

    required = {
        "schema_version",
        "status",
        "git_commit",
        "version_code",
        "version_name",
        "aab_path",
        "aab_sha256",
        "signer_sha256",
        "release_evidence_path",
    }
    missing = sorted(required - document.keys())
    if missing:
        return failures + [f"attestation fields missing: {missing}"]
    if document["schema_version"] != ATTESTATION_SCHEMA:
        failures.append(f"unsupported attestation schema: {document['schema_version']!r}")
    if document["status"] != "upload-ready":
        failures.append("attestation status is not upload-ready")

    try:
        source_version_identity, source_version_bytes = snapshot_regular_bytes(
            root, SOURCE_VERSION_PATH
        )
        source_version = parse_source_version(source_version_bytes.decode("utf-8"))
    except (OSError, UnicodeDecodeError, ValueError) as error:
        failures.append(f"could not safely read source version: {error}")
        source_version_identity = None
        source_version = None

    try:
        initial_repository = repository_snapshot(run, root)
    except ValueError as error:
        failures.append(f"could not observe initial repository drift state: {error}")
        initial_repository = None
    head = initial_repository.head if initial_repository is not None else ""
    attested_commit = str(document["git_commit"]).casefold()
    if not re.fullmatch(r"[0-9a-f]{40}", attested_commit):
        failures.append("attested git_commit is not a full 40-character commit")
    elif head != attested_commit:
        failures.append(f"HEAD {head or '?'} does not match attested commit {attested_commit}")
    if initial_repository is not None:
        if initial_repository.dirty_records:
            failures.append("working tree is not clean")
        if initial_repository.ignored_protected_sources:
            failures.append("release source roots contain ignored packageable inputs")
    if source_version is not None:
        if document["version_code"] != source_version.version_code:
            failures.append("attested version_code does not match app/build.gradle.kts")
        if document["version_name"] != source_version.version_name:
            failures.append("attested version_name does not match app/build.gradle.kts")

    raw_aab_path = pathlib.Path(str(document["aab_path"]))
    candidate_aab = root / raw_aab_path if not raw_aab_path.is_absolute() else raw_aab_path
    aab_path = pathlib.Path(os.path.abspath(candidate_aab))
    try:
        relative_aab = aab_path.relative_to(root)
    except ValueError:
        failures.append("AAB must live under the repository root")
        relative_aab = None
    if relative_aab is not None and relative_aab.parts[:3] == ("app", "build", "outputs"):
        failures.append("mutable app/build/outputs is not an immutable upload artifact location")
    if relative_aab is None:
        return failures

    raw_evidence_path = pathlib.Path(str(document["release_evidence_path"]))
    candidate_evidence = (
        root / raw_evidence_path if not raw_evidence_path.is_absolute() else raw_evidence_path
    )
    evidence_path = pathlib.Path(os.path.abspath(candidate_evidence))
    try:
        relative_evidence = evidence_path.relative_to(root)
    except ValueError:
        failures.append("release evidence must live under the repository root")
        relative_evidence = None
    if relative_evidence is not None and (
        len(relative_evidence.parts) != 5
        or relative_evidence.parts[:3] != ("app", "build", "immutable-release")
        or relative_evidence.name != RELEASE_EVIDENCE_NAME
    ):
        failures.append(
            "release evidence must be the receipt in one immutable-release child namespace"
        )

    artifact_temp = tempfile.TemporaryDirectory(prefix="telecam-release-artifact-")
    verified_aab_path = pathlib.Path(artifact_temp.name) / "verified.aab"
    try:
        source_identity = snapshot_regular_file(root, relative_aab, verified_aab_path)
        private_seal = PrivateArtifactSeal.create(
            verified_aab_path,
            source_identity.sha256,
        )
    except OSError as error:
        artifact_temp.cleanup()
        return failures + [f"AAB is not a readable no-follow regular file: {error}"]

    def private_artifact_current(phase: str) -> bool:
        try:
            private_seal.verify(phase)
            return True
        except OSError as error:
            failures.append(str(error))
            return False

    attested_aab_sha = normalize_sha256(str(document["aab_sha256"]))
    actual_aab_sha = source_identity.sha256
    if attested_aab_sha != actual_aab_sha:
        failures.append("AAB SHA-256 does not match attestation")

    evidence: object | None = None
    evidence_identity: RegularFileIdentity | None = None
    if relative_evidence is not None:
        verified_evidence_path = pathlib.Path(artifact_temp.name) / RELEASE_EVIDENCE_NAME
        try:
            evidence_identity = snapshot_regular_file(
                root,
                relative_evidence,
                verified_evidence_path,
            )
            evidence = json.loads(verified_evidence_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            failures.append(f"release evidence is unreadable: {error}")
            evidence = None
        if not isinstance(evidence, dict):
            failures.append("release evidence root must be an object")
        else:
            if (
                evidence.get("schema") != RELEASE_EVIDENCE_SCHEMA
                or evidence.get("boundary") != RELEASE_EVIDENCE_BOUNDARY
                or evidence.get("source_authority") != RELEASE_SOURCE_AUTHORITY
                or evidence.get("commit") != attested_commit
            ):
                failures.append("release evidence identity does not match the attestation")
            evidence_tree = evidence.get("tree")
            if not isinstance(evidence_tree, str) or not re.fullmatch(
                r"[0-9a-f]{40}", evidence_tree
            ):
                failures.append("release evidence tree is not canonical")
            evidence_outputs = evidence.get("outputs")
            matching_outputs = []
            if isinstance(evidence_outputs, list):
                matching_outputs = [
                    entry
                    for entry in evidence_outputs
                    if isinstance(entry, dict)
                    and isinstance(entry.get("path"), str)
                    and re.fullmatch(r"bundle/release/[^/]+\.aab", entry["path"])
                    and entry.get("sha256") == actual_aab_sha
                    and entry.get("size") == source_identity.size
                ]
            if len(matching_outputs) != 1:
                failures.append(
                    "AAB is not the unique bundle output recorded by release evidence"
                )
    if re.fullmatch(r"[0-9a-f]{40}", attested_commit):
        immutable_tokens = (attested_commit[:7], actual_aab_sha[:12])
        if any(token not in aab_path.name.casefold() for token in immutable_tokens):
            failures.append("AAB filename must contain the short commit and SHA-256 prefix")
        packaged_commits = packaged_source_commits(verified_aab_path)
        if not private_artifact_current("after AGP provenance inspection"):
            artifact_temp.cleanup()
            return failures
        if packaged_commits != {attested_commit}:
            failures.append(
                "packaged AGP source revision does not uniquely match the attested commit"
            )
        tree_result = run(["git", "rev-parse", f"{attested_commit}^{{tree}}"], root)
        expected_tree = tree_result.stdout.strip() if tree_result.returncode == 0 else ""
        source_provenance = packaged_source_provenance(verified_aab_path)
        if not private_artifact_current("after release provenance inspection"):
            artifact_temp.cleanup()
            return failures
        if (
            not re.fullmatch(r"[0-9a-f]{40}", expected_tree)
            or source_provenance != (attested_commit, expected_tree)
        ):
            failures.append(
                "packaged clean-source commit/tree provenance does not match the attested commit"
            )
        if isinstance(evidence, dict) and evidence.get("tree") != expected_tree:
            failures.append("release evidence tree does not match the attested commit")

    signer = normalize_sha256(str(document["signer_sha256"]))
    if signer != EXPECTED_UPLOAD_CERT_SHA256:
        failures.append("attested signer is not the recorded Play upload certificate")

    cert_result = run(["keytool", "-printcert", "-jarfile", str(verified_aab_path)], root)
    if not private_artifact_current("after signer inspection"):
        artifact_temp.cleanup()
        return failures
    cert_output = cert_result.stdout + "\n" + cert_result.stderr
    actual_signers = {
        normalized
        for value in re.findall(r"SHA256:\s*([0-9A-Fa-f:]{64,95})", cert_output)
        if (normalized := normalize_sha256(value)) is not None
    }
    if cert_result.returncode != 0 or actual_signers != {signer}:
        failures.append("AAB signer certificate does not match attestation")
    else:
        try:
            strict_failure = strict_jar_verification_failure(
                root,
                verified_aab_path,
                run,
                verify_artifact=private_seal.verify,
            )
        except OSError as error:
            artifact_temp.cleanup()
            return failures + [str(error)]
        if strict_failure is not None:
            failures.append(strict_failure)

    validate_result = run(["bundletool", "validate", f"--bundle={verified_aab_path}"], root)
    if not private_artifact_current("after bundle validation"):
        artifact_temp.cleanup()
        return failures
    if validate_result.returncode != 0:
        failures.append("bundletool validation failed")

    manifest_result = run(["bundletool", "dump", "manifest", f"--bundle={verified_aab_path}"], root)
    if not private_artifact_current("after manifest inspection"):
        artifact_temp.cleanup()
        return failures
    manifest = manifest_result.stdout
    packaged_application = re.search(r'<manifest[^>]+\bpackage="([^"]+)"', manifest)
    packaged_code = re.search(r'android:versionCode="(\d+)"', manifest)
    packaged_name = re.search(r'android:versionName="([^"]+)"', manifest)
    packaged_min_sdk = re.search(r'android:minSdkVersion="(\d+)"', manifest)
    packaged_target_sdk = re.search(r'android:targetSdkVersion="(\d+)"', manifest)
    if manifest_result.returncode != 0 or any(
        value is None for value in (
            packaged_application, packaged_code, packaged_name,
            packaged_min_sdk, packaged_target_sdk,
        )
    ):
        failures.append("bundletool could not prove packaged application/version/SDK identity")
    else:
        assert packaged_application is not None
        assert packaged_code is not None
        assert packaged_name is not None
        assert packaged_min_sdk is not None
        assert packaged_target_sdk is not None
        if int(packaged_code.group(1)) != document["version_code"]:
            failures.append("packaged versionCode does not match attestation")
        if packaged_name.group(1) != document["version_name"]:
            failures.append("packaged versionName does not match attestation")
        if source_version is not None:
            if packaged_application.group(1) != source_version.application_id:
                failures.append("packaged applicationId does not match source")
            if int(packaged_min_sdk.group(1)) != source_version.min_sdk:
                failures.append("packaged minSdk does not match source")
            if int(packaged_target_sdk.group(1)) != source_version.target_sdk:
                failures.append("packaged targetSdk does not match source")
    if source_version_identity is not None:
        try:
            final_source_version_identity = regular_file_identity(root, SOURCE_VERSION_PATH)
        except OSError as error:
            failures.append(
                "source-version input changed or became unsafe during verification: "
                f"{error}"
            )
        else:
            if final_source_version_identity != source_version_identity:
                failures.append(
                    "source-version input identity or digest changed during verification"
                )
    if not private_artifact_current("before verification completion"):
        artifact_temp.cleanup()
        return failures
    try:
        final_source_identity = regular_file_identity(root, relative_aab)
    except OSError as error:
        failures.append(f"AAB source path changed or became unsafe during verification: {error}")
    else:
        if final_source_identity != source_identity:
            failures.append("AAB source identity or digest changed during verification")
    if relative_evidence is not None and evidence_identity is not None:
        try:
            final_evidence_identity = regular_file_identity(root, relative_evidence)
        except OSError as error:
            failures.append(f"release evidence changed or became unsafe during verification: {error}")
        else:
            if final_evidence_identity != evidence_identity:
                failures.append("release evidence identity or digest changed during verification")
    for label, relative, identity in (
        ("attestation", relative_attestation, attestation_identity),
        ("attestation sidecar", relative_sidecar, sidecar_identity),
    ):
        try:
            final_identity = regular_file_identity(root, relative)
        except OSError as error:
            failures.append(f"{label} changed or became unsafe during verification: {error}")
        else:
            if final_identity != identity:
                failures.append(f"{label} source identity or digest changed during verification")
    # The signed AAB plus matching sealed-wrapper receipt above is the source authority. Finish file
    # revalidation and private cleanup before this last best-effort live-checkout drift observation
    # only to minimize its inevitable scan interval; Git status does not freeze the worktree.
    try:
        cleanup_private_artifact(artifact_temp)
    except OSError as error:
        return failures + [f"could not remove private AAB inspection files: {error}"]
    try:
        final_repository = repository_snapshot(run, root)
    except ValueError as error:
        return failures + [f"could not observe terminal repository drift state: {error}"]
    if initial_repository is not None:
        if final_repository.dirty_records != initial_repository.dirty_records:
            failures.append("working-tree status changed during verification")
        if (
            final_repository.ignored_protected_sources
            != initial_repository.ignored_protected_sources
        ):
            failures.append("ignored packageable source inputs changed during verification")
        if final_repository.head != initial_repository.head:
            failures.append("HEAD changed during verification")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("attestation", type=pathlib.Path)
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    failures = check_release_identity(args.root, args.attestation)
    if failures:
        print("release artifact is NOT upload-ready:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("release artifact identity verified: upload-ready")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
