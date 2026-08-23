#!/usr/bin/env python3
"""Build release tasks from an exact, private export of the current Git commit."""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import secrets
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Callable, Sequence


Run = Callable[[Sequence[str], pathlib.Path], subprocess.CompletedProcess[str]]
AfterSnapshot = Callable[[pathlib.Path, pathlib.Path], None]


def run_checked(command: Sequence[str], cwd: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, text=True, check=True)


def git_value(root: pathlib.Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


def require_clean_commit(root: pathlib.Path) -> tuple[str, str]:
    status = subprocess.run(
        [
            "git",
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
            "--ignore-submodules=none",
        ],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    if status:
        raise RuntimeError(f"release source is not clean:\n{status.rstrip()}")
    commit = git_value(root, "rev-parse", "HEAD")
    tree = git_value(root, "rev-parse", "HEAD^{tree}")
    if len(commit) != 40 or len(tree) != 40:
        raise RuntimeError("Git did not return canonical release commit/tree identities")
    return commit, tree


def export_commit(root: pathlib.Path, destination: pathlib.Path, commit: str) -> dict[str, str]:
    subprocess.run(
        ["git", "clone", "--quiet", "--shared", "--no-checkout", str(root), str(destination)],
        cwd=destination.parent,
        check=True,
    )
    subprocess.run(
        ["git", "checkout", "--quiet", "--detach", commit],
        cwd=destination,
        check=True,
    )
    tracked = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=destination,
        capture_output=True,
        check=True,
    ).stdout.split(b"\0")
    return {
        relative.decode("utf-8"): hashlib.sha256(
            destination.joinpath(relative.decode("utf-8")).read_bytes()
        ).hexdigest()
        for relative in tracked
        if relative
    }


def copy_local_build_inputs(root: pathlib.Path, snapshot: pathlib.Path) -> None:
    """Copy only machine/signing configuration; application inputs stay Git-exported."""
    for name in ("local.properties", "keystore.properties"):
        source = root / name
        if source.is_file():
            shutil.copy2(source, snapshot / name)
    signing_properties = root / "keystore.properties"
    if signing_properties.is_file():
        store_file = next(
            (
                line.partition("=")[2].strip()
                for line in signing_properties.read_text(encoding="utf-8").splitlines()
                if line.partition("=")[0].strip() == "storeFile"
            ),
            "",
        )
        configured_store = pathlib.Path(store_file)
        if store_file and not configured_store.is_absolute():
            source_store = (root / configured_store).resolve()
            try:
                relative_store = source_store.relative_to(root)
            except ValueError as error:
                raise RuntimeError("relative release keystore escapes the repository") from error
            if source_store.is_file():
                destination_store = snapshot / relative_store
                destination_store.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source_store, destination_store)


def verify_export(snapshot: pathlib.Path, expected: dict[str, str]) -> None:
    changed = [
        relative
        for relative, digest in expected.items()
        if not (snapshot / relative).is_file()
        or hashlib.sha256((snapshot / relative).read_bytes()).hexdigest() != digest
    ]
    if changed:
        raise RuntimeError("immutable release snapshot changed during build: " + ", ".join(changed))
    status = subprocess.run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        cwd=snapshot,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    ignored_sources = subprocess.run(
        [
            "git", "ls-files", "--others", "--ignored", "--exclude-standard", "--",
            "app/src/main", "app/src/release",
        ],
        cwd=snapshot,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    if status or ignored_sources:
        details = "\n".join(part.rstrip() for part in (status, ignored_sources) if part)
        raise RuntimeError(f"release build created or changed source inputs:\n{details}")


def build_immutable_release(
    root: pathlib.Path,
    tasks: Sequence[str],
    output_root: pathlib.Path,
    *,
    run: Run = run_checked,
    after_snapshot: AfterSnapshot | None = None,
) -> tuple[str, str]:
    root = root.resolve()
    output_root = output_root.resolve()
    commit, tree = require_clean_commit(root)
    if output_root.exists():
        raise RuntimeError(f"refusing to overwrite immutable release output: {output_root}")

    with tempfile.TemporaryDirectory(prefix="telecam-release-source-") as temp_dir:
        snapshot = pathlib.Path(temp_dir) / "source"
        expected = export_commit(root, snapshot, commit)
        copy_local_build_inputs(root, snapshot)
        if after_snapshot is not None:
            after_snapshot(root, snapshot)
        command = [
            "./gradlew",
            *tasks,
            f"-PimmutableReleaseCommit={commit}",
            f"-PimmutableReleaseTree={tree}",
        ]
        run(command, snapshot)
        verify_export(snapshot, expected)

        built_outputs = snapshot / "app/build/outputs"
        if built_outputs.is_dir():
            output_root.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(built_outputs, output_root)
    return commit, tree


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parent.parent,
    )
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument(
        "tasks",
        nargs="*",
        default=[":app:lintRelease", ":app:assembleRelease", ":app:bundleRelease"],
    )
    args = parser.parse_args()
    if args.output is None:
        commit = git_value(args.root.resolve(), "rev-parse", "HEAD")
        output = args.root / "app/build/immutable-release" / (
            f"{commit[:12]}-{secrets.token_hex(4)}"
        )
    else:
        output = args.output
    try:
        commit, tree = build_immutable_release(args.root, args.tasks, output)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"immutable release build failed: {error}", file=sys.stderr)
        return 1
    print(f"immutable release build complete: commit={commit} tree={tree} outputs={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
