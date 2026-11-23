package ai.kent.code;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;

/** Agent entry points for GitHub with durable receipts and one-time mutation approval. */
final class GitHubAgentTools {
    interface ClientSource {
        GitHubClient get() throws Exception;
    }

    static final class Result {
        final String text;
        final boolean review;
        final boolean failed;

        Result(String text, boolean review, boolean failed) {
            this.text = text;
            this.review = review;
            this.failed = failed;
        }
    }

    private final ClientSource clients;
    private final MissionStore missions;
    private final KentAgent.Events events;
    private final AtomicReference<GitHubClient> activeClient = new AtomicReference<>();

    GitHubAgentTools(ClientSource clients, MissionStore missions, KentAgent.Events events) {
        this.clients = clients;
        this.missions = missions;
        this.events = events;
    }

    Result execute(String name, JSONObject args, String apiKey) {
        try {
            return run(name, args, apiKey);
        } finally {
            activeClient.set(null);
        }
    }

    void cancel() {
        GitHubClient client = activeClient.get();
        if (client != null) client.cancel();
    }

    private GitHubClient client() throws Exception {
        GitHubClient client = clients.get();
        activeClient.set(client);
        if (events.cancelled()) client.cancel();
        return client;
    }

    private Result run(String name, JSONObject args, String apiKey) {
        boolean mutation = isMutation(name);
        String repository = SensitiveData.redact(args.optString("repository"), apiKey);
        if (events.cancelled()) return new Result("Действие остановлено.", false, true);
        if (mutation) return write(name, args, repository, apiKey);

        String target = repository;
        if (args.opt("path") instanceof String)
            target += " · " + SensitiveData.redact(args.optString("path"), apiKey);
        String id = start(name, target, false);
        try {
            rejectSensitiveArgs(args, apiKey);
            String output = format(read(client(), name, args, apiKey), apiKey);
            missions.finishAction(id, MissionStore.SUCCEEDED, output, false);
            return new Result(output, false, false);
        } catch (Exception error) {
            return failed(id, error, apiKey, false, "");
        }
    }

    private Result write(String name, JSONObject args, String repository, String apiKey) {
        GitHubClient client;
        GitHubClient.Change change;
        try {
            rejectSensitiveArgs(args, apiKey);
            client = client();
            change = prepare(client, name, args);
        } catch (Exception error) {
            return failed(start(name, repository, false), error, apiKey, false, "");
        }

        String preview = SensitiveData.redact(change.preview, apiKey);
        String target = change.repo + " · " + change.branch;
        boolean approved = !events.cancelled() && events.approve(change.title + " · GitHub", preview);
        if (!approved || events.cancelled()) {
            boolean halted = events.cancelled();
            String detail = halted ? "Действие остановлено до отправки." : "Отправка на GitHub отклонена.";
            missions.finishAction(start(name, target, false),
                    halted ? MissionStore.INTERRUPTED : MissionStore.DENIED, detail, false, preview);
            return new Result(detail, !halted, true);
        }
        String id = start(name, target, true);
        if (events.cancelled()) {
            String detail = "Действие остановлено до отправки.";
            missions.finishAction(id, MissionStore.INTERRUPTED, detail, false, preview);
            return new Result(detail, false, true);
        }
        GitHubClient.Result result;
        try {
            result = client.apply(change);
        } catch (Exception error) {
            return failed(id, error, apiKey, true, preview);
        }
        String output = format(result, apiKey);
        boolean committed = "github_commit_changes".equals(name);
        missions.finishAction(id, MissionStore.SUCCEEDED, output, false, preview,
                committed ? optional(args, "message") : "");
        return new Result(output, false, false);
    }

    private Result failed(String id, Exception error, String apiKey, boolean writeAttempted, String preview) {
        boolean unknown = writeAttempted && (error instanceof GitHubClient.AmbiguousWriteException
                || error instanceof JSONException);
        String outcome = unknown ? MissionStore.UNKNOWN
                : events.cancelled() ? MissionStore.INTERRUPTED : MissionStore.FAILED;
        String detail = unknown ? "GitHub не подтвердил итог операции. Проверьте ветку, коммиты или PR перед повтором."
                : friendlyError(error, apiKey);
        missions.finishAction(id, outcome, detail, false, preview);
        return new Result(detail, unknown, true);
    }

    private String start(String name, String target, boolean review) {
        String id = missions.startAction(name, target, review);
        events.missionChanged();
        return id;
    }

    private static String format(GitHubClient.Result result, String apiKey) {
        return SensitiveData.redact(result.title + "\n" + result.link + "\n" + result.text, apiKey);
    }

    private static String friendlyError(Exception error, String apiKey) {
        if (error instanceof SSLException) return "Защищённое подключение к GitHub не установлено.";
        if (error instanceof SocketTimeoutException || error instanceof SocketException || error instanceof UnknownHostException)
            return "Связь с GitHub недоступна. Повторите запрос позже.";
        if (error instanceof IOException || error instanceof IllegalArgumentException) {
            String detail = error.getMessage();
            if (detail != null) return SensitiveData.redact(detail, apiKey);
        }
        if (error instanceof JSONException) return "GitHub вернул неожиданный формат данных.";
        return "Не удалось получить данные GitHub или открыть сохранённый токен.";
    }

    private static boolean isMutation(String name) {
        return "github_create_branch".equals(name) || "github_commit_changes".equals(name)
                || "github_create_pull_request".equals(name);
    }

    private static GitHubClient.Result read(GitHubClient client, String name, JSONObject args, String apiKey) throws Exception {
        if ("github_list_repos".equals(name))
            return client.repositories(optional(args, "owner"), integer(args, "page", 1));
        String repo = required(args, "repository");
        switch (name) {
            case "github_list_branches":
                return client.branches(repo, integer(args, "page", 1));
            case "github_list_files":
                return client.files(repo, optional(args, "ref"), optional(args, "path"),
                        integer(args, "offset", 0), integer(args, "limit", 35));
            case "github_read_file":
                return client.readSource(repo, optional(args, "ref"), required(args, "path"),
                        integer(args, "offset", 0), integer(args, "limit", 4_000), apiKey);
            case "github_search_code":
                return client.searchCode(repo, required(args, "query"), integer(args, "page", 1));
            case "github_get_commit":
                return client.commitInfo(repo, optional(args, "ref"), bool(args, "history"), integer(args, "page", 1));
            case "github_get_diff":
                return client.diff(repo, optional(args, "base"), required(args, "head"),
                        integer(args, "file_offset", 0), integer(args, "patch_offset", 0), apiKey);
            case "github_get_workflow_status":
                return client.workflowStatus(repo, optional(args, "branch"), optional(args, "head_sha"),
                        longValue(args, "run_id", 0), integer(args, "page", 1));
            default:
                throw new IllegalArgumentException("Инструмент GitHub недоступен.");
        }
    }

    private static GitHubClient.Change prepare(GitHubClient client, String name, JSONObject args) throws Exception {
        String repo = required(args, "repository");
        switch (name) {
            case "github_create_branch":
                return client.prepareBranch(repo, optional(args, "source_ref"), required(args, "branch"));
            case "github_commit_changes":
                return client.prepareCommit(repo, required(args, "branch"), required(args, "message"), edits(args));
            case "github_create_pull_request":
                return client.preparePullRequest(repo, required(args, "head"), optional(args, "base"),
                        required(args, "title"), optional(args, "body"));
            default:
                throw new IllegalArgumentException("Инструмент GitHub недоступен.");
        }
    }

    private static List<GitHubClient.FileEdit> edits(JSONObject args) {
        JSONArray changes = args.optJSONArray("changes");
        if (changes == null || changes.length() == 0)
            throw new IllegalArgumentException("Укажите непустой массив changes.");
        List<GitHubClient.FileEdit> files = new ArrayList<>(changes.length());
        for (int i = 0; i < changes.length(); i++) {
            JSONObject entry = changes.optJSONObject(i);
            if (entry == null) throw new IllegalArgumentException("Изменение файла должно быть объектом.");
            String path = required(entry, "path");
            boolean delete = bool(entry, "delete");
            String reason = optional(entry, "reason");
            boolean fragment = entry.has("old_text") || entry.has("new_text") || entry.has("replace_all");
            if (((delete || fragment) && entry.has("content")) || (delete && fragment))
                throw new IllegalArgumentException("Выберите один способ изменения: content, delete или old_text/new_text.");
            if (fragment) {
                files.add(new GitHubClient.FileEdit(path, required(entry, "new_text"), reason,
                        required(entry, "old_text"), bool(entry, "replace_all")));
            } else {
                files.add(new GitHubClient.FileEdit(path, delete ? null : required(entry, "content"), reason));
            }
        }
        return files;
    }

    private static String optional(JSONObject args, String key) {
        return args.has(key) ? required(args, key) : "";
    }

    private static String required(JSONObject args, String key) {
        Object value = args.opt(key);
        if (!(value instanceof String))
            throw new IllegalArgumentException("Укажите параметр " + key + " в виде строки.");
        return (String) value;
    }

    private static boolean bool(JSONObject args, String key) {
        if (!args.has(key)) return false;
        Object value = args.opt(key);
        if (!(value instanceof Boolean))
            throw new IllegalArgumentException("Укажите параметр " + key + " в виде true или false.");
        return (Boolean) value;
    }

    private static int integer(JSONObject args, String key, int fallback) {
        long value = longValue(args, key, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Параметр " + key + " выходит за допустимый числовой диапазон.");
        return (int) value;
    }

    private static long longValue(JSONObject args, String key, long fallback) {
        if (!args.has(key)) return fallback;
        Object value = args.opt(key);
        if (!(value instanceof Number))
            throw new IllegalArgumentException("Укажите целое число в параметре " + key + ".");
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Укажите целое число в параметре " + key + ".");
        }
    }

    private static void rejectSensitiveArgs(JSONObject args, String apiKey) {
        Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            rejectSensitiveValue(key, apiKey);
            rejectSensitiveValue(args.opt(key), apiKey);
        }
    }

    private static void rejectSensitiveValue(Object value, String apiKey) {
        if (value instanceof String && !value.equals(SensitiveData.redact((String) value, apiKey)))
            throw new IllegalArgumentException("Запрос GitHub содержит данные, похожие на секрет.");
        if (value instanceof JSONObject) rejectSensitiveArgs((JSONObject) value, apiKey);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) rejectSensitiveValue(array.opt(i), apiKey);
        }
    }

    static void addSchemas(JSONArray tools) throws JSONException {
        JSONObject edit = new JSONObject().put("type", "object").put("properties",
                        properties("path", "content", "old_text", "new_text", "reason")
                                .put("delete", KentAgent.property("boolean")).put("replace_all", KentAgent.property("boolean")))
                .put("required", new JSONArray().put("path")).put("additionalProperties", false);
        tools.put(KentAgent.tool("github_list_repos", "List authenticated repositories, or public repos for owner. Use next_page.",
                        properties("owner").put("page", KentAgent.property("integer"))))
                .put(KentAgent.tool("github_list_branches", "List branches, commit IDs, and the default branch. Repository is owner/repo or a GitHub URL.",
                        properties("repository").put("page", KentAgent.property("integer")), "repository"))
                .put(KentAgent.tool("github_list_files", "List one directory at a branch, tag, or commit. Omit path for root. Continue using next_offset and commit_sha as ref.",
                        properties("repository", "ref", "path").put("offset", KentAgent.property("integer"))
                                .put("limit", KentAgent.property("integer")), "repository"))
                .put(KentAgent.tool("github_read_file", "Read a UTF-8 source slice with credentials redacted. Continue using next_offset with commit_sha as ref.",
                        properties("repository", "ref", "path").put("offset", KentAgent.property("integer"))
                                .put("limit", KentAgent.property("integer")), "repository", "path"))
                .put(KentAgent.tool("github_search_code", "Search GitHub's default-branch code index within this repository. Requires an authenticated GitHub token.",
                        properties("repository", "query").put("page", KentAgent.property("integer")), "repository", "query"))
                .put(KentAgent.tool("github_get_commit", "Get commit metadata and changed paths for a branch, tag or SHA. Set history=true to list its commit history.",
                        properties("repository", "ref").put("history", KentAgent.property("boolean"))
                                .put("page", KentAgent.property("integer")), "repository"))
                .put(KentAgent.tool("github_get_diff", "Compare refs, preferably pinned SHAs, and show one changed file per file_offset. Use next_patch_offset for long patches.",
                        properties("repository", "base", "head").put("file_offset", KentAgent.property("integer"))
                                .put("patch_offset", KentAgent.property("integer")), "repository", "head"))
                .put(KentAgent.tool("github_create_branch", "Preview and ask the user to create a separate branch from a specific ref or the default branch.",
                        properties("repository", "source_ref", "branch"), "repository", "branch"))
                .put(KentAgent.tool("github_commit_changes", "Review one atomic text commit on a working branch. Per file, give content, old_text/new_text for an exact edit, or delete=true.",
                        properties("repository", "branch", "message").put("changes",
                                new JSONObject().put("type", "array").put("items", edit)), "repository", "branch", "message", "changes"))
                .put(KentAgent.tool("github_create_pull_request", "Preview and ask the user to open a draft PR from head to base. Omit base for the default branch.",
                        properties("repository", "head", "base", "title", "body"), "repository", "head", "title"))
                .put(KentAgent.tool("github_get_workflow_status", "Read actual GitHub Actions status by branch, head_sha or run_id. An empty result means no reported run.",
                        properties("repository", "branch", "head_sha").put("run_id", KentAgent.property("integer"))
                                .put("page", KentAgent.property("integer")), "repository"));
    }

    private static JSONObject properties(String... strings) throws JSONException {
        JSONObject fields = new JSONObject();
        for (String name : strings) fields.put(name, KentAgent.property("string"));
        return fields;
    }
}
