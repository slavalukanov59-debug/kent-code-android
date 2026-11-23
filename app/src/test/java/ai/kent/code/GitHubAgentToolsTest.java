package ai.kent.code;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class GitHubAgentToolsTest {
    private static final String REPO = "kent/code";
    private static final String ROOT = "/repos/" + REPO;
    private static final String HEAD = "a".repeat(40);
    private static final String BRANCH = "topic";
    private static final String TOKEN = "ghp_" + "unit_test_only".repeat(3);

    @Test public void decliningPreviewSendsNoMutation() throws Exception {
        Fixture fixture = new Fixture(false);
        GitHubAgentTools.Result result = fixture.execute();
        assertTrue(result.failed);
        assertTrue(fixture.preview.contains("main @ " + HEAD));
        assertTrue(fixture.preview.contains(BRANCH));
        assertEquals(List.of("GET " + ROOT, "GET " + ROOT + "/commits/main",
                "GET " + ROOT + "/git/ref/heads/topic"), fixture.requests);
        assertTrue(fixture.missions.active().events.stream().anyMatch(event -> MissionStore.DENIED.equals(event.outcome)));
    }

    @Test public void uncertainRemoteWriteRequiresOperatorReviewBeforeAnotherAction() throws Exception {
        Fixture fixture = new Fixture(true);
        GitHubAgentTools.Result result = fixture.execute();
        assertTrue(result.failed);
        assertTrue(result.review);
        assertFalse(result.text.contains(TOKEN));
        assertEquals("POST " + ROOT + "/git/refs", fixture.requests.get(3));
        MissionStore.Mission active = fixture.missions.active();
        assertTrue(active.needsReview());
        assertTrue(active.events.stream().anyMatch(event -> MissionStore.UNKNOWN.equals(event.outcome)));
        assertThrows(IllegalStateException.class,
                () -> fixture.missions.startAction("github_create_branch", REPO, true));
        assertEquals(4, fixture.requests.size());
    }

    private static final class Fixture implements KentAgent.Events {
        final MissionStore missions = new MissionStore(new MissionStore.Persistence() {
            private byte[] bytes;
            @Override public byte[] read() { return bytes; }
            @Override public void write(byte[] data) { bytes = data; }
        });
        final List<String> requests = new ArrayList<>();
        final boolean approved;
        String preview = "";

        Fixture(boolean approved) {
            this.approved = approved;
            missions.begin("Create a branch", "openrouter", "model");
        }

        GitHubAgentTools.Result execute() throws Exception {
            String metadata = new JSONObject().put("default_branch", "main").toString();
            String commit = new JSONObject().put("sha", HEAD).toString();
            GitHubClient client = new GitHubClient(TOKEN, (method, path, body, token) -> {
                assertEquals(TOKEN, token);
                requests.add(method + " " + path);
                if ("POST".equals(method)) {
                    assertTrue(approved);
                    assertFalse(preview.isEmpty());
                    throw GitHubClient.httpError(502, null);
                }
                if (ROOT.equals(path)) return metadata;
                if ((ROOT + "/commits/main").equals(path)) return commit;
                if ((ROOT + "/git/ref/heads/topic").equals(path)) throw GitHubClient.httpError(404, null);
                throw new AssertionError("Unexpected GitHub request " + method + " " + path);
            });
            GitHubAgentTools tools = new GitHubAgentTools(() -> client, missions, this);
            return tools.execute("github_create_branch",
                    new JSONObject().put("repository", REPO).put("branch", BRANCH), "");
        }

        @Override public void log(String title, String message) { }
        @Override public boolean approve(String title, String detail) { preview = detail; return approved; }
        @Override public boolean cancelled() { return false; }
        @Override public String stopStatus() { return MissionStore.PAUSED; }
        @Override public void usage(TokenMeter.Snapshot snapshot) { }
        @Override public void missionChanged() { }
    }
}
