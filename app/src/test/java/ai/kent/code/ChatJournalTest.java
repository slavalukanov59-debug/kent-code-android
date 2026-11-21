package ai.kent.code;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatJournalTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test public void recentKeepsOrderAndIdentityAcrossAnIncompleteRecord() throws Exception {
        File root = directory.newFolder();
        File file = new File(root, "kent_chat.jsonl");
        Files.write(file.toPath(), "{\"time\":1,\"title\":\"ТЫ\",\"text\":\"Старое сообщение\"}\n"
                .getBytes(StandardCharsets.UTF_8));
        ChatJournal journal = new ChatJournal(root);
        String legacyId = journal.recent(1).get(0).id;
        ChatJournal.Record first = journal.append("КЕНТ", "Первая строка\nВторая строка");
        Files.write(file.toPath(), "{\"text\":\"оборван".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        ChatJournal.Record second = journal.append("ТЫ", "Продолжить");
        List<ChatJournal.Record> rows = new ChatJournal(root).recent(3);
        assertEquals(3, rows.size());
        assertEquals(legacyId, rows.get(0).id);
        assertEquals(first.id, rows.get(1).id);
        assertEquals("Первая строка\nВторая строка", rows.get(1).text);
        assertEquals(second.id, rows.get(2).id);
        assertEquals(second.id, journal.recent(1).get(0).id);
        assertTrue(journal.recent(0).isEmpty());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        journal.export(output);
        String[] exported = output.toString(StandardCharsets.UTF_8.name()).split("\n");
        assertEquals(3, exported.length);
        assertEquals(second.id, new JSONObject(exported[2]).getString("id"));
        journal.clear();
        assertTrue(journal.recent(10).isEmpty());
    }

    @Test public void displayPreviewBoundDoesNotChangeExportedRecord() throws Exception {
        ChatJournal journal = new ChatJournal(directory.newFolder());
        char[] prefix = new char[32_000];
        Arrays.fill(prefix, 'x');
        String text = new String(prefix) + "конец";
        journal.append("КЕНТ", text);
        assertFalse(journal.recent(1).get(0).text.endsWith("конец"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        journal.export(output);
        assertEquals(text, new JSONObject(output.toString(StandardCharsets.UTF_8.name())).getString("text"));
    }
}
