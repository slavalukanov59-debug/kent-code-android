package ai.kent.code;

/**
 * Validates the shape of an opaque vendor API key before it can be placed into
 * an HTTP Authorization header. Does not make any online authentication claim.
 */
public final class ApiKeyValidator {
    private ApiKeyValidator() {}

    public static void check(String value) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("Добавьте ключ API выбранного провайдера в настройках.");
        if (value.length() > 2048)
            throw new IllegalArgumentException("Ключ API превышает допустимую длину.");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch <= 32 || ch >= 127)
                throw new IllegalArgumentException("В ключе API допустимы печатные символы ASCII без пробелов.");
        }
    }
}
