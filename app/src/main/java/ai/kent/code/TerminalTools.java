package ai.kent.code;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Approved, scoped developer checks that share the user-enabled Termux bridges. */
final class TerminalTools {
    private final SecureKeyStore secrets;
    private final TermuxBridge official;
    private final LocalTermuxBridge loopback;
    private final HeadlessTermux headless;
    private final DiagnosticsStore diagnostics;
    private final File cache;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kent-developer-terminal");
        thread.setDaemon(true);
        return thread;
    });
    private Job active;

    TerminalTools(SecureKeyStore secrets, TermuxBridge official, LocalTermuxBridge loopback, HeadlessTermux headless,
                  DiagnosticsStore diagnostics, File cache) {
        this.secrets = secrets;
        this.official = official;
        this.loopback = loopback;
        this.headless = headless;
        this.diagnostics = diagnostics;
        this.cache = cache;
    }

    boolean cancel(String id) {
        synchronized (this) {
            if (active == null || !active.record.id.equals(id)) return false;
            active.cancel();
            return true;
        }
    }

    synchronized void cancelMission(String missionId) {
        if (active != null && active.record.missionId.equals(missionId)) active.cancel();
    }

    static boolean handles(String name) {
        switch (name) {
            case "terminal_check":
            case "terminal_run":
            case "terminal_get_output":
            case "terminal_get_exit_code":
            case "terminal_cancel":
                return true;
            default:
                return false;
        }
    }

    static void addTools(JSONArray tools) throws JSONException {
        add(tools, "terminal_check",
                "Check live authenticated Termux bridge status and Android permission without running commands. "
                        + "probe=true requests approval to run real version checks for installed tools.",
                new JSONObject().put("probe", property("boolean")));
        add(tools, "terminal_run",
                "Request approval for one exact Termux diagnostic. program/check: python3 syntax (one .py) or tests (unittest discovery); " +
                "node syntax (one JS file); c++, clang++, g++ syntax or compile (C++ source paths); javac or kotlinc compile (source paths); " +
                "gradle gradle (inputs: test, check, lint, testDebugUnitTest, lintDebug, assembleDebug); any supported program version. " +
                "source=project copies the currently opened project to a temporary directory with the local Termux bridge. " +
                "source=termux uses an existing directory under Termux HOME. work_dir is a relative path, inputs are relative to it. " +
                "snapshot_path optionally selects a subdirectory to copy. By default waits and reports the actual result. " +
                "With background=true use terminal_get_output until finished. Tool execution and output are factual.",
                new JSONObject().put("program", property("string")).put("check", property("string"))
                        .put("inputs", property("array").put("items", property("string"))).put("source", property("string"))
                        .put("work_dir", property("string")).put("snapshot_path", property("string"))
                        .put("timeout_seconds", property("integer")).put("background", property("boolean")),
                "program", "check");
        for (String name : new String[]{"terminal_get_output", "terminal_get_exit_code", "terminal_cancel"}) {
            String use = name.equals("terminal_cancel") ? "Request cancellation of an active job in this mission."
                    : name.equals("terminal_get_output") ? "Read actual status, stdout and stderr of a terminal job in this mission."
                    : "Read the actual exit code and status of a terminal job in this mission. Null means unobserved.";
            add(tools, name, use, new JSONObject().put("job_id", property("string")), "job_id");
        }
    }

    JSONObject execute(String name, JSONObject arguments, SafeWorkspace workspace, KentAgent.Events events,
                       String missionId) throws Exception {
        switch (name) {
            case "terminal_check": return check(arguments.optBoolean("probe", false), events, missionId);
            case "terminal_run": return run(arguments, workspace, events, missionId);
            case "terminal_get_output":
            case "terminal_get_exit_code":
            case "terminal_cancel": return job(name, arguments.getString("job_id"), missionId);
            default: throw new IllegalArgumentException("Неизвестный инструмент Termux");
        }
    }

    private JSONObject check(boolean probe, KentAgent.Events events, String missionId) throws Exception {
        HeadlessTermux.Status connection = headless.connect(probe && headless.autoStartEnabled(), events::cancelled);
        boolean local = connection.localReady();
        boolean ready = local || connection.officialAvailable();
        JSONObject availability = new JSONObject().put("bridge_enabled", connection.enabled)
                .put("termux_installed", connection.installed).put("run_command_permission", connection.permission)
                .put("run_command_service", connection.runCommandAvailable).put("local_configured", connection.tokenConfigured)
                .put("local_status", connection.localStatus.name()).put("local_ready", local)
                .put("transport_configured", connection.enabled && (connection.tokenConfigured || connection.officialAvailable()))
                .put("local_transport_verified", local).put("official_transport_eligible", connection.officialAvailable());
        if (!probe || !ready || events.cancelled()) {
            return availability.put("ok", true).put("status", "CONFIGURATION").put("versions_checked", false)
                    .put("detail", connection.detail + (ready ? " Проверка версий требует подтверждения." : ""));
        }

        StringBuilder commands = new StringBuilder();
        List<TerminalCommand> versions = new ArrayList<>();
        for (String program : TerminalCommand.PROGRAMS) {
            TerminalCommand command = new TerminalCommand(program, "version", new ArrayList<>());
            versions.add(command);
            commands.append(command.display()).append('\n');
        }
        String[] privateValues = secrets.exportSecrets();
        String route = local ? "Локальный мост" : "Служба Termux RUN_COMMAND";
        if (!events.approve("Проверить версии инструментов Termux?", route + "\nКаталог Termux: ~\n\n" + commands))
            return availability.put("ok", false).put("status", "DENIED").put("versions_checked", false);
        Job job = start(missionId, commands.toString(), "~", privateValues, events, current -> {
            current.running("");
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            boolean observed = false;
            for (TerminalCommand command : versions) {
                if (current.cancelled.get()) return stopped();
                CommandResult result = local ? loopback.runAllowed(command, ".", 5, null, current.cancelled::get)
                        : official.runAllowed(command, ".", 5, current.cancelled::get);
                if (result.status == CommandResult.Status.CANCELLED
                        || result.status == CommandResult.Status.TIMED_OUT && result.mayStillBeRunning()
                        || result.status == CommandResult.Status.UNKNOWN && result.mayStillBeRunning())
                    return result;
                observed |= result.status == CommandResult.Status.EXITED;
                out.append(command.program).append(" · ")
                        .append(result.exitCode == null ? result.status.name() : "exitCode=" + result.exitCode).append('\n');
                if (!result.detail.isEmpty()) out.append(result.detail).append('\n');
                if (!result.stdout.isEmpty()) out.append(result.stdout).append('\n');
                if (!result.stderr.isEmpty()) err.append(command.program).append('\n').append(result.stderr).append('\n');
                if (!observed && result.status == CommandResult.Status.REJECTED) break;
            }
            return CommandResult.observed(observed ? CommandResult.Status.CHECKED : CommandResult.Status.REJECTED,
                    null, limit(out.toString()), limit(err.toString()), "", false);
        });
        await(job, events);
        return result(job.record).put("termux", availability)
                .put("versions_checked", job.record.status.equals("CHECKED"));
    }

    private JSONObject run(JSONObject args, SafeWorkspace workspace, KentAgent.Events events,
                           String missionId) throws Exception {
        if (!secrets.bridgeEnabled()) return rejected("Включите мост Termux в настройках.");
        List<String> inputs = new ArrayList<>();
        JSONArray files = args.optJSONArray("inputs");
        if (files != null) for (int i = 0; i < files.length(); i++) inputs.add(files.getString(i));
        TerminalCommand command = new TerminalCommand(args.getString("program"), args.getString("check"), inputs);
        String source = args.optString("source", workspace != null && !command.check.equals("version") ? "project" : "termux");
        if (!source.equals("project") && !source.equals("termux")) return rejected("Укажите рабочую папку проекта или Termux.");
        boolean project = source.equals("project");
        if (project && workspace == null) return rejected("Откройте проект для проверки его файлов.");
        if (!project && command.needsSnapshot) return rejected("Этой проверке нужна временная копия открытого проекта.");
        String directory = checkedDirectory(args.optString("work_dir", "."));
        String selected = checkedDirectory(args.optString("snapshot_path", "."));
        if (!project && !selected.equals(".")) return rejected("snapshot_path относится к открытому проекту.");
        if (project) {
            String child = selected.equals(".") ? directory : selected + (directory.equals(".") ? "" : "/" + directory);
            File current = child.equals(".") ? workspace.root() : workspace.resolve(child);
            if (!current.isDirectory()) return rejected("Выбранная рабочая папка не существует.");
        }
        int timeout = args.optInt("timeout_seconds", 45);
        if (timeout < 1 || timeout > 120) return rejected("Укажите таймаут от 1 до 120 секунд.");
        String folder = project ? "Временный снимок · " + workspace.root().getName()
                + (selected.equals(".") ? "" : "/" + selected) + (directory.equals(".") ? "" : "/" + directory)
                : "~" + (directory.equals(".") ? "" : "/" + directory);
        String[] privateValues = secrets.exportSecrets();
        String display = command.display();
        if (!display.equals(redact(display, privateValues)) || !folder.equals(redact(folder, privateValues)))
            return rejected("Команда или путь содержит данные, похожие на секрет.");
        if (project) {
            for (String input : inputs) if (!input.equals(redact(input, privateValues)))
                return rejected("Путь содержит данные, похожие на секрет.");
        }
        HeadlessTermux.Status connection = headless.connect(headless.autoStartEnabled(), events::cancelled);
        if (events.cancelled()) return rejected(connection.detail);
        boolean local = connection.localReady();
        if (project && !local) return rejected("Для передачи копии проекта нужен локальный мост. " + connection.detail);
        if (!local && !connection.officialAvailable()) return rejected(connection.detail);
        String preview = "Рабочий каталог: " + folder + "\nТаймаут: " + timeout + " с\n\n" + display;
        preview = (local ? "Локальный мост\n" : "Служба Termux RUN_COMMAND\n") + preview;
        if (command.runsProjectCode) preview += "\n\nКоманда выполняет код проекта с правами Termux.";
        if (!local) preview += "\n\nОтмена официального моста прекращает ожидание результата. Команду ограничивает таймаут Termux.";
        if (!events.approve("Выполнить проверку в Termux?", preview))
            return new JSONObject().put("ok", false).put("status", "DENIED").put("detail", "Запуск не подтверждён.");
        Job job = start(missionId, display, folder, privateValues, events, current -> {
            if (!project) {
                current.running("");
                return local ? loopback.runAllowed(command, directory, timeout, null, current.cancelled::get)
                        : official.runAllowed(command, directory, timeout, current.cancelled::get);
            }
            WorkspaceZipExporter exporter = new WorkspaceZipExporter(workspace);
            String path = selected.equals(".") ? "" : selected;
            try (WorkspaceZipExporter.Archive snapshot = current.prepare(() -> {
                if (exporter.directorySize(path) > LocalTermuxBridge.MAX_SNAPSHOT_BYTES)
                    throw new IOException("Выберите меньшую папку для распаковки в Termux.");
                return exporter.stage(path, cache, privateValues);
            })) {
                if (current.cancelled.get()) return stopped();
                String fingerprint = DiagnosticsStore.hash(snapshot.sha256 + "\0" + directory + "\0" + display);
                if (diagnostics.previousFailure(missionId, fingerprint)) {
                    current.repeated = true;
                    return CommandResult.rejected("Та же проверка завершилась ошибкой на этом снимке. "
                            + "Исправьте исходные файлы или измените способ проверки.");
                }
                current.running(fingerprint);
                return loopback.runAllowed(command, directory, timeout, snapshot, current.cancelled::get);
            } catch (IOException failed) {
                if (current.cancelled.get()) return stopped();
                throw failed;
            }
        });
        if (!args.optBoolean("background", false)) await(job, events);
        return result(job.record).put("repeated_failure", job.repeated);
    }

    private JSONObject job(String name, String id, String missionId) throws JSONException {
        DiagnosticsStore.Record record;
        synchronized (this) {
            record = active != null && active.record.id.equals(id) ? active.record : diagnostics.get(id);
        }
        if (record == null || !record.missionId.equals(missionId)) return rejected("Проверка в этой задаче не найдена.");
        JSONObject result = withFailureKey(record, record.json().put("ok", true));
        if (name.equals("terminal_cancel")) {
            result.put("cancellation_requested", cancel(id));
        } else if (name.equals("terminal_get_exit_code")) {
            result.remove("stdout");
            result.remove("stderr");
        }
        return result;
    }

    private Job start(String mission, String command, String directory, String[] privateValues, KentAgent.Events events,
                      Operation operation) throws IOException {
        final Job job;
        synchronized (this) {
            if (active != null) throw new IOException("Дождитесь текущей проверки Termux или отмените её.");
            if (events.cancelled()) throw new IOException("Задача остановлена до запуска проверки.");
            DiagnosticsStore.Record record = DiagnosticsStore.Record.starting(mission, command, directory);
            diagnostics.save(record);
            job = new Job(record, events);
            active = job;
        }
        job.updateUi();
        worker.execute(() -> {
            CommandResult completed;
            try {
                if (job.cancelled.get() || events.cancelled()) completed = stopped();
                else if (!secrets.bridgeEnabled()) completed = CommandResult.rejected("Мост Termux отключён.");
                else completed = operation.run(job);
            } catch (IOException | RuntimeException error) {
                completed = job.cancelled.get() ? stopped() : CommandResult.stopped(
                        job.record.status.equals("RUNNING") ? CommandResult.Status.UNKNOWN : CommandResult.Status.REJECTED,
                        error.getMessage() == null ? "Не удалось выполнить проверку." : error.getMessage(),
                        job.record.status.equals("RUNNING"));
            }
            CommandResult safe = CommandResult.observed(completed.status, completed.exitCode,
                    redact(completed.stdout, privateValues), redact(completed.stderr, privateValues),
                    redact(completed.detail, privateValues), completed.mayStillBeRunning());
            try {
                job.save(job.record.observed(safe, job.record.fingerprint));
            } catch (IOException error) {
                job.record = job.record.observed(CommandResult.observed(safe.status, safe.exitCode, safe.stdout,
                        safe.stderr, safe.detail + "\nНе удалось сохранить запись диагностики.", safe.mayStillBeRunning()),
                        job.record.fingerprint);
            } finally {
                synchronized (TerminalTools.this) { active = null; }
                job.done.countDown();
                job.updateUi();
            }
        });
        return job;
    }

    private static void await(Job job, KentAgent.Events events) throws InterruptedException {
        try {
            while (!job.done.await(250, TimeUnit.MILLISECONDS)) if (events.cancelled()) job.cancel();
        } catch (InterruptedException e) {
            job.cancel();
            throw e;
        }
    }

    private static String redact(String text, String[] values) {
        for (String secret : values) text = SensitiveData.redact(text, secret);
        return text;
    }

    private static String checkedDirectory(String path) throws IOException {
        String directory = CommandResult.workDir(path, false);
        for (String segment : directory.split("/")) {
            String name = segment.toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith(".kent-") || name.equals(".git") || name.equals(".gradle")
                    || SafeWorkspace.isCredentialName(name) || name.contains(":"))
                throw new IOException("Недоступная рабочая папка Termux.");
        }
        return directory;
    }

    private static String limit(String text) {
        if (text.length() <= 8000) return text;
        int end = Character.isHighSurrogate(text.charAt(7999)) ? 7999 : 8000;
        return text.substring(0, end) + "\n[Вывод сокращён]";
    }

    private static JSONObject result(DiagnosticsStore.Record record) throws JSONException {
        boolean ok = (record.status.equals("EXITED") && record.exitCode != null && record.exitCode == 0
                && !record.mayStillBeRunning) || record.status.equals("CHECKED")
                || record.status.equals("PREPARING") || record.status.equals("RUNNING");
        return withFailureKey(record, record.json().put("ok", ok));
    }

    private static JSONObject withFailureKey(DiagnosticsStore.Record record, JSONObject output) throws JSONException {
        String key = record.failureKey();
        return key.isEmpty() ? output : output.put("failure_key", key);
    }

    private static JSONObject rejected(String detail) throws JSONException {
        return new JSONObject().put("ok", false).put("status", "REJECTED").put("detail", detail);
    }

    private static CommandResult stopped() {
        return CommandResult.stopped(CommandResult.Status.CANCELLED, "Запуск отменён до создания процесса.", false);
    }

    private static JSONObject property(String type) throws JSONException {
        return new JSONObject().put("type", type);
    }

    private static void add(JSONArray tools, String name, String description, JSONObject properties, String... required)
            throws JSONException {
        JSONObject parameters = new JSONObject().put("type", "object").put("properties", properties)
                .put("required", new JSONArray(required)).put("additionalProperties", false);
        tools.put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name)
                .put("description", description).put("parameters", parameters)));
    }

    private interface Operation { CommandResult run(Job job) throws IOException; }
    private interface Preparation { WorkspaceZipExporter.Archive stage() throws IOException; }

    private final class Job {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(1);
        final KentAgent.Events events;
        volatile DiagnosticsStore.Record record;
        volatile boolean repeated;
        private Thread preparing;

        Job(DiagnosticsStore.Record record, KentAgent.Events events) {
            this.record = record;
            this.events = events;
        }

        synchronized void cancel() {
            cancelled.set(true);
            if (preparing != null) preparing.interrupt();
        }

        WorkspaceZipExporter.Archive prepare(Preparation preparation) throws IOException {
            synchronized (this) {
                if (cancelled.get()) throw new IOException("Подготовка проверки отменена.");
                preparing = Thread.currentThread();
            }
            try {
                return preparation.stage();
            } finally {
                synchronized (this) {
                    preparing = null;
                    Thread.interrupted();
                }
            }
        }

        void running(String fingerprint) throws IOException {
            save(record.running(fingerprint));
            updateUi();
        }

        void save(DiagnosticsStore.Record revision) throws IOException {
            diagnostics.save(revision);
            record = revision;
        }

        void updateUi() {
            try { events.missionChanged(); }
            catch (RuntimeException ignored) { }
        }
    }
}
