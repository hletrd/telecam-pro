#!/usr/bin/env python3
"""Loopback -> device TCP forwarder for wireless ADB on a multi-homed host.

`adb connect 172.30.x.x:PORT` returns "No route to host" for a device this machine
reaches perfectly well: ping succeeds, and a bare TCP connect succeeds from either
interface. The cause is local, not remote — two interfaces share the 172.30/17
subnet and adb picks the wrong source. Pointing adb at 127.0.0.1 removes the
choice; this process just shuttles bytes to the device.

    python3 adb_proxy.py --owner-token telecam-manual 172.30.50.112 6112:5555

Devices reached over Tailscale do not need this.
"""
import argparse
import socket
import sys
import threading


class _Connection:
    """One proxied client/upstream pair; the LAST of its two pumps to finish closes both sockets.

    Each pump shuts both directions down on exit so its sibling's recv() returns promptly, but
    shutdown() alone never releases the file descriptors: a long-running proxy leaked two fds
    per connection. close() must wait for the sibling, which may still be inside recv/sendall.
    """

    def __init__(self, *sockets):
        self._sockets = sockets
        self._lock = threading.Lock()
        self._live_pumps = 2

    def pump_finished(self):
        for s in self._sockets:
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        with self._lock:
            self._live_pumps -= 1
            last = self._live_pumps == 0
        if last:
            for s in self._sockets:
                try:
                    s.close()
                except OSError:
                    pass


def pump(src, dst, connection):
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            dst.sendall(chunk)
    except OSError:
        pass
    finally:
        connection.pump_finished()


def serve(local_port, remote_host, remote_port):
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", local_port))
    srv.listen(16)
    print(f"127.0.0.1:{local_port} -> {remote_host}:{remote_port}", flush=True)
    while True:
        client, _ = srv.accept()
        try:
            upstream = socket.create_connection((remote_host, remote_port), timeout=10)
        except OSError as exc:
            print(f"upstream {remote_port} failed: {exc}", flush=True)
            client.close()
            continue
        client.settimeout(None)
        upstream.settimeout(None)
        connection = _Connection(client, upstream)
        threading.Thread(target=pump, args=(client, upstream, connection), daemon=True).start()
        threading.Thread(target=pump, args=(upstream, client, connection), daemon=True).start()


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner-token", required=True, help="opaque fleet ownership token")
    parser.add_argument("host")
    parser.add_argument("ports", nargs="+", metavar="LOCAL:REMOTE")
    return parser.parse_args(argv)


if __name__ == "__main__":
    args = parse_args(sys.argv[1:])
    host = args.host
    for spec in args.ports:
        lp, rp = (int(x) for x in spec.split(":"))
        threading.Thread(target=serve, args=(lp, host, rp), daemon=True).start()
    threading.Event().wait()
