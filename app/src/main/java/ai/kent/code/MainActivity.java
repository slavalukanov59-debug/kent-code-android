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
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowInsets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native Android screens for the chat, missions and workspace tools. */
public final class MainActivity extends Activity {
    static final int BG = 0xFF0B0B0E;
    static final int PANEL = 0xFF17171D;
    static final int PANEL_2 = 0xFF25252E;
    static final int BLUE = 0xFFFF506D;
    static final int WHITE = 0xFFF3F0F2;
    static final int MUTED = 0xFFAEAAB6;
    static final int GOLD = 0xFFFFBE6F;
    static final int GREEN = 0xFF8CDD9D;
    static final int OUTLINE = 0xFF49313B;
    private static final int PRIMARY = 0xFFAF2544;
    private static final int[] NAV_TABS = {0, 4, 2, 5, 3};
    private static final String[] NAV_LABELS = {"Чат", "Задачи", "Действия", "GitHub", "Настройки"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService creditExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private SafeWorkspace workspace;
    private SecureKeyStore secrets;
    private volatile KentAgent activeAgent;
    private volatile String stopStatus = MissionStore.PAUSED;
    private volatile boolean foreground;
    private GitHubPanel githubPanel;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshMissions = () -> {
        if (foreground && !isFinishing() && !isDestroyed()) refreshMissionPresentation();
    };
    private boolean missionRefreshQueued;
    private TermuxBridge bridge;
    private LocalTermuxBridge localBridge;
    private ChatJournal journal;
    private TokenMeter tokenMeter;
    private MissionStore missionStore;
    private String pendingMissionExportId = "";
    private static final int REQ_MISSION_EXPORT = 743;
    private static final int REQ_MEMORY_EXPORT = 744;
    private TextView creditLabel;
    private String creditText = CreditBalance.UNKNOWN_LABEL;
    private int creditGeneration;
    private String creditDetail = "";
    private volatile long lastCreditAttemptMs = 0;
    private volatile boolean creditLoading = false;
    private static final int REQ_JOURNAL_EXPORT = 742;
    private static final int RECENT_CHAT_LIMIT = 120;
    private String lastBridgeResult = "Проверки ещё не запускались.";
    private LinearLayout app;
    private LinearLayout mainArea;
    private TextView providerLabel;
    private final Button[] navButtons = new Button[NAV_TABS.length];
    private ChatTimeline timeline;
    private ChatTimeline.Anchor chatAnchor = ChatTimeline.Anchor.latest();
    private final Set<String> expandedChat = new HashSet<>();
    private final Set<String> expandedMissions = new HashSet<>();
    private ScrollView missionScroll;
    private ScrollView actionScroll;
    private String actionMissionId = "";
    private int actionPanel;
    private String chatDraft = "";
    private int draftCursor;
    private EditText composer;
    private TextView runStatus;
    private TextView sessionUsage;
    private Button chatPause;
    private int tab = 0;
    private volatile boolean running = false;
    private final List<ChatTimeline.Message> messages = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Own the insets so keyboard and system-bar padding are applied once.
            getWindow().setDecorFitsSystemWindows(false);
        }
        try {
            KentLocalData data = KentLocalData.get(this);
            workspace = data.workspace;
            secrets = data.keys;
            journal = data.chat;
            tokenMeter = data.tokens;
            missionStore = data.missions;
            bridge = new TermuxBridge(this);
            localBridge = new LocalTermuxBridge(secrets);
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Ошибка хранилища")
                    .setMessage(e.getMessage()).setPositiveButton("Закрыть", (d, w) -> finish()).show();
            return;
        }
        for (ChatJournal.Record record : journal.recent(RECENT_CHAT_LIMIT)) {
            messages.add(new ChatTimeline.Message(record.id, record.title, chatDisplay(record.text)));
        }
        if (state != null) {
            pendingMissionExportId = state.getString("missionExport", "");
            lastBridgeResult = state.getString("bridgeResult", lastBridgeResult);
        }
        restoreChatUiState(state);
        draw();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        if (missionStore != null) {
            if (tab == 4) draw();
            else updateChatRunningState();
        }
    }

    @Override protected void onStop() {
        foreground = false;
        if (activeAgent != null && !stopRequested.get()) requestStop(MissionStore.PAUSED);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        saveChatUiState(state);
        state.putString("missionExport", pendingMissionExportId);
        state.putString("bridgeResult", lastBridgeResult);
        super.onSaveInstanceState(state);
    }

    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), OUTLINE);
        return g;
    }

    private RippleDrawable controlBackground(int fill) {
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), shape(fill, 4), null);
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
        t.setTypeface(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        b.setTextColor(WHITE);
        b.setMinHeight(dp(44));
        b.setBackgroundTintList(null);
        b.setBackground(controlBackground(primary ? PRIMARY : PANEL_2));
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
        rememberChat();
        int missionScrollY = missionScroll == null ? 0 : missionScroll.getScrollY();
        int actionScrollY = actionScroll == null ? 0 : actionScroll.getScrollY();
        if (tab != 0 && composer != null && composer.hasFocus()) {
            InputMethodManager input = getSystemService(InputMethodManager.class);
            if (input != null) input.hideSoftInputFromWindow(composer.getWindowToken(), 0);
        }
        if (app == null) createShell();
        mainArea.removeAllViews();
        composer = null;
        timeline = null;
        runStatus = null;
        sessionUsage = null;
        chatPause = null;
        creditLabel = null;
        missionScroll = null;
        actionScroll = null;
        providerLabel.setText(secrets.provider().equals("openrouter") ? "OpenRouter" : "AI Flex");
        for (int i = 0; i < NAV_TABS.length; i++) {
            Button item = navButtons[i];
            boolean selected = NAV_TABS[i] == tab;
            item.setBackground(controlBackground(selected ? PRIMARY : PANEL));
            item.setTextColor(selected ? WHITE : MUTED);
            item.setSelected(selected);
        }
        if (tab == 0) drawChat();
        if (tab == 2) {
            drawActions();
            ScrollView scroll = actionScroll;
            if (scroll != null && actionScrollY > 0) scroll.post(() -> scroll.scrollTo(0, actionScrollY));
        }
        if (tab == 3) drawSettings();
        if (tab == 4) {
            drawMissions();
            ScrollView scroll = missionScroll;
            if (missionScrollY > 0) scroll.post(() -> scroll.scrollTo(0, missionScrollY));
        }
        if (tab == 5) {
            if (githubPanel == null) githubPanel = new GitHubPanel(this, secrets, workspace);
            mainArea.addView(githubPanel.view(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void createShell() {
        app = vertical();
        app.setBackgroundColor(BG);
        app.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
                int bottom = windowInsets.isVisible(WindowInsets.Type.ime())
                        ? Math.max(bars.bottom, ime.bottom) : bars.bottom;
                if (view.getPaddingLeft() != bars.left || view.getPaddingTop() != bars.top
                        || view.getPaddingRight() != bars.right || view.getPaddingBottom() != bottom) {
                    view.setPadding(bars.left, bars.top, bars.right, bottom);
                }
                return WindowInsets.CONSUMED;
            });
        }
        setContentView(app);
        app.requestApplyInsets();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(8), dp(12), dp(7));
        head.setBackgroundColor(PANEL);
        View mark = new View(this);
        mark.setBackgroundColor(BLUE);
        LinearLayout.LayoutParams markSpace = new LinearLayout.LayoutParams(dp(7), dp(7));
        markSpace.rightMargin = dp(8);
        head.addView(mark, markSpace);
        TextView title = text("KENT CODE 0.6", 16, WHITE, true);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        providerLabel = text("", 12, MUTED, false);
        head.addView(providerLabel);
        app.addView(head);

        android.widget.HorizontalScrollView navScroll = new android.widget.HorizontalScrollView(this);
        navScroll.setFillViewport(true);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(5), dp(3), dp(5), dp(4));
        nav.setBackgroundColor(PANEL);
        for (int i = 0; i < NAV_TABS.length; i++) {
            final int selected = NAV_TABS[i];
            Button b = button(NAV_LABELS[i], false);
            navButtons[i] = b;
            b.setTextSize(12);
            b.setMinWidth(dp(47));
            b.setMinimumWidth(dp(47));
            b.setSingleLine();
            b.setPadding(dp(7), 0, dp(7), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(45), 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            nav.addView(b, lp);
            b.setOnClickListener(v -> {
                if (tab == selected) return;
                tab = selected;
                draw();
            });
        }
        navScroll.addView(nav);
        app.addView(navScroll);
        mainArea = vertical();
        LinearLayout.LayoutParams area = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        app.addView(mainArea, area);
    }

    private void rememberChat() {
        if (timeline != null) chatAnchor = timeline.anchor();
        if (composer != null) {
            chatDraft = composer.getText().toString();
            draftCursor = Math.max(0, composer.getSelectionStart());
        }
    }

    private void saveChatUiState(Bundle state) {
        rememberChat();
        state.putBundle("chat.anchor", chatAnchor.asBundle());
        state.putStringArrayList("chat.expanded", new ArrayList<>(expandedChat));
        state.putInt("active.tab", tab);
        state.putInt("actions.panel", actionPanel);
        state.putString("actions.mission", actionMissionId);
    }

    private void restoreChatUiState(Bundle state) {
        if (state == null) return;
        chatAnchor = ChatTimeline.Anchor.fromBundle(state.getBundle("chat.anchor"));
        ArrayList<String> expanded = state.getStringArrayList("chat.expanded");
        if (expanded != null) expandedChat.addAll(expanded);
        tab = state.getInt("active.tab", 0);
        actionPanel = state.getInt("actions.panel", 0);
        actionMissionId = state.getString("actions.mission", "");
    }

    private void drawChat() {
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(12), dp(5), dp(10), dp(4));
        LinearLayout status = vertical();
        status.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        runStatus = text("", 12, WHITE, true);
        runStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.addView(runStatus);
        creditLabel = text(creditText, 12, MUTED, false);
        status.addView(creditLabel);
        creditLabel.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Баланс провайдера")
                .setMessage(creditDetail.isEmpty() ? creditText : creditDetail)
                .setNegativeButton("Закрыть", null)
                .setNeutralButton("На сайте", (dialog, which) -> openCreditSite())
                .setPositiveButton("Обновить", (dialog, which) -> requestCredits(true)).show());
        sessionUsage = text("", 11, MUTED, false);
        sessionUsage.setSingleLine();
        sessionUsage.setEllipsize(TextUtils.TruncateAt.END);
        sessionUsage.setOnClickListener(view -> {
            TokenMeter.Snapshot usage = tokenMeter.snapshot();
            new AlertDialog.Builder(this).setTitle("Расходы последнего запуска")
                    .setMessage(sessionUsageText(usage, "\n"))
                    .setPositiveButton("Закрыть", null).show();
        });
        status.addView(sessionUsage);
        renderSessionUsage(tokenMeter.snapshot());
        chatPause = button("Пауза", false);
        chatPause.setOnClickListener(v -> {
            MissionStore.Mission mission = missionStore.active();
            if (mission != null) pauseMission(mission);
            else stopRequested.set(true);
            updateChatRunningState();
        });
        statusRow.addView(chatPause, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        Button down = button("↓", false);
        down.setContentDescription("Перейти к последнему сообщению");
        down.setMinimumWidth(0);
        down.setMinWidth(0);
        LinearLayout.LayoutParams downParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        downParams.leftMargin = dp(5);
        statusRow.addView(down, downParams);
        down.setOnClickListener(v -> {
            if (timeline != null) timeline.scrollToLatest();
        });
        mainArea.addView(statusRow);
        updateChatRunningState();
        requestCredits(false);

        timeline = new ChatTimeline(this, expandedChat);
        mainArea.addView(timeline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        redrawFeed();

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setGravity(Gravity.BOTTOM);
        sendRow.setPadding(dp(9), dp(5), dp(9), dp(7));
        sendRow.setBackgroundColor(PANEL);
        composer = field("Опишите задачу…", chatDraft);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setTypeface(Typeface.SANS_SERIF);
        composer.setTextSize(15);
        composer.setMinimumHeight(dp(48));
        composer.setMaxLines(4);
        composer.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        composer.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        composer.setSelection(Math.min(draftCursor, composer.length()));
        composer.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams entry = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        sendRow.addView(composer, entry);
        Button send = button("➤", true);
        send.setContentDescription("Отправить задачу");
        send.setTextSize(17);
        LinearLayout.LayoutParams go = new LinearLayout.LayoutParams(dp(52), dp(48));
        go.leftMargin = dp(6);
        sendRow.addView(send, go);
        send.setOnClickListener(v -> submit());
        mainArea.addView(sendRow);
    }

    private void updateChatRunningState() {
        if (runStatus == null) return;
        MissionStore.Mission active = missionStore.active();
        MissionStore.Mission shown = active == null ? missionStore.selected() : active;
        String state = shown == null ? "Готов к задачам" : shown.status;
        int color = shown == null ? GREEN : missionColor(shown.status);
        if (running && active == null) {
            state = "Выполняется команда Termux";
            color = GOLD;
        }
        if (running && stopRequested.get()) {
            state = "Запрошена остановка";
            color = GOLD;
        }
        runStatus.setText(state);
        runStatus.setTextColor(color);
        chatPause.setEnabled(running && !stopRequested.get());
        chatPause.setAlpha(chatPause.isEnabled() ? 1f : 0.52f);
    }

    private void renderSessionUsage(TokenMeter.Snapshot usage) {
        if (sessionUsage == null) return;
        sessionUsage.setVisibility(usage.calls > 0 ? View.VISIBLE : View.GONE);
        sessionUsage.setText(sessionUsageText(usage, " · "));
    }

    private static String sessionUsageText(TokenMeter.Snapshot usage, String separator) {
        List<String> lines = new ArrayList<>();
        if (!usage.model.isEmpty()) {
            lines.add((usage.modelReported ? "Модель " : "Запрошена модель ") + usage.model);
        }
        addReportedUsage(lines, "Входные токены", usage.input, usage.inputCalls, usage.calls);
        addReportedUsage(lines, "Выходные токены", usage.output, usage.outputCalls, usage.calls);
        if (usage.inputCalls == 0 && usage.outputCalls == 0) {
            addReportedUsage(lines, "Всего токенов", usage.used, usage.tokenCalls, usage.calls);
        }
        if (usage.lastRequestCost != null) {
            lines.add("Последний запрос " + amount(usage.lastRequestCost));
        }
        if (usage.sessionCost != null) {
            lines.add((usage.costComplete() ? "Запуск " : "Учтённая часть запуска ") + amount(usage.sessionCost));
        }
        return String.join(separator, lines);
    }

    private static void addReportedUsage(List<String> lines, String label, long value, int covered, int total) {
        if (covered > 0) {
            String coverage = covered < total ? " (" + covered + " из " + total + " ответов)" : "";
            lines.add(label + " " + tokens(value) + coverage);
        }
    }

    private static String amount(java.math.BigDecimal value) {
        return value.stripTrailingZeros().toPlainString() + " USD";
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

    private void resetCredits() {
        creditGeneration++;
        lastCreditAttemptMs = 0;
        creditLoading = false;
        creditText = CreditBalance.UNKNOWN_LABEL;
        creditDetail = "";
    }

    private void requestCredits(boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (isDestroyed() || creditLoading ||
                (!force && lastCreditAttemptMs != 0 && now - lastCreditAttemptMs < 120_000)) return;
        lastCreditAttemptMs = now;
        creditLoading = true;
        final String provider = secrets.provider();
        final int generation = creditGeneration;
        if (creditLabel != null) creditLabel.setText("Баланс: проверка…");
        creditExecutor.submit(() -> {
            CreditBalance.Result result;
            try {
                result = CreditBalance.query(provider, secrets.loadProviderKey(provider));
            } catch (Exception e) {
                result = new CreditBalance.Result(CreditBalance.UNKNOWN_LABEL, false,
                        "Не удалось получить данные аккаунта.");
            }
            final CreditBalance.Result received = result;
            runOnUiThread(() -> {
                if (isDestroyed() || generation != creditGeneration || !provider.equals(secrets.provider())) return;
                creditLoading = false;
                creditText = received.label;
                creditDetail = received.details;
                if (tab == 0 && creditLabel != null) creditLabel.setText(creditText);
            });
        });
    }

    private void addMessage(String who, String message) {
        ChatJournal.Record saved = null;
        if (journal != null) {
            try { saved = journal.append(who, message); }
            catch (IOException e) { android.util.Log.w("KENT", "Журнал недоступен", e); }
        }
        if (saved == null) renderChatMessage(new ChatTimeline.Message(who, chatDisplay(message)));
        else renderPersistedMessage(saved);
    }

    private void renderPersistedMessage(ChatJournal.Record record) {
        renderChatMessage(new ChatTimeline.Message(record.id, record.title, chatDisplay(record.text)));
    }

    private void renderChatMessage(ChatTimeline.Message row) {
        messages.add(row);
        if (messages.size() > RECENT_CHAT_LIMIT) expandedChat.remove(messages.remove(0).id);
        if (timeline != null) {
            ChatTimeline.Anchor position = row.sentByUser() ? ChatTimeline.Anchor.latest() : timeline.anchor();
            timeline.setMessages(messages, position);
        } else if (row.sentByUser()) {
            chatAnchor = ChatTimeline.Anchor.latest();
        }
    }

    private static String chatDisplay(String message) {
        int limit = 24_000;
        if (message.length() <= limit) return message;
        int end = Character.isHighSurrogate(message.charAt(limit - 1)) ? limit - 1 : limit;
        return message.substring(0, end) + "\n[На экране показана часть сообщения. Доступен экспорт журнала.]";
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
        if (timeline != null) timeline.setMessages(messages, chatAnchor);
    }

    private boolean taskRunning() {
        return running || missionStore.active() != null;
    }

    private String activeKey() throws Exception {
        String key = secrets.loadActiveKey();
        SecureKeyStore.validateApiKey(key);
        return key;
    }

    private String cleanPrompt(String value) {
        String safe = SensitiveData.redact(value.trim());
        try { safe = SensitiveData.redact(safe, secrets.loadProviderKey("ai_flex")); }
        catch (Exception ignored) { }
        try { safe = SensitiveData.redact(safe, secrets.loadProviderKey("openrouter")); }
        catch (Exception ignored) { }
        try { safe = SensitiveData.redact(safe, secrets.bridgeToken()); }
        catch (Exception ignored) { }
        try { safe = SensitiveData.redact(safe, secrets.loadGitHubToken()); }
        catch (Exception ignored) { }
        return safe;
    }

    private void submit() {
        if (taskRunning()) { toast("Предыдущая задача ещё выполняется"); return; }
        String prompt = cleanPrompt(composer.getText().toString());
        if (prompt.isEmpty()) return;
        final String key;
        try {
            key = activeKey();
        } catch (Exception e) {
            toast("Сохраните API-ключ выбранного провайдера в настройках.");
            tab = 3;
            draw();
            return;
        }
        try {
            MissionStore.Mission mission = missionStore.begin(prompt, secrets.provider(), secrets.model());
            composer.setText("");
            addMessage("ТЫ", prompt);
            launchMission(mission, prompt, key);
        } catch (RuntimeException e) {
            toast("Не удалось сохранить задачу. " + e.getMessage());
        }
    }

    private KentAgent.Events agentEvents() {
        return new KentAgent.Events() {
            @Override public void log(String title, String message) {
                try {
                    ChatJournal.Record record = journal.append(title, SensitiveData.redact(message));
                    runOnUiThread(() -> {
                        if (!isDestroyed()) renderPersistedMessage(record);
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> {
                        if (!isDestroyed()) toast("Не удалось сохранить сообщение в журнале.");
                    });
                }
            }

            @Override public boolean approve(String title, String detail) {
                return foreground && !stopRequested.get() && requestApproval(title, detail);
            }

            @Override public boolean cancelled() { return stopRequested.get(); }
            @Override public String stopStatus() { return stopStatus; }

            @Override public void usage(TokenMeter.Snapshot snapshot) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && tab == 0) renderSessionUsage(snapshot);
                });
            }

            @Override public void missionChanged() {
                runOnUiThread(() -> {
                    if (missionRefreshQueued || isDestroyed() || (tab != 4 && tab != 2)) return;
                    missionRefreshQueued = true;
                    mainHandler.postDelayed(() -> {
                        missionRefreshQueued = false;
                        refreshMissions.run();
                    }, 350L);
                });
            }
        };
    }

    private void launchMission(MissionStore.Mission mission, String prompt, String key) {
        SafeWorkspace selected;
        try {
            selected = missionWorkspace(mission);
        } catch (IOException e) {
            missionStore.noteError("Рабочая копия", e.getMessage());
            missionStore.finish(MissionStore.ERROR);
            toast(e.getMessage());
            return;
        }
        KentAgent agent = new KentAgent(selected, tokenMeter, missionStore, agentEvents());
        boolean autoWrite = mission.workspaceId.isEmpty() && mission.attempts == 1 && secrets.autopilotEnabled();
        activeAgent = agent;
        running = true;
        stopStatus = MissionStore.PAUSED;
        stopRequested.set(false);
        updateChatRunningState();
        executor.submit(() -> {
            try { agent.task(mission, prompt, autoWrite, key); }
            finally {
                runOnUiThread(() -> {
                    running = false;
                    activeAgent = null;
                    if (isDestroyed()) return;
                    updateChatRunningState();
                    if (foreground) requestCredits(true);
                    refreshMissions.run();
                });
            }
        });
    }

    private void requestStop(String status) {
        stopStatus = status;
        stopRequested.set(true);
        KentAgent agent = activeAgent;
        if (agent != null) {
            Thread disconnect = new Thread(agent::disconnectRequest, "kent-request-cancel");
            disconnect.setDaemon(true);
            disconnect.start();
        }
        updateChatRunningState();
    }

    /**
     * Full-screen approval sheet: long command previews scroll independently
     * and the DENY / ALLOW buttons are always pinned at the bottom.
     * Nothing can run unless the user explicitly taps ALLOW.
     */
    private boolean requestApproval(String title, String detail) {
        if (!foreground || isFinishing() || stopRequested.get()) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean(false);
        final Dialog[] active = new Dialog[1];

        runOnUiThread(() -> {
            if (!foreground || isFinishing() || isDestroyed() || stopRequested.get()) {
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
            long deadline = android.os.SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(5);
            while (!stopRequested.get() && foreground &&
                    android.os.SystemClock.elapsedRealtime() < deadline) {
                if (latch.await(200, TimeUnit.MILLISECONDS)) {
                    answered = true;
                    break;
                }
            }
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
        content.setPadding(dp(12), dp(9), dp(12), dp(15));
        scroll.addView(content);
        mainArea.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout row = new LinearLayout(this);
        Button create = button("+ Новый файл", true);
        Button importButton = button("⇧ Импорт", false);
        row.addView(create, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(44), 1); right.leftMargin = dp(6);
        row.addView(importButton, right);
        create.setOnClickListener(v -> createFile());
        importButton.setOnClickListener(v -> importFile());
        padded(content, row, 4);
        try {
            List<String> paths = workspace.filePaths();
            if (paths.isEmpty()) padded(content, text("Пока нет файлов", 14, MUTED, false), 17);
            for (String path : paths) {
                Button f = button(path, false);
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
        if (requestCode == REQ_MEMORY_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) saveMemory(data.getData());
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
            if (tab == 2 && actionPanel == 1) draw();
        } catch (Exception e) { toast("Импорт: " + e.getMessage()); }
    }


    /** Manual opt-in for third-party Termux command bridge. */
    private void drawBridge() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(dp(12), dp(9), dp(12), dp(15));
        scroll.addView(content);
        mainArea.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        padded(content, text("Мост Termux", 16, WHITE, true), 3);
        padded(content, text("Для сборки Termux из Google Play доступен локальный мост. " +
                "Диагностику Termux и Shizuku запускает пользователь.", 13, MUTED, false), 5);

        padded(content, text(bridge.hasPermission()
                ? "● Разрешение RUN_COMMAND: выдано"
                : "○ Разрешение RUN_COMMAND: не выдано", 13,
                bridge.hasPermission() ? GREEN : GOLD, true), 20);

        Button permission = button("Выдать разрешение Termux", true);
        padded(content, permission, 10);
        permission.setOnClickListener(v -> {
            if (bridge.hasPermission()) { toast("Разрешение уже выдано"); return; }
            requestPermissions(new String[]{TermuxBridge.PERMISSION}, 403);
        });
        padded(content, text("Если Android не покажет запрос: Настройки → Приложения → KENT CODE RED → " +
                "Разрешения → Дополнительные разрешения → Run commands in Termux environment.", 12, MUTED, false), 7);

        padded(content, text("Разрешение внешних команд в Termux", 13, WHITE, true), 18);
        padded(content, text("mkdir -p ~/.termux\n" +
                "printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties\n" +
                "termux-reload-settings", 12, GREEN, false), 7);
        padded(content, text("Используется официальный RUN_COMMAND Intent (Termux >= 0.109). " +
                "Shizuku должен быть запущен, а ~/rish и ~/rish_shizuku.dex уже настроены. " +
                "Если Android блокирует запуск, открой Termux на экране и разреши фоновую работу.", 12, MUTED, false), 10);

        padded(content, text("Локальный мост для Termux", 15, WHITE, true), 20);
        padded(content, text("Для сборок без RUN_COMMAND запустите локальный сервер в Termux.", 12,
                MUTED, false), 7);
        padded(content, text("cd ~/KENT-CODE-Android\n" +
                "git pull --ff-only\n" +
                "pkg install python -y\n" +
                "python tools/kent_bridge_server.py", 12, GREEN, false), 10);
        padded(content, text("Оставь Termux с запущенным сервером. Он слушает ТОЛЬКО 127.0.0.1:47883 " +
                "и сохраняет токен в ~/.kent-bridge/token. При первой настройке прочитайте его через " +
                "cat ~/.kent-bridge/token и вставьте в поле ниже. Токен хранится зашифрованно.", 12, MUTED, false), 10);
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

        Button toggle = button(secrets.bridgeEnabled() ? "Отключить мост" : "Включить мост", true);
        padded(content, toggle, 16);
        toggle.setOnClickListener(v -> {
            if (secrets.bridgeEnabled()) {
                secrets.bridgeEnabled(false);
                draw();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Включить мост Termux?")
                    .setMessage("Проверки выполняются с правами Termux или Shizuku. " +
                            "Android сообщает о предоставленных разрешениях в настройках приложения.")
                    .setPositiveButton("Включить", (d, w) -> { secrets.bridgeEnabled(true); draw(); })
                    .setNegativeButton("Отмена", null).show();
        });

        Button testTermux = button("▶ Тест Termux: id", false);
        padded(content, testTermux, 14);
        testTermux.setOnClickListener(v -> testBridge(false));
        Button testAdb = button("▶ Тест Shizuku: id", false);
        padded(content, testAdb, 9);
        testAdb.setOnClickListener(v -> testBridge(true));
        Button manualCommand = button("Выполнить свою команду в Termux", false);
        padded(content, manualCommand, 9);
        manualCommand.setOnClickListener(v -> promptTermuxCommand());
        Button stopBridge = button("■ Остановить ожидание", false);
        padded(content, stopBridge, 9);
        stopBridge.setOnClickListener(v -> {
            stopRequested.set(true);
            toast("Ожидание отменяется; выполняемая команда в Termux может продолжить работу.");
        });
        padded(content, text("Последний результат", 13, MUTED, true), 16);
        padded(content, text(lastBridgeResult, 12, WHITE, false), 7);
        padded(content, text("Остановка ожидания не завершает уже запущенный процесс Termux.",
                12, MUTED, false), 10);
    }

    private void promptTermuxCommand() {
        if (taskRunning()) { toast("Приостановите текущую задачу."); return; }
        LinearLayout inputs = vertical();
        inputs.setPadding(dp(16), dp(4), dp(16), dp(10));
        EditText command = field("Команда Termux", "");
        command.setMinLines(2);
        EditText directory = field("Каталог относительно домашней папки", "");
        directory.setSingleLine(true);
        EditText timeout = field("Секунд ожидания", "45");
        timeout.setInputType(InputType.TYPE_CLASS_NUMBER);
        padded(inputs, command, 3);
        padded(inputs, directory, 6);
        padded(inputs, timeout, 6);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Команда Termux")
                .setView(inputs).setNegativeButton("Отмена", null)
                .setPositiveButton("Проверить", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String entered = command.getText().toString();
            if (entered.trim().isEmpty() || entered.length() > 5000) {
                command.setError("Введите команду до 5000 символов");
                return;
            }
            String workdir = directory.getText().toString().trim();
            int seconds;
            try {
                seconds = Integer.parseInt(timeout.getText().toString().trim());
                if (seconds < 1 || seconds > 120) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                timeout.setError("Укажите от 1 до 120 секунд");
                return;
            }
            dialog.dismiss();
            new AlertDialog.Builder(this).setTitle("Запустить команду в Termux?")
                    .setMessage("Каталог: " + (workdir.isEmpty() ? "домашний" : workdir) +
                            "\nОжидание: " + seconds + " с\n\n" + entered)
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Запустить", (confirm, which) -> executeBridge(entered, false, workdir, seconds))
                    .show();
        }));
        dialog.show();
    }

    private void testBridge(boolean adb) {
        executeBridge("id", adb, "", 30);
    }

    private void executeBridge(String command, boolean adb, String directory, int seconds) {
        if (taskRunning()) { toast("Приостановите текущую задачу."); return; }
        if (!secrets.bridgeEnabled()) { toast("Включите мост Termux."); return; }
        boolean local = localBridge.configured();
        if (!local && !bridge.hasPermission()) { toast("Добавьте локальный токен или разрешение RUN_COMMAND."); return; }
        running = true;
        stopRequested.set(false);
        lastBridgeResult = adb ? "Проверяется текущий UID Shizuku…" : "Ожидается результат Termux…";
        if (tab == 2) draw();
        executor.submit(() -> {
            String result;
            try {
                CommandResult completed = local
                        ? localBridge.runResult(command, adb, directory, seconds, stopRequested::get)
                        : bridge.runResult(command, adb, directory, seconds, stopRequested::get);
                result = SensitiveData.redact(completed.display());
            } catch (IOException e) {
                result = "Ошибка моста: " + SensitiveData.redact(e.getMessage() == null ? "нет результата" : e.getMessage());
            }
            final String output = result;
            runOnUiThread(() -> {
                running = false;
                lastBridgeResult = output;
                if (isDestroyed()) return;
                updateChatRunningState();
                if (tab == 2) draw();
            });
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

    private void drawActions() {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(dp(10), dp(7), dp(10), 0);
        String[] choices = {"Журнал", "Файлы", "Termux"};
        for (int i = 0; i < choices.length; i++) {
            int selected = i;
            Button item = button(choices[i], actionPanel == selected);
            item.setSelected(actionPanel == selected);
            LinearLayout.LayoutParams span = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (i > 0) span.leftMargin = dp(5);
            navigation.addView(item, span);
            item.setOnClickListener(view -> {
                actionPanel = selected;
                draw();
            });
        }
        mainArea.addView(navigation);
        if (actionPanel == 0) drawActionJournal();
        if (actionPanel == 1) drawFiles();
        if (actionPanel == 2) drawBridge();
    }

    private void drawActionJournal() {
        ScrollView scroll = new ScrollView(this);
        actionScroll = scroll;
        LinearLayout content = vertical();
        content.setPadding(dp(12), dp(9), dp(12), dp(15));
        scroll.addView(content);
        mainArea.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        MissionStore.Mission mission = missionStore.find(actionMissionId);
        if (mission == null) mission = missionStore.selected();
        if (mission == null) {
            padded(content, text("Журнал появится после запуска задачи.", 13, MUTED, false), 8);
            return;
        }

        padded(content, text(taskTitle(mission, 140), 16, WHITE, true), 1);
        padded(content, text(mission.status, 12, missionColor(mission.status), true), 3);
        if (mission.compactedEvents > 0) {
            padded(content, text("Действий в сводке: " + mission.compactedEvents, 12, MUTED, false), 4);
        }
        if (mission.events.isEmpty()) {
            padded(content, text("Записей пока нет", 13, MUTED, false), 8);
        }
        for (int i = mission.events.size() - 1; i >= 0; i--) {
            MissionStore.Event event = mission.events.get(i);
            String heading = event.name;
            if (!event.path.isEmpty()) heading += " · " + event.path;
            Button row = button(heading, false);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            padded(content, row, 7);
            String time = android.text.format.DateFormat.getTimeFormat(this).format(new Date(event.time));
            padded(content, text(time + (event.outcome.isEmpty() ? "" : " · " + event.outcome),
                    12, MUTED, false), 2);
            row.setOnClickListener(view -> openActionEntry(event));
        }
    }

    private void openActionEntry(MissionStore.Event event) {
        ScrollView scroll = new ScrollView(this);
        TextView body = text(event.detail.isEmpty() ? event.outcome : event.detail, 14, WHITE, false);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));
        body.setTextIsSelectable(true);
        scroll.addView(body);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(event.name)
                .setView(scroll).setPositiveButton("Закрыть", null);
        if (!event.patch.isEmpty()) {
            dialog.setNeutralButton("Изменения", (window, which) -> showCode(event.path, event.patch));
        }
        dialog.show();
    }

    private void showCode(String title, String code) {
        ScrollView vertical = new ScrollView(this);
        android.widget.HorizontalScrollView horizontal = new android.widget.HorizontalScrollView(this);
        TextView body = text(code, 12, WHITE, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(dp(14), dp(10), dp(14), dp(10));
        body.setHorizontallyScrolling(true);
        body.setTextIsSelectable(true);
        horizontal.addView(body);
        vertical.addView(horizontal);
        new AlertDialog.Builder(this).setTitle(title).setView(vertical)
                .setPositiveButton("Закрыть", null).show();
    }

    private static String taskTitle(MissionStore.Mission mission, int limit) {
        int end = mission.goal.indexOf('\n');
        String firstLine = (end < 0 ? mission.goal : mission.goal.substring(0, end)).trim();
        if (firstLine.isEmpty()) return "Задача " + mission.shortId();
        return firstLine.length() > limit ? firstLine.substring(0, limit) + "…" : firstLine;
    }

    private static int missionColor(String status) {
        if (MissionStore.DONE.equals(status)) return GREEN;
        if (MissionStore.RUNNING.equals(status)) return BLUE;
        if (MissionStore.ERROR.equals(status) || MissionStore.REVIEW.equals(status)) return GOLD;
        return MUTED;
    }

    private static String elapsedTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        if (seconds < 60) return seconds + " с";
        if (seconds < 3600) return (seconds / 60) + " мин " + (seconds % 60) + " с";
        return (seconds / 3600) + " ч " + ((seconds / 60) % 60) + " мин";
    }

    private void drawMissions() {
        missionScroll = new ScrollView(this);
        missionScroll.setFillViewport(true);
        LinearLayout content = vertical();
        content.setPadding(dp(12), dp(9), dp(12), dp(15));
        missionScroll.addView(content);
        mainArea.addView(missionScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button create = button("+ Новая задача", true);
        create.setOnClickListener(view -> createMission());
        padded(content, create, 0);

        MissionStore.Mission mission = missionStore.selected();
        if (mission == null) {
            padded(content, text("Создайте задачу или отправьте сообщение в чате.", 14, MUTED, false), 12);
        } else {
            drawMissionCard(content, mission);
            padded(content, text("История задач", 14, WHITE, true), 14);
            for (MissionStore.Mission item : missionStore.list()) {
                Button choose = button(taskTitle(item, 70) + "\n" + item.status, item.id.equals(mission.id));
                choose.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                padded(content, choose, 5);
                choose.setOnClickListener(view -> {
                    missionStore.select(item.id);
                    missionScroll.scrollTo(0, 0);
                    draw();
                });
            }
        }
        padded(content, actionButton("Память агента", this::showMemory), 12);
        padded(content, actionButton("Отчёт о работе агента", this::showQualityReport), 6);
        padded(content, actionButton("KENT LAB", this::showKentLab), 6);
    }

    private void refreshMissionPresentation() {
        if (tab == 4 || (tab == 2 && actionPanel == 0)) draw();
    }

    private Button actionButton(String title, Runnable action) {
        Button control = button(title, false);
        control.setOnClickListener(view -> action.run());
        return control;
    }

    private void drawMissionCard(LinearLayout content, MissionStore.Mission mission) {
        LinearLayout card = vertical();
        card.setPadding(dp(11), dp(10), dp(11), dp(10));
        card.setBackground(shape(PANEL, 4));
        padded(content, card, 8);
        padded(card, text(mission.status, 12, missionColor(mission.status), true), 0);
        padded(card, text(taskTitle(mission, 160), 16, WHITE, true), 4);
        padded(card, text(mission.stage, 14, WHITE, false), 5);
        if (mission.total > 0) {
            padded(card, text(mission.completed + " из " + mission.total + " этапов по отчёту агента",
                    12, MUTED, false), 4);
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(mission.total);
            bar.setProgress(mission.completed);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
            padded(card, bar, 2);
        }
        String model = mission.reportedModel.isEmpty() ? mission.model : mission.reportedModel;
        if (model.isEmpty()) model = "Модель неизвестна";
        padded(card, text(model + " · " + elapsedTime(mission.elapsedMillis), 12, MUTED, false), 4);
        if (!mission.error.isEmpty()) {
            padded(card, text(mission.error, 13, GOLD, false), 5);
        }
        LinearLayout controls = new LinearLayout(this);
        boolean active = MissionStore.RUNNING.equals(mission.status);
        boolean anyActive = taskRunning();
        if (active && running) {
            addControl(controls, "Пауза", true, () -> pauseMission(mission));
        }
        if (mission.canResume() && !anyActive) {
            addControl(controls, MissionStore.WAITING.equals(mission.status) ? "Запустить" : "Продолжить",
                    true, () -> confirmResume(mission));
        }
        if (mission.canResume() || (active && running)) {
            addControl(controls, "Отменить", false, () -> confirmCancel(mission));
        }
        addControl(controls, "Журнал", false, () -> {
            actionMissionId = mission.id;
            actionPanel = 0;
            actionScroll = null;
            tab = 2;
            draw();
        });
        padded(card, controls, 7);
        if (mission.canMarkVerified() && !anyActive) {
            padded(card, actionButton("Отметить проверенной", () -> confirmVerified(mission)), 5);
        }

        Button expand = button("Подробнее", false);
        padded(card, expand, 5);
        LinearLayout details = vertical();
        padded(card, details, 3);
        if (expandedMissions.contains(mission.id)) populateMissionDetails(details, mission);
        expand.setText(expandedMissions.contains(mission.id) ? "Свернуть" : "Подробнее");
        expand.setOnClickListener(view -> {
            if (expandedMissions.add(mission.id)) {
                populateMissionDetails(details, mission);
                expand.setText("Свернуть");
            } else {
                expandedMissions.remove(mission.id);
                details.removeAllViews();
                expand.setText("Подробнее");
            }
        });
    }

    private void addControl(LinearLayout row, String label, boolean primary, Runnable action) {
        Button control = button(label, primary);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(44), 1);
        if (row.getChildCount() > 0) size.leftMargin = dp(5);
        control.setMinWidth(0);
        control.setMinimumWidth(0);
        control.setPadding(dp(2), 0, dp(2), 0);
        row.addView(control, size);
        control.setOnClickListener(view -> action.run());
    }

    private void populateMissionDetails(LinearLayout details, MissionStore.Mission mission) {
        missionDetail(details, "Задача", mission.goal);
        missionDetail(details, "Контрольная точка", mission.summary);
        missionDetail(details, "Следующий этап", mission.next);
        missionDetail(details, "Результат", mission.result);
        missionDetail(details, "Решения", mission.decisions);
        missionDetail(details, "Структура проекта", mission.structure);
        missionDetail(details, "Учёт запросов", "Ответов API: " + mission.calls +
                " · вызовов инструментов: " + mission.tools);
        List<String> usage = new ArrayList<>();
        addReportedUsage(usage, "Входные токены", mission.inputTokens, mission.inputCalls, mission.calls);
        addReportedUsage(usage, "Выходные токены", mission.outputTokens, mission.outputCalls, mission.calls);
        if (mission.inputCalls == 0 && mission.outputCalls == 0) {
            addReportedUsage(usage, "Всего токенов", mission.tokens, mission.tokenCalls, mission.calls);
        }
        if (mission.lastRequestCost != null) {
            usage.add("Последний запрос " + amount(mission.lastRequestCost));
        }
        if (mission.cost != null) {
            String label = mission.costedCalls == mission.calls ? "Расход задачи " : "Учтённая часть расхода ";
            usage.add(label + amount(mission.cost));
        }
        missionDetail(details, "Данные провайдера", String.join("\n", usage));
        if (!mission.changedFiles.isEmpty()) {
            padded(details, text("Изменённые файлы", 13, WHITE, true), 9);
        }
        for (String path : mission.changedFiles) {
            LinearLayout actions = new LinearLayout(this);
            padded(details, text(path, 12, MUTED, false), 7);
            addControl(actions, "Открыть", false, () -> openMissionFile(mission, path));
            addControl(actions, "Изменения", false, () -> showMissionDiff(mission, path));
            padded(details, actions, 4);
        }
        padded(details, actionButton("Экспорт отчёта", () -> exportMission(mission)), 8);
    }

    private void missionDetail(LinearLayout parent, String label, String body) {
        if (body.isEmpty()) return;
        padded(parent, text(label, 12, MUTED, true), 8);
        TextView value = text(body, 13, WHITE, false);
        value.setTextIsSelectable(true);
        padded(parent, value, 2);
    }

    private void createMission() {
        EditText goal = field("Что нужно сделать", "");
        goal.setMinLines(3);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Новая задача")
                .setView(goal)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String prompt = cleanPrompt(goal.getText().toString());
            if (prompt.isEmpty()) { goal.setError("Опишите задачу"); return; }
            try {
                missionStore.create(prompt, secrets.provider(), secrets.model());
                dialog.dismiss();
                draw();
            } catch (RuntimeException e) { toast(e.getMessage()); }
        }));
        dialog.show();
    }

    private void confirmResume(MissionStore.Mission chosen) {
        if (taskRunning()) { toast("Дождитесь остановки текущей задачи."); return; }
        MissionStore.Mission current = missionStore.find(chosen.id);
        if (current == null || !current.canResume()) { draw(); return; }
        boolean review = current.needsReview();
        String provider = secrets.provider();
        String model = secrets.model();
        String message = "Запрос отправится провайдеру " + provider + " · " + model + ".";
        if (current.attempts > 0) {
            message += "\nБудут использованы исходная задача, контрольная точка и недавние события этой миссии.";
        }
        if (review) {
            message += "\n\nСверьте журнал и изменённые файлы. Подтвердите, что проверили состояние предыдущих действий.";
        }
        new AlertDialog.Builder(this)
                .setTitle(current.attempts == 0 ? "Запустить задачу?" : "Продолжить задачу?")
                .setMessage(message)
                .setNegativeButton("Отмена", null)
                .setPositiveButton(review ? "Проверено, продолжить" : "Запустить", (d, w) -> {
                    if (taskRunning()) return;
                    try {
                        String key = activeKey();
                        missionWorkspace(current);
                        MissionStore.Mission active = missionStore.resume(current.id, provider, model, review);
                        String prompt = current.attempts == 0 ? active.goal : current.resumePrompt();
                        addMessage(current.attempts == 0 ? "ТЫ" : "ТЫ · ПРОДОЛЖЕНИЕ",
                                current.attempts == 0 ? active.goal : "Продолжить задачу " + active.shortId());
                        tab = 0;
                        draw();
                        // Require a fresh review of writes after an interrupted or reviewed session.
                        launchMission(active, prompt, key);
                    } catch (Exception e) {
                        toast("Не удалось запустить задачу. " + e.getMessage());
                    }
                }).show();
    }

    private void pauseMission(MissionStore.Mission chosen) {
        MissionStore.Mission active = missionStore.active();
        if (activeAgent == null || active == null || !chosen.id.equals(active.id)) {
            toast("Задача уже останавливается.");
            return;
        }
        requestStop(MissionStore.PAUSED);
        toast("Остановка запрошена. Состояние сохранится после завершения текущей операции.");
    }

    private void confirmCancel(MissionStore.Mission chosen) {
        new AlertDialog.Builder(this)
                .setTitle("Отменить задачу " + chosen.shortId() + "?")
                .setMessage("Уже выполненные правки и журнал останутся в рабочей области.")
                .setNegativeButton("Назад", null)
                .setPositiveButton("Отменить задачу", (d, w) -> {
                    try {
                        MissionStore.Mission active = missionStore.active();
                        if (active != null && active.id.equals(chosen.id)) {
                            if (activeAgent != null) requestStop(MissionStore.CANCELLED);
                            else toast("Дождитесь завершения остановки.");
                        } else {
                            missionStore.cancel(chosen.id);
                            draw();
                        }
                    } catch (RuntimeException e) { toast(e.getMessage()); }
                }).show();
    }

    private void confirmVerified(MissionStore.Mission chosen) {
        new AlertDialog.Builder(this)
                .setTitle("Результат проверен?")
                .setMessage("Подтвердите после просмотра результата и выполненных правок.")
                .setNegativeButton("Назад", null)
                .setPositiveButton("Подтвердить", (d, w) -> {
                    try {
                        missionStore.markVerified(chosen.id);
                        draw();
                    } catch (RuntimeException e) { toast(e.getMessage()); }
                }).show();
    }

    private SafeWorkspace missionWorkspace(MissionStore.Mission mission) throws IOException {
        return mission.workspaceId.isEmpty() ? workspace : LabWorkspace.open(this, mission.workspaceId);
    }

    private void showMissionDiff(MissionStore.Mission mission, String path) {
        StringBuilder diff = new StringBuilder();
        for (MissionStore.Event event : mission.events) {
            if (path.equals(event.path) && !event.patch.isEmpty()) {
                if (diff.length() > 24_000) {
                    diff.append("\nДальнейшие изменения доступны в экспорте задачи.");
                    break;
                }
                if (diff.length() > 0) diff.append('\n');
                diff.append(event.patch);
            }
        }
        String preview = diff.length() == 0 ? "Сохранённые изменения отсутствуют." : diff.toString();
        ScrollView scroll = new ScrollView(this);
        TextView body = text(preview, 12, WHITE, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(14), dp(8), dp(14), dp(8));
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle(path).setView(scroll)
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Открыть файл", (d, w) -> openMissionFile(mission, path)).show();
    }

    private void openMissionFile(MissionStore.Mission mission, String path) {
        try { showCode(path, missionWorkspace(mission).readPreview(path)); }
        catch (IOException e) { toast(e.getMessage()); }
    }

    private void exportMission(MissionStore.Mission mission) {
        pendingMissionExportId = mission.id;
        createExport("text/plain", "KENT-task-" + mission.shortId() + ".txt", REQ_MISSION_EXPORT);
    }

    private void saveMissionReport(Uri target) {
        MissionStore.Mission selected = missionStore.find(pendingMissionExportId);
        if (selected == null) { toast("Задача не найдена"); return; }
        executor.submit(() -> {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                if (out == null) throw new IOException("Не удалось открыть документ.");
                out.write(selected.report().getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> toast("Отчёт задачи сохранён"));
            } catch (IOException e) {
                runOnUiThread(() -> toast("Не удалось сохранить отчёт задачи."));
            }
        });
    }

    private void showMemory() {
        String state = missionStore.loadFailed() ? "\nСостояние задач не удалось прочитать. Доступен экспорт исходного файла." : "";
        new AlertDialog.Builder(this).setTitle("Локальная память")
                .setMessage("Задач: " + missionStore.list().size() + "\nМодель: " + secrets.model() +
                        "\nНа устройстве хранятся чат, задачи, журнал действий и контрольные точки." + state)
                .setNeutralButton("Список задач", (d, w) -> { tab = 4; draw(); })
                .setPositiveButton("Закрыть", null).show();
    }

    private void exportMemory() {
        createExport("application/zip", "KENT-local-memory.zip", REQ_MEMORY_EXPORT);
    }

    private void createExport(String type, String name, int request) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, request);
    }

    private void saveMemory(Uri target) {
        executor.submit(() -> {
            try (java.io.OutputStream output = getContentResolver().openOutputStream(target, "w")) {
                if (output == null) throw new IOException("Не удалось открыть документ.");
                try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output)) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("missions.json"));
                    missionStore.export(zip);
                    zip.closeEntry();
                    zip.putNextEntry(new java.util.zip.ZipEntry("chat.jsonl"));
                    journal.export(zip);
                    zip.closeEntry();
                    zip.putNextEntry(new java.util.zip.ZipEntry("settings.json"));
                    org.json.JSONObject settings = new org.json.JSONObject()
                            .put("provider", secrets.provider())
                            .put("aiFlexModel", secrets.modelFor("ai_flex"))
                            .put("openRouterModel", secrets.modelFor("openrouter"))
                            .put("autopilot", secrets.autopilotEnabled())
                            .put("bridgeEnabled", secrets.bridgeEnabled());
                    zip.write(settings.toString(2).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                runOnUiThread(() -> toast("Локальная память экспортирована."));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Не удалось экспортировать локальную память."));
            }
        });
    }

    private void confirmClearMemory() {
        if (taskRunning()) { toast("Сначала приостановите выполняющуюся задачу."); return; }
        new AlertDialog.Builder(this).setTitle("Очистить память?")
                .setMessage("История чата, журнал задач и счётчики сессий будут удалены с устройства.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить", (d, w) -> {
                    if (taskRunning()) return;
                    try {
                        missionStore.clear();
                        journal.clear();
                        tokenMeter.clear();
                        messages.clear();
                        draw();
                        toast("Локальная память очищена.");
                    } catch (Exception e) { toast("Не удалось полностью очистить память."); }
                }).show();
    }

    private void showQualityReport() {
        String report = missionStore.qualityReport();
        new AlertDialog.Builder(this).setTitle("Качество работы агента")
                .setMessage(report)
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Создать задачу в KENT LAB", (d, w) -> prepareLab(
                        "Проанализируй исходный код KENT CODE по локальному отчёту. Уточни причины, " +
                                "предложи исправления и сценарии проверки.\n\n" + report)).show();
    }

    private void showKentLab() {
        new AlertDialog.Builder(this).setTitle("KENT LAB")
                .setMessage("Создайте отдельную копию исходников из APK. Агент изучит её и предложит правки для проверки.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Создать копию", (d, w) -> prepareLab(
                        "Изучи исходный код KENT CODE. Предложи конкретное улучшение надёжности " +
                                "или производительности, объясни причину и составь план проверки.")).show();
    }

    private void prepareLab(String goal) {
        if (taskRunning()) { toast("Приостановите текущую задачу перед созданием копии."); return; }
        running = true;
        executor.submit(() -> {
            try {
                String id = LabWorkspace.create(getApplicationContext());
                missionStore.create(SensitiveData.redact(goal), secrets.provider(), secrets.model(), id);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    tab = 4;
                    draw();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Не удалось создать копию KENT LAB."));
            } finally {
                runOnUiThread(() -> running = false);
            }
        });
    }

    private void drawSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(dp(12), dp(9), dp(12), dp(18));
        scroll.addView(content);
        mainArea.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        padded(content, text("Провайдер", 13, WHITE, true), 0);
        Spinner providers = new Spinner(this);
        String[] names = {"OpenRouter", "AI Flex"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, names);
        providers.setAdapter(adapter);
        providers.setSelection(secrets.provider().equals("openrouter") ? 0 : 1);
        padded(content, providers, 4);

        padded(content, text("Модель", 13, WHITE, true), 8);
        EditText model = field("Идентификатор модели", secrets.model());
        model.setSingleLine(true);
        padded(content, model, 4);

        LinearLayout presets = new LinearLayout(this);
        addControl(presets, "Быстрая", false, () -> model.setText("deepseek-v4.1-flash-1017"));
        addControl(presets, "Для кода", false, () -> model.setText("claude-sonnet-5-5"));
        addControl(presets, "Мощная", false, () -> model.setText("claude-opus-5-5"));
        padded(content, presets, 5);
        presets.setVisibility(secrets.provider().equals("ai_flex") ? View.VISIBLE : View.GONE);

        padded(content, text("Ключ API", 13, WHITE, true), 10);
        EditText key = field("Пустое поле сохранит текущий ключ", "");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        key.setSingleLine(true);
        key.setSaveEnabled(false);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        padded(content, key, 4);

        providers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String selected = pos == 0 ? "openrouter" : "ai_flex";
                model.setText(secrets.modelFor(selected));
                presets.setVisibility(selected.equals("ai_flex") ? View.VISIBLE : View.GONE);
                key.setText("");
                if (!selected.equals(secrets.provider())) resetCredits();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        CheckBox autopilot = new CheckBox(this);
        autopilot.setText("Автопилот для локальной рабочей папки");
        autopilot.setTextColor(WHITE);
        autopilot.setTextSize(13);
        autopilot.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
        autopilot.setChecked(secrets.autopilotEnabled());
        padded(content, autopilot, 9);
        padded(content, text("В новом запуске автопилот может записывать обычные файлы рабочей папки. " +
                "Удаление, запись после возобновления и изменения KENT LAB требуют подтверждения.",
                12, MUTED, false), 2);

        Button save = button("Сохранить настройки", true);
        padded(content, save, 11);
        save.setOnClickListener(view -> {
            if (taskRunning()) { toast("Сначала приостановите текущую задачу"); return; }
            try {
                String selected = providers.getSelectedItemPosition() == 0 ? "openrouter" : "ai_flex";
                String newModel = model.getText().toString().trim();
                if (newModel.isEmpty()) throw new IllegalArgumentException("Укажите модель");
                String value = key.getText().toString().trim();
                if (!value.isEmpty()) secrets.saveProviderKey(selected, value);
                secrets.provider(selected);
                secrets.model(newModel);
                secrets.autopilotEnabled(autopilot.isChecked());
                resetCredits();
                key.setText("");
                toast("Настройки сохранены");
                tab = 0;
                draw();
            } catch (Exception e) {
                toast("Ошибка настроек: " + e.getMessage());
            }
        });

        Button clearKey = actionButton("Удалить ключ провайдера", () -> {
            if (taskRunning()) { toast("Сначала приостановите текущую задачу"); return; }
            new AlertDialog.Builder(this).setTitle("Удалить ключ выбранного провайдера?")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить", (dialog, which) -> {
                        if (taskRunning()) { toast("Задача уже выполняется"); return; }
                        try {
                            String selected = providers.getSelectedItemPosition() == 0 ? "openrouter" : "ai_flex";
                            secrets.saveProviderKey(selected, "");
                            resetCredits();
                            toast("Ключ удалён");
                        } catch (Exception e) { toast("Не удалось удалить ключ"); }
                    }).show();
        });
        padded(content, clearKey, 6);

        padded(content, actionButton("Баланс на сайте провайдера", this::openCreditSite), 6);
        padded(content, text("Локальные данные", 14, WHITE, true), 16);
        padded(content, actionButton("Память агента", this::showMemory), 5);
        padded(content, actionButton("Экспорт памяти", this::exportMemory), 5);
        padded(content, actionButton("Очистить память задач", this::confirmClearMemory), 5);
        padded(content, actionButton("Экспорт журнала чата", this::exportJournal), 5);
        padded(content, actionButton("Очистить чат", () -> {
            if (taskRunning()) { toast("Сначала приостановите текущую задачу"); return; }
            new AlertDialog.Builder(this).setTitle("Удалить историю чата?")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить", (dialog, which) -> {
                        if (taskRunning()) { toast("Задача уже выполняется"); return; }
                        try {
                            journal.clear();
                            messages.clear();
                            expandedChat.clear();
                            chatAnchor = ChatTimeline.Anchor.latest();
                            chatDraft = "";
                            draftCursor = 0;
                            toast("История чата удалена");
                        } catch (IOException e) { toast("Не удалось очистить журнал"); }
                    }).show();
        }), 5);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        if (!stopRequested.get()) requestStop(MissionStore.PAUSED);
        mainHandler.removeCallbacksAndMessages(null);
        if (githubPanel != null) githubPanel.close();
        executor.shutdown();
        creditExecutor.shutdownNow();
        super.onDestroy();
    }
}
