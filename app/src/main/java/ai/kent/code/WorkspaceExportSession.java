package ai.kent.code;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps a prepared archive and its SAF request together across Activity recreation. */
final class WorkspaceExportSession {
    enum Phase { IDLE, PREPARING, READY, PICKER_OPEN, SAVING }

    static final class Result {
        final String message;
        final WorkspaceZipExporter.Archive archive;

        Result(String message, WorkspaceZipExporter.Archive archive) {
            this.message = message;
            this.archive = archive;
        }
    }

    private static final String SAVED_ARCHIVE = "workspace_export_archive";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final WorkspaceZipExporter exporter;
    private final SecureKeyStore secrets;
    private final ContentResolver resolver;
    private final File cache;
    private Runnable listener;
    private Phase phase = Phase.IDLE;
    private WorkspaceZipExporter.Archive archive;
    private Result result;
    private boolean closed;

    WorkspaceExportSession(Context context, WorkspaceZipExporter exporter, Bundle saved) {
        this.exporter = exporter;
        secrets = new SecureKeyStore(context);
        resolver = context.getContentResolver();
        cache = new File(context.getCacheDir(), "workspace-zip");
        Bundle pending = saved == null ? null : saved.getBundle(SAVED_ARCHIVE);
        if (pending != null) {
            phase = Phase.PICKER_OPEN;
            String fileName = pending.getString("file", "");
            String title = pending.getString("title", "");
            long count = pending.getLong("count", -1);
            long bytes = pending.getLong("bytes", -1);
            String sha = pending.getString("sha", "");
            if (fileName.matches("kent-workspace-[0-9]+\\.zip") && !title.isEmpty()
                    && count >= 0 && bytes > 0 && sha.matches("[0-9a-f]{64}")) {
                archive = new WorkspaceZipExporter.Archive(new File(cache, fileName), title,
                        count, count, bytes, sha);
            }
        }
    }

    void saveState(Bundle out) {
        if (phase != Phase.PICKER_OPEN) return;
        Bundle pending = new Bundle();
        if (archive != null) {
            pending.putString("file", archive.file.getName());
            pending.putString("title", archive.suggestedName);
            pending.putLong("count", archive.fileCount);
            pending.putLong("bytes", archive.bytes);
            pending.putString("sha", archive.sha256);
        }
        out.putBundle(SAVED_ARCHIVE, pending);
    }

    void listen(Runnable callback) { listener = callback; }
    Phase phase() { return phase; }
    Result result() { return result; }
    void clearResult() { result = null; }

    void prepare(String directory) {
        if (phase != Phase.IDLE) return;
        clearResult();
        setPhase(Phase.PREPARING);
        worker.execute(() -> {
            try {
                WorkspaceZipExporter.Archive prepared = prepareArchive(directory);
                main.post(() -> {
                    if (closed) {
                        closeArchive(prepared);
                        return;
                    }
                    archive = prepared;
                    setPhase(Phase.READY);
                });
            } catch (IOException | RuntimeException e) {
                main.post(() -> complete(errorMessage(e), null));
            }
        });
    }

    String openPicker() {
        setPhase(Phase.PICKER_OPEN);
        return archive.suggestedName;
    }

    void cancelPicker(String message) {
        closeArchive(archive);
        archive = null;
        if (message == null) setPhase(Phase.IDLE);
        else complete(message, null);
    }

    void saveTo(Uri target) {
        WorkspaceZipExporter.Archive prepared = archive;
        archive = null;
        setPhase(Phase.SAVING);
        worker.execute(() -> {
            try {
                if (prepared == null) {
                    boolean removed = WorkspaceZipDestination.discard(resolver, target);
                    throw new IOException("Подготовленный архив недоступен. Запустите экспорт снова."
                            + (removed ? "" : " Место сохранения может содержать пустой документ."));
                }
                WorkspaceZipDestination.save(resolver, target, prepared);
                String name = displayName(target);
                main.post(() -> complete(name, prepared));
            } catch (IOException | RuntimeException e) {
                main.post(() -> complete(errorMessage(e), null));
            } finally {
                closeArchive(prepared);
            }
        });
    }

    void close() {
        closed = true;
        listener = null;
        closeArchive(archive);
        archive = null;
        if (phase == Phase.SAVING) worker.shutdown();
        else worker.shutdownNow();
    }

    private void complete(String message, WorkspaceZipExporter.Archive verified) {
        if (closed) return;
        result = new Result(message, verified);
        setPhase(Phase.IDLE);
    }

    private WorkspaceZipExporter.Archive prepareArchive(String directory) throws IOException {
        String[] excludedValues;
        try {
            excludedValues = new String[]{secrets.loadProviderKey("ai_flex"),
                    secrets.loadProviderKey("openrouter"), secrets.bridgeToken()};
        } catch (Exception e) {
            throw new IOException("Не удалось проверить ключи приложения перед экспортом.");
        }
        return exporter.stage(directory, cache, excludedValues);
    }

    private void setPhase(Phase value) {
        phase = value;
        if (listener != null) listener.run();
    }

    private String displayName(Uri target) {
        try (Cursor cursor = resolver.query(target, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static void closeArchive(WorkspaceZipExporter.Archive prepared) {
        if (prepared == null) return;
        try { prepared.close(); }
        catch (IOException ignored) { }
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? "Не удалось завершить экспорт." : message;
    }
}
