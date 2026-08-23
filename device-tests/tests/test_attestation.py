from __future__ import annotations

import copy
import contextlib
import hashlib
import json
import os
import shutil
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


def copy_harness_fixture(parent: Path) -> Path:
    destination = parent / "device-tests"
    shutil.copytree(
        DEVICE_TESTS,
        destination,
        ignore=shutil.ignore_patterns("reports", "__pycache__", ".pytest_cache"),
    )
    return destination


class RunAttestationTest(unittest.TestCase):
    def test_authorized_child_acquires_serial_lock_before_device_runner(self) -> None:
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
            fake_snapshot = runner.ApkInspectionSnapshot(
                runner.DEFAULT_APK,
                runner.DEFAULT_APK,
                "a" * 64,
                SimpleNamespace(verify=lambda _phase: None),
            )
            with (
                patch.object(sys, "argv", ["run.py", "--serial", "serial-a"]),
                patch.object(runner, "sha256_file", return_value="a" * 64),
                patch.object(
                    runner,
                    "apk_inspection_snapshot",
                    return_value=contextlib.nullcontext(fake_snapshot),
                ),
                patch.object(runner, "inspect_apk_contract", return_value=apk_contract),
                patch.object(runner, "require_apk_source_match", return_value=packaged_source),
                patch.object(runner, "production_capture_subdir", return_value="TeleCamPro"),
                patch.object(runner, "_bootstrap_harness_source_manifest", return_value=[]),
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
                self.assertEqual(runner._run_authorized_child_cli(), 0)

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

    def test_imported_main_refuses_before_apk_or_device_preflight(self) -> None:
        script = textwrap.dedent(
            """
            import run

            def forbidden(*_args, **_kwargs):
                raise AssertionError("APK/device preflight must not run")

            run.apk_inspection_snapshot = forbidden
            run.probe_physical_device_identity = forbidden
            raise SystemExit(run.main())
            """
        )
        completed = subprocess.run(
            [sys.executable, "-c", script],
            cwd=DEVICE_TESTS.parent,
            env={**os.environ, "PYTHONPATH": str(DEVICE_TESTS)},
            capture_output=True,
            text=True,
            timeout=30,
        )

        self.assertEqual(completed.returncode, 2, completed.stdout + completed.stderr)
        self.assertIn("without inherited private-snapshot authority", completed.stderr)
        self.assertNotIn("APK/device preflight must not run", completed.stderr)

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

    def test_outer_child_uses_checkout_paths_for_default_apk_reports_and_forwarded_argv(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = copy_harness_fixture(root)
            controlled_tmp = root / "tmp"
            controlled_tmp.mkdir()
            expected_apk = root / "app/build/outputs/apk/debug/app-debug.apk"
            env = {
                **os.environ,
                "TMPDIR": str(controlled_tmp),
                # Neither caller-controlled value may be accepted as child proof/source authority.
                "TELECAM_HARNESS_SNAPSHOT": "0" * 64,
                "TELECAM_HARNESS_SOURCE_ROOT": "/caller-spoofed-harness-root",
            }

            default_run = subprocess.run(
                [sys.executable, str(source / "run.py"), "--serial", "no-device"],
                cwd=root,
                env=env,
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(2, default_run.returncode)
            output = default_run.stdout + default_run.stderr
            self.assertIn(str(expected_apk), output)
            self.assertNotIn("caller-spoofed-harness-root", output)
            self.assertNotIn("telecam-device-harness-", output)

            forwarded = subprocess.run(
                [
                    sys.executable,
                    str(source / "run.py"),
                    "--serial",
                    "no-device",
                    "--definitely-forwarded-unknown-option",
                ],
                cwd=root,
                env=env,
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(2, forwarded.returncode)
            self.assertIn("--definitely-forwarded-unknown-option", forwarded.stderr)

            self.assertEqual(expected_apk, runner.default_apk_path(source))
            self.assertEqual(source / "reports", runner.reports_root_path(source))
            self.assertEqual([], list(controlled_tmp.glob("telecam-device-harness-*")))

    def test_correct_digest_forged_direct_child_is_refused_before_imports(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            staging = root / "staging"
            entries = runner._copy_harness_snapshot(DEVICE_TESTS, staging)
            canonical = "".join(
                f"{entry['sha256']}  {entry['bytes']}  {entry['path']}\n" for entry in entries
            ).encode()
            digest = hashlib.sha256(canonical).hexdigest()
            snapshot = root / f"harness-{digest}"
            staging.rename(snapshot)
            completed = subprocess.run(
                [
                    sys.executable,
                    str(snapshot / "run.py"),
                    runner._FORBIDDEN_SERIALIZED_CHILD_OPTION,
                    digest,
                    "--serial",
                    "no-device",
                ],
                env={**os.environ, "TELECAM_HARNESS_SOURCE_ROOT": str(DEVICE_TESTS)},
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertNotEqual(0, completed.returncode)
            self.assertIn("only inherited fork authority is accepted", completed.stderr)

    def test_outer_child_imports_snapshot_after_mutable_source_changes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = copy_harness_fixture(root)
            controlled_tmp = root / "tmp"
            controlled_tmp.mkdir()
            def mutate_before_child(_snapshot: Path) -> None:
                (source / "cases.py").write_text("this is invalid python !!!\n", encoding="utf-8")

            result = runner._run_from_immutable_harness_snapshot(
                source_root=source,
                argv=["--serial", "no-device"],
                before_child=mutate_before_child,
                temporary_parent=controlled_tmp,
            )

            self.assertEqual(2, result)
            self.assertEqual([], list(controlled_tmp.glob("telecam-device-harness-*")))

    def test_outer_snapshot_rejects_file_swap_to_symlink_at_open(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            source.mkdir()
            original = source / "cases.py"
            original.write_text("VALUE = 'source'\n", encoding="utf-8")
            (source / "run.py").write_text("raise SystemExit(0)\n", encoding="utf-8")
            outside = root / "outside.py"
            outside.write_text("VALUE = 'outside'\n", encoding="utf-8")
            temporary_parent = root / "tmp"
            temporary_parent.mkdir()
            real_open = os.open
            swapped = False

            def swap_file(path, flags, mode=0o777, *, dir_fd=None):
                nonlocal swapped
                if path == "cases.py" and dir_fd is not None and not swapped:
                    swapped = True
                    original.rename(source / "cases.saved")
                    original.symlink_to(outside)
                return real_open(path, flags, mode, dir_fd=dir_fd)

            with patch.object(runner.os, "open", side_effect=swap_file):
                with self.assertRaisesRegex(RuntimeError, "stable non-symlink file: cases.py"):
                    runner._run_from_immutable_harness_snapshot(
                        source_root=source,
                        argv=[],
                        temporary_parent=temporary_parent,
                    )

    def test_outer_snapshot_rejects_regular_file_replacement_before_open(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            source.mkdir()
            original = source / "cases.py"
            original.write_text("VALUE = 'source'\n", encoding="utf-8")
            replacement = root / "replacement.py"
            replacement.write_text("VALUE = 'replacement'\n", encoding="utf-8")
            (source / "run.py").write_text("raise SystemExit(0)\n", encoding="utf-8")
            temporary_parent = root / "tmp"
            temporary_parent.mkdir()
            real_open = os.open
            swapped = False

            def swap_file(path, flags, mode=0o777, *, dir_fd=None):
                nonlocal swapped
                if path == "cases.py" and dir_fd is not None and not swapped:
                    swapped = True
                    original.rename(source / "cases.saved")
                    replacement.rename(original)
                return real_open(path, flags, mode, dir_fd=dir_fd)

            with patch.object(runner.os, "open", side_effect=swap_file):
                with self.assertRaisesRegex(RuntimeError, "changed before open: cases.py"):
                    runner._run_from_immutable_harness_snapshot(
                        source_root=source,
                        argv=[],
                        temporary_parent=temporary_parent,
                    )

    def test_snapshot_rejects_file_replacement_during_descriptor_read(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            source.mkdir()
            original = source / "cases.py"
            original.write_text("VALUE = 'source'\n", encoding="utf-8")
            replacement = root / "replacement.py"
            replacement.write_text("VALUE = 'replacement'\n", encoding="utf-8")
            real_read = os.read
            swapped = False

            def swap_after_read(fd, size):
                nonlocal swapped
                chunk = real_read(fd, size)
                if chunk and not swapped:
                    swapped = True
                    original.rename(source / "cases.saved")
                    replacement.rename(original)
                return chunk

            with patch.object(runner.os, "read", side_effect=swap_after_read):
                with self.assertRaisesRegex(RuntimeError, "changed while reading: cases.py"):
                    runner._copy_harness_snapshot(source, root / "snapshot")

    def test_outer_snapshot_rejects_parent_directory_swap_to_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            source.mkdir()
            package = source / "dtest"
            package.mkdir()
            (package / "module.py").write_text("VALUE = 'source'\n", encoding="utf-8")
            (source / "run.py").write_text("raise SystemExit(0)\n", encoding="utf-8")
            outside = root / "outside"
            outside.mkdir()
            (outside / "module.py").write_text("VALUE = 'outside'\n", encoding="utf-8")
            temporary_parent = root / "tmp"
            temporary_parent.mkdir()
            real_open = os.open
            swapped = False

            def swap_parent(path, flags, mode=0o777, *, dir_fd=None):
                nonlocal swapped
                if path == "dtest" and dir_fd is not None and not swapped:
                    swapped = True
                    package.rename(source / "dtest.saved")
                    package.symlink_to(outside, target_is_directory=True)
                return real_open(path, flags, mode, dir_fd=dir_fd)

            with patch.object(runner.os, "open", side_effect=swap_parent):
                with self.assertRaisesRegex(RuntimeError, "stable non-symlink: dtest"):
                    runner._run_from_immutable_harness_snapshot(
                        source_root=source,
                        argv=[],
                        temporary_parent=temporary_parent,
                    )

    def test_snapshot_rejects_parent_replacement_during_child_read(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            source.mkdir()
            package = source / "dtest"
            package.mkdir()
            (package / "module.py").write_text("VALUE = 'source'\n", encoding="utf-8")
            outside = root / "outside"
            outside.mkdir()
            (outside / "module.py").write_text("VALUE = 'outside'\n", encoding="utf-8")
            real_read = os.read
            swapped = False

            def swap_parent_after_read(fd, size):
                nonlocal swapped
                chunk = real_read(fd, size)
                if chunk and not swapped:
                    swapped = True
                    package.rename(source / "dtest.saved")
                    package.symlink_to(outside, target_is_directory=True)
                return chunk

            with patch.object(runner.os, "read", side_effect=swap_parent_after_read):
                with self.assertRaisesRegex(RuntimeError, "directory changed while reading: dtest"):
                    runner._copy_harness_snapshot(source, root / "snapshot")

    def test_snapshot_rejects_root_symlink_and_special_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            source.mkdir()
            (source / "run.py").write_text("raise SystemExit(0)\n", encoding="utf-8")
            link = root / "source-link"
            link.symlink_to(source, target_is_directory=True)
            with self.assertRaisesRegex(RuntimeError, "non-symlink directory"):
                runner._copy_harness_snapshot(link, root / "snapshot-link")

            fifo = source / "input.pipe"
            os.mkfifo(fifo)
            with self.assertRaisesRegex(RuntimeError, "must be a regular file: input.pipe"):
                runner._copy_harness_snapshot(source, root / "snapshot-fifo")

    def test_snapshot_read_budget_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            source.mkdir()
            (source / "run.py").write_bytes(b"12345")
            with patch.object(runner, "_MAX_HARNESS_FILE_BYTES", 4):
                with self.assertRaisesRegex(RuntimeError, "exceeds 4 bytes: run.py"):
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

    def test_apk_inspection_uses_one_private_regular_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "app-debug.apk"
            source.write_bytes(b"apk-A")
            with runner.apk_inspection_snapshot(source) as snapshot:
                self.assertEqual(snapshot.source_path, source)
                self.assertNotEqual(snapshot.private_path, source)
                self.assertEqual(snapshot.private_path.read_bytes(), b"apk-A")
                self.assertEqual(snapshot.sha256, hashlib.sha256(b"apk-A").hexdigest())
                self.assertEqual(snapshot.private_path.stat().st_mode & 0o777, 0o400)
                source.write_bytes(b"apk-B")
                self.assertEqual(snapshot.private_path.read_bytes(), b"apk-A")
                snapshot.verify("after original-path mutation")

    def test_private_apk_snapshot_rejects_permanent_and_transient_mutation(self) -> None:
        for restore in (False, True):
            with self.subTest(restore=restore), tempfile.TemporaryDirectory() as temp_dir:
                source = Path(temp_dir) / "app-debug.apk"
                source.write_bytes(b"apk-A")
                with runner.apk_inspection_snapshot(source) as snapshot:
                    snapshot.private_path.chmod(0o600)
                    snapshot.private_path.write_bytes(b"apk-B")
                    if restore:
                        snapshot.private_path.write_bytes(b"apk-A")
                    snapshot.private_path.chmod(0o400)
                    with self.assertRaisesRegex(ContractError, "private APK snapshot changed"):
                        snapshot.verify("between inspectors")

    def test_apk_snapshot_rejects_symlink_and_special_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            target = root / "target.apk"
            target.write_bytes(b"apk")
            link = root / "link.apk"
            link.symlink_to(target)
            with self.assertRaisesRegex(ContractError, "no-follow regular"):
                with runner.apk_inspection_snapshot(link):
                    pass
            fifo = root / "artifact.pipe"
            os.mkfifo(fifo)
            with self.assertRaisesRegex(ContractError, "no-follow regular"):
                with runner.apk_inspection_snapshot(fifo):
                    pass

    def test_apk_snapshot_is_inspector_authority_across_source_swap_restore(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "app-debug.apk"
            source.write_bytes(b"apk-A")
            inspected: list[Path] = []
            args = SimpleNamespace(serial="none")
            with runner.apk_inspection_snapshot(source) as snapshot:
                source.rename(root / "saved.apk")
                source.write_bytes(b"apk-B")
                source.unlink()
                (root / "saved.apk").rename(source)

                def inspect(path: Path, **_kwargs):
                    inspected.append(path)
                    return SimpleNamespace(application_id="wrong.package")

                def source_identity(path: Path, _repo: Path):
                    inspected.append(path)
                    return SimpleNamespace()

                with (
                    patch.object(runner, "inspect_apk_contract", side_effect=inspect),
                    patch.object(runner, "require_apk_source_match", side_effect=source_identity),
                    patch.object(runner, "production_capture_subdir", return_value="TeleCamPro"),
                    patch.object(runner, "require_harness_identity_unchanged"),
                ):
                    self.assertEqual(runner._run_snapshotted_cli(args, ["smoke"], snapshot), 2)
                self.assertEqual(inspected, [snapshot.private_path, snapshot.private_path])

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

            manifest = runner.artifact_manifest(report_dir)
            self.assertEqual(
                [artifact["path"] for artifact in manifest],
                ["evidence/frame.bin", "report.md"],
            )
            self.assertEqual(manifest[0]["sha256"], hashlib.sha256(b"frame").hexdigest())

            document = {"schema_version": 1, "artifacts": manifest}
            attestation, sidecar = runner.write_attestation(
                report_dir,
                document,
                expected_artifacts=manifest,
            )
            payload = attestation.read_bytes()
            self.assertEqual(json.loads(payload), document)
            self.assertEqual(
                sidecar.read_text(encoding="utf-8"),
                f"{hashlib.sha256(payload).hexdigest()}  {runner.ATTESTATION_NAME}\n",
            )
            self.assertEqual(
                runner.artifact_manifest(report_dir, allow_attestation_outputs=True),
                manifest,
            )

    def test_report_freeze_rejects_symlink_and_fifo(self) -> None:
        for special in ("symlink", "fifo"):
            with self.subTest(special=special), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                if special == "symlink":
                    target = root / "target.bin"
                    target.write_bytes(b"target")
                    (root / "evidence.bin").symlink_to(target)
                else:
                    os.mkfifo(root / "evidence.bin")
                with self.assertRaisesRegex(ContractError, "must not be a symlink|regular file"):
                    runner.artifact_manifest(root)

    def test_report_freeze_rejects_file_and_parent_swap(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            evidence = root / "evidence"
            evidence.mkdir()
            artifact = evidence / "frame.bin"
            artifact.write_bytes(b"frame")
            replacement = root / "replacement.bin"
            replacement.write_bytes(b"replacement")
            real_read = os.read
            swapped = False

            def swap_file(fd, size):
                nonlocal swapped
                chunk = real_read(fd, size)
                if chunk and not swapped:
                    swapped = True
                    artifact.rename(evidence / "saved.bin")
                    replacement.rename(artifact)
                return chunk

            with patch.object(runner.os, "read", side_effect=swap_file):
                with self.assertRaisesRegex(ContractError, "changed while reading"):
                    runner.artifact_manifest(root)

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            evidence = root / "evidence"
            evidence.mkdir()
            (evidence / "frame.bin").write_bytes(b"frame")
            outside = root / "outside"
            outside.mkdir()
            (outside / "frame.bin").write_bytes(b"outside")
            real_read = os.read
            swapped = False

            def swap_parent(fd, size):
                nonlocal swapped
                chunk = real_read(fd, size)
                if chunk and not swapped:
                    swapped = True
                    evidence.rename(root / "saved-evidence")
                    evidence.symlink_to(outside, target_is_directory=True)
                return chunk

            with patch.object(runner.os, "read", side_effect=swap_parent):
                with self.assertRaisesRegex(ContractError, "directory changed while reading"):
                    runner.artifact_manifest(root)

    def test_report_freeze_rejects_disappearance_and_unexpected_addition(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            first = root / "a.bin"
            second = root / "b.bin"
            first.write_bytes(b"a")
            second.write_bytes(b"b")
            real_read = os.read
            removed = False

            def remove_next(fd, size):
                nonlocal removed
                chunk = real_read(fd, size)
                if chunk and not removed:
                    removed = True
                    second.unlink()
                return chunk

            with patch.object(runner.os, "read", side_effect=remove_next):
                with self.assertRaisesRegex(ContractError, "disappeared|artifact set changed"):
                    runner.artifact_manifest(root)

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "a.bin").write_bytes(b"a")
            real_read = os.read
            added = False

            def add_unexpected(fd, size):
                nonlocal added
                chunk = real_read(fd, size)
                if chunk and not added:
                    added = True
                    (root / "unexpected.bin").write_bytes(b"unexpected")
                return chunk

            with patch.object(runner.os, "read", side_effect=add_unexpected):
                with self.assertRaisesRegex(ContractError, "artifact set changed"):
                    runner.artifact_manifest(root)

    def test_attestation_refuses_artifact_set_change_after_freeze(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "report.md").write_text("report\n", encoding="utf-8")
            frozen = runner.artifact_manifest(root)
            (root / "unexpected.bin").write_bytes(b"unexpected")
            with self.assertRaisesRegex(ContractError, "changed before attestation"):
                runner.write_attestation(
                    root,
                    {"schema_version": 1, "artifacts": frozen},
                    expected_artifacts=frozen,
                )

    def test_post_sidecar_report_race_rolls_back_the_attestation_pair(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "report.md").write_text("report\n", encoding="utf-8")
            frozen = runner.artifact_manifest(root)
            real_manifest = runner.artifact_manifest
            manifest_calls = 0

            def race_after_sidecar(report_dir: Path, *, allow_attestation_outputs: bool = False):
                nonlocal manifest_calls
                manifest_calls += 1
                if manifest_calls == 2:
                    # The second call is the final report-set proof, after both reserved outputs
                    # have been written and individually digested.
                    self.assertTrue((root / runner.ATTESTATION_NAME).is_file())
                    self.assertTrue((root / runner.ATTESTATION_SHA_NAME).is_file())
                    (root / "late-evidence.bin").write_bytes(b"late")
                return real_manifest(
                    report_dir,
                    allow_attestation_outputs=allow_attestation_outputs,
                )

            with patch.object(runner, "artifact_manifest", side_effect=race_after_sidecar):
                with self.assertRaisesRegex(ContractError, "changed during attestation"):
                    runner.write_attestation(
                        root,
                        {"schema_version": 1, "artifacts": frozen},
                        expected_artifacts=frozen,
                    )

            self.assertFalse((root / runner.ATTESTATION_NAME).exists())
            self.assertFalse((root / runner.ATTESTATION_SHA_NAME).exists())
            self.assertEqual((root / "late-evidence.bin").read_bytes(), b"late")

    def test_attestation_refuses_reserved_existing_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            report_dir = Path(temp_dir)
            (report_dir / "report.md").write_text("report\n", encoding="utf-8")
            (report_dir / runner.ATTESTATION_NAME).write_text("stale", encoding="utf-8")
            with self.assertRaisesRegex(ContractError, "reserved attestation output"):
                runner.artifact_manifest(report_dir)

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
