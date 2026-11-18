package ai.kent.code;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Official Termux RUN_COMMAND intent bridge (Termux >= 0.109).
 * Runs in the Termux UID, or via the user's existing rish/Shizuku shell.
 * No root, no privileged API, and no silent execution by the agent.
 *
 * A call must be approved in the caller BEFORE entering run().
 */
public final class TermuxBridge {
    public static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RESULT_ID = "kent_bridge_request_id";
    private static final int MAX_OUTPUT = 16000;
    private static final AtomicInteger NEXT = new AtomicInteger(1000);
    private static final ConcurrentHashMap<Integer, Pending> ACTIVE = new ConcurrentHashMap<>();

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String result;
    }

    private final Context context;

    public TermuxBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean hasPermission() {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public String runTermux(String command, int timeoutSeconds, BooleanSupplier cancelled) throws IOException {
        return run(command, false, timeoutSeconds, cancelled);
    }

    public String runAdb(String command, int timeoutSeconds, BooleanSupplier cancelled) throws IOException {
        return run(command, true, timeoutSeconds, cancelled);
    }

    private String run(String command, boolean adb, int timeoutSeconds, BooleanSupplier cancelled) throws IOException {
        if (command == null || command.isBlank() || command.length() > 5000 || command.indexOf('\0') >= 0)
            throw new IOException("Команда пуста, слишком длинная (>5000) или содержит NUL");
        if (!hasPermission())
            throw new IOException("Нет разрешения 'Run commands in Termux environment' у KENT CODE. Открой BRIDGE и выдай разрешение.");
        if (cancelled.getAsBoolean()) return "Отменено до запуска";
        int timeout = Math.max(1, Math.min(120, timeoutSeconds));
        int id = NEXT.incrementAndGet();
        Pending pending = new Pending();
        ACTIVE.put(id, pending);

        // Passing commands as ONE bash argument, not comma-separated arguments.
        // Quoting is critical here: the command must reach rish -c unchanged.
        String script = adb
                ? "RISH_APPLICATION_ID=com.termux \"$HOME/rish\" -c " + shellQuote(command)
                : command;
        Intent callback = new Intent(context, TermuxResultService.class);
        callback.putExtra(RESULT_ID, id);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getService(context, id, callback, flags);

        Intent request = new Intent("com.termux.RUN_COMMAND");
        request.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        request.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        request.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", script});
        request.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        request.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        request.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", adb ? "KENT · Shizuku ADB" : "KENT · Termux");
        request.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);

        try {
            if (context.startService(request) == null)
                throw new IOException("Служба Termux не найдена. Установи Termux и разреши внешние команды.");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            while (true) {
                if (pending.done.await(200, TimeUnit.MILLISECONDS)) return pending.result;
                if (cancelled.getAsBoolean()) return "Ожидание отменено. ВНИМАНИЕ: уже запущенная команда в Termux может продолжить работу.";
                if (System.nanoTime() >= end)
                    return "Ожидание ответа истекло (" + timeout + " с). Команда могла продолжить работу в Termux. Проверь настройки разрешений, фоновой работы и Shizuku.";
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "Ожидание прервано; команда могла продолжить работу.";
        } catch (SecurityException ex) {
            throw new IOException("Termux отклонил доступ. Выдай KENT CODE разрешение RUN_COMMAND и включи allow-external-apps=true.", ex);
        } catch (IllegalStateException ex) {
            throw new IOException("Android не разрешил запуск службы Termux из фона. Открой Termux и KENT CODE на экране, повтори.", ex);
        } finally {
            ACTIVE.remove(id);
            pi.cancel();
        }
    }

    // Keep shell command a single literal argument when delegating to rish.
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static void accept(Intent intent) {
        if (intent == null) return;
        Pending pending = ACTIVE.get(intent.getIntExtra(RESULT_ID, -1));
        if (pending == null) return;
        Bundle b = intent.getBundleExtra("result");
        if (b == null) pending.result = "Termux не вернул результат. Проверь версию Termux (>=0.109).";
        else {
            String out = b.getString("stdout", "");
            String errOut = b.getString("stderr", "");
            String error = b.getString("errmsg", "");
            int exit = b.getInt("exitCode", -1);
            int internalError = b.getInt("err", -1);
            String data = "exitCode=" + exit + " · termuxErr=" + internalError + "\n"
                    + (out.isEmpty() ? "" : out)
                    + (errOut.isEmpty() ? "" : "\nSTDERR:\n" + errOut)
                    + (error.isEmpty() ? "" : "\nTermux error: " + error);
            pending.result = data.length() > MAX_OUTPUT ? data.substring(0, MAX_OUTPUT) + "\n[Вывод сокращён до 16 KB]" : data;
        }
        pending.done.countDown();
    }
}
