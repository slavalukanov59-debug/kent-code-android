package ai.kent.code;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;

/** GitHub reads and prepared, explicitly reviewed writes. */
public final class GitHubClient {
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 48 * 1024;
    // Base64 and JSON need room within the API transport budget.
    private static final int MAX_AGENT_FILE_BYTES = MAX_RESPONSE_BYTES / 2;
    private static final int MAX_PREVIEW_CHARS = 50_000;
    private static final String COMMIT_MUTATION =
            "mutation KentFileChange($input: CreateCommitOnBranchInput!) {"
            + " createCommitOnBranch(input: $input) { commit { oid } } }";

    interface Transport {
        String request(String method, String path, JSONObject body, String token) throws IOException;
    }

    public static final class Result {
        public final String title;
        public final String text;
        public final String link;
        final String textPath;

        Result(String title, String text, String link) {
            this(title, text, link, "");
        }

        Result(String title, String text, String link, String textPath) {
            this.title = title;
            this.text = text;
            this.link = link;
            this.textPath = textPath;
        }
    }

    public static final class ApiException extends IOException {
        public final int status;
        public final long rateLimitRemaining;
        public final long rateLimitResetEpochSeconds;

        ApiException(int status, String message) {
            this(status, message, -1, -1);
        }

        ApiException(int status, String message, long remaining, long reset) {
            super(message);
            this.status = status;
            this.rateLimitRemaining = remaining;
            this.rateLimitResetEpochSeconds = reset;
        }
    }

    static final class AmbiguousWriteException extends IOException {
        AmbiguousWriteException(Exception cause) {
            super("GitHub не подтвердил итог операции. Проверьте состояние ветки, коммитов или PR перед повтором.", cause);
        }
    }

    private static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException() {
            super("Ответ GitHub слишком большой. Откройте отдельный каталог или сравните меньший диапазон.");
        }
    }

    public static final class TreeEntry {
        public final String path;
        public final String type;
        public final String mode;
        public final String sha;
        public final long size;

        private TreeEntry(JSONObject entry, String prefix) throws Exception {
            path = filePath(prefix + entry.getString("path"), false);
            type = entry.getString("type");
            mode = entry.getString("mode");
            sha = objectId(entry, "sha");
            size = entry.optLong("size", -1);
        }
    }

    public static final class RepositorySnapshot {
        public final String repository;
        public final String ref;
        public final String commitSha;
        public final List<TreeEntry> entries;

        private RepositorySnapshot(String repository, String ref, String commitSha, List<TreeEntry> entries) {
            this.repository = repository;
            this.ref = ref;
            this.commitSha = commitSha;
            this.entries = java.util.Collections.unmodifiableList(entries);
        }
    }

    public static final class BranchInfo {
        public final String name;
        public final String sha;
        public final boolean protectedBranch;

        private BranchInfo(JSONObject row) throws Exception {
            name = branch(row.getString("name"));
            sha = objectId(row.getJSONObject("commit"), "sha");
            protectedBranch = row.optBoolean("protected");
        }
    }

    public static final class BranchPage {
        public final String defaultBranch;
        public final List<BranchInfo> branches;
        public final boolean hasMore;

        private BranchPage(String defaultBranch, List<BranchInfo> branches, boolean hasMore) {
            this.defaultBranch = defaultBranch;
            this.branches = java.util.Collections.unmodifiableList(branches);
            this.hasMore = hasMore;
        }
    }

    static final class FileEdit {
        final String path;
        final String content;
        final String reason;
        final String oldText;
        final boolean replaceAll;

        FileEdit(String path, String content, String reason) {
            this(path, content, reason, null, false);
        }

        FileEdit(String path, String content, String reason, String oldText, boolean replaceAll) {
            this.path = path;
            this.content = content;
            this.reason = reason;
            this.oldText = oldText;
            this.replaceAll = replaceAll;
        }
    }

    public abstract static class Change {
        public final String title;
        public final String preview;
        final String repo;
        final String branch;
        final String head;
        final AtomicBoolean submitted = new AtomicBoolean();

        private Change(String title, String preview, String repo, String branch, String head) {
            this.title = title;
            this.preview = preview;
            this.repo = repo;
            this.branch = branch;
            this.head = head;
        }
    }

    public static final class WorkingFile {
        public final String path;
        public final String text;
        private final String repo;
        private final String branch;
        private final String head;

        private WorkingFile(String repo, String branch, String head, String path, String text) {
            this.repo = repo;
            this.branch = branch;
            this.head = head;
            this.path = path;
            this.text = text;
        }
    }

    private static final class BranchChange extends Change {
        BranchChange(String repo, String branch, String head, String source) {
            super("Создание ветки", repo + "\n" + source + " @ " + head + "\n→ " + branch,
                    repo, branch, head);
        }
    }

    private static final class CommitChange extends Change {
        final List<FileEdit> files;
        final String message;

        CommitChange(String repo, String branch, String head, List<FileEdit> files, String message, String diff) {
            super(files.size() == 1 ? "Коммит файла" : "Коммит файлов",
                    repo + " · " + branch + "\n" + head + "\n" + visible(message) + "\n\n" + diff, repo, branch, head);
            this.files = java.util.Collections.unmodifiableList(files);
            this.message = message;
        }
    }

    private static final class PullChange extends Change {
        final String base;
        final String baseHead;
        final String subject;
        final String body;

        PullChange(String repo, String branch, String head, String base, String baseHead,
                   String subject, String body, String diff) {
            super("Создание черновика PR", repo + "\n" + branch + " @ " + head + "\n→ "
                    + base + " @ " + baseHead + "\n\n" + visible(subject) + "\n" + visible(body) + "\n\n" + diff,
                    repo, branch, head);
            this.base = base;
            this.baseHead = baseHead;
            this.subject = subject;
            this.body = body;
        }
    }

    private final String token;
    private final Transport transport;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<HttpsURLConnection> inFlight = new AtomicReference<>();

    public GitHubClient(String token) {
        this(token, null);
    }

    GitHubClient(String token, Transport transport) {
        this.token = token == null ? "" : token;
        if (!this.token.isEmpty()) validateToken(this.token);
        this.transport = transport == null ? this::httpRequest : transport;
    }

    void cancel() {
        cancelled.set(true);
        HttpsURLConnection connection = inFlight.get();
        if (connection != null) connection.disconnect();
    }

    public static void validateToken(String token) {
        if (token == null || !token.matches("(github_pat_|gh[opstu]_)[A-Za-z0-9_]{16,300}"))
            throw new IllegalArgumentException("Вставьте personal access token GitHub.");
    }

    public Result contents(String repository, String ref, String path) throws Exception {
        String repo = repository(repository);
        String selected = selectedRef(ref);
        String relative = filePath(path, true);
        String route = repoRoute(repo) + "/contents" + (relative.isEmpty() ? "" : "/" + pathRoute(relative));
        String response = get(route + (selected.isEmpty() ? "" : "?ref=" + segment(selected)));
        String url = repositoryUrl(repo);
        if (response.trim().startsWith("[")) {
            JSONArray entries = new JSONArray(response);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < entries.length() && text.length() < MAX_PREVIEW_CHARS; i++) {
                JSONObject row = entries.getJSONObject(i);
                text.append("dir".equals(row.optString("type")) ? "▸ " : "  ")
                        .append(visible(row.optString("path"))).append('\n');
            }
            return new Result(relative.isEmpty() ? repo : relative, limited(text), url);
        }
        JSONObject file = new JSONObject(response);
        if (!"file".equals(file.optString("type"))) {
            throw new IOException("GitHub сообщает, что путь указывает на " + file.optString("type", "другой объект") + ".");
        }
        if (file.optLong("size", -1) > MAX_FILE_BYTES || !"base64".equals(file.optString("encoding"))) {
            return new Result(relative, "Файл открыт для просмотра на сайте GitHub.", webFile(repo, selected, relative));
        }
        try {
            return new Result(relative, readableFile(file), webFile(repo, selected, relative), relative);
        } catch (IOException inaccessible) {
            return new Result(relative, inaccessible.getMessage(), webFile(repo, selected, relative));
        }
    }

    public Result tree(String repository, String ref) throws Exception {
        String repo = repository(repository);
        String selected = refOrDefault(repo, ref);
        JSONObject commit = commit(repo, selected);
        String sha = objectId(commit.getJSONObject("commit").getJSONObject("tree"), "sha");
        JSONObject tree = object(repoRoute(repo) + "/git/trees/" + sha + "?recursive=1");
        JSONArray rows = tree.getJSONArray("tree");
        StringBuilder text = new StringBuilder("Объектов в ответе GitHub: ").append(rows.length()).append('\n');
        if (tree.optBoolean("truncated"))
            text.append("Дерево GitHub усечено. Откройте нужный каталог через «Файлы».\n");
        for (int i = 0; i < rows.length() && text.length() < MAX_PREVIEW_CHARS; i++) {
            JSONObject row = rows.getJSONObject(i);
            text.append("tree".equals(row.optString("type")) ? "▸ " : "  ")
                    .append(visible(row.optString("path"))).append('\n');
        }
        return new Result("Дерево · " + selected, limited(text), repositoryUrl(repo) + "/tree/" + pathRoute(selected));
    }

    public Result commits(String repository, String ref) throws Exception {
        String repo = repository(repository);
        String selected = selectedRef(ref);
        JSONArray rows = new JSONArray(get(repoRoute(repo) + "/commits?per_page=20"
                + (selected.isEmpty() ? "" : "&sha=" + segment(selected))));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.getJSONObject(i);
            JSONObject data = item.getJSONObject("commit");
            JSONObject author = data.optJSONObject("author");
            text.append(objectId(item, "sha")).append('\n');
            if (author != null) text.append(visible(author.optString("date"))).append(' ')
                    .append(visible(author.optString("name"))).append('\n');
            text.append(visible(data.optString("message"))).append("\n\n");
        }
        return new Result("Последние коммиты", text.length() == 0 ? "Нет коммитов." : limited(text), repositoryUrl(repo));
    }

    public Result compare(String repository, String base, String head) throws Exception {
        String repo = repository(repository);
        String baseRef = refOrDefault(repo, base);
        String headRef = selectedRef(head);
        if (headRef.isEmpty()) throw new IllegalArgumentException("Укажите ветку или SHA для сравнения.");
        return new Result("Diff · " + baseRef + "… " + headRef,
                compareText(comparison(repo, baseRef, headRef)),
                repositoryUrl(repo) + "/compare/" + segment(baseRef) + "..." + segment(headRef));
    }

    public Result actions(String repository, String branch) throws Exception {
        String repo = repository(repository);
        String selected = branch == null || branch.trim().isEmpty() ? "" : branch(branch);
        JSONObject result = object(repoRoute(repo) + "/actions/runs?per_page=20"
                + (selected.isEmpty() ? "" : "&branch=" + segment(selected)));
        JSONArray rows = result.getJSONArray("workflow_runs");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.getJSONObject(i);
            text.append(visible(item.optString("name"))).append(" #").append(item.optLong("run_number")).append('\n')
                    .append(runStatus(item.optString("status"),
                            item.isNull("conclusion") ? "" : item.optString("conclusion"))).append(" · ")
                    .append(visible(item.optString("head_branch"))).append('\n')
                    .append(visible(item.optString("updated_at"))).append(' ')
                    .append(visible(item.optString("head_sha"))).append("\n\n");
        }
        long run = rows.length() == 0 ? 0L : rows.getJSONObject(0).optLong("id", 0);
        return new Result("GitHub Actions", text.length() == 0 ? "Запусков нет." : limited(text),
                repositoryUrl(repo) + (run > 0 ? "/actions/runs/" + run : "/actions"));
    }

    Result repositories(String owner, int page) throws Exception {
        String account = owner.trim();
        if (account.isEmpty()) {
            if (token.isEmpty()) throw new IOException("Сохраните токен GitHub или укажите владельца публичного репозитория.");
        } else if (!account.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) {
            throw new IllegalArgumentException("Укажите имя пользователя или организации GitHub.");
        }
        String route = account.isEmpty() ? "/user/repos" : "/users/" + account + "/repos";
        String link = account.isEmpty() ? "https://github.com/dashboard" : "https://github.com/" + account;
        JSONArray rows = new JSONArray(get(route + pageQuery(page) + "&sort=updated"));
        StringBuilder text = pageHeader(page, rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            text.append(visible(row.getString("full_name"))).append(" · ")
                    .append(visible(row.optString("default_branch"))).append(
                            row.optBoolean("private") ? " · приватный\n" : "\n");
        }
        return new Result("Репозитории GitHub", text.toString(), link);
    }

    Result branches(String repository, int page) throws Exception {
        String repo = repository(repository);
        BranchPage result = listBranches(repo, page);
        StringBuilder text = pageHeader(page, result.branches.size()).append("Ветка по умолчанию: ")
                .append(visible(result.defaultBranch)).append('\n');
        for (BranchInfo row : result.branches) {
            text.append(visible(row.name)).append(" · ").append(row.sha)
                    .append(row.protectedBranch ? " · защищена\n" : "\n");
        }
        return new Result("Ветки · " + repo, text.toString(), repositoryUrl(repo) + "/branches");
    }

    public BranchPage listBranches(String repository, int page) throws Exception {
        String repo = repository(repository);
        String main = defaultBranch(repo);
        JSONArray rows = new JSONArray(get(repoRoute(repo) + "/branches" + pageQuery(page)));
        List<BranchInfo> branches = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) branches.add(new BranchInfo(rows.getJSONObject(i)));
        return new BranchPage(main, branches, rows.length() == PAGE_SIZE);
    }

    Result files(String repository, String ref, String path, int offset, int limit) throws Exception {
        String repo = repository(repository);
        String selected = refOrDefault(repo, ref);
        String relative = filePath(path, true);
        JSONObject commit = commit(repo, selected);
        String head = objectId(commit, "sha");
        String root = objectId(commit.getJSONObject("commit").getJSONObject("tree"), "sha");
        TreeEntry requested = relative.isEmpty() ? null : findEntry(repo, root, relative, new HashMap<>());
        if (!relative.isEmpty() && (requested == null || !"tree".equals(requested.type)))
            throw new IOException("Каталог не найден в снимке " + head + ".");
        JSONArray rows = gitTree(repo, requested == null ? root : requested.sha);
        checkSlice(offset, limit);
        StringBuilder text = new StringBuilder();
        int end = offset;
        while (end < rows.length() && end - offset < limit) {
            TreeEntry row = new TreeEntry(rows.getJSONObject(end), relative.isEmpty() ? "" : relative + "/");
            String line = row.type + " · " + visible(row.path) + (row.size < 0 ? "" : " · " + row.size + " B") + "\n";
            if (text.length() + line.length() > 4_300 && end > offset) break;
            text.append(line);
            end++;
        }
        String header = "ref=" + selected + " commit_sha=" + head + " offset=" + offset
                + " next_offset=" + (end < rows.length() ? end : "none") + " entries=" + rows.length();
        return new Result("Файлы · " + repo, header + "\n" + text,
                repositoryUrl(repo) + "/tree/" + head + (relative.isEmpty() ? "" : "/" + pathRoute(relative)));
    }

    Result readSource(String repository, String ref, String path, int offset, int limit, String apiKey) throws Exception {
        checkSlice(offset, limit);
        String repo = repository(repository);
        String selected = refOrDefault(repo, ref);
        String relative = filePath(path, false);
        JSONObject commit = commit(repo, selected);
        String head = objectId(commit, "sha");
        String root = objectId(commit.getJSONObject("commit").getJSONObject("tree"), "sha");
        TreeEntry entry = findEntry(repo, root, relative, new HashMap<>());
        if (entry == null) throw new IOException("Файл не найден в снимке " + head + ".");
        if (!"blob".equals(entry.type) || !("100644".equals(entry.mode) || "100755".equals(entry.mode)))
            throw new IOException("Объект не является обычным файлом.");
        if (entry.size > MAX_AGENT_FILE_BYTES) throw new IOException("Файл превышает бюджет чтения GitHub API.");
        String source = redactSource(decodeSource(readBlob(repo, entry.sha, MAX_AGENT_FILE_BYTES)), apiKey);
        int start = Math.min(offset, source.length());
        if (start > 0 && start < source.length() && Character.isLowSurrogate(source.charAt(start))) start--;
        int end = start + Math.min(Math.min(limit, 4500), source.length() - start);
        if (end > start && end < source.length() && Character.isHighSurrogate(source.charAt(end - 1))) {
            end += end - start == 1 ? 1 : -1;
        }
        String header = "commit_sha=" + head + " offset=" + start + " next_offset="
                + (end < source.length() ? end : "none") + " total_chars=" + source.length();
        return new Result(relative, header + "\n" + source.substring(start, end), webFile(repo, head, relative));
    }

    Result searchCode(String repository, String query, int page) throws Exception {
        String repo = repository(repository);
        if (token.isEmpty()) throw new IOException("Для поиска кода требуется токен GitHub.");
        String search = query.trim();
        if (search.isEmpty() || search.length() > 256 || search.chars().anyMatch(Character::isISOControl)
                || search.matches("(?is).*(?:repo|org|user):.*"))
            throw new IllegalArgumentException("Укажите запрос до 256 символов без фильтров repo:, org: или user:.");
        JSONObject result = object("/search/code" + pageQuery(page) + "&q=" + segment(search + " repo:" + repo));
        JSONArray rows = result.getJSONArray("items");
        StringBuilder text = pageHeader(page, rows.length()).append("Совпадений: ")
                .append(result.optLong("total_count")).append(" · поиск по ветке по умолчанию\n");
        if (result.optBoolean("incomplete_results")) text.append("GitHub вернул неполные результаты.\n");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (repo.equalsIgnoreCase(row.getJSONObject("repository").getString("full_name")))
                text.append(visible(row.getString("path"))).append('\n');
        }
        return new Result("Поиск · " + repo, text.toString(),
                "https://github.com/search?type=code&q=" + segment(search + " repo:" + repo));
    }

    Result commitInfo(String repository, String ref, boolean history, int page) throws Exception {
        String repo = repository(repository);
        String selected = refOrDefault(repo, ref);
        String root = repoRoute(repo) + "/commits";
        if (history) {
            JSONArray rows = new JSONArray(get(root + pageQuery(page) + "&sha=" + segment(selected)));
            StringBuilder text = pageHeader(page, rows.length());
            for (int i = 0; i < rows.length() && text.length() < MAX_PREVIEW_CHARS; i++) {
                JSONObject row = rows.getJSONObject(i);
                text.append(objectId(row, "sha")).append(" · ")
                        .append(summaryLine(row.getJSONObject("commit").optString("message"))).append('\n');
            }
            return new Result("История · " + selected, limited(text), repositoryUrl(repo) + "/commits/" + pathRoute(selected));
        }
        JSONObject commit = object(root + "/" + segment(selected) + pageQuery(page));
        String sha = objectId(commit, "sha");
        JSONArray files = commit.optJSONArray("files");
        StringBuilder text = pageHeader(page, files == null ? 0 : files.length()).append(sha).append('\n');
        JSONObject detail = commit.getJSONObject("commit");
        JSONObject author = detail.optJSONObject("author");
        if (author != null) text.append(visible(author.optString("name"))).append(" · ")
                .append(visible(author.optString("date"))).append('\n');
        text.append(visible(detail.optString("message"))).append('\n');
        if (files != null) {
            for (int i = 0; i < files.length() && text.length() < MAX_PREVIEW_CHARS; i++) {
                JSONObject file = files.getJSONObject(i);
                text.append(visible(file.optString("status"))).append(" · ")
                        .append(visible(file.getString("filename"))).append(" +")
                        .append(file.optInt("additions")).append(" −").append(file.optInt("deletions")).append('\n');
            }
        }
        return new Result("Коммит · " + selected, limited(text), repositoryUrl(repo) + "/commit/" + sha);
    }

    Result diff(String repository, String base, String head, int fileOffset, int patchOffset, String apiKey) throws Exception {
        String repo = repository(repository);
        String original = refOrDefault(repo, base);
        String changed = selectedRef(head);
        if (changed.isEmpty()) throw new IllegalArgumentException("Укажите ветку или SHA для сравнения.");
        if (fileOffset < 0 || patchOffset < 0) throw new IllegalArgumentException("Смещение должно быть неотрицательным.");
        JSONObject comparison = comparison(repo, original, changed);
        JSONArray files = comparison.optJSONArray("files");
        String url = repositoryUrl(repo) + "/compare/" + segment(original) + "..." + segment(changed);
        if (files == null) throw new IOException("GitHub не вернул список изменённых файлов. Откройте " + url);
        String overview = "ahead_by=" + comparison.optInt("ahead_by") + " behind_by="
                + comparison.optInt("behind_by") + " files=" + files.length()
                + (files.length() < 300 ? "" : " · достигнут лимит GitHub в 300 файлов") + "\n";
        if (files.length() == 0)
            return new Result("Diff · " + repo, overview + "Файлы не изменились.", url);
        if (fileOffset >= files.length()) throw new IllegalArgumentException("Смещение файла выходит за список diff.");
        JSONObject file = files.getJSONObject(fileOffset);
        String path = visible(file.getString("filename"));
        String earlier = file.optString("previous_filename");
        String change = visible(file.optString("status")) + " · " + path
                + (earlier.isEmpty() ? "" : " ← " + visible(earlier))
                + " · +" + file.optInt("additions") + " −" + file.optInt("deletions");
        String patch = redactSource(file.optString("patch"), apiKey);
        int start = Math.min(patchOffset, patch.length());
        if (start > 0 && start < patch.length() && Character.isLowSurrogate(patch.charAt(start))) start--;
        int end = Math.min(start + 4_000, patch.length());
        if (end < patch.length() && Character.isHighSurrogate(patch.charAt(end - 1))) end--;
        String header = "file_offset=" + fileOffset + " next_file_offset="
                + (fileOffset + 1 < files.length() ? fileOffset + 1 : "none")
                + " patch_offset=" + start + " next_patch_offset=" + (end < patch.length() ? end : "none");
        return new Result("Diff · " + repo, header + "\n" + overview + change + "\n"
                + (patch.isEmpty() ? "GitHub не вернул текст diff этого файла." : visible(patch.substring(start, end))), url);
    }

    private String redactSource(String source, String apiKey) {
        return SensitiveData.redact(SensitiveData.redact(source, token), apiKey);
    }

    Result workflowStatus(String repository, String branch, String headSha, long runId, int page) throws Exception {
        String repo = repository(repository);
        String route = repoRoute(repo) + "/actions/runs";
        if (runId < 0) throw new IllegalArgumentException("Укажите положительный run_id.");
        if (runId > 0) {
            if (!branch.isEmpty() || !headSha.isEmpty())
                throw new IllegalArgumentException("Для run_id не нужны фильтры ветки или SHA.");
            return new Result("Запуск GitHub Actions", workflowLine(object(route + "/" + runId)),
                    repositoryUrl(repo) + "/actions/runs/" + runId);
        }
        String selected = selectedRef(branch);
        if (!headSha.isEmpty()) requireObjectId(headSha);
        JSONObject result = object(route + pageQuery(page) + (selected.isEmpty() ? "" : "&branch=" + segment(selected))
                + (headSha.isEmpty() ? "" : "&head_sha=" + headSha));
        JSONArray rows = result.getJSONArray("workflow_runs");
        StringBuilder text = pageHeader(page, rows.length());
        for (int i = 0; i < rows.length(); i++) text.append(workflowLine(rows.getJSONObject(i)));
        return new Result("GitHub Actions · " + repo, text.toString(), repositoryUrl(repo) + "/actions");
    }

    private static String workflowLine(JSONObject row) {
        String result = row.isNull("conclusion") ? "" : visible(row.optString("conclusion"));
        return visible(row.optString("name")) + " run_id=" + row.optLong("id")
                + " · " + visible(row.optString("status")) + (result.isEmpty() ? "" : " / " + result)
                + " · " + visible(row.optString("head_branch")) + " · " + visible(row.optString("head_sha"))
                + " · " + visible(row.optString("updated_at")) + "\n";
    }

    private static final int PAGE_SIZE = 20;

    private static String pageQuery(int page) {
        if (page < 1) throw new IllegalArgumentException("Номер страницы должен быть положительным.");
        return "?per_page=" + PAGE_SIZE + "&page=" + page;
    }

    private static StringBuilder pageHeader(int page, int entries) {
        return new StringBuilder("page=").append(page).append(" next_page=")
                .append(entries < PAGE_SIZE ? "none" : ((long) page + 1)).append('\n');
    }

    private static String summaryLine(String text) {
        int end = text.indexOf('\n');
        if (end < 0) end = text.length();
        return visible(text.substring(0, Math.min(end, 160)));
    }

    private static void checkSlice(int offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("Укажите offset ≥ 0 и limit ≥ 1.");
    }

    /** Resolve the ref once and enumerate its immutable tree for project import. */
    public RepositorySnapshot snapshot(String repository, String ref) throws Exception {
        String repo = repository(repository);
        String selected = refOrDefault(repo, ref);
        JSONObject commit = commit(repo, selected);
        String head = objectId(commit, "sha");
        String root = objectId(commit.getJSONObject("commit").getJSONObject("tree"), "sha");
        try {
            JSONObject result = object(repoRoute(repo) + "/git/trees/" + root + "?recursive=1");
            if (!result.optBoolean("truncated"))
                return new RepositorySnapshot(repo, selected, head, treeEntries(result.getJSONArray("tree"), ""));
        } catch (ResponseTooLargeException largeTree) {
            // Discover the complete snapshot by walking bounded directory responses.
        }
        ArrayList<TreeEntry> entries = new ArrayList<>(treeEntries(gitTree(repo, root), ""));
        ArrayDeque<TreeEntry> pending = new ArrayDeque<>();
        for (TreeEntry entry : entries) if ("tree".equals(entry.type)) pending.add(entry);
        while (!pending.isEmpty()) {
            TreeEntry directory = pending.removeFirst();
            for (TreeEntry child : treeEntries(gitTree(repo, directory.sha), directory.path + "/")) {
                entries.add(child);
                if ("tree".equals(child.type)) pending.addLast(child);
            }
        }
        return new RepositorySnapshot(repo, selected, head, entries);
    }

    /** Read one selected blob and verify the bytes against its Git object ID. */
    public byte[] readBlob(String repository, String sha, int maxBytes) throws Exception {
        String repo = repository(repository);
        requireObjectId(sha);
        if (maxBytes < 0) throw new IllegalArgumentException("Лимит чтения файла не может быть отрицательным.");
        JSONObject blob = object(repoRoute(repo) + "/git/blobs/" + sha);
        if (!sha.equals(objectId(blob, "sha")) || !"base64".equals(blob.optString("encoding")))
            throw new IOException("GitHub вернул неожиданный объект файла.");
        long size = blob.getLong("size");
        if (size < 0 || size > maxBytes) throw new IOException("Файл превышает лимит чтения в " + maxBytes + " байт.");
        byte[] bytes = decodeBase64(blob);
        if (bytes.length != size || !sha.equals(blobId(bytes)))
            throw new IOException("Проверка целостности файла GitHub не пройдена.");
        return bytes;
    }

    static String blobId(byte[] bytes) throws Exception {
        // GitHub object IDs are Git's SHA-1 of the object header and bytes.
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }

    private static List<TreeEntry> treeEntries(JSONArray rows, String prefix) throws Exception {
        List<TreeEntry> entries = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) entries.add(new TreeEntry(rows.getJSONObject(i), prefix));
        return entries;
    }

    private JSONArray gitTree(String repo, String sha) throws Exception {
        JSONObject result = object(repoRoute(repo) + "/git/trees/" + sha);
        if (result.optBoolean("truncated"))
            throw new IOException("GitHub вернул неполный каталог. Уменьшите область запроса.");
        return result.getJSONArray("tree");
    }

    public Change prepareBranch(String repository, String sourceRef, String newBranch) throws Exception {
        requireToken();
        String repo = repository(repository);
        String target = branch(newBranch);
        rejectCredential(target);
        String defaultBranch = defaultBranch(repo);
        rejectProtectedBranch(target, defaultBranch);
        String source = selectedRef(sourceRef);
        if (source.isEmpty()) source = defaultBranch;
        String head = objectId(commit(repo, source), "sha");
        if (findBranchHead(repo, target) != null) throw new IOException("Ветка уже существует.");
        return new BranchChange(repo, target, head, source);
    }

    public WorkingFile openForEditing(String repository, String targetBranch, String path) throws Exception {
        requireToken();
        String repo = repository(repository);
        String target = branch(targetBranch);
        String relative = filePath(path, false);
        requireWorkingBranch(repo, target);
        String head = branchHead(repo, target);
        return new WorkingFile(repo, target, head, relative, originalFile(repo, head, relative));
    }

    public Change prepareFile(WorkingFile opened, String replacement, String message) throws Exception {
        requireToken();
        String title = headline(message);
        if (replacement == null) throw new IllegalArgumentException("Укажите новое содержимое файла.");
        FileEdit edit = new FileEdit(opened.path, replacement, "");
        String diff = reviewFile(edit, opened.text, MAX_FILE_BYTES);
        rejectCredential(title);
        return new CommitChange(opened.repo, opened.branch, opened.head,
                java.util.Collections.singletonList(edit), title, diff);
    }

    Change prepareCommit(String repository, String targetBranch, String message, List<FileEdit> edits) throws Exception {
        requireToken();
        String repo = repository(repository);
        String target = branch(targetBranch);
        String title = headline(message);
        rejectCredential(title);
        requireWorkingBranch(repo, target);
        if (edits.isEmpty()) throw new IllegalArgumentException("Укажите файлы для коммита.");
        Set<String> paths = new HashSet<>();
        for (FileEdit edit : edits) {
            filePath(edit.path, false);
            rejectCredential(edit.path);
            if (!paths.add(edit.path)) throw new IllegalArgumentException("Путь повторяется: " + edit.path);
        }
        for (String path : paths) {
            for (int parent = path.lastIndexOf('/'); parent >= 0; parent = path.lastIndexOf('/', parent - 1)) {
                if (paths.contains(path.substring(0, parent)))
                    throw new IllegalArgumentException("Изменения должны относиться к отдельным файлам: " + path);
            }
        }
        String head = branchHead(repo, target);
        String root = commitTree(repo, head);
        Map<String, JSONArray> cache = new HashMap<>();
        StringBuilder preview = new StringBuilder();
        List<FileEdit> resolved = new ArrayList<>(edits.size());
        long payloadBytes = 1024;
        for (FileEdit edit : edits) {
            String before = originalFile(repo, root, edit.path, cache, MAX_AGENT_FILE_BYTES);
            String content = edit.oldText == null ? edit.content
                    : SafeWorkspace.replaceFragment(before, edit.oldText, edit.content, edit.replaceAll);
            if (content == null && before == null)
                throw new IOException("Не найден файл для удаления: " + edit.path);
            FileEdit file = new FileEdit(edit.path, content, edit.reason);
            preview.append(reviewFile(file, before, MAX_AGENT_FILE_BYTES)).append('\n');
            if (preview.length() > MAX_PREVIEW_CHARS)
                throw new IOException("Diff слишком велик для полной проверки. Подготовьте отдельные коммиты.");
            int contentSize = content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
            payloadBytes += 6L * edit.path.length() + 128 + 4L * ((contentSize + 2) / 3);
            if (payloadBytes > MAX_RESPONSE_BYTES)
                throw new IOException("Коммит превышает бюджет запроса GitHub API. Подготовьте отдельные коммиты.");
            resolved.add(file);
        }
        return new CommitChange(repo, target, head, resolved, title, preview.toString());
    }

    private String reviewFile(FileEdit edit, String before, int maxFileBytes) throws Exception {
        rejectCredential(edit.path);
        rejectCredential(edit.reason);
        if (edit.content != null) checkReplacement(edit.content, maxFileBytes);
        String diff = visible(WorkDiff.between(edit.path, before, edit.content));
        if (diff.isEmpty()) throw new IOException("Текст файла не изменился: " + edit.path);
        if (!edit.reason.isEmpty()) diff = edit.path + "\nПричина · " + visible(edit.reason) + "\n" + diff;
        if (diff.length() > MAX_PREVIEW_CHARS)
            throw new IOException("Diff слишком велик для полной проверки. Подготовьте изменение меньшего размера.");
        return diff;
    }

    private void checkReplacement(String replacement, int maxFileBytes) {
        if (replacement.length() > maxFileBytes)
            throw new IllegalArgumentException("Файл превышает лимит записи в " + maxFileBytes + " байт.");
        byte[] bytes = replacement.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFileBytes || !replacement.equals(new String(bytes, StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Файл должен быть корректным UTF-8 и содержать до " + maxFileBytes + " байт.");
        requireText(replacement);
        rejectCredential(replacement);
    }

    public Change preparePullRequest(String repository, String targetBranch, String baseBranch, String subject,
                                     String body) throws Exception {
        requireToken();
        String repo = repository(repository);
        String headBranch = branch(targetBranch);
        String base = branch(refOrDefault(repo, baseBranch));
        if (headBranch.equals(base)) throw new IllegalArgumentException("Укажите две разные ветки.");
        String title = headline(subject);
        if (body == null || body.length() > 16_000)
            throw new IllegalArgumentException("Описание PR должно содержать до 16 000 символов.");
        rejectCredential(title);
        rejectCredential(body);
        String head = branchHead(repo, headBranch);
        String baseHead = branchHead(repo, base);
        JSONObject compare = comparison(repo, baseHead, head);
        if (compare.optInt("ahead_by", 0) <= 0)
            throw new IOException("В исходной ветке нет новых коммитов для этого PR.");
        String link = repositoryUrl(repo) + "/compare/" + baseHead + "..." + head;
        return new PullChange(repo, headBranch, head, base, baseHead, title, body,
                "Сравнение проверяемых коммитов · " + link + "\n\n" + compareText(compare));
    }

    /** Each applied plan is one-time, including after an ambiguous network error. */
    public Result apply(Change change) throws Exception {
        requireToken();
        if (change == null || !change.submitted.compareAndSet(false, true))
            throw new IllegalStateException("Создайте новое превью для этой операции.");
        if (change instanceof BranchChange) {
            String expectedRef = "refs/heads/" + change.branch;
            JSONObject result = post(repoRoute(change.repo) + "/git/refs", new JSONObject()
                    .put("ref", expectedRef).put("sha", change.head));
            if (!expectedRef.equals(result.optString("ref")) || !change.head.equals(receiptId(result.optJSONObject("object"), "sha")))
                throw new AmbiguousWriteException(new IOException("Новая ветка не совпадает с превью."));
            return new Result("Ветка создана", expectedRef + "\n" + change.head,
                    repositoryUrl(change.repo) + "/tree/" + pathRoute(change.branch));
        }
        if (change instanceof CommitChange) {
            CommitChange commit = (CommitChange) change;
            requireWorkingBranch(commit.repo, commit.branch);
            JSONObject branch = new JSONObject().put("repositoryNameWithOwner", commit.repo)
                    .put("branchName", commit.branch);
            JSONArray additions = new JSONArray();
            JSONArray deletions = new JSONArray();
            StringBuilder paths = new StringBuilder(commit.branch).append('\n');
            for (FileEdit edit : commit.files) {
                JSONObject file = new JSONObject().put("path", edit.path);
                if (edit.content == null) deletions.put(file);
                else additions.put(file.put("contents", Base64.getEncoder().encodeToString(
                        edit.content.getBytes(StandardCharsets.UTF_8))));
                paths.append(visible(edit.path)).append('\n');
            }
            JSONObject fileChanges = new JSONObject();
            if (additions.length() > 0) fileChanges.put("additions", additions);
            if (deletions.length() > 0) fileChanges.put("deletions", deletions);
            JSONObject input = new JSONObject().put("branch", branch).put("expectedHeadOid", commit.head)
                    .put("message", new JSONObject().put("headline", commit.message))
                    .put("fileChanges", fileChanges);
            JSONObject result = post("/graphql", new JSONObject().put("query", COMMIT_MUTATION)
                    .put("variables", new JSONObject().put("input", input)));
            JSONArray errors = result.optJSONArray("errors");
            if (errors != null && errors.length() > 0)
                throw new IOException("GitHub отклонил коммит. Проверьте права токена и текущее состояние ветки, затем подготовьте новое превью.");
            String sha = receiptId(result.getJSONObject("data").getJSONObject("createCommitOnBranch")
                    .getJSONObject("commit"), "oid");
            return new Result("Коммит создан", sha + "\n" + paths, repositoryUrl(commit.repo) + "/commit/" + sha);
        }
        PullChange pr = (PullChange) change;
        if (!pr.head.equals(branchHead(pr.repo, pr.branch)) || !pr.baseHead.equals(branchHead(pr.repo, pr.base)))
            throw new IOException("Ветка изменилась после превью. Снова подготовьте PR.");
        JSONObject result = post(repoRoute(pr.repo) + "/pulls", new JSONObject()
                .put("head", pr.branch).put("base", pr.base).put("title", pr.subject).put("body", pr.body)
                .put("draft", true));
        int number = result.getInt("number");
        boolean sameHeads = pr.head.equals(receiptId(result.optJSONObject("head"), "sha"))
                && pr.baseHead.equals(receiptId(result.optJSONObject("base"), "sha"));
        return new Result("Черновик PR #" + number, pr.subject + (sameHeads ? "" :
                "\nВо время создания одна из веток изменилась. Проверьте фактический diff на GitHub."),
                repositoryUrl(pr.repo) + "/pull/" + number);
    }

    private static String runStatus(String status, String conclusion) {
        switch (conclusion) {
            case "success": return "Успешно";
            case "failure": return "Ошибка";
            case "cancelled": return "Отменён";
            case "skipped": return "Пропущен";
            case "timed_out": return "Истёк тайм-аут";
            case "action_required": return "Требуется действие";
            case "neutral": return "Нейтральный итог";
            default:
                switch (status) {
                    case "queued": return "В очереди";
                    case "in_progress": return "В работе";
                    case "waiting": return "Ожидает";
                    case "pending": return "Ожидает запуска";
                    case "requested": return "Запрошен";
                    case "completed": return "Завершён" + (conclusion.isEmpty() ? "" : " · " + visible(conclusion));
                    default: return status.isEmpty() ? "Статус неизвестен" : visible(status);
                }
        }
    }

    private static String compareText(JSONObject compare) {
        StringBuilder text = new StringBuilder()
                .append("Коммитов впереди: ").append(compare.optInt("ahead_by"))
                .append(" · позади: ").append(compare.optInt("behind_by")).append('\n');
        JSONArray files = compare.optJSONArray("files");
        if (files == null) return text.append("Список изменённых файлов недоступен.").toString();
        if (files.length() >= 300) text.append("Достигнут лимит GitHub в 300 файлов.\n");
        for (int i = 0; i < files.length() && text.length() < MAX_PREVIEW_CHARS; i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            text.append("\n").append(visible(file.optString("filename")))
                    .append("  +").append(file.optInt("additions"))
                    .append("  −").append(file.optInt("deletions"));
            String earlier = file.optString("previous_filename");
            if (!earlier.isEmpty()) text.append("  ← ").append(visible(earlier));
            text.append('\n');
            text.append(visible(file.optString("patch", "Текст diff недоступен."))).append('\n');
        }
        return limited(text);
    }

    private String originalFile(String repo, String head, String relative) throws Exception {
        return originalFile(repo, commitTree(repo, head), relative, new HashMap<>(), MAX_FILE_BYTES);
    }

    private String commitTree(String repo, String head) throws Exception {
        return objectId(object(repoRoute(repo) + "/git/commits/" + head).getJSONObject("tree"), "sha");
    }

    private String originalFile(String repo, String root, String relative, Map<String, JSONArray> trees,
                                int maxFileBytes) throws Exception {
        TreeEntry entry = findEntry(repo, root, relative, trees);
        if (entry == null) return null;
        if (!"100644".equals(entry.mode) || !"blob".equals(entry.type))
            throw new IOException("Для коммита поддерживаются обычные текстовые файлы Git.");
        if (entry.size > maxFileBytes) throw new IOException("Файл превышает лимит чтения в " + maxFileBytes + " байт.");
        return readableFile(object(repoRoute(repo) + "/git/blobs/" + entry.sha), maxFileBytes);
    }

    private TreeEntry findEntry(String repo, String root, String relative, Map<String, JSONArray> trees) throws Exception {
        String treeSha = root;
        String[] parts = relative.split("/");
        for (int i = 0; i < parts.length; i++) {
            JSONArray tree = trees.get(treeSha);
            if (tree == null) {
                tree = gitTree(repo, treeSha);
                trees.put(treeSha, tree);
            }
            JSONObject match = matchingEntry(tree, parts[i]);
            if (match == null) return null;
            TreeEntry entry = new TreeEntry(match, "");
            if (i == parts.length - 1) return entry;
            if (!"tree".equals(entry.type))
                throw new IOException("Родительский путь должен указывать на каталог внутри репозитория.");
            treeSha = entry.sha;
        }
        throw new IOException("Путь к файлу недоступен.");
    }

    private static JSONObject matchingEntry(JSONArray entries, String name) throws Exception {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.getJSONObject(i);
            if (name.equals(item.optString("path"))) return item;
        }
        return null;
    }

    private static String readableFile(JSONObject file) throws Exception {
        return readableFile(file, MAX_FILE_BYTES);
    }

    private static String readableFile(JSONObject file, int maxFileBytes) throws Exception {
        if (!"base64".equals(file.optString("encoding"))) throw new IOException("GitHub не вернул текст файла.");
        byte[] bytes = decodeBase64(file);
        if (bytes.length > maxFileBytes) throw new IOException("Файл превышает лимит чтения в " + maxFileBytes + " байт.");
        return decodeSource(bytes);
    }

    private static byte[] decodeBase64(JSONObject file) throws Exception {
        try {
            return Base64.getDecoder().decode(file.getString("content").replaceAll("\\s+", ""));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("GitHub вернул некорректное содержимое файла.", invalid);
        }
    }

    private static String decodeSource(byte[] bytes) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            requireText(text);
            return text;
        } catch (CharacterCodingException | IllegalArgumentException invalid) {
            throw new IOException("Файл не является обычным текстом UTF-8.");
        }
    }

    private static void requireText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r')
                throw new IllegalArgumentException("Файл содержит двоичные управляющие символы.");
        }
    }

    private static String visible(String raw) {
        StringBuilder shown = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r') shown.append('␍');
            else if ((Character.isISOControl(c) && c != '\n' && c != '\t') || (c >= '\u202A' && c <= '\u202E')
                    || (c >= '\u2066' && c <= '\u2069'))
                shown.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else shown.append(c);
        }
        return shown.toString();
    }

    private static String limited(StringBuilder text) {
        return text.length() <= MAX_PREVIEW_CHARS ? text.toString()
                : text.substring(0, MAX_PREVIEW_CHARS) + "\n\nПросмотр сокращён. Полный результат доступен на GitHub.";
    }

    private JSONObject comparison(String repo, String base, String head) throws Exception {
        return object(repoRoute(repo) + "/compare/" + segment(base) + "..." + segment(head));
    }

    private JSONObject commit(String repo, String ref) throws Exception {
        return object(repoRoute(repo) + "/commits/" + segment(ref));
    }

    private String findBranchHead(String repo, String branch) throws Exception {
        try {
            JSONObject ref = object(repoRoute(repo) + "/git/ref/heads/" + segment(branch));
            JSONObject object = ref.getJSONObject("object");
            if (!("refs/heads/" + branch).equals(ref.optString("ref")) || !"commit".equals(object.optString("type")))
                throw new IOException("GitHub вернул другую ссылку на ветку.");
            return objectId(object, "sha");
        } catch (ApiException error) {
            if (error.status == 404) return null;
            throw error;
        }
    }

    private String branchHead(String repo, String branch) throws Exception {
        String head = findBranchHead(repo, branch);
        if (head == null) throw new IOException("Ветка не найдена или недоступна.");
        return head;
    }

    private String refOrDefault(String repo, String ref) throws Exception {
        String selected = selectedRef(ref);
        return selected.isEmpty() ? defaultBranch(repo) : selected;
    }

    private String defaultBranch(String repo) throws Exception {
        return branch(object(repoRoute(repo)).getString("default_branch"));
    }

    private void requireWorkingBranch(String repo, String branch) throws Exception {
        rejectProtectedBranch(branch, defaultBranch(repo));
    }

    private static void rejectProtectedBranch(String target, String defaultBranch) {
        if ("main".equalsIgnoreCase(target) || target.equalsIgnoreCase(defaultBranch))
            throw new IllegalArgumentException("Для коммита создайте отдельную рабочую ветку.");
    }

    private void requireToken() {
        if (token.isEmpty()) throw new IllegalArgumentException("Сохраните токен GitHub, чтобы отправлять изменения.");
    }

    private void rejectCredential(String value) {
        if (value.contains(token))
            throw new IllegalArgumentException("Изменение содержит сохранённый токен GitHub.");
    }

    private static String repository(String input) {
        String repo = input == null ? "" : input.trim();
        if (repo.startsWith("https://github.com/")) {
            repo = repo.substring("https://github.com/".length());
            if (repo.endsWith("/")) repo = repo.substring(0, repo.length() - 1);
            if (repo.endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
        }
        if (!repo.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,38}/[A-Za-z0-9_.-]{1,100}")
                || repo.endsWith("/.") || repo.endsWith("/.."))
            throw new IllegalArgumentException("Укажите репозиторий в формате владелец/репозиторий.");
        return repo;
    }

    private static String headline(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > 200 || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Введите заголовок в одну строку, до 200 символов.");
        return text;
    }

    private static String selectedRef(String value) {
        return value == null || value.trim().isEmpty() ? "" : branch(value);
    }

    private static String branch(String value) {
        String name = value == null ? "" : value.trim();
        boolean invalid = name.isEmpty() || name.length() > 200 || name.startsWith("-")
                || name.startsWith("/") || name.endsWith("/") || name.endsWith(".") || name.contains("..")
                || name.contains("//") || name.contains("@{") || "@".equals(name) || name.startsWith("refs/")
                || name.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)
                        || Character.getType(c) == Character.FORMAT || "~^:?*[\\".indexOf(c) >= 0);
        for (String part : name.split("/")) invalid |= part.startsWith(".") || part.endsWith(".lock");
        if (invalid) throw new IllegalArgumentException("Укажите имя ветки, тег или SHA без префикса refs/.");
        return name;
    }

    private static String filePath(String value, boolean optional) {
        String path = value == null ? "" : value;
        if (optional && path.isEmpty()) return "";
        if (path.length() > 1024 || path.indexOf('\\') >= 0
                || path.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException("Укажите относительный путь внутри репозитория.");
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)
                    || ".git".equalsIgnoreCase(component))
                throw new IllegalArgumentException("Укажите относительный путь внутри репозитория.");
        }
        return path;
    }

    private static String objectId(JSONObject object, String field) throws Exception {
        String sha = object.getString(field);
        requireObjectId(sha);
        return sha;
    }

    private static void requireObjectId(String sha) throws IOException {
        if (!sha.matches("[a-f0-9]{40}")) throw new IOException("GitHub вернул некорректный SHA.");
    }

    private static String receiptId(JSONObject object, String field) throws AmbiguousWriteException {
        String sha = object == null ? "" : object.optString(field);
        try {
            requireObjectId(sha);
            return sha;
        } catch (IOException invalid) {
            throw new AmbiguousWriteException(invalid);
        }
    }

    private static String repoRoute(String repo) { return "/repos/" + repo; }

    private static String repositoryUrl(String repo) { return "https://github.com/" + repo; }

    private static String webFile(String repo, String ref, String relative) {
        return ref.isEmpty() ? repositoryUrl(repo) + "/blob/HEAD/" + pathRoute(relative)
                : repositoryUrl(repo) + "/blob/" + pathRoute(ref) + "/" + pathRoute(relative);
    }

    private static String pathRoute(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) parts[i] = segment(parts[i]);
        return String.join("/", parts);
    }

    private static String segment(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private JSONObject object(String path) throws Exception { return new JSONObject(get(path)); }

    private String get(String path) throws IOException {
        checkActive();
        if (!token.isEmpty()) rejectCredential(path);
        return transport.request("GET", path, null, token);
    }

    private JSONObject post(String path, JSONObject body) throws Exception {
        checkActive();
        try {
            return new JSONObject(transport.request("POST", path, body, token));
        } catch (ApiException serverRejected) {
            if (serverRejected.status >= 500 || serverRejected.status == 408)
                throw new AmbiguousWriteException(serverRejected);
            throw serverRejected;
        } catch (IOException | org.json.JSONException uncertain) {
            throw new AmbiguousWriteException(uncertain);
        }
    }

    private void checkActive() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("Запрос GitHub прерван.");
    }

    private String httpRequest(String method, String path, JSONObject body, String token) throws IOException {
        checkActive();
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('#') >= 0)
            throw new IllegalArgumentException("Некорректный путь API GitHub.");
        HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://api.github.com" + path)
                .toURL().openConnection();
        inFlight.set(connection);
        try {
            checkActive();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "KENT-CODE-Android");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(request.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(request); }
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw httpError(code, connection.getHeaderField("X-RateLimit-Remaining"),
                    connection.getHeaderField("X-RateLimit-Reset"), connection.getHeaderField("Retry-After"));
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    checkActive();
                    if (output.size() + size > MAX_RESPONSE_BYTES)
                        throw new ResponseTooLargeException();
                    output.write(buffer, 0, size);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            inFlight.compareAndSet(connection, null);
            connection.disconnect();
        }
    }

    static ApiException httpError(int status, String remaining) {
        return httpError(status, remaining, null, null);
    }

    private static ApiException httpError(int status, String remaining, String reset, String retryAfter) {
        long available = headerNumber(remaining);
        long resetEpoch = headerNumber(reset);
        long delay = headerNumber(retryAfter);
        String message;
        if (status == 429 || (status == 403 && (available == 0 || delay > 0))) {
            String retry = delay > 0 ? "Повторите через " + delay + " с."
                    : resetEpoch > 0 && resetEpoch < 253402300799L ? "Сброс лимита в " + Instant.ofEpochSecond(resetEpoch) + "."
                    : "Повторите позже.";
            message = "Лимит запросов GitHub исчерпан. " + retry;
        }
        else if (status >= 300 && status < 400)
            message = "GitHub изменил адрес ресурса. Проверьте имя репозитория или путь.";
        else if (status == 401)
            message = "GitHub не принял токен. Проверьте, действует ли он.";
        else if (status == 403)
            message = "GitHub отклонил запрос. Проверьте права токена и ограничения репозитория.";
        else if (status == 404)
            message = "Ресурс GitHub не найден или недоступен для этого токена.";
        else if (status == 409 || status == 422)
            message = "GitHub отклонил изменение. Проверьте состояние ветки, открытые PR и правила репозитория.";
        else if (status >= 500)
            message = "GitHub временно недоступен. Перед повторной записью проверьте состояние репозитория.";
        else message = "GitHub вернул HTTP " + status + ".";
        return new ApiException(status, message, available, resetEpoch);
    }

    private static long headerNumber(String value) {
        try {
            return value == null ? -1 : Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}
