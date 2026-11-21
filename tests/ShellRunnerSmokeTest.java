package ai.kent.code;

import java.io.File;
import java.nio.file.Files;

public final class ShellRunnerSmokeTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("kent-runner-").toFile();
        ShellRunner runner = new ShellRunner(root);
        try {
            CommandResult success = runner.runResult("printf 'KENT-RUNNER-OK'", 10, () -> false);
            if (!success.succeeded() || !success.display().contains("KENT-RUNNER-OK"))
                throw new AssertionError("Unexpected exit: " + success.display());
            CommandResult failure = runner.runResult("exit 7", 10, () -> false);
            if (failure.succeeded() || failure.exitCode != 7)
                throw new AssertionError("Nonzero exit lost: " + failure.display());
            CommandResult cancelled = runner.runResult("touch marker", 10, () -> true);
            if (cancelled.status != CommandResult.Status.CANCELLED || cancelled.mayStillBeRunning()
                    || new File(root, "marker").exists())
                throw new AssertionError("Cancellation dispatched a process: " + cancelled.display());
            CommandResult timeout = runner.runResult("exec sleep 20", 1, () -> false);
            if (timeout.status != CommandResult.Status.TIMED_OUT || timeout.succeeded())
                throw new AssertionError("Timeout was lost: " + timeout.display());
            CommandResult noInput = runner.runResult("cat; printf CLOSED", 10, () -> false);
            if (!noInput.succeeded() || !noInput.display().contains("CLOSED"))
                throw new AssertionError("Child stdin was not closed: " + noInput.display());
            CommandResult large = runner.runResult("head -c 40000 /dev/zero | tr '\\000' a", 10, () -> false);
            if (!large.succeeded() || !large.display().contains("[Вывод сокращён")
                    || large.display().length() > 24_100)
                throw new AssertionError("Output exceeds its limit or silently truncates");
            if (!"one/two dirs".equals(CommandResult.workDir("one/two dirs", false)))
                throw new AssertionError("Valid project path was not preserved");
            for (String path : new String[]{"../other", "/tmp", "nested/../../outside", "nested//dir"}) {
                try {
                    CommandResult.workDir(path, false);
                    throw new AssertionError("Accepted unsafe path " + path);
                } catch (java.io.IOException expected) { }
            }
        } finally {
            Files.deleteIfExists(root.toPath());
        }
        System.out.println("ShellRunner smoke test: PASS");
    }
}
