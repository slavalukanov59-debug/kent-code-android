package ai.kent.code;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Displays persisted terminal outcomes and refreshes while the dialog is visible. */
final class DiagnosticsPanel {
    private final Activity activity;
    private final DiagnosticsStore store;
    private final Consumer<String> cancel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RunAdapter rows = new RunAdapter();
    private final Runnable refresh = this::refresh;
    private TextView empty;
    private ListView list;
    private AlertDialog dialog;

    private DiagnosticsPanel(Activity activity, DiagnosticsStore store, Consumer<String> cancel) {
        this.activity = activity;
        this.store = store;
        this.cancel = cancel;
    }

    static void show(Activity activity, DiagnosticsStore store, Consumer<String> cancel) {
        new DiagnosticsPanel(activity, store, cancel).open();
    }

    private void open() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), dp(6));
        empty = text("Чтение результатов…", 14, MainActivity.MUTED);
        content.addView(empty);
        list = new ListView(activity);
        list.setAdapter(rows);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> showResult(rows.getItem(position)));
        int height = Math.max(140, Math.min(410, activity.getResources().getConfiguration().screenHeightDp / 2));
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)));
        dialog = new AlertDialog.Builder(activity).setTitle("Диагностика").setView(content)
                .setPositiveButton("Закрыть", null).create();
        dialog.setOnDismissListener(window -> {
            main.removeCallbacks(refresh);
            worker.shutdownNow();
        });
        dialog.show();
        refresh();
    }

    private void refresh() {
        if (!dialog.isShowing() || activity.isDestroyed()) return;
        worker.execute(() -> {
            List<DiagnosticsStore.Record> records;
            try {
                records = store.records();
            } catch (RuntimeException failure) {
                main.post(() -> update(null, failure.getMessage()));
                return;
            }
            main.post(() -> update(records, null));
        });
    }

    private void update(List<DiagnosticsStore.Record> records, String error) {
        if (!dialog.isShowing() || activity.isDestroyed()) return;
        if (records == null) {
            empty.setText(error == null || error.isEmpty() ? "Не удалось прочитать результаты." : error);
            rows.update(Collections.emptyList());
        } else {
            empty.setText("Проверок пока нет.");
            rows.update(records);
        }
        main.postDelayed(refresh, 1_200);
    }

    private void showResult(DiagnosticsStore.Record record) {
        StringBuilder details = new StringBuilder(record.command).append('\n')
                .append(record.directory.isEmpty() ? "" : record.directory + "\n")
                .append(status(record));
        if (record.startedAt > 0) details.append("\nЗапуск ").append(date(record.startedAt));
        if (record.finishedAt > 0) details.append("\nОкончание ").append(date(record.finishedAt));
        if (record.mayStillBeRunning) details.append("\nЗавершение процесса Termux не подтверждено.");
        if (!record.detail.trim().isEmpty()) details.append("\n\n").append(record.detail);
        details.append("\n\nstdout\n").append(record.stdout.isEmpty() ? "Нет вывода" : record.stdout);
        details.append("\n\nstderr\n").append(record.stderr.isEmpty() ? "Нет вывода" : record.stderr);
        ScrollView scroll = new ScrollView(activity);
        TextView body = text(details.toString(), 13, MainActivity.WHITE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(14), dp(8), dp(14), dp(8));
        scroll.addView(body);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("Результат проверки")
                .setView(scroll).setPositiveButton("Закрыть", null);
        if (cancel != null && active(record)) {
            builder.setNeutralButton("Остановить", (window, which) -> cancel.accept(record.id));
        }
        builder.show();
    }

    private static boolean active(DiagnosticsStore.Record record) {
        return record.status.equals("RUNNING") || record.status.equals("PREPARING");
    }

    private static String status(DiagnosticsStore.Record record) {
        String state = String.valueOf(record.status);
        String localized = switch (state.toLowerCase(Locale.ROOT)) {
            case "running" -> "Выполняется";
            case "queued", "pending", "preparing" -> "Подготовка запуска";
            case "completed", "exited", "finished" -> "Процесс завершился";
            case "checked" -> "Получены версии инструментов";
            case "timeout", "timed_out" -> "Истекло время ожидания";
            case "cancelled", "canceled" -> "Отменено";
            case "failed", "error" -> "Ошибка запуска или проверки";
            case "rejected" -> "Запуск отклонён";
            case "interrupted" -> "Прервано";
            case "unavailable" -> "Среда недоступна";
            case "unknown" -> "Результат неизвестен";
            default -> state;
        };
        return record.exitCode == null ? localized : localized + " · код " + record.exitCode;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private String date(long epoch) {
        return android.text.format.DateFormat.getTimeFormat(activity).format(new Date(epoch));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private final class RunAdapter extends BaseAdapter {
        private final List<DiagnosticsStore.Record> records = new ArrayList<>();

        void update(List<DiagnosticsStore.Record> next) {
            int oldPosition = list.getFirstVisiblePosition();
            View oldView = list.getChildAt(0);
            int offset = oldView == null ? 0 : oldView.getTop();
            String id = oldPosition < records.size() ? records.get(oldPosition).id : "";
            records.clear();
            records.addAll(next);
            notifyDataSetChanged();
            if (oldPosition == 0 && offset >= 0) return;
            for (int i = 0; i < records.size(); i++) {
                if (records.get(i).id.equals(id)) {
                    list.setSelectionFromTop(i, offset);
                    break;
                }
            }
        }

        @Override public int getCount() { return records.size(); }
        @Override public DiagnosticsStore.Record getItem(int position) { return records.get(position); }
        @Override public long getItemId(int position) { return records.get(position).id.hashCode(); }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int position, View reusable, ViewGroup parent) {
            LinearLayout item;
            if (reusable == null) {
                item = new LinearLayout(activity);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setMinimumHeight(dp(48));
                item.setPadding(dp(8), dp(6), dp(8), dp(6));
                TextView title = text("", 14, MainActivity.WHITE);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                item.addView(title);
                item.addView(text("", 12, MainActivity.MUTED));
            } else {
                item = (LinearLayout) reusable;
            }
            DiagnosticsStore.Record run = getItem(position);
            ((TextView) item.getChildAt(0)).setText(run.command);
            String state = status(run);
            if (run.startedAt > 0) state += " · " + date(run.startedAt);
            ((TextView) item.getChildAt(1)).setText(state);
            return item;
        }
    }
}
