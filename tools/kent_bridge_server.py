#!/usr/bin/env python3
"""KENT CODE 0.3.1 loopback transport for Termux Google Play builds.

Start it manually in Termux: python tools/kent_bridge_server.py
It listens ONLY on 127.0.0.1:47883 and demands a 48-character secret.
It executes ONE user-approved command at a time. Never forward the port.
"""
import hmac
import json
import os
from pathlib import Path
import secrets
import signal
import socketserver
import struct
import subprocess
import threading
import time

HOST = "127.0.0.1"
PORT = 47883
MAX_REQUEST = 16384
MAX_OUTPUT = 16000
MAX_RESPONSE = 24000
HOME = Path.home()
SECRETS = HOME / ".kent-bridge"
TOKEN_FILE = SECRETS / "token"
GUARD = threading.Lock()

def load_token():
    os.umask(0o077)
    SECRETS.mkdir(mode=0o700, exist_ok=True)
    os.chmod(SECRETS, 0o700)
    if not TOKEN_FILE.exists():
        token = secrets.token_hex(24)
        with TOKEN_FILE.open("x") as f:
            f.write(token + "\n")
    os.chmod(TOKEN_FILE, 0o600)
    value = TOKEN_FILE.read_text().strip()
    if len(value) != 48 or any(c not in "0123456789abcdef" for c in value):
        raise RuntimeError("Invalid token; inspect ~/.kent-bridge/token")
    return value

TOKEN = load_token()

def reply(handler, data):
    buf = json.dumps(data, ensure_ascii=False).encode("utf-8")[:MAX_RESPONSE]
    handler.request.sendall(struct.pack("!I", len(buf)) + buf)

def exact(sock, n):
    out = bytearray()
    while len(out) < n:
        chunk = sock.recv(n-len(out))
        if not chunk:
            raise ConnectionError("connection closed")
        out.extend(chunk)
    return bytes(out)

def execute(mode, command, seconds):
    env = os.environ.copy()
    env["RISH_APPLICATION_ID"] = "com.termux"
    if mode == "adb":
        run = [str(HOME / "rish"), "-c", command]
        if not (HOME / "rish").is_file():
            return {"error": "rish missing: first configure Shizuku in Termux"}
    else:
        bash = os.environ.get("PREFIX", "/data/data/com.termux/files/usr") + "/bin/bash"
        run = [bash, "-c", command]
    proc = subprocess.Popen(run, cwd=str(HOME), env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, start_new_session=True)
    chunks = []
    total = [0]
    def reader():
        while True:
            block = proc.stdout.read(4096)
            if not block:
                break
            if total[0] < MAX_OUTPUT:
                piece = block[:MAX_OUTPUT - total[0]]
                chunks.append(piece)
                total[0] += len(piece)
    t = threading.Thread(target=reader, daemon=True)
    t.start()
    expired = False
    try:
        code = proc.wait(timeout=seconds)
    except subprocess.TimeoutExpired:
        expired = True
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        code = proc.wait(timeout=5)
    t.join(timeout=2)
    output = b"".join(chunks).decode("utf-8", "replace")
    if total[0] == MAX_OUTPUT:
        output += "\n[Output limited to 16KB]"
    return {"exitCode": code, "timeout": expired, "output": output}

class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            self.request.settimeout(5)
            size = struct.unpack("!I", exact(self.request, 4))[0]
            if size == 0 or size > MAX_REQUEST:
                return reply(self, {"error": "invalid message size"})
            obj = json.loads(exact(self.request, size).decode("utf-8"))
            candidate = obj.get("token", "")
            if not isinstance(candidate, str) or not hmac.compare_digest(candidate, TOKEN):
                return reply(self, {"error": "authentication failed"})
            mode, command = obj.get("mode"), obj.get("command")
            if mode not in ("termux", "adb") or not isinstance(command, str):
                return reply(self, {"error": "invalid mode / command"})
            if not command.strip() or len(command) > 5000 or "\x00" in command:
                return reply(self, {"error": "empty or invalid command"})
            seconds = obj.get("timeout", 45)
            if type(seconds) is not int:
                return reply(self, {"error": "invalid timeout"})
            seconds = max(1, min(120, seconds))
            if not GUARD.acquire(blocking=False):
                return reply(self, {"error": "another command is running"})
            try:
                self.request.settimeout(seconds + 8)
                result = execute(mode, command, seconds)
                reply(self, result)
            finally:
                GUARD.release()
        except (BrokenPipeError, ConnectionError, TimeoutError, OSError):
            pass
        except Exception as exc:
            try:
                reply(self, {"error": "bridge failed: " + str(exc)[:500]})
            except OSError:
                pass

class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True

if __name__ == "__main__":
    print("KENT BRIDGE · Google Play Termux · 127.0.0.1:%s" % PORT, flush=True)
    print("LOCAL TOKEN (keep private, paste ONLY into KENT CODE > BRIDGE):", flush=True)
    print(TOKEN, flush=True)
    print("Use Ctrl+C to stop. The server is accessible only on this device.", flush=True)
    with Server((HOST, PORT), Handler) as s:
        s.serve_forever()
