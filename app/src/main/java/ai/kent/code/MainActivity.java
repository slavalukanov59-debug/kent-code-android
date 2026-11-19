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
    private final ExecutorService creditExecutor = Executors.newSingleThreadExecutor();
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
    private TextView creditLabel;
    private String creditText = "Кредиты: —";
    private String creditDetail = "";
    private volatile long lastCreditAttemptMs = 0;
    private volatile boolean creditLoading = false;
    private static final int REQ_JOURNAL_EXPORT = 742;
    private static final int RECENT_CHAT_LIMIT = 120;
    private String lastBridgeResult = "Проверки ещё не запускались.";
    private LinearLayout app;
    private LinearLayout mainArea;
    private LinearLayout chatFeed;
    private ScrollView chatScroll;
    private int savedChatScrollY = 0;
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
                // Usage is still saved in Mission Control; no token budget UI.
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
        t.setLineSpacing(dp(1), 1.02f);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setTextColor(WHITE);
        b.setBackground(shape(primary ? Color.rgb(184, 29, 58) : PANEL_2, 3));
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        return b;
    }

    private EditText field(String hint, String value) {
        EditText f = new EditText(this);
        f.setText(value);
        f.setHint(hint);
        f.setHintTextColor(MUTED);
        f.setSingleLine(false);
        f.setTextColor(WHITE);
        f.setTextSize(13);
        f.setTypeface(Typeface.MONOSPACE);
        f.setPadding(dp(9), dp(8), dp(9), dp(8));
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
                // Android 15-17 edge-to-edge: shift the entire root above the
                // soft keyboard, not only the ScrollView.
                Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
                int bottom = windowInsets.isVisible(WindowInsets.Type.ime())
                        ? Math.max(bars.bottom, ime.bottom) : bars.bottom;
                view.setPadding(bars.left, bars.top, bars.right, bottom);
                return windowInsets;
            });
        }
        setContentView(app);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(7), dp(12), dp(7));
        head.setBackgroundColor(Color.rgb(41, 10, 19));
        TextView title = text("■ KENT 0.5.2", 16, WHITE, true);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView providerTag = text((secrets.provider().equals("openrouter") ?
                "OPENROUTER" : "AI-FLEX") +
                (secrets.autopilotEnabled() ? "  ⚡ AUTO" : "  SAFE"), 10,
                secrets.autopilotEnabled() ? GOLD : MUTED, true);
        head.addView(providerTag);
        app.addView(head);

        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(5), dp(3), dp(5), dp(3));
        nav.setBackgroundColor(Color.rgb(41, 10, 19));
        String[] labels = {"ЧАТ", "ФАЙЛЫ", "МОСТ", "CTRL", "JOBS"};
        for (int i = 0; i < labels.length; i++) {
            final int selected = i;
            Button b = button(labels[i], i == tab);
            b.setTextSize(11);
            b.setMinimumWidth(0);
            b.setMinWidth(0);
            b.setPadding(dp(3), dp(5), dp(3), dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
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
        statusRow.setPadding(dp(12), dp(5), dp(12), dp(5));
        runStatus = text(running ? "◉ [BUSY] агент работает" : (secrets.autopilotEnabled() ?
                "⚡ [AUTOPILOT] готов" : (secrets.trustedMode() ? "◆ [TRUSTED] готов" : "◇ [SAFE] готов")), 12,
                running ? GOLD : (secrets.autopilotEnabled() ? GOLD : (secrets.trustedMode() ? BLUE : GREEN)), true);
        LinearLayout.LayoutParams statusWeight = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        statusRow.addView(runStatus, statusWeight);
        Button cancel = button("■ Стоп", false);
        cancel.setOnClickListener(v -> { stopRequested.set(true); toast("Остановка после текущего API-запроса"); });
        statusRow.addView(cancel, new LinearLayout.LayoutParams(dp(72), dp(36)));
        Button down = button("↓", false);
        LinearLayout.LayoutParams downParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        downParams.leftMargin = dp(5);
        statusRow.addView(down, downParams);
        down.setOnClickListener(v -> {
            if (chatScroll != null) chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        });
        mainArea.addView(statusRow);

        LinearLayout creditBar = new LinearLayout(this);
        creditBar.setGravity(Gravity.CENTER_VERTICAL);
        creditBar.setPadding(dp(12), dp(4), dp(10), dp(4));
        creditLabel = text(creditText, 12, GOLD, true);
        creditBar.addView(creditLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        creditLabel.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Кредитный баланс")
                .setMessage(creditDetail.isEmpty() ? "Показывается только подтверждённый ответ API. " +
                        "Если баланс не удалось получить, проверь его на сайте провайдера." : creditDetail)
                .setPositiveButton("OK", null).show());
        Button refresh = button("↻", false);
        refresh.setTextSize(17);
        creditBar.addView(refresh, new LinearLayout.LayoutParams(dp(36), dp(34)));
        refresh.setOnClickListener(v -> requestCredits(true));
        Button site = button("↗", false);
        site.setTextSize(16);
        LinearLayout.LayoutParams siteLp = new LinearLayout.LayoutParams(dp(36), dp(34));
        siteLp.leftMargin = dp(5);
        creditBar.addView(site, siteLp);
        site.setOnClickListener(v -> openCreditSite());
        mainArea.addView(creditBar);
        requestCredits(false);

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatScroll.setOnScrollChangeListener((View v, int x, int y, int ox, int oy) -> savedChatScrollY = y);
        chatFeed = vertical();
        chatFeed.setPadding(dp(10), dp(5), dp(10), dp(8));
        chatScroll.addView(chatFeed);
        mainArea.addView(chatScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        redrawFeed();

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setGravity(Gravity.BOTTOM);
        sendRow.setPadding(dp(9), dp(5), dp(9), dp(7));
        composer = field("Опиши задачу КЕНТУ…", "");
        composer.setMinLines(1);
        composer.setMaxLines(4);
        composer.setGravity(Gravity.TOP | Gravity.START);
        composer.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        composer.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams entry = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        sendRow.addView(composer, entry);
        Button send = button("➤", true);
        LinearLayout.LayoutParams go = new LinearLayout.LayoutParams(dp(51), dp(49));
        go.leftMargin = dp(6);
        sendRow.addView(send, go);
        send.setOnClickListener(v -> submit());
        mainArea.addView(sendRow);
    }

    private static String tokens(long count) {
        return String.format(java.util.Locale.US, "%,d", count).replace(',', ' ');
    }

    private void openCreditSite() {
        String url = secrets.provider().equals("openrouter") ?
                "https://openrouter.ai/activity" : "https://ai-flex.online/";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) { toast("Не удалось открыть сайт провайдера"); }
    }

    private void requestCredits(boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (creditLoading || (!force && lastCreditAttemptMs != 0 && now - lastCreditAttemptMs < 120_000)) return;
        lastCreditAttemptMs = now;
        creditLoading = true;
        creditText = "Кредиты: проверка…";
        if (tab == 0 && creditLabel != null) creditLabel.setText(creditText);
        creditExecutor.submit(() -> {
            CreditBalance.Result result;
            try {
                result = CreditBalance.query(secrets.provider(), secrets.loadActiveKey());
            } catch (Exception e) {
                result = new CreditBalance.Result("Кредиты: —", false,
                        "Не удалось получить данные аккаунта.");
            }
            final CreditBalance.Result finalResult = result;
            runOnUiThread(() -> {
                creditLoading = false;
                creditText = finalResult.label;
                creditDetail = finalResult.details;
                if (tab == 0 && creditLabel != null) creditLabel.setText(creditText);
            });
        });
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
            // Do not hijack the user’s reading position when the agent answers.
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
        if (chatScroll != null) chatScroll.post(() -> chatScroll.scrollTo(0, savedChatScrollY));
    }

    private void appendBubble(String who, String message) {
        LinearLayout card = vertical();
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        int fill = who.equals("ТЫ") ? Color.rgb(73, 18, 35) : PANEL;
        card.setBackground(shape(fill, 4));
        card.addView(text(who, 11, who.startsWith("Ошибка") ? GOLD : BLUE, true));
        padded(card, text(message, 13, WHITE, false), 3);
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.bottomMargin = dp(6);
        box.leftMargin = who.equals("ТЫ") ? dp(18) : 0;
        box.rightMargin = who.equals("ТЫ") ? 0 : dp(10);
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
        // Credit display refreshes from the provider rather than token estimates.
        executor.submit(() -> {
            try { agent.task(prompt); }
            finally {
                running = false;
                runOnUiThread(() -> {
                    if (tab == 0 && runStatus != null) runStatus.setText("🟢 Готов к задачам");
                    requestCredits(true);
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
        content.setPadding(dp(12), dp(10), dp(12), dp(15));
        scroll.addView(content);
        mainArea.addView(scroll);

        padded(content, text("▤ МИССИИ", 17, WHITE, true), 2);
        padded(content, text("Этапы отмечает ИИ; проверяй результаты в Git.", 11, MUTED, false), 4);
        MissionStore.Mission mission = missionStore.selected();
        if (mission == null) {
            padded(content, text("ЗАДАЧ ПОКА НЕТ", 17, GOLD, true), 23);
            padded(content, text("Отправь задачу во вкладке CHAT. Она автоматически попадёт " +
                    "сюда, а её состояние сохранится на устройстве.", 13, WHITE, false), 8);
        } else {
            LinearLayout card = vertical();
            card.setPadding(dp(10), dp(9), dp(10), dp(9));
            card.setBackground(shape(PANEL, 4));
            padded(card, text("● МИССИЯ " + mission.shortId() + " · " + mission.status,
                    14, GOLD, true), 0);
            String goalPreview = mission.goal.length() > 190
                    ? mission.goal.substring(0, 190) + "…" : mission.goal;
            padded(card, text(goalPreview, 12, WHITE, true), 5);
            padded(card, text("ЭТАП: " + mission.stage, 12, BLUE, true), 8);
            String progress = mission.total > 0
                    ? mission.completed + "/" + mission.total + " этапов по отметкам ИИ"
                    : "Этапы ещё не определены";
            padded(card, text(progress, 11, MUTED, false), 3);
            ProgressBar bar = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
            bar.setProgress(mission.total > 0 ? mission.completed * 1000 / mission.total : 0);
            padded(card, bar, 4);
            TextView summaryCompact = text(mission.summary.isEmpty() ?
                    "Контрольных точек пока нет." :
                    (mission.summary.length() > 135 ?
                            mission.summary.substring(0, 135) + "…" : mission.summary),
                    11, MUTED, false);
            padded(card, summaryCompact, 5);
            Button more = button("▸ ПОДРОБНОСТИ", false);
            padded(card, more, 6);
            LinearLayout details = vertical();
            details.setVisibility(View.GONE);
            padded(card, details, 3);
            more.setOnClickListener(v -> {
                boolean open = details.getVisibility() != View.VISIBLE;
                details.setVisibility(open ? View.VISIBLE : View.GONE);
                more.setText(open ? "▾ СВЕРНУТЬ" : "▸ ПОДРОБНОСТИ");
            });
            padded(details, text("КОНТРОЛЬНАЯ ТОЧКА", 11, GOLD, true), 5);
            padded(details, text(mission.summary.isEmpty() ?
                    "Нет" : mission.summary, 12, WHITE, false), 3);
            padded(details, text("СЛЕДУЮЩИЙ ШАГ", 11, GOLD, true), 9);
            padded(details, text(mission.next, 12, WHITE, false), 3);
            padded(details, text("Модель: " + mission.model + " · API: " + mission.calls +
                    " · Инструменты: " + mission.tools +
                    "\nСессии: " + mission.attempts +
                    "\nПоследний инструмент: " + mission.lastTool, 11, MUTED, false), 8);
            padded(content, card, 9);

            LinearLayout controls = new LinearLayout(this);
            padded(content, controls, 7);
            Button resume = button("▶ ПРОДОЛЖИТЬ", true);
            controls.addView(resume, new LinearLayout.LayoutParams(0, dp(40), 1));
            resume.setEnabled(!running && !"выполняется".equals(mission.status));
            resume.setOnClickListener(v -> confirmResume(mission));
            Button stop = button("■ СТОП", false);
            LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(40), 1);
            stopLp.leftMargin = dp(6);
            controls.addView(stop, stopLp);
            stop.setEnabled(running);
            stop.setOnClickListener(v -> {
                stopRequested.set(true);
                toast("Запрошена остановка. Уже запущенный процесс может продолжаться.");
            });
            Button report = button("⇩ ЭКСПОРТ ОТЧЁТА", false);
            padded(details, report, 10);
            report.setOnClickListener(v -> {
                pendingMissionExportId = mission.id;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TITLE, "KENT-mission-" + mission.shortId() + ".txt");
                startActivityForResult(i, REQ_MISSION_EXPORT);
            });
        }

        Button chat = button("⌘ ОТКРЫТЬ ЧАТ", false);
        padded(content, chat, 9);
        chat.setOnClickListener(v -> { tab = 0; draw(); });

        padded(content, text("ИСТОРИЯ", 13, WHITE, true), 15);
        for (MissionStore.Mission item : missionStore.list()) {
            String heading = (mission != null && mission.id.equals(item.id) ? "◆ " : "◇ ") +
                    item.shortId() + " · " + item.status + " · " +
                    (item.goal.length() > 54 ? item.goal.substring(0, 54) + "…" : item.goal);
            Button choose = button(heading, mission != null && mission.id.equals(item.id));
            choose.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            padded(content, choose, 5);
            choose.setOnClickListener(v -> {
                missionStore.select(item.id);
                draw();
            });
        }
        padded(content, text("Продолжение после сбоя — вручную. Проверь, завершились ли старые процессы.", 11, MUTED, false), 9);
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
        content.setPadding(dp(13), dp(9), dp(13), dp(19));
        scroll.addView(content);
        mainArea.addView(scroll);

        padded(content, text("⚙ НАСТРОЙКИ", 17, WHITE, true), 0);
        Spinner providers = new Spinner(this);
        String[] names = {"OpenRouter", "AI-FLEX"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, names);
        providers.setAdapter(adapter);
        providers.setSelection(secrets.provider().equals("openrouter") ? 0 : 1);
        padded(content, providers, 7);

        padded(content, text("Модель", 12, MUTED, true), 10);
        EditText model = field("ID модели", secrets.model());
        model.setSingleLine(true);
        padded(content, model, 3);

        LinearLayout presets = new LinearLayout(this);
        Button fastPreset = button("FAST", false);
        Button codePreset = button("CODE", false);
        Button maxPreset = button("MAX", false);
        for (Button b : new Button[]{fastPreset, codePreset, maxPreset}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
            lp.leftMargin = dp(4);
            presets.addView(b, lp);
        }
        padded(content, presets, 6);
        fastPreset.setOnClickListener(v -> model.setText("deepseek-v4.1-flash-1017"));
        codePreset.setOnClickListener(v -> model.setText("claude-sonnet-5-5"));
        maxPreset.setOnClickListener(v -> model.setText("claude-opus-5-5"));
        presets.setVisibility(secrets.provider().equals("ai_flex") ? View.VISIBLE : View.GONE);

        padded(content, text("API-ключ (пусто = сохранить прежний)", 12, MUTED, true), 11);
        EditText key = field("Только ключ, не промпт", "");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSingleLine(true);
        padded(content, key, 4);

        providers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String selected = pos == 0 ? "openrouter" : "ai_flex";
                model.setText(secrets.modelFor(selected));
                presets.setVisibility(selected.equals("ai_flex") ? View.VISIBLE : View.GONE);
                key.setText("");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        CheckBox autopilot = new CheckBox(this);
        autopilot.setText("⚡ AUTOPILOT — команды без подтверждений");
        autopilot.setTextColor(WHITE);
        autopilot.setTextSize(12);
        autopilot.setButtonTintList(android.content.res.ColorStateList.valueOf(GOLD));
        autopilot.setChecked(secrets.autopilotEnabled());
        padded(content, autopilot, 12);
        padded(content, text("⚠ В AUTOPILOT агент может менять файлы и запускать Termux/ADB без разрешения. " +
                "Доступные ему результаты могут отправляться ИИ-провайдеру.", 11, MUTED, false), 3);

        Button save = button("СОХРАНИТЬ", true);
        padded(content, save, 12);
        save.setOnClickListener(v -> {
            if (running) { toast("Сначала останови текущую задачу"); return; }
            try {
                String selected = providers.getSelectedItemPosition() == 0 ? "openrouter" : "ai_flex";
                String newModel = model.getText().toString().trim();
                if (newModel.isEmpty()) throw new IllegalArgumentException("Укажи модель");
                String value = key.getText().toString().trim();
                if (!value.isEmpty()) secrets.saveProviderKey(selected, value);
                boolean changed = !selected.equals(secrets.provider());
                secrets.provider(selected);
                secrets.model(newModel);
                secrets.autopilotEnabled(autopilot.isChecked());
                if (changed) {
                    agent.clear();
                    messages.clear();
                    addMessage("КЕНТ", "Провайдер изменён; контекст очищен. Файлы и история сохранены.");
                }
                lastCreditAttemptMs = 0;
                creditText = "Кредиты: —";
                creditDetail = "";
                key.setText("");
                toast("Настройки сохранены");
                tab = 0; draw();
            } catch (Exception e) {
                toast("Ошибка настроек: " + e.getMessage());
            }
        });

        Button advanced = button("▸ ДОПОЛНИТЕЛЬНО", false);
        padded(content, advanced, 18);
        LinearLayout extra = vertical();
        extra.setVisibility(View.GONE);
        padded(content, extra, 3);
        advanced.setOnClickListener(v -> {
            boolean visible = extra.getVisibility() != View.VISIBLE;
            extra.setVisibility(visible ? View.VISIBLE : View.GONE);
            advanced.setText(visible ? "▾ ДОПОЛНИТЕЛЬНО" : "▸ ДОПОЛНИТЕЛЬНО");
        });

        CheckBox trusted = new CheckBox(this);
        trusted.setText("TRUSTED — изменения файлов без запроса");
        trusted.setTextSize(12);
        trusted.setTextColor(WHITE);
        trusted.setChecked(secrets.trustedMode());
        padded(extra, trusted, 7);
        Button saveTrusted = button("ПРИМЕНИТЬ TRUSTED", false);
        padded(extra, saveTrusted, 5);
        saveTrusted.setOnClickListener(v -> {
            if (running) { toast("Останови задачу"); return; }
            if (trusted.isChecked() && !secrets.trustedMode()) {
                new AlertDialog.Builder(this)
                        .setTitle("Включить TRUSTED?")
                        .setMessage("КЕНТ сможет автоматически изменять файлы в рабочей папке.")
                        .setPositiveButton("Включить", (d, w) -> {
                            secrets.trustedMode(true); toast("TRUSTED включён");
                        })
                        .setNegativeButton("Отмена", null).show();
            } else {
                secrets.trustedMode(trusted.isChecked());
                toast("TRUSTED обновлён");
            }
        });

        Button openBalance = button("💳 ПРОВЕРИТЬ КРЕДИТЫ НА САЙТЕ ↗", false);
        padded(extra, openBalance, 7);
        openBalance.setOnClickListener(v -> openCreditSite());
        padded(extra, text("Кент показывает цифру кредитов, только если провайдер возвращает " +
                "баланс через API. Для AI-FLEX публичная схема баланса не подтверждена; " +
                "кнопка ↗ ведёт на официальный сайт. Токеновые лимиты задач отключены.", 11,
                MUTED, false), 5);

        Button clearKey = button("Удалить API-ключ", false);
        padded(extra, clearKey, 8);
        clearKey.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Удалить ключ выбранного провайдера?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    try {
                        String selected = providers.getSelectedItemPosition() == 0 ? "openrouter" : "ai_flex";
                        secrets.saveProviderKey(selected, "");
                        lastCreditAttemptMs = 0;
                        toast("Ключ удалён");
                    } catch (Exception e) { toast("Не удалось удалить ключ"); }
                }).show());

        Button export = button("⇩ ЭКСПОРТ ЖУРНАЛА", false);
        padded(extra, export, 8);
        export.setOnClickListener(v -> exportJournal());
        Button clear = button("Очистить диалог", false);
        padded(extra, clear, 6);
        clear.setOnClickListener(v -> {
            if (running) { toast("Сначала останови задачу"); return; }
            new AlertDialog.Builder(this)
                    .setTitle("Очистить чат и журнал?")
                    .setMessage("Миссии и файлы проекта останутся, история сообщений будет удалена.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Очистить", (d, w) -> {
                        try {
                            journal.clear();
                            agent.clear();
                            messages.clear();
                            addMessage("КЕНТ", "Диалог очищен. Проекты и миссии сохранены.");
                            toast("Готово");
                        } catch (IOException e) { toast("Ошибка журнала"); }
                    }).show();
        });
        padded(extra, text("KENT 0.5.2 · RED PIXEL · Termux + Shizuku", 11, MUTED, false), 13);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        stopRequested.set(true);
        executor.shutdownNow();
        creditExecutor.shutdownNow();
        super.onDestroy();
    }
}
