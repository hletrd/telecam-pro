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
import pathlib
import re
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from typing import Callable, Sequence


EXPECTED_UPLOAD_CERT_SHA256 = (
    "9dfdb903269238ef6de424052666b05814577b4b3bb43a5e3e3a05572660e584"
)
ATTESTATION_SCHEMA = 1


@dataclass(frozen=True)
class SourceVersion:
    version_code: int
    version_name: str
    application_id: str
    min_sdk: int
    target_sdk: int


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


def strict_jar_verification_failure(
    root: pathlib.Path,
    aab_path: pathlib.Path,
    run: Callable[[Sequence[str], pathlib.Path], subprocess.CompletedProcess[str]],
) -> str | None:
    """Trust the pinned public cert, then require strict signature coverage for every JAR entry."""
    cert_result = run(
        ["keytool", "-printcert", "-rfc", "-jarfile", str(aab_path)], root
    )
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
        if imported.returncode != 0:
            return "could not create the temporary upload-certificate truststore"
        verified = run(
            [
                "jarsigner", "-verify", "-strict", "-keystore", str(store_path),
                "-storepass", password, str(aab_path), "upload",
            ],
            root,
        )
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


def check_release_identity(
    root: pathlib.Path,
    attestation_path: pathlib.Path,
    *,
    run: Callable[[Sequence[str], pathlib.Path], subprocess.CompletedProcess[str]] = default_run,
) -> list[str]:
    failures: list[str] = []
    root = root.resolve()
    attestation_path = attestation_path.resolve()
    sidecar = attestation_path.with_name(attestation_path.name + ".sha256")
    if not attestation_path.is_file():
        return [f"attestation missing: {attestation_path}"]
    if not sidecar.is_file():
        return [f"attestation sidecar missing: {sidecar}"]

    expected_attestation_sha = parse_sidecar(
        sidecar.read_text(encoding="utf-8"), attestation_path.name
    )
    actual_attestation_sha = sha256_file(attestation_path)
    if expected_attestation_sha != actual_attestation_sha:
        failures.append("attestation SHA-256 sidecar does not match the JSON bytes")

    try:
        document = json.loads(attestation_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
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
    }
    missing = sorted(required - document.keys())
    if missing:
        return failures + [f"attestation fields missing: {missing}"]
    if document["schema_version"] != ATTESTATION_SCHEMA:
        failures.append(f"unsupported attestation schema: {document['schema_version']!r}")
    if document["status"] != "upload-ready":
        failures.append("attestation status is not upload-ready")

    head_result = run(["git", "rev-parse", "HEAD"], root)
    head = head_result.stdout.strip().casefold() if head_result.returncode == 0 else ""
    attested_commit = str(document["git_commit"]).casefold()
    if not re.fullmatch(r"[0-9a-f]{40}", attested_commit):
        failures.append("attested git_commit is not a full 40-character commit")
    elif head != attested_commit:
        failures.append(f"HEAD {head or '?'} does not match attested commit {attested_commit}")

    status_result = run(
        ["git", "status", "--porcelain", "--untracked-files=all"], root
    )
    if status_result.returncode != 0 or status_result.stdout.strip():
        failures.append("working tree is not clean")

    try:
        source_version = parse_source_version((root / "app/build.gradle.kts").read_text())
    except (OSError, ValueError) as error:
        failures.append(f"could not read source version: {error}")
        source_version = None
    if source_version is not None:
        if document["version_code"] != source_version.version_code:
            failures.append("attested version_code does not match app/build.gradle.kts")
        if document["version_name"] != source_version.version_name:
            failures.append("attested version_name does not match app/build.gradle.kts")

    raw_aab_path = pathlib.Path(str(document["aab_path"]))
    aab_path = (root / raw_aab_path).resolve() if not raw_aab_path.is_absolute() else raw_aab_path.resolve()
    try:
        relative_aab = aab_path.relative_to(root)
    except ValueError:
        failures.append("AAB must live under the repository root")
        relative_aab = None
    if relative_aab is not None and relative_aab.parts[:3] == ("app", "build", "outputs"):
        failures.append("mutable app/build/outputs is not an immutable upload artifact location")
    if not aab_path.is_file():
        return failures + [f"AAB missing: {aab_path}"]

    attested_aab_sha = normalize_sha256(str(document["aab_sha256"]))
    actual_aab_sha = sha256_file(aab_path)
    if attested_aab_sha != actual_aab_sha:
        failures.append("AAB SHA-256 does not match attestation")
    if re.fullmatch(r"[0-9a-f]{40}", attested_commit):
        immutable_tokens = (attested_commit[:7], actual_aab_sha[:12])
        if any(token not in aab_path.name.casefold() for token in immutable_tokens):
            failures.append("AAB filename must contain the short commit and SHA-256 prefix")
        packaged_commits = packaged_source_commits(aab_path)
        if packaged_commits != {attested_commit}:
            failures.append(
                "packaged AGP source revision does not uniquely match the attested commit"
            )

    signer = normalize_sha256(str(document["signer_sha256"]))
    if signer != EXPECTED_UPLOAD_CERT_SHA256:
        failures.append("attested signer is not the recorded Play upload certificate")

    cert_result = run(["keytool", "-printcert", "-jarfile", str(aab_path)], root)
    cert_output = cert_result.stdout + "\n" + cert_result.stderr
    actual_signers = {
        normalized
        for value in re.findall(r"SHA256:\s*([0-9A-Fa-f:]{64,95})", cert_output)
        if (normalized := normalize_sha256(value)) is not None
    }
    if cert_result.returncode != 0 or actual_signers != {signer}:
        failures.append("AAB signer certificate does not match attestation")
    else:
        strict_failure = strict_jar_verification_failure(root, aab_path, run)
        if strict_failure is not None:
            failures.append(strict_failure)

    validate_result = run(["bundletool", "validate", f"--bundle={aab_path}"], root)
    if validate_result.returncode != 0:
        failures.append("bundletool validation failed")

    manifest_result = run(["bundletool", "dump", "manifest", f"--bundle={aab_path}"], root)
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
