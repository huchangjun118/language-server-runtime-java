package com.github.huchangjun118.languageserverruntimejava.lsp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/** In-process test client driving an {@link LspServer} over piped streams. */
final class TestClient implements AutoCloseable {
    final LspServer server;
    private final Thread serverThread;
    private final Thread readerThread;
    private final OutputStream toServer;
    private final List<JsonNode> notifications = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<JsonNode>> responses = new ConcurrentHashMap<>();
    final List<Integer> exitCodes = new CopyOnWriteArrayList<>();
    final CountDownLatch serverTerminated = new CountDownLatch(1);
    private volatile boolean closed;

    TestClient(ServerConfig config) throws IOException {
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        PipedOutputStream clientOut = new PipedOutputStream(serverIn);
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(serverOut, 1 << 16);
        this.toServer = clientOut;
        this.server = new LspServer(serverIn, serverOut, config);
        this.server.setExitHandler(code -> exitCodes.add(code));
        this.serverThread = new Thread(() -> {
            server.run();
            serverTerminated.countDown();
        }, "test-lsp-server");
        this.serverThread.setDaemon(true);
        this.serverThread.start();
        Framing.Reader reader = new Framing.Reader(clientIn, config.maxMessageSize());
        this.readerThread = new Thread(() -> {
            try {
                while (!closed) {
                    Framing.ReadResult r = reader.read();
                    if (r == null) {
                        return;
                    }
                    if (r.framingError() != null) {
                        continue;
                    }
                    JsonNode msg = Json.MAPPER.readTree(r.body());
                    if (msg.hasNonNull("method")) {
                        notifications.add(msg);
                    } else if (msg.has("id")) {
                        String key = msg.get("id").toString();
                        responses.computeIfAbsent(key, k -> new CompletableFuture<>()).complete(msg);
                    }
                }
            } catch (IOException ignored) {
                // stream closed during shutdown
            }
        }, "test-client-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    // ------------------------------------------------------------ send

    synchronized void send(JsonNode message) {
        try {
            byte[] body = Json.MAPPER.writeValueAsBytes(message);
            toServer.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            toServer.write(body);
            toServer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    synchronized void sendRaw(String raw) {
        try {
            toServer.write(raw.getBytes(StandardCharsets.UTF_8));
            toServer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void sendRequest(int id, String method, JsonNode params) {
        var msg = Json.obj();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        send(msg);
    }

    void sendNotification(String method, JsonNode params) {
        var msg = Json.obj();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        send(msg);
    }

    // ------------------------------------------------------------ receive

    JsonNode awaitResponse(int id, long timeoutMs) {
        try {
            return responses.computeIfAbsent(String.valueOf(id), k -> new CompletableFuture<>())
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fail("no response for id " + id + " within " + timeoutMs + "ms: " + e);
        }
    }

    /** Waits for an error/response message carrying a null id (e.g. parse errors). */
    JsonNode awaitNullIdResponse(long timeoutMs) {
        try {
            return responses.computeIfAbsent("null", k -> new CompletableFuture<>())
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fail("no null-id response within " + timeoutMs + "ms: " + e);
        }
    }

    /** Waits until a notification matching the predicate has been received. */
    JsonNode awaitNotification(Predicate<JsonNode> predicate, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode n : notifications) {
                if (predicate.test(n)) {
                    return n;
                }
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fail("interrupted");
            }
        }
        return fail("no matching notification within " + timeoutMs + "ms");
    }

    JsonNode awaitDiagnostics(String uri, long timeoutMs) {
        return awaitNotification(n -> "textDocument/publishDiagnostics".equals(n.path("method").asText())
                && uri.equals(n.path("params").path("uri").asText()), timeoutMs);
    }

    List<JsonNode> notifications() {
        return notifications;
    }

    List<JsonNode> diagnosticsFor(String uri) {
        return notifications.stream()
                .filter(n -> "textDocument/publishDiagnostics".equals(n.path("method").asText())
                        && uri.equals(n.path("params").path("uri").asText()))
                .toList();
    }

    // ------------------------------------------------------------ lifecycle helpers

    void initialize() {
        initialize(null);
    }

    void initialize(String positionEncoding) {
        var capabilities = Json.obj();
        if (positionEncoding != null) {
            capabilities.put("positionEncoding", positionEncoding);
        }
        var params = Json.obj();
        params.put("processId", 0);
        params.set("capabilities", capabilities);
        sendRequest(1, "initialize", params);
        awaitResponse(1, 5000);
        sendNotification("initialized", Json.obj());
    }

    void cancelRequest(int id) {
        var params = Json.obj();
        params.put("id", id);
        sendNotification("$/cancelRequest", params);
    }

    void didOpen(String uri, String text, int version) {
        var doc = Json.obj();
        doc.put("uri", uri);
        doc.put("languageId", "minils");
        doc.put("version", version);
        doc.put("text", text);
        var params = Json.obj();
        params.set("textDocument", doc);
        sendNotification("textDocument/didOpen", params);
    }

    void didChange(String uri, int version, JsonNode contentChanges) {
        var doc = Json.obj();
        doc.put("uri", uri);
        doc.put("version", version);
        var params = Json.obj();
        params.set("textDocument", doc);
        params.set("contentChanges", contentChanges);
        sendNotification("textDocument/didChange", params);
    }

    void didClose(String uri) {
        var doc = Json.obj();
        doc.put("uri", uri);
        var params = Json.obj();
        params.set("textDocument", doc);
        sendNotification("textDocument/didClose", params);
    }

    @Override
    public void close() {
        closed = true;
        try {
            toServer.close();
        } catch (IOException ignored) {
        }
        try {
            serverThread.join(3000);
        } catch (InterruptedException ignored) {
        }
    }
}
