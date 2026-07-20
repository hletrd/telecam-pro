from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


DEVICE_TESTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEVICE_TESTS))

import run as runner  # noqa: E402
from dtest import framework  # noqa: E402
from dtest.adb import APP_ID, Adb, AdbError  # noqa: E402


class FakeAdb:
    serial = "test-serial"


class FakeTree:
    def __init__(self, descriptions: set[str]):
        self.descriptions = descriptions

    def find(self, *, desc: str | None = None, text: str | None = None):
        del text
        if desc in self.descriptions:
            return object()
        return None


class GuardAdb(Adb):
    def __init__(self, workdir: Path, *, allow_destructive: bool, descriptions: set[str]):
        super().__init__("test-serial", workdir, allow_destructive=allow_destructive)
        self.descriptions = descriptions
        self.commands: list[str] = []

    def pid(self) -> int | None:
        return 123

    def ui(self) -> FakeTree:
        return FakeTree(self.descriptions)

    def shell(self, cmd: str, timeout: int = 60) -> str:
        del timeout
        self.commands.append(cmd)
        return ""


class RunnerHelpersTest(unittest.TestCase):
    def test_base_apk_path_prefers_base_split(self) -> None:
        output = "\n".join(
            (
                "package:/data/app/example/split_config.arm64_v8a.apk",
                "package:/data/app/example/base.apk",
            )
        )

        self.assertEqual(runner.base_apk_path(output), "/data/app/example/base.apk")

    def test_base_apk_path_rejects_missing_package_rows(self) -> None:
        self.assertIsNone(runner.base_apk_path(""))

    def test_sha256_file_streams_the_exact_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture = Path(temp_dir) / "fixture.apk"
            fixture.write_bytes(b"TeleCam Pro")

            self.assertEqual(
                runner.sha256_file(fixture),
                "0941d239425b174d99d3eb516e36fcff357b668a7ad24e5e481531f59a5ec28f",
            )


class ForceStopGuardTest(unittest.TestCase):
    def make_adb(self, *, allowed: bool, descriptions: set[str]) -> tuple[GuardAdb, tempfile.TemporaryDirectory]:
        temp_dir = tempfile.TemporaryDirectory()
        return (
            GuardAdb(Path(temp_dir.name), allow_destructive=allowed, descriptions=descriptions),
            temp_dir,
        )

    def test_force_stop_requires_destructive_guard(self) -> None:
        adb, temp_dir = self.make_adb(allowed=False, descriptions={"Take photo"})
        self.addCleanup(temp_dir.cleanup)

        with self.assertRaisesRegex(AdbError, "explicit destructive approval"):
            adb.force_stop()

        self.assertNotIn(f"am force-stop {APP_ID}", adb.commands)

    def test_force_stop_refuses_active_recording(self) -> None:
        adb, temp_dir = self.make_adb(allowed=True, descriptions={"Stop recording"})
        self.addCleanup(temp_dir.cleanup)

        with self.assertRaisesRegex(AdbError, "recording is active"):
            adb.force_stop()

        self.assertNotIn(f"am force-stop {APP_ID}", adb.commands)

    def test_force_stop_refuses_unknown_ui_state(self) -> None:
        adb, temp_dir = self.make_adb(allowed=True, descriptions=set())
        self.addCleanup(temp_dir.cleanup)

        with self.assertRaisesRegex(AdbError, "idle camera state cannot be proven"):
            adb.force_stop()

        self.assertNotIn(f"am force-stop {APP_ID}", adb.commands)

    def test_force_stop_allows_proven_idle_video(self) -> None:
        adb, temp_dir = self.make_adb(allowed=True, descriptions={"Start recording"})
        self.addCleanup(temp_dir.cleanup)

        adb.force_stop()

        self.assertIn(f"am force-stop {APP_ID}", adb.commands)


class FrameworkSafetyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_registry = list(framework._REGISTRY)
        framework._REGISTRY.clear()

    def tearDown(self) -> None:
        framework._REGISTRY[:] = self.original_registry

    def run_suite(self, *, allow_destructive: bool = False, name_filter: str | None = None) -> int:
        with tempfile.TemporaryDirectory() as temp_dir:
            return framework.run(
                FakeAdb(),
                ["smoke"],
                name_filter,
                Path(temp_dir),
                allow_destructive=allow_destructive,
            )

    def test_destructive_case_is_not_invoked_without_guard(self) -> None:
        called = False

        @framework.test("guarded", "smoke", destructive=True)
        def guarded(_ctx) -> None:
            nonlocal called
            called = True

        self.assertEqual(self.run_suite(), 2)
        self.assertFalse(called)

    def test_destructive_case_runs_only_with_explicit_guard(self) -> None:
        called = False

        @framework.test("guarded", "smoke", destructive=True)
        def guarded(_ctx) -> None:
            nonlocal called
            called = True

        self.assertEqual(self.run_suite(allow_destructive=True), 0)
        self.assertTrue(called)

    def test_empty_filter_is_non_green(self) -> None:
        @framework.test("present", "smoke")
        def present(_ctx) -> None:
            pass

        self.assertEqual(self.run_suite(name_filter="missing"), 2)

    def test_failed_recording_case_cannot_feed_a_later_force_stop(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            adb = GuardAdb(
                Path(temp_dir),
                allow_destructive=True,
                descriptions={"Start recording"},
            )

            @framework.test("start_then_fail", "smoke")
            def start_then_fail(_ctx) -> None:
                adb.descriptions = {"Stop recording"}
                raise AssertionError("simulated failure after REC start")

            @framework.test("later_force_stop", "smoke", destructive=True)
            def later_force_stop(_ctx) -> None:
                adb.force_stop()

            exit_code = framework.run(
                adb,
                ["smoke"],
                None,
                Path(temp_dir) / "report",
                allow_destructive=True,
            )

        self.assertEqual(exit_code, 1)
        self.assertNotIn(f"am force-stop {APP_ID}", adb.commands)


if __name__ == "__main__":
    unittest.main()
