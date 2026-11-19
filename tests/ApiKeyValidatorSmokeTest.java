package ai.kent.code;

/** Regression test: multiline chat instructions must never be HTTP credentials. */
public final class ApiKeyValidatorSmokeTest {
    public static void main(String[] args) {
        ApiKeyValidator.check("sk-valid-example-1234");
        ApiKeyValidator.check("token_0123456789-._~");
        reject(null);
        reject("");
        reject("   ");
        reject("Bearer sk-example");
        reject("KENT EVOLUTION — SELF IMPROVEMENT 0.6\nРОЛЬ\nТы — автономный разработчик");
        reject("sk-a\nb");
        reject("sk-a\rb");
        reject("sk-a\tb");
        reject("sk-key-with-space ");
        reject("sk-nonASCII-é");
        reject("x".repeat(2049));
        System.out.println("ApiKeyValidatorSmokeTest OK");
    }

    private static void reject(String bad) {
        try {
            ApiKeyValidator.check(bad);
            throw new AssertionError("Invalid API key was accepted");
        } catch (IllegalArgumentException expected) {
            // Never include the actual invalid value in logs.
        }
    }
}
