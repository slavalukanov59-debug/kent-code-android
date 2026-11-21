package ai.kent.code;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Mutual token authentication for the locally launched Google Play Termux bridge. */
public final class LocalTermuxBridge {
    private static final int PORT = 47883;
    private static final int MAX_REPLY = 24000;
    private static final int NONCE_BYTES = 32;
    private static final int MAC_BYTES = 32;
    private static final byte[] HELLO = "KENT-BRIDGE-2".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecureKeyStore secrets;

    public LocalTermuxBridge(SecureKeyStore secrets) { this.secrets = secrets; }

    public boolean configured() {
        try { return validToken(secrets.bridgeToken()); }
        catch (Exception ignored) { return false; }
    }

    public String run(String command, boolean adb, int requestedSeconds, BooleanSupplier cancelled) throws IOException {
        return runResult(command, adb, null, requestedSeconds, cancelled).display();
    }

    public CommandResult runResult(String command, boolean adb, String workingDirectory, int requestedSeconds,
                                   BooleanSupplier cancelled) throws IOException {
        String token;
        try { token = secrets.bridgeToken(); }
        catch (Exception e) { throw new IOException("Не удалось прочитать локальный токен", e); }
        if (!validToken(token)) return CommandResult.rejected("В настройках моста нужен токен из 48 символов.");
        CommandResult.validateCommand(command);
        String directory = CommandResult.workDir(workingDirectory, adb);
        if (CommandResult.cancelled(cancelled)) return cancelledResult(false);
        int seconds = CommandResult.timeout(requestedSeconds);
        boolean dispatched = false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            socket.setSoTimeout(250);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            byte[] clientNonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(clientNonce);
            writeFrame(out, join(HELLO, clientNonce));
            long helloDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            byte[] challenge = readFrame(in, helloDeadline, cancelled, NONCE_BYTES + MAC_BYTES);
            if (challenge.length != NONCE_BYTES + MAC_BYTES)
                return CommandResult.rejected("Обнови и перезапусти tools/kent_bridge_server.py в Termux.");
            byte[] serverNonce = Arrays.copyOf(challenge, NONCE_BYTES);
            byte[] proof = Arrays.copyOfRange(challenge, NONCE_BYTES, challenge.length);
            byte[] key = token.getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(proof, signature(key, "server", clientNonce, serverNonce, new byte[0])))
                return CommandResult.rejected("Мост не подтвердил подлинность. Сверь токен и перезапусти сервер Termux.");

            JSONObject request = new JSONObject()
                    .put("mode", adb ? "adb" : "termux")
                    .put("command", command)
                    .put("workDir", directory)
                    .put("timeout", seconds);
            byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
            byte[] signedRequest = join(signature(key, "request", clientNonce, serverNonce, payload), payload);
            if (CommandResult.cancelled(cancelled)) return cancelledResult(false);
            dispatched = true;
            writeFrame(out, signedRequest);
            long responseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds + 8L);
            byte[] response = readFrame(in, responseDeadline, cancelled, MAX_REPLY);
            if (response.length <= MAC_BYTES) throw new IOException("Неполный ответ моста");
            byte[] responseMac = Arrays.copyOf(response, MAC_BYTES);
            byte[] responsePayload = Arrays.copyOfRange(response, MAC_BYTES, response.length);
            if (!MessageDigest.isEqual(responseMac, signature(key, "response", clientNonce, serverNonce, responsePayload)))
                throw new IOException("Подпись ответа моста не подтверждена");
            return decode(responsePayload, adb);
        } catch (BridgeCancelled ignored) {
            return cancelledResult(dispatched);
        } catch (SocketTimeoutException ignored) {
            return dispatched ? CommandResult.stopped(CommandResult.Status.TIMED_OUT, "Мост не вернул ответ вовремя.", true)
                    : CommandResult.rejected("Истекло ожидание подключения или подтверждения моста Termux.");
        } catch (IOException | JSONException | GeneralSecurityException invalid) {
            return dispatched ? CommandResult.stopped(CommandResult.Status.UNKNOWN, "Связь с мостом прервалась после отправки.", true)
                    : CommandResult.rejected("Нет подтверждённого моста. Запусти актуальный tools/kent_bridge_server.py в Termux.");
        }
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

    private static byte[] readFrame(DataInputStream in, long deadline, BooleanSupplier cancelled, int limit) throws IOException {
        byte[] header = new byte[4];
        readExact(in, header, deadline, cancelled);
        int size = ByteBuffer.wrap(header).getInt();
        if (size < 1 || size > limit) throw new IOException("Недопустимый размер ответа моста");
        byte[] frame = new byte[size];
        readExact(in, frame, deadline, cancelled);
        return frame;
    }

    private static void readExact(DataInputStream in, byte[] buffer, long deadline, BooleanSupplier cancelled) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            if (CommandResult.cancelled(cancelled)) throw new BridgeCancelled();
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
}
