package ai.kent.code;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowInsets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
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
    private static final int BG = Color.rgb(14, 5, 9);
    private static final int PANEL = Color.rgb(35, 11, 19);
    private static final int PANEL_2 = Color.rgb(63, 17, 28);
    private static final int BLUE = Color.rgb(255, 78, 103); // red-pixel accent
    private static final int WHITE = Color.rgb(255, 238, 239);
    private static final int MUTED = Color.rgb(216, 150, 159);
    private static final int GOLD = Color.rgb(255, 180, 82);
    private static final int GREEN = Color.rgb(127, 232, 162);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private SafeWorkspace workspace;
    private SecureKeyStore secrets;
    private KentAgent agent;
    private TermuxBridge bridge;
    private LocalTermuxBridge localBridge;
    private String lastBridgeResult = "Проверки ещё не запускались.";
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
            bridge = new TermuxBridge(this);
            localBridge = new LocalTermuxBridge(secrets);
            if (workspace.filePaths().isEmpty()) workspace.write("hello.py", "def greet(name):\n    return f'Привет, {name}!'\n\nprint(greet('КЕНТ'))\n");
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Ошибка хранилища")
                    .setMessage(e.getMessage()).setPositiveButton("Закрыть", (d, w) -> finish()).show();
            return;
        }
        messages.add(new ChatItem("КЕНТ", "Здарова! Встречай KENT CODE // RED PIXEL 0.3.2. " +
                "Нужен новый API-ключ AI-FLEX — добавь в CTRL. " +
                "TRUSTED MODE разрешает автоматическое редактирование файлов рабочей папки. " +
                "В разделе BRIDGE можно подключить Termux + Shizuku; root не нужен."));
        agent = new KentAgent(workspace, secrets, bridge, localBridge, new KentAgent.Events() {
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
        g.setCornerRadius(dp(3)); // angular pixel-like panels
        g.setStroke(dp(1), Color.rgb(147, 38, 61));
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
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setTextColor(WHITE);
        b.setBackground(shape(primary ? Color.rgb(184, 29, 58) : PANEL_2, 3));
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
        f.setTextSize(14);
        f.setTypeface(Typeface.MONOSPACE);
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
        // Target SDK 35+ draws behind status/navigation bars on Android 15+.
        // Apply system bar and display-cutout insets to the full app root.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
        }
        setContentView(app);
        LinearLayout head = vertical();
        head.setPadding(dp(18), dp(13), dp(18), dp(14));
        head.setBackgroundColor(Color.rgb(41, 10, 19));
        head.addView(text("█ KENT CODE // RED PIXEL 0.3.2", 20, WHITE, true));
        padded(head, text("◇ AGENT ONLINE   ◇   AI-FLEX   ◇   ANDROID", 11, MUTED, false), 3);
        app.addView(head);

        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(10), dp(8), dp(10), dp(8));
        nav.setBackgroundColor(Color.rgb(41, 10, 19));
        String[] labels = {"⌘ CHAT", "▣ FILES", "▧ BRIDGE", "⚙ CTRL"};
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
        if (tab == 2) drawBridge();
        if (tab == 3) drawSettings();
    }

    private void drawChat() {
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setPadding(dp(16), dp(12), dp(16), dp(9));
        runStatus = text(running ? "◉ [BUSY] агент работает" : (secrets.trustedMode() ? "◆ [TRUSTED] готов" : "◇ [SAFE] готов"), 12,
                running ? GOLD : (secrets.trustedMode() ? BLUE : GREEN), true);
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
        int fill = who.equals("ТЫ") ? Color.rgb(73, 18, 35) : PANEL;
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
                toast("Сначала настрой API-ключ"); tab = 3; draw(); return;
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

    /**
     * Full-screen approval sheet: long command previews scroll independently
     * and the DENY / ALLOW buttons are always pinned at the bottom.
     * Nothing can run unless the user explicitly taps ALLOW.
     */
    private boolean requestApproval(String title, String detail) {
        if (isFinishing() || stopRequested.get()) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean(false);
        final Dialog[] active = new Dialog[1];

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || stopRequested.get()) {
                latch.countDown();
                return;
            }
            // The content fills the screen, including on very small displays.
            // Only the preview scrolls; the bottom action bar never scrolls.
            LinearLayout sheet = vertical();
            sheet.setBackgroundColor(BG);
            final int inset = dp(18);
            sheet.setPadding(inset, inset, inset, inset);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sheet.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    view.setPadding(inset + bars.left, inset + bars.top,
                            inset + bars.right, inset + bars.bottom);
                    return windowInsets;
                });
            }

            TextView heading = text("⚠ ПОДТВЕРЖДЕНИЕ ДЕЙСТВИЯ", 18, WHITE, true);
            padded(sheet, heading, 0);
            padded(sheet, text(title, 15, BLUE, true), 10);
            padded(sheet, text("Прочитай полную команду ниже. Разрешение действует ТОЛЬКО один раз.", 
                    12, MUTED, false), 10);

            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(false);
            scroll.setBackground(shape(PANEL, 3));
            TextView preview = text(detail, 12, WHITE, false);
            preview.setTextIsSelectable(true);
            preview.setPadding(dp(12), dp(14), dp(12), dp(14));
            scroll.addView(preview);
            LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            content.topMargin = dp(14);
            sheet.addView(scroll, content);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button deny = button("ОТКЛОНИТЬ", false);
            Button allow = button("РАЗРЕШИТЬ", true);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(58), 1);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(58), 1);
            right.leftMargin = dp(10);
            actions.addView(deny, left);
            actions.addView(allow, right);
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionParams.topMargin = dp(12);
            sheet.addView(actions, actionParams);

            Dialog dialog = new Dialog(this);
            active[0] = dialog;
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(sheet);
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnCancelListener(d -> latch.countDown());
            dialog.setOnDismissListener(d -> latch.countDown());
            deny.setOnClickListener(v -> dialog.dismiss());
            allow.setOnClickListener(v -> {
                if (!stopRequested.get()) approved.set(true);
                dialog.dismiss();
            });
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            }
        });

        boolean answered = false;
        try {
            answered = latch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!answered) {
            // Never leave a stale dialog that can be approved after the task timed out.
            runOnUiThread(() -> {
                if (active[0] != null && active[0].isShowing()) active[0].dismiss();
            });
        }
        return answered && !stopRequested.get() && approved.get();
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


    /** Manual opt-in for third-party Termux command bridge. */
    private void drawBridge() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(content);
        mainArea.addView(scroll);

        padded(content, text("▧ KENT / TERMUX BRIDGE", 20, WHITE, true), 3);
        padded(content, text("Termux → Python, Git, Linux. Shizuku/rish → adb shell (uid=2000, НЕ root). " +
                "Для Google Play Termux используем локальный защищённый мост. " +
                "BRIDGE выключен по умолчанию; TRUSTED MODE не отменяет подтверждение команд.", 13, MUTED, false), 9);

        padded(content, text(bridge.hasPermission()
                ? "● Разрешение RUN_COMMAND: выдано"
                : "○ Разрешение RUN_COMMAND: не выдано", 13,
                bridge.hasPermission() ? GREEN : GOLD, true), 20);

        Button permission = button("1. Выдать KENT разрешение Termux", true);
        padded(content, permission, 10);
        permission.setOnClickListener(v -> {
            if (bridge.hasPermission()) { toast("Разрешение уже выдано"); return; }
            requestPermissions(new String[]{TermuxBridge.PERMISSION}, 403);
        });
        padded(content, text("Если Android не покажет запрос: Настройки → Приложения → KENT CODE RED → " +
                "Разрешения → Дополнительные разрешения → Run commands in Termux environment.", 12, MUTED, false), 7);

        padded(content, text("2. В Termux разреши внешние команды:", 13, WHITE, true), 18);
        padded(content, text("mkdir -p ~/.termux\n" +
                "printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties\n" +
                "termux-reload-settings", 12, GREEN, false), 7);
        padded(content, text("Используется официальный RUN_COMMAND Intent (Termux >= 0.109). " +
                "Shizuku должен быть запущен, а ~/rish и ~/rish_shizuku.dex уже настроены. " +
                "Если Android блокирует запуск, открой Termux на экране и разреши фоновую работу.", 12, MUTED, false), 10);

        padded(content, text("▣ Google Play Termux — без RUN_COMMAND", 15, WHITE, true), 20);
        padded(content, text("Твоя сборка Termux из Google Play не регистрирует разрешение RUN_COMMAND. " +
                "Вместо него запусти в Termux локальный сервер вручную:", 12, MUTED, false), 7);
        padded(content, text("cd ~/KENT-CODE-Android\n" +
                "git pull --ff-only\n" +
                "pkg install python -y\n" +
                "python tools/kent_bridge_server.py", 12, GREEN, false), 10);
        padded(content, text("Оставь Termux с запущенным сервером. Он слушает ТОЛЬКО 127.0.0.1:47883 " +
                "и выдаёт случайный токен. Не отправляй токен в чат, в GitHub или третьим лицам. " +
                "Вставь токен ниже (хранится зашифрованно в Android Keystore).", 12, MUTED, false), 10);
        padded(content, text(localBridge.configured() ?
                "● Локальный токен сохранён" : "○ Локальный токен не задан",
                13, localBridge.configured() ? GREEN : GOLD, true), 9);
        EditText tokenInput = field("Вставь 48 символов токена", "");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        padded(content, tokenInput, 10);
        Button saveToken = button("Сохранить токен локального моста", false);
        padded(content, saveToken, 8);
        saveToken.setOnClickListener(v -> {
            try {
                secrets.saveBridgeToken(tokenInput.getText().toString().trim());
                tokenInput.setText("");
                toast("Токен сохранён локально");
                draw();
            } catch (Exception e) {
                toast("Ошибка токена: " + e.getMessage());
            }
        });

        Button toggle = button(secrets.bridgeEnabled() ? "3. ◉ ОТКЛЮЧИТЬ BRIDGE" : "3. ○ ВКЛЮЧИТЬ BRIDGE", true);
        padded(content, toggle, 16);
        toggle.setOnClickListener(v -> {
            if (secrets.bridgeEnabled()) {
                secrets.bridgeEnabled(false);
                draw();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Включить KENT BRIDGE?")
                    .setMessage("КЕНТ сможет ПРЕДЛАГАТЬ команды для Termux и Shizuku. " +
                            "Для КАЖДОЙ команды потребуется твоё отдельное подтверждение — даже в TRUSTED MODE. " +
                            "Команды могут изменять систему, а их вывод отправляется AI-FLEX. Не одобряй команды с доступом к секретам.")
                    .setPositiveButton("ВКЛЮЧИТЬ", (d, w) -> { secrets.bridgeEnabled(true); draw(); })
                    .setNegativeButton("Отмена", null).show();
        });

        Button testTermux = button("▶ Тест Termux: id", false);
        padded(content, testTermux, 14);
        testTermux.setOnClickListener(v -> testBridge(false));
        Button testAdb = button("▶ Тест Shizuku: id", false);
        padded(content, testAdb, 9);
        testAdb.setOnClickListener(v -> testBridge(true));
        Button stopBridge = button("■ Остановить ожидание", false);
        padded(content, stopBridge, 9);
        stopBridge.setOnClickListener(v -> {
            stopRequested.set(true);
            toast("Ожидание отменяется; выполняемая команда в Termux может продолжить работу.");
        });
        padded(content, text("Последний результат:", 13, MUTED, true), 16);
        padded(content, text(lastBridgeResult, 12, WHITE, false), 7);
        padded(content, text("При зависании кнопка «Стоп» прекращает ожидание, но уже запущенный " +
                "процесс в Termux может продолжить работу. Не запускай долгие процессы, пока не проверим связь.", 12, MUTED, false), 15);
    }

    private void testBridge(boolean adb) {
        if (running) { toast("Сначала заверши текущую задачу"); return; }
        if (!secrets.bridgeEnabled()) { toast("Сначала включи BRIDGE"); return; }
        if (!bridge.hasPermission() && !localBridge.configured()) { toast("Нужен локальный токен или RUN_COMMAND"); return; }
        running = true;
        stopRequested.set(false);
        lastBridgeResult = adb ? "Проверяем Shizuku…" : "Проверяем Termux…";
        if (tab == 2) draw();
        executor.submit(() -> {
            try {
                String result;
                if (localBridge.configured()) {
                    result = localBridge.run("id", adb, 30, stopRequested::get);
                } else {
                    result = adb ? bridge.runAdb("id", 30, stopRequested::get)
                                 : bridge.runTermux("id", 30, stopRequested::get);
                }
                lastBridgeResult = result;
            } catch (Exception e) {
                lastBridgeResult = "Ошибка: " + e.getMessage();
            } finally {
                running = false;
                runOnUiThread(() -> { if (tab == 2) draw(); });
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 403) {
            toast(bridge.hasPermission() ? "Разрешение Termux получено" :
                    "Не разрешено. Проверь дополнительные разрешения KENT CODE RED в настройках Android.");
            if (tab == 2) draw();
        }
    }

    private void drawSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(20), dp(18), dp(18));
        scroll.addView(content);
        mainArea.addView(scroll);
        padded(content, text("⚙ CONTROL PANEL", 22, WHITE, true), 2);
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
        padded(content, text("🔄 Максимум API-шагов (1–500)", 13, MUTED, true), 18);
        EditText steps = field("40", String.valueOf(secrets.steps()));
        steps.setInputType(InputType.TYPE_CLASS_NUMBER);
        padded(content, steps, 7);
        padded(content, text("☢ TRUSTED MODE", 14, BLUE, true), 20);
        CheckBox trusted = new CheckBox(this);
        trusted.setText("Автоизменение и удаление файлов БЕЗ подтверждений — только в рабочей папке");
        trusted.setTextColor(WHITE);
        trusted.setTypeface(Typeface.MONOSPACE);
        trusted.setChecked(secrets.trustedMode());
        trusted.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
        padded(content, trusted, 8);
        padded(content, text("По умолчанию выключен. Внутренние файлы приложения и секреты остаются закрытыми. Все shell-команды требуют отдельного согласия.", 12, MUTED, false), 8);
        Button save = button("Сохранить настройки", true);
        padded(content, save, 20);
        save.setOnClickListener(v -> {
            if (running) { toast("Дождись завершения задачи"); return; }
            try {
                if (model.getText().toString().trim().isEmpty()) throw new IOException("Укажи модель");
                if (!key.getText().toString().trim().isEmpty()) secrets.save(key.getText().toString());
                secrets.model(model.getText().toString());
                secrets.steps(Integer.parseInt(steps.getText().toString()));
                boolean requested = trusted.isChecked();
                if (requested && !secrets.trustedMode()) {
                    new AlertDialog.Builder(this)
                            .setTitle("☢ Включить TRUSTED MODE?")
                            .setMessage("Агент сможет самостоятельно изменять и удалять файлы внутри рабочей папки без вопросов. Это НЕ даёт root и не разрешает запуск команд без согласия. Включить?")
                            .setPositiveButton("ВКЛЮЧИТЬ", (d, w) -> {
                                secrets.trustedMode(true);
                                key.setText("");
                                toast("TRUSTED MODE активирован"); tab = 0; draw();
                            })
                            .setNegativeButton("Отмена", null).show();
                } else {
                    secrets.trustedMode(requested);
                    key.setText("");
                    toast("Настройки сохранены"); tab = 0; draw();
                }
            } catch (Exception e) { toast("Ошибка настроек: " + e.getMessage()); }
        });
        Button clearKey = button("Удалить сохранённый ключ", false);
        padded(content, clearKey, 11);
        clearKey.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Удалить API-ключ?")
                .setPositiveButton("Удалить", (d, w) -> { try { secrets.save(""); toast("Ключ удалён"); }
                    catch (Exception e) { toast(e.getMessage()); } })
                .setNegativeButton("Отмена", null).show());
        padded(content, text("▣ RUNNER / PERMISSIONS", 17, WHITE, true), 25);
        padded(content, text("Обычный run_command работает с правами KENT CODE, НЕ root. В разделе BRIDGE можно отдельно включить Termux и Shizuku. Там КАЖДАЯ команда требует подтверждения. Ответы инструментов отправляются AI-FLEX; не давай доступ к секретам. API-запросы могут стоить денег.", 13, MUTED, false), 9);
        padded(content, text("KENT CODE · Android 0.3.2 RED PIXEL · GitHub Actions", 12, MUTED, false), 20);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        stopRequested.set(true);
        executor.shutdownNow();
        super.onDestroy();
    }
}
