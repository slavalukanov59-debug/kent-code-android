package ai.kent.code;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** The observed outcome of a command, including loss of contact after dispatch. */
public final class CommandResult {
    public enum Status { EXITED, CHECKED, REJECTED, CANCELLED, TIMED_OUT, UNKNOWN }

    public final Status status;
    public final Integer exitCode;
    private final String output;
    private final boolean mayStillBeRunning;
    final String stdout;
    final String stderr;
    final String detail;

    private CommandResult(Status status, Integer exitCode, String output, boolean mayStillBeRunning) {
        this(status, exitCode, output, mayStillBeRunning, "", "", output);
    }

    private CommandResult(Status status, Integer exitCode, String output, boolean mayStillBeRunning,
                          String stdout, String stderr, String detail) {
        this.status = status;
        this.exitCode = exitCode;
        this.output = output;
        this.mayStillBeRunning = mayStillBeRunning;
        this.stdout = stdout;
        this.stderr = stderr;
        this.detail = detail;
    }

    static CommandResult exited(int exitCode, String output) {
        return exited(exitCode, output, false);
    }

    static CommandResult exited(int exitCode, String output, boolean mayStillBeRunning) {
        return new CommandResult(Status.EXITED, exitCode, output, mayStillBeRunning);
    }

    static CommandResult rejected(String reason) {
        return new CommandResult(Status.REJECTED, null, reason, false);
    }

    static CommandResult stopped(Status status, String reason, boolean mayStillBeRunning) {
        return new CommandResult(status, null, reason, mayStillBeRunning);
    }

    static CommandResult observed(Status status, Integer exitCode, String stdout, String stderr,
                                  String detail, boolean mayStillBeRunning) {
        String combined = stdout;
        if (!stderr.isEmpty()) combined += "\nСтандартный поток ошибок:\n" + stderr;
        if (!detail.isEmpty()) combined += (combined.isEmpty() ? "" : "\n") + detail;
        return new CommandResult(status, exitCode, combined, mayStillBeRunning, stdout, stderr, detail);
    }

    public boolean succeeded() { return status == Status.EXITED && exitCode == 0 && !mayStillBeRunning; }

    public boolean mayStillBeRunning() { return mayStillBeRunning; }

    public String display() {
        String header;
        switch (status) {
            case EXITED: header = "exitCode=" + exitCode; break;
            case CHECKED: header = "ИНСТРУМЕНТЫ ПРОВЕРЕНЫ"; break;
            case REJECTED: header = "ЗАПУСК ОТКЛОНЁН"; break;
            case CANCELLED: header = mayStillBeRunning ? "ОЖИДАНИЕ ОТМЕНЕНО" : "ОТМЕНЕНО"; break;
            case TIMED_OUT: header = "ТАЙМАУТ"; break;
            default: header = "РЕЗУЛЬТАТ НЕИЗВЕСТЕН";
        }
        return header + (mayStillBeRunning ? " · команда могла продолжить работу" : "")
                + (output.isEmpty() ? "" : "\n" + output);
    }

    static boolean cancelled(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
    }

    static int timeout(int seconds) { return Math.max(1, Math.min(120, seconds)); }

    static void validateCommand(String command) throws IOException {
        if (command == null || command.length() > 5000 || command.indexOf('\0') >= 0 ||
                command.codePoints().allMatch(Character::isWhitespace)) {
            throw new IOException("Команда пуста, содержит NUL или превышает 5000 символов");
        }
    }

    static String workDir(String workDir, boolean adb) throws IOException {
        if (workDir == null || workDir.isEmpty() || workDir.equals(".")) return ".";
        if (adb || workDir.length() > 1024 || workDir.startsWith("/") || workDir.contains("\\")) {
            throw new IOException("Укажи относительный путь внутри домашней папки Termux");
        }
        for (String segment : workDir.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") ||
                    segment.chars().anyMatch(Character::isISOControl)) {
                throw new IOException("Недопустимый путь к рабочей папке Termux");
            }
        }
        return workDir;
    }
}
