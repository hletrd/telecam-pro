from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOLS_DIR.parents[1]
sys.path.insert(0, str(TOOLS_DIR))

import partition_report  # noqa: E402


def counter(missed: int, covered: int) -> str:
    return f'<counter type="LINE" missed="{missed}" covered="{covered}"/>'


def report_xml(*classes: str) -> str:
    return "<report><package name=\"pkg\">" + "".join(classes) + "</package></report>"


def class_xml(name: str, missed: int, covered: int, methods: str = "") -> str:
    return f'<class name="{name}">{methods}{counter(missed, covered)}</class>'


def method_xml(name: str, descriptor: str, missed: int, covered: int) -> str:
    return (
        f'<method name="{name}" desc="{descriptor}">'
        f'{counter(missed, covered)}</method>'
    )


class PartitionReportTest(unittest.TestCase):
    def run_report(
        self,
        report: str,
        partition: str,
        excluded: str,
    ) -> tuple[int, str, str]:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            report_path = root / "report.xml"
            partition_path = root / "partition-b.txt"
            excluded_path = root / "partition-excluded.txt"
            report_path.write_text(report, encoding="utf-8")
            partition_path.write_text(partition, encoding="utf-8")
            excluded_path.write_text(excluded, encoding="utf-8")

            stdout = io.StringIO()
            stderr = io.StringIO()
            exit_code = 0
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                try:
                    partition_report.main([
                        str(report_path),
                        "--partition", str(partition_path),
                        "--excluded", str(excluded_path),
                    ])
                except SystemExit as error:
                    exit_code = int(error.code)
            return exit_code, stdout.getvalue(), stderr.getvalue()

    def complete_report(self, device_descriptor: str = "()V") -> str:
        mixed_methods = (
            method_xml("device", device_descriptor, 1, 0)
            + method_xml("pure", "()V", 0, 1)
        )
        return report_xml(
            class_xml("pkg/Mixed", 1, 1, mixed_methods),
            class_xml("pkg/Excluded", 0, 1),
        )

    def test_method_rule_is_rejected_instead_of_subtracting_overlapping_lines(self) -> None:
        code, stdout, stderr = self.run_report(
            self.complete_report(),
            "pkg/Mixed#device\n",
            "pkg/Excluded\n",
        )

        self.assertEqual(1, code)
        self.assertEqual("", stdout)
        self.assertIn("method-level coverage partition is mathematically invalid", stderr)

    def test_class_partition_uses_the_class_unique_line_union(self) -> None:
        # Method counters deliberately exceed the class union, reproducing the Kotlin bridge/lambda
        # overlap that previously printed 196 outcomes for a 190-line class.
        overlapping = class_xml(
            "pkg/Mixed",
            14,
            176,
            method_xml("device", "()V", 20, 15),
        )
        code, stdout, stderr = self.run_report(
            report_xml(overlapping, class_xml("pkg/Pure", 0, 1), class_xml("pkg/Excluded", 0, 1)),
            "pkg/Mixed\n",
            "pkg/Excluded\n",
        )

        self.assertEqual(0, code, stderr)
        self.assertIn("PARTITION B : 176/190", stdout)
        self.assertIn("OVERALL     : 178/192", stdout)

    def test_stale_exact_and_glob_rules_are_fatal(self) -> None:
        code, _, stderr = self.run_report(
            self.complete_report(),
            "pkg/Mixed\npkg/Missing\npkg/Missing$*\n",
            "pkg/Excluded\n",
        )

        self.assertEqual(1, code)
        self.assertIn("FAIL: partition patterns matching nothing", stderr)
        self.assertIn("pkg/Missing", stderr)
        self.assertIn("pkg/Missing$*", stderr)

    def test_each_expected_empty_bucket_is_fatal_even_when_its_rules_match(self) -> None:
        cases = {
            "Partition A": report_xml(
                class_xml("pkg/Device", 0, 1),
                class_xml("pkg/Excluded", 0, 1),
            ),
            "Partition B": report_xml(
                class_xml("pkg/Pure", 0, 1),
                class_xml("pkg/Device", 0, 0),
                class_xml("pkg/Excluded", 0, 1),
            ),
            "Excluded": report_xml(
                class_xml("pkg/Pure", 0, 1),
                class_xml("pkg/Device", 0, 1),
                class_xml("pkg/Excluded", 0, 0),
            ),
        }

        for bucket, report in cases.items():
            with self.subTest(bucket=bucket):
                code, _, stderr = self.run_report(
                    report,
                    "pkg/Device\n",
                    "pkg/Excluded\n",
                )
                self.assertEqual(1, code)
                self.assertIn("expected non-empty coverage bucket(s)", stderr)
                self.assertIn(bucket, stderr)

    def test_committed_filters_use_the_current_namespace(self) -> None:
        for name in ("partition-b.txt", "partition-excluded.txt"):
            text = (TOOLS_DIR / name).read_text(encoding="utf-8")
            self.assertNotIn("me/hletrd/findx9tele", text)
            self.assertIn("me/hletrd/telecampro", text)


if __name__ == "__main__":
    unittest.main()
