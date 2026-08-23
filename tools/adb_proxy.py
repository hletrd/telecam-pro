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


def pump(src, dst):
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            dst.sendall(chunk)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


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
        threading.Thread(target=pump, args=(client, upstream), daemon=True).start()
        threading.Thread(target=pump, args=(upstream, client), daemon=True).start()


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
