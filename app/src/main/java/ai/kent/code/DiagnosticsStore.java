package ai.kent.code;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** App-private, atomically written records of actual terminal requests and observations. */
final class DiagnosticsStore {
    private static final int RECENT_RECORDS = 100;
    private final File directory;
    private final List<Record> recent;

    DiagnosticsStore(File appFilesDir) throws IOException {
        directory = new File(appFilesDir, "kent-diagnostics");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Не удалось открыть хранилище диагностики.");
        recent = loadRecent();
        for (Record prior : records()) {
            if (prior.status.equals("PREPARING") || prior.status.equals("RUNNING"))
                save(prior.observed(CommandResult.stopped(CommandResult.Status.UNKNOWN,
                        "Приложение закрылось до получения результата.", prior.status.equals("RUNNING")), prior.fingerprint));
        }
    }

    static final class Record {
        public final String id, command, directory, status, stdout, stderr, detail;
        public final Integer exitCode;
        public final long startedAt, finishedAt;
        public final boolean mayStillBeRunning;
        final String missionId;
        final String fingerprint;

        private Record(String id, String missionId, String fingerprint, String command, String directory,
                       String status, String stdout, String stderr, String detail, Integer exitCode,
                       long startedAt, long finishedAt, boolean mayStillBeRunning) {
            this.id = id;
            this.missionId = missionId;
            this.fingerprint = fingerprint;
            this.command = command;
            this.directory = directory;
            this.status = status;
            this.stdout = stdout;
            this.stderr = stderr;
            this.detail = detail;
            this.exitCode = exitCode;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.mayStillBeRunning = mayStillBeRunning;
        }

        static Record starting(String missionId, String command, String directory) {
            return new Record(UUID.randomUUID().toString(), missionId, "", command, directory,
                    "PREPARING", "", "", "Подготовка проверки.", null, System.currentTimeMillis(), 0, false);
        }

        Record running(String fingerprint) {
            return new Record(id, missionId, fingerprint, command, directory, "RUNNING", "", "",
                    "Ожидание результата Termux.", null, startedAt, 0, false);
        }

        Record observed(CommandResult result, String fingerprint) {
            return new Record(id, missionId, fingerprint, command, directory, result.status.name(), result.stdout,
                    result.stderr, result.detail, result.exitCode, startedAt, System.currentTimeMillis(), result.mayStillBeRunning());
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("job_id", id).put("command", command).put("directory", directory)
                    .put("status", status).put("stdout", stdout).put("stderr", stderr).put("detail", detail)
                    .put("exit_code", exitCode == null ? JSONObject.NULL : exitCode)
                    .put("started_at", startedAt).put("finished_at", finishedAt)
                    .put("may_still_be_running", mayStillBeRunning);
        }

        String failureKey() {
            if (!status.equals("EXITED") || exitCode == null || exitCode == 0 || mayStillBeRunning)
                return "";
            String evidence = (stderr.isEmpty() ? stdout : stderr)
                    .replaceAll("(?<=/)\\.kent-bridge/check-[^/\\s]+/project/", "")
                    .replaceAll(":\\d+(?::\\d+)?(?=[:\\s])", ":#")
                    .replaceAll("(?i)\\bline \\d+", "line #").trim();
            StringBuilder errors = new StringBuilder();
            for (String line : evidence.split("\\R")) {
                if (line.contains("error:") || line.contains("Error:") || line.contains("Exception:"))
                    errors.append(line.trim()).append('\n');
            }
            return hash(command + "\n" + exitCode + "\n" + (errors.length() == 0 ? evidence : errors));
        }

        private static Record read(JSONObject data) throws JSONException {
            return new Record(data.getString("job_id"), data.getString("mission_id"), data.getString("fingerprint"),
                    data.getString("command"), data.getString("directory"), data.getString("status"),
                    data.getString("stdout"), data.getString("stderr"), data.getString("detail"),
                    data.isNull("exit_code") ? null : data.getInt("exit_code"), data.getLong("started_at"),
                    data.getLong("finished_at"), data.getBoolean("may_still_be_running"));
        }
    }

    synchronized void save(Record record) throws IOException {
        File temp = File.createTempFile(".diagnostic-", ".tmp", directory);
        try {
            JSONObject data = record.json().put("mission_id", record.missionId).put("fingerprint", record.fingerprint);
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(data.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            Files.move(temp.toPath(), file(record.id).toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            recent.removeIf(candidate -> candidate.id.equals(record.id));
            recent.add(record);
            recent.sort(Comparator.comparingLong((Record candidate) -> candidate.startedAt).reversed());
            if (recent.size() > RECENT_RECORDS) recent.remove(recent.size() - 1);
        } catch (JSONException invalid) {
            throw new IOException("Не удалось записать проверку.", invalid);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    synchronized Record get(String id) {
        if (!id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return null;
        for (Record record : recent) if (record.id.equals(id)) return record;
        return read(file(id));
    }

    synchronized List<Record> records() {
        return new ArrayList<>(recent);
    }

    private List<Record> loadRecent() {
        File[] files = directory.listFiles((parent, name) -> name.endsWith(".json"));
        List<Record> records = new ArrayList<>();
        if (files == null) return records;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            Record record = read(file);
            if (record != null) records.add(record);
            if (records.size() == RECENT_RECORDS) break;
        }
        records.sort(Comparator.comparingLong((Record record) -> record.startedAt).reversed());
        return records;
    }

    synchronized boolean previousFailure(String missionId, String fingerprint) {
        for (Record record : recent) {
            if (missionId.equals(record.missionId) && fingerprint.equals(record.fingerprint)
                    && "EXITED".equals(record.status) && record.exitCode != null && record.exitCode != 0
                    && record.exitCode != 126 && record.exitCode != 127)
                return true;
        }
        return false;
    }

    static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private File file(String id) {
        return new File(directory, id + ".json");
    }

    private Record read(File file) {
        if (!file.isFile()) return null;
        try {
            return Record.read(new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
        } catch (IOException | JSONException invalid) {
            return null;
        }
    }
}
