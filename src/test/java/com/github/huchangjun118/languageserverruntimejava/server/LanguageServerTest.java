package com.github.huchangjun118.languageserverruntimejava.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.huchangjun118.languageserverruntimejava.json.JsonSupport;
import com.github.huchangjun118.languageserverruntimejava.protocol.ErrorCodes;
import com.github.huchangjun118.languageserverruntimejava.protocol.MessageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End to end tests driving a {@link LanguageServer} over in-memory pipes with
 * real base-protocol framing.
 */
class LanguageServerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * In-memory full-duplex byte pipe without the thread-liveness checks of
     * {@link java.io.PipedInputStream}, safe for concurrent writers.
     */
    static final class Pipe {
        private final byte[] buffer = new byte[1 << 16];
        private int head;
        private int count;
        private boolean closed;

        final InputStream in = new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                synchronized (Pipe.this) {
                    while (count == 0) {
                        if (closed) {
                            return -1;
                        }
                        try {
                            Pipe.this.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                    }
                    int n = Math.min(len, count);
                    for (int i = 0; i < n; i++) {
                        b[off + i] = buffer[(head + i) % buffer.length];
                    }
                    head = (head + n) % buffer.length;
                    count -= n;
                    Pipe.this.notifyAll();
                    return n;
                }
            }

            @Override
            public void close() {
                Pipe.this.close();
            }
        };

        final OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                synchronized (Pipe.this) {
                    int written = 0;
                    while (written < len) {
                        while (count == buffer.length) {
                            try {
                                Pipe.this.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException(e);
                            }
                        }
                        int n = Math.min(len - written, buffer.length - count);
                        for (int i = 0; i < n; i++) {
                            buffer[(head + count + i) % buffer.length] = b[off + written + i];
                        }
                        count += n;
                        written += n;
                        Pipe.this.notifyAll();
                    }
                }
            }

            @Override
            public void close() {
                Pipe.this.close();
            }
        };

        synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    /** Client side of the connection: frames messages and collects responses. */
    static final class Harness implements AutoCloseable {
        private final Pipe clientToServer = new Pipe();
        private final Pipe serverToClient = new Pipe();
        private final Thread serverThread;
        private final Thread readerThread;
        private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
        private final AtomicInteger exitCode = new AtomicInteger(-1);
        private final AtomicInteger nextId = new AtomicInteger(1);

        Harness() throws Exception {
            MessageIO clientReader = new MessageIO(serverToClient.in, OutputStream.nullOutputStream());
            LanguageServer server = new LanguageServer(clientToServer.in, serverToClient.out);
            serverThread = new Thread(() -> {
                try {
                    exitCode.set(server.run());
                } catch (Exception e) {
                    e.printStackTrace();
                    exitCode.set(-2);
                }
            }, "test-server");
            serverThread.setDaemon(true);
            serverThread.start();
            readerThread = new Thread(() -> {
                try {
                    while (true) {
                        JsonNode message = clientReader.readMessage();
                        if (message == null) {
                            return;
                        }
                        incoming.add(message);
                    }
                } catch (Exception e) {
                    // stream closed
                }
            }, "test-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        void sendRaw(String raw) throws Exception {
            synchronized (clientToServer) {
                clientToServer.out.write(raw.getBytes(StandardCharsets.UTF_8));
                clientToServer.out.flush();
            }
        }

        void send(JsonNode message) throws Exception {
            byte[] body = JsonSupport.MAPPER.writeValueAsBytes(message);
            synchronized (clientToServer) {
                clientToServer.out.write(("Content-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                clientToServer.out.write(body);
                clientToServer.out.flush();
            }
        }

        int sendRequest(String method, JsonNode params) throws Exception {
            int id = nextId.getAndIncrement();
            ObjectNode message = JsonSupport.MAPPER.createObjectNode();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            if (params != null) {
                message.set("params", params);
            }
            send(message);
            return id;
        }

        void sendNotification(String method, JsonNode params) throws Exception {
            ObjectNode message = JsonSupport.MAPPER.createObjectNode();
            message.put("jsonrpc", "2.0");
            message.put("method", method);
            if (params != null) {
                message.set("params", params);
            }
            send(message);
        }

        JsonNode awaitResponse(int id) throws Exception {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            List<JsonNode> skipped = new ArrayList<>();
            try {
                while (System.nanoTime() < deadline) {
                    JsonNode message = incoming.poll(100, TimeUnit.MILLISECONDS);
                    if (message == null) {
                        continue;
                    }
                    if (message.has("id") && message.get("id").isIntegralNumber()
                            && message.get("id").asInt() == id) {
                        return message;
                    }
                    skipped.add(message);
                }
                fail("timed out waiting for response to id " + id);
                return null;
            } finally {
                incoming.addAll(skipped);
            }
        }

        /** Waits for a notification with the given method; consumes earlier messages. */
        JsonNode awaitNotification(String method) throws Exception {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            List<JsonNode> skipped = new ArrayList<>();
            try {
                while (System.nanoTime() < deadline) {
                    JsonNode message = incoming.poll(100, TimeUnit.MILLISECONDS);
                    if (message == null) {
                        continue;
                    }
                    if (method.equals(message.path("method").asText(null))) {
                        return message;
                    }
                    skipped.add(message);
                }
                fail("timed out waiting for notification " + method);
                return null;
            } finally {
                incoming.addAll(skipped);
            }
        }

        void initialize() throws Exception {
            initialize(null);
        }

        void initialize(JsonNode extraCapabilities) throws Exception {
            ObjectNode params = JsonSupport.MAPPER.createObjectNode();
            params.put("processId", 0);
            if (extraCapabilities != null) {
                params.set("capabilities", extraCapabilities);
            }
            int id = sendRequest("initialize", params);
            JsonNode response = awaitResponse(id);
            assertNull(response.get("error"), () -> "initialize failed: " + response);
            sendNotification("initialized", null);
        }

        void shutdownAndExit() throws Exception {
            int id = sendRequest("shutdown", null);
            awaitResponse(id);
            sendNotification("exit", null);
            serverThread.join(TimeUnit.SECONDS.toMillis(10));
        }

        int exitCode() {
            return exitCode.get();
        }

        boolean serverTerminated() {
            return !serverThread.isAlive();
        }

        @Override
        public void close() throws Exception {
            clientToServer.close();
            serverToClient.close();
            serverThread.join(2000);
        }
    }

    private Harness harness;

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) {
            harness.close();
        }
    }

    private static ObjectNode textDocument(String uri) {
        ObjectNode params = JsonSupport.MAPPER.createObjectNode();
        ObjectNode doc = JsonSupport.MAPPER.createObjectNode();
        doc.put("uri", uri);
        params.set("textDocument", doc);
        return params;
    }

    private static ObjectNode positionParams(String uri, int line, int character) {
        ObjectNode params = textDocument(uri);
        ObjectNode position = JsonSupport.MAPPER.createObjectNode();
        position.put("line", line);
        position.put("character", character);
        params.set("position", position);
        return params;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    void requestBeforeInitializeIsRejected() throws Exception {
        harness = new Harness();
        int id = harness.sendRequest("textDocument/definition",
                positionParams("file:///a", 0, 0));
        JsonNode response = harness.awaitResponse(id);
        assertEquals(ErrorCodes.SERVER_NOT_INITIALIZED, response.get("error").get("code").asInt());
        harness.sendNotification("exit", null);
    }

    @Test
    void shutdownBeforeInitializeIsRejected() throws Exception {
        harness = new Harness();
        int id = harness.sendRequest("shutdown", null);
        JsonNode response = harness.awaitResponse(id);
        assertEquals(ErrorCodes.SERVER_NOT_INITIALIZED, response.get("error").get("code").asInt());
        harness.sendNotification("exit", null);
    }

    @Test
    void requestAfterShutdownIsRejectedAndExitIsClean() throws Exception {
        harness = new Harness();
        harness.initialize();
        int shutdownId = harness.sendRequest("shutdown", null);
        assertNull(harness.awaitResponse(shutdownId).get("error"));

        int id = harness.sendRequest("textDocument/definition",
                positionParams("file:///a", 0, 0));
        JsonNode response = harness.awaitResponse(id);
        assertEquals(ErrorCodes.INVALID_REQUEST, response.get("error").get("code").asInt());

        harness.sendNotification("exit", null);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!harness.serverTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(harness.serverTerminated(), "server must terminate after exit");
        assertEquals(0, harness.exitCode(), "clean shutdown exits with code 0");
    }

    @Test
    void exitWithoutShutdownExitsWithErrorCode() throws Exception {
        harness = new Harness();
        harness.initialize();
        harness.sendNotification("exit", null);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!harness.serverTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(harness.serverTerminated());
        assertEquals(1, harness.exitCode(), "exit without shutdown exits with code 1");
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        harness = new Harness();
        harness.initialize();
        int id = harness.sendRequest("workspace/doesSomething", null);
        JsonNode response = harness.awaitResponse(id);
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, response.get("error").get("code").asInt());
        harness.shutdownAndExit();
    }

    @Test
    void framingErrorIsReportedAndServerRecovers() throws Exception {
        harness = new Harness();
        // Malformed header line (no colon), then a valid initialize message.
        harness.sendRaw("garbage-header\r\n");
        int id = harness.sendRequest("initialize", JsonSupport.MAPPER.createObjectNode());
        JsonNode response = harness.awaitResponse(id);
        assertNull(response.get("error"), "server must recover and answer initialize");
        harness.shutdownAndExit();
    }

    @Test
    void invalidJsonBodyIsReportedAndServerRecovers() throws Exception {
        harness = new Harness();
        byte[] garbage = "{not json".getBytes(StandardCharsets.UTF_8);
        harness.sendRaw("Content-Length: " + garbage.length + "\r\n\r\n" + new String(garbage));
        int id = harness.sendRequest("initialize", JsonSupport.MAPPER.createObjectNode());
        JsonNode response = harness.awaitResponse(id);
        assertNull(response.get("error"));
        harness.shutdownAndExit();
    }

    // ------------------------------------------------------------------
    // Diagnostics and navigation
    // ------------------------------------------------------------------

    private void openDocument(String uri, int version, String text) throws Exception {
        ObjectNode params = textDocument(uri);
        ((ObjectNode) params.get("textDocument")).put("version", version);
        ((ObjectNode) params.get("textDocument")).put("text", text);
        harness.sendNotification("textDocument/didOpen", params);
    }

    private void changeDocument(String uri, int version, JsonNode contentChanges) throws Exception {
        ObjectNode params = textDocument(uri);
        ((ObjectNode) params.get("textDocument")).put("version", version);
        params.set("contentChanges", contentChanges);
        harness.sendNotification("textDocument/didChange", params);
    }

    private static JsonNode fullChange(String text) {
        com.fasterxml.jackson.databind.node.ObjectNode change =
                JsonSupport.MAPPER.createObjectNode();
        change.put("text", text);
        com.fasterxml.jackson.databind.node.ArrayNode changes =
                JsonSupport.MAPPER.createArrayNode();
        changes.add(change);
        return changes;
    }

    @Test
    void diagnosticsPublishedOnOpenAndClearedOnClose() throws Exception {
        harness = new Harness();
        harness.initialize();
        openDocument("file:///a", 1, "ref missing\n");
        JsonNode published = harness.awaitNotification("textDocument/publishDiagnostics");
        assertEquals("file:///a", published.get("params").get("uri").asText());
        JsonNode diagnostics = published.get("params").get("diagnostics");
        assertEquals(1, diagnostics.size());
        assertEquals("undefined-reference", diagnostics.get(0).get("code").asText());

        harness.sendNotification("textDocument/didClose", textDocument("file:///a"));
        JsonNode cleared = harness.awaitNotification("textDocument/publishDiagnostics");
        assertEquals("file:///a", cleared.get("params").get("uri").asText());
        assertEquals(0, cleared.get("params").get("diagnostics").size());
        harness.shutdownAndExit();
    }

    @Test
    void diagnosticsDistinguishUndefinedAndDuplicate() throws Exception {
        harness = new Harness();
        harness.initialize();
        openDocument("file:///a", 1, "def a\ndef a\nref b\n");
        JsonNode published = harness.awaitNotification("textDocument/publishDiagnostics");
        List<String> codes = new ArrayList<>();
        published.get("params").get("diagnostics").forEach(d -> codes.add(d.get("code").asText()));
        assertTrue(codes.contains("duplicate-definition"), codes.toString());
        assertTrue(codes.contains("undefined-reference"), codes.toString());
        harness.shutdownAndExit();
    }

    @Test
    void diagnosticsFollowLatestChangeOnly() throws Exception {
        harness = new Harness();
        harness.initialize();
        openDocument("file:///a", 1, "ref x\n");
        harness.awaitNotification("textDocument/publishDiagnostics");
        // Rapid successive changes; only the final state may be published last.
        for (int v = 2; v <= 6; v++) {
            changeDocument("file:///a", v, fullChange("ref v" + v + "\n"));
        }
        changeDocument("file:///a", 7, fullChange("def ok\nref ok\n"));
        // Wait for the clean diagnostics of the final version.
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            JsonNode published = harness.awaitNotification("textDocument/publishDiagnostics");
            last = published;
            if (published.get("params").get("diagnostics").isEmpty()) {
                break; // final content is clean
            }
        }
        assertNotNull(last);
        assertEquals(0, last.get("params").get("diagnostics").size(),
                "final diagnostics must reflect the newest version");
        harness.shutdownAndExit();
    }

    @Test
    void duplicateChangeVersionDoesNotCorruptDocument() throws Exception {
        harness = new Harness();
        harness.initialize();
        openDocument("file:///a", 1, "def a\n");
        changeDocument("file:///a", 2, fullChange("def b\n"));
        // Duplicate delivery of version 2 with different text must be ignored.
        changeDocument("file:///a", 2, fullChange("def WRONG\n"));
        changeDocument("file:///a", 1, fullChange("def OLDER\n"));
        int id = harness.sendRequest("textDocument/definition",
                positionParams("file:///a", 0, 5));
        JsonNode response = harness.awaitResponse(id);
        JsonNode range = response.get("result").get("range");
        assertEquals(0, range.get("start").get("line").asInt());
        assertEquals(4, range.get("start").get("character").asInt());
        assertEquals(5, range.get("end").get("character").asInt(),
                "identifier 'b' is one character; WRONG/OLDER edits must be ignored");
        harness.shutdownAndExit();
    }

    @Test
    void definitionAndReferences() throws Exception {
        harness = new Harness();
        harness.initialize();
        openDocument("file:///a", 1, "def foo\nref foo\ndef bar\nref foo\n");
        harness.awaitNotification("textDocument/publishDiagnostics");

        // Definition from a reference on line 3.
        int defId = harness.sendRequest("textDocument/definition",
                positionParams("file:///a", 3, 5));
        JsonNode defResponse = harness.awaitResponse(defId);
        JsonNode range = defResponse.get("result").get("range");
        assertEquals(0, range.get("start").get("line").asInt());
        assertEquals(4, range.get("start").get("character").asInt());
        assertEquals(7, range.get("end").get("character").asInt());

        // References including the declaration.
        ObjectNode refParams = positionParams("file:///a", 0, 5);
        ObjectNode context = JsonSupport.MAPPER.createObjectNode();
        context.put("includeDeclaration", true);
        refParams.set("context", context);
        int refId = harness.sendRequest("textDocument/references", refParams);
        JsonNode refResponse = harness.awaitResponse(refId);
        assertEquals(3, refResponse.get("result").size());

        // References excluding the declaration.
        context.put("includeDeclaration", false);
        int refId2 = harness.sendRequest("textDocument/references", refParams);
        JsonNode refResponse2 = harness.awaitResponse(refId2);
        assertEquals(2, refResponse2.get("result").size());

        // Undefined reference has no definition target.
        changeDocument("file:///a", 2, fullChange("ref ghost\n"));
        int undefId = harness.sendRequest("textDocument/definition",
                positionParams("file:///a", 0, 5));
        JsonNode undefResponse = harness.awaitResponse(undefId);
        assertTrue(undefResponse.get("result").isNull(),
                "undefined identifier must have no definition");
        harness.shutdownAndExit();
    }

    // ------------------------------------------------------------------
    // Position encoding negotiation
    // ------------------------------------------------------------------

    @Test
    void utf8EncodingIsNegotiatedAndUsed() throws Exception {
        harness = new Harness();
        ObjectNode general = JsonSupport.MAPPER.createObjectNode();
        general.putArray("positionEncodings").add("utf-8");
        ObjectNode capabilities = JsonSupport.MAPPER.createObjectNode();
        capabilities.set("general", general);
        ObjectNode params = JsonSupport.MAPPER.createObjectNode();
        params.set("capabilities", capabilities);
        int initId = harness.sendRequest("initialize", params);
        JsonNode initResponse = harness.awaitResponse(initId);
        assertEquals("utf-8", initResponse.get("result").get("capabilities")
                .get("positionEncoding").asText());
        harness.sendNotification("initialized", null);

        // Identifier after a 4-byte emoji on the same line is not possible
        // (keyword comes first), so check a multibyte identifier instead.
        openDocument("file:///a", 1, "def héllo\nref héllo\n");
        harness.awaitNotification("textDocument/publishDiagnostics");
        // In UTF-8, 'héllo' spans bytes 4..10 on the line.
        int id = harness.sendRequest("textDocument/definition",
                positionParams("file:///a", 1, 9));
        JsonNode response = harness.awaitResponse(id);
        JsonNode range = response.get("result").get("range");
        assertEquals(4, range.get("start").get("character").asInt());
        assertEquals(10, range.get("end").get("character").asInt());
        harness.shutdownAndExit();
    }

    @Test
    void defaultEncodingIsUtf16() throws Exception {
        harness = new Harness();
        int initId = harness.sendRequest("initialize", JsonSupport.MAPPER.createObjectNode());
        JsonNode initResponse = harness.awaitResponse(initId);
        assertEquals("utf-16", initResponse.get("result").get("capabilities")
                .get("positionEncoding").asText());
        harness.sendNotification("initialized", null);
        harness.shutdownAndExit();
    }

    // ------------------------------------------------------------------
    // Cancellation and concurrency
    // ------------------------------------------------------------------

    @Test
    void requestCancelledBeforeStartReturnsCancelError() throws Exception {
        harness = new Harness();
        harness.initialize();
        ObjectNode cancelParams = JsonSupport.MAPPER.createObjectNode();
        cancelParams.put("id", 999);
        harness.sendNotification("$/cancelRequest", cancelParams);
        // The id is already marked cancelled; the request must not execute.
        ObjectNode message = JsonSupport.MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", 999);
        message.put("method", "textDocument/definition");
        message.set("params", positionParams("file:///a", 0, 0));
        harness.send(message);
        JsonNode response = harness.awaitResponse(999);
        assertEquals(ErrorCodes.REQUEST_CANCELLED, response.get("error").get("code").asInt());
        harness.shutdownAndExit();
    }

    @Test
    void cancelledRequestsProduceExactlyOneResponse() throws Exception {
        harness = new Harness();
        harness.initialize();
        openDocument("file:///a", 1, "def a\nref a\n");
        harness.awaitNotification("textDocument/publishDiagnostics");

        Map<Integer, JsonNode> responses = new ConcurrentHashMap<>();
        List<Integer> ids = new ArrayList<>();
        // Fire many requests and cancel half of them immediately.
        for (int i = 0; i < 40; i++) {
            int id = harness.sendRequest("textDocument/definition",
                    positionParams("file:///a", 1, 5));
            ids.add(id);
            if (i % 2 == 0) {
                ObjectNode cancelParams = JsonSupport.MAPPER.createObjectNode();
                cancelParams.put("id", id);
                harness.sendNotification("$/cancelRequest", cancelParams);
            }
        }
        int cancelled = 0;
        int succeeded = 0;
        for (int id : ids) {
            JsonNode response = harness.awaitResponse(id);
            assertTrue(responses.put(id, response) == null,
                    "each request id must produce exactly one response");
            if (response.has("error")) {
                assertEquals(ErrorCodes.REQUEST_CANCELLED,
                        response.get("error").get("code").asInt());
                cancelled++;
            } else {
                succeeded++;
            }
        }
        assertEquals(40, responses.size());
        assertTrue(succeeded > 0, "uncancelled requests must succeed");
        harness.shutdownAndExit();
    }

    @Test
    void concurrentRequestsAllGetResponses() throws Exception {
        harness = new Harness();
        harness.initialize();
        openDocument("file:///a", 1, "def a\nref a\n");
        harness.awaitNotification("textDocument/publishDiagnostics");

        int count = 30;
        List<Thread> threads = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int line = i % 2;
            threads.add(new Thread(() -> {
                try {
                    synchronized (ids) {
                        ids.add(harness.sendRequest("textDocument/definition",
                                positionParams("file:///a", line, 5)));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertEquals(count, ids.size());
        for (int id : ids) {
            JsonNode response = harness.awaitResponse(id);
            assertNull(response.get("error"), () -> "request " + id + " failed: " + response);
            assertEquals(id, response.get("id").asInt(), "response must match its request id");
        }
        harness.shutdownAndExit();
    }

    @Test
    void slowNotificationsDoNotBlockRequests() throws Exception {
        harness = new Harness();
        harness.initialize();
        openDocument("file:///a", 1, "def a\nref a\n");
        // Immediately send a request without waiting for diagnostics: the
        // request path must not be blocked behind notification processing.
        int id = harness.sendRequest("textDocument/definition",
                positionParams("file:///a", 1, 5));
        JsonNode response = harness.awaitResponse(id);
        assertNull(response.get("error"));
        harness.shutdownAndExit();
    }
}
