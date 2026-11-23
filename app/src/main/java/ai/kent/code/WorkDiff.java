package ai.kent.code;

import java.io.IOException;
import java.util.Objects;

/** A text diff with the unchanged prefix and suffix reduced to context. */
final class WorkDiff {
    private static final int MAX_PREVIEW_CHARS = 24_000;
    private static final int CONTEXT_LINES = 3;

    private WorkDiff() {}

    static String between(String path, String before, String after) throws IOException {
        return between(path, before, after, false);
    }

    static String preview(String path, String before, String after) throws IOException {
        return between(path, before, after, true);
    }

    private static String between(String path, String before, String after, boolean truncate) throws IOException {
        if (Objects.equals(before, after)) return "";
        String[] oldLines = lines(before);
        String[] newLines = lines(after);
        int prefix = 0;
        while (prefix < Math.min(oldLines.length, newLines.length) &&
                oldLines[prefix].equals(newLines[prefix])) prefix++;
        int oldEnd = oldLines.length;
        int newEnd = newLines.length;
        while (oldEnd > prefix && newEnd > prefix &&
                oldLines[oldEnd - 1].equals(newLines[newEnd - 1])) {
            oldEnd--;
            newEnd--;
        }
        int start = Math.max(0, prefix - CONTEXT_LINES);
        int contextAfter = Math.min(CONTEXT_LINES, oldLines.length - oldEnd);
        int oldCount = oldEnd - start + contextAfter;
        int newCount = newEnd - start + contextAfter;
        StringBuilder diff = new StringBuilder();
        diff.append("--- ").append(before == null ? "/dev/null" : "a/" + path).append('\n');
        diff.append("+++ ").append(after == null ? "/dev/null" : "b/" + path).append('\n');
        diff.append("@@ -").append(oldCount == 0 ? start : start + 1).append(',').append(oldCount)
                .append(" +").append(newCount == 0 ? start : start + 1).append(',').append(newCount)
                .append(" @@\n");
        boolean complete = append(diff, ' ', oldLines, start, prefix)
                && append(diff, '-', oldLines, prefix, oldEnd)
                && append(diff, '+', newLines, prefix, newEnd)
                && append(diff, ' ', oldLines, oldEnd, oldEnd + contextAfter);
        if (!complete) {
            if (!truncate) throw new IOException("Изменение слишком велико для проверки. Разбейте его на части.");
            diff.setLength(Math.min(diff.length(), MAX_PREVIEW_CHARS - 50));
            diff.append("\n[Дальнейшие строки доступны в файле проекта]\n");
        }
        return diff.toString();
    }

    private static String[] lines(String value) {
        return value == null || value.isEmpty() ? new String[0] : value.split("(?<=\\n)(?!\\z)");
    }

    private static boolean append(StringBuilder out, char marker, String[] lines, int from, int to) {
        for (int i = from; i < to; i++) {
            String line = lines[i];
            int available = MAX_PREVIEW_CHARS - out.length() - 1;
            if (available < 1) return false;
            out.append(marker).append(line, 0, Math.min(line.length(), available));
            if (line.length() > available) return false;
            if (!lines[i].endsWith("\n")) out.append("\n\\ No newline at end of file\n");
            if (out.length() > MAX_PREVIEW_CHARS) return false;
        }
        return true;
    }
}
