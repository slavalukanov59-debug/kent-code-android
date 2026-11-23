package ai.kent.code;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
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
            return new JSONObject().put("ref", "refs/heads/" + target)
                    .put("object", new JSONObject().put("sha", HEAD)).toString();
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

    @Test public void importsCompletePinnedSnapshotAndVerifiesSelectedBlob() throws Exception {
        String source = "fun main() = println(\"Кент\")\n";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        String sourceSha = GitHubClient.blobId(bytes);
        Script script = new Script("");
        GitHubClient client = script.client();
        script.response("GET", ROOT + "/commits/feature%2Fimport", commitWithTree());
        script.response("GET", ROOT + "/git/trees/" + TREE + "?recursive=1",
                new JSONObject().put("truncated", true));
        script.response("GET", ROOT + "/git/trees/" + TREE,
                new JSONObject().put("tree", new JSONArray().put(entry("src", "040000", "tree", BLOB))));
        script.response("GET", ROOT + "/git/trees/" + BLOB,
                new JSONObject().put("tree", new JSONArray().put(
                        entry("main.kt", "100644", "blob", sourceSha).put("size", bytes.length))));
        GitHubClient.RepositorySnapshot snapshot = client.snapshot("https://github.com/kent/code.git", "feature/import");
        assertEquals(REPO, snapshot.repository);
        assertEquals("feature/import", snapshot.ref);
        assertEquals(HEAD, snapshot.commitSha);
        assertEquals("src/main.kt", snapshot.entries.get(1).path);
        assertEquals(bytes.length, snapshot.entries.get(1).size);
        script.response("GET", ROOT + "/git/blobs/" + sourceSha, blob(source).put("sha", sourceSha));
        assertEquals(source, new String(client.readBlob(REPO, sourceSha, bytes.length), StandardCharsets.UTF_8));
        script.response("GET", ROOT + "/git/blobs/" + sourceSha, blob("different").put("sha", sourceSha));
        IOException integrity = assertThrows(IOException.class, () -> client.readBlob(REPO, sourceSha, bytes.length));
        assertTrue(integrity.getMessage().contains("целостности"));
        script.done();
    }

    @Test public void rangesUtf8SourceWithContinuationAndPinnedCommit() throws Exception {
        String source = "x🌱y";
        String sha = GitHubClient.blobId(source.getBytes(StandardCharsets.UTF_8));
        Script script = new Script("");
        script.response("GET", ROOT + "/commits/" + HEAD, commitWithTree());
        script.response("GET", ROOT + "/git/trees/" + TREE,
                new JSONObject().put("tree", new JSONArray().put(entry("main.java", "100644", "blob", sha))));
        script.response("GET", ROOT + "/git/blobs/" + sha, blob(source).put("sha", sha));
        GitHubClient.Result slice = script.client().readSource(REPO, HEAD, "main.java", 1, 1, "");
        assertTrue(slice.text.contains("commit_sha=" + HEAD + " offset=1 next_offset=3 total_chars=4"));
        assertTrue(slice.text.endsWith("🌱"));
        assertTrue(slice.link.contains("/blob/" + HEAD + "/"));
        script.done();
    }

    @Test public void removesCredentialsBeforeChoosingSourceSlices() throws Exception {
        String providerKey = "custom-provider-key-for-test-only";
        String source = "x" + TOKEN + "\ny" + providerKey + "\n";
        String sha = GitHubClient.blobId(source.getBytes(StandardCharsets.UTF_8));
        Script script = new Script(TOKEN);
        script.response("GET", ROOT + "/commits/" + HEAD, commitWithTree());
        script.response("GET", ROOT + "/git/trees/" + TREE,
                new JSONObject().put("tree", new JSONArray().put(entry("main.java", "100644", "blob", sha))));
        script.response("GET", ROOT + "/git/blobs/" + sha, blob(source).put("sha", sha));
        String slice = script.client().readSource(REPO, HEAD, "main.java", 2, 100, providerKey).text;
        assertFalse(slice.contains("unit_test_only"));
        assertFalse(slice.contains(providerKey));
        assertTrue(slice.contains("секрет скрыт"));
        script.done();
    }

    @Test public void listsRepositoriesBranchesAndScopedCodeSearch() throws Exception {
        Script script = new Script(TOKEN);
        GitHubClient client = script.client();
        script.expect("GET", "/user/repos?per_page=20&page=2&sort=updated", ignored -> new JSONArray()
                .put(new JSONObject().put("full_name", REPO).put("default_branch", "main")).toString());
        assertTrue(client.repositories("", 2).text.contains("page=2 next_page=none"));
        script.response("GET", ROOT, defaultBranch());
        script.expect("GET", ROOT + "/branches?per_page=20&page=1", ignored -> new JSONArray().put(
                new JSONObject().put("name", "feature/a").put("protected", true)
                        .put("commit", new JSONObject().put("sha", HEAD))).toString());
        GitHubClient.BranchPage branches = client.listBranches(REPO, 1);
        assertEquals("main", branches.defaultBranch);
        assertEquals(HEAD, branches.branches.get(0).sha);
        assertTrue(branches.branches.get(0).protectedBranch);
        assertFalse(branches.hasMore);
        script.response("GET", "/search/code?per_page=20&page=1&q=MainActivity%20repo%3Akent%2Fcode",
                new JSONObject().put("total_count", 2).put("incomplete_results", true).put("items", new JSONArray()
                        .put(new JSONObject().put("path", "src/MainActivity.java")
                                .put("repository", new JSONObject().put("full_name", REPO)))
                        .put(new JSONObject().put("path", "private/secret.txt")
                                .put("repository", new JSONObject().put("full_name", "other/repo")))));
        String search = client.searchCode(REPO, "MainActivity", 1).text;
        assertTrue(search.contains("src/MainActivity.java"));
        assertTrue(search.contains("неполные"));
        assertFalse(search.contains("private/secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> client.searchCode(REPO, "repo:other/repo", 1));
        script.done();
    }

    @Test public void keepsCredentialFilesOutOfAgentReadsChangesAndDiffs() throws Exception {
        Script script = new Script(TOKEN);
        GitHubClient client = script.client();
        assertThrows(IOException.class, () -> client.readSource(REPO, HEAD, "app/.ENV.local", 0, 100, ""));
        assertThrows(IOException.class, () -> client.prepareCommit(REPO, "topic", "Edit settings",
                List.of(new GitHubClient.FileEdit("settings/credentials.json", "{}", ""))));
        script.done();
        script.response("GET", ROOT + "/compare/" + BASE + "..." + HEAD,
                new JSONObject().put("ahead_by", 1).put("files", new JSONArray().put(new JSONObject()
                        .put("filename", "settings.txt").put("previous_filename", "app/.env")
                        .put("status", "renamed").put("patch", "-TOKEN=private-short-value"))));
        String diff = client.diff(REPO, BASE, HEAD, 0, 0, "").text;
        assertTrue(diff.contains("Содержимое файла учётных данных скрыто."));
        assertFalse(diff.contains("private-short-value"));
        script.done();
    }

    @Test public void agentDiffAndWorkflowExposeActualRowsAtExactOffsets() throws Exception {
        Script script = new Script("");
        GitHubClient client = script.client();
        script.response("GET", ROOT + "/compare/" + BASE + "..." + HEAD,
                new JSONObject().put("ahead_by", 1).put("files", new JSONArray()
                        .put(new JSONObject().put("filename", "first.txt").put("patch", "+first"))
                        .put(new JSONObject().put("filename", "src/main.kt").put("status", "modified")
                                .put("additions", 1).put("deletions", 1).put("patch", "-one\n+two"))));
        GitHubClient.Result diff = client.diff(REPO, BASE, HEAD, 1, 5, "");
        assertTrue(diff.text.contains("file_offset=1 next_file_offset=none patch_offset=5 next_patch_offset=none"));
        assertTrue(diff.text.contains("src/main.kt"));
        assertTrue(diff.text.endsWith("+two"));
        script.response("GET", ROOT + "/actions/runs?per_page=20&page=1&head_sha=" + CREATED,
                new JSONObject().put("workflow_runs", new JSONArray().put(new JSONObject()
                        .put("id", 10).put("name", "CI").put("head_sha", CREATED)
                        .put("status", "completed").put("conclusion", "failure"))));
        String run = client.workflowStatus(REPO, "", CREATED, 0, 1).text;
        assertTrue(run.contains("run_id=10"));
        assertTrue(run.contains("completed / failure"));
        script.done();
    }

    @Test public void batchCommitPinsSingleHeadAndIncludesAllReviewedFiles() throws Exception {
        Script script = new Script(TOKEN);
        GitHubClient client = script.client();
        script.response("GET", ROOT, defaultBranch());
        script.branch("topic", HEAD);
        script.response("GET", ROOT + "/git/commits/" + HEAD,
                new JSONObject().put("tree", new JSONObject().put("sha", TREE)));
        script.response("GET", ROOT + "/git/trees/" + TREE,
                new JSONObject().put("tree", new JSONArray().put(entry("old.txt", "100644", "blob", BLOB))));
        script.response("GET", ROOT + "/git/blobs/" + BLOB, blob("outdated\n"));
        GitHubClient.Change change = client.prepareCommit(REPO, "topic", "Refresh files",
                List.of(new GitHubClient.FileEdit("old.txt", null, "Outdated"),
                        new GitHubClient.FileEdit("new.txt", "replacement\n", "Replacement")));
        assertTrue(change.preview.contains("-outdated"));
        assertTrue(change.preview.contains("+replacement"));
        assertTrue(change.preview.contains("Причина · Replacement"));
        script.done();

        script.response("GET", ROOT, defaultBranch());
        script.expect("POST", "/graphql", request -> {
            JSONObject input = request.getJSONObject("variables").getJSONObject("input");
            assertEquals(HEAD, input.getString("expectedHeadOid"));
            JSONObject changes = input.getJSONObject("fileChanges");
            assertEquals("new.txt", changes.getJSONArray("additions").getJSONObject(0).getString("path"));
            assertEquals("old.txt", changes.getJSONArray("deletions").getJSONObject(0).getString("path"));
            return new JSONObject().put("data", new JSONObject().put("createCommitOnBranch",
                    new JSONObject().put("commit", new JSONObject().put("oid", CREATED)))).toString();
        });
        assertTrue(client.apply(change).link.endsWith("/commit/" + CREATED));
        script.done();
    }

    @Test public void mainAndDefaultBranchStayProtectedRegardlessOfDefaultName() throws Exception {
        Script script = new Script(TOKEN);
        GitHubClient client = script.client();
        JSONObject defaultDevelop = new JSONObject().put("default_branch", "develop");
        script.response("GET", ROOT, defaultDevelop);
        assertThrows(IllegalArgumentException.class, () -> client.prepareBranch(REPO, "develop", "main"));
        script.response("GET", ROOT, defaultDevelop);
        assertThrows(IllegalArgumentException.class, () -> client.openForEditing(REPO, "main", "README.md"));
        script.response("GET", ROOT, defaultDevelop);
        assertThrows(IllegalArgumentException.class, () -> client.openForEditing(REPO, "develop", "README.md"));
        script.done();
    }

    @Test public void commitsAnExactFragmentOfLargeSourceWithoutFullContentInToolArguments() throws Exception {
        String source = "blank work line, 20 characters\n".repeat(2_000) + "int oldValue = 7;\n";
        Script script = new Script(TOKEN);
        GitHubClient client = script.client();
        script.response("GET", ROOT, defaultBranch());
        script.branch("topic", HEAD);
        script.response("GET", ROOT + "/git/commits/" + HEAD,
                new JSONObject().put("tree", new JSONObject().put("sha", TREE)));
        script.response("GET", ROOT + "/git/trees/" + TREE, new JSONObject().put("tree", new JSONArray()
                .put(entry("Main.java", "100644", "blob", BLOB).put("size", source.length()))));
        script.response("GET", ROOT + "/git/blobs/" + BLOB, blob(source));
        GitHubClient.Change change = client.prepareCommit(REPO, "topic", "Fix value",
                List.of(new GitHubClient.FileEdit("Main.java", "int newValue = 8;", "", "int oldValue = 7;", false)));
        assertTrue(change.preview.contains("-int oldValue = 7;"));
        assertTrue(change.preview.contains("+int newValue = 8;"));
        assertTrue(change.preview.length() < 1_000);
        script.response("GET", ROOT, defaultBranch());
        script.expect("POST", "/graphql", request -> {
            JSONObject input = request.getJSONObject("variables").getJSONObject("input");
            JSONObject file = input.getJSONObject("fileChanges").getJSONArray("additions").getJSONObject(0);
            String replacement = new String(Base64.getDecoder().decode(file.getString("contents")), StandardCharsets.UTF_8);
            assertEquals(source.replace("int oldValue = 7;", "int newValue = 8;"), replacement);
            return new JSONObject().put("data", new JSONObject().put("createCommitOnBranch",
                    new JSONObject().put("commit", new JSONObject().put("oid", CREATED)))).toString();
        });
        assertTrue(client.apply(change).link.endsWith(CREATED));
        script.done();
    }

    @Test public void ambiguousMutationTransportFailureInvalidatesReview() throws Exception {
        Script script = new Script(TOKEN);
        GitHubClient client = script.client();
        script.response("GET", ROOT, defaultBranch());
        script.response("GET", ROOT + "/commits/" + HEAD, new JSONObject().put("sha", HEAD));
        script.failure("GET", ROOT + "/git/ref/heads/topic", 404);
        GitHubClient.Change plan = client.prepareBranch(REPO, HEAD, "topic");
        script.failure("POST", ROOT + "/git/refs", 502);
        assertThrows(GitHubClient.AmbiguousWriteException.class, () -> client.apply(plan));
        assertThrows(IllegalStateException.class, () -> client.apply(plan));
        script.done();
    }

    @Test public void cancellingClientPreventsFurtherRequests() {
        Script script = new Script("");
        GitHubClient client = script.client();
        client.cancel();
        assertThrows(IOException.class, () -> client.contents(REPO, "", ""));
        script.done();
    }

    private static JSONObject commitWithTree() throws Exception {
        return new JSONObject().put("sha", HEAD).put("commit",
                new JSONObject().put("tree", new JSONObject().put("sha", TREE)));
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
