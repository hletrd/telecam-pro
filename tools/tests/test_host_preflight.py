"""Host-gate preflight (JDK 21 + keytool) and adb_proxy socket ownership."""

from __future__ import annotations

import importlib.util
import socket
import tempfile
import threading
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def load_tool(name: str):
    source = REPO_ROOT / "tools" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(f"telecam_{name}", source)
    if spec is None or spec.loader is None:
        raise AssertionError(f"could not load {source}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fake_jdk(root: Path, name: str, tools=("java", "keytool", "jarsigner")) -> Path:
    home = root / name
    (home / "bin").mkdir(parents=True)
    for tool in tools:
        (home / "bin" / tool).write_text("#!/bin/sh\n", encoding="utf-8")
    return home


class JavaHomePreflightTest(unittest.TestCase):
    def setUp(self) -> None:
        self.verify_host = load_tool("verify_host")

    def test_selects_first_complete_jdk_21(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old = fake_jdk(root, "jdk17")
            no_keytool = fake_jdk(root, "jdk21-jre", tools=("java", "jarsigner"))
            good = fake_jdk(root, "jdk21")
            versions = {old: 17, no_keytool: 21, good: 21}
            selected = self.verify_host.java_home(
                [old, no_keytool, good],
                version_of=lambda java: versions[java.parent.parent],
            )
            self.assertEqual(good, selected)

    def test_rejects_wrong_major_and_missing_keytool_with_clear_message(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old = fake_jdk(root, "jdk17")
            no_keytool = fake_jdk(root, "jdk21-jre", tools=("java", "jarsigner"))
            unknown = fake_jdk(root, "jdk-unknown")
            versions = {old: 17, unknown: None}
            with self.assertRaises(SystemExit) as raised:
                self.verify_host.java_home(
                    [old, no_keytool, unknown],
                    version_of=lambda java: versions[java.parent.parent],
                )
            message = str(raised.exception)
            self.assertIn("JDK 21 with java, keytool, jarsigner is required", message)
            self.assertIn("JDK 17", message)
            self.assertIn("missing keytool", message)
            self.assertIn("unknown version", message)

    def test_parses_modern_and_legacy_version_banners(self) -> None:
        cases = {
            'openjdk version "21.0.5" 2024-10-15': 21,
            'openjdk version "17" 2021-09-14': 17,
            'java version "1.8.0_402"': 8,
            "no banner": None,
        }
        for banner, expected in cases.items():
            with self.subTest(banner=banner), tempfile.TemporaryDirectory() as temp:
                java = Path(temp) / "java"
                java.write_text(f"#!/bin/sh\necho '{banner}' >&2\n", encoding="utf-8")
                java.chmod(0o755)
                self.assertEqual(expected, self.verify_host.java_major_version(java))


class AdbProxySocketOwnershipTest(unittest.TestCase):
    def test_both_sockets_close_once_both_pumps_finish(self) -> None:
        adb_proxy = load_tool("adb_proxy")
        client_side, client = socket.socketpair()
        upstream_side, upstream = socket.socketpair()
        connection = adb_proxy._Connection(client, upstream)
        pumps = [
            threading.Thread(target=adb_proxy.pump, args=(client, upstream, connection)),
            threading.Thread(target=adb_proxy.pump, args=(upstream, client, connection)),
        ]
        try:
            for thread in pumps:
                thread.start()
            client_side.sendall(b"ping")
            self.assertEqual(b"ping", upstream_side.recv(4))
            client_side.close()
            for thread in pumps:
                thread.join(5)
                self.assertFalse(thread.is_alive())
            self.assertEqual(-1, client.fileno())
            self.assertEqual(-1, upstream.fileno())
        finally:
            for s in (client_side, client, upstream_side, upstream):
                s.close()


if __name__ == "__main__":
    unittest.main()
