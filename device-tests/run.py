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
_HARNESS_SNAPSHOT_ENV = "TELECAM_HARNESS_SNAPSHOT"


def _bootstrap_harness_source_manifest(harness_root: Path) -> list[dict[str, object]]:
    """Hash harness bytes before importing any executable harness module."""
    entries: list[dict[str, object]] = []
    for path in sorted(harness_root.rglob("*")):
        relative = path.relative_to(harness_root)
        if any(part in _HARNESS_GENERATED_PARTS for part in relative.parts):
            continue
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode):
            raise RuntimeError(f"harness source must not be a symlink: {relative.as_posix()}")
        if stat.S_ISDIR(mode):
            continue
        if not stat.S_ISREG(mode):
            raise RuntimeError(f"harness source must be a regular file: {relative.as_posix()}")
        payload = path.read_bytes()
        entries.append(
            {
                "path": relative.as_posix(),
                "bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            }
        )
    if not entries:
        raise RuntimeError(f"no harness sources found under {harness_root}")
    return entries


def _copy_harness_snapshot(source_root: Path, snapshot_root: Path) -> list[dict[str, object]]:
    """Copy accepted regular inputs and return a manifest of the exact copied bytes."""
    entries: list[dict[str, object]] = []
    snapshot_root.mkdir(parents=True)
    for source in sorted(source_root.rglob("*")):
        relative = source.relative_to(source_root)
        if any(part in _HARNESS_GENERATED_PARTS for part in relative.parts):
            continue
        mode = source.lstat().st_mode
        if stat.S_ISLNK(mode):
            raise RuntimeError(f"harness source must not be a symlink: {relative.as_posix()}")
        if stat.S_ISDIR(mode):
            continue
        if not stat.S_ISREG(mode):
            raise RuntimeError(f"harness source must be a regular file: {relative.as_posix()}")
        payload = source.read_bytes()
        destination = snapshot_root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(payload)
        entries.append(
            {
                "path": relative.as_posix(),
                "bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            }
        )
    if not entries:
        raise RuntimeError(f"no harness sources found under {source_root}")
    return entries


def _run_from_immutable_harness_snapshot() -> int:
    """Execute the CLI from the exact private bytes recorded by its attestation."""
    temporary_root = Path(tempfile.mkdtemp(prefix="telecam-device-harness-"))
    staging_root = temporary_root / "staging"
    try:
        entries = _copy_harness_snapshot(HARNESS_ROOT, staging_root)
        canonical = "".join(
            f"{entry['sha256']}  {entry['bytes']}  {entry['path']}\n" for entry in entries
        ).encode()
        digest = hashlib.sha256(canonical).hexdigest()
        snapshot_root = temporary_root / f"harness-{digest}"
        staging_root.rename(snapshot_root)
        environment = os.environ.copy()
        environment[_HARNESS_SNAPSHOT_ENV] = digest
        environment["TELECAM_HARNESS_SOURCE_ROOT"] = str(HARNESS_ROOT)
        completed = subprocess.run(
            [sys.executable, str(snapshot_root / "run.py"), *sys.argv[1:]],
            env=environment,
            check=False,
        )
        return completed.returncode
    finally:
        shutil.rmtree(temporary_root, ignore_errors=True)


if __name__ == "__main__" and _HARNESS_SNAPSHOT_ENV not in os.environ:
    raise SystemExit(_run_from_immutable_harness_snapshot())


# This is intentionally evaluated before dtest/cases imports. A green run must verify these exact
# bytes both immediately before case dispatch and after restoration, so imports cannot execute one
# source revision while the attestation names another.
IMPORTED_HARNESS_SOURCES = _bootstrap_harness_source_manifest(HARNESS_ROOT)
_IMPORTED_MANIFEST_CANONICAL = "".join(
    f"{entry['sha256']}  {entry['bytes']}  {entry['path']}\n"
    for entry in IMPORTED_HARNESS_SOURCES
).encode()
_IMPORTED_MANIFEST_DIGEST = hashlib.sha256(_IMPORTED_MANIFEST_CANONICAL).hexdigest()
if (
    _HARNESS_SNAPSHOT_ENV in os.environ
    and os.environ[_HARNESS_SNAPSHOT_ENV] != _IMPORTED_MANIFEST_DIGEST
):
    raise RuntimeError(
        "harness snapshot digest mismatch: "
        f"expected={os.environ[_HARNESS_SNAPSHOT_ENV]} actual={_IMPORTED_MANIFEST_DIGEST}"
    )

sys.path.insert(0, str(HARNESS_ROOT))

from dtest.adb import APP_ID, MAIN_ACTIVITY, Adb  # noqa: E402
from dtest.contracts import (  # noqa: E402
    ApkContract,
    ContractError,
    DebugSourceIdentity,
    harness_source_manifest,
    inspect_apk_contract,
    production_capture_subdir,
    require_apk_source_match,
    source_manifest_sha256,
)
from dtest.framework import TIERS, run  # noqa: E402
import cases  # noqa: E402, F401  — registers all test cases

EXPECTED_MODEL = "PMA110"
EXPECTED_API = 36
DEFAULT_APK = Path(__file__).resolve().parent.parent / "app/build/outputs/apk/debug/app-debug.apk"
REPO_ROOT = SOURCE_HARNESS_ROOT.parent
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
    rows = list(manifest) if manifest is not None else harness_source_manifest(harness_root)
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
    reports_root = SOURCE_HARNESS_ROOT / "reports"
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
