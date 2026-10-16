"""Shared setup for the throwaway git repositories the tool tests build.

git (2.54 here) may start a DETACHED auto-maintenance/auto-gc process after a commit that adds
many objects. That background repack keeps writing pack, multi-pack-index and bitmap files under
`.git` after `git commit` has returned, so a `TemporaryDirectory` cleanup racing it fails with
`ENOTEMPTY` and the host gate aborts (RPL cycle 3, QA3-1). Fixture repositories exist only for the
duration of one test, so they never need maintenance: disable both triggers at init.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

FIXTURE_REPO_CONFIG = (
    ("gc.auto", "0"),
    ("maintenance.auto", "false"),
)


def init_fixture_repo(root: Path, branch: str | None = "main") -> None:
    command = ["git", "init", "-q"]
    if branch is not None:
        command += ["-b", branch]
    subprocess.run(command, cwd=root, check=True, capture_output=True)
    for key, value in FIXTURE_REPO_CONFIG:
        subprocess.run(["git", "config", key, value], cwd=root, check=True, capture_output=True)
