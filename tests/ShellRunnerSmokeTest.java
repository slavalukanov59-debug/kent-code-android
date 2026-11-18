package ai.kent.code;

import java.io.File;
import java.nio.file.Files;

public final class ShellRunnerSmokeTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("kent-runner-").toFile();
        ShellRunner runner = new ShellRunner(root);
        String result = runner.run("printf 'KENT-RUNNER-OK'", 10, () -> false);
        if (!result.contains("Exit code: 0") || !result.contains("KENT-RUNNER-OK"))
            throw new AssertionError("Runner smoke test failed: " + result);
        System.out.println("ShellRunner smoke test: PASS");
    }
}
