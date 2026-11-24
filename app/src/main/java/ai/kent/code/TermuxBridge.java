package ai.kent.code;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private static final ComponentName RUN_COMMAND_SERVICE = new ComponentName(
            TERMUX_PACKAGE, "com.termux.app.RunCommandService");
    private static final int MAX_OUTPUT = 16000;
    private static final String SELECT_DIRECTORY =
            "cd -P -- \"$HOME\" || exit 125\n" +
            "root=$PWD\n" +
            "cd -P -- \"./$1\" || exit 125\n" +
            "case \"$PWD/\" in \"$root/\"*) ;; *) printf '%s\\n' 'Рабочая папка вне Termux HOME' >&2; exit 125;; esac\n" +
            "exec \"$BASH\" -c \"$2\"";
    private static final String RISH = "RISH_APPLICATION_ID=com.termux exec \"$HOME/rish\" -c \"$1\"";
    private static final String PREFIX_BIN = "/data/data/com.termux/files/usr/bin/";
    private static final String SELECT_ARGV_DIRECTORY =
            "cd -P -- \"$HOME\" || exit 125\n" +
            "root=$PWD\n" +
            "cd -P -- \"./$1\" || exit 125\n" +
            "case \"$PWD/\" in \"$root/\"*) ;; *) exit 125;; esac\n" +
            "root=$PWD; seconds=$2; count=$3; shift 3\n" +
            "while (( count > 0 )); do\n" +
            "  resolved=$(" + PREFIX_BIN + "realpath -e -- \"$1\") || exit 125\n" +
            "  case \"$resolved\" in \"$root/\"*) ;; *) exit 125;; esac\n" +
            "  test -f \"$resolved\" || exit 125\n" +
            "  shift; (( count-- ));\n" +
            "done\n" +
            "test -x \"$1\" || { printf 'Инструмент не найден: %s\\n' \"$1\" >&2; exit 127; }\n" +
            "test -x " + PREFIX_BIN + "timeout || { printf '%s\\n' 'Утилита Termux timeout недоступна' >&2; exit 125; }\n" +
            "exec " + PREFIX_BIN + "timeout --kill-after=2s \"${seconds}s\" \"$@\"";
    private static final ConcurrentHashMap<String, Pending> ACTIVE = new ConcurrentHashMap<>();

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        volatile CommandResult result;
    }

    interface BackgroundStart extends AutoCloseable {
        CommandResult completed();
        @Override void close();
    }

    private static final class ServiceRequest implements BackgroundStart {
        final String id;
        final Pending pending;
        final PendingIntent callback;
        final Intent intent;

        ServiceRequest(String id, Pending pending, PendingIntent callback, Intent intent) {
            this.id = id;
            this.pending = pending;
            this.callback = callback;
            this.intent = intent;
        }

        @Override public CommandResult completed() { return pending.result; }

        @Override public void close() {
            ACTIVE.remove(id, pending);
            callback.cancel();
        }
    }

    private final Context context;

    public TermuxBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean hasPermission() {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    boolean installed() {
        try {
            context.getPackageManager().getApplicationInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    boolean runCommandAvailable() {
        try {
            ServiceInfo info = context.getPackageManager().getServiceInfo(RUN_COMMAND_SERVICE, 0);
            return info.exported && info.enabled && info.applicationInfo.enabled && PERMISSION.equals(info.permission);
        } catch (PackageManager.NameNotFoundException | SecurityException missing) {
            return false;
        }
    }

    /** Caller checks consent for the fixed helper and keeps a visible Activity. */
    BackgroundStart startLocalBridge(BooleanSupplier cancelled) throws IOException {
        if (!runCommandAvailable()) throw new IOException("Эта установка Termux не предоставляет RUN_COMMAND.");
        if (!hasPermission()) throw new IOException("Выдайте Kent Code разрешение RUN_COMMAND в Android.");
        String[] arguments = {"-I", "-u", ".kent-bridge/server.py"};
        ServiceRequest request = request(PREFIX_BIN + "python3", arguments, "KENT · Локальный мост");
        try {
            start(request, cancelled);
            return request;
        } catch (IOException | RuntimeException failure) {
            request.close();
            throw failure;
        }
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
        String[] arguments = adb ? new String[]{"-c", RISH, "kent-rish", command}
                : directory.equals(".") ? new String[]{"-c", command}
                : new String[]{"-c", SELECT_DIRECTORY, "kent-directory", directory, command};
        return dispatch(arguments, adb, CommandResult.timeout(timeoutSeconds), cancelled);
    }

    CommandResult runAllowed(TerminalCommand command, String directory, int requested,
                             BooleanSupplier cancelled) throws IOException {
        if (command.needsSnapshot)
            return CommandResult.rejected("Этой проверке нужна временная копия проекта и локальный мост.");
        int timeout = CommandResult.timeout(requested);
        List<String> args = new ArrayList<>(Arrays.asList(
                "-c", SELECT_ARGV_DIRECTORY, "kent-developer", CommandResult.workDir(directory, false),
                String.valueOf(timeout), String.valueOf(command.inputs.size())));
        args.addAll(command.inputs);
        args.add(PREFIX_BIN + command.program);
        args.addAll(command.argv.subList(1, command.argv.size()));
        CommandResult completed = dispatch(args.toArray(new String[0]), false, timeout + 4, cancelled);
        if (completed.status == CommandResult.Status.EXITED && completed.exitCode == 124) {
            return CommandResult.observed(CommandResult.Status.TIMED_OUT, completed.exitCode,
                    completed.stdout, completed.stderr, "Termux timeout завершил ожидание команды.", false);
        }
        return completed;
    }

    private CommandResult dispatch(String[] arguments, boolean adb, int timeout,
                                   BooleanSupplier cancelled) throws IOException {
        if (!hasPermission())
            return CommandResult.rejected("Выдайте Kent Code разрешение RUN_COMMAND в Android.");
        if (CommandResult.cancelled(cancelled))
            return CommandResult.stopped(CommandResult.Status.CANCELLED, "Запуск отменён до отправки.", false);
        try (ServiceRequest request = request(BASH, arguments, adb ? "KENT · Shizuku ADB" : "KENT · Termux")) {
            try {
                start(request, cancelled);
            } catch (IOException failure) {
                if (CommandResult.cancelled(cancelled))
                    return CommandResult.stopped(CommandResult.Status.CANCELLED, "Запуск отменён до отправки.", false);
                return CommandResult.rejected(failure.getMessage());
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            while (true) {
                if (request.pending.done.await(200, TimeUnit.MILLISECONDS)) return request.completed();
                if (CommandResult.cancelled(cancelled))
                    return CommandResult.stopped(CommandResult.Status.CANCELLED, "Официальный мост прекращает ожидание.", true);
                if (System.nanoTime() >= end)
                    return CommandResult.stopped(CommandResult.Status.TIMED_OUT, "Termux не ответил за " + timeout + " с.", true);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CommandResult.stopped(CommandResult.Status.CANCELLED, "Ожидание прервано.", true);
        }
    }

    private ServiceRequest request(String executable, String[] arguments, String label) {
        // A callback from a previous process must never match a newer request.
        Uri requestId = Uri.fromParts("kent-termux-result", UUID.randomUUID().toString(), null);
        String id = requestId.toString();
        Pending pending = new Pending();
        Intent callback = new Intent(context, TermuxResultReceiver.class).setData(requestId);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, callback, flags);

        Intent request = new Intent("com.termux.RUN_COMMAND").setComponent(RUN_COMMAND_SERVICE);
        request.putExtra("com.termux.RUN_COMMAND_PATH", executable);
        request.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments);
        request.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        request.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        request.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label);
        request.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);
        ACTIVE.put(id, pending);
        return new ServiceRequest(id, pending, pi, request);
    }

    private void start(ServiceRequest request, BooleanSupplier cancelled) throws IOException {
        try {
            if (CommandResult.cancelled(cancelled)) throw new IOException("Запуск отменён до отправки.");
            if (context.startService(request.intent) == null)
                throw new IOException("Служба Termux RUN_COMMAND недоступна в этой установке.");
        } catch (SecurityException ex) {
            throw new IOException("Android отклонил доступ к RUN_COMMAND. Проверьте разрешение приложения.", ex);
        } catch (IllegalStateException ex) {
            throw new IOException("Android не разрешил запуск службы Termux. Повторите из открытого Kent Code или запустите мост вручную.", ex);
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
            String stdout = b.getString("stdout", "");
            String stderr = b.getString("stderr", "");
            String detail = clipOutput(b.getString("errmsg", ""), 2048);
            int half = MAX_OUTPUT / 2;
            boolean truncated = stdout.length() > half || stderr.length() > half
                    || wasTruncated(b.getString("stdout_original_length"), stdout)
                    || wasTruncated(b.getString("stderr_original_length"), stderr);
            if (truncated) detail += (detail.isEmpty() ? "" : "\n") + "[Вывод сокращён]";
            stdout = clipOutput(stdout, half);
            stderr = clipOutput(stderr, half);
            int exit = b.getInt("exitCode", -1);
            int internalError = b.getInt("err", Activity.RESULT_OK);
            if (exit >= 0 && internalError == Activity.RESULT_OK)
                pending.result = CommandResult.observed(CommandResult.Status.EXITED, exit, stdout, stderr, detail, false);
            else pending.result = CommandResult.observed(CommandResult.Status.UNKNOWN, null, stdout, stderr,
                    "Код Termux: " + internalError + "\n" + detail, true);
        } catch (RuntimeException invalid) {
            pending.result = CommandResult.stopped(CommandResult.Status.UNKNOWN, "Termux передал повреждённый ответ.", true);
        } finally {
            pending.done.countDown();
        }
    }

    private static String clipOutput(String value, int limit) {
        if (value == null) return "";
        int end = Math.min(value.length(), limit);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
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
