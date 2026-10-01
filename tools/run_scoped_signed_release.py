#!/usr/bin/env python3
"""Verify an owner-approved upload key and run one release with scoped secrets."""

from __future__ import annotations

import argparse
import os
import pathlib
import subprocess
import sys
from collections.abc import Callable, Mapping, Sequence

_TOOLS_DIR = pathlib.Path(__file__).resolve().parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

# The approval + certificate gate is SHARED with build_immutable_release.py, so the documented
# release wrapper cannot sign with a key this helper refuses.
from build_immutable_release import (
    APPROVAL_PROPERTY,
    FINGERPRINT_PROPERTY,
    KEY_ALIAS_ENV,
    KEY_PASSWORD_ENV,
    STORE_PASSWORD_ENV,
    UploadKeyGateError,
    UploadKeyPrerequisite,
    load_upload_key_prerequisite,
    verify_upload_key_certificate,
)
# The generated-secret floor is the SAME rule the immutable wrapper applies (SEC3-2).
from upload_key_policy import meets_generated_secret_floor


STORE_FILE_ENV = "TELECAMPRO_STORE_FILE"
SECRET_FIELDS = {"storePassword": STORE_PASSWORD_ENV, "keyPassword": KEY_PASSWORD_ENV}
MAX_CREDENTIAL_BYTES = 64 * 1024

Run = Callable[..., subprocess.CompletedProcess[bytes]]


# One refusal type for both wrappers; its message never includes a credential value.
ScopedReleaseError = UploadKeyGateError


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
        key, separator, value = raw_line.partition("=")
        if not separator or key not in SECRET_FIELDS or key in values:
            raise ScopedReleaseError("scoped signing credentials have an invalid field set")
        if not value or any(ord(character) < 0x20 for character in value):
            raise ScopedReleaseError("scoped signing credentials contain an invalid value")
        if not meets_generated_secret_floor(value):
            raise ScopedReleaseError("scoped signing credentials do not meet the strong-key policy")
        values[key] = value
    if values.keys() != SECRET_FIELDS.keys():
        values.clear()
        raise ScopedReleaseError("scoped signing credentials are incomplete")
    return values


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
        verify_upload_key_certificate(root, prerequisite, child_environment, run)

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
