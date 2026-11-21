package ai.kent.code;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Official Termux RUN_COMMAND intent bridge (Termux >= 0.109).
 * Uses the installed Termux environment. The optional rish path uses the
 * separately configured Shizuku service with its currently granted identity.
 *
 * A call must be approved in the caller BEFORE entering run().
 */
public final class TermuxBridge {
    public static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final int MAX_OUTPUT = 16000;
    private static final String SELECT_DIRECTORY =
            "cd -P -- \"$HOME\" || exit 125\n" +
            "root=$PWD\n" +
            "cd -P -- \"./$1\" || exit 125\n" +
            "case \"$PWD/\" in \"$root/\"*) ;; *) printf '%s\\n' 'Рабочая папка вне Termux HOME' >&2; exit 125;; esac\n" +
            "exec \"$BASH\" -c \"$2\"";
    private static final String RISH = "RISH_APPLICATION_ID=com.termux exec \"$HOME/rish\" -c \"$1\"";
    private static final ConcurrentHashMap<String, Pending> ACTIVE = new ConcurrentHashMap<>();

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        volatile CommandResult result;
    }

    private final Context context;

    public TermuxBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean hasPermission() {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public String runTermux(String command, int timeoutSeconds, BooleanSupplier cancelled) throws IOException {
        return runResult(command, false, null, timeoutSeconds, cancelled).display();
    }

    public String runAdb(String command, int timeoutSeconds, BooleanSupplier cancelled) throws IOException {
        return runResult(command, true, null, timeoutSeconds, cancelled).display();
    }

    public CommandResult runResult(String command, boolean adb, String workingDirectory, int timeoutSeconds,
                                   BooleanSupplier cancelled) throws IOException {
        CommandResult.validateCommand(command);
        String directory = CommandResult.workDir(workingDirectory, adb);
        if (!hasPermission())
            return CommandResult.rejected("Выдай KENT CODE разрешение RUN_COMMAND в настройках Android.");
        if (CommandResult.cancelled(cancelled))
            return CommandResult.stopped(CommandResult.Status.CANCELLED, "Запуск отменён до отправки.", false);
        int timeout = CommandResult.timeout(timeoutSeconds);
        // A result delivered after process restart must never match a newer request.
        Uri requestId = Uri.fromParts("kent-termux-result", UUID.randomUUID().toString(), null);
        String id = requestId.toString();
        Pending pending = new Pending();

        // Pass user input as separate argv elements, including arguments to rish.
        String[] arguments = adb ? new String[]{"-c", RISH, "kent-rish", command}
                : directory.equals(".") ? new String[]{"-c", command}
                : new String[]{"-c", SELECT_DIRECTORY, "kent-directory", directory, command};
        Intent callback = new Intent(context, TermuxResultReceiver.class).setData(requestId);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, callback, flags);

        Intent request = new Intent("com.termux.RUN_COMMAND");
        request.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        request.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        request.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments);
        request.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        request.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        request.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", adb ? "KENT · Shizuku ADB" : "KENT · Termux");
        request.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);

        ACTIVE.put(id, pending);
        try {
            if (CommandResult.cancelled(cancelled))
                return CommandResult.stopped(CommandResult.Status.CANCELLED, "Запуск отменён до отправки.", false);
            if (context.startService(request) == null)
                return CommandResult.rejected("Служба Termux не найдена. Проверь установку и разрешение внешних команд.");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            while (true) {
                if (pending.done.await(200, TimeUnit.MILLISECONDS)) return pending.result;
                if (CommandResult.cancelled(cancelled))
                    return CommandResult.stopped(CommandResult.Status.CANCELLED, "Официальный мост прекращает ожидание.", true);
                if (System.nanoTime() >= end)
                    return CommandResult.stopped(CommandResult.Status.TIMED_OUT, "Termux не ответил за " + timeout + " с.", true);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CommandResult.stopped(CommandResult.Status.CANCELLED, "Ожидание прервано.", true);
        } catch (SecurityException ex) {
            return CommandResult.rejected("Termux отклонил доступ. Проверь RUN_COMMAND и allow-external-apps=true.");
        } catch (IllegalStateException ex) {
            return CommandResult.rejected("Android не запустил службу Termux. Открой Termux и KENT CODE на экране.");
        } finally {
            ACTIVE.remove(id);
            pi.cancel();
        }
    }

    static void accept(Intent intent) {
        if (intent == null || intent.getDataString() == null) return;
        Pending pending = ACTIVE.get(intent.getDataString());
        if (pending == null) return;
        try {
            Bundle b = intent.getBundleExtra("result");
            if (b == null) {
                pending.result = CommandResult.stopped(CommandResult.Status.UNKNOWN, "Termux не передал результат.", true);
                return;
            }
            StringBuilder output = new StringBuilder();
            String stdout = b.getString("stdout", "");
            String stderr = b.getString("stderr", "");
            boolean truncated = appendOutput(output, "", stdout);
            truncated |= appendOutput(output, "\nСтандартный поток ошибок:\n", stderr);
            truncated |= appendOutput(output, "\nОшибка Termux: ", b.getString("errmsg", ""));
            if (truncated || wasTruncated(b.getString("stdout_original_length"), stdout)
                    || wasTruncated(b.getString("stderr_original_length"), stderr))
                output.append("\n[Вывод сокращён]");
            int exit = b.getInt("exitCode", -1);
            int internalError = b.getInt("err", Activity.RESULT_OK);
            if (exit >= 0 && internalError == Activity.RESULT_OK)
                pending.result = CommandResult.exited(exit, output.toString());
            else pending.result = CommandResult.stopped(CommandResult.Status.UNKNOWN,
                    "Код Termux: " + internalError + "\n" + output, true);
        } catch (RuntimeException invalid) {
            pending.result = CommandResult.stopped(CommandResult.Status.UNKNOWN, "Termux передал повреждённый ответ.", true);
        } finally {
            pending.done.countDown();
        }
    }

    private static boolean appendOutput(StringBuilder output, String label, String value) {
        if (value == null || value.isEmpty()) return false;
        if (output.length() + label.length() >= MAX_OUTPUT) return true;
        output.append(label);
        int end = Math.min(value.length(), MAX_OUTPUT - output.length());
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        output.append(value, 0, end);
        return end < value.length();
    }

    private static boolean wasTruncated(String originalLength, String value) {
        if (originalLength == null) return false;
        try {
            return Integer.parseInt(originalLength) > (value == null ? 0 : value.length());
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
