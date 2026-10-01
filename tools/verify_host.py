#!/usr/bin/env python3
"""Run every non-device repository quality gate from one authoritative command."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

_TOOLS_DIR = Path(__file__).resolve().parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))
from android_sdk import android_sdk_environment


ROOT = Path(__file__).resolve().parent.parent
PROJECT_JAVA_HOME = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")


REQUIRED_JDK_MAJOR = 21
REQUIRED_JDK_TOOLS = ("java", "keytool", "jarsigner")


def java_major_version(java: Path) -> int | None:
    """The feature release of `java -version`, or None when it cannot be determined."""
    try:
        result = subprocess.run(
            [str(java), "-version"],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    match = re.search(r'version "(\d+)(?:\.(\d+))?', result.stderr + result.stdout)
    if match is None:
        return None
    major = int(match.group(1))
    # Pre-9 JDKs report "1.8.0_x"; their feature release is the second component.
    if major == 1 and match.group(2) is not None:
        major = int(match.group(2))
    return major


def java_home_candidates() -> list[Path]:
    candidates = []
    configured = os.environ.get("JAVA_HOME")
    if configured:
        candidates.append(Path(configured))
    candidates.append(PROJECT_JAVA_HOME)
    javac = shutil.which("javac")
    if javac:
        candidates.append(Path(javac).resolve().parent.parent)
    return candidates


def java_home(
    candidates: list[Path] | None = None,
    version_of=java_major_version,
) -> Path:
    """First candidate JDK that carries every required tool AND is feature release 21.

    Gradle's toolchain and the release signing/verification wrappers all assume JDK 21 with
    keytool and jarsigner; a JDK 17 (or a JRE) on PATH would otherwise pass this preflight and
    fail much later inside Gradle or the signing step.
    """
    rejected: list[str] = []
    for candidate in java_home_candidates() if candidates is None else candidates:
        missing = [tool for tool in REQUIRED_JDK_TOOLS if not (candidate / "bin" / tool).is_file()]
        if missing:
            rejected.append(f"{candidate}: missing {', '.join(missing)}")
            continue
        major = version_of(candidate / "bin/java")
        if major != REQUIRED_JDK_MAJOR:
            found = "unknown version" if major is None else f"JDK {major}"
            rejected.append(f"{candidate}: {found}")
            continue
        return candidate
    detail = "; ".join(rejected) if rejected else "no JDK candidates found"
    raise SystemExit(
        f"JDK {REQUIRED_JDK_MAJOR} with {', '.join(REQUIRED_JDK_TOOLS)} is required "
        f"(set JAVA_HOME; rejected: {detail})"
    )


def run(command: list[str], env: dict[str, str]) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=ROOT, env=env, check=True)


def repository_diff_check_command() -> list[str]:
    """Check the complete HEAD patch, including index and unstaged worktree changes."""
    return ["git", "diff", "--check", "HEAD", "--"]


def main() -> int:
    if sys.flags.optimize != 0:
        print("optimized Python is unsupported for the host verification gate", file=sys.stderr)
        return 2

    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--release",
        action="store_true",
        help="also run signed release lint/APK/AAB gates (requires a clean committed tree)",
    )
    args = parser.parse_args()

    home = java_home()
    try:
        sdk_environment = android_sdk_environment(ROOT)
    except (OSError, RuntimeError) as error:
        raise SystemExit(f"Android SDK preflight failed: {error}") from error
    env = {
        **os.environ,
        **sdk_environment,
        "JAVA_HOME": str(home),
        "PATH": str(home / "bin") + os.pathsep + os.environ.get("PATH", ""),
    }
    run(
        [
            "./gradlew",
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":app:testDebugUnitTest",
            ":app:lintDebug",
            ":app:verifyPartitionACoverage",
        ],
        env,
    )
    for suite in ("tools/tests", "tools/coverage/tests", "device-tests/tests"):
        run([sys.executable, "-m", "unittest", "discover", "-s", suite, "-v"], env)
    run([sys.executable, "tools/check_docs.py"], env)
    run([sys.executable, "-m", "compileall", "-q", "device-tests", "tools"], env)
    run(repository_diff_check_command(), env)
    if args.release:
        run(
            [
                sys.executable,
                "tools/build_immutable_release.py",
                ":app:lintRelease",
                ":app:assembleRelease",
                ":app:bundleRelease",
            ],
            env,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
