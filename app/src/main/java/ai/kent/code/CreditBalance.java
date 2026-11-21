package ai.kent.code;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Read-only credit balances with a provider-specific response schema. */
public final class CreditBalance {
    public static final String UNKNOWN_LABEL = "БАЛАНС: НЕИЗВЕСТНО";
    private static final int MAX_RESPONSE_BYTES = 64_000;

    private CreditBalance() {}

    public static final class Result {
        public final String label;
        public final boolean available;
        public final String details;

        Result(String label, boolean available, String details) {
            this.label = label;
            this.available = available;
            this.details = details;
        }
    }

    public static Result query(String provider, String apiKey) {
        String endpoint;
        if ("ai_flex".equals(provider)) {
            // The provider's CCSwitch example authenticates this endpoint with the inference key.
            endpoint = "https://ai-flex.online/v1/usage";
        } else if ("openrouter".equals(provider)) {
            endpoint = "https://openrouter.ai/api/v1/credits";
        } else {
            return unavailable("Провайдер баланса не поддерживается.");
        }
        try {
            ApiKeyValidator.check(apiKey);
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(7_000);
                connection.setReadTimeout(8_000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                int status = connection.getResponseCode();
                if (status == 401 || status == 403) {
                    return unavailable("openrouter".equals(provider)
                            ? "API баланса OpenRouter требует ключ управления. Баланс доступен в кабинете."
                            : "AI-FLEX не предоставил баланс для этого API-ключа.");
                }
                if (status != 200) return unavailable("API баланса вернул HTTP " + status + ".");
                String contentType = connection.getContentType();
                if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("json"))
                    return unavailable("Провайдер не вернул JSON с балансом.");
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        if (output.size() + size > MAX_RESPONSE_BYTES)
                            return unavailable("Ответ API баланса превышает допустимый размер.");
                        output.write(buffer, 0, size);
                    }
                    return parse(provider, new JSONObject(output.toString(StandardCharsets.UTF_8.name())));
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception failure) {
            return unavailable("Не удалось получить баланс. Проверьте ключ и кабинет провайдера.");
        }
    }

    static Result parse(String provider, JSONObject data) {
        if ("ai_flex".equals(provider)) {
            if (Boolean.FALSE.equals(data.opt("is_active")))
                return unavailable("AI-FLEX сообщает, что доступ по ключу неактивен.");
            BigDecimal remaining = ProviderUsage.amount(data.opt("remaining"));
            String unit = data.optString("unit", "USD");
            if (remaining != null && unit.matches("[A-Z]{3}"))
                return available(remaining, unit, "AI-FLEX /v1/usage");
        }
        if ("openrouter".equals(provider)) {
            JSONObject credits = data.optJSONObject("data");
            if (credits != null) {
                BigDecimal purchased = ProviderUsage.amount(credits.opt("total_credits"));
                BigDecimal used = ProviderUsage.amount(credits.opt("total_usage"));
                if (purchased != null && used != null && purchased.signum() >= 0 && used.signum() >= 0)
                    return available(purchased.subtract(used), "USD", "OpenRouter /api/v1/credits");
            }
        }
        return unavailable("API не вернул подтверждённые данные о балансе.");
    }

    private static Result available(BigDecimal remaining, String unit, String source) {
        String amount = remaining.stripTrailingZeros().toPlainString();
        return new Result("Баланс: " + amount + " " + unit, true, source);
    }

    private static Result unavailable(String details) {
        return new Result(UNKNOWN_LABEL, false, details);
    }
}
