package ai.kent.code;

import org.junit.Test;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import static ai.kent.code.LocalTermuxBridge.Health.Status;
import static org.junit.Assert.*;

public class HeadlessTermuxTest {
    @Test public void checkingAnOfflineBridgeNeverStartsIt() {
        Platform platform = new Platform();
        HeadlessTermux termux = new HeadlessTermux(platform);
        termux.setAppVisible(true);
        termux.setAutoStartEnabled(true);
        assertTrue(termux.autoStartEnabled());
        assertEquals(Status.OFFLINE, termux.check(() -> false).localStatus);
        assertEquals(Status.OFFLINE, termux.connect(false, () -> false).localStatus);
        assertEquals(0, platform.starts);
    }

    @Test public void acceptsTheExistingAuthenticatedBridgeWithoutOfficialPermission() {
        Platform platform = new Platform();
        platform.capabilities = new HeadlessTermux.Capabilities(true, true, false, false, true);
        HeadlessTermux termux = new HeadlessTermux(platform);
        for (Status live : new Status[]{Status.READY, Status.LEGACY}) {
            platform.health = live;
            HeadlessTermux.Status result = termux.connect(true, () -> false);
            assertEquals(live, result.localStatus);
            assertTrue(result.localReady());
            assertFalse(result.officialAvailable());
        }
        assertEquals(0, platform.starts);
    }

    @Test public void blocksLaunchWhenHiddenCancelledOrTheEndpointIsUnverified() {
        Platform platform = new Platform();
        HeadlessTermux termux = new HeadlessTermux(platform);
        assertEquals(Status.OFFLINE, termux.connect(true, () -> false).localStatus);
        termux.setAppVisible(true);
        assertEquals(Status.CANCELLED, termux.connect(true, () -> true).localStatus);
        for (Status unavailable : new Status[]{Status.AUTH_FAILED, Status.TIMEOUT, Status.INCOMPATIBLE,
                Status.UNAVAILABLE}) {
            platform.health = unavailable;
            assertEquals(unavailable, termux.connect(true, () -> false).localStatus);
        }
        assertEquals(0, platform.starts);
    }

    @Test public void requiresAnAuthenticatedReceiptAndRecoversAfterBridgeDies() {
        Platform platform = new Platform();
        platform.becomeReady = true;
        HeadlessTermux termux = new HeadlessTermux(platform);
        termux.setAppVisible(true);
        assertTrue(termux.connect(true, () -> false).localReady());
        assertEquals(1, platform.starts);
        assertEquals(1, platform.closedCallbacks);
        assertTrue(termux.connect(true, () -> false).localReady());
        assertEquals(1, platform.starts);

        platform.health = Status.OFFLINE;
        assertTrue(termux.connect(true, () -> false).localReady());
        assertEquals(2, platform.starts);
    }

    @Test public void acceptedStartWithoutAnAuthenticatedListenerIsNotReadyOrRetried() {
        Platform platform = new Platform();
        platform.ended = CommandResult.observed(CommandResult.Status.EXITED, 127, "", "", "", false);
        HeadlessTermux termux = new HeadlessTermux(platform);
        termux.setAppVisible(true);
        HeadlessTermux.Status result = termux.connect(true, () -> false);
        assertFalse(result.localReady());
        assertTrue(result.detail.contains("127"));
        assertTrue(result.detail.contains("server.py"));
        assertFalse(termux.connect(true, () -> false).localReady());
        assertEquals(1, platform.starts);
        assertEquals(1, platform.closedCallbacks);
    }

    @Test public void tokenAndPermissionAreIndependentRequirementsForAutomaticLaunch() {
        Platform platform = new Platform();
        HeadlessTermux termux = new HeadlessTermux(platform);
        termux.setAppVisible(true);
        platform.capabilities = new HeadlessTermux.Capabilities(true, true, true, true, false);
        HeadlessTermux.Status noToken = termux.connect(true, () -> false);
        assertEquals(Status.UNCONFIGURED, noToken.localStatus);
        assertTrue(noToken.officialAvailable());
        assertFalse(noToken.localReady());
        platform.capabilities = new HeadlessTermux.Capabilities(true, true, true, false, true);
        HeadlessTermux.Status noService = termux.connect(true, () -> false);
        assertEquals(Status.OFFLINE, noService.localStatus);
        assertFalse(noService.officialAvailable());
        assertTrue(noService.detail.contains("вручную"));
        assertEquals(0, platform.starts);
    }

    private static final class Platform implements HeadlessTermux.Platform {
        HeadlessTermux.Capabilities capabilities = new HeadlessTermux.Capabilities(true, true, true, true, true);
        Status health = Status.OFFLINE;
        CommandResult ended;
        boolean becomeReady, autoStartEnabled;
        int starts, closedCallbacks;

        @Override public HeadlessTermux.Capabilities capabilities() { return capabilities; }

        @Override public LocalTermuxBridge.Health probe(BooleanSupplier cancelled) {
            return new LocalTermuxBridge.Health(health, health.name());
        }

        @Override public TermuxBridge.BackgroundStart start(BooleanSupplier cancelled) throws IOException {
            if (cancelled.getAsBoolean()) throw new IOException("cancelled");
            starts++;
            if (becomeReady) health = Status.READY;
            return new TermuxBridge.BackgroundStart() {
                @Override public CommandResult completed() { return ended; }
                @Override public void close() { closedCallbacks++; }
            };
        }

        @Override public boolean autoStartEnabled() { return autoStartEnabled; }
        @Override public void setAutoStartEnabled(boolean enabled) { autoStartEnabled = enabled; }
    }
}
