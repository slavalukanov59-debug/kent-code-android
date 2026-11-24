package ai.kent.code;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.Test;

public class LocalBridgeProtocolTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final byte[] KEY = TOKEN.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HELLO = "KENT-BRIDGE-2".getBytes(StandardCharsets.US_ASCII);

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

    @Test public void requiresSignedHealthResponseAndSendsNoRunnableCommand() throws Exception {
        LocalTermuxBridge.Health ready = probeWithPeer(false, peer -> respond(peer, KEY,
                "{\"status\":\"healthy\",\"healthVersion\":1}", KEY), () -> false);
        assertEquals(LocalTermuxBridge.Health.Status.READY, ready.status);

        LocalTermuxBridge.Health forged = probeWithPeer(false, peer -> respond(peer, KEY,
                "{\"status\":\"healthy\",\"healthVersion\":1}", new byte[48]), () -> false);
        assertEquals(LocalTermuxBridge.Health.Status.AUTH_FAILED, forged.status);

        LocalTermuxBridge.Health legacy = probeWithPeer(false, peer -> respond(peer, KEY,
                "{\"status\":\"rejected\",\"message\":\"Недопустимая команда или таймаут.\"}", KEY), () -> false);
        assertEquals(LocalTermuxBridge.Health.Status.LEGACY, legacy.status);

        LocalTermuxBridge.Health broken = probeWithPeer(false, peer -> respond(peer, KEY,
                "{\"status\":\"healthy\",\"healthVersion\":2}", KEY), () -> false);
        assertEquals(LocalTermuxBridge.Health.Status.INCOMPATIBLE, broken.status);
    }

    @Test public void mismatchedServerTokenStopsBeforeSendingHealthRequest() throws Exception {
        LocalTermuxBridge.Health mismatch = probeWithPeer(false, peer -> {
            DataInputStream input = new DataInputStream(peer.getInputStream());
            DataOutputStream output = new DataOutputStream(peer.getOutputStream());
            sendChallenge(input, output, new byte[48]);
            assertEquals(-1, input.read());
        }, () -> false);
        assertEquals(LocalTermuxBridge.Health.Status.AUTH_FAILED, mismatch.status);
    }

    @Test public void reportsRefusalSeparatelyFromTimeoutAndCancellation() throws Exception {
        InetSocketAddress endpoint;
        try (ServerSocket port = loopbackServer()) { endpoint = (InetSocketAddress) port.getLocalSocketAddress(); }
        assertEquals(LocalTermuxBridge.Health.Status.OFFLINE,
                LocalTermuxBridge.probe(endpoint, TOKEN, () -> false).status);
        assertEquals(LocalTermuxBridge.Health.Status.UNCONFIGURED,
                LocalTermuxBridge.probe(endpoint, "", () -> false).status);
        LocalTermuxBridge.Health timeout = probeWithPeer(true, peer -> {
            DataInputStream input = new DataInputStream(peer.getInputStream());
            readFrame(input);
            assertEquals(-1, input.read());
        }, () -> false);
        assertEquals(LocalTermuxBridge.Health.Status.TIMEOUT, timeout.status);

        AtomicBoolean cancelled = new AtomicBoolean();
        LocalTermuxBridge.Health stopped = probeWithPeer(false, peer -> {
            DataInputStream input = new DataInputStream(peer.getInputStream());
            readFrame(input);
            cancelled.set(true);
            assertEquals(-1, input.read());
        }, cancelled::get);
        assertEquals(LocalTermuxBridge.Health.Status.CANCELLED, stopped.status);
    }

    private static void respond(Socket peer, byte[] challengeKey, String response, byte[] responseKey) throws Exception {
        DataInputStream input = new DataInputStream(peer.getInputStream());
        DataOutputStream output = new DataOutputStream(peer.getOutputStream());
        byte[][] nonces = sendChallenge(input, output, challengeKey);
        byte[] request = readFrame(input);
        byte[] payload = Arrays.copyOfRange(request, 32, request.length);
        assertEquals("{\"mode\":\"health\",\"healthVersion\":1}", new String(payload, StandardCharsets.UTF_8));
        assertTrue(Arrays.equals(Arrays.copyOf(request, 32),
                LocalTermuxBridge.signature(KEY, "request", nonces[0], nonces[1], payload)));
        byte[] reply = response.getBytes(StandardCharsets.UTF_8);
        writeFrame(output, LocalTermuxBridge.signature(responseKey, "response", nonces[0], nonces[1], reply), reply);
    }

    private static byte[][] sendChallenge(DataInputStream input, DataOutputStream output, byte[] key) throws Exception {
        byte[] hello = readFrame(input);
        assertTrue(Arrays.equals(HELLO, Arrays.copyOf(hello, HELLO.length)));
        assertEquals(HELLO.length + 32, hello.length);
        byte[] client = Arrays.copyOfRange(hello, HELLO.length, hello.length);
        byte[] server = new byte[32];
        writeFrame(output, server, LocalTermuxBridge.signature(key, "server", client, server, new byte[0]));
        return new byte[][]{client, server};
    }

    private static byte[] readFrame(DataInputStream input) throws Exception {
        int length = input.readInt();
        assertTrue(length > 0 && length <= 256);
        byte[] frame = new byte[length];
        input.readFully(frame);
        return frame;
    }

    private static void writeFrame(DataOutputStream output, byte[] first, byte[] second) throws Exception {
        output.writeInt(first.length + second.length);
        output.write(first);
        output.write(second);
        output.flush();
    }

    private static ServerSocket loopbackServer() throws Exception {
        return new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    }

    private static LocalTermuxBridge.Health probeWithPeer(boolean awaitsTimeout, Peer action, BooleanSupplier cancelled)
            throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = loopbackServer()) {
            Future<?> peer = worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(awaitsTimeout ? 6000 : 3000);
                    action.handle(socket);
                }
                return null;
            });
            LocalTermuxBridge.Health health = LocalTermuxBridge.probe(
                    (InetSocketAddress) server.getLocalSocketAddress(), TOKEN, cancelled);
            peer.get(7, TimeUnit.SECONDS);
            return health;
        } finally {
            worker.shutdownNow();
        }
    }

    private interface Peer { void handle(Socket socket) throws Exception; }
}
