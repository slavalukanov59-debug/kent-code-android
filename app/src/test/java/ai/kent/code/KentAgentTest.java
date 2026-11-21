package ai.kent.code;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class KentAgentTest {
    private static final String KEY = "opaque-key-for-agent-test";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void executesAPlanAndRecordsAReviewedWrite() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(temporary.newFolder());
        MissionStore missions = newStore();
        MissionStore.Mission mission = missions.begin("Подготовить документ", "openrouter", "model");
        ArrayDeque<JSONObject> responses = new ArrayDeque<>();
        responses.add(calls(tool("checkpoint", "mission_checkpoint", new JSONObject()
                .put("stage", "Документация").put("summary", "Прочитано задание").put("next", "Создать файл")
                .put("completed", 0).put("total", 1))));
        responses.add(calls(tool("write", "write_file", new JSONObject()
                .put("path", "README.md").put("content", "Результат\n"))));
        responses.add(response("stop", new JSONObject().put("content", "README.md сохранён")));
        TestEvents events = new TestEvents(true);
        new KentAgent(workspace, tokenMeter(), missions, events, (active, key, context) -> {
            assertEquals(mission.id, active.id);
            assertEquals(KEY, key);
            if (responses.size() == 1) {
                assertTrue(context.toString().contains("\"tool_call_id\":\"write\""));
                assertEquals("Результат\n", workspace.currentText("README.md"));
            }
            return responses.removeFirst();
        }).task(mission, mission.goal, false, KEY);

        assertEquals(1, events.approvals);
        assertTrue(events.preview.contains("+Результат"));
        MissionStore.Mission saved = missions.find(mission.id);
        assertEquals(MissionStore.REVIEW, saved.status);
        assertEquals("Документация", saved.stage);
        assertEquals(3, saved.calls);
        assertEquals("README.md сохранён", saved.result);
        assertTrue(saved.changedFiles.contains("README.md"));
        assertTrue(saved.canMarkVerified());
        missions.markVerified(mission.id);
        assertEquals(MissionStore.DONE, missions.find(mission.id).status);
    }

    @Test public void deletionAlwaysRequiresConsentAndShellIsUnavailable() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(temporary.newFolder());
        workspace.write("main.txt", "present");
        MissionStore missions = newStore();
        MissionStore.Mission mission = missions.begin("Сохранить проект", "ai_flex", "model");
        ArrayDeque<JSONObject> responses = new ArrayDeque<>();
        responses.add(calls(tool("delete", "delete_file", new JSONObject().put("path", "main.txt")),
                tool("shell", "run_command", new JSONObject().put("command", "echo ignored"))));
        responses.add(response("stop", new JSONObject().put("content", "Действия проверены")));
        TestEvents events = new TestEvents(false);
        new KentAgent(workspace, tokenMeter(), missions, events,
                (active, key, context) -> responses.removeFirst()).task(mission, mission.goal, true, KEY);
        assertEquals(1, events.approvals);
        assertEquals("present", workspace.read("main.txt"));
        MissionStore.Mission saved = missions.find(mission.id);
        assertEquals(MissionStore.REVIEW, saved.status);
        assertTrue(saved.events.stream().anyMatch(event ->
                "delete_file".equals(event.name) && MissionStore.DENIED.equals(event.outcome)));
        assertTrue(saved.events.stream().anyMatch(event ->
                "run_command".equals(event.name) && MissionStore.FAILED.equals(event.outcome)));
    }

    @Test public void incompleteProviderResponseCannotTriggerAnOperation() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(temporary.newFolder());
        MissionStore missions = newStore();
        MissionStore.Mission mission = missions.begin("Изменить проект", "ai_flex", "model");
        JSONObject truncated = response("length", new JSONObject().put("tool_calls", new JSONArray()
                .put(tool("write", "write_file", new JSONObject().put("path", "file.txt").put("content", "new")))));
        TestEvents events = new TestEvents(true);
        new KentAgent(workspace, tokenMeter(), missions, events, (active, key, context) -> truncated)
                .task(mission, mission.goal, true, KEY);

        assertEquals(0, events.approvals);
        assertNull(workspace.currentText("file.txt"));
        assertEquals(MissionStore.REVIEW, missions.find(mission.id).status);
    }

    private static JSONObject tool(String id, String name, JSONObject arguments) throws Exception {
        return new JSONObject().put("id", id).put("type", "function").put("function",
                new JSONObject().put("name", name).put("arguments", arguments.toString()));
    }

    private static JSONObject calls(JSONObject... tools) throws Exception {
        JSONArray items = new JSONArray();
        for (JSONObject tool : tools) items.put(tool);
        return response("tool_calls", new JSONObject().put("tool_calls", items));
    }

    private static JSONObject response(String reason, JSONObject message) throws Exception {
        return new JSONObject().put("model", "model").put("choices", new JSONArray().put(
                new JSONObject().put("finish_reason", reason).put("message", message)))
                .put("usage", new JSONObject().put("prompt_tokens", 30).put("completion_tokens", 6));
    }

    private static MissionStore newStore() {
        return new MissionStore(new MissionStore.Persistence() {
            private byte[] bytes;

            @Override public byte[] read() { return bytes; }
            @Override public void write(byte[] data) { bytes = data; }
        });
    }

    private static TokenMeter tokenMeter() {
        return new TokenMeter(ProviderAccountingTest.inMemoryPreferences());
    }

    private static final class TestEvents implements KentAgent.Events {
        final boolean decision;
        int approvals;
        String preview;
        final List<String> logs = new ArrayList<>();

        TestEvents(boolean decision) { this.decision = decision; }

        @Override public void log(String title, String message) { logs.add(message); }
        @Override public boolean approve(String title, String detail) {
            approvals++;
            preview = detail;
            return decision;
        }
        @Override public boolean cancelled() { return false; }
        @Override public String stopStatus() { return MissionStore.PAUSED; }
        @Override public void usage(TokenMeter.Snapshot snapshot) { }
        @Override public void missionChanged() { }
    }
}
