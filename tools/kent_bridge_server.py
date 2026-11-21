#!/usr/bin/env python3
"""Manually started, mutually authenticated loopback bridge for Termux."""
import hmac
import json
import os
from pathlib import Path
import secrets
import selectors
import signal
import socketserver
import stat
import struct
import subprocess
import threading
import time

HOST = "127.0.0.1"
PORT = 47883
HELLO = b"KENT-BRIDGE-2"
NONCE_BYTES = MAC_BYTES = 32
MAX_REQUEST = 40000
MAX_OUTPUT = 16000
MAX_RESPONSE = 24000
GUARD = threading.Lock()


def load_token(home):
    os.umask(0o077)
    directory = home / ".kent-bridge"
    token_path = directory / "token"
    directory.mkdir(mode=0o700, exist_ok=True)
    if directory.is_symlink():
        raise RuntimeError("Папка токена моста должна быть обычной папкой.")
    os.chmod(directory, 0o700)
    try:
        with os.fdopen(os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as token_file:
            token_file.write(secrets.token_hex(24).encode("ascii") + b"\n")
    except FileExistsError:
        pass
    with os.fdopen(os.open(token_path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), "rb") as token_file:
        if not stat.S_ISREG(os.fstat(token_file.fileno()).st_mode):
            raise RuntimeError("Токен моста должен храниться в обычном файле.")
        os.fchmod(token_file.fileno(), 0o600)
        value = token_file.read(64).strip()
    if len(value) != 48 or any(c not in b"0123456789abcdef" for c in value):
        raise RuntimeError("Некорректный токен в ~/.kent-bridge/token.")
    return value


def signature(token, label, client_nonce, server_nonce, payload=b""):
    return hmac.digest(token, HELLO + b"\0" + label + b"\0" + client_nonce + server_nonce + payload, "sha256")


def send_frame(sock, data):
    sock.sendall(struct.pack("!I", len(data)) + data)


def read_exact(sock, size, deadline):
    data = bytearray()
    while len(data) < size:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("bridge request deadline")
        sock.settimeout(remaining)
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError("bridge connection closed")
        data.extend(chunk)
    return bytes(data)


def receive_frame(sock, limit, deadline):
    size = struct.unpack("!I", read_exact(sock, 4, deadline))[0]
    if not 1 <= size <= limit:
        raise ValueError("invalid bridge frame size")
    return read_exact(sock, size, deadline)


def encode_result(result):
    def encode(value):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    data = encode(result)
    if len(data) <= MAX_RESPONSE - MAC_BYTES:
        return data
    output = result["output"]
    result = result.copy()
    low, high = 0, len(output)
    while low < high:
        length = (low + high + 1) // 2
        result["output"] = output[:length] + "\n[Вывод сокращён]"
        if len(encode(result)) > MAX_RESPONSE - MAC_BYTES:
            high = length - 1
        else:
            low = length
    result["output"] = output[:low] + "\n[Вывод сокращён]"
    return encode(result)


def resolve_workspace(home, value, mode):
    if value == ".":
        return home
    if mode != "termux" or not isinstance(value, str) or len(value) > 1024 or "\\" in value:
        raise ValueError()
    for part in value.split("/"):
        if part in ("", ".", "..") or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in part):
            raise ValueError()
    path = (home / value).resolve(strict=True)
    if not path.is_relative_to(home) or not path.is_dir():
        raise ValueError()
    return path


def kill_group(proc):
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def collect_output(proc, seconds):
    capture = bytearray()
    truncated = False
    timed_out = False
    detached = False
    deadline = time.monotonic() + seconds
    drain_until = None
    with selectors.DefaultSelector() as selector:
        selector.register(proc.stdout, selectors.EVENT_READ)
        while selector.get_map() or proc.poll() is None:
            now = time.monotonic()
            if not timed_out and now >= deadline and proc.poll() is None:
                timed_out = True
                kill_group(proc)
                proc.wait(timeout=5)
            if proc.poll() is not None:
                if drain_until is None:
                    drain_until = now + 0.25
                elif now >= drain_until:
                    detached = bool(selector.get_map())
                    break
            if not selector.get_map():
                try:
                    proc.wait(timeout=max(0, deadline - now))
                except subprocess.TimeoutExpired:
                    pass
                continue
            if selector.select(0.1):
                block = os.read(proc.stdout.fileno(), 4096)
                if not block:
                    selector.unregister(proc.stdout)
                    continue
                free = MAX_OUTPUT - len(capture)
                truncated |= len(block) > free
                capture.extend(block[:free])
    output = capture.decode("utf-8", "replace")
    if truncated:
        output += "\n[Вывод сокращён]"
    if detached:
        output += "\n[Поток вывода остался открытым]"
    return {"status": "timeout" if timed_out else "exited", "exitCode": proc.returncode,
            "detached": detached, "output": output}


def reject(message):
    return {"status": "rejected", "message": message}


def execute(home, bash, mode, command, directory, seconds):
    env = os.environ.copy()
    if mode == "adb":
        rish = home / "rish"
        if not rish.is_file():
            return reject("Файл ~/rish не найден. Настрой Shizuku в Termux.")
        env["RISH_APPLICATION_ID"] = "com.termux"
        run = [str(rish), "-c", command]
    else:
        run = [str(bash), "-c", command]
    try:
        proc = subprocess.Popen(run, cwd=directory, env=env, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    except OSError:
        return reject("Не удалось запустить команду. Проверь рабочую папку, Bash и rish.")
    try:
        return collect_output(proc, seconds)
    finally:
        proc.stdout.close()
        if proc.poll() is None:
            kill_group(proc)
            proc.wait(timeout=5)


def run_request(home, bash, payload):
    try:
        request = json.loads(payload.decode("utf-8"))
        if not isinstance(request, dict):
            raise ValueError()
        mode = request["mode"]
        command = request["command"]
        seconds = request["timeout"]
        if mode not in ("termux", "adb") or not isinstance(command, str) or not command.strip():
            raise ValueError()
        if len(command) > 5000 or "\x00" in command or type(seconds) is not int or not 1 <= seconds <= 120:
            raise ValueError()
    except (KeyError, ValueError, RecursionError):
        return reject("Недопустимая команда или таймаут.")
    try:
        directory = resolve_workspace(home, request["workDir"], mode)
    except (KeyError, OSError, ValueError, RuntimeError):
        return reject("Рабочая папка должна существовать внутри домашней папки Termux.")
    if not GUARD.acquire(blocking=False):
        return reject("В мосте уже выполняется команда.")
    try:
        return execute(home, bash, mode, command, directory, seconds)
    except (OSError, subprocess.SubprocessError):
        return {"status": "unknown"}
    finally:
        GUARD.release()


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        token = self.server.token
        try:
            deadline = time.monotonic() + 5
            hello = receive_frame(sock, len(HELLO) + NONCE_BYTES, deadline)
            if not hello.startswith(HELLO) or len(hello) != len(HELLO) + NONCE_BYTES:
                return
            client_nonce = hello[len(HELLO):]
            server_nonce = secrets.token_bytes(NONCE_BYTES)
            send_frame(sock, server_nonce + signature(token, b"server", client_nonce, server_nonce))
            request = receive_frame(sock, MAX_REQUEST, deadline)
            request_mac, payload = request[:MAC_BYTES], request[MAC_BYTES:]
            if not hmac.compare_digest(request_mac, signature(token, b"request", client_nonce, server_nonce, payload)):
                return
            result = encode_result(run_request(self.server.home, self.server.bash, payload))
            sock.settimeout(2)
            send_frame(sock, signature(token, b"response", client_nonce, server_nonce, result) + result)
        except (OSError, ValueError):
            return


class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, home, bash):
        self.home = home.resolve()
        self.bash = bash
        self.token = load_token(self.home)
        self.slots = threading.BoundedSemaphore(16)
        super().__init__((HOST, PORT), Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def main(home, bash):
    with Server(home, bash) as server:
        print("KENT BRIDGE 0.6 · 127.0.0.1:%s" % PORT, flush=True)
        print("Файл токена: ~/.kent-bridge/token. Остановить мост: Ctrl+C.", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main(Path.home(), Path(os.environ.get("PREFIX", "/data/data/com.termux/files/usr")) / "bin" / "bash")
