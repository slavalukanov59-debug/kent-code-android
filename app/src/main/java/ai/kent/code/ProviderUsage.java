package ai.kent.code;

import org.json.JSONObject;

import java.math.BigDecimal;

final class ProviderUsage {
    private final JSONObject usage;
    final String model;
    final long input;
    final long output;
    final long total;
    final BigDecimal cost;

    ProviderUsage(String provider, JSONObject response) {
        Object reportedModel = response.opt("model");
        model = reportedModel instanceof String && !((String) reportedModel).trim().isEmpty()
                && ((String) reportedModel).length() <= 200 ? (String) reportedModel : "";
        usage = response.optJSONObject("usage");
        input = count("prompt_tokens", "input_tokens");
        output = count("completion_tokens", "output_tokens");
        long reportedTotal = count("total_tokens");
        total = reportedTotal >= 0 ? reportedTotal : subtotal(input, output);
        // OpenRouter documents usage.cost for Chat Completions. AI-FLEX documents
        // this field only for its Alpha Decisions API, which this app does not call.
        BigDecimal reportedCost = usage == null || !"openrouter".equals(provider)
                ? null : amount(usage.opt("cost"));
        cost = reportedCost != null && reportedCost.signum() >= 0 ? reportedCost : null;
    }

    private long count(String... fields) {
        if (usage == null) return -1;
        for (String field : fields) {
            BigDecimal value = amount(usage.opt(field));
            if (value == null || value.signum() < 0) continue;
            try {
                return value.longValueExact();
            } catch (ArithmeticException invalidCount) {
                // A second provider field can still report a valid count.
            }
        }
        return -1;
    }

    private static long subtotal(long input, long output) {
        if (input < 0 || output < 0 || input > Long.MAX_VALUE - output) return -1;
        return input + output;
    }

    static BigDecimal amount(Object field) {
        if (!(field instanceof Number)) return null;
        try {
            BigDecimal value = new BigDecimal(field.toString());
            if (value.scale() < -100 || value.scale() > 100 || value.precision() > 100) return null;
            return value.stripTrailingZeros();
        } catch (NumberFormatException invalidAmount) {
            return null;
        }
    }
}
