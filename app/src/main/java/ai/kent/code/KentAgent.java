package ai.kent.code;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Runs exclusively on the Activity's background executor. */
public final class KentAgent {
    public interface Events {
        void log(String title, String message);
        boolean approve(String title, String detail);
        boolean cancelled();
        void usage(TokenMeter.Snapshot snapshot);
        void missionChanged();
    }

    private static final String AI_FLEX_API = "https://ai-flex.online/v1/chat/completions";
    private static final String OPENROUTER_API = "https://openrouter.ai/api/v1/chat/completions";
    private static final String PROMPT =
            "Ты KENT CODE — ИИ-агент для разработки Android. Пиши на русском. " +
            "Тебе доступны инструменты работы с файлами в изолированной рабочей папке приложения. " +
            "Читай код перед правкой, сначала планируй минимальное изменение. " +
            "В доверенном режиме можно изменять файлы рабочей папки без повторных подтверждений. " +
            "run_command выполняет команды Android shell. В SAFE режиме — только с отдельным подтверждением, в AUTOPILOT — сразу. " +
            "Дополнительно доступны run_termux и run_adb через Termux и Shizuku; для этого нужен BRIDGE; подтверждение зависит от режима SAFE / AUTOPILOT. " +
            "run_termux запускает bash в Termux, Python/Git зависят от установленных пакетов. run_adb выполняет команду через rish с UID shell (НЕ root). " +
            "Перед выполнением кратко описывай действия; не запрашивай секреты/ключи/учётные данные, не выполняй скрытых или разрушительных действий. В AUTOPILOT инструменты исполняются сразу, сначала проверяй пути и минимизируй риски. " +
            "Вывод команд может попасть на сервер выбранного ИИ-провайдера в следующем запросе; не считывай приватные данные. " +
            "Никогда не утверждай, что действие выполнено, пока инструмент не подтвердил. " +
            "Не пытайся прочитать API-ключи или секретные файлы. " +
            "Не трогай чужие проекты и не выполняй операции с GitHub самостоятельно. " +
            "В долгих задачах поддерживай в рабочем проекте файл PROGRESS.md с текущим этапом, изменёнными файлами, результатами тестов и следующим действием. " +
            "После обрезки API-контекста перечитывай PROGRESS.md и состояние Git, никогда не выдумывай результаты прошлых шагов. " +
            "Mission Control: в начале длительной задачи вызови mission_checkpoint со стадией, коротким планом, следующим действием, completed=0 и total (число этапов). " +
            "После каждого проверенного этапа обновляй mission_checkpoint с фактами тестов и следующим действием. " +
            "Не отмечай этап завершённым до проверки результатов. При ограничении токенов сохрани прогресс локально и прекрати работу.";

    private final SafeWorkspace files;
    private final ShellRunner runner;
    private final TermuxBridge bridge;
    private final LocalTermuxBridge localBridge;
    private final SecureKeyStore secrets;
    private final JSONArray history = new JSONArray();
    // A bounded API context prevents quadratic billed-token growth.
    private static final int MAX_CONTEXT_CHARS = 32_000;
    private static final int MAX_TOOL_RESULT_CHARS = 5_000;
    private int lastRequestTokenEstimate = 0;
    private int lastInputEstimate = 0;
    private int lastOutputEstimate = 0;
    private final TokenMeter tokenMeter;
    private final MissionStore missionStore;

    private final Events events;

    public KentAgent(SafeWorkspace files, SecureKeyStore secrets, TermuxBridge bridge, LocalTermuxBridge localBridge, TokenMeter tokenMeter, MissionStore missionStore, Events events) {
        this.files = files;
        this.runner = new ShellRunner(files.root());
        this.bridge = bridge;
        this.localBridge = localBridge;
        this.secrets = secrets;
        this.tokenMeter = tokenMeter;
        this.missionStore = missionStore;
        this.events = events;
        history.put(message("system", PROMPT));
    }

    public void clear() {
        while (history.length() > 1) history.remove(history.length() - 1);
    }

    private static JSONObject message(String role, String content) {
        try { return new JSONObject().put("role", role).put("content", content); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private static JSONObject property(String type) {
        try { return new JSONObject().put("type", type); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private static JSONObject action(String name, String description, JSONObject properties, String... required) {
        try {
            JSONArray requiredNames = new JSONArray();
            for (String r : required) requiredNames.put(r);
            JSONObject params = new JSONObject().put("type", "object").put("properties", properties)
                    .put("required", requiredNames).put("additionalProperties", false);
            return new JSONObject().put("type", "function")
                    .put("function", new JSONObject().put("name", name).put("description", description)
                            .put("parameters", params));
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private static JSONArray tools() {
        try { return new JSONArray()
                .put(action("mission_checkpoint", "Save a durable Mission Control checkpoint: stage, verified facts, next action, completed and total stages. Update after every tested milestone.", new JSONObject()
                        .put("stage", property("string"))
                        .put("summary", property("string"))
                        .put("next", property("string"))
                        .put("completed", property("integer"))
                        .put("total", property("integer")),
                        "stage", "summary", "next", "completed", "total"))
                .put(action("list_files", "List project files and folders", new JSONObject()))
                .put(action("read_file", "Read an existing UTF-8 file in app workspace", new JSONObject()
                        .put("path", property("string")), "path"))
                .put(action("search_text", "Find occurrences of text within project files", new JSONObject()
                        .put("query", property("string")), "query"))
                .put(action("write_file", "Create or overwrite a UTF-8 file. Requires user confirmation", new JSONObject()
                        .put("path", property("string")).put("content", property("string")), "path", "content"))
                .put(action("edit_file", "Replace exact source snippet. Requires user confirmation", new JSONObject()
                        .put("path", property("string")).put("old_text", property("string"))
                        .put("new_text", property("string")).put("replace_all", property("boolean")),
                        "path", "old_text", "new_text"))
                .put(action("delete_file", "Delete one regular file (confirmation unless trusted mode)", new JSONObject()
                        .put("path", property("string")), "path"))
                .put(action("run_command", "Run an Android shell command in app workspace. Always requires one-time user approval. Normal app permissions, not root.", new JSONObject()
                        .put("command", property("string")).put("timeout", property("integer")), "command"))
                .put(action("run_termux", "Execute shell command in Termux home, including installed Python/Git. Requires BRIDGE enabled, Termux permission and separate user approval EVERY time. Output is returned to model.", new JSONObject()
                        .put("command", property("string")).put("timeout", property("integer")), "command"))
                .put(action("run_adb", "Execute an Android adb-shell command via Termux rish and Shizuku; UID=2000, not root. Requires BRIDGE enabled and one-time approval EVERY time. Output is returned to model.", new JSONObject()
                        .put("command", property("string")).put("timeout", property("integer")), "command"));
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    /** Task is synchronous for background executor. */
    /**
     * Keep the current user request plus whole recent assistant/tool-call groups.
     * Never orphan a tool reply by dropping its matching assistant tool_calls.
     */
    private void compactHistory() {
        if (history.length() < 8 && history.toString().length() <= MAX_CONTEXT_CHARS) return;
        int lastUser = -1;
        for (int i = history.length() - 1; i >= 1; i--) {
            if ("user".equals(history.optJSONObject(i).optString("role"))) {
                lastUser = i;
                break;
            }
        }
        if (lastUser == -1) return;
        JSONObject system = history.optJSONObject(0);
        JSONObject user = history.optJSONObject(lastUser);
        // Prior task conversations are not required for executing the current task.
        java.util.ArrayList<JSONArray> groups = new java.util.ArrayList<>();
        for (int i = lastUser + 1; i < history.length();) {
            JSONObject item = history.optJSONObject(i);
            if (item == null || !"assistant".equals(item.optString("role"))) {
                i++;
                continue;
            }
            JSONArray block = new JSONArray().put(item);
            i++;
            while (i < history.length()) {
                JSONObject tool = history.optJSONObject(i);
                if (tool == null || !"tool".equals(tool.optString("role"))) break;
                block.put(tool);
                i++;
            }
            groups.add(block);
        }
        int room = MAX_CONTEXT_CHARS - system.toString().length() - user.toString().length();
        // Always retain at least the most recent complete group.
        java.util.ArrayList<JSONArray> selected = new java.util.ArrayList<>();
        for (int i = groups.size() - 1; i >= 0; i--) {
            JSONArray group = groups.get(i);
            int size = group.toString().length();
            if (!selected.isEmpty() && size > room) break;
            selected.add(0, group);
            room -= size;
            if (selected.size() >= 20) break;
        }
        // Skip reconstructing the array when nothing would be removed.
        int retained = 2;
        for (JSONArray group : selected) retained += group.length();
        if (retained == history.length() && lastUser == 1) return;
        JSONArray replacement = new JSONArray().put(system).put(user);
        for (JSONArray group : selected) {
            for (int i = 0; i < group.length(); i++) replacement.put(group.optJSONObject(i));
        }
        while (history.length() > 0) history.remove(history.length() - 1);
        for (int i = 0; i < replacement.length(); i++) history.put(replacement.optJSONObject(i));
    }

    public void task(String instruction) {
        if (instruction == null || instruction.trim().isEmpty()) return;
        history.put(message("user", instruction));
        int toolActions = 0;
        long billedTokensOrEstimate = 0;
        int budget = secrets.taskTokenBudget();
        tokenMeter.begin(secrets.provider(), budget);
        events.usage(tokenMeter.snapshot());
        boolean failed = false;
        String missionStatus = "пауза: лимит API-шагов";
        try {
        for (int n = 0; n < secrets.steps(); n++) {
            if (events.cancelled()) {
                missionStatus = "остановлена";
                events.log("Остановка", "Задача отменена. Изменённые файлы и журнал сохраняются.");
                return;
            }
            if (billedTokensOrEstimate >= budget) {
                missionStatus = "пауза: лимит токенов";
                events.log("Лимит токенов", "Остановлено для защиты баланса: примерно " +
                        billedTokensOrEstimate + " / " + budget +
                        " токенов за задачу. Проверь Git и PROGRESS.md; продолжай отдельным запросом.");
                return;
            }
            try {
                compactHistory();
                JSONObject response = request();
                JSONObject usage = response.optJSONObject("usage");
                long reported = usage == null ? 0 : usage.optLong("total_tokens", 0);
                boolean hasExactUsage = reported > 0;
                long input = usage == null ? -1 : usage.optLong("prompt_tokens",
                        usage.optLong("input_tokens", -1));
                long output = usage == null ? -1 : usage.optLong("completion_tokens",
                        usage.optLong("output_tokens", -1));
                if (input < 0) input = lastInputEstimate;
                if (output < 0) output = lastOutputEstimate;
                long counted = hasExactUsage ? reported : lastRequestTokenEstimate;
                TokenMeter.Snapshot updated = tokenMeter.record(counted, input, output, !hasExactUsage);
                billedTokensOrEstimate = updated.used;
                missionStore.noteUsage(updated);
                events.usage(updated);
                events.missionChanged();
                JSONArray choices = response.optJSONArray("choices");
                if (choices == null || choices.length() == 0) throw new IOException("API вернул пустой choices");
                JSONObject answerMessage = choices.getJSONObject(0).getJSONObject("message");
                JSONObject contextMessage = new JSONObject(answerMessage.toString());
                String answer = answerMessage.optString("content", "");
                if (!answer.trim().isEmpty() && !answer.equals("null")) {
                    events.log("КЕНТ", answer);
                    if (answer.length() > MAX_TOOL_RESULT_CHARS)
                        contextMessage.put("content", answer.substring(0, MAX_TOOL_RESULT_CHARS) + "\n[ответ сокращён для API-контекста]");
                }
                history.put(contextMessage);
                JSONArray calls = answerMessage.optJSONArray("tool_calls");
                if (calls == null || calls.length() == 0) {
                    missionStatus = "ответ получен";
                    if (answer.trim().isEmpty()) events.log("КЕНТ", "Пустой ответ модели. Попробуй другую модель.");
                    events.log("Расход", "Токены задачи: примерно " + billedTokensOrEstimate +
                            " / лимит " + budget +
                            ". Точное списание смотри в AI-FLEX.");
                    return;
                }
                for (int i = 0; i < calls.length(); i++) {
                    if (events.cancelled()) { missionStatus = "остановлена"; return; }
                    JSONObject call = calls.getJSONObject(i);
                    String id = call.optString("id", "call_" + n + "_" + i);
                    JSONObject function = call.getJSONObject("function");
                    String name = function.getString("name");
                    JSONObject args = new JSONObject(function.optString("arguments", "{}"));
                    String result = execute(name, args);
                    toolActions++;
                    if (!"mission_checkpoint".equals(name)) missionStore.noteTool(name);
                    events.missionChanged();
                    events.log("Инструмент · " + name,
                            result.length() > 2300 ? result.substring(0, 2300) + "\n…" : result);
                    String contextResult = result.length() > MAX_TOOL_RESULT_CHARS ?
                            result.substring(0, MAX_TOOL_RESULT_CHARS) + "\n[вывод обрезан для экономии токенов]" : result;
                    history.put(new JSONObject().put("role", "tool").put("tool_call_id", id)
                            .put("content", contextResult));
                }
            } catch (Exception e) {
                failed = true;
                missionStatus = "ошибка";
                String safe = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                events.log("Ошибка API", safe.substring(0, Math.min(1100, safe.length())));
                events.log("Расход", "Примерно " + billedTokensOrEstimate +
                        " токенов в этой задаче. Рабочие файлы не очищались.");
                return;
            }
        }
        events.log("Лимит шагов", "Достигнут предел " + secrets.steps() +
                " обращений. Токены задачи: примерно " + billedTokensOrEstimate +
                " / " + budget +
                ". Для продолжения проверь Git и PROGRESS.md, запусти новую короткую задачу.");
        } finally {
            if (events.cancelled()) missionStatus = "остановлена";
            missionStore.finish(missionStatus);
            tokenMeter.finish(events.cancelled() ? "остановлена" :
                    failed ? "ошибка" : "завершена");
            events.usage(tokenMeter.snapshot());
            events.missionChanged();
        }
    }

    private JSONObject request() throws Exception {
        String provider = secrets.provider();
        String apiKey = secrets.loadActiveKey();
        if (apiKey.trim().isEmpty())
            throw new IOException("Сначала сохрани API-ключ " +
                    (provider.equals("openrouter") ? "OpenRouter" : "AI-FLEX") + " в настройках CTRL");
        JSONObject payload = new JSONObject().put("model", secrets.model()).put("messages", history)
                .put("tools", tools()).put("tool_choice", "auto")
                .put("max_tokens", secrets.responseTokens());
        if (provider.equals("openrouter")) {
            // Avoid routing to endpoints that silently drop required tool calls.
            payload.put("provider", new JSONObject().put("require_parameters", true));
        }
        String endpoint = provider.equals("openrouter") ? OPENROUTER_API : AI_FLEX_API;
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(95_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("User-Agent", "KENT-CODE-Android/0.4");
        if (provider.equals("openrouter")) {
            connection.setRequestProperty("HTTP-Referer",
                    "https://github.com/slavalukanov59-debug/kent-code-android");
            connection.setRequestProperty("X-Title", "KENT CODE Android");
        }
        try {
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            lastInputEstimate = Math.max(1, bytes.length / 3);
            lastOutputEstimate = 0;
            lastRequestTokenEstimate = lastInputEstimate;
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int code = connection.getResponseCode();
            InputStream stream = code < 400 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (stream != null) {
                byte[] chunk = new byte[8192];
                int size;
                while ((size = stream.read(chunk)) != -1) {
                    if (buffer.size() + size > 1_000_000) throw new IOException("Слишком большой ответ API");
                    buffer.write(chunk, 0, size);
                }
            }
            String body = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            lastOutputEstimate = Math.max(1, buffer.size() / 3);
            lastRequestTokenEstimate += lastOutputEstimate;
            if (stream != null) stream.close();
            if (code != 200) {
                String detail = body;
                try {
                    JSONObject err = new JSONObject(body).optJSONObject("error");
                    if (err != null) detail = err.optString("message", body);
                } catch (Exception ignored) {}
                throw new IOException("HTTP " + code + ": " + detail.substring(0, Math.min(detail.length(), 600)));
            }
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    private String execute(String name, JSONObject args) {
        if (events.cancelled()) return "Отклонено: задача отменена";
        try {
            String path = args.optString("path", "");
            switch (name) {
                case "mission_checkpoint": {
                    String result = missionStore.checkpoint(args);
                    events.missionChanged();
                    return result;
                }
                case "list_files": return files.tree();
                case "read_file": return files.read(path);
                case "search_text": return files.search(args.getString("query"));
                case "write_file": {
                    String content = args.getString("content");
                    String before;
                    try { before = files.read(path); } catch (IOException e) { before = "<новый файл>"; }
                    String detail = "Файл: " + path + "\n\nДО:\n" + preview(before)
                            + "\n\nПОСЛЕ:\n" + preview(content);
                    if (!secrets.trustedMode() && !events.approve("Разрешить запись файла?", detail)) return "Отклонено пользователем";
                    files.write(path, content);
                    return "Записан файл: " + path;
                }
                case "edit_file": {
                    String oldPart = args.getString("old_text");
                    String newPart = args.getString("new_text");
                    boolean all = args.optBoolean("replace_all", false);
                    String before = files.read(path);
                    String after = files.editPreview(path, oldPart, newPart, all);
                    if (!secrets.trustedMode() && !events.approve("Разрешить изменение файла?", "Файл: " + path +
                            "\n\nСТАРЫЙ ФРАГМЕНТ:\n" + preview(oldPart) +
                            "\n\nНОВЫЙ ФРАГМЕНТ:\n" + preview(newPart))) return "Отклонено пользователем";
                    // Guard against external modification while dialog was open.
                    if (!files.read(path).equals(before)) return "Файл изменился во время подтверждения; повтори операцию";
                    files.write(path, after);
                    return "Изменён файл: " + path;
                }
                case "run_command": {
                    String command = args.getString("command");
                    int seconds = Math.max(1, Math.min(120, args.optInt("timeout", 45)));
                    String detail = "Выполнить shell-команду от имени приложения Android?\n"
                            + "Папка: " + files.root().getAbsolutePath() + "\n"
                            + "ВНИМАНИЕ: команда может обращаться к данным приложения, включая сохранённый API-ключ."
                            + " Не одобряй непонятные команды.\n\n$ " + command;
                    if (!events.approve("▶ Запуск команды — подтвердить", detail)) return "Запуск команды отклонён пользователем";
                    return runner.run(command, seconds, events::cancelled);
                }
                case "run_termux":
                case "run_adb": {
                    if (!secrets.bridgeEnabled()) return "BRIDGE выключен. Пользователь должен включить его на вкладке BRIDGE.";
                    String command = args.getString("command");
                    boolean adb = name.equals("run_adb");
                    int seconds = Math.max(1, Math.min(120, args.optInt("timeout", 45)));
                    if (command.isBlank() || command.length() > 5000 || command.indexOf('\0') >= 0)
                        return "Отклонено: недопустимая команда";
                    String detail = (adb ? "ADB-SHELL / SHIZUKU (uid=2000, НЕ root)" :
                            "TERMUX (доступ к домашней папке Termux, Git, Python и файлам)") +
                            "\nВ режиме SAFE команда требует отдельного подтверждения; в AUTOPILOT запускается без окна подтверждения." +
                            "\nВНИМАНИЕ: команда может менять приложения и файлы. Вывод будет отправлен выбранному ИИ-провайдеру." +
                            "\nНе используй команды с неизвестными последствиями или с доступом к секретам.\n\n$ " + command;
                    if (!events.approve("▶ KENT BRIDGE — разрешить команду?", detail)) return "Команда отклонена пользователем";
                    // Google Play Termux omits RUN_COMMAND permission. Use opt-in
                    // token-authenticated loopback service when configured.
                    if (localBridge.configured())
                        return localBridge.run(command, adb, seconds, events::cancelled);
                    return adb ? bridge.runAdb(command, seconds, events::cancelled)
                            : bridge.runTermux(command, seconds, events::cancelled);
                }
                case "delete_file": {
                    String before = files.read(path);
                    if (!secrets.trustedMode() && !events.approve("Удалить файл?", "НЕОБРАТИМОЕ ДЕЙСТВИЕ\n" + path +
                            "\n\nСОДЕРЖИМОЕ:\n" + preview(before))) return "Отклонено пользователем";
                    files.delete(path);
                    return "Удалён файл: " + path;
                }
                default: return "Нет такого инструмента: " + name;
            }
        } catch (Exception e) {
            return "Ошибка инструмента: " + e.getMessage();
        }
    }

    private static String preview(String source) {
        return source.length() <= 3600 ? source : source.substring(0, 3600) + "\n…(превью ограничено 3600 символами)";
    }
}
