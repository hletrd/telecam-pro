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


def class_xml(
    name: str,
    missed: int,
    covered: int,
    methods: str = "",
    source_filename: str = "Pure.kt",
) -> str:
    return (
        f'<class name="{name}" sourcefilename="{source_filename}">'
        f'{methods}{counter(missed, covered)}</class>'
    )


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
        residuals: str = "# no reviewed residuals\n",
    ) -> tuple[int, str, str]:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            report_path = root / "report.xml"
            partition_path = root / "partition-b.txt"
            excluded_path = root / "partition-excluded.txt"
            residuals_path = root / "partition-a-residuals.txt"
            report_path.write_text(report, encoding="utf-8")
            partition_path.write_text(partition, encoding="utf-8")
            excluded_path.write_text(excluded, encoding="utf-8")
            residuals_path.write_text(residuals, encoding="utf-8")
            for raw in residuals.splitlines():
                if not raw.strip() or raw.lstrip().startswith("#"):
                    continue
                fields = raw.split("\t")
                if len(fields) != 4 or ":" not in fields[2]:
                    continue
                source_text, line_text = fields[2].rsplit(":", 1)
                if not source_text.endswith(("/Pure.kt", "/Other.kt")):
                    continue
                numbers = [
                    int(value)
                    for item in line_text.split(",")
                    for value in item.split("-")
                    if value.isdigit()
                ]
                source = root / source_text
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("line\n" * max(numbers, default=1), encoding="utf-8")

            stdout = io.StringIO()
            stderr = io.StringIO()
            exit_code = 0
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                try:
                    partition_report.main([
                        str(report_path),
                        "--partition", str(partition_path),
                        "--excluded", str(excluded_path),
                        "--residuals", str(residuals_path),
                        "--source-root", str(root),
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

    def residual(self, class_name: str, missed: int = 2) -> str:
        return (
            f"{class_name}\t{missed}\tapp/src/main/kotlin/Pure.kt:1-{missed}"
            "\tproven-unreachable: fixture branch is structurally unreachable\n"
        )

    def residual_report(self, missed: int = 2) -> str:
        return report_xml(
            class_xml("pkg/Device", 1, 0),
            class_xml("pkg/Excluded", 0, 1),
            class_xml("pkg/Pure", missed, 8),
        )

    def test_exact_reviewed_residual_manifest_passes(self) -> None:
        code, stdout, stderr = self.run_report(
            self.residual_report(),
            "pkg/Device\n",
            "pkg/Excluded\n",
            self.residual("pkg/Pure"),
        )

        self.assertEqual(0, code, stderr)
        self.assertIn("REVIEWED A RESIDUALS: 2 lines across 1 classes", stdout)

    def test_unexpected_resolved_and_count_drifted_residuals_are_fatal(self) -> None:
        cases = {
            "unexpected residual": (self.residual_report(), "# empty\n"),
            "resolved/stale residual": (
                self.residual_report(missed=0),
                self.residual("pkg/Pure"),
            ),
            "residual count drift": (
                self.residual_report(missed=2),
                self.residual("pkg/Pure", missed=1),
            ),
            "residual source drift": (
                self.residual_report(missed=2),
                self.residual("pkg/Pure").replace("Pure.kt", "Other.kt"),
            ),
        }
        for message, (report, residuals) in cases.items():
            with self.subTest(message=message):
                code, _, stderr = self.run_report(
                    report,
                    "pkg/Device\n",
                    "pkg/Excluded\n",
                    residuals,
                )
                self.assertEqual(1, code)
                self.assertIn(message, stderr)

    def test_malformed_duplicate_unsorted_and_unjustified_residuals_are_fatal(self) -> None:
        valid = self.residual("pkg/Pure")
        cases = {
            "expected 4 tab-separated fields": "pkg/Pure\t2\n",
            "missed count must be a positive integer": valid.replace("\t2\t", "\tzero\t"),
            "invalid source region": valid.replace("Pure.kt:1-2", "Pure.kt"),
            "source file does not exist": valid.replace("Pure.kt", "Missing.kt"),
            "reason must have a concrete": valid.replace(
                "proven-unreachable: fixture branch is structurally unreachable",
                "later: no",
            ),
            "duplicate class": valid + valid,
            "classes must be strictly sorted": self.residual("pkg/Zed") + valid,
        }
        for message, residuals in cases.items():
            with self.subTest(message=message):
                code, _, stderr = self.run_report(
                    self.residual_report(),
                    "pkg/Device\n",
                    "pkg/Excluded\n",
                    residuals,
                )
                self.assertEqual(1, code)
                self.assertIn(message, stderr)

    def test_missing_residual_manifest_is_fatal(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit):
                    partition_report.Residuals(
                        Path(td) / "missing.txt",
                        Path(td),
                    )
        self.assertIn("could not read Partition-A residual manifest", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
