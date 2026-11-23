#!/usr/bin/env python3
"""Linux integration test for the Termux loopback helper."""
import hmac
import hashlib
from io import BytesIO
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
import zipfile

HELLO = b"KENT-BRIDGE-2"
ADDRESS = ("127.0.0.1", 47883)
BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
sys.dont_write_bytecode = True
from tools.kent_bridge_server import developer_command


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


def read_signed(sock, key, client_nonce, server_nonce):
    frame = read_frame(sock)
    assert hmac.compare_digest(frame[:32], signature(key, b"response", client_nonce, server_nonce, frame[32:]))
    return json.loads(frame[32:])


def snapshot(files):
    data = BytesIO()
    with zipfile.ZipFile(data, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in files:
            archive.writestr(name, content)
    return data.getvalue()


def developer_request(sock, key, program, check, inputs, archive=None, *, digest=None, timeout=10, argv=None):
    client_nonce, server_nonce, legit = challenge(sock, key)
    assert legit
    if argv is None:
        try:
            args, _, _ = developer_command(program, check, inputs)
            argv = [program, *args]
        except ValueError:
            argv = []
    request = dict(mode="developer", program=program, check=check, inputs=inputs, argv=argv, workDir=".", timeout=timeout)
    if archive is not None:
        request.update(archiveBytes=len(archive), archiveSha256=digest or hashlib.sha256(archive).hexdigest())
    payload = json.dumps(request).encode()
    write_frame(sock, signature(key, b"request", client_nonce, server_nonce, payload) + payload)
    if archive is not None:
        response = read_signed(sock, key, client_nonce, server_nonce)
        if response.get("status") != "ready":
            return response, None
        sock.sendall(archive)
    return None, (client_nonce, server_nonce)


def developer(key, program, check, inputs, archive=None, **options):
    with socket.create_connection(ADDRESS, timeout=6) as sock:
        response, challenge_data = developer_request(sock, key, program, check, inputs, archive, **options)
        if response is None:
            response = read_signed(sock, key, *challenge_data)
        return response


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

        result = developer(token, "python3", "version", [])
        assert result["status"] == "exited" and result["exitCode"] == 0 and "Python " in result["stdout"], result
        result = developer(token, "bash", "version", [])
        assert result["status"] == "rejected", result
        result = developer(token, "python3", "version", [], argv=["python3", "-c", "print('altered')"])
        assert result["status"] == "rejected", result
        result = developer(token, "python3", "syntax", ["../outside.py"])
        assert result["status"] == "rejected", result
        result = developer(token, "python3", "syntax", ["-c.py"])
        assert result["status"] == "rejected", result
        result = developer(token, "python3", "syntax", ["broken.py"], snapshot([("broken.py", "def invalid(")]))
        assert result["exitCode"] != 0 and "SyntaxError" in result["stderr"] and not result["stdout"], result
        valid = snapshot([("nested/hello.py", "print('syntax only')")])
        result = developer(token, "python3", "syntax", ["nested/hello.py"], valid)
        assert result["exitCode"] == 0 and "syntax only" not in result["stdout"], result
        result = developer(token, "python3", "tests", [], valid)
        assert result["exitCode"] == 4 and "No tests discovered" in result["stderr"], result
        result = developer(token, "python3", "syntax", ["nested/hello.py"], valid, digest="0" * 64)
        assert result["status"] == "rejected" and "сумма" in result["message"], result
        file_escape = snapshot([("../approved-run", b"escaped")])
        result = developer(token, "python3", "syntax", ["inside.py"], file_escape)
        assert result["status"] == "rejected" and not marker.exists(), result
        link = zipfile.ZipInfo("unsafe.py")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        result = developer(token, "python3", "syntax", ["unsafe.py"], snapshot([(link, "/etc/passwd")]))
        assert result["status"] == "rejected", result
        if shutil.which("c++"):
            code = """
            #include <cmath>
            int main() {
                double a = 1, b = -3, c = 2;
                double root = (-b + std::sqrt(b*b - 4*a*c)) / (2*a);
                return root == 2 ? 0 : 1;
            }
            """
            result = developer(token, "c++", "compile", ["main with spaces.cpp"],
                               snapshot([("main with spaces.cpp", code)]))
            assert result["status"] == "exited" and result["exitCode"] == 0, result

        started = home / "check-started"
        late = home / "check-child-escaped"
        late_code = f"import time; from pathlib import Path; time.sleep(2); Path({str(late)!r}).touch()"
        test_code = f"""
import subprocess
import sys
import time
import unittest
from pathlib import Path

class CancellationTest(unittest.TestCase):
    def test_wait(self):
        subprocess.Popen([sys.executable, "-I", "-c", {late_code!r}])
        Path({str(started)!r}).touch()
        print("ready", flush=True)
        time.sleep(30)
"""
        with socket.create_connection(ADDRESS, timeout=6) as sock:
            initial, nonces = developer_request(sock, token, "python3", "tests", [],
                                                snapshot([("test_cancel.py", test_code)]))
            assert initial is None
            for _ in range(30):
                if started.exists():
                    break
                time.sleep(0.05)
            assert started.exists(), "The test command never started"
            write_frame(sock, signature(token, b"cancel", *nonces))
            result = read_signed(sock, token, *nonces)
            assert result["status"] == "cancelled" and result["exitCode"] is not None and not result["detached"], result

        result, _ = ask(token, "printf Before; sleep 3; touch approved-run", timeout=1)
        assert result["status"] == "timeout" and result["output"].startswith("Before"), result
        assert not result["detached"], result
        time.sleep(2.1)
        assert not marker.exists()
        assert not late.exists()
        assert not list((home / ".kent-bridge").glob("check-*"))

        print("PASS: Termux authentication, replay rejection, cwd, argv policy, snapshot integrity, "
              "separate output, compilation, timeout, and process-group cancellation")
    finally:
        server.terminate()
        server.wait(timeout=5)
        output, errors = server.communicate()
        if "token" in locals():
            assert token not in output
        assert not errors, errors
