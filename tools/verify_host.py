#!/usr/bin/env python3
"""Run every non-device repository quality gate from one authoritative command."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
PROJECT_JAVA_HOME = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")


def java_home() -> Path:
    candidates = []
    configured = os.environ.get("JAVA_HOME")
    if configured:
        candidates.append(Path(configured))
    candidates.append(PROJECT_JAVA_HOME)
    javac = shutil.which("javac")
    if javac:
        candidates.append(Path(javac).resolve().parent.parent)
    for candidate in candidates:
        if (candidate / "bin/java").is_file() and (candidate / "bin/jarsigner").is_file():
            return candidate
    raise SystemExit("JDK 21 with java, keytool, and jarsigner is required")


def run(command: list[str], env: dict[str, str]) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=ROOT, env=env, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--release",
        action="store_true",
        help="also run signed release lint/APK/AAB gates (requires a clean committed tree)",
    )
    args = parser.parse_args()

    home = java_home()
    env = {
        **os.environ,
        "JAVA_HOME": str(home),
        "PATH": str(home / "bin") + os.pathsep + os.environ.get("PATH", ""),
    }
    run(
        [
            "./gradlew",
            ":app:assembleDebug",
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
    run(["git", "diff", "--check"], env)
    if args.release:
        run(
            [
                "./gradlew",
                ":app:lintRelease",
                ":app:assembleRelease",
                ":app:bundleRelease",
            ],
            env,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
