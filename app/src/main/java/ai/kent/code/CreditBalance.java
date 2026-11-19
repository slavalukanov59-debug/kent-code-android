package ai.kent.code;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Optional read-only credit lookup. Fail closed; never guess a dollar balance
 * from token use. AI-FLEX does not publish a verified public balance endpoint:
 * try the OpenRouter-compatible route on the SAME AI-FLEX origin only, and
 * show "unavailable" if the provider does not support it.
 */
public final class CreditBalance {
    private CreditBalance() {}

    public static final class Result {
        public final String label;
        public final boolean available;
        public final String details;
        Result(String label, boolean available, String details) {
            this.label = label; this.available = available; this.details = details;
        }
    }

    public static Result query(String provider, String apiKey) {
        String endpoint = "openrouter".equals(provider)
                ? "https://openrouter.ai/api/v1/credits"
                : "https://ai-flex.online/v1/credits";
        try {
            ApiKeyValidator.check(apiKey);
            HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                c.setRequestMethod("GET");
                c.setConnectTimeout(7_000);
                c.setReadTimeout(8_000);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + apiKey);
                if (c.getResponseCode() != 200)
                    return new Result("Баланс: недоступен", false,
                            "Провайдер не предоставил баланс по этому API-ключу.");
                String contentType = c.getContentType();
                if (contentType == null || !contentType.toLowerCase(Locale.US).contains("json"))
                    return new Result("Баланс: недоступен", false,
                            "Провайдер не вернул JSON с кредитами.");
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        if (out.size() + n > 64_000) return new Result(
                                "Баланс: недоступен", false, "Слишком большой ответ API.");
                        out.write(buffer, 0, n);
                    }
                    JSONObject root = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                    JSONObject data = root.optJSONObject("data");
                    if (data == null) data = root;
                    // OpenRouter billing schema: account total_credits minus total_usage.
                    if (data.has("total_credits") && data.has("total_usage")) {
                        double total = data.optDouble("total_credits", Double.NaN);
                        double spent = data.optDouble("total_usage", Double.NaN);
                        if (Double.isFinite(total) && Double.isFinite(spent) &&
                                total >= 0 && spent >= 0)
                            return new Result(String.format(Locale.US, "Кредиты: %.2f", Math.max(0, total - spent)),
                                    true, "Источник: ответ API провайдера");
                    }
                    // Some relay APIs expose a direct numeric credit balance.
                    if (data.has("balance")) {
                        double balance = data.optDouble("balance", Double.NaN);
                        if (Double.isFinite(balance) && balance >= 0)
                            return new Result(String.format(Locale.US, "Кредиты: %.2f", balance),
                                    true, "Источник: ответ API провайдера");
                    }
                    return new Result("Баланс: недоступен", false,
                            "API не вернул подтверждённое поле кредитов.");
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            // Never include the URL, bearer token, response or exception message in UI.
            return new Result("Баланс: недоступен", false,
                    "Не удалось запросить кредитный баланс; проверь его на сайте провайдера.");
        }
    }
}
