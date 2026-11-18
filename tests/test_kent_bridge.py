#!/usr/bin/env python3
"""Smoke test local authenticated bridge on CI Linux (not Android)."""
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import time

base = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as tmp:
    env = dict(os.environ, HOME=tmp, PREFIX="/usr", PYTHONUNBUFFERED="1")
    server = subprocess.Popen([sys.executable, "-u", str(base/"tools/kent_bridge_server.py")],
                              env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        path = Path(tmp)/".kent-bridge"/"token"
        for _ in range(60):
            if path.exists():
                try:
                    with socket.create_connection(("127.0.0.1", 47883), timeout=0.2):
                        break
                except OSError:
                    pass
            time.sleep(0.1)
        else:
            raise AssertionError("server failed to listen")
        token = path.read_text().strip()
        assert len(token) == 48
        assert (path.stat().st_mode & 0o777) == 0o600

        def ask(value):
            payload = json.dumps(value).encode()
            with socket.create_connection(("127.0.0.1", 47883), timeout=5) as s:
                s.sendall(struct.pack("!I", len(payload)) + payload)
                length = struct.unpack("!I", s.recv(4))[0]
                chunks = bytearray()
                while len(chunks) < length:
                    part = s.recv(length-len(chunks))
                    if not part:
                        raise AssertionError("truncated reply")
                    chunks.extend(part)
            return json.loads(chunks.decode())

        denied = ask({"token":"bad", "mode":"termux","command":"id"})
        assert "authentication failed" in denied["error"]
        success = ask({"token":token, "mode":"termux","command":"printf KENT_OK","timeout":10})
        assert success["exitCode"] == 0, success
        assert "KENT_OK" in success["output"], success
        print("PASS: loopback authentication and command result")
    finally:
        server.terminate()
        server.wait(timeout=5)
