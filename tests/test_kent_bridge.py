#!/usr/bin/env python3
"""Linux integration test for the Termux loopback helper."""
import hmac
import json
from pathlib import Path
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time

HELLO = b"KENT-BRIDGE-2"
ADDRESS = ("127.0.0.1", 47883)
BASE = Path(__file__).resolve().parents[1]


def signature(key, label, client_nonce, server_nonce, payload=b""):
    return hmac.digest(key, HELLO + b"\0" + label + b"\0" + client_nonce + server_nonce + payload, "sha256")


def read_exact(sock, size):
    result = bytearray()
    while len(result) < size:
        chunk = sock.recv(size - len(result))
        if not chunk:
            raise AssertionError("Incomplete response")
        result.extend(chunk)
    return bytes(result)


def read_frame(sock):
    length = struct.unpack("!I", read_exact(sock, 4))[0]
    assert 1 <= length <= 24000
    return read_exact(sock, length)


def write_frame(sock, contents):
    data = struct.pack("!I", len(contents)) + contents
    sock.sendall(data[:2])
    sock.sendall(data[2:])


def challenge(sock, key):
    client_nonce = secrets.token_bytes(32)
    write_frame(sock, HELLO + client_nonce)
    challenge_frame = read_frame(sock)
    assert len(challenge_frame) == 64
    server_nonce, server_mac = challenge_frame[:32], challenge_frame[32:]
    legit = hmac.compare_digest(server_mac, signature(key, b"server", client_nonce, server_nonce))
    return client_nonce, server_nonce, legit


def ask(key, command, work_dir=".", mode="termux", timeout=10):
    payload = json.dumps(dict(mode=mode, command=command, workDir=work_dir, timeout=timeout)).encode()
    with socket.create_connection(ADDRESS, timeout=5) as sock:
        client_nonce, server_nonce, legit = challenge(sock, key)
        assert legit
        request = signature(key, b"request", client_nonce, server_nonce, payload) + payload
        write_frame(sock, request)
        response = read_frame(sock)
        result = response[32:]
        assert hmac.compare_digest(response[:32], signature(key, b"response", client_nonce, server_nonce, result))
    return json.loads(result), request


with tempfile.TemporaryDirectory() as tmp:
    home = Path(tmp)
    start = "import sys; from pathlib import Path; from tools.kent_bridge_server import main; main(Path(sys.argv[1]), sys.argv[2])"
    server = subprocess.Popen([sys.executable, "-u", "-B", "-c", start, tmp, shutil.which("bash")],
                              cwd=BASE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        token_file = home / ".kent-bridge" / "token"
        for _ in range(60):
            if token_file.exists():
                try:
                    with socket.create_connection(ADDRESS, timeout=0.2):
                        break
                except OSError:
                    pass
            time.sleep(0.1)
        else:
            raise AssertionError("Server failed to listen")
        token = token_file.read_bytes().strip()
        assert len(token) == 48
        assert (token_file.stat().st_mode & 0o777) == 0o600

        # Sending a previously used signed command with a fresh challenge must fail.
        marker = home / "approved-run"
        success, signed_request = ask(token, "touch approved-run && printf 'КЕНТ_OK,%s' \"a'b\"")
        assert success == dict(status="exited", exitCode=0, detached=False, output="КЕНТ_OK,a'b"), success
        assert marker.exists()
        marker.unlink()
        with socket.create_connection(ADDRESS, timeout=5) as sock:
            assert challenge(sock, token)[2]
            write_frame(sock, signed_request)
            assert sock.recv(1) == b""
        assert not marker.exists()
        with socket.create_connection(ADDRESS, timeout=5) as sock:
            assert not challenge(sock, b"x" * 48)[2]
        with socket.create_connection(ADDRESS, timeout=5) as sock:
            legacy = json.dumps(dict(token=token.decode(), mode="termux", command="touch approved-run")).encode()
            write_frame(sock, legacy)
            try:
                assert sock.recv(1) == b""
            except ConnectionResetError:
                pass
        assert not marker.exists()

        workspace = home / "project's dir"
        workspace.mkdir()
        result, _ = ask(token, 'printf "%s" "$PWD"', workspace.name)
        assert result["output"] == str(workspace), result
        (home / "outside").symlink_to(BASE, target_is_directory=True)
        for invalid in ("../", "outside", "/etc", "./nested"):
            result, _ = ask(token, "printf ESCAPED", invalid)
            assert result["status"] == "rejected", result
        result, _ = ask(token, "id", workspace.name, mode="adb")
        assert result["status"] == "rejected", result
        result, _ = ask(token, "id", mode="adb")
        assert result["status"] == "rejected" and "rish" in result["message"], result

        result, _ = ask(token, "printf 'ERROR'; exit 7")
        assert result["exitCode"] == 7 and result["output"] == "ERROR", result
        result, _ = ask(token, "python3 -c 'import sys; sys.stdout.buffer.write(bytes([1]) * 60000)'")
        assert result["status"] == "exited" and result["output"].startswith("\x01" * 100), result
        assert result["output"].endswith("[Вывод сокращён]"), result
        result, _ = ask(token, "printf Before; sleep 3; touch approved-run", timeout=1)
        assert result["status"] == "timeout" and result["output"].startswith("Before"), result
        assert not result["detached"], result
        time.sleep(2.1)
        assert not marker.exists()

        print("PASS: Termux loopback authentication, replay rejection, cwd, output, and timeout")
    finally:
        server.terminate()
        server.wait(timeout=5)
        output, errors = server.communicate()
        if "token" in locals():
            assert token not in output
        assert not errors, errors
