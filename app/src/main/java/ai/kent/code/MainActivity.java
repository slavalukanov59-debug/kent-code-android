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
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
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
    private ChatJournal journal;
    private TokenMeter tokenMeter;
    private MissionStore missionStore;
    private String pendingMissionExportId = "";
    private static final int REQ_MISSION_EXPORT = 743;
    private TextView usageTitle;
    private TextView usageNumbers;
    private TextView usageBreakdown;
    private ProgressBar usageBar;
    private static final int REQ_JOURNAL_EXPORT = 742;
    private static final int RECENT_CHAT_LIMIT = 120;
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
            journal = new ChatJournal(this);
            tokenMeter = new TokenMeter(this);
            tokenMeter.recoverInterrupted();
            missionStore = new MissionStore(this);
            missionStore.recoverInterrupted();
            bridge = new TermuxBridge(this);
            localBridge = new LocalTermuxBridge(secrets);
            if (workspace.filePaths().isEmpty()) workspace.write("hello.py", "def greet(name):\n    return f'Привет, {name}!'\n\nprint(greet('КЕНТ'))\n");
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Ошибка хранилища")
                    .setMessage(e.getMessage()).setPositiveButton("Закрыть", (d, w) -> finish()).show();
            return;
        }
        for (ChatJournal.Record record : journal.recent(RECENT_CHAT_LIMIT)) {
            messages.add(new ChatItem(record.title, record.text));
        }
        if (messages.isEmpty()) {
            addMessage("КЕНТ", "KENT CODE 0.5.0. Журнал и MISSION CONTROL сохраняются на устройстве. " +
                    "BRIDGE и модель выбираются в настройках.");
        } else {
            addMessage("СЕССИЯ", "История чата восстановлена из локального журнала. " +
                    "После перезапуска модель НЕ помнит прежние обращения: чтобы продолжить работу, " +
                    "попроси её проверить git status / PROGRESS.md. Файлы проекта не очищались.");
        }
        agent = new KentAgent(workspace, secrets, bridge, localBridge, tokenMeter, missionStore, new KentAgent.Events() {
            @Override public void log(String title, String text) { runOnUiThread(() -> addMessage(title, text)); }
            @Override public boolean approve(String title, String detail) {
                if (stopRequested.get()) return false;
                if (secrets.autopilotEnabled()) {
                    // AUTO is explicitly enabled by the owner in CTRL. Show the tool
                    // request in chat without interrupting the agent with a dialog.
                    String shown = detail.length() > 700 ? detail.substring(0, 700) +
                            "\n[Команда/превью сокращены — весь запрос выполнен]" : detail;
                    runOnUiThread(() -> addMessage("⚡ AUTO", title + "\n" + shown));
                    return !stopRequested.get();
                }
                return requestApproval(title, detail);
            }
            @Override public boolean cancelled() { return stopRequested.get(); }
            @Override public void usage(TokenMeter.Snapshot snapshot) {
                runOnUiThread(() -> refreshTokenMeter(snapshot));
            }
            @Override public void missionChanged() {
                runOnUiThread(() -> {
                    if (tab == 4 && !isFinishing() && !isDestroyed()) draw();
                });
            }
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
        head.addView(text("█ KENT CODE // RED PIXEL 0.5.1", 20, WHITE, true));
        padded(head, text("◇ AGENT ONLINE   ◇   " + (secrets.provider().equals("openrouter") ? "OPENROUTER" : "AI-FLEX") + "   ◇   ANDROID", 11, MUTED, false), 3);
        if (secrets.autopilotEnabled()) padded(head,
                text("⚡ AUTOPILOT · БЕЗ ПОДТВЕРЖДЕНИЙ · ЖМИ СТОП ДЛЯ ОТМЕНЫ", 11, GOLD, true), 7);
        app.addView(head);

        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(10), dp(8), dp(10), dp(8));
        nav.setBackgroundColor(Color.rgb(41, 10, 19));
        String[] labels = {"⌘ CHAT", "▣ FILES", "▧ BRIDGE", "⚙ CTRL", "▤ JOBS"};
        for (int i = 0; i < labels.length; i++) {
            final int selected = i;
            Button b = button(labels[i], i == tab);
            b.setTextSize(11);
            b.setMinimumWidth(0);
            b.setMinWidth(0);
            b.setPadding(dp(3), dp(5), dp(3), dp(5));
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
        if (tab == 4) drawMissions();
    }

    private void drawChat() {
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setPadding(dp(16), dp(12), dp(16), dp(9));
        runStatus = text(running ? "◉ [BUSY] агент работает" : (secrets.autopilotEnabled() ?
                "⚡ [AUTOPILOT] готов" : (secrets.trustedMode() ? "◆ [TRUSTED] готов" : "◇ [SAFE] готов")), 12,
                running ? GOLD : (secrets.autopilotEnabled() ? GOLD : (secrets.trustedMode() ? BLUE : GREEN)), true);
        LinearLayout.LayoutParams statusWeight = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        statusRow.addView(runStatus, statusWeight);
        Button cancel = button("■ Стоп", false);
        cancel.setOnClickListener(v -> { stopRequested.set(true); toast("Остановка после текущего API-запроса"); });
        statusRow.addView(cancel, new LinearLayout.LayoutParams(dp(98), dp(45)));
        mainArea.addView(statusRow);

        // Task budget meter survives screen redraws and Android process restarts.
        // It tracks our configurable per-task cap, never claims AI-FLEX wallet balance.
        LinearLayout usagePanel = vertical();
        usagePanel.setPadding(dp(14), dp(10), dp(14), dp(11));
        usagePanel.setBackground(shape(PANEL, 3));
        usageTitle = text("◈ ТОКЕНЫ ЗАДАЧИ", 13, GOLD, true);
        usagePanel.addView(usageTitle);
        usageNumbers = text("", 13, WHITE, true);
        padded(usagePanel, usageNumbers, 5);
        usageBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        usageBar.setMax(1000);
        usageBar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        usageBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL_2));
        padded(usagePanel, usageBar, 6);
        usageBreakdown = text("", 11, MUTED, false);
        padded(usagePanel, usageBreakdown, 5);
        padded(usagePanel, text("Остаток — лимит задачи из CTRL, НЕ деньги на счёте AI-FLEX. " +
                "Оценка может отличаться от фактического расхода API.", 10, MUTED, false), 5);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.setMargins(dp(12), dp(3), dp(12), dp(8));
        mainArea.addView(usagePanel, panelParams);
        refreshTokenMeter(tokenMeter.snapshot());

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
            new AlertDialog.Builder(this)
                    .setTitle("Удалить историю диалога?")
                    .setMessage("Локальный журнал также будет удалён. Сначала экспортируй его, " +
                            "если хочешь сохранить отчёты. Код проекта не изменится.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Очистить", (d, w) -> {
                        try {
                            journal.clear();
                            agent.clear();
                            messages.clear();
                            chatFeed.removeAllViews();
                            addMessage("КЕНТ", "Контекст и журнал очищены. Файлы проекта не затронуты.");
                        } catch (IOException e) { toast("Ошибка журнала: " + e.getMessage()); }
                    }).show();
        });
        actions.addView(clear, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button export = button("⇩ Журнал", false);
        LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        exportLp.leftMargin = dp(8);
        actions.addView(export, exportLp);
        export.setOnClickListener(v -> exportJournal());
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

    private static String tokens(long count) {
        return String.format(java.util.Locale.US, "%,d", count).replace(',', ' ');
    }

    private void refreshTokenMeter(TokenMeter.Snapshot snapshot) {
        if (snapshot == null || usageTitle == null || usageNumbers == null
                || usageBar == null || usageBreakdown == null || tab != 0) return;
        String source = "openrouter".equals(snapshot.provider) ? "OPENROUTER" : "AI-FLEX";
        usageTitle.setText("◈ ТОКЕНЫ ЗАДАЧИ · " + source + " · " + snapshot.status);
        String prefix = snapshot.approximate() ? "≈" : "";
        usageNumbers.setText("ИСПОЛЬЗОВАНО " + prefix + tokens(snapshot.used) +
                " / " + tokens(snapshot.budget) +
                "     ОСТАЛОСЬ " + prefix + tokens(snapshot.remaining()));
        usageBar.setProgress(snapshot.permille());
        usageBreakdown.setText("ВХОД: " + tokens(snapshot.input) +
                "   ВЫХОД: " + tokens(snapshot.output) +
                "   API: " + snapshot.calls +
                "\nВСЕГО ЧЕРЕЗ KENT (" + source + "): " + prefix + tokens(snapshot.cumulative) +
                (snapshot.approximate() ? "\n⚠ Часть API не вернула usage: применена оценка." : "") +
                "\nРазбивка вход/выход может быть приблизительной.");
    }

    private void addMessage(String who, String message) {
        if (journal != null) {
            try { journal.append(who, message); }
            catch (IOException e) { android.util.Log.w("KENT", "Журнал недоступен", e); }
        }
        String display = message.length() > 9000
                ? message.substring(0, 9000) + "\n[На экране сокращено; полный текст — в журнале]"
                : message;
        messages.add(new ChatItem(who, display));
        while (messages.size() > RECENT_CHAT_LIMIT) messages.remove(0);
        if (tab == 0 && chatFeed != null) {
            appendBubble(who, display);
            while (chatFeed.getChildCount() > RECENT_CHAT_LIMIT) chatFeed.removeViewAt(0);
            if (chatScroll != null) chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void exportJournal() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "KENT-chat-journal.jsonl");
        startActivityForResult(i, REQ_JOURNAL_EXPORT);
    }

    private void saveJournalTo(Uri target) {
        executor.submit(() -> {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                if (out == null) throw new IOException("Не удалось открыть файл экспорта");
                journal.export(out);
                runOnUiThread(() -> toast("Журнал экспортирован. Не публикуй секреты из чата."));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Не удалось экспортировать: " + e.getMessage()));
            }
        });
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
            SecureKeyStore.validateApiKey(secrets.loadActiveKey());
        } catch (Exception e) {
            toast("Неверный API-ключ: вставь только ключ в CTRL, задачу — в CHAT");
            tab = 3; draw(); return;
        }
        try {
            missionStore.begin(prompt, secrets.provider(), secrets.model());
        } catch (Exception e) {
            toast("Не удалось создать контрольную точку: " + e.getMessage());
            return;
        }
        composer.setText("");
        launchMission(prompt, false);
    }

    private void launchMission(String prompt, boolean resumed) {
        if (running) return;
        if (resumed) agent.clear(); // Process memory isn't a reliable resume source.
        addMessage(resumed ? "ТЫ · ПРОДОЛЖЕНИЕ" : "ТЫ", prompt);
        running = true;
        stopRequested.set(false);
        if (runStatus != null) runStatus.setText("🟡 Агент работает…");
        refreshTokenMeter(tokenMeter.snapshot());
        executor.submit(() -> {
            try { agent.task(prompt); }
            finally {
                running = false;
                runOnUiThread(() -> {
                    if (tab == 0 && runStatus != null) runStatus.setText("🟢 Готов к задачам");
                    refreshTokenMeter(tokenMeter.snapshot());
                    if (tab == 4 && !isFinishing() && !isDestroyed()) draw();
                });
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
        if (requestCode == REQ_JOURNAL_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                saveJournalTo(data.getData());
            return;
        }
        if (requestCode == REQ_MISSION_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                saveMissionReport(data.getData());
            return;
        }
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
                "BRIDGE выключен по умолчанию; в CTRL можно включить AUTOPILOT, чтобы не подтверждать каждую команду.", 13, MUTED, false), 9);

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
                            "По умолчанию каждая команда требует подтверждения. AUTOPILOT в CTRL отключает эти окна. " +
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

    private void drawMissions() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(dp(16), dp(17), dp(16), dp(27));
        scroll.addView(content);
        mainArea.addView(scroll);

        padded(content, text("▤ MISSION CONTROL // 0.5", 20, WHITE, true), 3);
        padded(content, text("Контроль задач, сохранение этапов и продолжение после сбоя. " +
                "Показанный прогресс — отметки ИИ, а не доказательство успешных тестов.", 12, MUTED, false), 8);
        MissionStore.Mission mission = missionStore.selected();
        if (mission == null) {
            padded(content, text("ЗАДАЧ ПОКА НЕТ", 17, GOLD, true), 23);
            padded(content, text("Отправь задачу во вкладке CHAT. Она автоматически попадёт " +
                    "сюда, а её состояние сохранится на устройстве.", 13, WHITE, false), 8);
        } else {
            LinearLayout card = vertical();
            card.setPadding(dp(13), dp(12), dp(13), dp(14));
            card.setBackground(shape(PANEL, 4));
            padded(card, text("● МИССИЯ " + mission.shortId() + " · " + mission.status,
                    14, GOLD, true), 0);
            String goalPreview = mission.goal.length() > 600
                    ? mission.goal.substring(0, 600) + "\n… полный текст в экспорте"
                    : mission.goal;
            padded(card, text(goalPreview, 13, WHITE, true), 10);
            padded(card, text("ЭТАП: " + mission.stage, 13, BLUE, true), 13);
            String progress = mission.total > 0
                    ? mission.completed + "/" + mission.total + " этапов по отметкам ИИ"
                    : "Этапы ещё не определены";
            padded(card, text(progress, 12, MUTED, false), 6);
            ProgressBar bar = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
            bar.setProgress(mission.total > 0 ? mission.completed * 1000 / mission.total : 0);
            padded(card, bar, 7);
            padded(card, text("ПОСЛЕДНЯЯ КОНТРОЛЬНАЯ ТОЧКА", 12, GOLD, true), 14);
            String summaryPreview = mission.summary.length() > 700
                    ? mission.summary.substring(0, 700) + "\n… полный текст в отчёте"
                    : mission.summary;
            padded(card, text(summaryPreview.isEmpty() ?
                    "ИИ ещё не сохранял контрольную точку." : summaryPreview,
                    12, WHITE, false), 6);
            padded(card, text("ДАЛЬШЕ:", 12, GOLD, true), 13);
            padded(card, text(mission.next.length() > 460 ?
                    mission.next.substring(0, 460) + "…" : mission.next,
                    12, WHITE, false), 5);
            padded(card, text("Модель: " + mission.model + "\n" +
                    "API: " + mission.calls + " · Инструменты: " + mission.tools +
                    " · Сессии: " + mission.attempts + "\n" +
                    "Токены за всю миссию: ≈" + tokens(mission.tokens) + "\n" +
                    "Последний инструмент: " + mission.lastTool + "\n" +
                    "Обновлено: " + java.text.DateFormat.getDateTimeInstance().format(
                            new Date(mission.updated)), 12, MUTED, false), 15);
            padded(content, card, 16);

            Button resume = button("▶ ПРОДОЛЖИТЬ МИССИЮ", true);
            padded(content, resume, 12);
            resume.setEnabled(!running && !"выполняется".equals(mission.status));
            resume.setOnClickListener(v -> confirmResume(mission));
            Button stop = button("■ ОСТАНОВИТЬ АКТИВНУЮ ЗАДАЧУ", false);
            padded(content, stop, 9);
            stop.setEnabled(running);
            stop.setOnClickListener(v -> {
                stopRequested.set(true);
                toast("Запрошена остановка; уже запущенная команда может продолжиться");
            });
            Button report = button("⇩ ЭКСПОРТ ОТЧЁТА МИССИИ", false);
            padded(content, report, 9);
            padded(content, text("Экспорт может содержать текст приватного задания — " +
                    "сохраняй отчёт только в доверенном месте.", 11, MUTED, false), 4);
            report.setOnClickListener(v -> {
                pendingMissionExportId = mission.id;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TITLE, "KENT-mission-" + mission.shortId() + ".txt");
                startActivityForResult(i, REQ_MISSION_EXPORT);
            });
        }

        Button chat = button("⌘ ПЕРЕЙТИ В ЧАТ", false);
        padded(content, chat, 13);
        chat.setOnClickListener(v -> { tab = 0; draw(); });

        padded(content, text("ИСТОРИЯ МИССИЙ (максимум 25)", 14, WHITE, true), 25);
        for (MissionStore.Mission item : missionStore.list()) {
            String heading = (mission != null && mission.id.equals(item.id) ? "◆ " : "◇ ") +
                    item.shortId() + " · " + item.status + " · " +
                    (item.goal.length() > 54 ? item.goal.substring(0, 54) + "…" : item.goal);
            Button choose = button(heading, mission != null && mission.id.equals(item.id));
            choose.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            padded(content, choose, 9);
            choose.setOnClickListener(v -> {
                missionStore.select(item.id);
                draw();
            });
        }
        padded(content, text("После вылета задача не запускается сама. " +
                "Нажми «Продолжить»: Кент перечитает фактическое состояние файлов и Git. " +
                "Системные процессы Termux могут продолжаться отдельно.", 12, MUTED, false), 16);
    }

    private void confirmResume(MissionStore.Mission chosen) {
        if (running) { toast("Сначала останови выполняющуюся задачу"); return; }
        new AlertDialog.Builder(this)
                .setTitle("Продолжить миссию " + chosen.shortId() + "?")
                .setMessage("Модельный контекст предыдущего запуска утрачен. " +
                        "Кент должен сверить реальное состояние файлов и Git перед изменениями. " +
                        "Это новый платный запрос к " + secrets.provider() + " с отдельным лимитом токенов. " +
                        (secrets.autopilotEnabled() ?
                                "⚠ AUTOPILOT ВКЛЮЧЁН: следующие команды могут исполняться без дополнительных подтверждений. " : "") +
                        "Не запускай, если предыдущие процессы Termux ещё работают.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("ПРОДОЛЖИТЬ", (d, w) -> {
                    if (running) return;
                    try {
                        if (secrets.loadActiveKey().isBlank()) {
                            toast("Сначала сохрани ключ в CTRL"); return;
                        }
                        MissionStore.Mission active = missionStore.resume(chosen.id, secrets.provider(), secrets.model());
                        String prompt = "ПРОДОЛЖЕНИЕ МИССИИ " + active.shortId() + ". " +
                                "Исходная задача:\n" + active.goal + "\n\n" +
                                "Последний подтверждённый ИИ этап: " + active.stage +
                                " (" + active.completed + "/" + active.total + ").\n" +
                                "Контрольная точка:\n" + active.summary +
                                "\nСледующее действие:\n" + active.next + "\n\n" +
                                "Предыдущий модельный контекст потерян. СНАЧАЛА проверь фактические файлы, " +
                                "Git status, существующие тесты и PROGRESS.md через доступные инструменты. " +
                                "НЕ повторяй команды установки, удаления, отправки данных или Git push " +
                                "без проверки состояния и отдельного согласования. " +
                                "После проверки обнови mission_checkpoint. " +
                                "Продолжай только с минимально необходимого следующего шага.";
                        launchMission(prompt, true);
                        tab = 0; draw();
                    } catch (Exception e) {
                        toast("Не удалось продолжить: " + e.getMessage());
                    }
                }).show();
    }

    private void saveMissionReport(Uri target) {
        MissionStore.Mission selected = null;
        for (MissionStore.Mission m : missionStore.list()) {
            if (m.id.equals(pendingMissionExportId)) { selected = m; break; }
        }
        if (selected == null) { toast("Миссия не найдена"); return; }
        final MissionStore.Mission mission = selected;
        executor.submit(() -> {
            String report = "KENT MISSION REPORT\nID: " + mission.id +
                    "\nSTATUS: " + mission.status +
                    "\nPROVIDER/MODEL: " + mission.provider + " / " + mission.model +
                    "\nGOAL:\n" + mission.goal +
                    "\nSTAGE: " + mission.stage + " (" + mission.completed +
                    "/" + mission.total + ")\nSUMMARY (AI-reported):\n" + mission.summary +
                    "\nNEXT:\n" + mission.next +
                    "\nAPI REQUESTS: " + mission.calls +
                    "\nTOOLS: " + mission.tools +
                    "\nSESSIONS: " + mission.attempts +
                    "\nAPPROX TOKENS ACROSS SESSIONS: " + mission.tokens +
                    "\nLAST TOOL: " + mission.lastTool +
                    "\n\nWARNING: this is an AI-reported checkpoint, not proof of tests passing.\n";
            try (java.io.OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                if (out == null) throw new IOException("Ошибка открытия документа");
                out.write(report.getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> toast("Отчёт миссии сохранён"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Не удалось экспортировать миссию: " + e.getMessage()));
            }
        });
    }

    private void drawSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(20), dp(18), dp(24));
        scroll.addView(content);
        mainArea.addView(scroll);

        padded(content, text("⚙ CONTROL PANEL", 22, WHITE, true), 2);
        padded(content, text("🌐 ИИ-провайдер", 14, BLUE, true), 18);
        Spinner providers = new Spinner(this);
        String[] names = {"OpenRouter — Claude / GPT / другие", "AI-FLEX — прежний провайдер"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, names);
        providers.setAdapter(adapter);
        providers.setSelection(secrets.provider().equals("openrouter") ? 0 : 1);
        padded(content, providers, 8);
        padded(content, text("OpenRouter позволяет выбирать разные модели через один API-ключ. " +
                "Расходы и ограничения зависят от модели и баланса провайдера. " +
                "AI-FLEX сохранён для обратной совместимости.", 12, MUTED, false), 8);

        padded(content, text("🔑 API-ключ ВЫБРАННОГО провайдера (пусто = оставить)", 13, MUTED, true), 18);
        EditText key = field("Только API-ключ, НЕ текст задачи", "");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        padded(content, key, 6);
        padded(content, text("СЮДА — только секретный ключ AI-FLEX/OpenRouter. " +
                "Большие задания отправляй через CHAT. " +
                "Неверный ключ нельзя сохранить — не будет ошибок Bearer/HTTP.", 12, GOLD, false), 6);
        padded(content, text("API-ключи хранятся раздельно и зашифрованно (Android Keystore). " +
                "Никогда не вставляй ключ в чат и не публикуй его в GitHub.", 12, MUTED, false), 6);

        padded(content, text("🧠 Модель (точный ID)", 13, MUTED, true), 17);
        EditText model = field("ID модели", secrets.model());
        padded(content, model, 6);

        // Safe salvage of the agent's 0.5 patch: presets update only the
        // visible model ID; they are persisted with the regular Save button.
        // No extra flag or replacement of existing provider credentials.
        LinearLayout presetPanel = vertical();
        padded(presetPanel, text("⚡ Быстрый выбор модели AI-FLEX", 13, BLUE, true), 4);
        LinearLayout presets = new LinearLayout(this);
        Button fastPreset = button("⚡ FAST", false);
        Button codePreset = button("💻 CODE", false);
        Button maxPreset = button("🔥 MAX", false);
        LinearLayout.LayoutParams presetFirst = new LinearLayout.LayoutParams(0, dp(48), 1);
        LinearLayout.LayoutParams presetMiddle = new LinearLayout.LayoutParams(0, dp(48), 1);
        LinearLayout.LayoutParams presetLast = new LinearLayout.LayoutParams(0, dp(48), 1);
        presetMiddle.leftMargin = dp(7);
        presetLast.leftMargin = dp(7);
        presets.addView(fastPreset, presetFirst);
        presets.addView(codePreset, presetMiddle);
        presets.addView(maxPreset, presetLast);
        padded(presetPanel, presets, 7);
        padded(presetPanel, text("FAST: deepseek-v4.1-flash-1017\n" +
                "CODE: claude-sonnet-5-5\n" +
                "MAX: claude-opus-5-5", 12, MUTED, false), 7);
        padded(presetPanel, text("Нажатие только подставляет ID в поле выше. " +
                "Чтобы применить — нажми «Сохранить настройки». " +
                "MAX обычно существенно дороже; стоимость и доступность проверяй в AI-FLEX.", 12, MUTED, false), 6);
        padded(content, presetPanel, 10);
        fastPreset.setOnClickListener(v -> model.setText("deepseek-v4.1-flash-1017"));
        codePreset.setOnClickListener(v -> model.setText("claude-sonnet-5-5"));
        maxPreset.setOnClickListener(v -> model.setText("claude-opus-5-5"));
        presetPanel.setVisibility(secrets.provider().equals("ai_flex") ?
                View.VISIBLE : View.GONE);

        TextView hint = text("OpenRouter: anthropic/claude-sonnet-5.5, " +
                "anthropic/claude-opus-5.5, openai/gpt-6-luna. " +
                "AI-FLEX: укажи ID из каталога провайдера.", 12, MUTED, false);
        padded(content, hint, 5);

        providers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String selected = pos == 0 ? "openrouter" : "ai_flex";
                model.setText(secrets.modelFor(selected));
                presetPanel.setVisibility(selected.equals("ai_flex") ?
                        View.VISIBLE : View.GONE);
                key.setText("");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        padded(content, text("📦 Токенов на один ответ (512–8192)", 13, MUTED, true), 17);
        EditText outputTokens = field("4096", String.valueOf(secrets.responseTokens()));
        outputTokens.setInputType(InputType.TYPE_CLASS_NUMBER);
        padded(content, outputTokens, 6);
        padded(content, text("Увеличение лимита может повысить стоимость ответа. " +
                "Это не отменяет ограничений модели и API.", 12, MUTED, false), 5);

        padded(content, text("💰 Лимит токенов на задачу (10 000–300 000)", 13, GOLD, true), 17);
        EditText tokenBudget = field("80000", String.valueOf(secrets.taskTokenBudget()));
        tokenBudget.setInputType(InputType.TYPE_CLASS_NUMBER);
        padded(content, tokenBudget, 6);
        padded(content, text("Защита от повторной отправки длинного контекста. " +
                "Учитывается usage из API, либо приблизительная оценка. " +
                "Проверяй реальный расход в AI-FLEX: лимит приблизительный.", 12, MUTED, false), 5);
        padded(content, text("В CHAT видны потраченные, оставшиеся токены и шкала текущей задачи. " +
                "Счётчик ведётся локально и сохраняется после перезапуска. " +
                "Остаток НЕ показывает баланс AI-FLEX или свободный контекст модели.", 12, MUTED, false), 6);

        padded(content, text("🔄 Максимум API-шагов (1–500)", 13, MUTED, true), 17);
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
        padded(content, text("TRUSTED MODE затрагивает только файловые операции. " +
                "Для отключения ВСЕХ запросов на подтверждение инструментов включи AUTOPILOT ниже.", 12, MUTED, false), 8);

        padded(content, text("⚡ AUTOPILOT — БЕЗ ОКОН ПОДТВЕРЖДЕНИЯ", 15, GOLD, true), 23);
        CheckBox autopilot = new CheckBox(this);
        autopilot.setText("Автоматически разрешать ВСЕ инструменты агента (Termux / Shizuku / файлы / shell)");
        autopilot.setTextColor(WHITE);
        autopilot.setTypeface(Typeface.MONOSPACE);
        autopilot.setButtonTintList(android.content.res.ColorStateList.valueOf(GOLD));
        autopilot.setChecked(secrets.autopilotEnabled());
        padded(content, autopilot, 10);
        padded(content, text("⚠ ВНИМАНИЕ: при включении КЕНТ сможет без вопросов запускать команды, " +
                "удалять или менять файлы, управлять доступными функциями Android и запускать Git. " +
                "Команды выполняются в пределах прав Termux / Shizuku, а НЕ root. " +
                "Не отправляй агенту секреты; результаты команд могут уходить ИИ-провайдеру. " +
                "Все действия отображаются в чате. Кнопка «Стоп» отменяет ожидание, " +
                "но уже запущенную команду может не остановить. " +
                "Режим сохраняется после перезапуска, пока не выключишь его здесь.", 12, GOLD, false), 9);

        Button save = button("Сохранить настройки", true);
        padded(content, save, 20);
        save.setOnClickListener(v -> {
            if (running) { toast("Дождись завершения задачи"); return; }
            try {
                String selected = providers.getSelectedItemPosition() == 0 ? "openrouter" : "ai_flex";
                String newModel = model.getText().toString().trim();
                if (newModel.isEmpty()) throw new IOException("Укажи модель");
                int maxTokens = Integer.parseInt(outputTokens.getText().toString().trim());
                if (maxTokens < 512 || maxTokens > 8192)
                    throw new IOException("Токенов должно быть от 512 до 8192");
                int requestedBudget = Integer.parseInt(tokenBudget.getText().toString().trim());
                if (requestedBudget < 10_000 || requestedBudget > 300_000)
                    throw new IOException("Лимит токенов должен быть 10000–300000");
                int maxSteps = Integer.parseInt(steps.getText().toString().trim());
                if (maxSteps < 1 || maxSteps > 500)
                    throw new IOException("Число API-шагов должно быть 1–500");
                if (!key.getText().toString().trim().isEmpty())
                    secrets.saveProviderKey(selected, key.getText().toString());
                boolean changed = !selected.equals(secrets.provider());
                secrets.provider(selected);
                secrets.model(newModel);
                secrets.steps(maxSteps);
                secrets.taskTokenBudget(requestedBudget);
                secrets.responseTokens(maxTokens);
                secrets.autopilotEnabled(autopilot.isChecked());
                if (changed) {
                    agent.clear();
                    messages.clear();
                    addMessage("КЕНТ", "Провайдер изменён. Контекст очищен, локальные файлы сохранены.");
                }
                boolean requested = trusted.isChecked();
                if (requested && !secrets.trustedMode() && !secrets.autopilotEnabled()) {
                    new AlertDialog.Builder(this)
                            .setTitle("☢ Включить TRUSTED MODE?")
                            .setMessage("КЕНТ сможет менять и удалять файлы своей рабочей папки без повторного разрешения. " +
                                    "Команды терминала по-прежнему требуют отдельного согласия. Включить?")
                            .setPositiveButton("ВКЛЮЧИТЬ", (d, w) -> {
                                secrets.trustedMode(true);
                                key.setText("");
                                toast("Настройки сохранены"); tab = 0; draw();
                            })
                            .setNegativeButton("Оставить выключенным", (d, w) -> {
                                key.setText("");
                                toast("Настройки провайдера сохранены"); tab = 0; draw();
                            }).show();
                } else {
                    secrets.trustedMode(requested);
                    key.setText("");
                    toast("Настройки сохранены"); tab = 0; draw();
                }
            } catch (Exception e) { toast("Ошибка настроек: " + e.getMessage()); }
        });

        Button clearKey = button("Удалить API-ключ выбранного провайдера", false);
        padded(content, clearKey, 11);
        clearKey.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Удалить ключ выбранного провайдера?")
                .setPositiveButton("Удалить", (d, w) -> {
                    try {
                        String selected = providers.getSelectedItemPosition() == 0 ? "openrouter" : "ai_flex";
                        secrets.saveProviderKey(selected, "");
                        toast("Ключ удалён");
                    } catch (Exception e) { toast("Ошибка: " + e.getMessage()); }
                })
                .setNegativeButton("Отмена", null).show());

        padded(content, text("▣ RUNNER / PERMISSIONS", 17, WHITE, true), 24);
        padded(content, text("run_command работает с правами KENT CODE. В BRIDGE отдельные права Termux и " +
                "Shizuku/ADB. Подтверждения инструментов отключены ТОЛЬКО если включён AUTOPILOT. " +
                "Результаты команд отправляются выбранному ИИ-провайдеру, поэтому не читай секреты. " +
                "Запросы к платным API могут списывать деньги.", 13, MUTED, false), 9);
        padded(content, text("KENT CODE · Android 0.5.1 RED PIXEL · GitHub Actions", 12, MUTED, false), 20);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        stopRequested.set(true);
        executor.shutdownNow();
        super.onDestroy();
    }
}
