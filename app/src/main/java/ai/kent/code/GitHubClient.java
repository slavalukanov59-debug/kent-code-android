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
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/** GitHub reads and explicitly reviewed writes. Never used as an agent tool. */
public final class GitHubClient {
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 48 * 1024;
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
        private final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
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

    private static final class FileChange extends Change {
        final String path;
        final String replacement;
        final String message;

        FileChange(String repo, String branch, String head, String path, String replacement,
                   String message, String diff) {
            super("Коммит файла", repo + " · " + branch + "\n" + head + "\n" + visible(message) + "\n\n" + diff,
                    repo, branch, head);
            this.path = path;
            this.replacement = replacement;
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

    public GitHubClient(String token) {
        this(token, GitHubClient::httpRequest);
    }

    GitHubClient(String token, Transport transport) {
        this.token = token == null ? "" : token;
        if (!this.token.isEmpty()) validateToken(this.token);
        this.transport = transport;
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

    public Change prepareBranch(String repository, String sourceRef, String newBranch) throws Exception {
        requireToken();
        String repo = repository(repository);
        String source = refOrDefault(repo, sourceRef);
        String target = branch(newBranch);
        rejectCredential(target);
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
        if (replacement == null || replacement.length() > MAX_FILE_BYTES)
            throw new IllegalArgumentException("Файл должен быть текстовым и содержать до 48 КиБ.");
        byte[] bytes = replacement.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES || !replacement.equals(new String(bytes, StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Файл должен быть корректным UTF-8 и содержать до 48 КиБ.");
        requireText(replacement);
        rejectCredential(opened.path);
        rejectCredential(title);
        rejectCredential(replacement);
        if (replacement.equals(opened.text)) throw new IOException("Текст файла не изменился.");
        String diff = visible(WorkDiff.between(opened.path, opened.text, replacement));
        if (diff.length() > MAX_PREVIEW_CHARS)
            throw new IOException("Diff слишком велик для полной проверки. Подготовьте изменение меньшего размера.");
        return new FileChange(opened.repo, opened.branch, opened.head, opened.path, replacement, title, diff);
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
        return new PullChange(repo, headBranch, head, base, baseHead, title, body, compareText(compare));
    }

    /** Each applied plan is one-time, including after an ambiguous network error. */
    public Result apply(Change change) throws Exception {
        requireToken();
        if (change == null || !change.submitted.compareAndSet(false, true))
            throw new IllegalStateException("Создайте новое превью для этой операции.");
        if (change instanceof BranchChange) {
            JSONObject result = post(repoRoute(change.repo) + "/git/refs", new JSONObject()
                    .put("ref", "refs/heads/" + change.branch).put("sha", change.head));
            return new Result("Ветка создана", result.getString("ref") + "\n" + change.head,
                    repositoryUrl(change.repo) + "/tree/" + pathRoute(change.branch));
        }
        if (change instanceof FileChange) {
            FileChange file = (FileChange) change;
            requireWorkingBranch(file.repo, file.branch);
            JSONObject branch = new JSONObject().put("repositoryNameWithOwner", file.repo)
                    .put("branchName", file.branch);
            JSONObject addition = new JSONObject().put("path", file.path).put("contents",
                    Base64.getEncoder().encodeToString(file.replacement.getBytes(StandardCharsets.UTF_8)));
            JSONObject input = new JSONObject().put("branch", branch).put("expectedHeadOid", file.head)
                    .put("message", new JSONObject().put("headline", file.message))
                    .put("fileChanges", new JSONObject().put("additions", new JSONArray().put(addition)));
            JSONObject result = post("/graphql", new JSONObject().put("query", COMMIT_MUTATION)
                    .put("variables", new JSONObject().put("input", input)));
            if (result.optJSONArray("errors") != null)
                throw new IOException("GitHub отклонил коммит. Проверьте права токена и текущее состояние ветки, затем подготовьте новое превью.");
            String sha = objectId(result.getJSONObject("data").getJSONObject("createCommitOnBranch")
                    .getJSONObject("commit"), "oid");
            return new Result("Файл закоммичен", file.branch + " · " + file.path + "\n" + sha,
                    repositoryUrl(file.repo) + "/commit/" + sha);
        }
        PullChange pr = (PullChange) change;
        if (!pr.head.equals(branchHead(pr.repo, pr.branch)) || !pr.baseHead.equals(branchHead(pr.repo, pr.base)))
            throw new IOException("Ветка изменилась после превью. Снова подготовьте PR.");
        JSONObject result = post(repoRoute(pr.repo) + "/pulls", new JSONObject()
                .put("head", pr.branch).put("base", pr.base).put("title", pr.subject).put("body", pr.body)
                .put("draft", true));
        int number = result.getInt("number");
        boolean sameHeads = pr.head.equals(objectId(result.getJSONObject("head"), "sha"))
                && pr.baseHead.equals(objectId(result.getJSONObject("base"), "sha"));
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
        JSONObject commit = object(repoRoute(repo) + "/git/commits/" + head);
        String treeSha = objectId(commit.getJSONObject("tree"), "sha");
        String[] parts = relative.split("/");
        for (int i = 0; i < parts.length; i++) {
            JSONObject tree = object(repoRoute(repo) + "/git/trees/" + treeSha);
            if (tree.optBoolean("truncated")) throw new IOException("GitHub вернул неполный каталог. Проверьте файл на GitHub.");
            JSONObject entry = matchingEntry(tree.getJSONArray("tree"), parts[i]);
            if (entry == null) return null;
            boolean leaf = i == parts.length - 1;
            if (leaf) {
                if (!"100644".equals(entry.optString("mode")) || !"blob".equals(entry.optString("type")))
                    throw new IOException("Для коммита поддерживаются обычные текстовые файлы Git.");
                if (entry.optLong("size", -1) > MAX_FILE_BYTES) throw new IOException("Для редактирования доступно до 48 КиБ.");
                return readableFile(object(repoRoute(repo) + "/git/blobs/" + objectId(entry, "sha")));
            }
            if (!"tree".equals(entry.optString("type")))
                throw new IOException("Родительский путь должен указывать на каталог внутри репозитория.");
            treeSha = objectId(entry, "sha");
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
        if (!"base64".equals(file.optString("encoding"))) throw new IOException("GitHub не вернул текст файла.");
        try {
            byte[] bytes = Base64.getDecoder().decode(file.getString("content").replaceAll("\\s+", ""));
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Локальный просмотр доступен до 48 КиБ.");
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
        if (branch.equals(defaultBranch(repo)))
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
        if (!sha.matches("[a-f0-9]{40}")) throw new IOException("GitHub вернул некорректный SHA.");
        return sha;
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
        if (!token.isEmpty()) rejectCredential(path);
        return transport.request("GET", path, null, token);
    }

    private JSONObject post(String path, JSONObject body) throws Exception {
        try {
            return new JSONObject(transport.request("POST", path, body, token));
        } catch (ApiException serverRejected) {
            throw serverRejected;
        } catch (IOException uncertain) {
            throw new IOException("Ответ GitHub не получен. Проверьте состояние ветки или списка PR перед повтором.", uncertain);
        }
    }

    private static String httpRequest(String method, String path, JSONObject body, String token) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Запрос GitHub прерван.");
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('#') >= 0)
            throw new IllegalArgumentException("Некорректный путь API GitHub.");
        HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://api.github.com" + path)
                .toURL().openConnection();
        try {
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
            if (code < 200 || code >= 300) throw httpError(code, connection.getHeaderField("X-RateLimit-Remaining"));
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Запрос GitHub прерван.");
                    if (output.size() + size > MAX_RESPONSE_BYTES)
                        throw new IOException("Ответ GitHub слишком большой. Откройте отдельный каталог или сравните меньший диапазон.");
                    output.write(buffer, 0, size);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    static ApiException httpError(int status, String remaining) {
        String message;
        if (status == 429 || (status == 403 && "0".equals(remaining)))
            message = "Лимит запросов GitHub исчерпан. Повторите позже.";
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
        return new ApiException(status, message);
    }
}
