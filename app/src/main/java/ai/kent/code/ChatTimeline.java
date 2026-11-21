package ai.kent.code;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recycles message rows and retains the visible message when the transcript grows. */
final class ChatTimeline extends ListView {
    private static final Pattern CODE_FENCE = Pattern.compile(
            "(?ms)^ {0,3}```[^`\\r\\n]*\\r?\\n(.*?)(?:^ {0,3}```[\\t ]*(?:\\r?\\n|\\z)|\\z)");

    static final class Message {
        final String id;
        final String title;
        final String text;

        Message(String title, String text) {
            this(UUID.randomUUID().toString(), title, text);
        }

        Message(String id, String title, String text) {
            this.id = id;
            this.title = title;
            this.text = text;
        }

        boolean sentByUser() {
            return title.equals("ТЫ") || title.startsWith("ТЫ · ");
        }
    }

    static final class Anchor {
        final String id;
        final int position;
        final int offset;
        final boolean latest;

        private Anchor(String id, int position, int offset, boolean latest) {
            this.id = id;
            this.position = position;
            this.offset = offset;
            this.latest = latest;
        }

        static Anchor latest() {
            return new Anchor("", 0, 0, true);
        }

        Bundle asBundle() {
            Bundle saved = new Bundle();
            saved.putString("message", id);
            saved.putInt("position", position);
            saved.putInt("offset", offset);
            saved.putBoolean("latest", latest);
            return saved;
        }

        static Anchor fromBundle(Bundle saved) {
            if (saved == null) return latest();
            return new Anchor(saved.getString("message", ""), saved.getInt("position"),
                    saved.getInt("offset"), saved.getBoolean("latest", true));
        }
    }

    private final MessageAdapter rows;
    private Anchor waitingForLayout;
    private boolean pinnedToLatest = true;
    private boolean userScrolling;

    ChatTimeline(Context context, Set<String> expanded) {
        super(context);
        setDivider(null);
        setSelector(android.R.color.transparent);
        setPadding(dp(10), dp(5), dp(10), dp(4));
        setClipToPadding(false);
        setItemsCanFocus(true);
        setTranscriptMode(TRANSCRIPT_MODE_DISABLED);
        setVerticalScrollBarEnabled(true);
        rows = new MessageAdapter(expanded);
        setAdapter(rows);
        setOnScrollListener(new OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (waitingForLayout != null) return;
                if (state != SCROLL_STATE_IDLE) {
                    userScrolling = true;
                    pinnedToLatest = false;
                } else if (userScrolling) {
                    userScrolling = false;
                    pinnedToLatest = atBottom(getHeight());
                }
            }

            @Override public void onScroll(AbsListView view, int first, int visible, int count) { }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    Anchor anchor() {
        return waitingForLayout != null ? waitingForLayout : captureAnchor(getHeight(), true);
    }

    private Anchor captureAnchor(int viewportHeight, boolean followLatest) {
        int first = getFirstVisiblePosition();
        int children = getChildCount();
        if (children == 0 || first >= rows.getCount()) return Anchor.latest();
        if (followLatest && (pinnedToLatest || atBottom(viewportHeight))) return Anchor.latest();
        return new Anchor(rows.getItem(first).id, first, getChildAt(0).getTop() - getListPaddingTop(), false);
    }

    private boolean atBottom(int viewportHeight) {
        int children = getChildCount();
        return children == 0 || (getLastVisiblePosition() == rows.getCount() - 1
                && getChildAt(children - 1).getBottom() <= viewportHeight - getListPaddingBottom() + dp(24));
    }

    void setMessages(List<Message> messages, Anchor position) {
        rows.messages.clear();
        rows.messages.addAll(messages);
        rows.notifyDataSetChanged();
        restore(position);
    }

    void scrollToLatest() {
        restore(Anchor.latest());
    }

    private void restore(Anchor anchor) {
        waitingForLayout = anchor;
        pinnedToLatest = anchor.latest;
        if (rows.getCount() == 0) return;
        if (anchor.latest) {
            setSelection(rows.getCount() - 1);
            return;
        }
        int fallback = Math.min(anchor.position, rows.getCount() - 1);
        for (int i = 0; i < rows.getCount(); i++) {
            if (rows.getItem(i).id.equals(anchor.id)) {
                setSelectionFromTop(i, anchor.offset);
                return;
            }
        }
        setSelectionFromTop(Math.max(0, fallback), 0);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        Anchor beforeResize = waitingForLayout;
        if (beforeResize == null && oldHeight > 0 && rows != null) {
            beforeResize = captureAnchor(oldHeight, true);
        }
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (beforeResize != null) restore(beforeResize);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        waitingForLayout = null;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private GradientDrawable panel(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(4));
        background.setStroke(dp(1), MainActivity.OUTLINE);
        return background;
    }

    private final class MessageAdapter extends BaseAdapter {
        final List<Message> messages = new ArrayList<>();
        final Set<String> expanded;

        MessageAdapter(Set<String> expanded) {
            this.expanded = expanded;
        }

        @Override public int getCount() { return messages.size(); }
        @Override public Message getItem(int position) { return messages.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            MessageRow row = convertView instanceof MessageRow
                    ? (MessageRow) convertView : new MessageRow();
            row.bind(getItem(position));
            return row;
        }
    }

    private final class MessageRow extends LinearLayout {
        private final LinearLayout card;
        private final LinearLayout contents;
        private final TextView title;
        private final Button expand;
        private Message bound;
        private boolean showingExpanded;

        MessageRow() {
            super(ChatTimeline.this.getContext());
            setOrientation(VERTICAL);
            card = new LinearLayout(getContext());
            card.setOrientation(VERTICAL);
            card.setPadding(dp(10), dp(7), dp(10), dp(5));
            addView(card, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            title = label("", 11, MainActivity.BLUE, true);
            card.addView(title);
            contents = new LinearLayout(getContext());
            contents.setOrientation(VERTICAL);
            contents.setPadding(0, dp(4), 0, 0);
            card.addView(contents);

            expand = new Button(getContext());
            expand.setAllCaps(false);
            expand.setMinHeight(dp(44));
            expand.setPadding(dp(4), 0, dp(4), 0);
            expand.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            expand.setTextColor(MainActivity.BLUE);
            expand.setTextSize(12);
            expand.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            card.addView(expand);
        }

        void bind(Message message) {
            boolean expanded = rows.expanded.contains(message.id);
            if (message == bound && expanded == showingExpanded) return;
            bound = message;
            showingExpanded = expanded;
            setPadding(message.sentByUser() ? dp(14) : 0, 0,
                    message.sentByUser() ? 0 : dp(9), dp(6));
            card.setBackground(panel(message.sentByUser() ? MainActivity.PANEL_2 : MainActivity.PANEL));
            title.setText(message.title);
            title.setTextColor(message.title.startsWith("Ошибка") ? MainActivity.GOLD : MainActivity.BLUE);
            int previewLimit = message.sentByUser() ? 420 : 1000;
            boolean canExpand = message.text.length() > previewLimit;
            String visible = canExpand && !expanded
                    ? message.text.substring(0, previewLimit) + "\n…" : message.text;

            contents.removeAllViews();
            addBlocks(visible);
            expand.setVisibility(canExpand ? VISIBLE : GONE);
            expand.setText(expanded ? "Свернуть" : "Развернуть сообщение");
            expand.setOnClickListener(view -> {
                Anchor previous = captureAnchor(ChatTimeline.this.getHeight(), false);
                if (!rows.expanded.add(message.id)) rows.expanded.remove(message.id);
                bind(message);
                restore(previous);
            });
        }

        private void addBlocks(String message) {
            Matcher code = CODE_FENCE.matcher(message);
            int processed = 0;
            while (code.find()) {
                addText(message.substring(processed, code.start()));
                addCode(code.group(1));
                processed = code.end();
            }
            addText(message.substring(processed));
        }

        private void addText(String paragraph) {
            String stripped = paragraph.trim();
            if (stripped.isEmpty()) return;
            TextView body = label(stripped, 14, MainActivity.WHITE, false);
            body.setTextIsSelectable(true);
            contents.addView(body);
        }

        private void addCode(String code) {
            HorizontalScrollView horizontal = new HorizontalScrollView(getContext());
            horizontal.setFillViewport(true);
            horizontal.setBackground(panel(MainActivity.BG));
            horizontal.setContentDescription("Блок кода, горизонтальная прокрутка");
            TextView body = label(code, 12, MainActivity.WHITE, false);
            body.setPadding(dp(9), dp(6), dp(9), dp(6));
            body.setTextIsSelectable(true);
            body.setHorizontallyScrolling(true);
            body.setTextDirection(View.TEXT_DIRECTION_LTR);
            horizontal.addView(body);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(4);
            params.bottomMargin = dp(4);
            contents.addView(horizontal, params);
        }
    }
}
