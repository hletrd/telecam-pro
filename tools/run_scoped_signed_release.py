#!/usr/bin/env python3
"""Verify an owner-approved upload key and run one release with scoped secrets."""

from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import re
import shutil
import stat
import subprocess
import sys
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass

_TOOLS_DIR = pathlib.Path(__file__).resolve().parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

from build_immutable_release import parse_java_properties, read_regular_beneath, release_store_file


STORE_PASSWORD_ENV = "TELECAMPRO_STORE_PASSWORD"
KEY_PASSWORD_ENV = "TELECAMPRO_KEY_PASSWORD"
KEY_ALIAS_ENV = "TELECAMPRO_KEY_ALIAS"
STORE_FILE_ENV = "TELECAMPRO_STORE_FILE"
SECRET_FIELDS = {"storePassword": STORE_PASSWORD_ENV, "keyPassword": KEY_PASSWORD_ENV}
MAX_CREDENTIAL_BYTES = 64 * 1024
MIN_STRONG_PASSWORD_LENGTH = 16
APPROVAL_PROPERTY = "uploadKeyRotationApproved"
FINGERPRINT_PROPERTY = "uploadKeyCertificateSha256"

Run = Callable[..., subprocess.CompletedProcess[bytes]]


class ScopedReleaseError(RuntimeError):
    """Expected fail-closed refusal whose message never includes a credential value."""


@dataclass(frozen=True)
class UploadKeyPrerequisite:
    store_path: pathlib.Path
    alias: str
    certificate_sha256: str


def parse_scoped_credentials(payload: bytes) -> dict[str, str]:
    if not payload or len(payload) > MAX_CREDENTIAL_BYTES:
        raise ScopedReleaseError("scoped signing credentials are missing or oversized")
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ScopedReleaseError("scoped signing credentials are not valid UTF-8") from error
    values: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key not in SECRET_FIELDS or key in values:
            raise ScopedReleaseError("scoped signing credentials have an invalid field set")
        if not value or any(ord(character) < 0x20 for character in value):
            raise ScopedReleaseError("scoped signing credentials contain an invalid value")
        if len(value) < MIN_STRONG_PASSWORD_LENGTH or re.fullmatch(r"\d{6}", value):
            raise ScopedReleaseError("scoped signing credentials do not meet the strong-key policy")
        values[key] = value
    if values.keys() != SECRET_FIELDS.keys():
        values.clear()
        raise ScopedReleaseError("scoped signing credentials are incomplete")
    return values


def _single_property(entries: Sequence[tuple[str, str]], name: str) -> str:
    matches = [value.strip() for key, value in entries if key == name]
    if len(matches) != 1 or not matches[0]:
        raise ScopedReleaseError(f"release signing prerequisite {name} is missing or ambiguous")
    return matches[0]


def _no_follow_regular(root: pathlib.Path, relative: str) -> pathlib.Path:
    current = root
    parts = pathlib.PurePosixPath(relative).parts
    for index, component in enumerate(parts):
        current = current / component
        try:
            attributes = current.lstat()
        except OSError as error:
            raise ScopedReleaseError("release upload keystore is unavailable") from error
        if stat.S_ISLNK(attributes.st_mode):
            raise ScopedReleaseError("release upload keystore path must not contain symlinks")
        if index < len(parts) - 1 and not stat.S_ISDIR(attributes.st_mode):
            raise ScopedReleaseError("release upload keystore parent is not a directory")
    if not stat.S_ISREG(current.lstat().st_mode):
        raise ScopedReleaseError("release upload keystore is not a regular file")
    return current


def load_upload_key_prerequisite(
    root: pathlib.Path,
    environment: Mapping[str, str],
) -> UploadKeyPrerequisite:
    root = root.resolve()
    try:
        properties_payload, _ = read_regular_beneath(root, "keystore.properties")
        entries = parse_java_properties(properties_payload)
        store_relative = release_store_file(properties_payload)
    except (OSError, RuntimeError, UnicodeError) as error:
        raise ScopedReleaseError("release signing properties are unavailable or unsafe") from error
    approved = _single_property(entries, APPROVAL_PROPERTY).casefold()
    if approved != "true":
        raise ScopedReleaseError(
            "upload key is blocked until owner-confirmed strong-key rotation or Play reset",
        )
    fingerprint = _single_property(entries, FINGERPRINT_PROPERTY).replace(":", "").casefold()
    if re.fullmatch(r"[0-9a-f]{64}", fingerprint) is None:
        raise ScopedReleaseError("approved upload certificate fingerprint is invalid")
    alias = environment.get(KEY_ALIAS_ENV, "").strip()
    if not alias:
        alias = _single_property(entries, "keyAlias")
    if any(ord(character) < 0x20 for character in alias):
        raise ScopedReleaseError("release upload alias is invalid")
    return UploadKeyPrerequisite(
        store_path=_no_follow_regular(root, store_relative),
        alias=alias,
        certificate_sha256=fingerprint,
    )


def _keytool_path(environment: Mapping[str, str]) -> str:
    java_home = environment.get("JAVA_HOME", "").strip()
    if java_home:
        candidate = pathlib.Path(java_home) / "bin" / "keytool"
        if candidate.is_file():
            return str(candidate)
    resolved = shutil.which("keytool", path=environment.get("PATH"))
    if resolved is None:
        raise ScopedReleaseError("keytool is unavailable")
    return resolved


def run_scoped_signed_release(
    root: pathlib.Path,
    tasks: Sequence[str],
    output: pathlib.Path | None,
    credentials: dict[str, str],
    base_environment: Mapping[str, str] = os.environ,
    run: Run = subprocess.run,
) -> None:
    child_environment: dict[str, str] | None = None
    try:
        prerequisite = load_upload_key_prerequisite(root, base_environment)
        child_environment = dict(base_environment)
        child_environment.pop(STORE_FILE_ENV, None)
        child_environment[STORE_PASSWORD_ENV] = credentials["storePassword"]
        child_environment[KEY_PASSWORD_ENV] = credentials["keyPassword"]
        child_environment[KEY_ALIAS_ENV] = prerequisite.alias
        keytool_command = [
            _keytool_path(child_environment),
            "-exportcert",
            "-keystore",
            str(prerequisite.store_path),
            "-alias",
            prerequisite.alias,
            "-storepass:env",
            STORE_PASSWORD_ENV,
        ]
        verified = run(
            keytool_command,
            cwd=root,
            env=child_environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if verified.returncode != 0:
            raise ScopedReleaseError("approved upload keystore verification failed")
        certificate = verified.stdout if isinstance(verified.stdout, bytes) else b""
        if hashlib.sha256(certificate).hexdigest() != prerequisite.certificate_sha256:
            raise ScopedReleaseError("approved upload certificate fingerprint does not match")

        command = [
            sys.executable,
            str(root.resolve() / "tools" / "build_immutable_release.py"),
            "--root",
            str(root.resolve()),
        ]
        if output is not None:
            command.extend(("--output", str(output)))
        command.extend(tasks)
        built = run(command, cwd=root, env=child_environment, check=False)
        if built.returncode != 0:
            raise ScopedReleaseError("immutable signed release build failed")
    finally:
        # The helper is deliberately short-lived, but erase its mutable copies too. No secret is
        # written to a file, placed in argv, returned, or exported into the caller's shell.
        credentials.clear()
        if child_environment is not None:
            for name in (STORE_PASSWORD_ENV, KEY_PASSWORD_ENV):
                if name in child_environment:
                    child_environment[name] = ""
                    child_environment.pop(name, None)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parent.parent,
    )
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--check-prerequisites", action="store_true")
    parser.add_argument(
        "tasks",
        nargs="*",
        default=[":app:lintRelease", ":app:assembleRelease", ":app:bundleRelease"],
    )
    args = parser.parse_args()
    try:
        load_upload_key_prerequisite(args.root, os.environ)
        if args.check_prerequisites:
            print("owner-approved upload-key prerequisite satisfied")
            return 0
        credentials = parse_scoped_credentials(sys.stdin.buffer.read(MAX_CREDENTIAL_BYTES + 1))
        run_scoped_signed_release(
            root=args.root,
            tasks=args.tasks,
            output=args.output,
            credentials=credentials,
        )
        return 0
    except ScopedReleaseError as error:
        print(f"scoped signed release refused: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
