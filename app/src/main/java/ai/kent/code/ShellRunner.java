package ai.kent.code;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Runs an explicitly approved command with the app UID. Cwd is not a sandbox. */
public final class ShellRunner {
    private static final int MAX_OUTPUT = 24_000;
    private final File workDir;

    public ShellRunner(File workDir) { this.workDir = workDir; }

    public String run(String command, int requestedSeconds, BooleanSupplier cancelled) throws IOException {
        return runResult(command, requestedSeconds, cancelled).display();
    }

    public CommandResult runResult(String command, int requestedSeconds, BooleanSupplier cancelled) throws IOException {
        CommandResult.validateCommand(command);
        if (CommandResult.cancelled(cancelled))
            return CommandResult.stopped(CommandResult.Status.CANCELLED, "Запуск отменён до создания процесса.", false);
        int seconds = CommandResult.timeout(requestedSeconds);
        File androidShell = new File("/system/bin/sh");
        File shell = androidShell.isFile() ? androidShell : new File("/bin/sh");
        Process process = new ProcessBuilder(shell.getPath(), "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        boolean truncated = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try (InputStream stream = process.getInputStream()) {
            process.getOutputStream().close();
            while (true) {
                int ready = stream.available();
                if (ready > 0) {
                    int read = stream.read(chunk, 0, Math.min(chunk.length, ready));
                    if (read < 0) break;
                    int allowed = Math.min(read, MAX_OUTPUT - capture.size());
                    capture.write(chunk, 0, allowed);
                    truncated |= read > allowed;
                } else if (!process.isAlive()) {
                    break;
                } else {
                    process.waitFor(25, TimeUnit.MILLISECONDS);
                }
                if (CommandResult.cancelled(cancelled)) {
                    process.destroyForcibly();
                    return CommandResult.stopped(CommandResult.Status.CANCELLED, output(capture, truncated), true);
                }
                if (System.nanoTime() >= deadline) {
                    process.destroyForcibly();
                    return CommandResult.stopped(CommandResult.Status.TIMED_OUT, output(capture, truncated), true);
                }
            }
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                return CommandResult.stopped(CommandResult.Status.TIMED_OUT, output(capture, truncated), true);
            }
            return CommandResult.exited(process.exitValue(), output(capture, truncated));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return CommandResult.stopped(CommandResult.Status.CANCELLED, output(capture, truncated), true);
        } catch (IOException e) {
            process.destroyForcibly();
            return CommandResult.stopped(CommandResult.Status.UNKNOWN, output(capture, truncated), true);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String output(ByteArrayOutputStream capture, boolean truncated) {
        return new String(capture.toByteArray(), StandardCharsets.UTF_8)
                + (truncated ? "\n[Вывод сокращён до 24 КБ]" : "");
    }
}
