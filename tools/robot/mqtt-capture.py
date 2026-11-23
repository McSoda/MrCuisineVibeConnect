#!/usr/bin/env python3
"""Enregistre les publications MQTT de Toque (état, statut) dans un fichier JSONL horodaté.

Sert à comparer, pendant la recette d'essai, ce que publie MQTT avec l'API locale
(`verifier-recette.py --capture`). Python 3 seul, aucune dépendance : client MQTT 3.1.1
d'abonnement (QoS 0), TLS en option.

    set MQTT_PASSWORD=...        (jamais en argument : il resterait dans l'historique du shell)
    python tools/robot/mqtt-capture.py --host 192.168.1.10 --user toque \
        --topic "toque/df89b8b22d79cb00/#" --out passage-mqtt.jsonl --duration 1200

Chaque ligne : {"ts": <secondes epoch, millisecondes>, "topic": "...", "retain": false, "payload": "..."}.
Le message retenu envoyé à l'abonnement (état courant) porte "retain": true.
"""
import argparse
import json
import os
import socket
import ssl
import struct
import sys
import time


def enc_str(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def enc_len(n: int) -> bytes:
    out = bytearray()
    while True:
        d, n = n % 128, n // 128
        out.append(d | (0x80 if n else 0))
        if not n:
            return bytes(out)


def packet(first: int, body: bytes) -> bytes:
    return bytes([first]) + enc_len(len(body)) + body


def read_exact(sock, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connexion fermée par le broker")
        buf += chunk
    return buf


def read_packet(sock):
    first = read_exact(sock, 1)[0]
    mult, length = 1, 0
    while True:
        b = read_exact(sock, 1)[0]
        length += (b & 0x7F) * mult
        if not b & 0x80:
            break
        mult *= 128
    return first, read_exact(sock, length) if length else b""


def connect(a, password: str):
    raw = socket.create_connection((a.host, a.port), timeout=10)
    if a.tls:
        ctx = ssl.create_default_context()
        sock = ctx.wrap_socket(raw, server_hostname=a.host)
    else:
        sock = raw
    flags = 0x02  # clean session
    body = enc_str("MQTT") + bytes([4])
    payload = enc_str(a.client_id)
    if a.user:
        flags |= 0x80
        payload += enc_str(a.user)
        if password:
            flags |= 0x40
            payload += enc_str(password)
    body += bytes([flags]) + struct.pack(">H", 60) + payload
    sock.sendall(packet(0x10, body))
    first, data = read_packet(sock)
    if first >> 4 != 2 or len(data) < 2 or data[1] != 0:
        raise ConnectionError(f"connexion refusée (code {data[1] if len(data) > 1 else '?'})")
    sub = struct.pack(">H", 1) + enc_str(a.topic) + bytes([0])
    sock.sendall(packet(0x82, sub))
    return sock


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")   # console Windows en cp1252 par défaut
        except (AttributeError, ValueError):
            pass
    ap =argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--user", default="")
    ap.add_argument("--tls", action="store_true", help="MQTT sur TLS (port 8883 en général)")
    ap.add_argument("--topic", required=True, help='filtre, ex. "toque/<id>/#"')
    ap.add_argument("--out", required=True, help="fichier JSONL (ajouté à la fin s'il existe)")
    ap.add_argument("--duration", type=int, default=0, help="durée en secondes (défaut : jusqu'à Ctrl+C)")
    ap.add_argument("--client-id", default="toque-capture-%d" % os.getpid())
    a = ap.parse_args()
    password = os.environ.get("MQTT_PASSWORD", "")

    end = time.time() + a.duration if a.duration else None
    count = 0
    with open(a.out, "a", encoding="utf-8") as f:
        while end is None or time.time() < end:
            try:
                sock = connect(a, password)
                sock.settimeout(30)
                last_ping = time.time()
                print("connecté, abonné à", a.topic, file=sys.stderr)
                while end is None or time.time() < end:
                    try:
                        first, data = read_packet(sock)
                    except socket.timeout:
                        first = None
                    now = time.time()
                    if now - last_ping > 30:
                        sock.sendall(b"\xc0\x00")
                        last_ping = now
                    if first is None or first >> 4 != 3:
                        continue
                    tlen = struct.unpack(">H", data[:2])[0]
                    topic = data[2:2 + tlen].decode("utf-8", "replace")
                    pos = 2 + tlen + (2 if (first >> 1) & 3 else 0)
                    f.write(json.dumps({"ts": round(now, 3), "topic": topic, "retain": bool(first & 1),
                                        "payload": data[pos:].decode("utf-8", "replace")}, ensure_ascii=False) + "\n")
                    f.flush()
                    count += 1
            except KeyboardInterrupt:
                break
            except (OSError, ConnectionError, ssl.SSLError) as e:
                print("connexion perdue :", e, "— nouvel essai dans 3 s", file=sys.stderr)
                time.sleep(3)
    print(count, "messages enregistrés dans", a.out, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
