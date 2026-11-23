package ai.kent.code;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Developer Mode operations on project directories and files. */
final class ProjectTools {
    private static final int MAX_TEXT_CHARS = 5_000;
    private final ProjectService projects;
    private final MissionStore missions;
    private final KentAgent.Events events;
    private final GitHubAgentTools.ClientSource github;
    private final File exportCache;
    private final Supplier<String[]> secretValues;
    private final Map<String, String> generatedRevisions = new HashMap<>();
    private final Object transferLock = new Object();
    private Thread transferThread;
    private GitHubClient importClient;
    private boolean interruptedByCancel;

    ProjectTools(ProjectService projects, MissionStore missions, KentAgent.Events events,
                 GitHubAgentTools.ClientSource github, File exportCache, Supplier<String[]> secretValues) {
        this.projects = projects;
        this.missions = missions;
        this.events = events;
        this.github = github;
        this.exportCache = exportCache;
        this.secretValues = secretValues;
    }

    ToolDispatcher.Result execute(String name, JSONObject args, String apiKey) {
        if (events.cancelled()) return new ToolDispatcher.Result("Действие остановлено.", false, true);
        MissionStore.Mission mission = missions.active();
        String project = args.optString("project", mission == null ? "" : mission.projectId);
        String path = args.optString("path");
        String target = path.isEmpty() ? project : project.isEmpty() ? path : project + "/" + path;
        String action = missions.startAction(name, SensitiveData.redact(target, apiKey), mutates(name));
        events.missionChanged();
        boolean transfer = "project_import".equals(name) || "project_export".equals(name);
        if (transfer) beginTransfer();
        Outcome outcome;
        try {
            outcome = events.cancelled() ? stoppedOrDenied() : run(name, args, project, path, apiKey);
        } catch (Exception failure) {
            outcome = new Outcome(errorText(failure), events.cancelled() ? MissionStore.INTERRUPTED : MissionStore.FAILED,
                    false, "", "");
        } finally {
            if (transfer) endTransfer();
        }
        String result = redact(outcome.text, apiKey);
        missions.finishAction(action, outcome.status, result, outcome.changed,
                redact(outcome.patch, apiKey), redact(outcome.reason, apiKey));
        events.missionChanged();
        return new ToolDispatcher.Result(result, MissionStore.DENIED.equals(outcome.status),
                !MissionStore.SUCCEEDED.equals(outcome.status));
    }

    void cancel() {
        GitHubClient client;
        synchronized (transferLock) {
            if (transferThread != null && !transferThread.isInterrupted()) {
                interruptedByCancel = true;
                transferThread.interrupt();
            }
            client = importClient;
        }
        if (client != null) client.cancel();
    }

    private void beginTransfer() {
        synchronized (transferLock) {
            transferThread = Thread.currentThread();
        }
    }

    private void endTransfer() {
        synchronized (transferLock) {
            if (interruptedByCancel) Thread.interrupted();
            interruptedByCancel = false;
            importClient = null;
            transferThread = null;
        }
    }

    private Outcome run(String name, JSONObject args, String project, String path, String apiKey) throws Exception {
        switch (name) {
            case "project_create":
                project = projects.create(required(args, "project"));
                missions.selectProject(project);
                return success("Папка проекта создана и выбрана: " + project + ". Добавьте исходные файлы.");
            case "project_open":
                project = projects.open(required(args, "project"));
                missions.selectProject(project);
                return success("Выбран проект: " + project);
            case "project_list": {
                List<String> items = projects.list();
                int offset = bounded(args, "offset", 0, 0, Integer.MAX_VALUE);
                int limit = bounded(args, "limit", 35, 1, 500);
                int end = (int) Math.min(items.size(), (long) offset + limit);
                List<String> page = offset >= items.size() ? Collections.emptyList() : items.subList(offset, end);
                return success(paths("Проекты (" + items.size() + ")", page, offset, end < items.size()));
            }
            case "project_tree": {
                project = selected(project);
                long offset = nonnegativeOffset(args);
                int limit = bounded(args, "limit", 50, 1, 500);
                ProjectService.TreePage page = projects.tree(project, args.optString("directory"),
                        offset, limit, args.optBoolean("recursive", true));
                return success(paths("Дерево проекта " + project, page.paths, offset, page.hasMore));
            }
            case "project_read": {
                project = selected(project);
                SafeWorkspace.TextPage page = projects.read(project, path, nonnegativeOffset(args),
                        bounded(args, "max_chars", MAX_TEXT_CHARS, 1, MAX_TEXT_CHARS));
                return success("Файл: " + path + " · " + page.bytes + " байт · " +
                        (page.eof ? "конец файла" : "next_offset=" + page.nextOffset) + "\n" + page.text);
            }
            case "project_write":
            case "project_edit":
                return change(name, args, selected(project), path, apiKey);
            case "project_delete":
                return delete(args, selected(project), path, apiKey);
            case "project_export":
                return export(selected(project));
            case "project_import":
                return importProject(args, required(args, "project"), apiKey);
            default:
                throw new IOException("Неизвестный инструмент проектов.");
        }
    }

    private Outcome change(String name, JSONObject args, String project, String path, String apiKey) throws Exception {
        String scopedPath = projects.filePath(project, path);
        String before = projects.currentText(project, path);
        String after = "project_write".equals(name) ? args.getString("content")
                : SafeWorkspace.replaceFragment(before, args.getString("old_text"), args.getString("new_text"),
                args.optBoolean("replace_all", false));
        if (!after.equals(redact(after, apiKey))) {
            throw new IOException("Текст содержит данные, похожие на закрытые учётные данные.");
        }
        if (Objects.equals(before, after)) return success("Содержимое файла уже совпадает: " + scopedPath);
        boolean owned = before == null || digest(before).equals(generatedRevisions.get(scopedPath));
        String patch = owned ? WorkDiff.preview(scopedPath, before, after) : WorkDiff.between(scopedPath, before, after);
        String reason = args.optString("reason").trim();
        if (!owned && !events.approve("Изменение файла · " + scopedPath,
                redact(reason.isEmpty() ? patch : reason + "\n\n" + patch, apiKey))) return stoppedOrDenied();
        if (events.cancelled()) return stoppedOrDenied();
        if (!projects.applyText(project, path, before, after)) {
            throw new IOException("Файл изменился после подготовки правки. Перечитайте его.");
        }
        if (owned) generatedRevisions.put(scopedPath, digest(after));
        else generatedRevisions.remove(scopedPath);
        return new Outcome("Файл сохранён: " + scopedPath, MissionStore.SUCCEEDED, true, patch, reason);
    }

    private Outcome delete(JSONObject args, String project, String path, String apiKey) throws Exception {
        ProjectService.DeletePlan plan = projects.prepareDelete(project, path);
        String reason = args.optString("reason").trim();
        String preview = plan.path + "\nОбъект переместится в корзину проектов. Его можно восстановить.";
        if (!reason.isEmpty()) preview += "\nПричина: " + reason;
        if (!events.approve("Переместить в корзину", redact(preview, apiKey)) || events.cancelled()) {
            return stoppedOrDenied();
        }
        ProjectService.TrashEntry trash = projects.delete(plan);
        MissionStore.Mission mission = missions.active();
        if (mission != null && (mission.projectId.equals(trash.project) ||
                mission.projectId.startsWith(trash.project + "/"))) missions.selectProject("");
        generatedRevisions.keySet().removeIf(key -> key.equals(trash.project) || key.startsWith(trash.project + "/"));
        return new Outcome("В корзине: " + trash.project + " · " + trash.id, MissionStore.SUCCEEDED, true, "", reason);
    }

    private Outcome export(String project) throws Exception {
        WorkspaceZipExporter.Archive archive = projects.exporter().stage(project, exportCache, secretValues.get());
        boolean accepted = false;
        try {
            if (!events.cancelled()) accepted = events.offerProjectExport(archive);
            if (!accepted) {
                if (events.cancelled()) return stoppedOrDenied();
                throw new IOException("Не удалось открыть сохранение проверенного ZIP. Повторите экспорт из менеджера проектов.");
            }
            return success("ZIP подготовлен и проверен: " + archive.suggestedName + "\nФайлов: " + archive.fileCount +
                    " · " + archive.bytes + " байт\nSHA-256 " + archive.sha256 +
                    "\nНажмите «Сохранить подготовленный ZIP» в чате и выберите место в системном окне Android.");
        } finally {
            if (!accepted) archive.close();
        }
    }

    private Outcome importProject(JSONObject args, String project, String apiKey) throws Exception {
        String repository = args.optString("repository").trim();
        String zip = args.optString("zip_path");
        if (repository.isEmpty() == zip.isEmpty()) {
            throw new IOException("Укажите один источник: repository или zip_path, либо выберите ZIP в менеджере проектов.");
        }
        ProjectService.ImportTarget destination = projects.prepareTarget(project);
        try (ProjectService.PreparedImport prepared = repository.isEmpty()
                ? projects.prepareWorkspaceZip(zip, args.optString("sha256"))
                : prepareGitHub(repository, args.optString("ref"))) {
            String source = prepared.source + "\nФайлов: " + prepared.fileCount + " · " + prepared.bytes + " байт" +
                    (prepared.skippedCount > 0 ? " · Пропущено: " + prepared.skippedCount : "") +
                    (prepared.sha256.isEmpty() ? "" : "\nSHA-256 " + prepared.sha256);
            boolean replace = destination.exists && events.approve("Заменить проект · " + project,
                    redact(source + "\nТекущая папка будет перемещена в корзину проектов целиком.", apiKey));
            if (destination.exists && !replace || events.cancelled()) return stoppedOrDenied();
            ProjectService.ImportResult result = projects.publish(prepared, destination, replace, events::cancelled);
            missions.selectProject(result.project);
            return new Outcome("Импортирован проект " + result.project + "\n" + source +
                    (result.previousProject == null ? "" : "\nПредыдущая папка в корзине: " + result.previousProject.id),
                    MissionStore.SUCCEEDED, result.fileCount > 0, "", "");
        }
    }

    private ProjectService.PreparedImport prepareGitHub(String repository, String ref) throws Exception {
        GitHubClient client = github.get();
        synchronized (transferLock) {
            ProjectService.checkInterrupted();
            if (events.cancelled()) throw new IOException("Импорт GitHub остановлен.");
            importClient = client;
        }
        return projects.prepareGitHub(client, repository, ref);
    }

    private String redact(String value, String apiKey) {
        String text = SensitiveData.redact(value, apiKey);
        for (String secret : secretValues.get()) {
            if (secret != null && secret.length() >= 8) text = text.replace(secret, "[секрет скрыт]");
        }
        return text;
    }

    private static String digest(String text) {
        return WorkspaceZipExporter.toHex(WorkspaceZipExporter.sha256Digest().digest(
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String paths(String title, List<String> items, long offset, boolean hasMore) {
        StringBuilder result = new StringBuilder(title);
        int count = 0;
        for (String item : items) {
            if (count > 0 && result.length() + item.length() > MAX_TEXT_CHARS) break;
            result.append('\n').append(item);
            count++;
        }
        if (count == 0 && items.isEmpty()) result.append("\nЗаписей нет.");
        if (hasMore || count < items.size()) result.append("\nnext_offset=").append(offset + count);
        return result.toString();
    }

    private static String selected(String project) throws IOException {
        if (project.isEmpty()) throw new IOException("Сначала выберите существующий проект или создайте папку проекта.");
        return project;
    }

    private static String required(JSONObject args, String name) throws IOException, JSONException {
        String value = args.getString(name);
        if (value.trim().isEmpty()) throw new IOException("Укажите параметр " + name);
        return value;
    }

    private static long nonnegativeOffset(JSONObject args) throws IOException, JSONException {
        long value = args.has("offset") ? args.getLong("offset") : 0;
        if (value < 0) throw new IOException("Смещение должно быть неотрицательным");
        return value;
    }

    private static int bounded(JSONObject args, String name, int fallback, int minimum, int maximum)
            throws IOException, JSONException {
        long value = args.has(name) ? args.getLong(name) : fallback;
        if (value < minimum || value > maximum) throw new IOException("Недопустимое значение параметра " + name);
        return (int) value;
    }

    private static String errorText(Exception error) {
        if (error instanceof SocketException || error instanceof SocketTimeoutException ||
                error instanceof UnknownHostException) return "Связь с GitHub недоступна. Импорт не завершён.";
        if (error instanceof JSONException) return "Проверьте обязательные параметры инструмента.";
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "Действие с проектом завершилось ошибкой." : message;
    }

    private static boolean mutates(String name) {
        switch (name) {
            case "project_create": case "project_write": case "project_edit": case "project_delete":
            case "project_import": case "project_export": return true;
            default: return false;
        }
    }

    private Outcome stoppedOrDenied() {
        return events.cancelled() ? new Outcome("Действие остановлено.", MissionStore.INTERRUPTED, false, "", "")
                : new Outcome("Пользователь отклонил действие.", MissionStore.DENIED, false, "", "");
    }

    private static Outcome success(String text) {
        return new Outcome(text, MissionStore.SUCCEEDED, false, "", "");
    }

    private static final class Outcome {
        final String text, status, patch, reason;
        final boolean changed;

        Outcome(String text, String status, boolean changed, String patch, String reason) {
            this.text = text;
            this.status = status;
            this.changed = changed;
            this.patch = patch;
            this.reason = reason;
        }
    }

    static void addTools(JSONArray tools) throws JSONException {
        tools.put(schema("project_create", "Create an actual private workspace directory and select it for this task.",
                strings("project"), "project"));
        tools.put(schema("project_open", "Select an existing workspace project for this task.",
                strings("project"), "project"));
        tools.put(schema("project_list", "List existing project directories. Use next_offset for more.",
                strings().put("offset", typed("integer")).put("limit", typed("integer"))));
        tools.put(schema("project_tree", "List files in the selected project or a relative subdirectory. Use next_offset for more.",
                strings("project", "directory").put("offset", typed("integer")).put("limit", typed("integer"))
                        .put("recursive", typed("boolean"))));
        tools.put(schema("project_read", "Read a UTF-8 source page. Pass the reported next_offset to continue.",
                strings("project", "path").put("offset", typed("integer")).put("max_chars", typed("integer")), "path"));
        tools.put(schema("project_write", "Save a new or replacement project file. Existing user files require approval.",
                strings("project", "path", "content", "reason"), "path", "content", "reason"));
        tools.put(schema("project_edit", "Replace an exact text fragment in a project file.",
                strings("project", "path", "old_text", "new_text", "reason").put("replace_all", typed("boolean")),
                "path", "old_text", "new_text", "reason"));
        tools.put(schema("project_delete", "Ask before moving a file or project to recoverable trash. Omit path for the whole project.",
                strings("project", "path", "reason"), "reason"));
        tools.put(schema("project_export", "Prepare and verify a real ZIP, then offer the user the Android save dialog.",
                strings("project")));
        tools.put(schema("project_import", "Import a GitHub repository at a ref, or a workspace ZIP at zip_path. Existing targets require approval.",
                strings("project", "repository", "ref", "zip_path", "sha256"), "project"));
    }

    private static JSONObject strings(String... names) throws JSONException {
        JSONObject properties = new JSONObject();
        for (String name : names) properties.put(name, typed("string"));
        return properties;
    }

    private static JSONObject typed(String type) throws JSONException {
        return new JSONObject().put("type", type);
    }

    private static JSONObject schema(String name, String description, JSONObject properties, String... mandatory)
            throws JSONException {
        JSONArray required = new JSONArray();
        for (String field : mandatory) required.put(field);
        JSONObject parameters = new JSONObject().put("type", "object").put("properties", properties)
                .put("required", required).put("additionalProperties", false);
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", name).put("description", description).put("parameters", parameters));
    }
}
