package com.github.huchangjun118.languageserverruntimejava.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * A standalone Language Server speaking LSP over stdio-style streams.
 *
 * <p>Threading model: one reader loop applies document notifications in
 * arrival order; requests are executed concurrently on a worker pool;
 * diagnostics are computed asynchronously on a single scheduler thread and
 * stale results (superseded versions) are dropped before publishing.
 * All writes are serialized by {@link Framing.Writer}.
 */
public final class LspServer {
    public enum State {
        CREATED, INITIALIZED, SHUTDOWN
    }

    private final Framing.Reader reader;
    private final Framing.Writer writer;
    private final DocumentStore store = new DocumentStore();
    private final ServerConfig config;
    private final ExecutorService requestExecutor;
    private final ScheduledExecutorService diagnosticsExecutor;
    private final Map<JsonNode, RequestHandle> inFlight = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingDiagnostics = new ConcurrentHashMap<>();
    private volatile IntConsumer exitHandler = System::exit;
    private volatile State state = State.CREATED;
    private volatile PositionEncoding encoding = PositionEncoding.UTF16;
    private volatile boolean exitRequested;

    public LspServer(InputStream in, OutputStream out, ServerConfig config) {
        this.reader = new Framing.Reader(in, config.maxMessageSize());
        this.writer = new Framing.Writer(out);
        this.config = config;
        this.requestExecutor = Executors.newCachedThreadPool(r -> daemon(r, "lsp-request"));
        this.diagnosticsExecutor = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "lsp-diagnostics"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Test hook: replace the exit handler so the JVM is not terminated. */
    public void setExitHandler(IntConsumer exitHandler) {
        this.exitHandler = exitHandler;
    }

    public State state() {
        return state;
    }

    public DocumentStore documents() {
        return store;
    }

    public PositionEncoding positionEncoding() {
        return encoding;
    }

    /** Main loop: reads and dispatches messages until EOF or exit. */
    public void run() {
        try {
            while (!exitRequested) {
                Framing.ReadResult result = reader.read();
                if (result == null) {
                    break; // clean EOF
                }
                if (result.framingError() != null) {
                    sendError(NullNode.getInstance(), JsonRpc.PARSE_ERROR,
                            "Framing error: " + result.framingError());
                    continue;
                }
                JsonNode message;
                try {
                    message = Json.MAPPER.readTree(result.body());
                } catch (Exception e) {
                    sendError(NullNode.getInstance(), JsonRpc.PARSE_ERROR,
                            "Invalid JSON: " + e.getMessage());
                    continue;
                }
                try {
                    dispatch(message);
                } catch (Exception e) {
                    // Never let a single message kill the loop.
                    System.err.println("dispatch failure: " + e);
                }
            }
        } catch (IOException e) {
            System.err.println("I/O failure, shutting down: " + e);
        } finally {
            shutdownExecutors();
        }
    }

    private void shutdownExecutors() {
        requestExecutor.shutdownNow();
        diagnosticsExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    private void dispatch(JsonNode message) {
        if (!message.isObject()) {
            sendError(NullNode.getInstance(), JsonRpc.INVALID_REQUEST, "Message is not a JSON object");
            return;
        }
        JsonNode method = message.get("method");
        JsonNode id = message.get("id");
        if (method == null || !method.isTextual()) {
            if (id != null && !id.isNull()) {
                sendError(id, JsonRpc.INVALID_REQUEST, "Request is missing a method");
            }
            // Responses from the client (result/error without method) are ignored.
            return;
        }
        if (id != null && !id.isNull()) {
            handleRequest(id, method.asText(), message.get("params"));
        } else {
            handleNotification(method.asText(), message.get("params"));
        }
    }

    private void handleRequest(JsonNode id, String method, JsonNode params) {
        // Lifecycle requests are handled on the reader thread to keep ordering.
        switch (method) {
            case "initialize" -> {
                doInitialize(id, params);
                return;
            }
            case "shutdown" -> {
                if (state != State.INITIALIZED) {
                    sendError(id, JsonRpc.INVALID_REQUEST, "Server is not initialized");
                } else {
                    state = State.SHUTDOWN;
                    sendResult(id, NullNode.getInstance());
                }
                return;
            }
            default -> {
            }
        }
        State current = state;
        if (current == State.CREATED) {
            sendError(id, JsonRpc.SERVER_NOT_INITIALIZED,
                    "Server not initialized: request '" + method + "' rejected");
            return;
        }
        if (current == State.SHUTDOWN) {
            sendError(id, JsonRpc.INVALID_REQUEST,
                    "Server is shut down: request '" + method + "' rejected");
            return;
        }
        switch (method) {
            case "textDocument/definition" -> submitRequest(id, params, this::computeDefinition);
            case "textDocument/references" -> submitRequest(id, params, this::computeReferences);
            default -> sendError(id, JsonRpc.METHOD_NOT_FOUND, "Unknown method: " + method);
        }
    }

    private void handleNotification(String method, JsonNode params) {
        switch (method) {
            case "exit" -> {
                int code = state == State.SHUTDOWN ? 0 : 1;
                exitRequested = true;
                exitHandler.accept(code);
            }
            case "initialized" -> {
                // Client finished initialization; nothing to do.
            }
            case "$/cancelRequest" -> handleCancel(params);
            case "textDocument/didOpen" -> {
                if (state == State.INITIALIZED) {
                    handleDidOpen(params);
                }
            }
            case "textDocument/didChange" -> {
                if (state == State.INITIALIZED) {
                    handleDidChange(params);
                }
            }
            case "textDocument/didClose" -> {
                if (state == State.INITIALIZED) {
                    handleDidClose(params);
                }
            }
            default -> {
                // Unknown notifications are ignored per JSON-RPC.
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private void doInitialize(JsonNode id, JsonNode params) {
        if (state != State.CREATED) {
            sendError(id, JsonRpc.INVALID_REQUEST, "Server is already initialized");
            return;
        }
        // Negotiate the position encoding: honor the client's preference if supported.
        PositionEncoding negotiated = PositionEncoding.UTF16;
        JsonNode capabilities = params != null ? params.get("capabilities") : null;
        JsonNode preferred = capabilities != null ? capabilities.get("positionEncoding") : null;
        if (preferred != null && preferred.isTextual()) {
            PositionEncoding requested = PositionEncoding.fromWireName(preferred.asText());
            if (requested != null) {
                negotiated = requested;
            }
        }
        this.encoding = negotiated;
        this.state = State.INITIALIZED;

        var sync = Json.obj();
        sync.put("openClose", true);
        sync.put("change", 2); // TextDocumentSyncKind.Incremental
        var caps = Json.obj();
        caps.put("positionEncoding", negotiated.wireName());
        caps.set("textDocumentSync", sync);
        caps.put("definitionProvider", true);
        caps.put("referencesProvider", true);
        var serverInfo = Json.obj();
        serverInfo.put("name", "minils-java");
        serverInfo.put("version", "1.0");
        var result = Json.obj();
        result.set("capabilities", caps);
        result.set("serverInfo", serverInfo);
        sendResult(id, result);
    }

    // ------------------------------------------------------------------
    // Document synchronization (always executed on the reader thread,
    // so changes for one document take effect in arrival order)
    // ------------------------------------------------------------------

    private void handleDidOpen(JsonNode params) {
        JsonNode doc = params != null ? params.get("textDocument") : null;
        if (doc == null) {
            return;
        }
        String uri = doc.path("uri").asText();
        String text = doc.path("text").asText("");
        int version = doc.path("version").asInt(0);
        store.open(uri, text, version);
        scheduleDiagnostics(uri, version);
    }

    private void handleDidChange(JsonNode params) {
        JsonNode doc = params != null ? params.get("textDocument") : null;
        if (doc == null) {
            return;
        }
        String uri = doc.path("uri").asText();
        TextDocument document = store.get(uri);
        if (document == null) {
            return; // change for a document that is not open: ignore
        }
        int version = doc.path("version").asInt(document.version());
        List<TextDocument.Change> changes = new ArrayList<>();
        JsonNode contentChanges = params.path("contentChanges");
        if (contentChanges.isArray()) {
            for (JsonNode c : contentChanges) {
                Types.Range range = null;
                JsonNode rangeNode = c.get("range");
                if (rangeNode != null && rangeNode.isObject()) {
                    range = Json.MAPPER.convertValue(rangeNode, Types.Range.class);
                }
                changes.add(new TextDocument.Change(range, c.path("text").asText("")));
            }
        }
        document.applyChanges(changes, version, encoding);
        scheduleDiagnostics(uri, version);
    }

    private void handleDidClose(JsonNode params) {
        JsonNode doc = params != null ? params.get("textDocument") : null;
        if (doc == null) {
            return;
        }
        String uri = doc.path("uri").asText();
        ScheduledFuture<?> pending = pendingDiagnostics.remove(uri);
        if (pending != null) {
            pending.cancel(false);
        }
        if (store.close(uri) != null) {
            publishDiagnostics(uri, List.of()); // clear diagnostics for the closed document
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics (asynchronous, stale versions dropped)
    // ------------------------------------------------------------------

    private void scheduleDiagnostics(String uri, int version) {
        ScheduledFuture<?> previous = pendingDiagnostics.remove(uri);
        if (previous != null) {
            previous.cancel(false);
        }
        ScheduledFuture<?> future = diagnosticsExecutor.schedule(
                () -> computeAndPublishDiagnostics(uri, version),
                config.diagnosticsDelayMs(), TimeUnit.MILLISECONDS);
        pendingDiagnostics.put(uri, future);
    }

    private void computeAndPublishDiagnostics(String uri, int version) {
        TextDocument doc = store.get(uri);
        if (doc == null || doc.version() != version) {
            return; // stale: a newer change superseded this computation
        }
        List<Types.Diagnostic> diagnostics = Analyzer.analyze(doc.text(), encoding).diagnostics();
        if (doc.version() != version || !store.isOpen(uri)) {
            return; // modified or closed while computing: do not overwrite newer state
        }
        publishDiagnostics(uri, diagnostics);
    }

    private void publishDiagnostics(String uri, List<Types.Diagnostic> diagnostics) {
        var params = Json.obj();
        params.put("uri", uri);
        ArrayNode array = params.putArray("diagnostics");
        for (Types.Diagnostic d : diagnostics) {
            array.add(Json.MAPPER.valueToTree(d));
        }
        sendNotification("textDocument/publishDiagnostics", params);
    }

    // ------------------------------------------------------------------
    // Requests: concurrent execution + cancellation
    // ------------------------------------------------------------------

    private static final class RequestHandle {
        final AtomicBoolean responded = new AtomicBoolean();
        volatile Future<?> future;
    }

    @FunctionalInterface
    private interface RequestComputation {
        JsonNode compute(JsonNode params) throws Exception;
    }

    private void submitRequest(JsonNode id, JsonNode params, RequestComputation computation) {
        RequestHandle handle = new RequestHandle();
        inFlight.put(id, handle);
        Future<?> future = requestExecutor.submit(() -> {
            try {
                JsonNode result = computation.compute(params);
                respondWithResult(id, handle, result);
            } catch (CancellationException | InterruptedException e) {
                respondCancelled(id, handle);
            } catch (Exception e) {
                if (handle.responded.compareAndSet(false, true)) {
                    sendError(id, JsonRpc.INTERNAL_ERROR, String.valueOf(e.getMessage()));
                }
            } finally {
                inFlight.remove(id, handle);
            }
        });
        handle.future = future;
    }

    private void respondWithResult(JsonNode id, RequestHandle handle, JsonNode result) {
        if (handle.responded.compareAndSet(false, true)) {
            sendResult(id, result != null ? result : NullNode.getInstance());
        }
    }

    private void respondCancelled(JsonNode id, RequestHandle handle) {
        if (handle.responded.compareAndSet(false, true)) {
            sendError(id, JsonRpc.REQUEST_CANCELLED, "Request was cancelled");
        }
    }

    private void handleCancel(JsonNode params) {
        JsonNode id = params != null ? params.get("id") : null;
        if (id == null) {
            return;
        }
        RequestHandle handle = inFlight.get(id);
        if (handle == null) {
            return; // already finished or unknown: nothing to cancel
        }
        // Exactly one response per request: whoever wins the CAS sends it.
        if (handle.responded.compareAndSet(false, true)) {
            Future<?> future = handle.future;
            if (future != null) {
                future.cancel(true);
            }
            sendError(id, JsonRpc.REQUEST_CANCELLED, "Request was cancelled");
        }
    }

    /** Simulates slow computation in small cancellable slices (test hook / backpressure). */
    private void computeDelay() throws InterruptedException {
        long remaining = config.computeDelayMs();
        while (remaining > 0) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            long slice = Math.min(remaining, 10);
            Thread.sleep(slice);
            remaining -= slice;
        }
    }

    private JsonNode computeDefinition(JsonNode params) throws Exception {
        computeDelay();
        Analyzer.Analysis analysis = analysisAt(params);
        if (analysis == null) {
            return NullNode.getInstance();
        }
        int offset = analysis.index().offsetAt(
                Json.MAPPER.convertValue(params.get("position"), Types.Position.class), encoding);
        Analyzer.Token token = analysis.tokenAt(offset);
        if (token == null) {
            return NullNode.getInstance();
        }
        List<Analyzer.Token> defs = analysis.definitionsOf(token.name());
        if (defs.isEmpty()) {
            return NullNode.getInstance();
        }
        String uri = params.path("textDocument").path("uri").asText();
        return Json.MAPPER.valueToTree(analysis.locationOf(uri, defs.get(0)));
    }

    private JsonNode computeReferences(JsonNode params) throws Exception {
        computeDelay();
        Analyzer.Analysis analysis = analysisAt(params);
        ArrayNode result = Json.MAPPER.createArrayNode();
        if (analysis == null) {
            return result;
        }
        int offset = analysis.index().offsetAt(
                Json.MAPPER.convertValue(params.get("position"), Types.Position.class), encoding);
        Analyzer.Token token = analysis.tokenAt(offset);
        if (token == null) {
            return result;
        }
        boolean includeDeclaration = params.path("context").path("includeDeclaration").asBoolean(false);
        String uri = params.path("textDocument").path("uri").asText();
        if (includeDeclaration) {
            for (Analyzer.Token def : analysis.definitionsOf(token.name())) {
                result.add(Json.MAPPER.valueToTree(analysis.locationOf(uri, def)));
            }
        }
        for (Analyzer.Token ref : analysis.referencesOf(token.name())) {
            result.add(Json.MAPPER.valueToTree(analysis.locationOf(uri, ref)));
        }
        return result;
    }

    private Analyzer.Analysis analysisAt(JsonNode params) {
        JsonNode doc = params != null ? params.get("textDocument") : null;
        if (doc == null || params.get("position") == null) {
            return null;
        }
        TextDocument document = store.get(doc.path("uri").asText());
        if (document == null) {
            return null;
        }
        return Analyzer.analyze(document.text(), encoding);
    }

    // ------------------------------------------------------------------
    // Outgoing messages
    // ------------------------------------------------------------------

    private void sendResult(JsonNode id, JsonNode result) {
        var response = Json.obj();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        write(response);
    }

    private void sendError(JsonNode id, int code, String message) {
        var error = Json.obj();
        error.put("code", code);
        error.put("message", message);
        var response = Json.obj();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("error", error);
        write(response);
    }

    private void sendNotification(String method, JsonNode params) {
        var notification = Json.obj();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        write(notification);
    }

    private void write(JsonNode message) {
        try {
            writer.write(Json.MAPPER.writeValueAsBytes(message));
        } catch (IOException e) {
            System.err.println("failed to write message: " + e);
        }
    }
}
