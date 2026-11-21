package ai.kent.code;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;

import static org.junit.Assert.*;

public class AgentSafetyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void scopesPathsAndPreventsReplacingUnreviewedState() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(temporary.newFolder());
        workspace.write("src/Main.java", "old");
        assertFalse(workspace.applyReviewedChange("src/Main.java", "stale", "next"));
        assertFalse(workspace.applyReviewedChange("src/Main.java", null, "next"));
        assertEquals("old", workspace.read("src/Main.java"));
        assertTrue(workspace.applyReviewedChange("src/Main.java", "old", "next"));
        assertFalse(workspace.applyReviewedChange("src/Main.java", "old", null));
        assertTrue(workspace.applyReviewedChange("src/Main.java", "next", null));
        assertNull(workspace.currentText("src/Main.java"));
        blocked(() -> workspace.read("src/../.env"));
        blocked(() -> workspace.write("src/line\nbreak", "invalid path"));
    }

    @Test public void hidesSecretFilesAndSymlinksFromAgent() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(temporary.newFolder());
        assertTrue(workspace.filePaths().isEmpty());
        Files.write(workspace.root().toPath().resolve(".env"), "PRIVATE".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(workspace.root().toPath().resolve("alias.txt"), workspace.root().toPath().resolve(".env"));
        Files.write(workspace.root().toPath().resolve("binary"), new byte[] {(byte) 0xff});
        blocked(() -> workspace.read(".env"));
        blocked(() -> workspace.read("alias.txt"));
        blocked(() -> workspace.read("binary"));
        assertFalse(workspace.tree().contains(".env"));
        assertFalse(workspace.tree().contains("alias.txt"));
    }

    @Test public void previewPreservesNewlineChangesAndNewFiles() throws Exception {
        assertEquals("", WorkDiff.between("file.txt", "same", "same"));
        String patch = WorkDiff.between("file.txt", "ab", "ab\n");
        assertTrue(patch.contains("-ab\n\\ No newline at end of file\n+ab\n"));
        assertTrue(WorkDiff.between("file.txt", null, "").contains("--- /dev/null"));
        assertTrue(WorkDiff.between("file.txt", "first\nstart\n", "first\nafter\n")
                .contains(" first\n-start\n+after\n"));
    }

    @Test public void protectsCredentialsInContextAndJournals() {
        String key = "opaque-test-key-for-local-only";
        assertEquals("key=[секрет скрыт]", SensitiveData.redact("key=" + key, key));
        assertEquals("[секрет скрыт]", SensitiveData.redact("Bearer " + "a".repeat(22)));
        assertEquals("[секрет скрыт]", SensitiveData.redact("ghp_" + "x".repeat(23)));
        assertEquals("git status", SensitiveData.redact("git status"));
    }

    @Test public void stopsRepeatedBatchesWhileAllowingLongProgress() {
        ArrayDeque<String> recent = new ArrayDeque<>();
        for (int index = 0; index < 550; index++) assertTrue(KentAgent.recordProgress(recent, "batch" + index));
        for (int index = 0; index < 3; index++) {
            assertTrue(KentAgent.recordProgress(recent, "again"));
            assertTrue(KentAgent.recordProgress(recent, "interleaved" + index));
        }
        assertFalse(KentAgent.recordProgress(recent, "again"));
    }

    @Test public void honorsRateLimitAndNeverRetriesPaymentOrAuthErrors() {
        assertTrue(ApiRetryPolicy.shouldRetry(429, "rate_limit_exceeded"));
        assertFalse(ApiRetryPolicy.shouldRetry(429, "insufficient_quota"));
        assertFalse(ApiRetryPolicy.shouldRetry(402, ""));
        assertFalse(ApiRetryPolicy.shouldRetry(401, ""));
        assertFalse(ApiRetryPolicy.shouldRetry(302, ""));
        assertEquals(9_000L, ApiRetryPolicy.retryAfterMillis("9", 0));
        assertEquals(10_000L, ApiRetryPolicy.retryAfterMillis("Thu, 1 Jan 1970 00:00:15 GMT", 5_000));
        assertEquals(0L, ApiRetryPolicy.retryAfterMillis("invalid", 0));
    }

    private static void blocked(ThrowingAction action) throws Exception {
        try {
            action.run();
            fail("The workspace should reject this operation");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }
}
