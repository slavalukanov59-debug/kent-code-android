package ai.kent.code;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native Android UI. No Termux, root, WebView server or shell dependency. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 14, 26);
    private static final int PANEL = Color.rgb(18, 30, 50);
    private static final int PANEL_2 = Color.rgb(24, 42, 69);
    private static final int BLUE = Color.rgb(70, 159, 255);
    private static final int WHITE = Color.rgb(232, 242, 255);
    private static final int MUTED = Color.rgb(148, 169, 200);
    private static final int GOLD = Color.rgb(250, 195, 87);
    private static final int GREEN = Color.rgb(90, 218, 158);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private SafeWorkspace workspace;
    private SecureKeyStore secrets;
    private KentAgent agent;
    private LinearLayout app;
    private LinearLayout mainArea;
    private LinearLayout chatFeed;
    private ScrollView chatScroll;
    private EditText composer;
    private TextView runStatus;
    private int tab = 0;
    private volatile boolean running = false;
    private final List<ChatItem> messages = new ArrayList<>();

    private static final class ChatItem {
        final String title, text;
        ChatItem(String t, String s) { title = t; text = s; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        try {
            workspace = new SafeWorkspace(new java.io.File(getFilesDir(), "workspace"));
            secrets = new SecureKeyStore(this);
            if (workspace.filePaths().isEmpty()) workspace.write("hello.py", "def greet(name):\n    return f'Привет, {name}!'\n\nprint(greet('КЕНТ'))\n");
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Ошибка хранилища")
                    .setMessage(e.getMessage()).setPositiveButton("Закрыть", (d, w) -> finish()).show();
            return;
        }
        messages.add(new ChatItem("КЕНТ", "Здарова, братан! Это отдельное Android-приложение. " +
                "Открой ⚙ Настройки, добавь новый ключ AI-FLEX, затем дай мне задачу. " +
                "Я могу изучать и редактировать файлы в собственной рабочей папке. " +
                "Запуск shell-команд и полноценный Git пока не реализованы."));
        agent = new KentAgent(workspace, secrets, new KentAgent.Events() {
            @Override public void log(String title, String text) { runOnUiThread(() -> addMessage(title, text)); }
            @Override public boolean approve(String title, String detail) { return requestApproval(title, detail); }
            @Override public boolean cancelled() { return stopRequested.get(); }
        });
        draw();
    }

    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), Color.rgb(43, 65, 97));
        return g;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(3), 1.05f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(WHITE);
        b.setBackground(shape(primary ? Color.rgb(16, 113, 244) : PANEL_2, 11));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        return b;
    }

    private EditText field(String hint, String value) {
        EditText f = new EditText(this);
        f.setText(value);
        f.setHint(hint);
        f.setHintTextColor(MUTED);
        f.setSingleLine(false);
        f.setTextColor(WHITE);
        f.setTextSize(15);
        f.setPadding(dp(12), dp(12), dp(12), dp(12));
        f.setBackground(shape(PANEL, 10));
        return f;
    }

    private void padded(LinearLayout parent, View v, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        parent.addView(v, p);
    }

    private void draw() {
        app = vertical();
        app.setBackgroundColor(BG);
        setContentView(app);
        LinearLayout head = vertical();
        head.setPadding(dp(18), dp(13), dp(18), dp(14));
        head.setBackgroundColor(Color.rgb(14, 23, 39));
        head.addView(text("⚡ КЕНТ CODE  ·  Android 0.1", 22, WHITE, true));
        padded(head, text("ИИ-агент для программирования • AI-FLEX", 12, MUTED, false), 2);
        app.addView(head);

        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(10), dp(8), dp(10), dp(8));
        nav.setBackgroundColor(Color.rgb(14, 23, 39));
        String[] labels = {"💬 Чат", "📁 Файлы", "⚙ Настройки"};
        for (int i = 0; i < labels.length; i++) {
            final int selected = i;
            Button b = button(labels[i], i == tab);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            nav.addView(b, lp);
            b.setOnClickListener(v -> { tab = selected; draw(); });
        }
        app.addView(nav);
        mainArea = vertical();
        LinearLayout.LayoutParams area = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        app.addView(mainArea, area);
        if (tab == 0) drawChat();
        if (tab == 1) drawFiles();
        if (tab == 2) drawSettings();
    }

    private void drawChat() {
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setPadding(dp(16), dp(12), dp(16), dp(9));
        runStatus = text(running ? "🟡 Агент работает…" : "🟢 Готов к задачам", 13,
                running ? GOLD : GREEN, true);
        LinearLayout.LayoutParams statusWeight = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        statusRow.addView(runStatus, statusWeight);
        Button cancel = button("■ Стоп", false);
        cancel.setOnClickListener(v -> { stopRequested.set(true); toast("Остановка после текущего API-запроса"); });
        statusRow.addView(cancel, new LinearLayout.LayoutParams(dp(98), dp(45)));
        mainArea.addView(statusRow);

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatFeed = vertical();
        chatFeed.setPadding(dp(14), dp(8), dp(14), dp(12));
        chatScroll.addView(chatFeed);
        mainArea.addView(chatScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        redrawFeed();

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(12), dp(8), dp(12), dp(8));
        Button clear = button("Очистить диалог", false);
        clear.setOnClickListener(v -> {
            if (running) { toast("Сначала останови текущую задачу"); return; }
            agent.clear(); messages.clear();
            addMessage("КЕНТ", "Контекст очищен. Файлы проекта не затронуты.");
        });
        actions.addView(clear);
        mainArea.addView(actions);

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setPadding(dp(12), dp(6), dp(12), dp(14));
        composer = field("Опиши задачу КЕНТУ…", "");
        composer.setMinLines(2);
        composer.setMaxLines(5);
        LinearLayout.LayoutParams entry = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        sendRow.addView(composer, entry);
        Button send = button("➤", true);
        LinearLayout.LayoutParams go = new LinearLayout.LayoutParams(dp(66), dp(72));
        go.leftMargin = dp(8);
        sendRow.addView(send, go);
        send.setOnClickListener(v -> submit());
        mainArea.addView(sendRow);
    }

    private void addMessage(String who, String message) {
        messages.add(new ChatItem(who, message));
        if (tab == 0 && chatFeed != null) {
            appendBubble(who, message);
            if (chatScroll != null) chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void redrawFeed() {
        if (chatFeed == null) return;
        chatFeed.removeAllViews();
        for (ChatItem item : messages) appendBubble(item.title, item.text);
        if (chatScroll != null) chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void appendBubble(String who, String message) {
        LinearLayout card = vertical();
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        int fill = who.equals("ТЫ") ? Color.rgb(18, 57, 94) : PANEL;
        card.setBackground(shape(fill, 13));
        card.addView(text(who, 11, who.startsWith("Ошибка") ? GOLD : BLUE, true));
        padded(card, text(message, 14, WHITE, false), 5);
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.bottomMargin = dp(10);
        box.leftMargin = who.equals("ТЫ") ? dp(26) : 0;
        box.rightMargin = who.equals("ТЫ") ? 0 : dp(20);
        chatFeed.addView(card, box);
    }

    private void submit() {
        if (running) { toast("Предыдущая задача ещё выполняется"); return; }
        String prompt = composer.getText().toString().trim();
        if (prompt.isEmpty()) return;
        try {
            if (secrets.load().trim().isEmpty()) {
                toast("Сначала настрой API-ключ"); tab = 2; draw(); return;
            }
        } catch (Exception e) { toast("Ошибка ключа: сбрось его в настройках"); return; }
        composer.setText("");
        addMessage("ТЫ", prompt);
        running = true;
        stopRequested.set(false);
        if (runStatus != null) runStatus.setText("🟡 Агент работает…");
        executor.submit(() -> {
            try { agent.task(prompt); }
            finally {
                running = false;
                runOnUiThread(() -> { if (tab == 0 && runStatus != null) runStatus.setText("🟢 Готов к задачам"); });
            }
        });
    }

    private boolean requestApproval(String title, String detail) {
        if (isFinishing() || stopRequested.get()) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean(false);
        runOnUiThread(() -> {
            if (isFinishing() || stopRequested.get()) { latch.countDown(); return; }
            ScrollView scroll = new ScrollView(this);
            TextView preview = text(detail, 12, WHITE, false);
            preview.setTypeface(Typeface.MONOSPACE);
            preview.setPadding(dp(12), dp(10), dp(12), dp(10));
            scroll.addView(preview);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("Проверь файл и содержимое перед разрешением.")
                    .setView(scroll)
                    .setPositiveButton("Разрешить один раз", (d, w) -> { approved.set(true); latch.countDown(); })
                    .setNegativeButton("Запретить", (d, w) -> latch.countDown())
                    .setOnCancelListener(d -> latch.countDown())
                    .create();
            dialog.show();
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(540));
        });
        try { latch.await(5, TimeUnit.MINUTES); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return !stopRequested.get() && approved.get();
    }

    private void drawFiles() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(dp(16), dp(18), dp(16), dp(18));
        scroll.addView(content);
        mainArea.addView(scroll);
        padded(content, text("📂 Рабочая папка", 22, WHITE, true), 2);
        padded(content, text("Это отдельное хранилище приложения, другие проекты не затрагиваются.", 13, MUTED, false), 5);
        LinearLayout row = new LinearLayout(this);
        Button create = button("+ Новый файл", true);
        Button importButton = button("⇧ Импорт", false);
        row.addView(create, new LinearLayout.LayoutParams(0, dp(53), 1));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(53), 1); right.leftMargin = dp(10);
        row.addView(importButton, right);
        create.setOnClickListener(v -> createFile());
        importButton.setOnClickListener(v -> importFile());
        padded(content, row, 20);
        try {
            List<String> paths = workspace.filePaths();
            if (paths.isEmpty()) padded(content, text("Пока нет файлов", 14, MUTED, false), 17);
            for (String path : paths) {
                Button f = button("📄  " + path, false);
                f.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                f.setOnClickListener(v -> editFile(path));
                padded(content, f, 8);
            }
        } catch (Exception e) {
            padded(content, text(e.getMessage(), 14, GOLD, false), 10);
        }
    }

    private void createFile() {
        EditText name = field("Например: src/main.py", "");
        new AlertDialog.Builder(this).setTitle("Новый файл")
                .setView(name).setPositiveButton("Создать", (d, w) -> {
                    String path = name.getText().toString().trim();
                    try {
                        if (workspace.resolve(path).exists()) throw new IOException("Файл уже существует");
                        workspace.write(path, "");
                        draw(); editFile(path);
                    } catch (Exception e) { toast(e.getMessage()); }
                }).setNegativeButton("Отмена", null).show();
    }

    private void editFile(String path) {
        try {
            String content = workspace.read(path);
            EditText editor = field("Содержимое", content);
            editor.setTypeface(Typeface.MONOSPACE);
            editor.setMinLines(9);
            editor.setMaxLines(24);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(editor);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("📝 " + path)
                    .setView(scroll)
                    .setPositiveButton("Сохранить", (d, w) -> {
                        try { workspace.write(path, editor.getText().toString()); toast("Сохранено: " + path); }
                        catch (Exception e) { toast(e.getMessage()); }
                    })
                    .setNeutralButton("Удалить", (d, w) -> {
                        new AlertDialog.Builder(this).setTitle("Точно удалить " + path + "?")
                                .setPositiveButton("Удалить", (dd, ww) -> {
                                    try { workspace.delete(path); draw(); }
                                    catch (Exception e) { toast(e.getMessage()); }
                                }).setNegativeButton("Отмена", null).show();
                    })
                    .setNegativeButton("Закрыть", null).create();
            dialog.show();
        } catch (Exception e) { toast(e.getMessage()); }
    }

    private void importFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, 100);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 100 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = "import.txt";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = cursor.getString(i);
            }
        } catch (Exception ignored) { }
        name = name == null ? "import.txt" : name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (name.trim().isEmpty() || name.equals(".") || name.equals("..")) name = "import.txt";
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Не удалось открыть файл");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = input.read(chunk)) != -1) {
                if (buffer.size() + n > SafeWorkspace.MAX_READ_BYTES) throw new IOException("Файл слишком большой (>120 KB)");
                buffer.write(chunk, 0, n);
            }
            byte[] bytes = buffer.toByteArray();
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (!java.util.Arrays.equals(body.getBytes(StandardCharsets.UTF_8), bytes))
                throw new IOException("Импорт поддерживает только текст UTF-8");
            String path = "imports/" + name;
            int suffix = 1;
            while (workspace.resolve(path).exists()) path = "imports/" + suffix++ + "_" + name;
            workspace.write(path, body);
            toast("Импортировано: " + path);
            if (tab == 1) draw();
        } catch (Exception e) { toast("Импорт: " + e.getMessage()); }
    }

    private void drawSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(20), dp(18), dp(18));
        scroll.addView(content);
        mainArea.addView(scroll);
        padded(content, text("⚙ Настройки AI-FLEX", 23, WHITE, true), 2);
        padded(content, text("Ключ хранится локально в зашифрованном виде Android Keystore. " +
                "Не публикуй API-ключи в GitHub и переписке.", 13, MUTED, false), 8);
        padded(content, text("🔑 Новый API-ключ (пусто = оставить старый)", 13, MUTED, true), 24);
        EditText key = field("sk-…", "");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        padded(content, key, 7);
        padded(content, text("🧠 Модель — идентификатор из /v1/models", 13, MUTED, true), 19);
        EditText model = field("Имя модели", secrets.model());
        padded(content, model, 7);
        padded(content, text("Примеры: deepseek-v4.1-flash-1017, deepseek-v4-pro, cn-sonnet-5-5. " +
                "Доступность и стоимость зависят от AI-FLEX.", 12, MUTED, false), 5);
        padded(content, text("🔄 Максимум API-шагов на задачу (1–100)", 13, MUTED, true), 18);
        EditText steps = field("12", String.valueOf(secrets.steps()));
        steps.setInputType(InputType.TYPE_CLASS_NUMBER);
        padded(content, steps, 7);
        Button save = button("Сохранить настройки", true);
        padded(content, save, 20);
        save.setOnClickListener(v -> {
            if (running) { toast("Дождись завершения задачи"); return; }
            try {
                if (model.getText().toString().trim().isEmpty()) throw new IOException("Укажи модель");
                if (!key.getText().toString().trim().isEmpty()) secrets.save(key.getText().toString());
                secrets.model(model.getText().toString());
                secrets.steps(Integer.parseInt(steps.getText().toString()));
                key.setText("");
                toast("Настройки сохранены");
                tab = 0; draw();
            } catch (Exception e) { toast("Ошибка настроек: " + e.getMessage()); }
        });
        Button clearKey = button("Удалить сохранённый ключ", false);
        padded(content, clearKey, 11);
        clearKey.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Удалить API-ключ?")
                .setPositiveButton("Удалить", (d, w) -> { try { secrets.save(""); toast("Ключ удалён"); }
                    catch (Exception e) { toast(e.getMessage()); } })
                .setNegativeButton("Отмена", null).show());
        padded(content, text("🔒 Режим безопасности", 18, WHITE, true), 26);
        padded(content, text("Изменения файлов требуют отдельного подтверждения. " +
                "Агент не запускает команды Android и не имеет доступа к другим приложениям. " +
                "Каждый запрос к AI-FLEX может стоить денег. Ошибки 503/524 зависят от провайдера.",
                13, MUTED, false), 9);
        padded(content, text("KENT CODE · Android MVP 0.1 · исходники и сборка через GitHub Actions", 12, MUTED, false), 20);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        stopRequested.set(true);
        executor.shutdownNow();
        super.onDestroy();
    }
}
