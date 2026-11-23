package ai.kent.code;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class MissionStoreTest {
    private static final class MemoryFile implements MissionStore.Persistence {
        private byte[] bytes;
        private boolean failWrites;

        @Override public byte[] read() { return bytes; }

        @Override public void write(byte[] data) throws IOException {
            if (failWrites) throw new IOException("Testing failed write");
            bytes = data;
        }
    }

    @Test public void selectionDoesNotChangeRunningTaskAndQueuedTaskCanBeCancelled() {
        MemoryFile file = new MemoryFile();
        MissionStore store = new MissionStore(file);
        MissionStore.Mission active = store.begin("Подготовить проект", "ai_flex", "model");
        MissionStore.Mission waiting = store.create("Задача в очереди", "openrouter", "model2", "lab-01");
        assertEquals(MissionStore.WAITING, waiting.status);
        assertEquals(waiting.id, store.selected().id);
        assertEquals(active.id, store.active().id);
        assertThrows(IllegalStateException.class, () -> store.resume(waiting.id, "openrouter", "model2"));
        assertThrows(IllegalStateException.class, store::clear);
        store.cancel(waiting.id);
        store.noteResult("Проект готов");
        store.finish(MissionStore.DONE);
        assertNull(store.active());

        MissionStore restored = new MissionStore(file);
        assertEquals(MissionStore.DONE, restored.find(active.id).status);
        assertEquals("Проект готов", restored.find(active.id).result);
        assertEquals(MissionStore.CANCELLED, restored.find(waiting.id).status);
        assertEquals("lab-01", restored.find(waiting.id).workspaceId);
        assertThrows(IllegalStateException.class, () -> restored.resume(active.id, "openrouter", "model2"));
    }

    @Test public void unknownSideEffectRequiresSeparateReviewAndSurvivesRestart() throws Exception {
        MemoryFile file = new MemoryFile();
        MissionStore store = new MissionStore(file);
        MissionStore.Mission mission = store.begin("Обновить README", "ai_flex", "model");
        store.checkpoint(new JSONObject().put("stage", "Подготовка").put("summary", "Тесты исходной версии пройдены")
                .put("next", "Обновить инструкцию").put("completed", 1).put("total", 2)
                .put("decisions", "Хранить документы в корне"));
        store.noteStructure("README.md\nsrc/\n");
        store.startAction("write_file", "README.md", true);
        MissionStore restored = new MissionStore(file);
        restored.recoverInterrupted();
        MissionStore.Mission recovered = restored.find(mission.id);
        assertEquals(MissionStore.REVIEW, recovered.status);
        assertTrue(recovered.needsReview());
        assertFalse(recovered.canMarkVerified());
        assertThrows(IllegalStateException.class, () -> restored.markVerified(mission.id));
        assertTrue(recovered.resumePrompt().contains("Действие с неопределённым результатом write_file README.md"));
        assertTrue(recovered.resumePrompt().contains("Хранить документы в корне"));
        assertTrue(recovered.resumePrompt().contains("src/"));
        assertThrows(IllegalStateException.class, () -> restored.resume(mission.id, "ai_flex", "model"));

        MissionStore.Mission restarted = restored.resume(mission.id, "ai_flex", "model", true);
        assertEquals(2, restarted.attempts);
        assertFalse(restarted.needsReview());
        String action = restored.startAction("read_file", "README.md", false);
        restored.finishAction(action, MissionStore.SUCCEEDED, "Прочитано", false);
        restored.noteResult("Инструкция обновлена");
        restored.finish(MissionStore.REVIEW);
        assertTrue(restored.find(mission.id).canMarkVerified());
        restored.markVerified(mission.id);
        assertEquals(MissionStore.DONE, new MissionStore(file).find(mission.id).status);
    }

    @Test public void interruptedReadCanBeRestartedAndFailedIntentRollsBack() {
        MemoryFile file = new MemoryFile();
        MissionStore store = new MissionStore(file);
        MissionStore.Mission mission = store.begin("Чтение", "ai_flex", "m");
        file.failWrites = true;
        assertThrows(IllegalStateException.class, () -> store.startAction("write_file", "src/a", true));
        assertEquals(0, store.active().tools);
        file.failWrites = false;
        String reading = store.startAction("read_file", "src/a", false);
        MissionStore restored = new MissionStore(file);
        restored.recoverInterrupted();
        assertEquals(MissionStore.PAUSED, restored.find(mission.id).status);
        assertTrue(restored.find(mission.id).events.stream().anyMatch(event -> reading.equals(event.id)
                && MissionStore.INTERRUPTED.equals(event.outcome)));
        assertNotNull(restored.resume(mission.id, "ai_flex", "m"));
        restored.finish(MissionStore.CANCELLED);
        assertEquals(MissionStore.CANCELLED, restored.find(mission.id).status);
    }

    @Test public void compactionRetainsLatestChangeAndAggregatesRepeatedErrors() throws Exception {
        MemoryFile file = new MemoryFile();
        MissionStore store = new MissionStore(file);
        MissionStore.Mission mission = store.begin("Исправить проект", "ai_flex", "m");
        String first = store.startAction("write_file", "src/a", true);
        store.finishAction(first, MissionStore.SUCCEEDED, "Файл записан", true, "-old\n+new\n");
        for (int i = 0; i < 80; i++) {
            String action = store.startAction("read_file", "src/a", false);
            store.finishAction(action, MissionStore.FAILED, "Недоступно", false);
        }
        store.finish(MissionStore.ERROR);
        MissionStore restored = new MissionStore(file);
        MissionStore.Mission archived = restored.find(mission.id);
        assertEquals(List.of("src/a"), archived.changedFiles);
        assertTrue(archived.compactedEvents > 0);
        assertTrue(archived.report().contains("-old\n+new\n"));
        assertFalse(archived.resumePrompt().contains("-old\n+new\n"));
        assertTrue(restored.qualityReport().contains("Инструмент · read_file × 80"));
        assertTrue(restored.qualityReport().contains("read_file · src/a × 79"));
        ByteArrayOutputStream export = new ByteArrayOutputStream();
        restored.export(export);
        assertEquals(1, new JSONObject(export.toString(StandardCharsets.UTF_8.name())).getJSONArray("missions").length());
        restored.clear();
        assertTrue(new MissionStore(file).list().isEmpty());
    }

    @Test public void oldLedgerMigratesWithoutLabelingEstimatedUsageAsExact() {
        MemoryFile file = new MemoryFile();
        file.bytes = ("{\"missions\":[{\"id\":\"legacy\",\"status\":\"выполняется\",\"goal\":\"Задача\","
                + "\"tokens\":123,\"calls\":4}],\"selected\":\"legacy\"}").getBytes(StandardCharsets.UTF_8);
        MissionStore store = new MissionStore(file);
        store.recoverInterrupted();
        MissionStore.Mission mission = store.selected();
        assertEquals("legacy", mission.id);
        assertEquals("Задача", mission.goal);
        assertEquals(MissionStore.REVIEW, mission.status);
        assertEquals(123, mission.legacyTokenTotal);
        assertEquals(0, mission.tokens);
        assertEquals(4, mission.calls);
    }

    @Test public void developerPlanAndProjectSurviveInterruptionWithoutReplayingPendingWrites() throws Exception {
        MemoryFile file = new MemoryFile();
        MissionStore store = new MissionStore(file);
        String goal = "Исходная задача\n" + "а".repeat(18_000) + "\nВажное последнее требование";
        MissionStore.Mission mission = store.begin(goal, "ai_flex", "model", "", true, "first");
        assertEquals(MissionStore.STARTED, mission.phaseStatus.get(MissionStore.ANALYSIS));
        store.plan(new JSONObject().put("analysis", "Исходники Java").put("plan", "Проверить и исправить README")
                .put("deliverable", "project"));
        store.selectProject("second");
        store.recordPhase(MissionStore.EXECUTION, MissionStore.STARTED);
        String write = store.startAction("project_write", "README.md", true);
        store.finishAction(write, MissionStore.SUCCEEDED, "Сохранено", true, "+Инструкция", "Добавление инструкции");
        String unresolved = store.startAction("github_create_branch", "owner/repo · feature/check", true);

        MissionStore restored = new MissionStore(file);
        restored.recoverInterrupted();
        MissionStore.Mission recovered = restored.find(mission.id);
        assertTrue(recovered.developerMode);
        assertEquals("second", recovered.projectId);
        assertEquals("Проверить и исправить README", recovered.plan);
        assertEquals("project", recovered.deliverable);
        assertTrue(recovered.projectFilesChanged);
        assertEquals(MissionStore.SUCCEEDED, recovered.phaseStatus.get(MissionStore.PLAN));
        assertEquals(MissionStore.INTERRUPTED, recovered.phaseStatus.get(MissionStore.EXECUTION));
        assertTrue(recovered.resumePrompt().contains("Важное последнее требование"));
        assertTrue(recovered.events.stream().anyMatch(event -> write.equals(event.id)
                && "Добавление инструкции".equals(event.reason)));
        assertTrue(recovered.events.stream().anyMatch(event -> unresolved.equals(event.id)
                && MissionStore.UNKNOWN.equals(event.outcome)));
        assertThrows(IllegalStateException.class, () -> restored.resume(mission.id, "ai_flex", "model"));
    }

    @Test public void pendingDiagnosticRequiresReviewAndOldResultDoesNotVerifyLaterChanges() {
        MemoryFile file = new MemoryFile();
        MissionStore store = new MissionStore(file);
        MissionStore.Mission mission = store.begin("Проверить проект", "ai_flex", "model", "", true, "project");
        long priorCheck = System.currentTimeMillis() - 1;
        String write = store.startAction("project_edit", "Main.java", true);
        store.finishAction(write, MissionStore.SUCCEEDED, "Исправлено", true);
        assertFalse(store.recordDiagnostic("prior-job", priorCheck, MissionStore.SUCCEEDED));
        assertFalse(store.active().diagnosticCompleted);

        store.recordPhase(MissionStore.VERIFICATION, MissionStore.STARTED);
        store.pendingDiagnostic("running-job");
        MissionStore restored = new MissionStore(file);
        restored.recoverInterrupted();
        MissionStore.Mission recovered = restored.find(mission.id);
        assertEquals(MissionStore.REVIEW, recovered.status);
        assertEquals("running-job", recovered.pendingDiagnosticId);
        assertEquals(MissionStore.INTERRUPTED, recovered.phaseStatus.get(MissionStore.VERIFICATION));
        assertThrows(IllegalStateException.class, () -> restored.resume(mission.id, "ai_flex", "model"));
        restored.resume(mission.id, "ai_flex", "model", true);
        assertTrue(restored.active().pendingDiagnosticId.isEmpty());
    }

    @Test public void corruptedLedgerIsPreservedAndRawExportRemainsAvailable() throws Exception {
        MemoryFile file = new MemoryFile();
        file.bytes = "{broken".getBytes(StandardCharsets.UTF_8);
        MissionStore store = new MissionStore(file);
        assertTrue(store.loadFailed());
        assertThrows(IllegalStateException.class, () -> store.create("Задача", "ai_flex", "m"));
        assertThrows(IllegalStateException.class, store::clear);
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        store.export(exported);
        assertEquals("{broken", exported.toString(StandardCharsets.UTF_8.name()));
    }
}
