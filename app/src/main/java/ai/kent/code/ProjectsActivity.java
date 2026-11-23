package ai.kent.code;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/** Browses the existing workspace and handles project transfers through Android documents. */
public final class ProjectsActivity extends Activity {
    static final String EXTRA_DIRECTORY = "ai.kent.code.project_directory";
    static final String EXTRA_ANALYZE = "ai.kent.code.project_analyze";
    private static final int OPEN_ZIP = 810;
    private static final int SAVE_ZIP = 811;
    private static final int PAGE_CHARS = 16_384;
    private static final ExecutorService IMPORT_CLEANUP = Executors.newSingleThreadExecutor();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, ListPosition> folderPositions = new HashMap<>();
    private final ArrayList<Long> sourceHistory = new ArrayList<>();
    private ProjectService projects;
    private SecureKeyStore secrets;
    private WorkspaceExportSession export;
    private ProjectService.PreparedImport staged;
    private Uri pendingZip;
    private LinearLayout screen;
    private TextView status;
    private ListView files;
    private ScrollView sourceVertical;
    private HorizontalScrollView sourceHorizontal;
    private String directory = "";
    private String filePath;
    private long sourceOffset;
    private int sourceX;
    private int sourceY;
    private String sourceRevision;
    private boolean busy;
    private boolean resumed;

    private static final class ListPosition {
        final int item;
        final int top;

        ListPosition(int item, int top) {
            this.item = item;
            this.top = top;
        }
    }

    private static final class SourcePage {
        final SafeWorkspace.TextPage page;
        final CharSequence styled;

        SourcePage(SafeWorkspace.TextPage page, CharSequence styled) {
            this.page = page;
            this.styled = styled;
        }
    }

    private record ZipPreview(ProjectService.PreparedImport project, String suggestion) { }

    static Intent intent(Context context, String initialDirectory) {
        return new Intent(context, ProjectsActivity.class).putExtra(EXTRA_DIRECTORY, initialDirectory);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(MainActivity.BG);
        getWindow().setNavigationBarColor(MainActivity.BG);
        screen = column();
        screen.setBackgroundColor(MainActivity.BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            screen.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
        }
        setContentView(screen);
        screen.requestApplyInsets();
        try {
            KentLocalData data = KentLocalData.get(this);
            secrets = data.keys;
            projects = data.projects;
            export = (WorkspaceExportSession) getLastNonConfigurationInstance();
            if (export == null) export = new WorkspaceExportSession(getApplicationContext(), projects.exporter(), secrets, state);
            export.listen(this::exportChanged);
        } catch (IOException e) {
            frame("Проекты");
            message("Не удалось открыть рабочую область. " + e.getMessage());
            return;
        }
        String initialDirectory = getIntent().getStringExtra(EXTRA_DIRECTORY);
        if (initialDirectory != null) directory = initialDirectory;
        if (state != null) restoreView(state);
        if (filePath == null) showDirectory(directory);
        else loadSource(sourceOffset, new ArrayList<>(sourceHistory), true);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        exportChanged();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        return export;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        rememberFolder();
        rememberSource();
        state.putString("directory", directory);
        state.putString("file", filePath);
        state.putLong("sourceOffset", sourceOffset);
        state.putInt("sourceX", sourceX);
        state.putInt("sourceY", sourceY);
        state.putString("sourceRevision", sourceRevision);
        if (pendingZip != null) state.putString("pendingZip", pendingZip.toString());
        long[] history = new long[sourceHistory.size()];
        for (int i = 0; i < history.length; i++) history[i] = sourceHistory.get(i);
        state.putLongArray("sourceHistory", history);
        ListPosition position = folderPositions.get(directory);
        if (position != null) {
            state.putInt("firstFile", position.item);
            state.putInt("firstFileTop", position.top);
        }
        if (export != null) export.saveState(state);
        super.onSaveInstanceState(state);
    }

    private void restoreView(Bundle state) {
        directory = state.getString("directory", "");
        filePath = state.getString("file");
        sourceOffset = state.getLong("sourceOffset");
        sourceX = state.getInt("sourceX");
        sourceY = state.getInt("sourceY");
        sourceRevision = state.getString("sourceRevision");
        String zip = state.getString("pendingZip");
        if (zip != null) pendingZip = Uri.parse(zip);
        long[] history = state.getLongArray("sourceHistory");
        if (history != null) for (long offset : history) sourceHistory.add(offset);
        folderPositions.put(directory, new ListPosition(state.getInt("firstFile"), state.getInt("firstFileTop")));
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        discardImport();
        if (export != null) {
            export.listen(null);
            if (!isChangingConfigurations()) export.close();
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (busy) {
            closeScreen();
            return;
        }
        if (filePath != null) showDirectory(directory);
        else if (!directory.isEmpty()) showDirectory(parent(directory));
        else super.onBackPressed();
    }

    private void closeScreen() {
        if (!busy) {
            finish();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Закрыть проекты?")
                .setMessage("Текущая операция получит запрос на остановку.")
                .setNegativeButton("Продолжить", null)
                .setPositiveButton("Закрыть", (dialog, button) -> finish()).show();
    }

    private void showDirectory(String target) {
        rememberFolder();
        directory = target;
        filePath = null;
        frame("Проекты");
        TextView path = label(target.isEmpty() ? "/" : "/" + target, 13, MainActivity.MUTED);
        path.setMaxLines(2);
        path.setEllipsize(TextUtils.TruncateAt.END);
        path.setTextIsSelectable(true);
        path.setPadding(dp(12), 0, dp(12), dp(4));
        screen.addView(path);

        LinearLayout transfer = row();
        add(transfer, target.isEmpty() ? "+ Проект" : "↑ Папка", false,
                target.isEmpty() ? this::createProject : () -> showDirectory(parent(target)));
        add(transfer, "Импорт", false, this::chooseImport);
        add(transfer, "Экспорт ZIP", true, () -> {
            if (export.phase() == WorkspaceExportSession.Phase.IDLE) export.prepare(target);
        });
        screen.addView(transfer);

        LinearLayout actions = row();
        if (target.isEmpty()) {
            add(actions, "Корзина", false, this::showTrash);
        } else {
            add(actions, "В чат", true, () -> handoff(target, false));
            add(actions, "Анализ", false, () -> handoff(target, true));
            add(actions, "В корзину", false, () -> reviewDelete(target));
        }
        screen.addView(actions);
        makeStatus();
        files = list();
        screen.addView(files, weighted());
        work("Чтение папки…", () -> projects.entries(target), entries -> {
            boolean empty = entries.isEmpty();
            message(empty ? "Папка пуста." : "");
            files.setAdapter(new EntryAdapter<>(entries, entry -> (entry.directory ? "▸  " : "") + entry.name,
                    entry -> entry.directory ? "" : Formatter.formatFileSize(this, entry.bytes)));
            files.setOnItemClickListener((parent, view, position, id) -> {
                if (busy) return;
                WorkspaceZipExporter.Entry entry = entries.get(position);
                if (entry.directory) showDirectory(entry.relativePath);
                else openSource(entry.relativePath);
            });
            ListPosition position = folderPositions.get(target);
            if (position != null && !empty) {
                files.setSelectionFromTop(Math.min(position.item, entries.size() - 1), position.top);
            }
        });
    }

    private void rememberFolder() {
        if (files != null && files.getChildCount() > 0) {
            folderPositions.put(directory, new ListPosition(files.getFirstVisiblePosition(), files.getChildAt(0).getTop()));
        }
    }

    private void rememberSource() {
        if (sourceHorizontal != null) sourceX = sourceHorizontal.getScrollX();
        if (sourceVertical != null) sourceY = sourceVertical.getScrollY();
    }

    private void openSource(String path) {
        rememberFolder();
        filePath = path;
        sourceOffset = 0;
        sourceHistory.clear();
        sourceRevision = null;
        loadSource(0, new ArrayList<>(), false);
    }

    private void loadSource(long offset, ArrayList<Long> history, boolean restoreScroll) {
        String path = filePath;
        frame(path);
        LinearLayout controls = row();
        add(controls, "← Папка", false, () -> showDirectory(directory));
        add(controls, "С начала", false, () -> {
            sourceRevision = null;
            loadSource(0, new ArrayList<>(), false);
        });
        screen.addView(controls);
        makeStatus();
        work("Чтение исходника…", () -> {
            SafeWorkspace.TextPage page = projects.read("", path, offset, PAGE_CHARS);
            if (page.text.indexOf('\0') >= 0) throw new IOException("Предпросмотр доступен для текста UTF-8.");
            return new SourcePage(page, CodeViewer.highlight(path, page.text));
        }, result -> {
            SafeWorkspace.TextPage page = result.page;
            if (sourceRevision != null && !sourceRevision.equals(page.revision)) {
                message("Файл изменился. Откройте его с начала.");
                return;
            }
            sourceRevision = page.revision;
            sourceOffset = offset;
            sourceHistory.clear();
            sourceHistory.addAll(history);
            message("Страница " + (history.size() + 1) + " · " + Formatter.formatFileSize(this, page.bytes));

            sourceVertical = new ScrollView(this);
            sourceHorizontal = new HorizontalScrollView(this);
            sourceHorizontal.setFillViewport(true);
            sourceHorizontal.addView(CodeViewer.sourceText(this, result.styled),
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sourceVertical.addView(sourceHorizontal, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            screen.addView(sourceVertical, weighted());
            if (restoreScroll) {
                HorizontalScrollView horizontal = sourceHorizontal;
                ScrollView vertical = sourceVertical;
                horizontal.post(() -> horizontal.scrollTo(sourceX, 0));
                vertical.post(() -> vertical.scrollTo(0, sourceY));
            }

            if (!history.isEmpty() || !page.eof) {
                LinearLayout paging = row();
                Button previous = add(paging, "Предыдущая", false, () -> {
                    ArrayList<Long> preceding = new ArrayList<>(sourceHistory);
                    long start = preceding.remove(preceding.size() - 1);
                    loadSource(start, preceding, false);
                });
                previous.setEnabled(!history.isEmpty());
                Button next = add(paging, "Следующая", false, () -> {
                    ArrayList<Long> prior = new ArrayList<>(sourceHistory);
                    prior.add(sourceOffset);
                    loadSource(page.nextOffset, prior, false);
                });
                next.setEnabled(!page.eof);
                screen.addView(paging);
            }
        });
    }

    private void createProject() {
        prompt("Новый проект", "Имя папки", "", "Создать", name ->
                work("Создание…", () -> projects.create(name), this::showDirectory));
    }

    private void handoff(String target, boolean analyze) {
        work("Открытие…", () -> projects.open(target), project -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_DIRECTORY, project).putExtra(EXTRA_ANALYZE, analyze));
            finish();
        });
    }

    private void reviewDelete(String project) {
        work("Проверка проекта…", () -> projects.prepareDelete(project), plan ->
                new AlertDialog.Builder(this).setTitle("Переместить проект в корзину?")
                        .setMessage("/" + project + "\n\nПроект можно восстановить из корзины.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("В корзину", (dialog, button) ->
                                work("Перемещение…", () -> projects.delete(plan), item -> showDirectory(parent(project))))
                        .show());
    }

    private void showTrash() {
        work("Чтение корзины…", projects::trash, entries -> {
            if (entries.isEmpty()) {
                message("Корзина пуста.");
                return;
            }
            ListView items = list();
            items.setAdapter(new EntryAdapter<>(entries, item -> item.project,
                    item -> DateUtils.formatDateTime(this, item.trashedAt,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME)
                            + " · " + item.id.substring(0, 8)));
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Корзина")
                    .setView(items).setNegativeButton("Закрыть", null).create();
            items.setOnItemClickListener((parent, view, position, id) -> {
                ProjectService.TrashEntry item = entries.get(position);
                dialog.dismiss();
                prompt("Восстановление проекта", "Папка назначения", item.project, "Восстановить", target ->
                        work("Восстановление…", () -> projects.restore(item.id, target),
                                restored -> showDirectory(item.directory ? restored : parent(restored))));
            });
            dialog.show();
        });
    }

    private void chooseImport() {
        new AlertDialog.Builder(this).setTitle("Импорт проекта")
                .setItems(new String[]{"ZIP с устройства", "Репозиторий GitHub"}, (dialog, position) -> {
                    if (position == 0) openZipPicker();
                    else prompt("Репозиторий GitHub", "Владелец/репозиторий или ссылка", "", "Показать ветки",
                            repository -> showBranches(repository, 1));
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void openZipPicker() {
        Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try {
            startActivityForResult(choose, OPEN_ZIP);
        } catch (ActivityNotFoundException e) {
            message("Выбор документов недоступен на устройстве.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Uri uri = data == null ? null : data.getData();
        if (request == SAVE_ZIP && export != null) {
            if (result == RESULT_OK && uri != null) export.saveTo(uri);
            else export.cancelPicker(result == RESULT_OK ? "Система не вернула документ." : null);
        }
        if (request == OPEN_ZIP && result == RESULT_OK && uri != null && projects != null) {
            pendingZip = uri;
            startPendingImport();
        }
    }

    private void startPendingImport() {
        if (pendingZip == null || busy || isFinishing() || isDestroyed()) return;
        Uri uri = pendingZip;
        pendingZip = null;
        work("Проверка ZIP…", () -> {
            String suggestion = suggestProject(displayName(uri));
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Не удалось открыть ZIP.");
                return new ZipPreview(projects.prepareZip(input, ""), suggestion);
            }
        }, preview -> showImport(preview.project, preview.suggestion, true));
    }

    private void showBranches(String repository, int page) {
        work("Получение веток GitHub…", () -> github().listBranches(repository, page), result -> {
            if (result.branches.isEmpty()) {
                message("GitHub не вернул ветки.");
                return;
            }
            ListView items = list();
            items.setAdapter(new EntryAdapter<>(result.branches, branch -> branch.name,
                    branch -> branch.name.equals(result.defaultBranch) ? "Ветка по умолчанию" : ""));
            AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Ветка GitHub")
                    .setView(items).setNegativeButton("Отмена", null);
            if (page > 1) builder.setNeutralButton("Назад", (dialog, button) -> showBranches(repository, page - 1));
            if (result.hasMore) builder.setPositiveButton("Далее", (dialog, button) -> showBranches(repository, page + 1));
            AlertDialog dialog = builder.create();
            items.setOnItemClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                String branch = result.branches.get(position).name;
                String name = repository.replaceAll("/+$", "");
                String suggestion = suggestProject(name.substring(name.lastIndexOf('/') + 1).replaceFirst("\\.git$", ""));
                work("Подготовка файлов GitHub…", () -> projects.prepareGitHub(github(), repository, branch),
                        preview -> showImport(preview, suggestion, false));
            });
            dialog.show();
        });
    }

    private GitHubClient github() throws Exception {
        return new GitHubClient(secrets.loadGitHubToken());
    }

    private void showImport(ProjectService.PreparedImport preview, String suggestion, boolean zip) {
        message("");
        discardImport();
        staged = preview;
        LinearLayout form = column();
        form.setPadding(dp(12), 0, dp(12), 0);
        form.setFocusableInTouchMode(true);
        TextView summary = label(preview.fileCount + " файлов · " + Formatter.formatFileSize(this, preview.bytes)
                + (preview.skippedCount == 0 ? "" : " · пропущено " + preview.skippedCount), 13, MainActivity.WHITE);
        form.addView(summary);
        if (zip) {
            TextView hash = label("SHA-256 " + preview.sha256, 12, MainActivity.MUTED);
            hash.setTextIsSelectable(true);
            form.addView(hash);
        } else {
            TextView source = label(preview.source, 12, MainActivity.MUTED);
            source.setTextIsSelectable(true);
            form.addView(source);
        }
        EditText destination = edit("Папка назначения", (directory.isEmpty() ? "" : directory + "/") + suggestion);
        form.addView(destination);
        EditText expectedSha = edit("Ожидаемый SHA-256 (если известен)", "");
        if (zip) form.addView(expectedSha);
        ListView contents = list();
        contents.setAdapter(new EntryAdapter<>(preview.entries,
                item -> (item.directory ? "▸ " : "") + item.path,
                item -> item.directory ? "" : Formatter.formatFileSize(this, item.bytes)));
        contents.setOnItemClickListener((parent, view, position, id) ->
                new AlertDialog.Builder(this).setMessage(preview.entries.get(position).path)
                        .setPositiveButton("Закрыть", null).show());
        form.addView(contents, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(Math.min(260, Math.max(88, getResources().getConfiguration().screenHeightDp / 3)))));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Содержимое проекта")
                .setView(form).setNegativeButton("Отмена", null)
                .setPositiveButton("Импортировать", null).create();
        dialog.setOnDismissListener(d -> {
            if (staged == preview) discardImport();
        });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (busy) return;
            String expected = expectedSha.getText().toString().trim();
            if (zip && !expected.isEmpty() && !preview.sha256.equalsIgnoreCase(expected)) {
                expectedSha.setError("SHA-256 архива не совпадает.");
                return;
            }
            String target = destination.getText().toString().trim();
            if (target.isEmpty()) {
                destination.setError("Укажите папку.");
                return;
            }
            work("Проверка назначения…", () -> projects.prepareTarget(target), checked -> {
                if (staged != preview || !dialog.isShowing()) return;
                message("");
                if (checked.exists) confirmReplacement(preview, checked, dialog);
                else publish(preview, checked, false, dialog);
            }, error -> destination.setError(error));
        }));
        dialog.show();
        form.requestFocus();
    }

    private void confirmReplacement(ProjectService.PreparedImport source, ProjectService.ImportTarget target,
            AlertDialog preview) {
        new AlertDialog.Builder(this).setTitle("Заменить существующий проект?")
                .setMessage("/" + target.project + "\n\nСуществующий проект целиком переместится в корзину. "
                        + "Импортированные файлы займут его место.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Заменить", (dialog, button) -> publish(source, target, true, preview))
                .show();
    }

    private void publish(ProjectService.PreparedImport source, ProjectService.ImportTarget target,
            boolean replace, AlertDialog dialog) {
        if (busy || staged != source) return;
        staged = null;
        dialog.dismiss();
        work("Импорт проекта…", () -> {
            try (ProjectService.PreparedImport input = source) {
                return projects.publish(input, target, replace);
            }
        }, result -> {
            Toast.makeText(this, "Импортировано файлов " + result.fileCount, Toast.LENGTH_SHORT).show();
            showDirectory(result.project);
        });
    }

    private void discardImport() {
        if (staged == null) return;
        ProjectService.PreparedImport previous = staged;
        staged = null;
        IMPORT_CLEANUP.execute(() -> closePrepared(previous));
    }

    private static void closePrepared(ProjectService.PreparedImport preview) {
        try { preview.close(); }
        catch (IOException e) { android.util.Log.w("KentProjects", "Не удалось очистить временный импорт", e); }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        } catch (RuntimeException e) {
            android.util.Log.w("KentProjects", "Имя документа недоступно");
        }
        return "project.zip";
    }

    private static String suggestProject(String name) {
        String normalized = name.replaceFirst("(?i)\\.zip$", "").replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (normalized.isEmpty() || normalized.startsWith(".")) return "project";
        return normalized;
    }

    private void exportChanged() {
        if (export == null || !resumed) return;
        switch (export.phase()) {
            case PREPARING -> message("Подготовка ZIP…");
            case SAVING -> message("Сохранение и проверка ZIP…");
            case READY -> {
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip").putExtra(Intent.EXTRA_TITLE, export.openPicker());
                try {
                    startActivityForResult(save, SAVE_ZIP);
                } catch (ActivityNotFoundException e) {
                    export.cancelPicker("Сохранение документов недоступно на устройстве.");
                }
            }
            case IDLE -> {
                WorkspaceExportSession.Result result = export.result();
                if (result == null) { if (!busy) message(""); return; }
                export.clearResult();
                if (result.archive == null) {
                    message(result.message);
                    return;
                }
                message("ZIP сохранён и проверен.");
                TextView details = label(result.message + "\n" + result.archive.fileCount + " файлов · "
                        + Formatter.formatFileSize(this, result.archive.bytes) + "\n\nSHA-256\n" + result.archive.sha256,
                        14, MainActivity.WHITE);
                details.setPadding(dp(18), dp(8), dp(18), dp(8));
                details.setTextIsSelectable(true);
                new AlertDialog.Builder(this).setTitle("Экспорт готов").setView(details)
                        .setPositiveButton("Закрыть", null).show();
            }
            case PICKER_OPEN -> message("Выберите место сохранения ZIP.");
        }
    }

    private <T> void work(String loading, Callable<T> task, Consumer<T> success) {
        work(loading, task, success, this::message);
    }

    private <T> void work(String loading, Callable<T> task, Consumer<T> success, Consumer<String> failure) {
        if (busy) return;
        busy = true;
        message(loading);
        worker.execute(() -> {
            try {
                T result = task.call();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        if (result instanceof ProjectService.PreparedImport preview) {
                            IMPORT_CLEANUP.execute(() -> closePrepared(preview));
                        } else if (result instanceof ZipPreview preview) {
                            IMPORT_CLEANUP.execute(() -> closePrepared(preview.project));
                        }
                        return;
                    }
                    busy = false;
                    success.accept(result);
                    startPendingImport();
                });
            } catch (Exception e) {
                String detail = e.getMessage();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy = false;
                    failure.accept(detail == null || detail.isEmpty() ? "Операция завершилась ошибкой." : detail);
                    startPendingImport();
                });
            }
        });
    }

    private void prompt(String title, String hint, String initial, String action, Consumer<String> submit) {
        EditText field = edit(hint, initial);
        LinearLayout form = column();
        form.setPadding(dp(16), 0, dp(16), 0);
        form.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(form)
                .setNegativeButton("Отмена", null).setPositiveButton(action, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = field.getText().toString().trim();
            if (value.isEmpty()) {
                field.setError("Заполните поле.");
                return;
            }
            dialog.dismiss();
            submit.accept(value);
        }));
        dialog.show();
    }

    private void frame(String title) {
        screen.removeAllViews();
        files = null;
        sourceVertical = null;
        sourceHorizontal = null;
        status = null;
        LinearLayout header = row();
        header.setBackgroundColor(MainActivity.PANEL);
        TextView heading = label(title, 17, MainActivity.WHITE);
        heading.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button done = button("Закрыть", false);
        done.setOnClickListener(view -> closeScreen());
        header.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        screen.addView(header);
    }

    private void makeStatus() {
        status = label("", 13, MainActivity.GOLD);
        status.setPadding(dp(12), dp(2), dp(12), dp(4));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setTextIsSelectable(true);
        screen.addView(status);
    }

    private void message(String text) {
        if (status == null) makeStatus();
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(3), dp(8), dp(3));
        return row;
    }

    private ListView list() {
        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        return list;
    }

    private Button add(LinearLayout row, String label, boolean primary, Runnable action) {
        Button button = button(label, primary);
        button.setOnClickListener(view -> { if (!busy) action.run(); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        if (row.getChildCount() > 0) params.leftMargin = dp(4);
        row.addView(button, params);
        return button;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setTextColor(MainActivity.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(4));
        background.setColor(primary ? 0xFFAF2544 : MainActivity.PANEL_2);
        background.setStroke(dp(1), MainActivity.OUTLINE);
        button.setBackground(background);
        return button;
    }

    private TextView label(String content, int size, int color) {
        TextView label = new TextView(this);
        label.setText(content);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private EditText edit(String hint, String initial) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(initial);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(true);
        return field;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private final class EntryAdapter<T> extends BaseAdapter {
        private final List<T> entries;
        private final Function<T, String> heading;
        private final Function<T, String> detail;

        EntryAdapter(List<T> entries, Function<T, String> heading, Function<T, String> detail) {
            this.entries = entries;
            this.heading = heading;
            this.detail = detail;
        }

        @Override public int getCount() { return entries.size(); }
        @Override public T getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View reusable, ViewGroup parent) {
            LinearLayout row;
            if (reusable == null) {
                row = column();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(44));
                row.setPadding(dp(12), dp(4), dp(12), dp(4));
                row.setBackgroundColor(MainActivity.PANEL);
                TextView title = label("", 14, MainActivity.WHITE);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                row.addView(title);
                row.addView(label("", 12, MainActivity.MUTED));
            } else {
                row = (LinearLayout) reusable;
            }
            T item = getItem(position);
            ((TextView) row.getChildAt(0)).setText(heading.apply(item));
            TextView secondary = (TextView) row.getChildAt(1);
            String info = detail.apply(item);
            secondary.setText(info);
            secondary.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
