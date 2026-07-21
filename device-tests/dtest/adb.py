"""adb transport + UI-tree helpers.

Every interaction is per-step verified (dump/log/screencap after acting) because the
wireless-debugging proxy can silently drop an injected tap mid-sequence — a lesson from
the review-plan-fix cycles. Keep helpers synchronous and chatty-on-failure.
"""

from __future__ import annotations

import re
import shlex
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

APP_ID = "me.hletrd.telecampro.debug"
MAIN_ACTIVITY = f"{APP_ID}/com.hletrd.findx9tele.MainActivity"
MEDIA_DIR = "/sdcard/DCIM/X9Tele"
MEDIA_RELATIVE_PATH = "DCIM/X9Tele/"
_CAPTURE_FILE = re.compile(
    r"^(IMG|VID)_TELECAM_F1_([0-9]{13})_([0-9]{10})\.([A-Za-z0-9]+)$"
)

# Error signatures worth failing a test over. CAMERA_DISCONNECTED during an intentional
# force-stop is expected noise, so reliability tests scan scoped windows, not whole runs.
LOGCAT_FATAL = re.compile(r"FATAL EXCEPTION|ANR in|CAMERA_ERROR|Fatal signal")
# Known-benign device facts that match the pattern above but must NOT fail a test:
# the QTI HAL's photo↔video teardown emits a framework-level -38 "Error clearing streaming
# request" that the app contains (closed-gate StateCallback, cycle 3) — it never reaches the UI.
LOGCAT_BENIGN = re.compile(r"Error clearing streaming request: Function not implemented \(-38\)")
GLOBAL_CAMERA_FATAL = re.compile(
    rf"ANR in {re.escape(APP_ID)}|"
    rf"am_anr.*{re.escape(APP_ID)}|"
    r"(?:Fatal signal|FATAL EXCEPTION).*(?:cameraserver|camera[_ .-]?provider|provider@2\.[4-9])|"
    r"(?:cameraserver|camera[_ .-]?provider|provider@2\.[4-9]).*"
    r"(?:Fatal signal|FATAL EXCEPTION|service died|has died|crash)|"
    r"CameraManagerGlobal.*Camera service (?:is )?(?:currently )?unavailable|"
    r"CameraService.*(?:service died|service is unavailable|ERROR_CAMERA_SERVICE)|"
    r"CameraProviderManager.*(?:service died|has died|fatal)|"
    r"Camera3-Device.*(?:Error condition .*reported by HAL|Camera HAL reported serious device error)",
    re.IGNORECASE,
)


class AdbError(RuntimeError):
    pass


@dataclass
class UiNode:
    text: str
    desc: str
    class_name: str
    bounds: tuple[int, int, int, int]  # l, t, r, b
    checkable: bool
    checked: bool
    selected: bool
    enabled: bool
    clickable: bool
    focusable: bool = False

    @property
    def center(self) -> tuple[int, int]:
        l, t, r, b = self.bounds
        return (l + r) // 2, (t + b) // 2


@dataclass(frozen=True)
class DisplayMetrics:
    width_px: int
    height_px: int
    density_dpi: int


@dataclass(frozen=True)
class MediaRow:
    collection: str
    row_id: int
    display_name: str
    mime_type: str
    relative_path: str
    is_pending: bool
    width: int
    height: int
    size_bytes: int
    duration_ms: int | None
    owner_package_name: str

    @property
    def key(self) -> tuple[str, int]:
        return self.collection, self.row_id

    @property
    def family_key(self) -> tuple[str, int, int] | None:
        match = _CAPTURE_FILE.fullmatch(self.display_name)
        if not match:
            return None
        prefix = "image" if match.group(1) == "IMG" else "video"
        if prefix != self.collection:
            return None
        return prefix, int(match.group(2)), int(match.group(3))

    @property
    def metadata_ready(self) -> bool:
        if self.is_pending or self.size_bytes <= 0:
            return False
        if self.mime_type in ("image/heic", "image/jpeg", "video/mp4"):
            if self.width <= 0 or self.height <= 0:
                return False
        if self.mime_type == "video/mp4" and (self.duration_ms or 0) <= 0:
            return False
        return True

    @property
    def metadata_fingerprint(self) -> tuple:
        return (
            self.key,
            self.display_name,
            self.mime_type,
            self.relative_path,
            self.is_pending,
            self.width,
            self.height,
            self.size_bytes,
            self.duration_ms,
            self.owner_package_name,
        )


def parse_media_rows(collection: str, output: str) -> list[MediaRow]:
    rows: list[MediaRow] = []
    for line in output.splitlines():
        match = re.match(r"Row: \d+ (.*)", line.strip())
        if not match:
            continue
        fields = {}
        for field in re.split(r", (?=[a-z_][a-z0-9_]*=)", match.group(1)):
            if "=" in field:
                key, value = field.split("=", 1)
                fields[key] = value

        required = {
            "_id",
            "_display_name",
            "mime_type",
            "relative_path",
            "is_pending",
            "width",
            "height",
            "_size",
            "owner_package_name",
        }
        if collection == "video":
            required.add("duration")
        missing = sorted(required - fields.keys())
        if missing:
            raise AdbError(f"MediaStore {collection} row is missing fields {missing}: {line[:300]}")

        def integer(name: str) -> int:
            value = fields.get(name, "0")
            return 0 if value in ("", "NULL") else int(value)

        duration = fields.get("duration")
        row = MediaRow(
            collection=collection,
            row_id=integer("_id"),
            display_name=fields["_display_name"],
            mime_type=fields["mime_type"],
            relative_path=fields["relative_path"],
            is_pending=integer("is_pending") != 0,
            width=integer("width"),
            height=integer("height"),
            size_bytes=integer("_size"),
            duration_ms=None if duration in (None, "", "NULL") else int(duration),
            owner_package_name=fields["owner_package_name"],
        )
        if row.row_id <= 0 or not all(
            (row.display_name, row.mime_type, row.relative_path, row.owner_package_name)
        ):
            raise AdbError(f"MediaStore {collection} row has invalid identity fields: {line[:300]}")
        rows.append(
            row
        )
    return rows


def relevant_global_fatal_lines(log: str) -> list[str]:
    """System/HAL failures relevant to this app, excluding unrelated device log noise."""
    return [
        line for line in log.splitlines()
        if GLOBAL_CAMERA_FATAL.search(line) and not LOGCAT_BENIGN.search(line)
    ]


def parse_df_available_bytes(output: str) -> int:
    """Parse the Available column from Android toybox ``df -k`` output."""
    lines = [line.split() for line in output.splitlines() if line.strip()]
    header_index = next(
        (index for index, fields in enumerate(lines) if "Available" in fields),
        None,
    )
    if header_index is None:
        raise AdbError(f"df -k omitted an Available column: {output[:300]}")
    available_index = lines[header_index].index("Available")
    candidates = []
    for fields in lines[header_index + 1:]:
        if len(fields) <= available_index:
            continue
        value = fields[available_index]
        if value.isdigit():
            candidates.append(int(value))
    if len(candidates) != 1:
        raise AdbError(
            f"df -k returned {len(candidates)} parseable filesystem rows: {output[:300]}"
        )
    available_kib = candidates[0]
    if available_kib <= 0:
        raise AdbError(f"df -k reported no available storage: {output[:300]}")
    return available_kib * 1024


class Adb:
    def __init__(self, serial: str, workdir: Path, *, allow_destructive: bool = False):
        self.serial = serial
        self.workdir = workdir
        self.allow_destructive = allow_destructive
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

    def free_bytes(self) -> int:
        """Return free shared-storage bytes from one unambiguous toybox ``df -k`` row."""
        return parse_df_available_bytes(self.shell("df -k /sdcard"))

    # -- app lifecycle -----------------------------------------------------

    def pid(self) -> int | None:
        out = self.shell(f"pidof {APP_ID} || true")
        return int(out.split()[0]) if out and out.split()[0].isdigit() else None

    def resumed_activity(self) -> str | None:
        """Return the exact foreground component, expanding Android's `.Class` shorthand."""
        output = self.shell("dumpsys activity activities")
        for line in output.splitlines():
            if "ResumedActivity" not in line:
                continue
            match = re.search(r"\bu\d+\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)(?:\s|})", line)
            if match is None:
                continue
            component = match.group(1)
            package_name, class_name = component.split("/", 1)
            if class_name.startswith("."):
                class_name = package_name + class_name
            return f"{package_name}/{class_name}"
        return None

    def launch(self, wait_s: float = 5.0) -> int:
        if not self.allow_destructive:
            raise AdbError(
                "refusing app launch without explicit destructive approval; "
                "startup may reclaim incomplete app-owned pending media"
            )
        self.shell("input keyevent KEYCODE_WAKEUP")
        self.shell(f"am start --activity-reorder-to-front -n {MAIN_ACTIVITY}")
        deadline = time.time() + wait_s + 10
        last_resumed: str | None = None
        while time.time() < deadline:
            p = self.pid()
            last_resumed = self.resumed_activity()
            if p and last_resumed == MAIN_ACTIVITY:
                time.sleep(wait_s)  # let the camera session configure
                if self.pid() == p and self.resumed_activity() == MAIN_ACTIVITY:
                    return p
            time.sleep(0.5)
        raise AdbError(f"main activity did not reach foreground (resumed={last_resumed!r})")

    def force_stop(self) -> None:
        if not self.allow_destructive:
            raise AdbError("refusing force-stop without explicit destructive approval")
        if self.pid() is None:
            return

        tree = self.ui()
        if tree.find(desc="Stop recording"):
            raise AdbError("refusing force-stop while recording is active")
        if not (tree.find(desc="Take photo") or tree.find(desc="Start recording")):
            raise AdbError("refusing force-stop because an idle camera state cannot be proven")
        self.shell(f"am force-stop {APP_ID}")

    def home(self) -> None:
        self.shell("input keyevent KEYCODE_HOME")

    # -- input (per-step verified by callers) ------------------------------

    def tap(self, x: int, y: int) -> None:
        self.shell(f"input tap {x} {y}")

    # -- screen ------------------------------------------------------------

    def display_metrics(self) -> DisplayMetrics:
        raw = self.exec_out("screencap")
        if len(raw) < 16:
            raise AdbError("screencap returned no display header")
        width, height, _fmt, _color_space = struct.unpack_from("<4I", raw, 0)
        if width <= 0 or height <= 0 or width > 16_384 or height > 16_384:
            raise AdbError(f"invalid screencap dimensions: {width}x{height}")

        density_output = self.shell("wm density")
        densities = [int(value) for value in re.findall(r"(?:Physical|Override) density: (\d+)", density_output)]
        if not densities:
            raise AdbError(f"could not parse display density: {density_output[:200]}")
        density = densities[-1]  # Override, when present, is the active logical density.
        if density < 72 or density > 1_200:
            raise AdbError(f"implausible display density: {density}")
        return DisplayMetrics(width, height, density)

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
        # logcat accepts fractional Unix epoch on API 36. Millisecond precision avoids admitting up
        # to 999 ms of pre-mark lines, while epoch form remains unambiguous across New Year.
        return self.shell("date +%s.%3N")

    def logcat_since(
        self,
        mark: str,
        pid: int | None = None,
        timeout: int = 30,
        *,
        all_buffers: bool = False,
    ) -> str:
        args = ["logcat", "-d", "-v", "threadtime"]
        if all_buffers:
            args.extend(("-b", "all"))
        args.extend(("-t", mark))
        if pid is not None:
            args.append(f"--pid={pid}")
        return self._run(*args, timeout=timeout)  # type: ignore[return-value]

    def fatal_lines(self, mark: str, pid: int | None = None) -> list[str]:
        app_log = self.logcat_since(mark, pid)
        app_lines = [
            ln for ln in app_log.splitlines()
            if LOGCAT_FATAL.search(ln) and not LOGCAT_BENIGN.search(ln)
        ]
        global_lines = relevant_global_fatal_lines(
            self.logcat_since(mark, all_buffers=True)
        )
        # The app line may also be present in the all-buffer dump; preserve order without duplicates.
        return list(dict.fromkeys((*app_lines, *global_lines)))

    def wait_log(self, mark: str, pattern: str, timeout_s: float = 15.0, pid: int | None = None) -> str | None:
        rx = re.compile(pattern)
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            for ln in self.logcat_since(mark, pid).splitlines():
                if rx.search(ln):
                    return ln
            time.sleep(1)
        return None

    # -- MediaStore --------------------------------------------------------

    def media_store_rows(self) -> list[MediaRow]:
        base_projection = (
            "_id:_display_name:mime_type:relative_path:is_pending:width:height:"
            "_size:owner_package_name"
        )
        where = f"relative_path='{MEDIA_RELATIVE_PATH}' AND owner_package_name='{APP_ID}'"
        # MediaStore hides pending rows by default. The content CLI accepts Bundle extras as
        # key:type:value; escape the colon inside the Android query-arg key itself.
        include_pending = shlex.quote(r"android\:query-arg-match-pending:i:1")
        rows = []
        for collection, uri, projection in (
            ("image", "content://media/external/images/media", base_projection),
            ("video", "content://media/external/video/media", f"{base_projection}:duration"),
        ):
            output = self.shell(
                " ".join(
                    (
                        "content query",
                        f"--uri {uri}",
                        f"--projection {projection}",
                        f"--where {shlex.quote(where)}",
                        f"--sort {shlex.quote('_id ASC')}",
                        f"--extra {include_pending}",
                        "2>&1",
                    )
                )
            )
            if "Error while accessing provider" in output or "Exception:" in output:
                raise AdbError(f"MediaStore {collection} query failed: {output[:300]}")
            lines = [line.strip() for line in output.splitlines() if line.strip()]
            row_lines = [line for line in lines if re.fullmatch(r"Row: \d+ .+", line)]
            valid_empty = lines == ["No result found."]
            unexpected = [line for line in lines if line not in row_lines]
            if (
                "[ERROR]" in output
                or not lines
                or (not valid_empty and (unexpected or not row_lines))
            ):
                raise AdbError(
                    f"MediaStore {collection} query returned unexpected output: "
                    f"{(unexpected or lines or ['<empty>'])[:2]}"
                )
            parsed = parse_media_rows(collection, output)
            if len(parsed) != len(row_lines):
                raise AdbError(
                    f"MediaStore {collection} query lost rows while parsing: "
                    f"lines={len(row_lines)} parsed={len(parsed)}"
                )
            rows.extend(parsed)
        return sorted(rows, key=lambda row: row.key)

    def pending_rows(self) -> list[MediaRow]:
        return [row for row in self.media_store_rows() if row.is_pending]

    def wait_new_media_rows(
        self,
        before: set[tuple[str, int]],
        *,
        min_new: int = 1,
        timeout_s: float = 20.0,
        settle_s: float = 8.0,
    ) -> list[MediaRow]:
        deadline = time.time() + timeout_s
        stable_since: float | None = None
        last_fingerprint: tuple | None = None
        target_family: tuple[str, int, int] | None = None
        latest: list[MediaRow] = []
        while time.time() < deadline:
            discovered = [row for row in self.media_store_rows() if row.key not in before]
            families = {row.family_key for row in discovered}
            if None in families:
                invalid = [row.display_name for row in discovered if row.family_key is None]
                raise AdbError(f"new media uses a non-canonical capture family: {invalid}")
            if len(families) > 1:
                raise AdbError(f"multiple new capture families appeared: {sorted(families)}")
            if families:
                observed_family = next(iter(families))
                if target_family is None:
                    target_family = observed_family
                elif observed_family != target_family:
                    raise AdbError(
                        f"capture family changed while waiting: {target_family} -> {observed_family}"
                    )
            latest = [row for row in discovered if row.family_key == target_family]
            fingerprint = tuple(row.metadata_fingerprint for row in latest)
            publish_complete = (
                len(latest) >= min_new
                and all(row.metadata_ready for row in latest)
            )
            if publish_complete and fingerprint == last_fingerprint:
                stable_since = stable_since or time.time()
                if time.time() - stable_since >= settle_s:
                    return latest
            else:
                stable_since = None
            last_fingerprint = fingerprint
            time.sleep(0.5)
        return latest

    def wait_published_media_row(
        self,
        key: tuple[str, int],
        *,
        timeout_s: float = 45.0,
        settle_s: float = 1.0,
    ) -> MediaRow | None:
        """Wait for one already-known pending row to publish without assuming it is the only family."""
        deadline = time.time() + timeout_s
        stable_since: float | None = None
        last_fingerprint: tuple | None = None
        latest: MediaRow | None = None
        while time.time() < deadline:
            latest = next((row for row in self.media_store_rows() if row.key == key), None)
            fingerprint = latest.metadata_fingerprint if latest is not None else None
            if latest is not None and latest.metadata_ready and fingerprint == last_fingerprint:
                stable_since = stable_since or time.time()
                if time.time() - stable_since >= settle_s:
                    return latest
            else:
                stable_since = None
            last_fingerprint = fingerprint
            time.sleep(0.5)
        return latest

    # -- UI tree -----------------------------------------------------------

    def ui(self, evidence_name: str | None = None) -> "UiTree":
        self.shell("uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || uiautomator dump")
        xml = self.exec_out("cat /sdcard/window_dump.xml").decode(errors="replace")
        if evidence_name is not None:
            if re.fullmatch(r"[A-Za-z0-9_.-]+", evidence_name) is None:
                raise AdbError(f"invalid UI evidence name: {evidence_name!r}")
            (self.workdir / f"{evidence_name}.xml").write_text(xml, encoding="utf-8")
        return UiTree(xml)

    def tap_ui(self, *, desc: str | None = None, text: str | None = None, retries: int = 3) -> UiNode:
        """Find and tap an enabled node; the caller must verify the expected state change."""
        last = None
        for _ in range(retries):
            tree = self.ui()
            node = tree.find(desc=desc, text=text)
            if node:
                if not node.enabled:
                    raise AdbError(f"UI node is disabled (desc={desc!r} text={text!r})")
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
                    class_name=el.get("class", ""),
                    bounds=tuple(int(g) for g in m.groups()),  # type: ignore[arg-type]
                    checkable=el.get("checkable") == "true",
                    checked=el.get("checked") == "true",
                    selected=el.get("selected") == "true",
                    enabled=el.get("enabled") != "false",
                    clickable=el.get("clickable") == "true",
                    focusable=el.get("focusable") == "true",
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

    def find_desc_exact(self, description: str) -> UiNode | None:
        needle = description.casefold()
        return next((node for node in self.nodes if node.desc.casefold() == needle), None)

    def all_labels(self) -> set[str]:
        out = set()
        for n in self.nodes:
            if n.desc:
                out.add(n.desc)
            if n.text:
                out.add(n.text)
        return out
