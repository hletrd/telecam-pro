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
import subprocess
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).parent))

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
REPO_ROOT = Path(__file__).resolve().parent.parent
ATTESTATION_NAME = "run-attestation.json"
ATTESTATION_SHA_NAME = "run-attestation.sha256"
RESTORED_SETTINGS = ("font_scale", "accelerometer_rotation", "user_rotation")
REPORT_ALLOCATION_ATTEMPTS = 16


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


class DeviceRunLock:
    """Process lock for one adb serial. The stable lock inode is deliberately never unlinked."""

    def __init__(self, handle, path: Path, serial: str, run_id: str):
        self._handle = handle
        self.path = path
        self.serial = serial
        self.run_id = run_id
        self._released = False

    @classmethod
    def acquire(cls, reports_root: Path, serial: str, run_id: str) -> DeviceRunLock:
        lock_root = reports_root / ".locks"
        try:
            lock_root.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            raise DeviceRunLockError(f"could not create device lock directory: {error}") from error
        serial_key = hashlib.sha256(serial.encode("utf-8")).hexdigest()
        path = lock_root / f"{serial_key}.lock"
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
                    f"could not acquire device {serial!r} lock {path}: {error}",
                ) from error
            try:
                handle.seek(0)
                holder = handle.read().strip() or "holder metadata unavailable"
            finally:
                handle.close()
            raise DeviceRunLockError(
                f"device {serial!r} is already owned by another harness run: {holder}",
            ) from error

        try:
            handle.seek(0)
            handle.truncate()
            json.dump(
                {
                    "pid": os.getpid(),
                    "run_id": run_id,
                    "serial": serial,
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
        return cls(handle, path, serial, run_id)

    def release(self) -> None:
        if self._released:
            return
        try:
            fcntl.flock(self._handle.fileno(), fcntl.LOCK_UN)
        except OSError as error:
            raise DeviceRunLockError(
                f"could not release device {self.serial!r} lock for run {self.run_id}: {error}",
            ) from error
        finally:
            self._handle.close()
            self._released = True

    def __enter__(self) -> DeviceRunLock:
        return self

    def __exit__(self, _type, _value, _traceback) -> None:
        self.release()


def write_run_identity(allocation: ReportAllocation, *, serial: str) -> None:
    """Record the allocated run before lock acquisition or any ADB interaction can fail."""
    payload = {
        "schema_version": 1,
        "run_id": allocation.run_id,
        "serial": serial,
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
) -> None:
    """Persist a non-green preflight/ownership failure in the uniquely owned report directory."""
    payload = {
        "schema_version": 1,
        "run_id": allocation.run_id,
        "serial": serial,
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
        f"- restoration: **{status}**",
        f"- final CLI exit code: `{final_exit_code}`",
        f"- APK source identity: `{source_identity}`",
        f"- metadata: `{ATTESTATION_NAME}` (`{ATTESTATION_SHA_NAME}`)",
    ]
    if errors:
        lines.append("- restoration errors: " + "; ".join(errors))
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
    harness_sources: list[dict[str, object]],
    allocation: ReportAllocation,
) -> int:
    """Run every ADB operation while the caller owns this serial's process lock."""
    report_dir = allocation.directory
    # Preflight: device reachable and the debug app installed. The adb client can hang on a
    # half-dead TCP transport, so even this first probe is deadline-bounded.
    try:
        probe = subprocess.run(
            ["adb", "-s", args.serial, "get-state"],
            capture_output=True,
            text=True,
            timeout=15,
        )
    except subprocess.TimeoutExpired:
        print(f"device {args.serial} probe timed out\nhint: adb connect {args.serial}",
              file=sys.stderr)
        return 2
    if probe.returncode != 0 or probe.stdout.strip() != "device":
        print(f"device {args.serial} not ready: {probe.stderr.strip() or probe.stdout.strip()}\n"
              f"hint: adb connect {args.serial}", file=sys.stderr)
        return 2

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
    final_exit_code = attested_exit_code(case_exit_code, restore_errors)
    append_attestation_summary(
        report_dir,
        final_exit_code=final_exit_code,
        errors=restore_errors,
        source_identity=packaged_source.identity,
    )
    document: dict[str, object] = {
        "schema_version": 3,
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
            "source_manifest": harness_sources,
            "source_manifest_sha256": source_manifest_sha256(harness_sources),
        },
        "device": {
            "serial": args.serial,
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
        },
        "result": {
            "case_exit_code": case_exit_code,
            "final_exit_code": final_exit_code,
            "restoration": "pass" if not restore_errors else "fail",
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
    if restore_errors:
        print("Restoration attestation failed: " + "; ".join(restore_errors), file=sys.stderr)
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
        harness_sources = harness_source_manifest(Path(__file__).resolve().parent)
    except (ContractError, OSError) as error:
        print(f"could not establish APK/harness contract: {error}", file=sys.stderr)
        return 2
    if apk_contract.application_id != APP_ID:
        print(
            f"refusing non-debug APK identity {apk_contract.application_id!r}; expected {APP_ID!r}",
            file=sys.stderr,
        )
        return 2
    reports_root = Path(__file__).parent / "reports"
    try:
        allocation = allocate_report_directory(reports_root)
        write_run_identity(allocation, serial=args.serial)
    except ContractError as error:
        print(f"could not allocate/record device-test report: {error}", file=sys.stderr)
        return 2

    try:
        with DeviceRunLock.acquire(reports_root, args.serial, allocation.run_id):
            return run_locked_device(
                args,
                tiers,
                expected_apk,
                expected_sha,
                apk_contract,
                packaged_source,
                production_subdir,
                harness_sources,
                allocation,
            )
    except DeviceRunLockError as error:
        write_run_failure(
            allocation,
            serial=args.serial,
            phase="device-lock",
            error=str(error),
        )
        print(
            f"device-test ownership failed for run {allocation.run_id}: {error}\n"
            f"failure report: {allocation.directory / 'run-failure.json'}",
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
