package ai.kent.code;

/**
 * Validates the shape of an opaque vendor API key before it can be placed into
 * an HTTP Authorization header. Does not make any online authentication claim.
 */
public final class ApiKeyValidator {
    private ApiKeyValidator() {}

    public static void check(String value) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("Нет API-ключа: открой CTRL и введи ключ AI-FLEX / OpenRouter");
        if (value.length() > 2048)
            throw new IllegalArgumentException("Это не похоже на API-ключ. В CTRL вставь только ключ, а задачу отправь в CHAT");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch <= 32 || ch >= 127)
                throw new IllegalArgumentException("API-ключ содержит пробелы, переносы строк или текст задачи. " +
                        "В CTRL вставь только ключ, задания отправляй в CHAT");
        }
    }
}
