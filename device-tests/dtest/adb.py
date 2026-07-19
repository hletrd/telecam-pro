"""adb transport + UI-tree helpers.

Every interaction is per-step verified (dump/log/screencap after acting) because the
wireless-debugging proxy can silently drop an injected tap mid-sequence — a lesson from
the review-plan-fix cycles. Keep helpers synchronous and chatty-on-failure.
"""

from __future__ import annotations

import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

APP_ID = "me.hletrd.telecampro.debug"
MAIN_ACTIVITY = f"{APP_ID}/com.hletrd.findx9tele.MainActivity"
MEDIA_DIR = "/sdcard/DCIM/X9Tele"

# Error signatures worth failing a test over. CAMERA_DISCONNECTED during an intentional
# force-stop is expected noise, so reliability tests scan scoped windows, not whole runs.
LOGCAT_FATAL = re.compile(r"FATAL EXCEPTION|ANR in|CAMERA_ERROR|Fatal signal")
# Known-benign device facts that match the pattern above but must NOT fail a test:
# the QTI HAL's photo↔video teardown emits a framework-level -38 "Error clearing streaming
# request" that the app contains (closed-gate StateCallback, cycle 3) — it never reaches the UI.
LOGCAT_BENIGN = re.compile(r"Error clearing streaming request: Function not implemented \(-38\)")


class AdbError(RuntimeError):
    pass


@dataclass
class UiNode:
    text: str
    desc: str
    bounds: tuple[int, int, int, int]  # l, t, r, b
    selected: bool
    enabled: bool

    @property
    def center(self) -> tuple[int, int]:
        l, t, r, b = self.bounds
        return (l + r) // 2, (t + b) // 2


class Adb:
    def __init__(self, serial: str, workdir: Path):
        self.serial = serial
        self.workdir = workdir
        workdir.mkdir(parents=True, exist_ok=True)

    # -- transport ---------------------------------------------------------

    def _run(self, *args: str, binary: bool = False, timeout: int = 60) -> bytes | str:
        cmd = ["adb", "-s", self.serial, *args]
        try:
            out = subprocess.run(cmd, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as e:
            raise AdbError(f"adb timeout: {' '.join(args)}") from e
        if out.returncode != 0:
            err = out.stderr.decode(errors="replace").strip()
            # One reconnect attempt: the loopback proxy drops with "protocol fault"
            # and a plain re-connect recovers it every time (never kill-server).
            if "protocol fault" in err or "device offline" in err or "not found" in err:
                subprocess.run(["adb", "connect", self.serial], capture_output=True, timeout=15)
                time.sleep(2)
                out = subprocess.run(cmd, capture_output=True, timeout=timeout)
            if out.returncode != 0:
                raise AdbError(f"adb {' '.join(args)} failed: {err[:300]}")
        return out.stdout if binary else out.stdout.decode(errors="replace")

    def shell(self, cmd: str, timeout: int = 60) -> str:
        return self._run("shell", cmd, timeout=timeout).strip()

    def exec_out(self, cmd: str, timeout: int = 60) -> bytes:
        return self._run("exec-out", cmd, binary=True, timeout=timeout)

    def pull(self, remote: str, local: Path) -> Path:
        local.parent.mkdir(parents=True, exist_ok=True)
        self._run("pull", remote, str(local), timeout=180)
        return local

    # -- app lifecycle -----------------------------------------------------

    def pid(self) -> int | None:
        out = self.shell(f"pidof {APP_ID} || true")
        return int(out.split()[0]) if out and out.split()[0].isdigit() else None

    def launch(self, wait_s: float = 5.0) -> int:
        self.shell("input keyevent KEYCODE_WAKEUP")
        self.shell(f"am start -n {MAIN_ACTIVITY}")
        deadline = time.time() + wait_s + 10
        while time.time() < deadline:
            p = self.pid()
            if p:
                time.sleep(wait_s)  # let the camera session configure
                return p
            time.sleep(0.5)
        raise AdbError("app did not start")

    def force_stop(self) -> None:
        self.shell(f"am force-stop {APP_ID}")

    def home(self) -> None:
        self.shell("input keyevent KEYCODE_HOME")

    # -- input (per-step verified by callers) ------------------------------

    def tap(self, x: int, y: int) -> None:
        self.shell(f"input tap {x} {y}")

    # -- screen ------------------------------------------------------------

    def screen_stats(self, sample_step: int = 97) -> tuple[float, float]:
        """Mean/stddev of sampled luma from a RAW screencap (no PNG decode needed)."""
        raw = self.exec_out("screencap")
        if len(raw) < 16:
            raise AdbError("screencap returned nothing")
        w, h, _fmt, _cs = struct.unpack_from("<4I", raw, 0)
        px = raw[16:]
        n = min(len(px) // 4, w * h)
        vals = []
        for i in range(0, n, sample_step):
            o = i * 4
            r, g, b = px[o], px[o + 1], px[o + 2]
            vals.append(0.299 * r + 0.587 * g + 0.114 * b)
        mean = sum(vals) / len(vals)
        var = sum((v - mean) ** 2 for v in vals) / len(vals)
        return mean, var ** 0.5

    def screenshot(self, name: str) -> Path:
        out = self.workdir / f"{name}.png"
        out.write_bytes(self.exec_out("screencap -p"))
        return out

    def preview_is_live(self) -> bool:
        """Two frames a moment apart must differ (sensor noise guarantees it on a live feed).

        Samples the middle half of the frame — the top rows are static chrome/status bar.
        """
        a = self.exec_out("screencap")
        time.sleep(0.7)
        b = self.exec_out("screencap")
        if len(a) != len(b):
            return True
        lo = 16 + (len(a) - 16) // 4
        hi = 16 + 3 * (len(a) - 16) // 4
        diff = sum(1 for i in range(lo, hi, 613) if a[i] != b[i])
        return diff > 20

    # -- logcat ------------------------------------------------------------

    def log_mark(self) -> str:
        return self.shell('date +"%m-%d %H:%M:%S.000"')

    def logcat_since(self, mark: str, pid: int | None = None, timeout: int = 30) -> str:
        pid_arg = f"--pid={pid} " if pid else ""
        return self._run("logcat", "-d", "-t", mark, *(pid_arg.split()), timeout=timeout)  # type: ignore[return-value]

    def fatal_lines(self, mark: str, pid: int | None = None) -> list[str]:
        log = self.logcat_since(mark, pid)
        return [
            ln for ln in log.splitlines()
            if LOGCAT_FATAL.search(ln) and not LOGCAT_BENIGN.search(ln)
        ]

    def wait_log(self, mark: str, pattern: str, timeout_s: float = 15.0, pid: int | None = None) -> str | None:
        rx = re.compile(pattern)
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            for ln in self.logcat_since(mark, pid).splitlines():
                if rx.search(ln):
                    return ln
            time.sleep(1)
        return None

    # -- media dir ---------------------------------------------------------

    def media_listing(self) -> list[str]:
        out = self.shell(f"ls -1 {MEDIA_DIR} 2>/dev/null || true")
        return [l for l in out.splitlines() if l]

    def pending_files(self) -> list[str]:
        out = self.shell(f"ls -1a {MEDIA_DIR} 2>/dev/null | grep '^\\.pending' || true")
        return [l for l in out.splitlines() if l]

    def wait_new_media(self, before: list[str], min_new: int = 1, timeout_s: float = 20.0) -> list[str]:
        base = set(before)
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            new = [f for f in self.media_listing() if f not in base]
            if len(new) >= min_new:
                return sorted(new)
            time.sleep(1)
        return sorted(f for f in self.media_listing() if f not in base)

    # -- UI tree -----------------------------------------------------------

    def ui(self) -> "UiTree":
        self.shell("uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || uiautomator dump")
        xml = self.exec_out("cat /sdcard/window_dump.xml").decode(errors="replace")
        return UiTree(xml)

    def tap_ui(self, *, desc: str | None = None, text: str | None = None, retries: int = 3) -> UiNode:
        """Find a node and tap it, re-dumping to verify the tap had a chance to land."""
        last = None
        for _ in range(retries):
            tree = self.ui()
            node = tree.find(desc=desc, text=text)
            if node:
                self.tap(*node.center)
                time.sleep(0.9)
                return node
            last = tree
            time.sleep(1)
        labels = ", ".join(sorted(last.all_labels())[:25]) if last else "?"
        raise AdbError(f"UI node not found (desc={desc!r} text={text!r}); visible: {labels}")


_BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class UiTree:
    def __init__(self, xml: str):
        self.nodes: list[UiNode] = []
        # XXE/billion-laughs guard without a defusedxml dependency: uiautomator output
        # never contains a DTD, so any entity/doctype declaration is hostile — drop it.
        if "<!DOCTYPE" in xml or "<!ENTITY" in xml:
            return
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            return
        for el in root.iter("node"):
            m = _BOUNDS.match(el.get("bounds", ""))
            if not m:
                continue
            self.nodes.append(
                UiNode(
                    text=el.get("text", ""),
                    desc=el.get("content-desc", ""),
                    bounds=tuple(int(g) for g in m.groups()),  # type: ignore[arg-type]
                    selected=el.get("selected") == "true",
                    enabled=el.get("enabled") != "false",
                )
            )

    def find(self, *, desc: str | None = None, text: str | None = None) -> UiNode | None:
        for n in self.nodes:
            if desc is not None and desc.lower() in n.desc.lower():
                return n
            if text is not None and text.lower() == n.text.lower():
                return n
        return None

    def find_contains(self, needle: str) -> UiNode | None:
        needle = needle.lower()
        for n in self.nodes:
            if needle in n.desc.lower() or needle in n.text.lower():
                return n
        return None

    def all_labels(self) -> set[str]:
        out = set()
        for n in self.nodes:
            if n.desc:
                out.add(n.desc)
            if n.text:
                out.add(n.text)
        return out
