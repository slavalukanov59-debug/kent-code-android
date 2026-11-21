package ai.kent.code;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** On-device chat history stored in two private JSONL segments. */
public final class ChatJournal {
    public static final class Record {
        public final String id, title, text;
        public final long time;

        private Record(String id, long time, String title, String text) {
            this.id = id;
            this.time = time;
            this.title = title;
            this.text = text;
        }
    }

    private static final int SEGMENT_MAX_BYTES = 5_000_000;
    private static final int MAX_RECORD_CHARS = 120_000;
    private static final int PREVIEW_CHARS = 32_000;
    private static final int MAX_ROW_BYTES = MAX_RECORD_CHARS * 6 + 4_096;
    private final File current;
    private final File previous;

    public ChatJournal(Context context) { this(context.getFilesDir()); }

    ChatJournal(File directory) {
        current = new File(directory, "kent_chat.jsonl");
        previous = new File(directory, "kent_chat_previous.jsonl");
    }

    public synchronized Record append(String title, String text) throws IOException {
        String full = text == null ? "" : text;
        full = limit(full, MAX_RECORD_CHARS, "\n[Запись сокращена до предела журнала]");
        String author = limit(title == null ? "КЕНТ" : title, 256, "…");
        Record record = new Record(UUID.randomUUID().toString(), System.currentTimeMillis(), author, full);
        JSONObject row = new JSONObject();
        try {
            row.put("id", record.id);
            row.put("time", record.time);
            row.put("title", record.title);
            row.put("text", record.text);
        } catch (JSONException e) {
            throw new IOException("Не удалось сохранить сообщение", e);
        }
        byte[] bytes = (row + "\n").getBytes(StandardCharsets.UTF_8);
        if (current.isFile() && current.length() + bytes.length + 1 > SEGMENT_MAX_BYTES) rotate();
        try (RandomAccessFile data = new RandomAccessFile(current, "rw")) {
            long end = data.length();
            if (end > 0) {
                data.seek(end - 1);
                if (data.read() != '\n') data.write('\n');
            }
            data.write(bytes);
            data.getFD().sync();
        }
        return record;
    }

    private void rotate() throws IOException {
        try {
            Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String limit(String text, int length, String suffix) {
        if (text.length() <= length) return text;
        int end = Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end) + suffix;
    }

    public synchronized List<Record> recent(int limit) {
        ArrayDeque<Record> records = new ArrayDeque<>();
        if (limit > 0) {
            readTail(current, records, limit);
            readTail(previous, records, limit);
        }
        return new ArrayList<>(records);
    }

    private static void readTail(File file, ArrayDeque<Record> records, int limit) {
        if (!file.isFile() || records.size() >= limit) return;
        try (RandomAccessFile data = new RandomAccessFile(file, "r")) {
            byte[] chunk = new byte[8192];
            ByteArrayOutputStream row = new ByteArrayOutputStream();
            long end = data.length();
            boolean oversized = false;
            while (end > 0 && records.size() < limit) {
                int size = (int) Math.min(end, chunk.length);
                end -= size;
                data.seek(end);
                data.readFully(chunk, 0, size);
                for (int i = size - 1; i >= 0 && records.size() < limit; i--) {
                    if (chunk[i] == '\n') {
                        if (!oversized) addRow(row, end + i + 1, records);
                        row.reset();
                        oversized = false;
                    } else if (row.size() < MAX_ROW_BYTES) {
                        row.write(chunk[i]);
                    } else {
                        oversized = true;
                    }
                }
            }
            if (end == 0 && records.size() < limit && !oversized) addRow(row, 0, records);
        } catch (IOException ignored) {
            // Keep readable records on an I/O error.
        }
    }

    private static void addRow(ByteArrayOutputStream row, long offset, ArrayDeque<Record> records) {
        if (row.size() == 0) return;
        byte[] bytes = row.toByteArray();
        for (int left = 0, right = bytes.length - 1; left < right; left++, right--) {
            byte value = bytes[left];
            bytes[left] = bytes[right];
            bytes[right] = value;
        }
        String line = new String(bytes, StandardCharsets.UTF_8);
        try {
            JSONObject data = new JSONObject(line);
            String id = data.optString("id", "");
            if (id.isEmpty()) id = UUID.nameUUIDFromBytes((offset + "\n" + line).getBytes(StandardCharsets.UTF_8)).toString();
            String preview = limit(data.optString("text", ""), PREVIEW_CHARS,
                    "\n[Просмотр сокращён. Полный текст доступен в экспорте]");
            records.addFirst(new Record(id, data.optLong("time"), data.optString("title", "КЕНТ"), preview));
        } catch (JSONException ignored) {
            // A process can end in the middle of one record.
        }
    }

    public synchronized void export(OutputStream output) throws IOException {
        copyRecords(previous, output);
        copyRecords(current, output);
        output.flush();
    }

    private static void copyRecords(File file, OutputStream output) throws IOException {
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject data = new JSONObject(line);
                    output.write((data + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (JSONException ignored) {
                    // Keep the exported JSONL readable after an interrupted write.
                }
            }
        }
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(previous.toPath());
        Files.deleteIfExists(current.toPath());
    }
}
