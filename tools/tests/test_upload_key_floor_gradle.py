"""SEC4-2 / AGG4-38: the Gradle release gate runs the SAME secret floor as the Python wrappers.

Every value here is synthetic. The Kotlin port in app/build.gradle.kts is executed through its
`verifyUploadKeySecretFloorFixture` seam and must return the Python verdict for every vector.
"""

from __future__ import annotations

import random
import string
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_release_source_gate import run  # noqa: E402

from tools import upload_key_policy as policy  # noqa: E402


NAMED_VECTORS = {
    # Weak shapes (SEC3-2 + SEC4-5), each must be refused by BOTH implementations.
    "123456": False,
    "0" * 20: False,
    "a" * 20: False,
    "abcdefghijklmnopqrst": False,
    "only-two-classes-long": False,
    " Strong-password-Value-7!": False,
    "Strong-password-Value-7! ": False,
    "Abcdefg-1234567-Value!": False,
    "a" * 18 + "A1": False,
    "Aa1" * 7: False,
    "Password1234Password": False,
    "Xq7-Xq7-Xq7-Xq7-Xq7-": False,
    "Zk4!mR9#vT2$wQ8%nnnnB": False,
    "Aa1-Bb2-Cc3-Aa1-Bb2-C": False,
    "Zyxwvu-Q7!kP3#mR9$tW2": False,  # descending walk of six
    # Accepted shapes.
    "Store-password-with-Entropy-7!": True,
    "Key-password-with-Entropy-8!": True,
    "Zk4!mR9#vT2$wQ8%nnnB": True,
    "Zyxwv-Q7!kP3#mR9$tW2u": True,  # a walk of five is allowed
    # MRG4-7: outside ASCII. Thirteen code points are too short even though they are 23 UTF-16
    # units (the old Kotlin port counted units and accepted this).
    "Ab1\U0001D400\U0001D401\U0001D402\U0001D403\U0001D404\U0001D405\U0001D406\U0001D407"
    "\U0001D408\U0001D409": False,
    "Zk4!mR9#vT2$wQ8%nnnB\U0001F600": True,
    # Superscripts are not ASCII digits on either side: lower + symbol is only two classes (the old
    # Python floor counted `²` as a digit and accepted it).
    "qwmzrtkpxvbn!\u00b2\u00b3\u00b9\u2074\u2075\u2076\u2077\u2078": False,
    # Python's isspace() edges, including the ones the JVM's trim() treats differently.
    "Zk4!mR9#vT2$wQ8%nnnB\u00a0": False,
    "\u0085Zk4!mR9#vT2$wQ8%nnnB": False,
    "Zk4!mR9#vT2$wQ8%nnnB\u3000": False,
    # Only ASCII letters walk: a Kelvin sign and `\u017f` (long s) casefold to k / s in Python.
    "Zq9!hij\u212alm-Q7#pX3$wR": True,
    "Zq9!pqr\u017ftu-Q7#kX3$wR": True,
}


def generated_vectors() -> list[str]:
    alphabet = string.ascii_letters + string.digits + "-_"
    generator = random.Random(20261002)
    values = ["".join(generator.choice(alphabet) for _ in range(43)) for _ in range(300)]
    # Short and structured shapes exercise every rule boundary, not only the happy path.
    mixed = string.ascii_letters + string.digits + "!#$%-_"
    for _ in range(300):
        length = generator.randint(18, 26)
        block = "".join(generator.choice(mixed) for _ in range(generator.randint(1, 13)))
        values.append((block * (length // len(block) + 1))[:length])
        values.append("".join(generator.choice(mixed[:generator.randint(10, len(mixed))])
                              for _ in range(length)))
    return values


class GradleSecretFloorParityTest(unittest.TestCase):
    def test_kotlin_floor_matches_python_floor_on_every_vector(self) -> None:
        vectors = [*NAMED_VECTORS, *generated_vectors()]
        for value, expected in NAMED_VECTORS.items():
            with self.subTest(value=value):
                self.assertEqual(expected, policy.meets_generated_secret_floor(value))
        with tempfile.TemporaryDirectory() as temp:
            vectors_path = Path(temp) / "vectors.txt"
            output = Path(temp) / "verdicts.txt"
            vectors_path.write_text("".join(f"{value}\n" for value in vectors), encoding="utf-8")
            result = run(
                [
                    "./gradlew",
                    "--console=plain",
                    ":app:verifyUploadKeySecretFloorFixture",
                    f"-PuploadKeyFloorFixtureVectors={vectors_path}",
                    f"-PuploadKeyFloorFixtureOutput={output}",
                ],
                REPO_ROOT,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            verdicts = output.read_text(encoding="utf-8").split("\n")[:-1]
        self.assertEqual(len(vectors), len(verdicts))
        mismatches = [
            (index, verdict)
            for index, (value, verdict) in enumerate(zip(vectors, verdicts))
            if verdict != ("accept" if policy.meets_generated_secret_floor(value) else "reject")
        ]
        self.assertEqual([], mismatches[:10])
        # The parity check is only meaningful if both verdicts actually occur.
        self.assertIn("accept", verdicts)
        self.assertIn("reject", verdicts)


if __name__ == "__main__":
    unittest.main()
