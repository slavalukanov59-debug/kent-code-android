package ai.kent.code;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

/** Direct user controls for GitHub. Reviewed writes never run through Autopilot. */
final class GitHubPanel implements AutoCloseable {
    private static final int BG = Color.rgb(11, 11, 14);
    private static final int PANEL = Color.rgb(23, 23, 29);
    private static final int PANEL_SECONDARY = Color.rgb(37, 37, 46);
    private static final int OUTLINE = Color.rgb(73, 49, 59);
    private static final int FG = Color.rgb(243, 240, 242);
    private static final int MUTED = Color.rgb(174, 170, 182);
    private static final int ACCENT = Color.rgb(255, 80, 109);
    private static final int PRIMARY = Color.rgb(175, 37, 68);

    private final Activity activity;
    private final SecureKeyStore secrets;
    private final SafeWorkspace workspace;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScrollView scroll;
    private final List<Button> actions = new ArrayList<>();
    private final Button tokenButton;
    private final EditText repo;
    private final EditText ref;
    private final EditText path;
    private final TextView status;
    private final Button review;
    private final TextView resultTitle;
    private final TextView resultText;
    private final Button resultLink;
    private final Button copyFile;
    private AlertDialog dialog;
    private GitHubClient.Change pending;
    private boolean busy;
    private volatile boolean closed;

    GitHubPanel(Activity activity, SecureKeyStore secrets, SafeWorkspace workspace) {
        this.activity = activity;
        this.secrets = secrets;
        this.workspace = workspace;
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout form = column();
        form.setPadding(dp(12), dp(8), dp(12), dp(18));
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));

        tokenButton = button(tokenLabel(), false, this::tokenDialog);
        add(form, tokenButton);
        repo = field("Владелец/репозиторий", "", false);
        ref = field("Ветка, тег или SHA", "", false);
        path = field("Файл или каталог", "", false);
        add(form, repo);
        add(form, ref);
        add(form, path);

        row(form, button("Файлы", false, () -> read((client, r, b, p) -> client.contents(r, b, p))),
                button("Дерево", false, () -> read((client, r, b, p) -> client.tree(r, b))),
                button("Коммиты", false, () -> read((client, r, b, p) -> client.commits(r, b))));
        row(form, button("Изменения", false, this::compareDialog),
                button("Проверки", false, () -> read((client, r, b, p) -> client.actions(r, b))));

        row(form, button("Новая ветка", true, this::branchDialog),
                button("Коммит файла", true, this::editFile),
                button("Черновик PR", true, this::pullDialog));

        status = label("", MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        add(form, status);
        review = button("Проверить изменение", true, this::reviewDialog);
        review.setVisibility(View.GONE);
        add(form, review);

        resultTitle = label("", ACCENT);
        resultTitle.setVisibility(View.GONE);
        add(form, resultTitle);
        resultText = label("", FG);
        resultText.setTextIsSelectable(true);
        resultText.setPadding(dp(10), dp(10), dp(10), dp(10));
        resultText.setBackground(border(PANEL));
        resultText.setVisibility(View.GONE);
        resultLink = button("Открыть на GitHub", false, () -> {});
        resultLink.setVisibility(View.GONE);
        add(form, resultLink);
        copyFile = button("В рабочую область", true, () -> {});
        copyFile.setVisibility(View.GONE);
        add(form, copyFile);
        add(form, resultText);
    }

    View view() {
        if (scroll.getParent() instanceof ViewGroup) ((ViewGroup) scroll.getParent()).removeView(scroll);
        return scroll;
    }

    @Override public void close() {
        closed = true;
        executor.shutdownNow();
        if (dialog != null) dialog.dismiss();
        pending = null;
    }

    private String tokenLabel() {
        return secrets.hasGitHubToken() ? "Токен GitHub сохранён" : "Добавить токен GitHub";
    }

    private void tokenDialog() {
        LinearLayout form = dialogContent();
        TextView help = label("Публичные репозитории доступны без токена. Для записи выберите репозиторий и выдайте токену права Contents: Read and write и Pull requests: Read and write.", MUTED);
        add(form, help);
        EditText token = field("Персональный токен GitHub", "", false);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        token.setSaveEnabled(false);
        add(form, token);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Доступ к GitHub").setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String value = value(token).trim();
                    token.setText("");
                    if (value.isEmpty()) {
                        setStatus("Введите токен GitHub.", true);
                    } else {
                        changeToken(value);
                    }
                });
        if (secrets.hasGitHubToken())
            builder.setNeutralButton("Удалить токен", (d, w) -> changeToken(""));
        show(builder);
    }

    private void changeToken(String value) {
        submit(() -> {
            secrets.saveGitHubToken(value);
            return secrets.hasGitHubToken();
        }, saved -> {
            tokenButton.setText(tokenLabel());
            setPending(null);
            clearResult();
            setStatus(saved ? "Токен сохранён на устройстве." : "Токен удалён.", false);
        });
    }

    private void compareDialog() {
        String repository = value(repo);
        EditText base = field("Базовая ветка, тег или SHA", "", false);
        EditText head = field("Сравниваемая ветка, тег или SHA", value(ref), false);
        LinearLayout form = dialogContent();
        add(form, base);
        add(form, head);
        show(new AlertDialog.Builder(activity).setTitle("Сравнение GitHub").setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Показать diff", (d, w) -> {
                    String source = value(base);
                    String target = value(head);
                    submit(() -> client().compare(repository, source, target), this::showResult);
                }));
    }

    private void branchDialog() {
        String repository = value(repo);
        EditText source = field("Исходная ветка, тег или SHA", value(ref), false);
        EditText target = field("Имя новой ветки", "", false);
        LinearLayout form = dialogContent();
        add(form, source);
        add(form, target);
        show(new AlertDialog.Builder(activity).setTitle("Новая ветка").setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Подготовить", (d, w) -> {
                    String start = value(source);
                    String branch = value(target);
                    submit(() -> client().prepareBranch(repository, start, branch), this::setPending);
                }));
    }

    private void editFile() {
        String repository = value(repo);
        String target = value(ref);
        String file = value(path);
        submit(() -> client().openForEditing(repository, target, file), opened -> {
            setStatus("", false);
            if (scroll.isAttachedToWindow() && activity.hasWindowFocus()) fileDialog(opened);
            else setStatus("Откройте файл для редактирования повторно.", false);
        });
    }

    private void fileDialog(GitHubClient.WorkingFile opened) {
        EditText message = field("Сообщение коммита", "", false);
        EditText text = field("Содержимое файла", opened.text == null ? "" : opened.text, true);
        LinearLayout form = dialogContent();
        add(form, message);
        add(form, text);
        EditText local = field("Путь в рабочей области", opened.path, false);
        add(form, local);
        TextView loading = label("", MUTED);
        AlertDialog editor = new AlertDialog.Builder(activity).setTitle(opened.path).setView(scrollable(form))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Подготовить diff", null).create();
        Button load = createButton("Загрузить из рабочей области", false);
        load.setOnClickListener(v -> {
            String path = value(local);
            load.setEnabled(false);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            loading.setText("Чтение файла…");
            submit(() -> GitHubWorkspaceTransfer.readFile(workspace, path), loaded -> {
                setStatus("", false);
                if (!editor.isShowing()) return;
                text.setText(loaded);
                loading.setText("Текст загружен. Проверьте diff перед отправкой.");
                load.setEnabled(true);
                editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            }, error -> {
                setStatus("", false);
                if (!editor.isShowing()) return;
                loading.setText(error);
                load.setEnabled(true);
                editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            });
        });
        add(form, load);
        add(form, loading);
        show(editor);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = value(message);
            String replacement = value(text);
            load.setEnabled(false);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            submit(() -> client().prepareFile(opened, replacement, title), change -> {
                if (!editor.isShowing()) {
                    setStatus("", false);
                    return;
                }
                editor.dismiss();
                setPending(change);
            }, error -> {
                setStatus("", false);
                if (!editor.isShowing()) return;
                loading.setText(error);
                load.setEnabled(true);
                editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            });
        });
    }

    private void pullDialog() {
        String repository = value(repo);
        EditText head = field("Рабочая ветка", value(ref), false);
        EditText base = field("Целевая ветка", "", false);
        EditText title = field("Заголовок PR", "", false);
        EditText body = field("Описание PR", "", true);
        LinearLayout form = dialogContent();
        add(form, head);
        add(form, base);
        add(form, title);
        add(form, body);
        show(new AlertDialog.Builder(activity).setTitle("Черновик PR").setView(scrollable(form))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Подготовить", (d, w) -> {
                    String branch = value(head);
                    String into = value(base);
                    String subject = value(title);
                    String description = value(body);
                    submit(() -> client().preparePullRequest(repository, branch, into, subject, description),
                            this::setPending);
                }));
    }

    private void reviewDialog() {
        GitHubClient.Change change = pending;
        if (change == null) return;
        ScrollView viewer = new ScrollView(activity);
        TextView preview = label(change.preview, FG);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(18), dp(10), dp(18), dp(10));
        viewer.addView(preview, new ScrollView.LayoutParams(-1, -2));
        show(new AlertDialog.Builder(activity).setTitle(change.title).setView(viewer)
                .setNegativeButton("Назад", null)
                .setNeutralButton("Снять", (d, w) -> {
                    setPending(null);
                    setStatus("", false);
                })
                .setPositiveButton("Подтвердить отправку", (d, w) -> {
                    setPending(null);
                    submit(() -> client().apply(change), this::showResult);
                }));
    }

    private void setPending(GitHubClient.Change change) {
        pending = change;
        review.setVisibility(change == null ? View.GONE : View.VISIBLE);
        if (change != null) {
            review.setText("Проверить · " + change.title);
            setStatus("Превью готово к проверке.", false);
            scroll.post(() -> scroll.smoothScrollTo(0, review.getTop()));
        }
    }

    private void clearResult() {
        resultTitle.setText("");
        resultText.setText("");
        resultTitle.setVisibility(View.GONE);
        resultText.setVisibility(View.GONE);
        resultLink.setVisibility(View.GONE);
        resultLink.setOnClickListener(null);
        copyFile.setVisibility(View.GONE);
        copyFile.setOnClickListener(null);
    }

    private void showResult(GitHubClient.Result result) {
        setStatus("", false);
        resultTitle.setText(result.title);
        resultTitle.setVisibility(View.VISIBLE);
        resultText.setText(result.text);
        resultText.setVisibility(View.VISIBLE);
        resultLink.setOnClickListener(v -> open(result.link));
        resultLink.setVisibility(View.VISIBLE);
        copyFile.setVisibility(result.textPath.isEmpty() ? View.GONE : View.VISIBLE);
        copyFile.setOnClickListener(v -> importDialog(result));
        scroll.post(() -> scroll.smoothScrollTo(0, resultTitle.getTop()));
    }

    private void importDialog(GitHubClient.Result result) {
        EditText destination = field("Путь в рабочей области", result.textPath, false);
        LinearLayout form = dialogContent();
        add(form, destination);
        show(new AlertDialog.Builder(activity).setTitle("Копировать файл в проект").setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Копировать", (d, w) -> {
                    String path = value(destination);
                    submit(() -> {
                        GitHubWorkspaceTransfer.importFile(workspace, path, result.text);
                        return path;
                    }, imported -> setStatus("Скопировано в рабочую область · " + imported, false));
                }));
    }

    private void open(String link) {
        Uri uri = Uri.parse(link);
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException unavailable) {
            setStatus("Браузер недоступен.", true);
        }
    }

    private GitHubClient client() throws Exception {
        return new GitHubClient(secrets.loadGitHubToken());
    }

    private interface Read {
        GitHubClient.Result run(GitHubClient client, String repo, String ref, String path) throws Exception;
    }

    private void read(Read read) {
        String repository = value(repo);
        String selected = value(ref);
        String relative = value(path);
        submit(() -> read.run(client(), repository, selected, relative), this::showResult);
    }

    private interface Success<T> { void accept(T result); }

    private <T> void submit(Callable<T> operation, Success<T> success) {
        submit(operation, success, error -> setStatus(error, true));
    }

    private <T> void submit(Callable<T> operation, Success<T> success, Success<String> failure) {
        if (closed || busy) return;
        setBusy(true);
        executor.execute(() -> {
            try {
                T result = operation.call();
                activity.runOnUiThread(() -> {
                    if (closed || activity.isFinishing() || activity.isDestroyed()) return;
                    setBusy(false);
                    success.accept(result);
                });
            } catch (Exception error) {
                String message = friendlyError(error);
                activity.runOnUiThread(() -> {
                    if (closed || activity.isFinishing() || activity.isDestroyed()) return;
                    setBusy(false);
                    failure.accept(message);
                });
            }
        });
    }

    private static String friendlyError(Exception error) {
        if (error instanceof SSLException) return "Защищённое подключение к GitHub не установлено.";
        if (error instanceof SocketTimeoutException || error instanceof SocketException || error instanceof UnknownHostException)
            return "Связь с GitHub недоступна. Попробуйте позже.";
        if (error instanceof IOException || error instanceof IllegalArgumentException
                || error instanceof IllegalStateException) {
            String message = error.getMessage();
            if (message != null) return SensitiveData.redact(message);
        }
        return "Не удалось прочитать ответ GitHub или локальное хранилище.";
    }

    private void setBusy(boolean value) {
        busy = value;
        for (Button action : actions) action.setEnabled(!value);
        if (value) setStatus("Выполняется операция…", false);
    }

    private void setStatus(String message, boolean error) {
        status.setTextColor(error ? ACCENT : MUTED);
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void show(AlertDialog.Builder builder) {
        show(builder.create());
    }

    private void show(AlertDialog created) {
        dialog = created;
        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout dialogContent() {
        LinearLayout form = column();
        form.setPadding(dp(16), dp(4), dp(16), dp(8));
        return form;
    }

    private ScrollView scrollable(LinearLayout content) {
        ScrollView container = new ScrollView(activity);
        container.addView(content, new ScrollView.LayoutParams(-1, -2));
        return container;
    }

    private void row(LinearLayout form, Button... buttons) {
        LinearLayout row = new LinearLayout(activity);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) p.leftMargin = dp(5);
            row.addView(buttons[i], p);
        }
        add(form, row);
    }

    private void add(LinearLayout form, View child) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(5);
        form.addView(child, p);
    }

    private TextView label(String value, int color) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextColor(color);
        label.setTypeface(Typeface.MONOSPACE);
        label.setTextSize(13);
        return label;
    }

    private EditText field(String hint, String value, boolean multiline) {
        EditText field = new EditText(activity);
        field.setSingleLine(!multiline);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        field.setTextColor(FG);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(14);
        field.setTypeface(Typeface.MONOSPACE);
        field.setBackground(border(PANEL));
        field.setPadding(dp(10), dp(8), dp(10), dp(8));
        field.setMinHeight(dp(44));
        if (multiline) {
            field.setMinLines(3);
            field.setMaxLines(10);
            field.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        }
        return field;
    }

    private Button button(String label, boolean primary, Runnable click) {
        Button button = createButton(label, primary);
        button.setOnClickListener(v -> click.run());
        actions.add(button);
        return button;
    }

    private Button createButton(String label, boolean primary) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(FG);
        button.setTextSize(13);
        button.setMinHeight(dp(48));
        button.setPadding(dp(5), dp(3), dp(5), dp(3));
        button.setBackground(border(primary ? PRIMARY : PANEL_SECONDARY));
        return button;
    }

    private GradientDrawable border(int fill) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(1), OUTLINE);
        drawable.setCornerRadius(dp(3));
        return drawable;
    }

    private static String value(EditText input) { return input.getText().toString(); }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
