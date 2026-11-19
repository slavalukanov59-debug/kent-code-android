package ai.kent.code;

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
}
