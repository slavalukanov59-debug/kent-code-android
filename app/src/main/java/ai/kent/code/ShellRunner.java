package ai.kent.code;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Executes commands with the normal Android app UID, not root. NEVER call this
 * before showing the user the entire command and receiving one-time approval.
 * Commands can access private app files, so automatic approval is not supported.
 */
public final class ShellRunner {
    private static final int MAX_OUTPUT = 24_000;
    private final File workDir;

    public ShellRunner(File workDir) { this.workDir = workDir; }

    public String run(String command, int requestedSeconds, BooleanSupplier cancelled) throws IOException {
        if (command == null || command.isBlank() || command.length() > 5000 || command.indexOf('\0') >= 0)
            throw new IOException("Команда пуста или некорректна (максимум 5000 символов)");
        int seconds = Math.max(1, Math.min(120, requestedSeconds));
        File androidShell = new File("/system/bin/sh");
        File shell = androidShell.isFile() ? androidShell : new File("/bin/sh");
        ProcessBuilder pb = new ProcessBuilder(shell.getPath(), "-c", command);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        final ByteArrayOutputStream capture = new ByteArrayOutputStream();
        final boolean[] truncated = {false};
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = stream.read(chunk)) >= 0) {
                    synchronized (capture) {
                        int allowed = Math.max(0, MAX_OUTPUT - capture.size());
                        if (n > allowed) truncated[0] = true;
                        if (allowed > 0) capture.write(chunk, 0, Math.min(n, allowed));
                    }
                }
            } catch (IOException ignored) { }
        }, "kent-command-output");
        reader.setDaemon(true);
        reader.start();
        boolean done = false;
        boolean stopped = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try {
            while (!(done = process.waitFor(150, TimeUnit.MILLISECONDS))) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    stopped = true;
                    break;
                }
                if (System.nanoTime() >= deadline) break;
            }
            if (!done) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            reader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Команда прервана", e);
        } finally {
            process.destroy();
        }
        String output;
        synchronized (capture) { output = new String(capture.toByteArray(), StandardCharsets.UTF_8); }
        String status = stopped ? "ОСТАНОВЛЕНО" : done ? "Exit code: " + process.exitValue() : "ТАЙМАУТ: " + seconds + " сек";
        if (truncated[0]) output += "\n[Вывод сокращён до 24 KB]";
        return status + "\n" + (output.isBlank() ? "[Нет вывода]" : output);
    }
}
