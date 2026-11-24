package ai.kent.code;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;

/** Mutual token authentication for the locally launched Google Play Termux bridge. */
public final class LocalTermuxBridge {
    private static final int PORT = 47883;
    private static final int MAX_REPLY = 24000;
    private static final int MAX_REQUEST = 40000;
    private static final int NONCE_BYTES = 32;
    private static final int MAC_BYTES = 32;
    static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;
    static final long MAX_SNAPSHOT_BYTES = 512L * 1024 * 1024;
    private static final byte[] HELLO = "KENT-BRIDGE-2".getBytes(StandardCharsets.US_ASCII);
    // Deliberately carries no command, even when received by an older server.
    private static final byte[] HEALTH_REQUEST =
            "{\"mode\":\"health\",\"healthVersion\":1}".getBytes(StandardCharsets.US_ASCII);
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", PORT);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();
    private final SecureKeyStore secrets;

    public LocalTermuxBridge(SecureKeyStore secrets) { this.secrets = secrets; }

    public boolean configured() {
        try { return validToken(secrets.bridgeToken()); }
        catch (Exception ignored) { return false; }
    }

    public static final class Health {
        public enum Status { READY, OFFLINE, TIMEOUT, AUTH_FAILED, LEGACY, INCOMPATIBLE, UNAVAILABLE, CANCELLED, UNCONFIGURED }

        public final Status status;
        public final String detail;

        Health(Status status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

    /** Verifies the running loopback server without starting a process or sending code. */
    public Health probe(BooleanSupplier cancelled) {
        if (CommandResult.cancelled(cancelled)) return healthCancelled();
        String token;
        try {
            token = secrets.bridgeToken();
        } catch (Exception unavailable) {
            return new Health(Health.Status.UNCONFIGURED, "Не удалось прочитать токен моста из защищённого хранилища.");
        }
        return probe(LOOPBACK, token, cancelled);
    }

    static Health probe(InetSocketAddress endpoint, String token, BooleanSupplier cancelled) {
        if (CommandResult.cancelled(cancelled)) return healthCancelled();
        if (!validToken(token))
            return new Health(Health.Status.UNCONFIGURED, "Добавьте токен локального моста из 48 символов.");
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, 3000);
            Session session = authenticate(socket, token, cancelled);
            checkCancelled(cancelled);
            session.request(HEALTH_REQUEST);
            byte[] reply = session.readSigned(deadline(3), () -> checkCancelled(cancelled));
            JSONObject response = new JSONObject(new String(reply, StandardCharsets.UTF_8));
            if ("healthy".equals(response.optString("status")) && Integer.valueOf(1).equals(response.opt("healthVersion")))
                return new Health(Health.Status.READY, "Подлинность и готовность локального моста подтверждены.");
            if ("rejected".equals(response.optString("status")) &&
                    "Недопустимая команда или таймаут.".equals(response.optString("message")))
                return new Health(Health.Status.LEGACY, "Работает прежняя версия моста. Для автоподключения обновите сервер до 0.9.");
            return incompatible();
        } catch (BridgeCancelled stopped) {
            return healthCancelled();
        } catch (BridgeAuthenticationFailed | GeneralSecurityException invalid) {
            if (CommandResult.cancelled(cancelled)) return healthCancelled();
            return new Health(Health.Status.AUTH_FAILED, "Подлинность моста не подтверждена. Сверьте токен в настройках.");
        } catch (BridgeProtocolMismatch | EOFException | JSONException invalid) {
            return CommandResult.cancelled(cancelled) ? healthCancelled() : incompatible();
        } catch (ConnectException unavailable) {
            if (CommandResult.cancelled(cancelled)) return healthCancelled();
            String reason = unavailable.getMessage();
            if (reason != null && (reason.contains("ECONNREFUSED") || reason.contains("Connection refused")))
                return new Health(Health.Status.OFFLINE, "Локальный мост не принимает подключения.");
            return healthUnavailable();
        } catch (SocketTimeoutException timeout) {
            if (CommandResult.cancelled(cancelled)) return healthCancelled();
            return new Health(Health.Status.TIMEOUT, "Истекло время ожидания ответа локального моста.");
        } catch (IOException unavailable) {
            return CommandResult.cancelled(cancelled) ? healthCancelled() : healthUnavailable();
        }
    }

    private static Health healthCancelled() {
        return new Health(Health.Status.CANCELLED, "Проверка локального моста отменена.");
    }

    private static Health healthUnavailable() {
        return new Health(Health.Status.UNAVAILABLE, "Не удалось установить или сохранить соединение с локальным мостом.");
    }

    private static Health incompatible() {
        return new Health(Health.Status.INCOMPATIBLE, "Мост не подтвердил протокол готовности. Обновите сервер до версии 0.9.");
    }

    public String run(String command, boolean adb, int requestedSeconds, BooleanSupplier cancelled) throws IOException {
        return runResult(command, adb, null, requestedSeconds, cancelled).display();
    }

    public CommandResult runResult(String command, boolean adb, String workingDirectory, int requestedSeconds,
                                   BooleanSupplier cancelled) throws IOException {
        CommandResult.validateCommand(command);
        String directory = CommandResult.workDir(workingDirectory, adb);
        int seconds = CommandResult.timeout(requestedSeconds);
        try {
            return exchange(new JSONObject().put("mode", adb ? "adb" : "termux").put("command", command)
                    .put("workDir", directory).put("timeout", seconds), false, adb, null, seconds, cancelled);
        } catch (JSONException invalid) {
            throw new IOException("Не удалось сформировать запрос к мосту", invalid);
        }
    }

    CommandResult runAllowed(TerminalCommand command, String directory, int requestedSeconds,
                             WorkspaceZipExporter.Archive snapshot, BooleanSupplier cancelled) throws IOException {
        if (command.needsSnapshot && snapshot == null)
            return CommandResult.rejected("Этой проверке нужна временная копия проекта.");
        if (snapshot != null && snapshot.bytes > MAX_ARCHIVE_BYTES)
            return CommandResult.rejected("Снимок превышает 64 МиБ. Выберите меньшую папку для проверки.");
        int seconds = CommandResult.timeout(requestedSeconds);
        try {
            JSONObject request = new JSONObject().put("mode", "developer")
                    .put("program", command.program).put("check", command.check)
                    .put("inputs", new JSONArray(command.inputs)).put("argv", new JSONArray(command.argv))
                    .put("workDir", CommandResult.workDir(directory, false)).put("timeout", seconds);
            if (snapshot != null) request.put("archiveBytes", snapshot.bytes).put("archiveSha256", snapshot.sha256);
            return exchange(request, true, false, snapshot, seconds, cancelled);
        } catch (JSONException invalid) {
            throw new IOException("Не удалось сформировать запрос проверки", invalid);
        }
    }

    private CommandResult exchange(JSONObject request, boolean developer, boolean adb,
                                   WorkspaceZipExporter.Archive snapshot, int seconds,
                                   BooleanSupplier cancelled) throws IOException {
        String token;
        try { token = secrets.bridgeToken(); }
        catch (Exception e) { throw new IOException("Не удалось прочитать локальный токен", e); }
        if (!validToken(token)) return CommandResult.rejected("В настройках моста нужен токен из 48 символов.");
        if (CommandResult.cancelled(cancelled)) return cancelledResult(false);
        boolean dispatched = false;
        try (Socket socket = new Socket()) {
            socket.connect(LOOPBACK, 3000);
            Session session = authenticate(socket, token, cancelled);

            byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
            if (payload.length > MAX_REQUEST - MAC_BYTES)
                return CommandResult.rejected("Параметры проверки превышают размер запроса к мосту.");
            if (CommandResult.cancelled(cancelled)) return cancelledResult(false);
            dispatched = snapshot == null;
            session.request(payload);
            if (snapshot != null) {
                byte[] initial = session.readSigned(deadline(5), () -> checkCancelled(cancelled));
                if (!"ready".equals(new JSONObject(new String(initial, StandardCharsets.UTF_8)).getString("status")))
                    return developerResult(initial);
                dispatched = true;
                upload(snapshot, session.out, socket, cancelled);
            }
            long responseDeadline = deadline(seconds + (snapshot == null ? 8L : 128L));
            byte[] cancelProof = session.signature("cancel", new byte[0]);
            long[] cancellationSent = {0};
            ReadCheck check = developer ? () -> {
                if (!CommandResult.cancelled(cancelled)) return;
                if (cancellationSent[0] == 0) {
                    cancellationSent[0] = System.nanoTime();
                    writeFrame(session.out, cancelProof);
                } else if (System.nanoTime() - cancellationSent[0] > TimeUnit.SECONDS.toNanos(8)) {
                    throw new BridgeCancelled();
                }
            } : () -> checkCancelled(cancelled);
            byte[] response = session.readSigned(responseDeadline, check);
            return developer ? developerResult(response) : decode(response, adb);
        } catch (BridgeCancelled ignored) {
            return cancelledResult(dispatched);
        } catch (BridgeAuthenticationFailed invalid) {
            if (CommandResult.cancelled(cancelled)) return cancelledResult(dispatched);
            return dispatched ? CommandResult.stopped(CommandResult.Status.UNKNOWN,
                    "Подпись результата проверки не подтверждена.", true)
                    : CommandResult.rejected("Мост не подтвердил подлинность. Сверь токен и перезапусти сервер Termux.");
        } catch (SocketTimeoutException ignored) {
            if (CommandResult.cancelled(cancelled)) return cancelledResult(dispatched);
            return dispatched ? CommandResult.stopped(CommandResult.Status.TIMED_OUT, "Мост не вернул ответ вовремя.", true)
                    : CommandResult.rejected("Истекло ожидание подключения или подтверждения моста Termux.");
        } catch (IOException | JSONException | GeneralSecurityException invalid) {
            if (CommandResult.cancelled(cancelled)) return cancelledResult(dispatched);
            return dispatched ? CommandResult.stopped(CommandResult.Status.UNKNOWN, "Связь с мостом прервалась после отправки.", true)
                    : CommandResult.rejected("Нет подтверждённого моста. Запусти актуальный tools/kent_bridge_server.py в Termux.");
        }
    }

    private static Session authenticate(Socket socket, String token, BooleanSupplier cancelled)
            throws IOException, GeneralSecurityException {
        checkCancelled(cancelled);
        socket.setSoTimeout(250);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        byte[] clientNonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(clientNonce);
        writeFrame(out, join(HELLO, clientNonce));
        byte[] challenge = readFrame(in, deadline(3), () -> checkCancelled(cancelled), NONCE_BYTES + MAC_BYTES);
        if (challenge.length != NONCE_BYTES + MAC_BYTES) throw new BridgeProtocolMismatch();
        byte[] serverNonce = Arrays.copyOf(challenge, NONCE_BYTES);
        byte[] proof = Arrays.copyOfRange(challenge, NONCE_BYTES, challenge.length);
        Session session = new Session(in, out, token.getBytes(StandardCharsets.US_ASCII), clientNonce, serverNonce);
        if (!MessageDigest.isEqual(proof, session.signature("server", new byte[0]))) throw new BridgeAuthenticationFailed();
        return session;
    }

    private static long deadline(long seconds) {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    }

    private static final class Session {
        final DataInputStream in;
        final DataOutputStream out;
        final byte[] key;
        final byte[] clientNonce;
        final byte[] serverNonce;

        Session(DataInputStream in, DataOutputStream out, byte[] key, byte[] clientNonce, byte[] serverNonce) {
            this.in = in;
            this.out = out;
            this.key = key;
            this.clientNonce = clientNonce;
            this.serverNonce = serverNonce;
        }

        byte[] signature(String label, byte[] payload) throws GeneralSecurityException {
            return LocalTermuxBridge.signature(key, label, clientNonce, serverNonce, payload);
        }

        void request(byte[] payload) throws IOException, GeneralSecurityException {
            writeFrame(out, join(signature("request", payload), payload));
        }

        byte[] readSigned(long deadline, ReadCheck check) throws IOException, GeneralSecurityException {
            byte[] response = readFrame(in, deadline, check, MAX_REPLY);
            if (response.length <= MAC_BYTES) throw new BridgeProtocolMismatch();
            byte[] proof = Arrays.copyOf(response, MAC_BYTES);
            byte[] payload = Arrays.copyOfRange(response, MAC_BYTES, response.length);
            if (!MessageDigest.isEqual(proof, signature("response", payload))) throw new BridgeAuthenticationFailed();
            return payload;
        }
    }

    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "kent-termux-upload");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static void upload(WorkspaceZipExporter.Archive snapshot, DataOutputStream out, Socket socket,
                               BooleanSupplier cancelled) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        ScheduledFuture<?> timeout = WATCHDOG.scheduleWithFixedDelay(() -> {
            if (cancelled.getAsBoolean() || System.nanoTime() >= deadline) {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }, 250, 250, TimeUnit.MILLISECONDS);
        try (InputStream file = Files.newInputStream(snapshot.file.toPath())) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = snapshot.bytes;
            while (remaining > 0) {
                checkCancelled(cancelled);
                int size = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (size < 0) throw new EOFException("Снимок проекта неполный");
                out.write(buffer, 0, size);
                remaining -= size;
            }
            out.flush();
        } finally {
            timeout.cancel(false);
        }
    }

    static CommandResult developerResult(byte[] data) throws JSONException {
        JSONObject result = new JSONObject(new String(data, StandardCharsets.UTF_8));
        String remoteStatus = result.getString("status");
        if (remoteStatus.equals("rejected")) {
            String reason = result.getString("message");
            if (reason.equals("Недопустимая команда или таймаут."))
                reason = "Для режима разработчика обновите и перезапустите tools/kent_bridge_server.py в Termux.";
            return CommandResult.rejected(reason);
        }
        CommandResult.Status status;
        switch (remoteStatus) {
            case "exited": status = CommandResult.Status.EXITED; break;
            case "timeout": status = CommandResult.Status.TIMED_OUT; break;
            case "cancelled": status = CommandResult.Status.CANCELLED; break;
            default: return CommandResult.stopped(CommandResult.Status.UNKNOWN, "Мост не подтвердил результат проверки.", true);
        }
        Integer code = result.isNull("exitCode") ? null : result.getInt("exitCode");
        if (status == CommandResult.Status.EXITED && code == null)
            throw new JSONException("Мост не вернул код завершения");
        return CommandResult.observed(status, code, result.getString("stdout"), result.getString("stderr"),
                result.getString("detail"), result.getBoolean("detached"));
    }

    static CommandResult decode(byte[] data, boolean adb) throws JSONException {
        JSONObject response = new JSONObject(new String(data, StandardCharsets.UTF_8));
        switch (response.getString("status")) {
            case "rejected": return CommandResult.rejected(response.getString("message"));
            case "exited": return CommandResult.exited(response.getInt("exitCode"), response.getString("output"),
                    response.getBoolean("detached"));
            case "timeout": return CommandResult.stopped(CommandResult.Status.TIMED_OUT,
                    response.getString("output"), adb || response.getBoolean("detached"));
            default: return CommandResult.stopped(CommandResult.Status.UNKNOWN, "Мост сообщил о сбое после отправки.", true);
        }
    }

    private static CommandResult cancelledResult(boolean dispatched) {
        return CommandResult.stopped(CommandResult.Status.CANCELLED,
                dispatched ? "Связь с мостом прекращена." : "Запуск отменён до отправки.", dispatched);
    }

    private static boolean validToken(String token) {
        return token != null && token.matches("[0-9a-f]{48}");
    }

    private static void writeFrame(DataOutputStream out, byte[] frame) throws IOException {
        out.writeInt(frame.length);
        out.write(frame);
        out.flush();
    }

    private interface ReadCheck { void check() throws IOException; }

    private static void checkCancelled(BooleanSupplier cancelled) throws BridgeCancelled {
        if (CommandResult.cancelled(cancelled)) throw new BridgeCancelled();
    }

    private static byte[] readFrame(DataInputStream in, long deadline, ReadCheck check, int limit) throws IOException {
        byte[] header = new byte[4];
        readExact(in, header, deadline, check);
        int size = ByteBuffer.wrap(header).getInt();
        if (size < 1 || size > limit) throw new BridgeProtocolMismatch();
        byte[] frame = new byte[size];
        readExact(in, frame, deadline, check);
        return frame;
    }

    private static void readExact(DataInputStream in, byte[] buffer, long deadline, ReadCheck check) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            check.check();
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException();
            try {
                int n = in.read(buffer, offset, buffer.length - offset);
                if (n < 0) throw new EOFException();
                offset += n;
            } catch (SocketTimeoutException ignored) { }
        }
    }

    static byte[] signature(byte[] token, String label, byte[] clientNonce, byte[] serverNonce, byte[] payload)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token, "HmacSHA256"));
        mac.update(HELLO);
        mac.update((byte) 0);
        mac.update(label.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) 0);
        mac.update(clientNonce);
        mac.update(serverNonce);
        return mac.doFinal(payload);
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    private static final class BridgeCancelled extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class BridgeAuthenticationFailed extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class BridgeProtocolMismatch extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
