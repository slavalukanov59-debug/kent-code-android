package ai.kent.code;

import java.util.regex.Pattern;

/** Recognize common credential formats before model context or persistent diagnostics. */
final class SensitiveData {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?im)-----BEGIN [^\\r\\n-]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^\\r\\n-]*PRIVATE KEY-----|\\z)"
            + "|\\b(?:sk-(?:proj-|or-v1-)?|gh[pousr]_|github_pat_)[a-z0-9_\\-]{16,}"
            + "|\\b(?:authorization\\s*[:=]\\s*)?bearer[ \\t]+[a-z0-9._~+/-]{16,}={0,2}"
            + "|\\b(?:api[_-]?key|access[_-]?token|client[_-]?secret|password)\\s*[:=]\\s*[\\\"']?[a-z0-9._~+/-]{16,}");

    private SensitiveData() {}

    static String redact(String text) {
        return CREDENTIAL.matcher(text).replaceAll("[секрет скрыт]");
    }

    static String redact(String text, String activeKey) {
        if (activeKey != null && activeKey.length() >= 8) text = text.replace(activeKey, "[секрет скрыт]");
        return redact(text);
    }
}
