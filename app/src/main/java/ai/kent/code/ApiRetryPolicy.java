package ai.kent.code;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Conservative retry policy for transient HTTP errors.
 * A timed-out POST is NOT retried automatically: it might have reached
 * the provider and already incurred billing.
 */
public final class ApiRetryPolicy {
    private ApiRetryPolicy() {}

    public static final int MAX_ATTEMPTS = 2;

    public static boolean isTransient(int status) {
        return status == 429 || status == 500 || status == 502 ||
                status == 503 || status == 504 || status == 529;
    }

    public static long waitMillis(int attemptNumber) {
        return Math.min(10_000L, 2000L * Math.max(1, attemptNumber));
    }

    static long retryAfterMillis(String header, long now) {
        if (header == null || header.length() > 128) return 0;
        String value = header.trim();
        try {
            return Math.max(0L, Math.multiplyExact(Long.parseLong(value), 1000L));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        } catch (NumberFormatException ignored) {
            try {
                return Math.max(0L, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - now);
            } catch (java.time.DateTimeException e) {
                return 0;
            }
        }
    }

    static boolean shouldRetry(int status, String code) {
        return !billing(code) && isTransient(status);
    }

    static String description(int status, String code) {
        if (status == 402 || billing(code)) return "Лимит оплаты или кредиты исчерпаны. Проверьте аккаунт провайдера.";
        if (status == 401 || status == 403) return "Проверьте API-ключ и доступ к выбранной модели (HTTP " + status + ").";
        if (status == 429) return "Провайдер ограничил частоту запросов (HTTP 429). Продолжите позже.";
        if (status == 400 || status == 404 || status == 422) return "Проверьте модель и формат запроса (HTTP " + status + ").";
        if (status >= 300 && status < 400) return "Провайдер вернул перенаправление (HTTP " + status + ").";
        if (status == 200) return "Провайдер вернул ошибку в теле ответа.";
        return "Сбой запроса к провайдеру (HTTP " + status + ").";
    }

    private static boolean billing(String code) {
        String value = code == null ? "" : code.toLowerCase(Locale.ROOT);
        return value.equals("402") || value.contains("insufficient_quota") ||
                value.contains("insufficient_credit") || value.contains("billing") ||
                value.equals("payment_required");
    }
}
