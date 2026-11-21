package ai.kent.code;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.Test;

public class LocalBridgeProtocolTest {
    @Test
    public void authenticationMatchesWireFormat() throws Exception {
        byte[] client = new byte[32];
        byte[] server = new byte[32];
        for (int i = 0; i < 32; i++) {
            client[i] = (byte) i;
            server[i] = (byte) (i + 32);
        }
        byte[] token = "0123456789abcdef0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        byte[] payload = "{\"command\":\"id\"}".getBytes(StandardCharsets.UTF_8);
        byte[] proof = LocalTermuxBridge.signature(token, "request", client, server, payload);
        assertEquals("3569aa4314925db327dde8b303db4449d0c5275c747da68a87e5ca77c377117d", HexFormat.of().formatHex(proof));
    }

    @Test
    public void detachedAndRemoteTimeoutsRequireReview() throws Exception {
        byte[] timeout = "{\"status\":\"timeout\",\"detached\":false,\"output\":\"partial\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(LocalTermuxBridge.decode(timeout, false).mayStillBeRunning());
        assertTrue(LocalTermuxBridge.decode(timeout, true).mayStillBeRunning());
        byte[] detached = "{\"status\":\"exited\",\"exitCode\":0,\"detached\":true,\"output\":\"\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(LocalTermuxBridge.decode(detached, false).succeeded());
    }
}
