package ai.kent.code;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistent per-task token meter for the configured client-side budget.
 * This is NOT the AI-FLEX wallet, billing amount, or the model context window.
 * Successful requests use API usage.total_tokens when available; otherwise an estimate.
 */
public final class TokenMeter {
    private static final String NAME = "kent_token_meter";
    private final SharedPreferences prefs;

    public static final class Snapshot {
        public final String provider;
        public final String status;
        public final long used;
        public final long budget;
        public final long input;
        public final long output;
        public final long cumulative;
        public final int calls;
        public final int estimatedCalls;
        public Snapshot(String provider, String status, long used, long budget,
                        long input, long output, long cumulative, int calls, int estimatedCalls) {
            this.provider = provider;
            this.status = status;
            this.used = used;
            this.budget = budget;
            this.input = input;
            this.output = output;
            this.cumulative = cumulative;
            this.calls = calls;
            this.estimatedCalls = estimatedCalls;
        }
        public long remaining() { return Math.max(0L, budget - used); }
        public int permille() {
            return (int) Math.min(1000L, used >= budget ? 1000L : (used * 1000L / Math.max(1L, budget)));
        }
        public boolean approximate() { return estimatedCalls > 0; }
    }

    public TokenMeter(Context context) {
        prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static String cumulativeKey(String provider) {
        return "openrouter".equals(provider) ? "cumulative_openrouter" : "cumulative_ai_flex";
    }

    public synchronized Snapshot snapshot() {
        String provider = prefs.getString("provider", "ai_flex");
        return new Snapshot(provider, prefs.getString("status", "нет задач"),
                prefs.getLong("used", 0), prefs.getLong("budget", 80_000),
                prefs.getLong("input", 0), prefs.getLong("output", 0),
                prefs.getLong(cumulativeKey(provider), 0),
                prefs.getInt("calls", 0), prefs.getInt("estimated_calls", 0));
    }

    /** Called only when a NEW user task is submitted. Does not wipe provider totals. */
    public synchronized void begin(String provider, int budget) {
        prefs.edit()
                .putString("provider", provider)
                .putString("status", "выполняется")
                .putLong("budget", budget)
                .putLong("used", 0).putLong("input", 0).putLong("output", 0)
                .putInt("calls", 0).putInt("estimated_calls", 0)
                .commit();
    }

    /** Called after each successful API response before executing its tool calls. */
    public synchronized Snapshot record(long tokens, long promptTokens,
                                        long completionTokens, boolean estimated) {
        Snapshot prior = snapshot();
        long increment = Math.max(1L, tokens);
        long inputIncrement = Math.max(0L, promptTokens);
        long outputIncrement = Math.max(0L, completionTokens);
        long newUsed = saturatedAdd(prior.used, increment);
        long newTotal = saturatedAdd(prior.cumulative, increment);
        prefs.edit()
                .putLong("used", newUsed)
                .putLong("input", saturatedAdd(prior.input, inputIncrement))
                .putLong("output", saturatedAdd(prior.output, outputIncrement))
                .putLong(cumulativeKey(prior.provider), newTotal)
                .putInt("calls", prior.calls + 1)
                .putInt("estimated_calls", prior.estimatedCalls + (estimated ? 1 : 0))
                .commit();
        return snapshot();
    }

    public synchronized void finish(String status) {
        prefs.edit().putString("status", status).commit();
    }

    /** Android process was killed mid-task: keep last usage without claiming success. */
    public synchronized void recoverInterrupted() {
        if ("выполняется".equals(prefs.getString("status", "")))
            finish("прервана при закрытии приложения");
    }

    private static long saturatedAdd(long a, long b) {
        return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b;
    }
}
