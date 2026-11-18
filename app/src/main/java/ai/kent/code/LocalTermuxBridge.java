package ai.kent.code;

import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/**
 * Authenticated on-device TCP fallback for Google Play Termux builds that do
 * not register com.termux.permission.RUN_COMMAND.
 * Never binds a server; only connects to Termux on 127.0.0.1.
 */
public final class LocalTermuxBridge {
    private static final int PORT = 47883;
    private static final int MAX_REPLY = 24000;
    private final SecureKeyStore secrets;

    public LocalTermuxBridge(SecureKeyStore secrets) { this.secrets = secrets; }

    public boolean configured() {
        try { return !secrets.bridgeToken().isBlank(); }
        catch (Exception ignored) { return false; }
    }

    public String run(String command, boolean adb, int requestedSeconds,
                      BooleanSupplier cancelled) throws IOException {
        String token;
        try { token = secrets.bridgeToken(); }
        catch (Exception e) { throw new IOException("Не удалось прочитать локальный токен", e); }
        if (!token.matches("[0-9a-f]{48}"))
            throw new IOException("Не задан корректный токен (48 символов). Открой вкладку BRIDGE.");
        if (command == null || command.isBlank() || command.length() > 5000 || command.indexOf('\0') >= 0)
            throw new IOException("Недопустимая команда");
        if (cancelled.getAsBoolean()) return "Отменено до запуска";
        int seconds = Math.max(1, Math.min(120, requestedSeconds));
        JSONObject obj = new JSONObject();
        try {
            obj.put("token", token);
            obj.put("mode", adb ? "adb" : "termux");
            obj.put("command", command);
            obj.put("timeout", seconds);
        } catch (Exception e) { throw new IOException("Не удалось собрать команду", e); }
        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            socket.setSoTimeout(500);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(bytes.length);
            out.write(bytes);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            long deadline = System.nanoTime() + (seconds + 8L) * 1_000_000_000L;
            int size = readInt(in, deadline, cancelled);
            if (size < 1 || size > MAX_REPLY) throw new IOException("Слишком большой или пустой ответ мостa");
            byte[] result = new byte[size];
            int offset = 0;
            while (offset < size) {
                if (cancelled.getAsBoolean()) return "Ожидание отменено. Команда может ещё выполняться в Termux.";
                if (System.nanoTime() > deadline) throw new IOException("Истекло ожидание ответа от Termux");
                try {
                    int n = in.read(result, offset, size - offset);
                    if (n < 0) throw new IOException("Termux закрыл соединение");
                    offset += n;
                } catch (java.net.SocketTimeoutException ignored) {}
            }
            try {
                JSONObject response = new JSONObject(new String(result, StandardCharsets.UTF_8));
                String error = response.optString("error", "");
                if (!error.isEmpty()) return "BRIDGE error: " + error;
                return "exitCode=" + response.optInt("exitCode", -1)
                        + (response.optBoolean("timeout", false) ? " (TIMEOUT)" : "")
                        + "\n" + response.optString("output", "");
            } catch (org.json.JSONException ex) {
                throw new IOException("Неверный JSON-ответ моста", ex);
            }
        } catch (java.net.ConnectException e) {
            throw new IOException("Локальный мост не запущен. В Termux: python tools/kent_bridge_server.py", e);
        }
    }

    private static int readInt(DataInputStream in, long deadline,
                               BooleanSupplier cancelled) throws IOException {
        byte[] head = new byte[4];
        int off = 0;
        while (off < 4) {
            if (cancelled.getAsBoolean()) throw new IOException("Ожидание отменено");
            if (System.nanoTime() > deadline) throw new IOException("Таймаут получения ответа");
            try {
                int n = in.read(head, off, 4 - off);
                if (n < 0) throw new IOException("Соединение закрыто");
                off += n;
            } catch (java.net.SocketTimeoutException ignored) {}
        }
        return ((head[0] & 255) << 24) | ((head[1] & 255) << 16) | ((head[2] & 255) << 8) | (head[3] & 255);
    }
}
