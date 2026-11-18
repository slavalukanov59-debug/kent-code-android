package ai.kent.code;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Durable on-device event log, independent of the model's short working context.
 *  Two private JSONL segments, up to ~10 MB total. Never saved to GitHub.
 */
public final class ChatJournal {
    public static final class Record {
        public final String title;
        public final String text;
        Record(String title, String text) { this.title = title; this.text = text; }
    }
    private static final int SEGMENT_MAX_BYTES = 5_000_000;
    private final File current;
    private final File previous;

    public ChatJournal(Context context) {
        current = new File(context.getFilesDir(), "kent_chat.jsonl");
        previous = new File(context.getFilesDir(), "kent_chat_previous.jsonl");
    }

    public synchronized void append(String title, String text) throws IOException {
        String full = text == null ? "" : text;
        // Keep disk bounded, even if a tool accidentally dumps a huge file.
        if (full.length() > 120_000) full = full.substring(0, 120_000) + "\n[record truncated]";
        JSONObject row = new JSONObject();
        try {
            row.put("time", System.currentTimeMillis());
            row.put("title", title);
            row.put("text", full);
        } catch (Exception e) {
            throw new IOException("Не удалось сериализовать сообщение", e);
        }
        byte[] bytes = (row.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (current.length() + bytes.length > SEGMENT_MAX_BYTES) {
            if (previous.exists() && !previous.delete()) throw new IOException("Не удалось обновить журнал");
            if (current.exists() && !current.renameTo(previous)) throw new IOException("Не удалось повернуть журнал");
        }
        try (FileOutputStream out = new FileOutputStream(current, true)) {
            out.write(bytes);
            out.getFD().sync();
        }
    }

    public synchronized List<Record> recent(int limit) {
        ArrayDeque<Record> queue = new ArrayDeque<>();
        load(previous, queue, Math.max(1, limit));
        load(current, queue, Math.max(1, limit));
        return new ArrayList<>(queue);
    }

    private static void load(File file, ArrayDeque<Record> result, int limit) {
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject obj = new JSONObject(line);
                    String text = obj.optString("text", "");
                    if (text.length() > 9000)
                        text = text.substring(0, 9000) + "\\n[На экране сокращено; полный текст в экспортируемом журнале]";
                    result.addLast(new Record(obj.optString("title", "КЕНТ"), text));
                    while (result.size() > limit) result.removeFirst();
                } catch (Exception ignored) {
                    // Incomplete tail after a killed process must not destroy older records.
                }
            }
        } catch (IOException ignored) {
            // UI can still run even when journal cannot be read.
        }
    }

    public synchronized void export(OutputStream output) throws IOException {
        copy(previous, output);
        copy(current, output);
        output.flush();
    }

    private static void copy(File file, OutputStream output) throws IOException {
        if (!file.isFile()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[8192];
            int n;
            while ((n = in.read(bytes)) > 0) output.write(bytes, 0, n);
        }
    }

    public synchronized void clear() throws IOException {
        if (current.exists() && !current.delete()) throw new IOException("Не удалось очистить журнал");
        if (previous.exists() && !previous.delete()) throw new IOException("Не удалось очистить старый журнал");
    }
}
