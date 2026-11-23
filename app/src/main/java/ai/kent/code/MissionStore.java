package ai.kent.code;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private mission state and an execution journal. Model checkpoints are self-reported. */
public final class MissionStore {
    public static final String WAITING = "ОЖИДАЕТ";
    public static final String RUNNING = "В РАБОТЕ";
    public static final String DONE = "ГОТОВО";
    public static final String REVIEW = "НУЖНА ПРОВЕРКА";
    public static final String ERROR = "ОШИБКА";
    public static final String PAUSED = "ПРИОСТАНОВЛЕНА";
    public static final String CANCELLED = "ОТМЕНЕНА";

    public static final String STARTED = "начато";
    public static final String SUCCEEDED = "выполнено";
    public static final String FAILED = "ошибка";
    public static final String DENIED = "отклонено";
    public static final String UNKNOWN = "результат неизвестен";
    public static final String INTERRUPTED = "прервано";

    public static final String ANALYSIS = "Анализ";
    public static final String PLAN = "План";
    public static final String EXECUTION = "Выполнение";
    public static final String VERIFICATION = "Проверка";
    public static final String FIX = "Исправление";
    public static final String RESULT = "Результат";
    private static final String[] PHASES = {ANALYSIS, PLAN, EXECUTION, VERIFICATION, FIX, RESULT};

    private static final int SCHEMA = 2;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int RECENT_EVENTS = 72;
    private static final int CONTEXT_EVENTS = 8;
    private final Persistence persistence;
    private final ArrayList<Mission> missions = new ArrayList<>();
    private String selectedId = "";
    private String activeId = "";
    private boolean loadFailed;

    interface Persistence {
        byte[] read() throws IOException;
        void write(byte[] data) throws IOException;
    }

    public static final class Event {
        public final String id, kind, name, path, detail, outcome, patch, reason;
        public final long time;
        public final boolean requiresReview, acknowledged, changed;

        private Event(String id, long time, String kind, String name, String path,
                      String detail, String outcome, boolean requiresReview, String patch,
                      String reason, boolean acknowledged, boolean changed) {
            this.id = id;
            this.time = time;
            this.kind = kind;
            this.name = name;
            this.path = path;
            this.detail = detail;
            this.outcome = outcome;
            this.requiresReview = requiresReview;
            this.patch = patch;
            this.reason = reason;
            this.acknowledged = acknowledged;
            this.changed = changed;
        }

        private Event(JSONObject data) {
            this(data.optString("id"), data.optLong("time"), data.optString("kind"),
                    data.optString("name"), data.optString("path"), data.optString("detail"),
                    data.optString("outcome"), data.optBoolean("requiresReview"), data.optString("patch"),
                    data.optString("reason"), data.optBoolean("acknowledged"), data.optBoolean("changed"));
        }

        private JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("time", time).put("kind", kind)
                    .put("name", name).put("path", path).put("detail", detail)
                    .put("outcome", outcome).put("requiresReview", requiresReview)
                    .put("patch", patch).put("reason", reason).put("acknowledged", acknowledged).put("changed", changed);
        }

        private Event completed(String outcome, String detail, String patch, boolean changed) {
            return completed(outcome, detail, patch, reason, changed);
        }

        private Event completed(String outcome, String detail, String patch, String reason, boolean changed) {
            return new Event(id, time, kind, name, path, detail, outcome, requiresReview, patch, reason,
                    acknowledged, changed);
        }

        private Event acknowledge() {
            return new Event(id, time, kind, name, path, detail, outcome, requiresReview, patch, reason, true, changed);
        }
    }

    public static final class Mission {
        public String id, goal, provider, model, status, stage, summary, next, lastTool;
        public String error = "", result = "", structure = "", decisions = "", workspaceId = "", reportedModel = "";
        public boolean developerMode;
        public String projectId = "", phase = "", plan = "", analysis = "", deliverable = "analysis";
        public final Map<String, String> phaseStatus = new LinkedHashMap<>();
        public int completedActions, failedActions, deniedActions;
        public boolean projectCreated, projectFilesChanged, githubCommitPublished, diagnosticCompleted;
        public String pendingDiagnosticId = "";
        private long lastProjectChange;
        public int completed, total, calls, tools, attempts, lastSessionCalls;
        public int tokenCalls, inputCalls, outputCalls, costedCalls;
        public long tokens, inputTokens, outputTokens, lastSessionTokens, created, updated, legacyTokenTotal;
        public BigDecimal cost, lastRequestCost;
        public long elapsedMillis;
        public int compactedEvents;
        public final List<Event> events = new ArrayList<>();
        public final List<String> changedFiles = new ArrayList<>();
        private final Map<String, Integer> errorCounts = new LinkedHashMap<>();
        private final Map<String, Integer> repeatedReads = new LinkedHashMap<>();
        private long startedAt, lastSessionInput, lastSessionOutput;
        private int lastSessionTokenCalls, lastSessionInputCalls, lastSessionOutputCalls, lastSessionCostedCalls;
        private int resultAttempt;
        private BigDecimal lastSessionCost;
        private String lastRead = "";
        private boolean reviewRequired;

        private Mission(String goal, String provider, String model, String workspaceId,
                        boolean developerMode, String projectId) {
            id = UUID.randomUUID().toString();
            this.goal = goal;
            this.provider = provider;
            this.model = model;
            this.workspaceId = workspaceId;
            this.developerMode = developerMode;
            this.projectId = projectId;
            if (developerMode) for (String name : PHASES) phaseStatus.put(name, WAITING);
            status = WAITING;
            stage = "Подготовка";
            summary = "";
            next = "";
            lastTool = "";
            created = System.currentTimeMillis();
            updated = created;
        }

        private Mission(JSONObject data) {
            id = data.optString("id", UUID.randomUUID().toString());
            goal = data.optString("goal");
            provider = data.optString("provider", "ai_flex");
            model = data.optString("model");
            reportedModel = data.optString("reportedModel");
            status = normalizeStatus(data.optString("status"));
            stage = data.optString("stage", "Подготовка");
            summary = data.optString("summary");
            next = data.optString("next");
            lastTool = data.optString("lastTool");
            error = data.optString("error");
            result = data.optString("result");
            structure = data.optString("structure");
            decisions = data.optString("decisions");
            workspaceId = data.optString("workspaceId");
            developerMode = data.optBoolean("developerMode");
            projectId = data.optString("projectId");
            phase = data.optString("phase");
            plan = data.optString("plan");
            analysis = data.optString("analysis");
            deliverable = data.optString("deliverable", "analysis");
            completedActions = data.optInt("completedActions");
            failedActions = data.optInt("failedActions");
            deniedActions = data.optInt("deniedActions");
            projectCreated = data.optBoolean("projectCreated");
            projectFilesChanged = data.optBoolean("projectFilesChanged");
            githubCommitPublished = data.optBoolean("githubCommitPublished");
            diagnosticCompleted = data.optBoolean("diagnosticCompleted");
            pendingDiagnosticId = data.optString("pendingDiagnosticId");
            lastProjectChange = data.optLong("lastProjectChange");
            JSONObject phases = data.optJSONObject("phaseStatus");
            if (developerMode) for (String name : PHASES) {
                phaseStatus.put(name, phases == null ? WAITING : phases.optString(name, WAITING));
            }
            completed = data.optInt("completed");
            total = data.optInt("total");
            calls = data.optInt("calls");
            tools = data.optInt("tools");
            attempts = data.optInt("attempts");
            resultAttempt = data.optInt("resultAttempt");
            tokenCalls = data.optInt("tokenCalls");
            inputCalls = data.optInt("inputCalls");
            outputCalls = data.optInt("outputCalls");
            costedCalls = data.optInt("costedCalls");
            cost = amount(data, "cost");
            lastRequestCost = amount(data, "lastRequestCost");
            lastSessionCalls = data.optInt("lastSessionCalls");
            lastSessionTokenCalls = data.optInt("lastSessionTokenCalls");
            lastSessionInputCalls = data.optInt("lastSessionInputCalls");
            lastSessionOutputCalls = data.optInt("lastSessionOutputCalls");
            lastSessionCostedCalls = data.optInt("lastSessionCostedCalls");
            lastSessionCost = amount(data, "lastSessionCost");
            tokens = data.optLong("tokens");
            legacyTokenTotal = data.optLong("legacyTokenTotal");
            inputTokens = data.optLong("inputTokens");
            outputTokens = data.optLong("outputTokens");
            lastSessionTokens = data.optLong("lastSessionTokens");
            lastSessionInput = data.optLong("lastSessionInput");
            lastSessionOutput = data.optLong("lastSessionOutput");
            created = data.optLong("created");
            updated = data.optLong("updated");
            startedAt = data.optLong("startedAt");
            elapsedMillis = data.optLong("elapsedMillis");
            compactedEvents = data.optInt("compactedEvents");
            reviewRequired = data.optBoolean("reviewRequired", REVIEW.equals(status));
            lastRead = data.optString("lastRead");
            loadCounts(data.optJSONObject("errorCounts"), errorCounts);
            loadCounts(data.optJSONObject("repeatedReads"), repeatedReads);
            JSONArray paths = data.optJSONArray("changedFiles");
            if (paths != null) for (int i = 0; i < paths.length(); i++) changedFiles.add(paths.optString(i));
            JSONArray rows = data.optJSONArray("events");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) events.add(new Event(row));
            }
        }

        private JSONObject toJson() throws JSONException {
            JSONArray rows = new JSONArray();
            for (Event event : events) rows.put(event.toJson());
            return new JSONObject().put("id", id).put("goal", goal).put("provider", provider)
                    .put("model", model).put("reportedModel", reportedModel).put("status", status).put("stage", stage)
                    .put("summary", summary).put("next", next).put("lastTool", lastTool)
                    .put("error", error).put("result", result).put("structure", structure)
                    .put("decisions", decisions).put("workspaceId", workspaceId)
                    .put("developerMode", developerMode).put("projectId", projectId)
                    .put("phase", phase).put("phaseStatus", new JSONObject(phaseStatus))
                    .put("plan", plan).put("analysis", analysis).put("deliverable", deliverable)
                    .put("completedActions", completedActions)
                    .put("failedActions", failedActions).put("deniedActions", deniedActions)
                    .put("projectCreated", projectCreated).put("projectFilesChanged", projectFilesChanged)
                    .put("githubCommitPublished", githubCommitPublished).put("diagnosticCompleted", diagnosticCompleted)
                    .put("pendingDiagnosticId", pendingDiagnosticId).put("lastProjectChange", lastProjectChange)
                    .put("completed", completed).put("total", total)
                    .put("calls", calls).put("tools", tools).put("attempts", attempts).put("resultAttempt", resultAttempt)
                    .put("tokenCalls", tokenCalls).put("inputCalls", inputCalls).put("outputCalls", outputCalls)
                    .put("costedCalls", costedCalls).put("cost", amountText(cost))
                    .put("lastRequestCost", amountText(lastRequestCost)).put("lastSessionCalls", lastSessionCalls)
                    .put("lastSessionTokenCalls", lastSessionTokenCalls).put("lastSessionInputCalls", lastSessionInputCalls)
                    .put("lastSessionOutputCalls", lastSessionOutputCalls).put("lastSessionCostedCalls", lastSessionCostedCalls)
                    .put("lastSessionCost", amountText(lastSessionCost)).put("tokens", tokens)
                    .put("legacyTokenTotal", legacyTokenTotal)
                    .put("inputTokens", inputTokens).put("outputTokens", outputTokens)
                    .put("lastSessionTokens", lastSessionTokens).put("lastSessionInput", lastSessionInput)
                    .put("lastSessionOutput", lastSessionOutput).put("created", created).put("updated", updated)
                    .put("startedAt", startedAt).put("elapsedMillis", elapsedMillis)
                    .put("reviewRequired", reviewRequired).put("compactedEvents", compactedEvents)
                    .put("lastRead", lastRead).put("errorCounts", new JSONObject(errorCounts))
                    .put("repeatedReads", new JSONObject(repeatedReads))
                    .put("changedFiles", new JSONArray(changedFiles)).put("events", rows);
        }

        private Mission copy() {
            try {
                return new Mission(toJson());
            } catch (JSONException e) {
                throw new IllegalStateException("Не удалось скопировать состояние задачи", e);
            }
        }

        private Mission displayCopy() {
            Mission copy = copy();
            if (startedAt > 0) copy.elapsedMillis += Math.max(0, System.currentTimeMillis() - startedAt);
            return copy;
        }

        public String shortId() { return id.length() >= 8 ? id.substring(0, 8) : id; }
        public boolean canResume() {
            return WAITING.equals(status) || PAUSED.equals(status) || REVIEW.equals(status) || ERROR.equals(status);
        }
        public boolean needsReview() { return reviewRequired; }

        public boolean canMarkVerified() {
            return REVIEW.equals(status) && !result.trim().isEmpty() && resultAttempt == attempts
                    && (!developerMode || SUCCEEDED.equals(phaseStatus.get(RESULT)) && pendingDiagnosticId.isEmpty())
                    && events.stream().noneMatch(event -> event.requiresReview && !event.acknowledged
                            && UNKNOWN.equals(event.outcome));
        }

        /** Selected mission memory is supplied as evidence, including action outcomes and uncertainty. */
        public String resumePrompt() {
            StringBuilder prompt = new StringBuilder("Продолжение задачи ").append(shortId()).append(".\n\n");
            section(prompt, "Исходная задача", goal, Integer.MAX_VALUE);
            return prompt.append(contextSummary()).toString();
        }

        /** Bounded evidence for in-session context compaction, where the user request is already present. */
        public String contextSummary() {
            StringBuilder prompt = new StringBuilder("Сначала сверь состояние файлов и предыдущих операций. ")
                    .append("Запрашивай отдельное разрешение на повтор действий с возможными побочными эффектами.\n\n");
            int uncertain = 0;
            for (int index = events.size() - 1; index >= 0 && uncertain < CONTEXT_EVENTS; index--) {
                Event event = events.get(index);
                if (event.requiresReview && UNKNOWN.equals(event.outcome)) {
                    prompt.append("Действие с неопределённым результатом ").append(event.name).append(' ')
                            .append(clip(event.path, 180)).append('\n');
                    uncertain++;
                }
            }
            section(prompt, "Этап", stage, 160);
            section(prompt, "Открытый проект", projectId, 500);
            section(prompt, "Проверка без подтверждённого завершения", pendingDiagnosticId, 100);
            section(prompt, "Текущий этап выполнения", phase, 160);
            section(prompt, "Технический план", plan, 4_200);
            if (developerMode && !plan.isEmpty()) section(prompt, "Требуемый результат", deliverable, 80);
            section(prompt, "Результаты анализа", analysis, 1_200);
            section(prompt, "Последняя контрольная точка модели", summary, 2_200);
            section(prompt, "Следующий этап", next, 1_100);
            section(prompt, "Важные решения", decisions, 2_200);
            section(prompt, "Последний результат", result, 600);
            section(prompt, "Последняя ошибка", error, 400);
            section(prompt, "Последний снимок структуры", structure, 600);
            section(prompt, "Изменённые файлы", String.join("\n", changedFiles), 800);
            prompt.append("\nЖурнал последних действий\n");
            for (int index = Math.max(0, events.size() - CONTEXT_EVENTS); index < events.size(); index++) {
                Event event = events.get(index);
                prompt.append(event.name).append(' ').append(event.outcome).append(' ')
                        .append(clip(event.path, 200)).append(' ').append(clip(event.detail, 250)).append('\n');
            }
            int failures = 0;
            HashSet<String> seenFailures = new HashSet<>();
            for (int index = events.size() - CONTEXT_EVENTS - 1; index >= 0 && failures < 3; index--) {
                Event event = events.get(index);
                if (!FAILED.equals(event.outcome) || !seenFailures.add(event.name + "\n" + event.path)) continue;
                prompt.append("Ранее зафиксированная ошибка ").append(event.name).append(' ')
                        .append(clip(event.path, 200)).append(' ').append(clip(event.detail, 300)).append('\n');
                failures++;
            }
            return prompt.toString();
        }

        public String report() {
            StringBuilder report = new StringBuilder("Задача KENT ").append(id).append('\n');
            section(report, "Статус", status, Integer.MAX_VALUE);
            section(report, "Провайдер и запрошенная модель", provider + " / " + model, Integer.MAX_VALUE);
            section(report, "Модель по ответу провайдера", reportedModel, Integer.MAX_VALUE);
            section(report, "Рабочая копия", workspaceId, Integer.MAX_VALUE);
            section(report, "Открытый проект", projectId, Integer.MAX_VALUE);
            section(report, "Исходная задача", goal, Integer.MAX_VALUE);
            section(report, "Этап", stage + (total > 0 ? " (" + completed + "/" + total + ")" : ""), Integer.MAX_VALUE);
            section(report, "Анализ", analysis, Integer.MAX_VALUE);
            section(report, "Технический план", plan, Integer.MAX_VALUE);
            if (developerMode) {
                report.append("Этапы выполнения\n");
                phaseStatus.forEach((name, outcome) -> report.append(name).append(' ').append(outcome).append('\n'));
                report.append('\n');
            }
            section(report, "Контрольная точка модели", summary, Integer.MAX_VALUE);
            section(report, "Следующий этап", next, Integer.MAX_VALUE);
            section(report, "Важные решения", decisions, Integer.MAX_VALUE);
            section(report, "Структура проекта", structure, Integer.MAX_VALUE);
            section(report, "Результат", result, Integer.MAX_VALUE);
            section(report, "Последняя ошибка", error, Integer.MAX_VALUE);
            section(report, "Изменённые файлы", String.join("\n", changedFiles), Integer.MAX_VALUE);
            report.append("\nВремя выполнения (мс) ").append(elapsedMillis)
                    .append("\nОтветы API ").append(calls).append("\nВызовы инструментов ").append(tools);
            reportedUsage(report, "Токены всего", tokens, tokenCalls, calls);
            reportedUsage(report, "Входные токены", inputTokens, inputCalls, calls);
            reportedUsage(report, "Выходные токены", outputTokens, outputCalls, calls);
            if (cost != null) reportedUsage(report, "Стоимость USD", cost.toPlainString(), costedCalls, calls);
            if (lastRequestCost != null) report.append("\nСтоимость последнего запроса USD ").append(lastRequestCost.toPlainString());
            report.append("\nСжато событий ").append(compactedEvents).append("\n\nЖурнал\n");
            for (Event event : events) {
                report.append(event.time).append(' ').append(event.name).append(' ').append(event.path)
                        .append(' ').append(event.outcome).append(' ').append(event.detail).append('\n');
                if (!event.reason.isEmpty()) report.append(event.reason).append('\n');
                if (!event.patch.isEmpty()) report.append(event.patch).append('\n');
            }
            return report.toString();
        }
    }

    public MissionStore(Context context) {
        this(new AndroidPersistence(new File(context.getFilesDir(), "kent_missions.json")));
    }

    MissionStore(Persistence persistence) {
        this.persistence = persistence;
        load();
    }

    private static final class AndroidPersistence implements Persistence {
        private final AtomicFile file;
        private final File pending;

        AndroidPersistence(File file) {
            this.file = new AtomicFile(file);
            this.pending = new File(file.getParentFile(), file.getName() + ".pending");
        }

        @Override public byte[] read() throws IOException {
            try (FileInputStream in = file.openRead();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int size;
                while ((size = in.read(chunk)) != -1) {
                    if (bytes.size() + size > MAX_FILE_BYTES) throw new IOException("Файл задач слишком велик");
                    bytes.write(chunk, 0, size);
                }
                return bytes.toByteArray();
            } catch (FileNotFoundException e) {
                if (file.getBaseFile().exists()) throw e;
                return null;
            }
        }

        @Override public void write(byte[] data) throws IOException {
            // AtomicFile can log and swallow sync/rename errors. Require successful
            // publication before the caller begins an operation with side effects.
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(data);
                out.getFD().sync();
            }
            Files.move(pending.toPath(), file.getBaseFile().toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static String clip(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end) + "…";
    }

    private static void section(StringBuilder text, String label, String value, int limit) {
        if (value.isEmpty()) return;
        text.append(label).append('\n').append(clip(value, limit)).append("\n\n");
    }

    private static void reportedUsage(StringBuilder report, String label, Object value, int coverage, int calls) {
        if (coverage == 0) return;
        report.append('\n').append(label).append(' ').append(value)
                .append(" (данные для ").append(coverage).append(" из ").append(calls).append(" ответов)");
    }

    private static BigDecimal amount(JSONObject data, String field) {
        String value = data.optString(field);
        if (value.isEmpty()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String amountText(BigDecimal amount) { return amount == null ? "" : amount.toPlainString(); }

    private static String normalizeStatus(String value) {
        switch (value) {
            case WAITING: case RUNNING: case DONE: case REVIEW: case ERROR: case PAUSED: case CANCELLED:
                return value;
            case "выполняется": return RUNNING;
            case "ответ получен": case "завершена": return REVIEW;
            case "ошибка": return ERROR;
            case "остановлена": return CANCELLED;
            case "пауза: технический предел": return PAUSED;
            case "прервана Android": default: return REVIEW;
        }
    }

    private static void loadCounts(JSONObject data, Map<String, Integer> values) {
        if (data == null) return;
        java.util.Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            values.put(key, data.optInt(key));
        }
    }

    private void load() {
        try {
            byte[] bytes = persistence.read();
            if (bytes == null) return;
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            int schema = root.optInt("schema", 1);
            if (schema > SCHEMA) throw new IOException("Требуется более новая версия KENT");
            JSONArray data = root.getJSONArray("missions");
            ArrayList<Mission> loaded = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                Mission mission = new Mission(data.getJSONObject(i));
                if (schema == 1) {
                    if (RUNNING.equals(mission.status)) mission.reviewRequired = true;
                    mission.legacyTokenTotal = mission.tokens;
                    mission.tokens = 0;
                }
                loaded.add(mission);
            }
            missions.addAll(loaded);
            selectedId = root.optString("selected");
        } catch (IOException | JSONException e) {
            // A damaged or unsupported file stays available for export and recovery.
            loadFailed = true;
        }
    }

    private byte[] encode() throws JSONException {
        JSONArray data = new JSONArray();
        for (Mission mission : missions) data.put(mission.toJson());
        return new JSONObject().put("schema", SCHEMA).put("selected", selectedId)
                .put("missions", data).toString().getBytes(StandardCharsets.UTF_8);
    }

    private void persist() {
        if (loadFailed) throw new IllegalStateException("Файл задач недоступен. Экспортируйте его для восстановления.");
        try {
            byte[] bytes = encode();
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IOException("Достигнут предел локального хранилища. Экспортируйте и очистите память задач.");
            }
            persistence.write(bytes);
        } catch (JSONException | IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public synchronized boolean loadFailed() { return loadFailed; }

    public synchronized List<Mission> list() {
        ArrayList<Mission> result = new ArrayList<>();
        for (Mission mission : missions) result.add(mission.displayCopy());
        return result;
    }

    public synchronized Mission selected() {
        int index = indexOf(selectedId);
        if (index >= 0) return missions.get(index).displayCopy();
        return missions.isEmpty() ? null : missions.get(0).displayCopy();
    }

    public synchronized Mission find(String id) {
        int index = indexOf(id);
        return index < 0 ? null : missions.get(index).displayCopy();
    }

    public synchronized Mission active() { return find(activeId); }

    public synchronized boolean hasActive() { return !activeId.isEmpty(); }

    private int indexOf(String id) {
        for (int i = 0; i < missions.size(); i++) if (missions.get(i).id.equals(id)) return i;
        return -1;
    }

    private int requireIndex(String id) {
        int index = indexOf(id);
        if (index < 0) throw new IllegalArgumentException("Задача не найдена");
        return index;
    }

    public synchronized void select(String id) {
        requireIndex(id);
        String prior = selectedId;
        selectedId = id;
        try {
            persist();
        } catch (IllegalStateException e) {
            selectedId = prior;
            throw e;
        }
    }

    private Mission add(String goal, String provider, String model, String workspaceId,
                        boolean developerMode, String projectId, boolean start) {
        if (goal == null || goal.trim().isEmpty()) throw new IllegalArgumentException("Введите задачу");
        if (start && !activeId.isEmpty()) throw new IllegalStateException("Уже выполняется другая задача");
        Mission mission = new Mission(goal, provider, model, workspaceId, developerMode, projectId);
        if (start) start(mission, provider, model, false);
        else addEvent(mission, event("state", WAITING, "", "", "", false));
        missions.add(0, mission);
        String prior = selectedId;
        selectedId = mission.id;
        try {
            persist();
        } catch (IllegalStateException e) {
            selectedId = prior;
            missions.remove(0);
            throw e;
        }
        if (start) activeId = mission.id;
        return mission.displayCopy();
    }

    public synchronized Mission create(String goal, String provider, String model) {
        return create(goal, provider, model, "");
    }

    public synchronized Mission create(String goal, String provider, String model, String workspaceId) {
        return create(goal, provider, model, workspaceId, false, "");
    }

    public synchronized Mission create(String goal, String provider, String model, String workspaceId,
                                       boolean developerMode, String projectId) {
        return add(goal, provider, model, workspaceId, developerMode, projectId, false);
    }

    public synchronized Mission begin(String goal, String provider, String model) {
        return begin(goal, provider, model, "", false, "");
    }

    public synchronized Mission begin(String goal, String provider, String model, String workspaceId,
                                      boolean developerMode, String projectId) {
        return add(goal, provider, model, workspaceId, developerMode, projectId, true);
    }

    private static void start(Mission mission, String provider, String model, boolean acknowledged) {
        mission.provider = provider;
        mission.model = model;
        mission.reportedModel = "";
        mission.attempts++;
        mission.lastSessionTokens = 0;
        mission.lastSessionInput = 0;
        mission.lastSessionOutput = 0;
        mission.lastSessionCalls = 0;
        mission.lastSessionTokenCalls = 0;
        mission.lastSessionInputCalls = 0;
        mission.lastSessionOutputCalls = 0;
        mission.lastSessionCostedCalls = 0;
        mission.lastSessionCost = null;
        mission.lastRequestCost = null;
        mission.lastRead = "";
        mission.completedActions = 0;
        mission.failedActions = 0;
        mission.deniedActions = 0;
        if (mission.developerMode) recordPhase(mission, ANALYSIS, STARTED);
        mission.status = RUNNING;
        mission.updated = System.currentTimeMillis();
        mission.startedAt = mission.updated;
        if (acknowledged && mission.reviewRequired) {
            for (int i = 0; i < mission.events.size(); i++) {
                Event event = mission.events.get(i);
                if (event.requiresReview && UNKNOWN.equals(event.outcome) && !event.acknowledged) {
                    mission.events.set(i, event.acknowledge());
                }
            }
            addEvent(mission, event("review", "Пользователь разрешил продолжение после проверки", "", "", "", false));
            mission.reviewRequired = false;
            mission.pendingDiagnosticId = "";
        }
        addEvent(mission, event("state", RUNNING, "", model, "", false));
    }

    public synchronized Mission resume(String id, String provider, String model) {
        return resume(id, provider, model, false);
    }

    public synchronized Mission resume(String id, String provider, String model, boolean reviewAcknowledged) {
        if (!activeId.isEmpty()) throw new IllegalStateException("Уже выполняется другая задача");
        int index = requireIndex(id);
        Mission mission = missions.get(index).copy();
        if (!mission.canResume()) throw new IllegalStateException("Задачу нельзя продолжить в текущем состоянии");
        if (mission.reviewRequired && !reviewAcknowledged) {
            throw new IllegalStateException("Перед продолжением проверьте результат предыдущих операций");
        }
        start(mission, provider, model, reviewAcknowledged);
        String prior = selectedId;
        selectedId = id;
        try {
            replace(index, mission);
        } catch (IllegalStateException e) {
            selectedId = prior;
            throw e;
        }
        activeId = id;
        return mission.displayCopy();
    }

    public synchronized void recoverInterrupted() {
        if (!activeId.isEmpty()) return;
        for (int i = 0; i < missions.size(); i++) {
            Mission mission = missions.get(i);
            if (!RUNNING.equals(mission.status)) continue;
            Mission recovered = mission.copy();
            settlePendingActions(recovered);
            stop(recovered, recovered.reviewRequired ? REVIEW : PAUSED, recovered.updated);
            addEvent(recovered, event("state", "Процесс приложения был завершён", "", "", "", false));
            replace(i, recovered);
        }
    }

    private static void settlePendingActions(Mission mission) {
        if (!mission.pendingDiagnosticId.isEmpty()) {
            mission.reviewRequired = true;
            addEvent(mission, event("state", "Ожидался результат Termux", mission.pendingDiagnosticId,
                    "Проверьте последнее состояние в диагностике перед повторным запуском.", UNKNOWN, false));
        }
        for (int i = 0; i < mission.events.size(); i++) {
            Event event = mission.events.get(i);
            if (!STARTED.equals(event.outcome)) continue;
            mission.events.set(i, event.completed(event.requiresReview ? UNKNOWN : INTERRUPTED,
                    "Завершение операции не зафиксировано", "", false));
            if (event.requiresReview) mission.reviewRequired = true;
        }
    }

    private static Event event(String kind, String name, String path, String detail,
                               String outcome, boolean requiresReview) {
        return new Event(UUID.randomUUID().toString(), System.currentTimeMillis(), kind,
                clip(name, 120), path == null ? "" : path, clip(detail, 900), outcome, requiresReview, "", "", false, false);
    }

    private static void addEvent(Mission mission, Event event) {
        mission.events.add(event);
        if (mission.events.size() <= RECENT_EVENTS) return;
        HashSet<String> changedPaths = new HashSet<>();
        HashSet<String> failurePaths = new HashSet<>();
        for (int i = mission.events.size() - 1; i >= 0; i--) {
            Event candidate = mission.events.get(i);
            boolean latestChange = candidate.changed && changedPaths.add(candidate.path);
            boolean latestFailure = FAILED.equals(candidate.outcome) && failurePaths.size() < CONTEXT_EVENTS
                    && failurePaths.add(candidate.name + '\n' + candidate.path);
            if (i >= mission.events.size() - RECENT_EVENTS || latestChange || latestFailure) continue;
            if (STARTED.equals(candidate.outcome)
                    || (candidate.requiresReview && UNKNOWN.equals(candidate.outcome) && !candidate.acknowledged)) continue;
            mission.events.remove(i);
            mission.compactedEvents++;
        }
    }

    private void replace(int index, Mission updated) {
        Mission prior = missions.set(index, updated);
        try {
            persist();
        } catch (IllegalStateException e) {
            missions.set(index, prior);
            throw e;
        }
    }

    private interface Update { void apply(Mission mission); }

    private void updateActive(Update update) {
        int index = indexOf(activeId);
        if (index < 0) return;
        Mission mission = missions.get(index).copy();
        update.apply(mission);
        mission.updated = System.currentTimeMillis();
        replace(index, mission);
    }

    public synchronized void selectProject(String projectId) {
        updateActive(mission -> mission.projectId = projectId);
    }

    public synchronized void recordPhase(String name, String outcome) {
        updateActive(mission -> recordPhase(mission, name, outcome));
    }

    public synchronized void pendingDiagnostic(String id) {
        updateActive(mission -> mission.pendingDiagnosticId = id);
    }

    public synchronized boolean recordDiagnostic(String id, long checkStarted, String outcome) {
        int index = indexOf(activeId);
        if (index < 0) return false;
        boolean current = checkStarted >= missions.get(index).lastProjectChange;
        updateActive(mission -> {
            if (mission.pendingDiagnosticId.equals(id)) mission.pendingDiagnosticId = "";
            if (current) {
                mission.diagnosticCompleted = SUCCEEDED.equals(outcome) || FAILED.equals(outcome);
                recordPhase(mission, VERIFICATION, outcome);
            }
        });
        return current;
    }

    private static void recordPhase(Mission mission, String name, String outcome) {
        if (!mission.developerMode || !mission.phaseStatus.containsKey(name)) return;
        if (name.equals(mission.phase) && outcome.equals(mission.phaseStatus.get(name))) return;
        mission.phase = name;
        mission.phaseStatus.put(name, outcome);
        addEvent(mission, event("phase", name, "", "", outcome, false));
    }

    /** Stores the developer plan before the first write or executable check. */
    public synchronized String plan(JSONObject params) {
        Mission active = active();
        if (active == null || !active.developerMode) return "Для плана требуется активная задача разработчика.";
        String analysis = params.optString("analysis").trim();
        String plan = params.optString("plan").trim();
        if (analysis.isEmpty() || plan.isEmpty()) return "Укажите результат анализа и технический план.";
        String deliverable = params.optString("deliverable", "analysis");
        switch (deliverable) {
            case "analysis": case "project": case "github_commit": case "diagnostics": break;
            default: return "Выберите тип результата: analysis, project, github_commit или diagnostics.";
        }
        updateActive(mission -> {
            mission.analysis = clip(analysis, 4_000);
            mission.plan = clip(plan, 8_000);
            mission.deliverable = deliverable;
            recordPhase(mission, ANALYSIS, SUCCEEDED);
            recordPhase(mission, PLAN, SUCCEEDED);
        });
        return "Технический план сохранён.";
    }

    /** A descriptive checkpoint supplied by the model. */
    public synchronized String checkpoint(JSONObject params) {
        if (activeId.isEmpty()) return "Нет активной задачи";
        String stage = clip(params.optString("stage"), 160).trim();
        String summary = clip(params.optString("summary"), 2_200).trim();
        String next = clip(params.optString("next"), 1_100).trim();
        int completed = params.optInt("completed", -1);
        int total = params.optInt("total", -1);
        if (stage.isEmpty() || summary.isEmpty() || next.isEmpty() || completed < 0 || total < 1 || completed > total) {
            return "Укажите этап, результаты, следующее действие и число завершённых этапов в пределах плана";
        }
        String decisions = clip(params.optString("decisions"), 2_200).trim();
        updateActive(mission -> {
            mission.stage = stage;
            mission.summary = summary;
            mission.next = next;
            mission.completed = completed;
            mission.total = total;
            if (!decisions.isEmpty()) mission.decisions = decisions;
            addEvent(mission, event("checkpoint", stage, "", summary, "", false));
        });
        return "Контрольная точка сохранена. Этап " + stage + " (" + completed + "/" + total + ").";
    }

    /** Flush intent to disk before invoking an operation with externally visible effects. */
    public synchronized String startAction(String name, String path, boolean requiresReview) {
        if (activeId.isEmpty()) throw new IllegalStateException("Нет активной задачи");
        Event event = event("action", name, path, "", STARTED, requiresReview);
        updateActive(mission -> {
            if (mission.reviewRequired) throw new IllegalStateException("Проверьте состояние предыдущей операции");
            mission.tools++;
            mission.lastTool = event.name;
            String signature = event.name + (event.path.isEmpty() ? "" : " · " + event.path);
            if (!requiresReview && signature.equals(mission.lastRead)) increment(mission.repeatedReads, signature);
            mission.lastRead = requiresReview ? "" : signature;
            addEvent(mission, event);
        });
        return event.id;
    }

    public synchronized void finishAction(String actionId, String outcome, String detail, boolean changed) {
        finishAction(actionId, outcome, detail, changed, "");
    }

    public synchronized void finishAction(String actionId, String outcome, String detail, boolean changed, String patch) {
        finishAction(actionId, outcome, detail, changed, patch, "");
    }

    public synchronized void finishAction(String actionId, String outcome, String detail, boolean changed,
                                          String patch, String reason) {
        if (actionId == null || actionId.isEmpty()) return;
        switch (outcome) {
            case SUCCEEDED: case FAILED: case DENIED: case UNKNOWN: case INTERRUPTED:
                break;
            default: throw new IllegalArgumentException("Недопустимый результат операции");
        }
        updateActive(mission -> {
            for (int i = mission.events.size() - 1; i >= 0; i--) {
                Event event = mission.events.get(i);
                if (!event.id.equals(actionId) || !STARTED.equals(event.outcome)) continue;
                String preview = clip(patch, 24_000);
                if (patch != null && patch.length() > 24_000) preview += "\n[Дальнейшие строки не сохранены]";
                mission.events.set(i, event.completed(outcome, clip(detail, 900), preview, clip(reason, 1_200), changed));
                if (SUCCEEDED.equals(outcome) && !event.name.startsWith("mission_")) {
                    mission.completedActions++;
                    if (mission.developerMode) {
                        switch (event.name) {
                            case "project_create": mission.projectCreated = true; break;
                            case "project_write": case "project_edit": case "project_delete": case "project_import":
                                mission.projectFilesChanged |= changed;
                                if (changed) {
                                    mission.diagnosticCompleted = false;
                                    mission.lastProjectChange = System.currentTimeMillis();
                                    mission.phaseStatus.put(VERIFICATION, WAITING);
                                }
                                break;
                            case "github_commit_changes":
                                mission.githubCommitPublished = true;
                                mission.phaseStatus.put(VERIFICATION, WAITING);
                                break;
                            default: break;
                        }
                    }
                }
                if (DENIED.equals(outcome)) mission.deniedActions++;
                if (FAILED.equals(outcome)) {
                    mission.failedActions++;
                    mission.error = clip(detail, 1_800);
                    increment(mission.errorCounts, "Инструмент · " + event.name);
                }
                if (event.requiresReview && UNKNOWN.equals(outcome)) mission.reviewRequired = true;
                if (changed && !event.path.isEmpty() && !mission.changedFiles.contains(event.path)) {
                    mission.changedFiles.add(event.path);
                }
                return;
            }
        });
    }

    /** Compatibility with callers that only have the tool identifier. */
    public synchronized void noteTool(String name) {
        String id = startAction(name, "", false);
        finishAction(id, SUCCEEDED, "", false);
    }

    public synchronized void noteStructure(String structure) {
        updateActive(mission -> mission.structure = clip(structure, 12_000));
    }

    public synchronized void noteResult(String result) {
        updateActive(mission -> {
            mission.result = clip(result, 8_000);
            mission.resultAttempt = mission.attempts;
        });
    }

    public synchronized void noteError(String error) {
        noteError("Ошибка выполнения", error);
    }

    public synchronized void noteError(String category, String error) {
        updateActive(mission -> {
            mission.error = clip(error, 1_800);
            String label = clip(category, 100);
            increment(mission.errorCounts, label);
            addEvent(mission, event("error", label, "", mission.error, FAILED, false));
        });
    }

    public synchronized void noteUsage(TokenMeter.Snapshot usage) {
        if (usage == null) return;
        updateActive(mission -> {
            if (usage.modelReported) mission.reportedModel = usage.model;
            mission.tokens = saturatedAdd(mission.tokens, delta(usage.used, mission.lastSessionTokens));
            mission.inputTokens = saturatedAdd(mission.inputTokens, delta(usage.input, mission.lastSessionInput));
            mission.outputTokens = saturatedAdd(mission.outputTokens, delta(usage.output, mission.lastSessionOutput));
            mission.calls += (int) delta(usage.calls, mission.lastSessionCalls);
            mission.tokenCalls += (int) delta(usage.tokenCalls, mission.lastSessionTokenCalls);
            mission.inputCalls += (int) delta(usage.inputCalls, mission.lastSessionInputCalls);
            mission.outputCalls += (int) delta(usage.outputCalls, mission.lastSessionOutputCalls);
            mission.costedCalls += (int) delta(usage.costedCalls, mission.lastSessionCostedCalls);
            if (usage.sessionCost != null) {
                BigDecimal additional = usage.sessionCost.subtract(mission.lastSessionCost == null
                        ? BigDecimal.ZERO : mission.lastSessionCost);
                mission.cost = (mission.cost == null ? BigDecimal.ZERO : mission.cost).add(additional.max(BigDecimal.ZERO));
            }
            mission.lastRequestCost = usage.lastRequestCost;
            mission.lastSessionTokens = usage.used;
            mission.lastSessionInput = usage.input;
            mission.lastSessionOutput = usage.output;
            mission.lastSessionCalls = usage.calls;
            mission.lastSessionTokenCalls = usage.tokenCalls;
            mission.lastSessionInputCalls = usage.inputCalls;
            mission.lastSessionOutputCalls = usage.outputCalls;
            mission.lastSessionCostedCalls = usage.costedCalls;
            mission.lastSessionCost = usage.sessionCost;
        });
    }

    private static long delta(long current, long previous) { return Math.max(0, current - previous); }

    private static long saturatedAdd(long a, long b) { return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b; }

    private static void stop(Mission mission, String status, long elapsedUntil) {
        if (mission.startedAt > 0) mission.elapsedMillis += Math.max(0, elapsedUntil - mission.startedAt);
        mission.startedAt = 0;
        mission.updated = System.currentTimeMillis();
        mission.status = status;
        if (REVIEW.equals(status)) mission.reviewRequired = true;
        mission.phaseStatus.replaceAll((name, outcome) -> STARTED.equals(outcome) ? INTERRUPTED : outcome);
        addEvent(mission, event("state", status, "", "", "", false));
    }

    public synchronized void finish(String status) {
        int index = indexOf(activeId);
        if (index < 0) return;
        Mission mission = missions.get(index).copy();
        settlePendingActions(mission);
        String target = normalizeStatus(status);
        if (RUNNING.equals(target) || WAITING.equals(target)) throw new IllegalArgumentException("Недопустимый статус завершения");
        if (mission.reviewRequired) target = REVIEW;
        stop(mission, target, System.currentTimeMillis());
        replace(index, mission);
        activeId = "";
    }

    public synchronized void cancel(String id) {
        int index = requireIndex(id);
        Mission mission = missions.get(index).copy();
        if (!mission.canResume() || activeId.equals(id)) throw new IllegalStateException("Эту задачу нельзя отменить");
        stop(mission, CANCELLED, System.currentTimeMillis());
        replace(index, mission);
    }

    public synchronized void markVerified(String id) {
        int index = requireIndex(id);
        Mission mission = missions.get(index).copy();
        if (!mission.canMarkVerified()) throw new IllegalStateException("Для подтверждения нужен результат без незавершённых операций");
        addEvent(mission, event("review", "Результат проверен пользователем", "", "", "", false));
        mission.reviewRequired = false;
        stop(mission, DONE, System.currentTimeMillis());
        replace(index, mission);
    }

    public synchronized void export(OutputStream output) throws IOException {
        try {
            byte[] data = loadFailed ? persistence.read() : encode();
            if (data == null) throw new IOException("Файл задач отсутствует");
            output.write(data);
            output.flush();
        } catch (JSONException e) {
            throw new IOException("Не удалось экспортировать память задач", e);
        }
    }

    public synchronized void clear() {
        if (!activeId.isEmpty()) throw new IllegalStateException("Сначала завершите активную задачу");
        ArrayList<Mission> prior = new ArrayList<>(missions);
        String selection = selectedId;
        missions.clear();
        selectedId = "";
        try {
            persist();
        } catch (IllegalStateException e) {
            missions.addAll(prior);
            selectedId = selection;
            throw e;
        }
    }

    private static void increment(Map<String, Integer> values, String key) {
        String limited = values.containsKey(key) || values.size() < 40 ? key : "Прочее";
        values.merge(limited, 1, Integer::sum);
    }

    public synchronized String qualityReport() {
        Map<String, Integer> failures = new LinkedHashMap<>();
        Map<String, Integer> reads = new LinkedHashMap<>();
        int errors = 0;
        int reviews = 0;
        for (Mission mission : missions) {
            if (ERROR.equals(mission.status)) errors++;
            if (mission.needsReview()) reviews++;
            mission.errorCounts.forEach((kind, count) -> failures.merge(kind, count, Integer::sum));
            mission.repeatedReads.forEach((path, count) -> reads.merge(path, count, Integer::sum));
        }
        StringBuilder report = new StringBuilder("Задач в локальной истории ").append(missions.size())
                .append("\nЗавершились ошибкой ").append(errors).append("\nТребуют проверки ").append(reviews);
        appendCounts(report, "\n\nПовторяющиеся ошибки", failures, 2);
        appendCounts(report, "\n\nПовторные последовательные вызовы чтения", reads, 1);
        if (failures.values().stream().anyMatch(count -> count > 1)) {
            report.append("\n\nИзучите журнал повторяющейся ошибки и сформулируйте отдельную задачу с условием воспроизведения.");
        }
        if (!reads.isEmpty()) {
            report.append("\n\nСверьте повторные чтения с изменениями проекта. Если состояние совпало, переиспользуйте предыдущий результат.");
        }
        return report.toString();
    }

    private static void appendCounts(StringBuilder output, String label, Map<String, Integer> data, int minimum) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(data.entrySet());
        entries.removeIf(entry -> entry.getValue() < minimum);
        if (entries.isEmpty()) return;
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        output.append(label);
        for (Map.Entry<String, Integer> entry : entries.subList(0, Math.min(10, entries.size()))) {
            output.append('\n').append(entry.getKey()).append(" × ").append(entry.getValue());
        }
    }
}
