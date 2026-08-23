from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import threading
import textwrap
import unittest
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


DEVICE_TESTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEVICE_TESTS))

import run as runner  # noqa: E402
from dtest.adb import MAIN_ACTIVITY, DisplayMetrics  # noqa: E402
from dtest.contracts import ContractError  # noqa: E402


class RunAttestationTest(unittest.TestCase):
    def test_main_acquires_serial_lock_before_device_runner(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            directory = Path(temp_dir) / "run"
            directory.mkdir()
            allocation = runner.ReportAllocation("20260823-120000-aaaaaaaaaaaa", directory)
            physical_identity = runner.PhysicalDeviceIdentity(
                canonical_key="ro.serialno-sha256:" + "a" * 64,
                source="ro.serialno",
            )
            events: list[str] = []

            class HeldLock:
                def __enter__(self):
                    events.append("lock-enter")
                    return self

                def __exit__(self, _type, _value, _traceback):
                    events.append("lock-release")

            def allocate(_root: Path) -> runner.ReportAllocation:
                events.append("allocate")
                return allocation

            def record_identity(
                _allocation: runner.ReportAllocation,
                *,
                serial: str,
                physical_identity: runner.PhysicalDeviceIdentity | None = None,
            ) -> None:
                self.assertEqual(serial, "serial-a")
                events.append("identity" if physical_identity is None else "physical-identity")

            def acquire(
                _root: Path,
                serial: str,
                identity: runner.PhysicalDeviceIdentity,
                run_id: str,
            ) -> HeldLock:
                self.assertEqual((serial, identity, run_id), ("serial-a", physical_identity, allocation.run_id))
                events.append("lock-acquire")
                return HeldLock()

            def device_run(*_args, **_kwargs) -> int:
                events.append("device-run")
                return 0

            apk_contract = SimpleNamespace(application_id=runner.APP_ID)
            packaged_source = SimpleNamespace()
            with (
                patch.object(sys, "argv", ["run.py", "--serial", "serial-a"]),
                patch.object(runner, "sha256_file", return_value="a" * 64),
                patch.object(runner, "inspect_apk_contract", return_value=apk_contract),
                patch.object(runner, "require_apk_source_match", return_value=packaged_source),
                patch.object(runner, "production_capture_subdir", return_value="TeleCamPro"),
                patch.object(runner, "harness_source_manifest", return_value=[]),
                patch.object(runner, "require_harness_identity_unchanged"),
                patch.object(runner, "allocate_report_directory", side_effect=allocate),
                patch.object(runner, "write_run_identity", side_effect=record_identity),
                patch.object(
                    runner,
                    "probe_physical_device_identity",
                    return_value=physical_identity,
                ),
                patch.object(runner, "host_global_device_lock_root", return_value=Path("/host-locks")),
                patch.object(runner.DeviceRunLock, "acquire", side_effect=acquire),
                patch.object(runner, "run_locked_device", side_effect=device_run),
            ):
                self.assertEqual(runner.main(), 0)

            self.assertEqual(
                events,
                [
                    "allocate",
                    "identity",
                    "physical-identity",
                    "lock-acquire",
                    "lock-enter",
                    "device-run",
                    "lock-release",
                ],
            )

    def test_frozen_clock_concurrent_report_allocations_are_unique_and_atomic(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            reports = Path(temp_dir) / "reports"
            tokens = deque(["a" * 12, "a" * 12, "b" * 12, "c" * 12])
            token_lock = threading.Lock()
            start = threading.Barrier(2)

            def allocate() -> runner.ReportAllocation:
                start.wait(timeout=2)

                def next_token() -> str:
                    with token_lock:
                        return tokens.popleft()

                return runner.allocate_report_directory(
                    reports,
                    timestamp="20260823-120000",
                    token_factory=next_token,
                )

            with ThreadPoolExecutor(max_workers=2) as executor:
                futures = [executor.submit(allocate), executor.submit(allocate)]
                allocations = [future.result() for future in futures]

            self.assertEqual(
                {allocation.run_id for allocation in allocations},
                {"20260823-120000-aaaaaaaaaaaa", "20260823-120000-bbbbbbbbbbbb"},
            )
            self.assertTrue(all(allocation.directory.is_dir() for allocation in allocations))

    def test_report_allocation_exhaustion_names_every_collision(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            reports = Path(temp_dir) / "reports"
            existing = reports / "20260823-120000-aaaaaaaaaaaa"
            existing.mkdir(parents=True)

            with self.assertRaisesRegex(ContractError, "2 atomic attempts.*aaaaaaaaaaaa"):
                runner.allocate_report_directory(
                    reports,
                    timestamp="20260823-120000",
                    token_factory=lambda: "a" * 12,
                    max_attempts=2,
                )

    def test_device_lock_serializes_physical_identity_across_aliases_and_report_roots(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            lock_root = Path(temp_dir) / "host-global-locks"
            report_a = runner.allocate_report_directory(
                Path(temp_dir) / "checkout-a/reports",
                timestamp="20260823-120000",
                token_factory=lambda: "a" * 12,
            )
            report_b = runner.allocate_report_directory(
                Path(temp_dir) / "checkout-b/reports",
                timestamp="20260823-120000",
                token_factory=lambda: "b" * 12,
            )
            same_device = runner.canonical_physical_device_identity(
                {"ro.serialno": "PMA110-PHYSICAL"}
            )
            other_device = runner.canonical_physical_device_identity(
                {"ro.serialno": "PMA110-OTHER"}
            )
            first = runner.DeviceRunLock.acquire(
                lock_root,
                "192.0.2.10:37777",
                same_device,
                report_a.run_id,
            )
            other = runner.DeviceRunLock.acquire(
                lock_root,
                "192.0.2.11:38888",
                other_device,
                "run-other-device",
            )
            try:
                with self.assertRaisesRegex(runner.DeviceRunLockError, report_a.run_id):
                    runner.DeviceRunLock.acquire(
                        lock_root,
                        "127.0.0.1:5599",
                        same_device,
                        report_b.run_id,
                    )
            finally:
                other.release()
                first.release()

            replacement = runner.DeviceRunLock.acquire(
                lock_root,
                "127.0.0.1:5599",
                same_device,
                "replacement",
            )
            replacement.release()

    def test_physical_identity_uses_boot_serial_fallback_and_refuses_user_scoped_id(self) -> None:
        boot = runner.canonical_physical_device_identity(
            {"ro.serialno": "unknown", "ro.boot.serialno": "BOOT-PHYSICAL"}
        )
        self.assertEqual("ro.boot.serialno", boot.source)
        framework = runner.canonical_physical_device_identity(
            {"ro.serialno": "BOOT-PHYSICAL"}
        )
        self.assertEqual(framework.canonical_key, boot.canonical_key)
        with self.assertRaisesRegex(runner.PhysicalDeviceIdentityError, "no stable physical"):
            runner.canonical_physical_device_identity(
                {"ro.serialno": "", "ro.boot.serialno": "", "android_id": "0123456789abcdef"}
            )

    def test_device_lock_context_releases_after_failure_and_records_lock_refusal(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            reports = Path(temp_dir) / "reports"
            allocation = runner.allocate_report_directory(
                reports,
                timestamp="20260823-120000",
                token_factory=lambda: "d" * 12,
            )
            identity = runner.canonical_physical_device_identity(
                {"ro.serialno": "PMA110-PHYSICAL"}
            )
            runner.write_run_identity(
                allocation,
                serial="serial-a",
                physical_identity=identity,
            )
            recorded_identity = json.loads(
                (allocation.directory / "run-identity.json").read_text(encoding="utf-8")
            )
            self.assertEqual(recorded_identity["run_id"], allocation.run_id)
            self.assertEqual(recorded_identity["connection_alias"], "serial-a")
            self.assertEqual(recorded_identity["physical_device_key"], identity.canonical_key)
            with self.assertRaisesRegex(RuntimeError, "simulated run failure"):
                with runner.DeviceRunLock.acquire(
                    reports / ".locks",
                    "serial-a",
                    identity,
                    allocation.run_id,
                ):
                    raise RuntimeError("simulated run failure")

            replacement = runner.DeviceRunLock.acquire(
                reports / ".locks", "serial-a", identity, "replacement"
            )
            try:
                with self.assertRaises(runner.DeviceRunLockError) as refused:
                    runner.DeviceRunLock.acquire(
                        reports / ".locks", "alias-b", identity, "refused"
                    )
                runner.write_run_failure(
                    allocation,
                    serial="serial-a",
                    phase="device-lock",
                    error=str(refused.exception),
                    physical_identity=identity,
                )
            finally:
                replacement.release()

            failure = json.loads(
                (allocation.directory / "run-failure.json").read_text(encoding="utf-8")
            )
            self.assertEqual(failure["run_id"], allocation.run_id)
            self.assertEqual(failure["connection_alias"], "serial-a")
            self.assertEqual(failure["physical_device_key"], identity.canonical_key)
            self.assertEqual(failure["phase"], "device-lock")
            self.assertIn("replacement", failure["error"])

    def test_physical_identity_probe_is_alias_independent_and_never_attests_raw_id(self) -> None:
        responses = {
            ("direct", "get-state"): "device\n",
            ("proxy", "get-state"): "device\n",
            ("direct", "shell getprop ro.serialno"): "RAW-PHYSICAL-SERIAL\n",
            ("proxy", "shell getprop ro.serialno"): "RAW-PHYSICAL-SERIAL\n",
            ("direct", "shell getprop ro.boot.serialno"): "boot-value\n",
            ("proxy", "shell getprop ro.boot.serialno"): "boot-value\n",
            ("direct", "shell settings get secure android_id"): "0123456789abcdef\n",
            ("proxy", "shell settings get secure android_id"): "0123456789abcdef\n",
        }

        def run_command(command, **_kwargs):
            serial = command[2]
            operation = " ".join(command[3:])
            return SimpleNamespace(returncode=0, stdout=responses[(serial, operation)], stderr="")

        direct = runner.probe_physical_device_identity("direct", run_command=run_command)
        proxy = runner.probe_physical_device_identity("proxy", run_command=run_command)

        self.assertEqual(direct, proxy)
        self.assertEqual("ro.serialno", direct.source)
        self.assertNotIn("RAW-PHYSICAL-SERIAL", direct.canonical_key)

    def test_paused_subprocess_rejects_harness_drift_before_and_after_execution(self) -> None:
        program = textwrap.dedent(
            f"""
            import sys
            from pathlib import Path
            sys.path.insert(0, {str(DEVICE_TESTS)!r})
            import run as runner
            root = Path(sys.argv[1])
            expected = runner.harness_execution_identity(root)
            print("CAPTURED", flush=True)
            sys.stdin.readline()
            try:
                runner.require_harness_identity_unchanged(expected, root, phase="before case dispatch")
            except runner.ContractError:
                print("PRE_DRIFT", flush=True)
                raise SystemExit(2)
            print("PRE_OK", flush=True)
            sys.stdin.readline()
            try:
                runner.require_harness_identity_unchanged(expected, root, phase="after case execution")
            except runner.ContractError:
                print("POST_DRIFT", flush=True)
                raise SystemExit(2)
            print("POST_OK", flush=True)
            """
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "cases.py"
            source.write_text("version = 1\n", encoding="utf-8")

            before = subprocess.Popen(
                [sys.executable, "-c", program, str(root)],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                text=True,
            )
            self.assertEqual("CAPTURED", before.stdout.readline().strip())
            source.write_text("version = 2\n", encoding="utf-8")
            before.stdin.write("continue\n")
            before.stdin.flush()
            self.assertEqual("PRE_DRIFT", before.stdout.readline().strip())
            self.assertEqual(2, before.wait(timeout=10))
            before.stdin.close()
            before.stdout.close()

            source.write_text("version = 3\n", encoding="utf-8")
            after = subprocess.Popen(
                [sys.executable, "-c", program, str(root)],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                text=True,
            )
            self.assertEqual("CAPTURED", after.stdout.readline().strip())
            after.stdin.write("pre\n")
            after.stdin.flush()
            self.assertEqual("PRE_OK", after.stdout.readline().strip())
            source.write_text("version = 4\n", encoding="utf-8")
            after.stdin.write("post\n")
            after.stdin.flush()
            self.assertEqual("POST_DRIFT", after.stdout.readline().strip())
            self.assertEqual(2, after.wait(timeout=10))
            after.stdin.close()
            after.stdout.close()

    def test_snapshot_import_is_bound_to_copied_bytes_after_source_restoration(self) -> None:
        program = textwrap.dedent(
            f"""
            import sys
            from pathlib import Path
            sys.path.insert(0, {str(DEVICE_TESTS)!r})
            import run as runner
            source = Path(sys.argv[1])
            snapshot = Path(sys.argv[2])
            runner._copy_harness_snapshot(source, snapshot)
            print("SNAPSHOT", flush=True)
            sys.stdin.readline()
            sys.path.insert(0, str(snapshot))
            import snapshot_probe
            print(snapshot_probe.VERSION, flush=True)
            """
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            snapshot = root / "snapshot"
            source.mkdir()
            module = source / "snapshot_probe.py"
            module.write_text("VERSION = 'attested'\n", encoding="utf-8")
            process = subprocess.Popen(
                [sys.executable, "-c", program, str(source), str(snapshot)],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                text=True,
            )
            self.assertEqual("SNAPSHOT", process.stdout.readline().strip())
            module.write_text("VERSION = 'mutated'\n", encoding="utf-8")
            module.write_text("VERSION = 'attested'\n", encoding="utf-8")
            process.stdin.write("continue\n")
            process.stdin.flush()
            self.assertEqual("attested", process.stdout.readline().strip())
            self.assertEqual(0, process.wait(timeout=10))
            process.stdin.close()
            process.stdout.close()

    def test_snapshot_rejects_symlinked_executable_input(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            source.mkdir()
            target = root / "outside.py"
            target.write_text("VALUE = 1\n", encoding="utf-8")
            (source / "cases.py").symlink_to(target)

            with self.assertRaisesRegex(RuntimeError, "must not be a symlink: cases.py"):
                runner._copy_harness_snapshot(source, root / "snapshot")

    def test_restoration_attests_display_geometry(self) -> None:
        before = {
            "foreground_component": MAIN_ACTIVITY,
            "display": {"width_px": 1440, "height_px": 3168, "density_dpi": 560},
            "settings": {},
            "locale": {},
        }
        after = {
            **before,
            "display": {"width_px": 640, "height_px": 600, "density_dpi": 320},
        }
        self.assertTrue(
            any("display changed" in error for error in runner.restoration_errors(before, after))
        )

    def test_host_and_installed_apk_must_match_exactly(self) -> None:
        expected = "a" * 64
        self.assertEqual(
            runner.require_installed_apk_match(expected, f"{expected.upper()}  /base.apk\n"),
            expected,
        )
        with self.assertRaisesRegex(ContractError, "stale/mismatched install"):
            runner.require_installed_apk_match(expected, f"{'b' * 64}  /base.apk\n")
        with self.assertRaisesRegex(ContractError, "missing or malformed"):
            runner.require_installed_apk_match(expected, "not-a-digest /base.apk\n")

    def test_git_identity_records_head_and_exact_dirty_rows(self) -> None:
        responses = [
            SimpleNamespace(stdout="0123456789abcdef\n"),
            SimpleNamespace(stdout=" M device-tests/run.py\n?? local-note.txt\n"),
        ]
        with patch.object(runner.subprocess, "run", side_effect=responses) as run_command:
            identity = runner.git_identity(Path("/repo"))

        self.assertEqual(
            identity,
            {
                "head": "0123456789abcdef",
                "dirty": True,
                "status": [" M device-tests/run.py", "?? local-note.txt"],
            },
        )
        self.assertEqual(run_command.call_count, 2)
        self.assertEqual(run_command.call_args_list[0].args[0], ["git", "rev-parse", "HEAD"])
        self.assertEqual(
            run_command.call_args_list[1].args[0],
            ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        )

    def test_device_state_records_display_foreground_and_restored_settings(self) -> None:
        class StateAdb:
            def __init__(self):
                self.commands: list[str] = []

            @staticmethod
            def display_metrics() -> DisplayMetrics:
                return DisplayMetrics(1440, 3168, 560)

            @staticmethod
            def resumed_activity() -> str:
                return MAIN_ACTIVITY

            @staticmethod
            def locale_state() -> dict[str, str]:
                return {
                    "app_override_raw": "Locales for app: []",
                    "user": "0",
                    "system": "ko-KR",
                    "effective": "ko-KR",
                }

            def shell(self, command: str) -> str:
                self.commands.append(command)
                return {
                    "settings get system font_scale": "0.8",
                    "settings get system accelerometer_rotation": "0",
                    "settings get system user_rotation": "0",
                }[command]

        adb = StateAdb()

        self.assertEqual(
            runner.device_state(adb),  # type: ignore[arg-type]
            {
                "foreground_component": MAIN_ACTIVITY,
                "display": {"width_px": 1440, "height_px": 3168, "density_dpi": 560},
                "settings": {
                    "font_scale": "0.8",
                    "accelerometer_rotation": "0",
                    "user_rotation": "0",
                },
                "locale": {
                    "app_override_raw": "Locales for app: []",
                    "user": "0",
                    "system": "ko-KR",
                    "effective": "ko-KR",
                },
            },
        )
        self.assertEqual(
            adb.commands,
            [f"settings get system {name}" for name in runner.RESTORED_SETTINGS],
        )

    def test_restoration_errors_require_main_and_unchanged_settings(self) -> None:
        before = {
            "foreground_component": MAIN_ACTIVITY,
            "settings": {
                "font_scale": "0.8",
                "accelerometer_rotation": "0",
                "user_rotation": "0",
            },
            "locale": {
                "app_override_raw": "Locales for app: []",
                "user": "0",
                "system": "ko-KR",
                "effective": "ko-KR",
            },
        }
        after = copy.deepcopy(before)
        self.assertEqual(runner.restoration_errors(before, after), [])
        self.assertEqual(runner.attested_exit_code(0, []), 0)

        after["foreground_component"] = "com.android.launcher/.Launcher"
        after["settings"]["font_scale"] = "1.0"  # type: ignore[index]
        errors = runner.restoration_errors(before, after)
        self.assertTrue(any("MainActivity" in error for error in errors))
        self.assertTrue(any("font_scale" in error for error in errors))
        self.assertEqual(runner.attested_exit_code(0, errors), 2)
        self.assertEqual(runner.attested_exit_code(1, errors), 1)
        self.assertEqual(
            runner.restoration_errors(before, None),
            ["post-run device state could not be collected"],
        )

    def test_attestation_hashes_sorted_artifacts_and_excludes_itself(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            report_dir = Path(temp_dir)
            evidence = report_dir / "evidence"
            evidence.mkdir()
            (report_dir / "report.md").write_text("report\n", encoding="utf-8")
            (evidence / "frame.bin").write_bytes(b"frame")
            (report_dir / runner.ATTESTATION_NAME).write_text("stale", encoding="utf-8")
            (report_dir / runner.ATTESTATION_SHA_NAME).write_text("stale", encoding="utf-8")

            manifest = runner.artifact_manifest(report_dir)
            self.assertEqual(
                [artifact["path"] for artifact in manifest],
                ["evidence/frame.bin", "report.md"],
            )
            self.assertEqual(manifest[0]["sha256"], hashlib.sha256(b"frame").hexdigest())

            document = {"schema_version": 1, "artifacts": manifest}
            attestation, sidecar = runner.write_attestation(report_dir, document)
            payload = attestation.read_bytes()
            self.assertEqual(json.loads(payload), document)
            self.assertEqual(
                sidecar.read_text(encoding="utf-8"),
                f"{hashlib.sha256(payload).hexdigest()}  {runner.ATTESTATION_NAME}\n",
            )
            self.assertEqual(runner.artifact_manifest(report_dir), manifest)

    def test_report_summary_exposes_restoration_failure_and_final_exit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            report_dir = Path(temp_dir)
            report = report_dir / "report.md"
            report.write_text("# Device test report\n", encoding="utf-8")

            runner.append_attestation_summary(
                report_dir,
                final_exit_code=2,
                errors=["font_scale changed from '0.8' to '1.0'"],
                source_identity="git:" + "a" * 40 + "/content-sha256:" + "b" * 64 + "/clean",
            )

            rendered = report.read_text(encoding="utf-8")
            self.assertIn("evidence verification: **FAIL**", rendered)
            self.assertIn("final CLI exit code: `2`", rendered)
            self.assertIn(runner.ATTESTATION_NAME, rendered)
            self.assertIn("APK source identity", rendered)
            self.assertIn("font_scale changed", rendered)


if __name__ == "__main__":
    unittest.main()
