package ai.kent.code;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.math.BigDecimal;

/** Locally persisted accounting for reported usage in the current run. */
public final class TokenMeter {
    private static final String NAME = "kent_token_meter";
    private final SharedPreferences prefs;

    public static final class Snapshot {
        public final String provider;
        public final String status;
        public final String model;
        public final boolean modelReported;
        public final long used;
        public final long input;
        public final long output;
        public final long cumulative;
        public final int calls;
        public final int tokenCalls;
        public final int inputCalls;
        public final int outputCalls;
        public final int costedCalls;
        public final BigDecimal lastRequestCost;
        public final BigDecimal sessionCost;
        private final boolean hasLegacyEstimates;

        private Snapshot(SharedPreferences prefs) {
            provider = prefs.getString("provider", "ai_flex");
            status = prefs.getString("status", "нет задач");
            String reportedModel = prefs.getString("reported_model", "");
            modelReported = !reportedModel.isEmpty();
            model = modelReported ? reportedModel : prefs.getString("requested_model", "");
            used = prefs.getLong("confirmed_used", 0);
            input = prefs.getLong("confirmed_input", 0);
            output = prefs.getLong("confirmed_output", 0);
            cumulative = prefs.getLong(cumulativeKey(provider), 0);
            calls = prefs.getInt("calls", 0);
            tokenCalls = prefs.getInt("token_calls", 0);
            inputCalls = prefs.getInt("input_calls", 0);
            outputCalls = prefs.getInt("output_calls", 0);
            costedCalls = prefs.getInt("costed_calls", 0);
            lastRequestCost = savedAmount(prefs, "last_request_cost");
            sessionCost = savedAmount(prefs, "session_cost");
            hasLegacyEstimates = prefs.getInt("estimated_calls", 0) > 0;
        }

        public boolean tokenComplete() { return calls > 0 && calls == tokenCalls; }
        public boolean inputComplete() { return calls > 0 && calls == inputCalls; }
        public boolean outputComplete() { return calls > 0 && calls == outputCalls; }
        public boolean costComplete() { return calls > 0 && calls == costedCalls; }
        public boolean approximate() { return hasLegacyEstimates; }
    }

    public TokenMeter(Context context) {
        this(context.getSharedPreferences(NAME, Context.MODE_PRIVATE));
    }

    TokenMeter(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    private static String cumulativeKey(String provider) {
        // The older counters mixed provider totals with estimates.
        return "confirmed_cumulative_" + provider;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(prefs);
    }

    public synchronized void begin(String provider, String model) {
        prefs.edit()
                .putString("provider", provider)
                .putString("status", "выполняется")
                .putString("requested_model", model)
                .remove("reported_model")
                .putLong("confirmed_used", 0)
                .putLong("confirmed_input", 0)
                .putLong("confirmed_output", 0)
                .putInt("calls", 0)
                .putInt("token_calls", 0)
                .putInt("input_calls", 0)
                .putInt("output_calls", 0)
                .putInt("costed_calls", 0)
                .remove("estimated_calls")
                .remove("last_request_cost")
                .remove("session_cost")
                .commit();
    }

    /** Called for a successful response before executing the returned tool calls. */
    public synchronized Snapshot record(JSONObject response) {
        Snapshot prior = snapshot();
        ProviderUsage usage = new ProviderUsage(prior.provider, response);
        SharedPreferences.Editor edit = prefs.edit()
                .putString("reported_model", usage.model)
                .putInt("calls", increment(prior.calls))
                .remove("last_request_cost");
        if (usage.total >= 0) {
            edit.putLong("confirmed_used", saturatedAdd(prior.used, usage.total))
                    .putLong(cumulativeKey(prior.provider), saturatedAdd(prior.cumulative, usage.total))
                    .putInt("token_calls", increment(prior.tokenCalls));
        }
        if (usage.input >= 0) {
            edit.putLong("confirmed_input", saturatedAdd(prior.input, usage.input))
                    .putInt("input_calls", increment(prior.inputCalls));
        }
        if (usage.output >= 0) {
            edit.putLong("confirmed_output", saturatedAdd(prior.output, usage.output))
                    .putInt("output_calls", increment(prior.outputCalls));
        }
        if (usage.cost != null) {
            BigDecimal session = prior.sessionCost == null ? usage.cost : prior.sessionCost.add(usage.cost);
            edit.putString("last_request_cost", usage.cost.toPlainString())
                    .putString("session_cost", session.toPlainString())
                    .putInt("costed_calls", increment(prior.costedCalls));
        }
        edit.commit();
        return snapshot();
    }

    public synchronized void finish(String status) {
        prefs.edit().putString("status", status).commit();
    }

    /** Android process was killed mid-task. Keep the latest reported usage. */
    public synchronized void clear() {
        if (!prefs.edit().clear().commit()) throw new IllegalStateException("Не удалось очистить счётчики.");
    }

    public synchronized void recoverInterrupted() {
        if ("выполняется".equals(prefs.getString("status", "")))
            finish("прервана при закрытии приложения");
    }

    private static BigDecimal savedAmount(SharedPreferences prefs, String key) {
        String value = prefs.getString(key, null);
        if (value == null) return null;
        try {
            BigDecimal amount = new BigDecimal(value);
            return amount.signum() < 0 ? null : amount.stripTrailingZeros();
        } catch (NumberFormatException invalidAmount) {
            return null;
        }
    }

    private static int increment(int count) {
        return count == Integer.MAX_VALUE ? count : count + 1;
    }

    private static long saturatedAdd(long a, long b) {
        return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b;
    }
}
