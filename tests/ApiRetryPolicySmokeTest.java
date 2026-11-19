package ai.kent.code;

public final class ApiRetryPolicySmokeTest {
    public static void main(String[] args) {
        for (int status : new int[]{429, 500, 502, 503, 504, 529}) {
            if (!ApiRetryPolicy.isTransient(status))
                throw new AssertionError("Should retry HTTP " + status);
        }
        for (int status : new int[]{200, 400, 401, 402, 403, 404, 422}) {
            if (ApiRetryPolicy.isTransient(status))
                throw new AssertionError("Must not retry HTTP " + status);
        }
        if (ApiRetryPolicy.MAX_ATTEMPTS != 2)
            throw new AssertionError("Retry count must remain bounded");
        if (ApiRetryPolicy.waitMillis(1) < 1000 ||
                ApiRetryPolicy.waitMillis(1) > 3000)
            throw new AssertionError("Unexpected backoff");
        System.out.println("ApiRetryPolicySmokeTest OK");
    }
}
