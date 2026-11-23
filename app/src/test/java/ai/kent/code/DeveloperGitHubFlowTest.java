package ai.kent.code;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.*;

public class DeveloperGitHubFlowTest {
    private static final String REPO = "owner/fitness";
    private static final String ROOT = "/repos/" + REPO;
    private static final String HEAD = "1111111111111111111111111111111111111111";
    private static final String TREE = "2222222222222222222222222222222222222222";
    private static final String FIX = "3333333333333333333333333333333333333333";
    private static final String BRANCH = "kent-count-fix";
    private static final String SOURCE = "def total(items):\n    return len(items) + 1\n";
    private static final String REPLACEMENT = "def total(items):\n    return len(items)\n";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void agentReadsSourceAndPublishesAReviewedCommitWithReceipt() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(temporary.newFolder());
        MissionStore missions = new MissionStore(new MissionStore.Persistence() {
            private byte[] bytes;
            @Override public byte[] read() { return bytes; }
            @Override public void write(byte[] data) { bytes = data; }
        });
        MissionStore.Mission mission = missions.begin("Проверь репозиторий и исправь подсчёт в отдельной ветке",
                "openrouter", "model", "", true, "");
        List<String> previews = new ArrayList<>();
        List<String> writes = new ArrayList<>();
        KentAgent.Events events = new KentAgent.Events() {
            @Override public boolean approve(String title, String detail) {
                assertEquals(writes.size(), previews.size());
                previews.add(detail);
                return true;
            }
            @Override public void log(String title, String message) { }
            @Override public boolean cancelled() { return false; }
            @Override public String stopStatus() { return MissionStore.PAUSED; }
            @Override public void usage(TokenMeter.Snapshot snapshot) { }
            @Override public void missionChanged() { }
        };
        byte[] original = SOURCE.getBytes(StandardCharsets.UTF_8);
        String blob = GitHubClient.blobId(original);
        GitHubClient github = new GitHubClient("ghp_testtoken0123456789abcdef", (method, path, body, token) -> {
            try {
                if ("POST".equals(method)) {
                    assertEquals(writes.size() + 1, previews.size());
                    writes.add(path);
                    if ((ROOT + "/git/refs").equals(path)) {
                        assertEquals(HEAD, body.getString("sha"));
                        assertEquals("refs/heads/" + BRANCH, body.getString("ref"));
                        return reference();
                    }
                    assertEquals("/graphql", path);
                    JSONObject input = body.getJSONObject("variables").getJSONObject("input");
                    assertEquals(HEAD, input.getString("expectedHeadOid"));
                    assertEquals(BRANCH, input.getJSONObject("branch").getString("branchName"));
                    JSONObject addition = input.getJSONObject("fileChanges").getJSONArray("additions").getJSONObject(0);
                    assertEquals("counter.py", addition.getString("path"));
                    assertEquals(REPLACEMENT, new String(Base64.getDecoder().decode(addition.getString("contents")),
                            StandardCharsets.UTF_8));
                    return new JSONObject().put("data", new JSONObject().put("createCommitOnBranch",
                            new JSONObject().put("commit", new JSONObject().put("oid", FIX)))).toString();
                }
                if (path.equals(ROOT)) return new JSONObject().put("default_branch", "main").toString();
                if (path.equals(ROOT + "/git/ref/heads/" + BRANCH)) {
                    if (writes.isEmpty()) throw new GitHubClient.ApiException(404, "Missing ref");
                    return reference();
                }
                if (path.equals(ROOT + "/commits/main")) {
                    return new JSONObject().put("sha", HEAD).put("commit", new JSONObject()
                            .put("tree", new JSONObject().put("sha", TREE))).toString();
                }
                if (path.equals(ROOT + "/git/commits/" + HEAD)) {
                    return new JSONObject().put("tree", new JSONObject().put("sha", TREE)).toString();
                }
                if (path.equals(ROOT + "/git/trees/" + TREE)) {
                    return new JSONObject().put("tree", new JSONArray().put(new JSONObject().put("path", "counter.py")
                            .put("type", "blob").put("mode", "100644").put("sha", blob)
                            .put("size", original.length))).toString();
                }
                if (path.equals(ROOT + "/git/blobs/" + blob)) {
                    return new JSONObject().put("sha", blob).put("size", original.length)
                            .put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(original)).toString();
                }
                if (path.startsWith(ROOT + "/commits/" + FIX + "?")) {
                    assertTrue(writes.contains("/graphql"));
                    return new JSONObject().put("sha", FIX).put("commit", new JSONObject().put("message", "Correct count"))
                            .put("files", new JSONArray().put(new JSONObject().put("filename", "counter.py")
                                    .put("status", "modified").put("additions", 1).put("deletions", 1))).toString();
                }
                throw new IOException("Unexpected GitHub request " + method + " " + path);
            } catch (JSONException e) {
                throw new AssertionError(e);
            }
        });
        ProjectService projects = new ProjectService(workspace, temporary.newFolder());
        ToolDispatcher dispatcher = new ToolDispatcher(projects, null, missions, events, mission.id, () -> github,
                temporary.newFolder(), () -> new String[0]);
        ArrayDeque<JSONObject> model = new ArrayDeque<>();
        model.add(call("tree", "github_list_files", repo()));
        model.add(call("read", "github_read_file", repo().put("path", "counter.py")));
        model.add(call("plan", "mission_plan", new JSONObject()
                .put("analysis", "Подсчёт прибавляет единицу к длине списка")
                .put("plan", "Создать ветку, исправить подсчёт и проверить коммит")
                .put("deliverable", "github_commit")));
        model.add(call("branch", "github_create_branch", repo().put("source_ref", "main").put("branch", BRANCH)));
        model.add(call("commit", "github_commit_changes", repo().put("branch", BRANCH).put("message", "Correct count")
                .put("changes", new JSONArray().put(new JSONObject().put("path", "counter.py")
                        .put("content", REPLACEMENT).put("reason", "Убрать лишнее слагаемое")))));
        model.add(call("verify", "github_get_commit", repo().put("ref", FIX)));
        model.add(response("stop", new JSONObject().put("content", "Исправление записано в отдельную ветку.")));

        TokenMeter meter = new TokenMeter(ProviderAccountingTest.inMemoryPreferences());
        new KentAgent(workspace, meter, missions, events, (active, key, context) -> {
            if (model.size() == 5) assertTrue(context.toString().contains("len(items) + 1"));
            if (model.size() == 2) assertTrue(context.toString().contains("/commit/" + FIX));
            return model.removeFirst();
        }, dispatcher).task(mission, mission.goal, false, "opaque-key-for-integration-test");

        assertTrue(model.isEmpty());
        assertEquals(2, previews.size());
        assertTrue(previews.get(0).contains(HEAD));
        assertTrue(previews.get(1).contains("-    return len(items) + 1"));
        assertTrue(previews.get(1).contains("+    return len(items)"));
        MissionStore.Mission saved = missions.find(mission.id);
        assertEquals(MissionStore.DONE, saved.status);
        assertTrue(saved.result.contains("/commit/" + FIX));
        assertTrue(saved.events.stream().anyMatch(event -> "github_get_commit".equals(event.name)
                && MissionStore.SUCCEEDED.equals(event.outcome) && event.detail.contains(FIX)));
        assertTrue(projects.list().isEmpty());
    }

    private static JSONObject repo() throws JSONException { return new JSONObject().put("repository", REPO); }

    private static String reference() throws JSONException {
        return new JSONObject().put("ref", "refs/heads/" + BRANCH)
                .put("object", new JSONObject().put("type", "commit").put("sha", HEAD)).toString();
    }

    private static JSONObject call(String id, String name, JSONObject arguments) throws JSONException {
        JSONObject function = new JSONObject().put("name", name).put("arguments", arguments.toString());
        JSONObject tool = new JSONObject().put("id", id).put("type", "function").put("function", function);
        return response("tool_calls", new JSONObject().put("tool_calls", new JSONArray().put(tool)));
    }

    private static JSONObject response(String reason, JSONObject message) throws JSONException {
        return new JSONObject().put("model", "model").put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", reason).put("message", message)))
                .put("usage", new JSONObject().put("prompt_tokens", 30).put("completion_tokens", 6));
    }
}
