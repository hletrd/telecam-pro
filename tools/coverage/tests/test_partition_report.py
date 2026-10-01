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


def source_xml(name: str, missed_lines: set[int], last_line: int) -> str:
    rows = "".join(
        f'<line nr="{line}" mi="2" ci="0" mb="0" cb="0"/>'
        if line in missed_lines
        else f'<line nr="{line}" mi="0" ci="2" mb="0" cb="0"/>'
        for line in range(1, last_line + 1)
    )
    return f'<sourcefile name="{name}">{rows}</sourcefile>'


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
                if len(fields) != 5 or ":" not in fields[2]:
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
        # run_report writes every fixture source as identical `line` rows, so any region of the same
        # size has this fingerprint.
        fingerprint = partition_report.region_fingerprint("line\n" * missed, set(range(1, missed + 1)))
        return (
            f"{class_name}\t{missed}\tapp/src/main/kotlin/Pure.kt:1-{missed}\t{fingerprint}"
            "\tproven-unreachable: fixture branch is structurally unreachable\n"
        )

    def residual_report(self, missed: int = 2, missed_lines: tuple[int, ...] | None = None) -> str:
        # pkg/Pure's one method starts at line 1, so it owns every Pure.kt line; by default the
        # missed lines are 1..missed and lines up to 10 are covered (JaCoCo `<line nr mi ci>`).
        lines = set(range(1, missed + 1)) if missed_lines is None else set(missed_lines)
        return report_xml(
            class_xml("pkg/Device", 1, 0),
            class_xml("pkg/Excluded", 0, 1),
            class_xml(
                "pkg/Pure",
                missed,
                8,
                '<method name="pure" desc="()V" line="1">'
                f"{counter(missed, 8)}</method>",
            ),
            source_xml("Pure.kt", lines, 10),
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

    def test_a_miss_outside_its_cited_region_is_residual_line_drift(self) -> None:
        # TE3-1: the reviewed miss at line 2 became covered and an unreviewed one appeared at line 7
        # in the SAME class. The count still matches; only line identity catches it.
        code, _, stderr = self.run_report(
            self.residual_report(missed=2, missed_lines=(1, 7)),
            "pkg/Device\n",
            "pkg/Excluded\n",
            self.residual("pkg/Pure"),
        )

        self.assertEqual(1, code)
        self.assertIn("residual line drift pkg/Pure: missed line(s) 7 outside cited region", stderr)
        self.assertNotIn("residual count drift", stderr)

    def test_a_region_citing_covered_code_is_residual_line_drift(self) -> None:
        # The stale-region shape REG3-2 found: the region points at lines that are not missed at all.
        code, _, stderr = self.run_report(
            report_xml(
                class_xml("pkg/Device", 1, 0),
                class_xml("pkg/Excluded", 0, 1),
                class_xml("pkg/Pure", 2, 8),
                source_xml("Pure.kt", {1, 2}, 10),
            ),
            "pkg/Device\n",
            "pkg/Excluded\n",
            self.residual("pkg/Pure").replace("Pure.kt:1-2", "Pure.kt:9-10"),
        )

        self.assertEqual(1, code)
        self.assertIn("cited region app/src/main/kotlin/Pure.kt:9-10 holds 0 missed line(s)", stderr)

    def test_lines_are_attributed_to_the_class_whose_method_starts_nearest_above(self) -> None:
        # Two classes share Pure.kt: pkg/Pure's method starts at 1, a B-partition lambda class at 5.
        # Line 6 belongs to the lambda, so pkg/Pure's single residual at line 2 stays inside 1-2.
        report = report_xml(
            class_xml("pkg/Excluded", 0, 1),
            class_xml(
                "pkg/Pure",
                1,
                3,
                f'<method name="pure" desc="()V" line="1">{counter(1, 3)}</method>',
            ),
            class_xml(
                "pkg/Device",
                1,
                0,
                f'<method name="invoke" desc="()V" line="5">{counter(1, 0)}</method>',
            ),
            source_xml("Pure.kt", {2, 6}, 6),
        )
        code, stdout, stderr = self.run_report(
            report,
            "pkg/Device\n",
            "pkg/Excluded\n",
            self.residual("pkg/Pure", missed=1).replace("Pure.kt:1-1", "Pure.kt:2"),
        )

        self.assertEqual(0, code, stderr)
        self.assertIn("lines inside cited regions", stdout)

    PURE_SOURCE = "".join(f"statement{number}()\n" for number in range(1, 11))
    RATIONALE = "proven-unreachable: fixture branch is structurally unreachable"

    def regenerate(
        self, pure_source: str, manifest: str, pure_missed: set[int], fresh: bool = False,
    ) -> str:
        classes = [
            class_xml("pkg/Device", 1, 0),
            class_xml("pkg/Excluded", 0, 1),
            class_xml(
                "pkg/Pure",
                len(pure_missed),
                8,
                f'<method name="pure" desc="()V" line="1">{counter(len(pure_missed), 8)}</method>',
            ),
            source_xml("Pure.kt", pure_missed, len(pure_source.splitlines())),
        ]
        if fresh:
            classes += [
                class_xml(
                    "pkg/Fresh",
                    1,
                    1,
                    f'<method name="fresh" desc="()V" line="1">{counter(1, 1)}</method>',
                    source_filename="Fresh.kt",
                ),
                source_xml("Fresh.kt", {2}, 2),
            ]
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "app/src/main/kotlin/pkg").mkdir(parents=True)
            (root / "app/src/main/kotlin/pkg/Pure.kt").write_text(pure_source, encoding="utf-8")
            (root / "app/src/main/kotlin/pkg/Fresh.kt").write_text("line\n" * 2, encoding="utf-8")
            paths = {}
            for name, text in (
                ("report.xml", report_xml(*classes)),
                ("partition-b.txt", "pkg/Device\n"),
                ("partition-excluded.txt", "pkg/Excluded\n"),
                ("partition-a-residuals.txt", manifest),
            ):
                paths[name] = root / name
                paths[name].write_text(text, encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                partition_report.main([
                    str(paths["report.xml"]),
                    "--partition", str(paths["partition-b.txt"]),
                    "--excluded", str(paths["partition-excluded.txt"]),
                    "--residuals", str(paths["partition-a-residuals.txt"]),
                    "--source-root", str(root),
                    "--write-regions",
                ])
            return paths["partition-a-residuals.txt"].read_text(encoding="utf-8")

    def reviewed_row(self, source: str, lines: set[int]) -> str:
        fingerprint = partition_report.region_fingerprint(source, lines)
        region = partition_report.compress_lines(lines)
        return (
            f"pkg/Pure\t{len(lines)}\tapp/src/main/kotlin/pkg/Pure.kt:{region}\t{fingerprint}"
            f"\t{self.RATIONALE}\n"
        )

    def test_write_regions_carries_a_rationale_across_a_pure_line_shift(self) -> None:
        # Two lines were inserted above the reviewed guards: same text, new numbers.
        reviewed = self.reviewed_row(self.PURE_SOURCE, {4, 8})
        shifted = "// a\n// b\n" + self.PURE_SOURCE
        written = self.regenerate(shifted, reviewed, {6, 10}, fresh=True)

        self.assertTrue(written.startswith(partition_report.MANIFEST_HEADER))
        fingerprint = partition_report.region_fingerprint(shifted, {6, 10})
        self.assertEqual(fingerprint, partition_report.region_fingerprint(self.PURE_SOURCE, {4, 8}))
        self.assertIn(
            f"pkg/Pure\t2\tapp/src/main/kotlin/pkg/Pure.kt:6,10\t{fingerprint}\t{self.RATIONALE}\n",
            written,
        )
        # A new residual is never silently "reviewed": its placeholder fails the manifest loader.
        self.assertIn("pkg/Fresh\t1\tapp/src/main/kotlin/pkg/Fresh.kt:2\tsha256:", written)
        self.assertIn(f"\t{partition_report.UNREVIEWED_REASON}\n", written)
        self.assertFalse(partition_report.UNREVIEWED_REASON.startswith(partition_report.Residuals.REASON_PREFIXES))

    def test_write_regions_does_not_launder_a_same_class_substitution(self) -> None:
        # TE4-5 / REG4-8: the reviewed guard at line 8 became covered and an unreviewed miss appeared
        # at line 9 in the SAME class with the SAME count. Carrying the rationale by class name
        # re-explained line 9 with line 8's argument; the fingerprint refuses it.
        reviewed = self.reviewed_row(self.PURE_SOURCE, {4, 8})
        written = self.regenerate(self.PURE_SOURCE, reviewed, {4, 9})

        self.assertIn("pkg/Pure\t2\tapp/src/main/kotlin/pkg/Pure.kt:4,9\tsha256:", written)
        self.assertNotIn(self.RATIONALE, written)
        self.assertIn(partition_report.UNREVIEWED_REASON, written)

    def test_write_regions_does_not_carry_a_rationale_over_edited_cited_text(self) -> None:
        reviewed = self.reviewed_row(self.PURE_SOURCE, {4, 8})
        edited = self.PURE_SOURCE.replace("statement8()", "differentGuard()")
        written = self.regenerate(edited, reviewed, {4, 8})
        self.assertNotIn(self.RATIONALE, written)
        self.assertIn(partition_report.UNREVIEWED_REASON, written)

    def test_legacy_rows_without_a_fingerprint_are_never_carried(self) -> None:
        legacy = f"pkg/Pure\t2\tapp/src/main/kotlin/pkg/Pure.kt:4,8\t{self.RATIONALE}\n"
        written = self.regenerate(self.PURE_SOURCE, legacy, {4, 8})
        self.assertNotIn(self.RATIONALE, written)

    def test_gate_rejects_cited_text_that_changed_under_its_fingerprint(self) -> None:
        # Lines kept their numbers but their CONTENT changed since review: the gate itself re-hashes.
        stale = self.residual("pkg/Pure").replace(
            partition_report.region_fingerprint("line\nline", {1, 2}), "sha256:0000000000000000"
        )
        code, _, stderr = self.run_report(
            self.residual_report(), "pkg/Device\n", "pkg/Excluded\n", stale,
        )
        self.assertEqual(1, code)
        self.assertIn("cited-text fingerprint drift for pkg/Pure", stderr)

    def test_region_fingerprint_ignores_whitespace_and_tracks_content(self) -> None:
        self.assertEqual(
            partition_report.region_fingerprint("a\n  b  c\n", {2}),
            partition_report.region_fingerprint("b c\n", {1}),
        )
        self.assertNotEqual(
            partition_report.region_fingerprint("a\nb\n", {2}),
            partition_report.region_fingerprint("a\nc\n", {2}),
        )

    def test_committed_manifest_fingerprints_match_current_sources(self) -> None:
        manifest = partition_report.Residuals(
            TOOLS_DIR / "partition-a-residuals.txt", TOOLS_DIR.parent.parent,
        )
        self.assertTrue(manifest.entries)

    def test_committed_manifest_documents_the_regeneration_command(self) -> None:
        text = (TOOLS_DIR / "partition-a-residuals.txt").read_text(encoding="utf-8")
        self.assertTrue(text.startswith(partition_report.MANIFEST_HEADER))
        self.assertIn("--write-regions", text)

    def test_compress_lines(self) -> None:
        self.assertEqual("1,3-5,9", partition_report.compress_lines({9, 4, 1, 3, 5}))
        self.assertEqual("", partition_report.compress_lines(set()))

    def test_malformed_duplicate_unsorted_and_unjustified_residuals_are_fatal(self) -> None:
        valid = self.residual("pkg/Pure")
        cases = {
            "expected 5 tab-separated fields": "pkg/Pure\t2\n",
            "invalid cited-text fingerprint": valid.replace("\tsha256:", "\tmd5:"),
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
