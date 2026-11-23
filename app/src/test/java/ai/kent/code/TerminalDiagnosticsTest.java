package ai.kent.code;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TerminalDiagnosticsTest {
    @Rule public final TemporaryFolder directory = new TemporaryFolder();

    @Test public void onlyKnownCommandsAndSafeSourceArgumentsReachTermux() throws Exception {
        assertThrows(IOException.class, () -> new TerminalCommand("bash", "version", Collections.emptyList()));
        assertThrows(IOException.class, () -> new TerminalCommand("g++", "compile", Arrays.asList("@args.cpp")));
        assertThrows(IOException.class, () -> new TerminalCommand("g++", "syntax", Arrays.asList("../hello.cpp")));
        assertThrows(IOException.class, () -> new TerminalCommand("gradle", "gradle", Arrays.asList("--init-script")));
        assertThrows(IOException.class, () -> new TerminalCommand("python3", "syntax", Arrays.asList(".git/hidden.py")));

        TerminalCommand command = new TerminalCommand("clang++", "compile", Arrays.asList("src/main's solution.cpp"));
        assertEquals(Arrays.asList("clang++", "-std=c++17", "-o", ".kent-check-program", "src/main's solution.cpp"), command.argv);
        assertTrue(command.display().contains("'src/main'\\''s solution.cpp'"));
        assertTrue(command.needsSnapshot);
        assertFalse(command.runsProjectCode);
        assertTrue(new TerminalCommand("python3", "tests", Collections.emptyList()).runsProjectCode);
    }

    @Test public void resultsPersistAndInterruptedJobsRecoverWithoutRerun() throws Exception {
        DiagnosticsStore first = new DiagnosticsStore(directory.getRoot());
        DiagnosticsStore.Record running = DiagnosticsStore.Record.starting("mission-id", "g++ test.cpp", "project")
                .running("sha");
        first.save(running);
        assertEquals("RUNNING", first.get(running.id).status);
        DiagnosticsStore reopened = new DiagnosticsStore(directory.getRoot());
        assertEquals("UNKNOWN", reopened.get(running.id).status);
        assertTrue(reopened.get(running.id).mayStillBeRunning);
        assertEquals(null, reopened.get(running.id).exitCode);

        DiagnosticsStore.Record failed = running.observed(CommandResult.observed(
                CommandResult.Status.EXITED, 1, "", "test.cpp:1:2: error: missing ;", "", false), "sha");
        reopened.save(failed);
        assertEquals("test.cpp:1:2: error: missing ;", reopened.records().get(0).stderr);
        assertTrue(reopened.previousFailure("mission-id", "sha"));
        assertFalse(reopened.previousFailure("new-mission", "sha"));
        DiagnosticsStore.Record repeated = running.observed(CommandResult.observed(
                CommandResult.Status.EXITED, 1, "", "test.cpp:25:3: error: missing ;", "", false), "different-sha");
        assertEquals(failed.failureKey(), repeated.failureKey());
        assertNotEquals("", failed.failureKey());
    }

    @Test public void cancellationResponseKeepsObservedStreamsAndExit() throws Exception {
        byte[] payload = ("{\"status\":\"cancelled\",\"exitCode\":-9,\"detached\":false,"
                + "\"stdout\":\"started\",\"stderr\":\"diagnostic\",\"detail\":\"\"}").getBytes(StandardCharsets.UTF_8);
        CommandResult result = LocalTermuxBridge.developerResult(payload);
        assertEquals(CommandResult.Status.CANCELLED, result.status);
        assertEquals(Integer.valueOf(-9), result.exitCode);
        assertEquals("started", result.stdout);
        assertEquals("diagnostic", result.stderr);
        assertFalse(result.mayStillBeRunning());
        assertFalse(result.succeeded());
    }
}
