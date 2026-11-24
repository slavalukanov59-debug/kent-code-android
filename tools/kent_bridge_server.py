#!/usr/bin/env python3
"""Mutually authenticated loopback bridge for Termux."""
import errno
import hmac
import hashlib
import json
import os
from pathlib import Path
import secrets
import select
import selectors
import shutil
import signal
import socketserver
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zipfile

HOST = "127.0.0.1"
PORT = 47883
HELLO = b"KENT-BRIDGE-2"
NONCE_BYTES = MAC_BYTES = 32
MAX_REQUEST = 40000
MAX_OUTPUT = 16000
MAX_RESPONSE = 24000
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_EXPANDED = 512 * 1024 * 1024
PROGRAMS = ("python3", "java", "javac", "c++", "clang++", "g++", "git", "node", "gradle", "kotlinc")
PYTHON_SYNTAX = "import ast,sys,tokenize; p=sys.argv[1]; ast.parse(tokenize.open(p).read(),filename=p)"
PYTHON_TESTS = ("import sys,unittest\n"
                "suite=unittest.defaultTestLoader.discover('.')\n"
                "if not suite.countTestCases():\n print('No tests discovered',file=sys.stderr); sys.exit(4)\n"
                "result=unittest.TextTestRunner().run(suite)\n"
                "sys.exit(4 if result.testsRun==len(result.skipped) else 0 if result.wasSuccessful() else 1)")
GRADLE_TASKS = ("test", "check", "lint", "testDebugUnitTest", "lintDebug", "assembleDebug")
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
    result = result.copy()
    if "stdout" in result:
        # Developer runs reserve bounded space for each stream before encoding.
        result["stdout"] = result["stdout"][:1000] + "\n[Вывод сокращён]"
        result["stderr"] = result["stderr"][:1000] + "\n[Вывод сокращён]"
        return encode(result)
    output = result["output"]
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


def checked_parts(value):
    if not isinstance(value, str) or len(value) > 4096 or "\\" in value or ":" in value:
        raise ValueError("Неподдерживаемый путь")
    parts = value.split("/")
    for part in parts:
        if (part in ("", ".", "..") or part.lower().startswith(".kent-")
                or part.lower() in (".git", ".gradle")
                or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in part)):
            raise ValueError("Неподдерживаемый путь")
    return parts


def developer_command(program, check, inputs):
    if program not in PROGRAMS or not isinstance(inputs, list) or not all(isinstance(s, str) for s in inputs):
        raise ValueError("Неподдерживаемый инструмент")

    def sources(extensions):
        for value in inputs:
            checked_parts(value)
            if value.startswith(("-", "@")) or not value.lower().endswith(extensions):
                raise ValueError("Некорректный относительный путь исходного файла")
        return inputs

    snapshot = False
    files = []
    if check == "version" and not inputs:
        args = ["-version" if program in ("java", "javac", "kotlinc") else "--version"]
    elif program == "python3" and check == "syntax" and len(inputs) == 1:
        files = sources((".py",))
        args = ["-I", "-c", PYTHON_SYNTAX, *files]
    elif program == "python3" and check == "tests" and not inputs:
        args = ["-I", "-c", PYTHON_TESTS]
        snapshot = True
    elif program == "node" and check == "syntax" and len(inputs) == 1:
        files = sources((".js", ".mjs", ".cjs"))
        args = ["--check", *files]
    elif program in ("c++", "clang++", "g++") and check in ("syntax", "compile") and inputs:
        files = sources((".cpp", ".cc", ".cxx", ".c++"))
        snapshot = check == "compile"
        options = ["-o", ".kent-check-program"] if snapshot else ["-fsyntax-only"]
        args = ["-std=c++17", *options, *files]
    elif program == "javac" and check == "compile" and inputs:
        files = sources((".java",))
        args = ["-proc:none", "-d", ".kent-check-classes", *files]
        snapshot = True
    elif program == "kotlinc" and check == "compile" and inputs:
        files = sources((".kt",))
        args = ["-d", ".kent-check-classes", *files]
        snapshot = True
    elif program == "gradle" and check == "gradle" and inputs and all(task in GRADLE_TASKS for task in inputs):
        args = ["--no-daemon", "--console=plain", "--rerun-tasks", "--no-build-cache", *inputs]
        snapshot = True
    else:
        raise ValueError("Неподдерживаемый набор аргументов")
    if sum(len(part.encode("utf-8")) + 1 for part in [program, *args]) > 16000:
        raise ValueError("Слишком большой запрос")
    return args, snapshot, files


def receive_archive(sock, output, size, expected):
    digest = hashlib.sha256()
    deadline = time.monotonic() + 120
    while size:
        chunk = read_exact(sock, min(65536, size), deadline)
        output.write(chunk)
        digest.update(chunk)
        size -= len(chunk)
    return hmac.compare_digest(digest.hexdigest(), expected)


class CheckCancelled(Exception):
    pass


def cancellation_pending(sock, proof):
    if not select.select([sock], [], [], 0)[0]:
        return False
    try:
        return hmac.compare_digest(receive_frame(sock, len(proof), time.monotonic() + 1), proof)
    except (OSError, ValueError):
        return True


def extract_snapshot(archive, destination, cancelled):
    # Each request gets a fresh private destination. Reject alternate path spellings and special files.
    remaining = min(MAX_EXPANDED, max(0, shutil.disk_usage(destination).free - 32 * 1024 * 1024))
    with zipfile.ZipFile(archive) as project:
        entries = project.infolist()
        if len(entries) > 100_000:
            raise ValueError("Снимок содержит слишком много элементов для безопасной распаковки")
        if sum(info.file_size for info in entries) > remaining:
            raise ValueError("Снимок превышает безопасный объём распаковки или доступное место")
        seen = set()
        for info in entries:
            if cancelled():
                raise CheckCancelled()
            parts = checked_parts(info.filename[:-1] if info.is_dir() else info.filename)
            kind = stat.S_IFMT(info.external_attr >> 16)
            if (kind not in (0, stat.S_IFREG, stat.S_IFDIR) or
                    kind == stat.S_IFDIR and not info.is_dir() or kind == stat.S_IFREG and info.is_dir()):
                raise ValueError("Специальные файлы в снимке не поддерживаются")
            path = destination.joinpath(*parts)
            if path in seen:
                raise ValueError("Повторяющийся путь в снимке")
            seen.add(path)
            if info.is_dir():
                path.mkdir(parents=True, exist_ok=True)
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            with project.open(info) as source, path.open("xb") as target:
                while True:
                    if cancelled():
                        raise CheckCancelled()
                    data = source.read(65536)
                    if not data:
                        break
                    remaining -= len(data)
                    if remaining < 0:
                        raise ValueError("Размер распаковки превышен")
                    target.write(data)


def developer_output(proc, sock, cancel_proof, seconds):
    buffers = {proc.stdout: bytearray(), proc.stderr: bytearray()}
    status = "exited"
    detached = False
    truncated = False
    deadline = time.monotonic() + seconds
    drain_until = None
    with selectors.DefaultSelector() as selector:
        for stream in buffers:
            selector.register(stream, selectors.EVENT_READ)
        selector.register(sock, selectors.EVENT_READ)
        while True:
            now = time.monotonic()
            if now >= deadline and proc.poll() is None:
                status = "timeout"
                kill_group(proc)
                proc.wait(timeout=5)
            if proc.poll() is not None:
                if drain_until is None:
                    # Child processes in the command's group must not outlive the job.
                    kill_group(proc)
                    drain_until = now + 0.25
                if not any(stream.fileno() in selector.get_map() for stream in buffers):
                    break
                if now >= drain_until:
                    detached = True
                    break
            for key, _ in selector.select(0.1):
                if key.fileobj is sock:
                    if not cancellation_pending(sock, cancel_proof):
                        continue
                    selector.unregister(sock)
                    if proc.poll() is None:
                        status = "cancelled"
                        kill_group(proc)
                        proc.wait(timeout=5)
                    continue
                block = os.read(key.fd, 4096)
                if not block:
                    selector.unregister(key.fileobj)
                    continue
                buffer = buffers[key.fileobj]
                free = max(0, 8000 - len(buffer))
                truncated |= len(block) > free
                buffer.extend(block[:free])
    detail = "[Вывод сокращён]" if truncated else ""
    return dict(status=status, exitCode=proc.returncode, detached=detached,
                stdout=buffers[proc.stdout].decode("utf-8", "replace"),
                stderr=buffers[proc.stderr].decode("utf-8", "replace"), detail=detail)


def run_developer(home, bash, request, sock, cancel_proof, ready):
    if os.geteuid() == 0:
        return reject("Для проверки проекта запустите мост под обычным пользователем Termux.")
    try:
        program = request["program"]
        args, needs_snapshot, inputs = developer_command(program, request["check"], request.get("inputs", []))
        if request["argv"] != [program, *args]:
            raise ValueError("Команда отличается от предварительного просмотра")
        seconds = request["timeout"]
        size = request.get("archiveBytes", 0)
        digest = request.get("archiveSha256", "")
        directory = request["workDir"]
        if (type(seconds) is not int or not 1 <= seconds <= 120 or type(size) is not int
                or not 0 <= size <= MAX_ARCHIVE or needs_snapshot and not size):
            raise ValueError("Недопустимый размер снимка или таймаут")
        if size and (not isinstance(digest, str) or len(digest) != 64
                     or any(c not in "0123456789abcdef" for c in digest)):
            raise ValueError("Некорректная контрольная сумма снимка")
        if directory != ".":
            checked_parts(directory)
    except (KeyError, ValueError, TypeError, UnicodeError, RecursionError):
        return reject("Недопустимые параметры проверки Termux.")
    if not GUARD.acquire(blocking=False):
        return reject("В мосте уже выполняется команда.")
    try:
        bin_directory = str(Path(bash).parent)
        executable = shutil.which(program, path=bin_directory)
        if executable is None:
            return dict(status="exited", exitCode=127, detached=False, stdout="", stderr="",
                        detail=f"Инструмент {program} не найден в {bin_directory}")
        with tempfile.TemporaryDirectory(prefix="check-", dir=home / ".kent-bridge") as tmp:
            work = Path(tmp)
            def cancelled():
                return cancellation_pending(sock, cancel_proof)

            if size:
                if size > shutil.disk_usage(work).free:
                    return reject("Недостаточно свободного места для снимка.")
                archive = work / "snapshot.zip"
                ready()
                with archive.open("wb") as output:
                    if not receive_archive(sock, output, size, digest):
                        return reject("Контрольная сумма снимка не совпала.")
                project = work / "project"
                project.mkdir()
                extract_snapshot(archive, project, cancelled)
                archive.unlink()
                cwd = resolve_workspace(project, directory, "termux")
            else:
                cwd = resolve_workspace(home, directory, "termux")
            for name in inputs:
                file = cwd.joinpath(*checked_parts(name)).resolve(strict=True)
                if not file.is_relative_to(cwd) or not file.is_file():
                    return reject("Исходный файл недоступен внутри выбранной папки.")
            check_home = work / "home"
            check_home.mkdir()
            env = {key: os.environ[key] for key in
                   ("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_HOME", "ANDROID_SDK_ROOT", "PREFIX", "LANG",
                    "LD_LIBRARY_PATH", "LD_PRELOAD", "JAVA_HOME")
                   if key in os.environ}
            env.update(HOME=str(check_home), TMPDIR=str(work), PATH=bin_directory)
            if cancelled():
                raise CheckCancelled()
            proc = subprocess.Popen([executable, *args], cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
            try:
                return developer_output(proc, sock, cancel_proof, seconds)
            finally:
                proc.stdout.close()
                proc.stderr.close()
                if proc.poll() is None:
                    kill_group(proc)
                    proc.wait(timeout=5)
    except CheckCancelled:
        return dict(status="cancelled", exitCode=None, detached=False, stdout="", stderr="",
                    detail="Запуск отменён до создания процесса.")
    except (zipfile.BadZipFile, ValueError, FileNotFoundError) as error:
        return reject(f"Не удалось подготовить рабочую папку: {error}")
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

            def respond(value):
                result = encode_result(value)
                sock.settimeout(2)
                send_frame(sock, signature(token, b"response", client_nonce, server_nonce, result) + result)

            decoded = json.loads(payload.decode("utf-8"))
            mode = decoded.get("mode") if isinstance(decoded, dict) else None
            if mode == "health":
                if set(decoded) == {"mode", "healthVersion"} and type(decoded["healthVersion"]) is int \
                        and decoded["healthVersion"] == 1:
                    respond({"status": "healthy", "healthVersion": 1})
                else:
                    respond(reject("Версия проверки готовности моста не поддерживается."))
            elif mode == "developer":
                cancel_proof = signature(token, b"cancel", client_nonce, server_nonce)
                respond(run_developer(self.server.home, self.server.bash, decoded, sock, cancel_proof,
                                      lambda: respond({"status": "ready"})))
            else:
                respond(run_request(self.server.home, self.server.bash, payload))
        except (OSError, ValueError, RecursionError):
            return


class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, home, bash):
        self.home = home.resolve()
        self.bash = bash
        self.slots = threading.BoundedSemaphore(16)
        # Binding first prevents duplicate launches racing token creation or touching a running bridge's files.
        super().__init__((HOST, PORT), Handler)
        try:
            self.token = load_token(self.home)
        except Exception:
            self.server_close()
            raise

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
    try:
        server = Server(home, bash)
    except OSError as error:
        if error.errno == errno.EADDRINUSE:
            print(f"Порт {HOST}:{PORT} уже занят. Проверьте состояние моста в KENT CODE.", flush=True)
            return 0
        print("KENT BRIDGE не запущен. Проверьте права Termux и папку установки.", file=sys.stderr, flush=True)
        return 1
    except RuntimeError:
        print("KENT BRIDGE не запущен. Проверьте файл токена и папку установки.", file=sys.stderr, flush=True)
        return 1
    with server:
        print(f"KENT BRIDGE 0.9 · {HOST}:{PORT}", flush=True)
        if sys.stdout.isatty():
            print("Файл токена: ~/.kent-bridge/token. Остановить мост: Ctrl+C.", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main(Path.home(), Path(os.environ.get("PREFIX", "/data/data/com.termux/files/usr")) / "bin" / "bash"))
