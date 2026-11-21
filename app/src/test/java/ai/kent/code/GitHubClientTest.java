package ai.kent.code;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class GitHubClientTest {
    private static final String REPO = "kent/code";
    private static final String ROOT = "/repos/" + REPO;
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String TREE = "c".repeat(40);
    private static final String BLOB = "d".repeat(40);
    private static final String CREATED = "e".repeat(40);
    private static final String TOKEN = "ghp_" + "unit_test_only".repeat(3);

    @Test public void readsPublicPathWithEncodedUnicodeAndRef() throws Exception {
        Script script = new Script("");
        script.response("GET", ROOT + "/contents/docs/%D1%84%D0%B0%D0%B9%D0%BB%201.md?ref=feature%2Fone",
                blob("Привет\n"));
        GitHubClient.Result got = script.client().contents(REPO, "feature/one", "docs/файл 1.md");
        assertEquals("Привет\n", got.text);
        assertTrue(got.link.startsWith("https://github.com/" + REPO + "/blob/feature/one/"));
        script.done();
    }

    @Test public void disallowsTraversalAndWrongRepositoryForm() {
        Script script = new Script("");
        GitHubClient client = script.client();
        assertThrows(IllegalArgumentException.class, () -> client.contents(REPO, "", "../secret"));
        assertThrows(IllegalArgumentException.class, () -> client.contents(REPO, "", "docs/.git/config"));
        assertThrows(IllegalArgumentException.class, () -> client.contents("https://example.com/kent/code", "", ""));
        assertThrows(IllegalArgumentException.class, () -> client.contents(REPO, "foo..bar", ""));
        script.done();
    }

    @Test public void branchCreationPinsSourceAndRunsOnceAfterReview() throws Exception {
        String target = "feature/reviewed";
        Script script = new Script(TOKEN);
        script.response("GET", ROOT, defaultBranch());
        script.response("GET", ROOT + "/commits/main", new JSONObject().put("sha", HEAD));
        script.failure("GET", ROOT + "/git/ref/heads/feature%2Freviewed", 404);
        GitHubClient client = script.client();
        GitHubClient.Change draft = client.prepareBranch(REPO, "", target);
        assertTrue(draft.preview.contains(HEAD));
        assertTrue(draft.preview.contains(target));
        script.done();

        script.expect("POST", ROOT + "/git/refs", body -> {
            assertEquals(HEAD, body.getString("sha"));
            assertEquals("refs/heads/" + target, body.getString("ref"));
            assertFalse(body.has("force"));
            return new JSONObject().put("ref", "refs/heads/" + target).toString();
        });
        assertTrue(client.apply(draft).link.endsWith("/tree/" + target));
        assertThrows(IllegalStateException.class, () -> client.apply(draft));
        script.done();
    }

    @Test public void fileCommitUsesSingleAtomicExpectedHeadAndExactBytes() throws Exception {
        Script script = new Script(TOKEN);
        script.response("GET", ROOT, defaultBranch());
        script.branch("topic", HEAD);
        script.response("GET", ROOT + "/git/commits/" + HEAD,
                new JSONObject().put("tree", new JSONObject().put("sha", TREE)));
        script.response("GET", ROOT + "/git/trees/" + TREE,
                new JSONObject().put("tree", new JSONArray().put(entry("README.md", "100644", "blob", BLOB))));
        script.response("GET", ROOT + "/git/blobs/" + BLOB, blob("до\n"));
        GitHubClient client = script.client();
        GitHubClient.WorkingFile source = client.openForEditing(REPO, "topic", "README.md");
        assertEquals("до\n", source.text);
        String body = "после\r\n";
        GitHubClient.Change plan = client.prepareFile(source, body, "Правка");
        assertTrue(plan.preview.contains("-до\n+после␍\n"));
        assertTrue(plan.preview.contains(HEAD));
        assertThrows(IllegalArgumentException.class, () -> client.prepareFile(source, TOKEN, "Правка"));
        script.done();

        script.response("GET", ROOT, defaultBranch());
        script.expect("POST", "/graphql", request -> {
            assertTrue(request.getString("query").contains("createCommitOnBranch"));
            JSONObject input = request.getJSONObject("variables").getJSONObject("input");
            assertEquals(HEAD, input.getString("expectedHeadOid"));
            assertEquals(REPO, input.getJSONObject("branch").getString("repositoryNameWithOwner"));
            assertEquals("topic", input.getJSONObject("branch").getString("branchName"));
            JSONObject addition = input.getJSONObject("fileChanges").getJSONArray("additions").getJSONObject(0);
            assertEquals("README.md", addition.getString("path"));
            assertEquals(body, new String(Base64.getDecoder().decode(addition.getString("contents")),
                    StandardCharsets.UTF_8));
            JSONObject commit = new JSONObject().put("oid", CREATED);
            return new JSONObject().put("data", new JSONObject().put("createCommitOnBranch",
                    new JSONObject().put("commit", commit))).toString();
        });
        assertTrue(client.apply(plan).link.endsWith(CREATED));
        script.done();
    }

    @Test public void declinesEditsToDefaultBranchAndSymlink() throws Exception {
        Script script = new Script(TOKEN);
        GitHubClient client = script.client();
        script.response("GET", ROOT, defaultBranch());
        assertThrows(IllegalArgumentException.class, () -> client.openForEditing(REPO, "main", "README.md"));
        script.done();

        script.response("GET", ROOT, defaultBranch());
        script.branch("topic", HEAD);
        script.response("GET", ROOT + "/git/commits/" + HEAD,
                new JSONObject().put("tree", new JSONObject().put("sha", TREE)));
        script.response("GET", ROOT + "/git/trees/" + TREE,
                new JSONObject().put("tree", new JSONArray().put(entry("alias", "120000", "blob", BLOB))));
        assertThrows(IOException.class, () -> client.openForEditing(REPO, "topic", "alias"));
        script.done();
    }

    @Test public void stalePullRequestPreviewMakesNoPost() throws Exception {
        Script script = new Script(TOKEN);
        GitHubClient client = script.client();
        enqueuePullReview(script);
        GitHubClient.Change plan = client.preparePullRequest(REPO, "topic", "main", "Улучшение", "Описание");
        assertTrue(plan.preview.contains("README.md"));
        script.branch("topic", CREATED);
        assertThrows(IOException.class, () -> client.apply(plan));
        script.done();
    }

    @Test public void createsDraftPullRequestFromReviewedBranches() throws Exception {
        Script script = new Script(TOKEN);
        GitHubClient client = script.client();
        enqueuePullReview(script);
        GitHubClient.Change plan = client.preparePullRequest(REPO, "topic", "main", "Улучшение", "Описание");
        script.branch("topic", HEAD);
        script.branch("main", BASE);
        script.expect("POST", ROOT + "/pulls", body -> {
            assertEquals("topic", body.getString("head"));
            assertEquals("main", body.getString("base"));
            assertTrue(body.getBoolean("draft"));
            return new JSONObject().put("number", 42)
                    .put("head", new JSONObject().put("sha", HEAD))
                    .put("base", new JSONObject().put("sha", BASE)).toString();
        });
        assertEquals("https://github.com/" + REPO + "/pull/42", client.apply(plan).link);
        script.done();
    }

    @Test public void actionsReportsActualStatusAndProviderErrors() throws Exception {
        Script script = new Script("");
        GitHubClient client = script.client();
        script.response("GET", ROOT + "/actions/runs?per_page=20&branch=topic",
                new JSONObject().put("workflow_runs", new JSONArray().put(new JSONObject()
                        .put("id", 10).put("run_number", 3).put("name", "CI").put("head_branch", "topic")
                        .put("status", "in_progress").put("conclusion", JSONObject.NULL))));
        GitHubClient.Result result = client.actions(REPO, "topic");
        assertTrue(result.text.contains("В работе"));
        assertTrue(result.link.endsWith("/actions/runs/10"));
        assertTrue(GitHubClient.httpError(403, "0").getMessage().contains("Лимит"));
        assertTrue(GitHubClient.httpError(401, null).getMessage().contains("токен"));
        script.done();
    }

    private static void enqueuePullReview(Script script) throws Exception {
        script.branch("topic", HEAD);
        script.branch("main", BASE);
        script.response("GET", ROOT + "/compare/" + BASE + "..." + HEAD,
                new JSONObject().put("ahead_by", 1).put("behind_by", 0)
                        .put("files", new JSONArray().put(new JSONObject().put("filename", "README.md"))));
    }

    private static JSONObject defaultBranch() throws Exception {
        return new JSONObject().put("default_branch", "main");
    }

    private static JSONObject entry(String path, String mode, String type, String sha) throws Exception {
        return new JSONObject().put("path", path).put("mode", mode).put("type", type).put("sha", sha);
    }

    private static JSONObject blob(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new JSONObject().put("type", "file").put("encoding", "base64").put("size", bytes.length)
                .put("content", Base64.getEncoder().encodeToString(bytes));
    }

    private interface Reply {
        String respond(JSONObject body) throws Exception;
    }

    private static final class Script {
        private final String token;
        private final Queue<GitHubClient.Transport> queue = new ArrayDeque<>();

        Script(String token) { this.token = token; }

        GitHubClient client() {
            return new GitHubClient(token, (method, route, body, actualToken) -> {
                GitHubClient.Transport next = queue.poll();
                if (next == null) throw new AssertionError("Unexpected request " + method + " " + route);
                return next.request(method, route, body, actualToken);
            });
        }

        void expect(String method, String path, Reply reply) {
            queue.add((actualMethod, actualPath, body, actualToken) -> {
                assertEquals(method, actualMethod);
                assertEquals(path, actualPath);
                assertEquals(token, actualToken);
                if ("GET".equals(method)) assertNull(body);
                try {
                    return reply.respond(body);
                } catch (IOException deliberate) {
                    throw deliberate;
                } catch (Exception invalidFixture) {
                    throw new AssertionError(invalidFixture);
                }
            });
        }

        void response(String method, String path, JSONObject body) {
            expect(method, path, ignored -> body.toString());
        }

        void branch(String branch, String sha) throws Exception {
            response("GET", ROOT + "/git/ref/heads/" + branch,
                    new JSONObject().put("ref", "refs/heads/" + branch)
                            .put("object", new JSONObject().put("sha", sha).put("type", "commit")));
        }

        void failure(String method, String path, int status) {
            expect(method, path, ignored -> { throw GitHubClient.httpError(status, null); });
        }

        void done() { assertTrue("Expected requests were not sent", queue.isEmpty()); }
    }
}
