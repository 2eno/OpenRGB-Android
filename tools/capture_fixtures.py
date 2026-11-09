#!/usr/bin/env python3
"""Captures real OpenRGB SDK packets as unit-test fixtures.

Start an OpenRGB server with debug devices first, e.g.:

    mkdir -p /tmp/orgbcfg && cat > /tmp/orgbcfg/OpenRGB.json <<'JSON'
    {"DebugDevices": {"devices": [{"type": "keyboard"}, {"type": "motherboard"},
      {"type": "argb"}, {"type": "gpu"}, {"type": "mouse"}, {"type": "dram"}]}}
    JSON
    QT_QPA_PLATFORM=offscreen openrgb --config /tmp/orgbcfg --server --server-port 16742 --noautoconnect

Then run:

    python3 tools/capture_fixtures.py --port 16742 --out openrgb-sdk/src/test/resources/fixtures/openrgb-1.0

For every protocol version 0..6 the script opens a fresh connection, negotiates that version
(version 0 skips the version request, like an OpenRGB 0.3 client), requests the controller count
and the data of every controller, and stores each reply payload as `v<version>_controller_count.bin`
and `v<version>_device<index>.bin`. For protocol 6 it additionally records the SIGNALUPDATE that
follows an UpdateLEDs. Nothing but the Python standard library is required.
"""
import argparse
import os
import socket
import struct
import time

HEADER = struct.Struct("<4sIII")


def send(sock, dev_id, pkt_id, payload=b""):
    sock.sendall(HEADER.pack(b"ORGB", dev_id, pkt_id, len(payload)) + payload)


def recv_exact(sock, size):
    buf = b""
    while len(buf) < size:
        chunk = sock.recv(size - len(buf))
        if not chunk:
            raise EOFError("server closed connection")
        buf += chunk
    return buf


def recv_packet(sock):
    magic, dev_id, pkt_id, size = HEADER.unpack(recv_exact(sock, HEADER.size))
    if magic != b"ORGB":
        raise ValueError("bad magic %r" % magic)
    return dev_id, pkt_id, recv_exact(sock, size) if size else b""


def wait_for(sock, wanted_id, wanted_dev=None, timeout=5.0):
    """Returns the payload of the next packet with wanted_id, skipping ACKs and notifications."""
    sock.settimeout(timeout)
    while True:
        dev_id, pkt_id, payload = recv_packet(sock)
        if pkt_id == wanted_id and (wanted_dev is None or dev_id == wanted_dev):
            return dev_id, payload


def capture(host, port, version, out):
    with socket.create_connection((host, port), timeout=5) as sock:
        if version > 0:
            send(sock, 0, 40, struct.pack("<I", version))
            _, payload = wait_for(sock, 40)
            server_version = struct.unpack("<I", payload)[0]
            assert server_version >= version, "server too old for protocol %d" % version
        send(sock, 0, 50, b"fixture-capture\x00")

        send(sock, 0, 0)
        _, count_payload = wait_for(sock, 0)
        write(out, "v%d_controller_count.bin" % version, count_payload)
        count = struct.unpack_from("<I", count_payload)[0]
        if version >= 6:
            ids = list(struct.unpack_from("<%dI" % count, count_payload, 4))
        else:
            ids = list(range(count))

        for index, dev_id in enumerate(ids):
            request = struct.pack("<I", version) if version > 0 else b""
            send(sock, dev_id, 1, request)
            _, payload = wait_for(sock, 1, dev_id)
            write(out, "v%d_device%d.bin" % (version, index), payload)

        if version >= 6 and ids:
            # UpdateLEDs on the first device with LEDs, then capture the SIGNALUPDATE echo.
            dev_id = ids[0]
            send(sock, dev_id, 1, struct.pack("<I", version))
            _, payload = wait_for(sock, 1, dev_id)
            num_leds = struct.unpack_from("<H", payload, find_colors_offset(payload))[0]
            colors = b"".join(struct.pack("<BBBB", (i * 7) & 0xFF, 0x80, (0xFF - i) & 0xFF, 0) for i in range(num_leds))
            body = struct.pack("<H", num_leds) + colors
            send(sock, dev_id, 1050, struct.pack("<I", 4 + len(body)) + body)
            _, signal = wait_for(sock, 1150, dev_id)
            write(out, "v6_signalupdate_leds_device0.bin", signal)
            write(out, "v6_updateleds_request_device0.bin", struct.pack("<I", 4 + len(body)) + body)


def find_colors_offset(payload):
    """Minimal v6 walk to the num_colors field (mirrors DeviceCodec, kept tiny on purpose)."""
    o = 4  # data_size

    def string16(off):
        (length,) = struct.unpack_from("<H", payload, off)
        return off + 2 + length

    def mode(off):
        off = string16(off)
        off += 4 * 10  # flags, speed min/max, brightness min/max, colors min/max, speed, brightness, direction
        off += 4  # color_mode
        (n,) = struct.unpack_from("<H", payload, off)
        return off + 2 + 4 * n

    def matrix(off):
        (length,) = struct.unpack_from("<H", payload, off)
        return off + 2 + length

    o += 4  # type
    for _ in range(6):  # name vendor description version serial location
        o = string16(o)
    (num_modes,) = struct.unpack_from("<H", payload, o)
    o += 2 + 4  # num_modes, active_mode
    for _ in range(num_modes):
        o = mode(o)
    (num_zones,) = struct.unpack_from("<H", payload, o)
    o += 2
    for _ in range(num_zones):
        o = string16(o)
        o += 4 * 4  # type, leds min/max/count
        o = matrix(o)
        (num_segments,) = struct.unpack_from("<H", payload, o)
        o += 2
        for _ in range(num_segments):
            o = string16(o)
            o += 4 * 3
            o = matrix(o)
            o += 4
        o += 4  # zone flags
        o += 4  # zone active mode
        (zone_modes,) = struct.unpack_from("<H", payload, o)
        o += 2
        for _ in range(zone_modes):
            o = mode(o)
        o = string16(o)  # zone display name
    (num_leds,) = struct.unpack_from("<H", payload, o)
    o += 2
    for _ in range(num_leds):
        o = string16(o)
    return o


def write(out, name, data):
    path = os.path.join(out, name)
    with open(path, "wb") as f:
        f.write(data)
    print("wrote %s (%d bytes)" % (path, len(data)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6742)
    parser.add_argument("--out", required=True)
    parser.add_argument("--versions", default="0,1,2,3,4,5,6")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for version in [int(v) for v in args.versions.split(",")]:
        capture(args.host, args.port, version, args.out)
        time.sleep(0.2)


if __name__ == "__main__":
    main()
