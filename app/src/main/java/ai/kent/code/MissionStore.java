package ai.kent.code;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Local durable mission ledger. This stores progress independently from chat/model memory.
 * Checkpoints are model-reported, NOT independent proof that tests passed.
 *
 * No network calls and no automatic execution on Android process restart.
 */
public final class MissionStore {
    private static final String TAG = "KENT-MISSIONS";
    private static final int MAX_MISSIONS = 25;
    private static final int MAX_FILE = 1_000_000;
    private final AtomicFile file;
    private final ArrayList<Mission> missions = new ArrayList<>();
    private String selectedId = "";
    // Execution is distinct from which historical record the user is viewing.
    private String activeId = "";

    public static final class Mission {
        public String id, goal, provider, model, status, stage, summary, next, lastTool;
        public int completed, total, calls, tools, attempts, lastSessionCalls;
        public long tokens, lastSessionTokens, created, updated;

        Mission(String goal, String provider, String model) {
            this.id = UUID.randomUUID().toString();
            this.goal = clip(goal, 7000);
            this.provider = provider;
            this.model = model;
            this.status = "выполняется";
            this.stage = "Подготовка / планирование";
            this.summary = "";
            this.next = "Проверить файлы и подготовить план.";
            this.lastTool = "нет";
            this.completed = 0;
            this.total = 0;
            this.calls = 0;
            this.tools = 0;
            this.attempts = 1;
            this.created = System.currentTimeMillis();
            this.updated = this.created;
        }

        private Mission(JSONObject j) {
            id = j.optString("id", UUID.randomUUID().toString());
            goal = j.optString("goal", "");
            provider = j.optString("provider", "ai_flex");
            model = j.optString("model", "");
            status = j.optString("status", "неизвестно");
            stage = j.optString("stage", "Неизвестный этап");
            summary = j.optString("summary", "");
            next = j.optString("next", "");
            lastTool = j.optString("lastTool", "нет");
            completed = j.optInt("completed", 0);
            total = j.optInt("total", 0);
            calls = j.optInt("calls", 0);
            tools = j.optInt("tools", 0);
            attempts = j.optInt("attempts", 0);
            lastSessionCalls = j.optInt("lastSessionCalls", 0);
            tokens = j.optLong("tokens", 0);
            lastSessionTokens = j.optLong("lastSessionTokens", 0);
            created = j.optLong("created", 0);
            updated = j.optLong("updated", 0);
        }

        JSONObject toJson() {
            JSONObject j = new JSONObject();
            try {
                j.put("id", id);
                j.put("goal", goal);
                j.put("provider", provider);
                j.put("model", model);
                j.put("status", status);
                j.put("stage", stage);
                j.put("summary", summary);
                j.put("next", next);
                j.put("lastTool", lastTool);
                j.put("completed", completed);
                j.put("total", total);
                j.put("calls", calls);
                j.put("tools", tools);
                j.put("attempts", attempts);
                j.put("lastSessionCalls", lastSessionCalls);
                j.put("tokens", tokens);
                j.put("lastSessionTokens", lastSessionTokens);
                j.put("created", created);
                j.put("updated", updated);
            } catch (Exception ignored) {}
            return j;
        }

        Mission copy() { return new Mission(toJson()); }
        public String shortId() { return id.length() >= 8 ? id.substring(0, 8) : id; }
    }

    public MissionStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "kent_missions.json"));
        load();
    }

    private static String clip(String value, int length) {
        if (value == null) return "";
        return value.length() <= length ? value : value.substring(0, length);
    }

    private synchronized void load() {
        try (FileInputStream in = file.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int size;
            while ((size = in.read(chunk)) >= 0) {
                if (bytes.size() + size > MAX_FILE) throw new IOException("Mission file too large");
                bytes.write(chunk, 0, size);
            }
            JSONObject root = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            selectedId = root.optString("selected", "");
            JSONArray list = root.optJSONArray("missions");
            if (list != null) for (int i = 0; i < Math.min(list.length(), MAX_MISSIONS); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null) missions.add(new Mission(item));
            }
        } catch (java.io.FileNotFoundException ignored) {
            // Fresh install.
        } catch (Exception e) {
            // Never overwrite a journal that failed to parse.
            Log.e(TAG, "Cannot read local missions; preserving original file", e);
        }
    }

    private synchronized void persist() {
        JSONObject root = new JSONObject();
        JSONArray list = new JSONArray();
        for (Mission mission : missions) list.put(mission.toJson());
        try {
            root.put("selected", selectedId);
            root.put("missions", list);
        } catch (Exception e) { throw new IllegalStateException(e); }
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            throw new IllegalStateException("Не удалось сохранить состояние задачи", e);
        }
    }

    public synchronized List<Mission> list() {
        ArrayList<Mission> result = new ArrayList<>();
        for (Mission mission : missions) result.add(mission.copy());
        return result;
    }

    public synchronized Mission selected() {
        for (Mission m : missions) if (m.id.equals(selectedId)) return m.copy();
        return missions.isEmpty() ? null : missions.get(0).copy();
    }

    private Mission activeMutable() {
        for (Mission m : missions) if (m.id.equals(activeId)) return m;
        return null;
    }

    public synchronized void select(String id) {
        for (Mission m : missions) {
            if (m.id.equals(id)) {
                selectedId = id;
                persist();
                return;
            }
        }
        throw new IllegalArgumentException("Задача не найдена");
    }

    public synchronized Mission begin(String goal, String provider, String model) {
        Mission mission = new Mission(goal, provider, model);
        missions.add(0, mission);
        while (missions.size() > MAX_MISSIONS) missions.remove(missions.size() - 1);
        selectedId = mission.id;
        activeId = mission.id;
        persist();
        return mission.copy();
    }

    public synchronized Mission resume(String id) {
        select(id);
        Mission m = null;
        for (Mission candidate : missions) if (candidate.id.equals(id)) m = candidate;
        if (m == null) throw new IllegalArgumentException("Задача не найдена");
        if ("выполняется".equals(m.status)) throw new IllegalStateException("Задача уже выполняется");
        activeId = id;
        m.attempts++;
        m.lastSessionTokens = 0;
        m.lastSessionCalls = 0;
        m.status = "выполняется";
        m.updated = System.currentTimeMillis();
        persist();
        return m.copy();
    }

    public synchronized void recoverInterrupted() {
        boolean changed = false;
        for (Mission m : missions) if ("выполняется".equals(m.status)) {
            m.status = "прервана Android";
            m.updated = System.currentTimeMillis();
            changed = true;
        }
        activeId = "";
        if (changed) persist();
    }

    /** AI checkpoint: descriptive progress, not verified percentage. */
    public synchronized String checkpoint(JSONObject params) {
        Mission m = activeMutable();
        if (m == null) return "Нет активной задачи";
        String stage = clip(params.optString("stage", ""), 160).trim();
        String summary = clip(params.optString("summary", ""), 2200).trim();
        String next = clip(params.optString("next", ""), 1100).trim();
        int completed = params.optInt("completed", -1);
        int total = params.optInt("total", -1);
        if (stage.isEmpty() || summary.isEmpty() || next.isEmpty()
                || completed < 0 || total < 1 || total > 100 || completed > total)
            return "Неверные данные контрольной точки: заполни stage, summary, next, completed (0..total), total (1..100)";
        m.stage = stage;
        m.summary = summary;
        m.next = next;
        m.completed = completed;
        m.total = total;
        m.updated = System.currentTimeMillis();
        persist();
        return "Контрольная точка сохранена локально: " + completed + "/" + total +
                " — " + stage + ". Это отчёт ИИ; результат ещё нужно проверить тестами.";
    }

    public synchronized void noteTool(String name) {
        Mission m = activeMutable();
        if (m == null) return;
        m.tools++;
        m.lastTool = clip(name, 100);
        m.updated = System.currentTimeMillis();
        persist();
    }

    public synchronized void noteUsage(TokenMeter.Snapshot usage) {
        Mission m = activeMutable();
        if (m == null || usage == null) return;
        if (usage.used >= m.lastSessionTokens) {
            m.tokens += usage.used - m.lastSessionTokens;
        }
        m.lastSessionTokens = usage.used;
        if (usage.calls >= m.lastSessionCalls)
            m.calls += usage.calls - m.lastSessionCalls;
        m.lastSessionCalls = usage.calls;
        m.updated = System.currentTimeMillis();
        persist();
    }

    public synchronized void finish(String status) {
        Mission m = activeMutable();
        if (m == null) return;
        m.status = clip(status, 90);
        m.updated = System.currentTimeMillis();
        activeId = "";
        persist();
    }
}
