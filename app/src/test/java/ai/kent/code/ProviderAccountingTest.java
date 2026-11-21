package ai.kent.code;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public final class ProviderAccountingTest {
    @Test public void aiFlexReadsTheDocumentedRemainingField() throws Exception {
        CreditBalance.Result result = CreditBalance.parse("ai_flex", new JSONObject(
                "{\"remaining\":12.3456,\"used\":3,\"total\":22,\"unit\":\"USD\",\"is_active\":true}"));
        assertTrue(result.available);
        assertEquals("Баланс: 12.3456 USD", result.label);
        assertEquals("Баланс: 0 USD", CreditBalance.parse("ai_flex",
                new JSONObject("{\"remaining\":0}")).label);
    }

    @Test public void aiFlexLeavesUnknownBalancesUnknown() throws Exception {
        for (String payload : new String[]{
                "{\"balance\":400}",
                "{\"total\":20,\"used\":15}",
                "{\"remaining\":3,\"is_active\":false}",
                "{\"remaining\":3,\"unit\":\"request count\"}",
                "{\"remaining\":\"3\"}",
                "{\"remaining\":null}"}) {
            CreditBalance.Result result = CreditBalance.parse("ai_flex", new JSONObject(payload));
            assertFalse(payload, result.available);
            assertEquals(CreditBalance.UNKNOWN_LABEL, result.label);
        }
        assertFalse(CreditBalance.query("invalid-provider", "ignored").available);
    }

    @Test public void openRouterUsesItsOwnCreditSchema() throws Exception {
        CreditBalance.Result result = CreditBalance.parse("openrouter", new JSONObject(
                "{\"data\":{\"total_credits\":50.20,\"total_usage\":47.15}}"));
        assertTrue(result.available);
        assertEquals("Баланс: 3.05 USD", result.label);
        assertFalse(CreditBalance.parse("openrouter", new JSONObject(
                "{\"remaining\":3.05,\"data\":{\"balance\":3.05}}" )).available);
    }

    @Test public void readsReportedTokensAndOnlyDocumentedCosts() throws Exception {
        JSONObject response = new JSONObject("{\"model\":\"provider/model\",\"usage\":{"
                + "\"input_tokens\":8,\"output_tokens\":0,\"cost\":0.000023}}" );
        ProviderUsage router = new ProviderUsage("openrouter", response);
        assertEquals("provider/model", router.model);
        assertEquals(8, router.input);
        assertEquals(0, router.output);
        assertEquals(8, router.total);
        assertEquals(new BigDecimal("0.000023"), router.cost);
        assertNull(new ProviderUsage("ai_flex", response).cost);

        ProviderUsage invalid = new ProviderUsage("openrouter", new JSONObject(
                "{\"model\":null,\"usage\":{\"prompt_tokens\":1.2,\"completion_tokens\":-3,\"cost\":-2}}"));
        assertEquals("", invalid.model);
        assertEquals(-1, invalid.input);
        assertEquals(-1, invalid.output);
        assertEquals(-1, invalid.total);
        assertNull(invalid.cost);
    }

    @Test public void persistsCostAndItsCoverageAcrossRestarts() throws Exception {
        SharedPreferences preferences = inMemoryPreferences();
        TokenMeter meter = new TokenMeter(preferences);
        meter.begin("openrouter", "requested/model");
        TokenMeter.Snapshot first = meter.record(new JSONObject(
                "{\"model\":\"returned/model\",\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"cost\":0}}"));
        assertTrue(first.modelReported);
        assertEquals("returned/model", first.model);
        assertEquals(BigDecimal.ZERO, first.lastRequestCost);
        assertTrue(first.costComplete());
        assertTrue(first.tokenComplete());
        assertTrue(first.inputComplete());
        assertTrue(first.outputComplete());

        meter.record(new JSONObject("{\"usage\":{\"total_tokens\":9,\"cost\":0.0125}}"));
        meter.record(new JSONObject("{}"));
        TokenMeter reopenedMeter = new TokenMeter(preferences);
        reopenedMeter.recoverInterrupted();
        TokenMeter.Snapshot reopened = reopenedMeter.snapshot();
        assertEquals("прервана при закрытии приложения", reopened.status);
        assertEquals("requested/model", reopened.model);
        assertFalse(reopened.modelReported);
        assertEquals(3, reopened.calls);
        assertEquals(2, reopened.tokenCalls);
        assertEquals(12, reopened.used);
        assertEquals(12, reopened.cumulative);
        assertEquals(1, reopened.inputCalls);
        assertEquals(1, reopened.outputCalls);
        assertFalse(reopened.tokenComplete());
        assertFalse(reopened.inputComplete());
        assertFalse(reopened.outputComplete());
        assertEquals(2, reopened.costedCalls);
        assertFalse(reopened.costComplete());
        assertNull(reopened.lastRequestCost);
        assertEquals(new BigDecimal("0.0125"), reopened.sessionCost);

        reopenedMeter.begin("ai_flex", "other/model");
        assertEquals(0, reopenedMeter.snapshot().cumulative);
        assertNull(reopenedMeter.snapshot().sessionCost);
        reopenedMeter.begin("openrouter", "requested/model");
        assertEquals(12, reopenedMeter.snapshot().cumulative);
        assertEquals(0, reopenedMeter.snapshot().used);
    }

    static SharedPreferences inMemoryPreferences() {
        Map<String, Object> values = new HashMap<>();
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getString": case "getInt": case "getLong":
                            return values.getOrDefault(args[0], args[1]);
                        case "edit":
                            return editor(values);
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static SharedPreferences.Editor editor(Map<String, Object> values) {
        Map<String, Object> changes = new HashMap<>();
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "putString": case "putInt": case "putLong":
                            changes.put((String) args[0], args[1]);
                            return proxy;
                        case "remove":
                            changes.put((String) args[0], null);
                            return proxy;
                        case "commit":
                            changes.forEach((key, value) -> {
                                if (value == null) values.remove(key);
                                else values.put(key, value);
                            });
                            return true;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
