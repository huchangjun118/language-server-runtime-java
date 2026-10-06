package com.github.huchangjun118.languageserverruntimejava.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.huchangjun118.languageserverruntimejava.analysis.AnalysisResult;
import com.github.huchangjun118.languageserverruntimejava.analysis.AnalysisResult.SymbolItem;
import com.github.huchangjun118.languageserverruntimejava.analysis.Analyzer;
import com.github.huchangjun118.languageserverruntimejava.document.DocumentManager;
import com.github.huchangjun118.languageserverruntimejava.document.TextDocument;
import com.github.huchangjun118.languageserverruntimejava.json.JsonSupport;
import com.github.huchangjun118.languageserverruntimejava.protocol.ErrorCodes;
import com.github.huchangjun118.languageserverruntimejava.protocol.MessageIO;
import com.github.huchangjun118.languageserverruntimejava.protocol.ProtocolException;
import com.github.huchangjun118.languageserverruntimejava.protocol.Types.Position;
import com.github.huchangjun118.languageserverruntimejava.protocol.Types.Range;
import com.github.huchangjun118.languageserverruntimejava.text.PositionEncoding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The language server main loop: reads framed messages, dispatches them
 * according to the session lifecycle and writes responses.
 */
public class LanguageServer {

    private final MessageIO io;
    private final DocumentManager documents = new DocumentManager();
    private final Analyzer analyzer = new Analyzer();

    private final ExecutorService requestExecutor =
            Executors.newCachedThreadPool(daemonFactory("lsp-request"));
    private final ExecutorService notificationExecutor =
            Executors.newSingleThreadExecutor(daemonFactory("lsp-notification"));
    private final ExecutorService diagnosticsExecutor =
            Executors.newSingleThreadExecutor(daemonFactory("lsp-diagnostics"));

    /** In-flight cancellable requests, keyed by canonical request id. */
    private final Map<String, RequestTask> inFlight = new ConcurrentHashMap<>();
    /** Ids cancelled before their request started executing. */
    private final Set<String> cancelledIds = ConcurrentHashMap.newKeySet();

    private volatile ServerState state = ServerState.CREATED;
    private volatile PositionEncoding encoding = PositionEncoding.UTF_16;
    private volatile boolean exitRequested = false;

    public LanguageServer(InputStream in, OutputStream out) {
        this.io = new MessageIO(in, out);
    }

    /** Visible for testing. */
    LanguageServer(MessageIO io) {
        this.io = io;
    }

    /**
     * Runs the read/dispatch loop until exit.
     *
     * @return process exit code (0 after clean shutdown, 1 otherwise)
     */
    public int run() throws IOException {
        int exitCode = 1;
        try {
            while (!exitRequested) {
                JsonNode message;
                try {
                    message = io.readMessage();
                } catch (ProtocolException e) {
                    // Framing is broken for this message only; report and try
                    // to resynchronize on the next header line.
                    sendError(NullNode.getInstance(), ErrorCodes.PARSE_ERROR,
                            "protocol framing error: " + e.getMessage());
                    continue;
                } catch (JsonProcessingException e) {
                    sendError(NullNode.getInstance(), ErrorCodes.PARSE_ERROR,
                            "invalid JSON: " + e.getOriginalMessage());
                    continue;
                }
                if (message == null) {
                    break; // clean end of stream
                }
                dispatch(message);
            }
            if (exitRequested && state == ServerState.SHUTDOWN) {
                exitCode = 0;
            }
        } finally {
            shutdownExecutors();
        }
        return exitCode;
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    private void dispatch(JsonNode message) throws IOException {
        if (!message.isObject()) {
            sendError(NullNode.getInstance(), ErrorCodes.INVALID_REQUEST,
                    "message must be a JSON object");
            return;
        }
        JsonNode idNode = message.get("id");
        JsonNode methodNode = message.get("method");
        boolean isRequest = idNode != null && !idNode.isNull();
        if (methodNode == null || !methodNode.isTextual()) {
            if (isRequest) {
                sendError(idNode, ErrorCodes.INVALID_REQUEST, "missing method");
            }
            return; // responses from the client and stray notifications are ignored
        }
        String method = methodNode.asText();
        JsonNode params = message.get("params");

        if (isRequest) {
            dispatchRequest(idNode, method, params);
        } else {
            dispatchNotification(method, params);
        }
    }

    private void dispatchRequest(JsonNode id, String method, JsonNode params) throws IOException {
        switch (method) {
            case "initialize" -> {
                if (state != ServerState.CREATED) {
                    sendError(id, ErrorCodes.INVALID_REQUEST, "server is already initialized");
                    return;
                }
                handleInitialize(id, params);
            }
            case "shutdown" -> {
                if (state == ServerState.CREATED) {
                    sendError(id, ErrorCodes.SERVER_NOT_INITIALIZED, "server is not initialized");
                    return;
                }
                if (state == ServerState.SHUTDOWN) {
                    sendError(id, ErrorCodes.INVALID_REQUEST, "server is already shut down");
                    return;
                }
                state = ServerState.SHUTDOWN;
                sendResponse(id, NullNode.getInstance());
            }
            default -> {
                if (state == ServerState.CREATED) {
                    sendError(id, ErrorCodes.SERVER_NOT_INITIALIZED,
                            "server is not initialized: '" + method + "' was rejected");
                    return;
                }
                if (state == ServerState.SHUTDOWN) {
                    sendError(id, ErrorCodes.INVALID_REQUEST,
                            "server is shut down: '" + method + "' was rejected");
                    return;
                }
                submitRequest(id, method, params);
            }
        }
    }

    private void dispatchNotification(String method, JsonNode params) throws IOException {
        switch (method) {
            case "exit" -> exitRequested = true;
            case "initialized" -> {
                // no-op
            }
            case "$/cancelRequest" -> handleCancel(params);
            case "textDocument/didOpen", "textDocument/didChange", "textDocument/didClose" -> {
                if (state != ServerState.INITIALIZED) {
                    return; // dropped: outside an active session
                }
                // Serialized on one thread so changes apply in arrival order.
                notificationExecutor.submit(() -> handleDocumentNotification(method, params));
            }
            default -> {
                // Unknown notifications are ignored per JSON-RPC.
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private void handleInitialize(JsonNode id, JsonNode params) throws IOException {
        JsonNode encodings = params == null ? null
                : params.path("capabilities").path("general").path("positionEncodings");
        PositionEncoding chosen = PositionEncoding.UTF_16;
        if (encodings != null && encodings.isArray()) {
            for (JsonNode candidate : encodings) {
                PositionEncoding supported = PositionEncoding.fromWireName(candidate.asText(""));
                if (supported != null) {
                    chosen = supported;
                    break;
                }
            }
        }
        this.encoding = chosen;
        this.state = ServerState.INITIALIZED;

        ObjectNode sync = JsonSupport.MAPPER.createObjectNode();
        sync.put("openClose", true);
        sync.put("change", 2); // TextDocumentSyncKind.Incremental

        ObjectNode capabilities = JsonSupport.MAPPER.createObjectNode();
        capabilities.put("positionEncoding", chosen.wireName());
        capabilities.set("textDocumentSync", sync);
        capabilities.put("definitionProvider", true);
        capabilities.put("referencesProvider", true);

        ObjectNode serverInfo = JsonSupport.MAPPER.createObjectNode();
        serverInfo.put("name", "mini-config-language-server");
        serverInfo.put("version", "1.0.0");

        ObjectNode result = JsonSupport.MAPPER.createObjectNode();
        result.set("capabilities", capabilities);
        result.set("serverInfo", serverInfo);
        sendResponse(id, result);
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    private void handleCancel(JsonNode params) {
        JsonNode id = params == null ? null : params.get("id");
        if (id == null || id.isNull()) {
            return;
        }
        String key = id.toString();
        RequestTask task = inFlight.get(key);
        if (task != null) {
            task.cancel();
        } else {
            // The request may not have started yet; remember the cancellation.
            cancelledIds.add(key);
        }
    }

    private void submitRequest(JsonNode id, String method, JsonNode params) {
        String key = id.toString();
        if (cancelledIds.remove(key)) {
            sendQuietly(errorMessage(id, ErrorCodes.REQUEST_CANCELLED,
                    "request was cancelled before it started"));
            return;
        }
        RequestTask task = new RequestTask();
        inFlight.put(key, task);
        requestExecutor.submit(() -> {
            try {
                JsonNode result;
                try {
                    result = handleRequest(method, params, task);
                } catch (UnknownMethodException e) {
                    sendTaskMessage(task, errorMessage(id, ErrorCodes.METHOD_NOT_FOUND,
                            "unknown method: '" + method + "'"));
                    return;
                } catch (InvalidParamsException e) {
                    sendTaskMessage(task, errorMessage(id, ErrorCodes.INVALID_PARAMS, e.getMessage()));
                    return;
                } catch (Exception e) {
                    sendTaskMessage(task, errorMessage(id, ErrorCodes.INTERNAL_ERROR,
                            String.valueOf(e.getMessage())));
                    return;
                }
                sendTaskMessage(task, resultMessage(id, result));
            } finally {
                inFlight.remove(key, task);
            }
        });
    }

    /** Sends exactly one terminal message for a task; cancellation wins. */
    private void sendTaskMessage(RequestTask task, ObjectNode message) {
        synchronized (task) {
            if (task.cancelled && message.path("error").path("code").asInt()
                    != ErrorCodes.REQUEST_CANCELLED) {
                message = errorMessage(message.get("id"), ErrorCodes.REQUEST_CANCELLED,
                        "request was cancelled");
            }
            sendQuietly(message);
        }
    }

    private JsonNode handleRequest(String method, JsonNode params, RequestTask task)
            throws InvalidParamsException, UnknownMethodException {
        return switch (method) {
            case "textDocument/definition" -> handleDefinition(params);
            case "textDocument/references" -> handleReferences(params);
            default -> throw new UnknownMethodException();
        };
    }

    // ------------------------------------------------------------------
    // Document notifications
    // ------------------------------------------------------------------

    private void handleDocumentNotification(String method, JsonNode params) {
        try {
            switch (method) {
                case "textDocument/didOpen" -> {
                    JsonNode doc = params == null ? null : params.get("textDocument");
                    if (doc == null || !doc.path("uri").isTextual()) {
                        return;
                    }
                    String uri = doc.get("uri").asText();
                    int version = doc.path("version").isInt() ? doc.get("version").asInt() : 0;
                    String text = doc.path("text").isTextual() ? doc.get("text").asText() : "";
                    documents.open(uri, version, text);
                    scheduleDiagnostics(uri, version);
                }
                case "textDocument/didChange" -> {
                    JsonNode doc = params == null ? null : params.get("textDocument");
                    JsonNode changes = params == null ? null : params.get("contentChanges");
                    if (doc == null || !doc.path("uri").isTextual()
                            || !doc.path("version").isNumber()
                            || changes == null || !changes.isArray()) {
                        return;
                    }
                    String uri = doc.get("uri").asText();
                    int version = doc.get("version").asInt();
                    List<JsonNode> edits = new ArrayList<>();
                    changes.forEach(edits::add);
                    Optional<Integer> applied = documents.change(uri, version, edits, encoding);
                    applied.ifPresent(v -> scheduleDiagnostics(uri, v));
                }
                case "textDocument/didClose" -> {
                    JsonNode doc = params == null ? null : params.get("textDocument");
                    if (doc == null || !doc.path("uri").isTextual()) {
                        return;
                    }
                    String uri = doc.get("uri").asText();
                    documents.close(uri);
                    publishDiagnostics(uri, List.of());
                }
            }
        } catch (Exception e) {
            // A malformed notification must never kill the notification loop.
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    private void scheduleDiagnostics(String uri, int version) {
        diagnosticsExecutor.submit(() -> {
            Optional<TextDocument> document = documents.get(uri);
            if (document.isEmpty() || document.get().version() != version) {
                return; // stale: a newer change superseded this computation
            }
            AnalysisResult result = analyzer.analyze(document.get().text(), encoding);
            Optional<Integer> current = documents.versionOf(uri);
            if (current.isEmpty() || current.get() != version) {
                return; // changed or closed while computing: do not publish
            }
            List<ObjectNode> items = new ArrayList<>();
            for (AnalysisResult.DiagnosticItem d : result.diagnostics()) {
                ObjectNode node = JsonSupport.MAPPER.createObjectNode();
                node.set("range", rangeJson(d.range()));
                node.put("severity", 1); // Error
                node.put("code", d.code());
                node.put("source", "mini-config");
                node.put("message", d.message());
                items.add(node);
            }
            publishDiagnostics(uri, items);
        });
    }

    private void publishDiagnostics(String uri, List<? extends JsonNode> diagnostics) {
        ObjectNode params = JsonSupport.MAPPER.createObjectNode();
        params.put("uri", uri);
        ArrayNode array = JsonSupport.MAPPER.createArrayNode();
        diagnostics.forEach(array::add);
        params.set("diagnostics", array);
        sendQuietly(notificationMessage("textDocument/publishDiagnostics", params));
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private JsonNode handleDefinition(JsonNode params) throws InvalidParamsException {
        String uri = requireUri(params);
        Position position = requirePosition(params);
        Optional<TextDocument> document = documents.get(uri);
        if (document.isEmpty()) {
            return NullNode.getInstance();
        }
        AnalysisResult result = analyzer.analyze(document.get().text(), encoding);
        SymbolItem target = symbolAt(result, position);
        if (target == null) {
            return NullNode.getInstance();
        }
        SymbolItem definition = null;
        for (SymbolItem symbol : result.symbols()) {
            if (symbol.definition() && symbol.name().equals(target.name())) {
                definition = symbol;
                break;
            }
        }
        if (definition == null) {
            return NullNode.getInstance();
        }
        return locationJson(uri, definition.range());
    }

    private JsonNode handleReferences(JsonNode params) throws InvalidParamsException {
        String uri = requireUri(params);
        Position position = requirePosition(params);
        boolean includeDeclaration = params.path("context").path("includeDeclaration").asBoolean(false);
        Optional<TextDocument> document = documents.get(uri);
        ArrayNode locations = JsonSupport.MAPPER.createArrayNode();
        if (document.isEmpty()) {
            return locations;
        }
        AnalysisResult result = analyzer.analyze(document.get().text(), encoding);
        SymbolItem target = symbolAt(result, position);
        if (target == null) {
            return locations;
        }
        for (SymbolItem symbol : result.symbols()) {
            if (!symbol.name().equals(target.name())) {
                continue;
            }
            if (symbol.definition() && !includeDeclaration) {
                continue;
            }
            locations.add(locationJson(uri, symbol.range()));
        }
        return locations;
    }

    private SymbolItem symbolAt(AnalysisResult result, Position position) {
        for (SymbolItem symbol : result.symbols()) {
            Range range = symbol.range();
            if (range.start().line() != position.line()) {
                continue;
            }
            if (range.start().character() <= position.character()
                    && position.character() <= range.end().character()) {
                return symbol;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    private String requireUri(JsonNode params) throws InvalidParamsException {
        if (params == null || !params.path("textDocument").path("uri").isTextual()) {
            throw new InvalidParamsException("missing textDocument.uri");
        }
        return params.get("textDocument").get("uri").asText();
    }

    private Position requirePosition(JsonNode params) throws InvalidParamsException {
        JsonNode node = params == null ? null : params.get("position");
        if (node == null || !node.path("line").isInt() || !node.path("character").isInt()) {
            throw new InvalidParamsException("missing or invalid position");
        }
        return new Position(node.get("line").asInt(), node.get("character").asInt());
    }

    private ObjectNode locationJson(String uri, Range range) {
        ObjectNode location = JsonSupport.MAPPER.createObjectNode();
        location.put("uri", uri);
        location.set("range", rangeJson(range));
        return location;
    }

    private ObjectNode rangeJson(Range range) {
        ObjectNode node = JsonSupport.MAPPER.createObjectNode();
        node.set("start", positionJson(range.start()));
        node.set("end", positionJson(range.end()));
        return node;
    }

    private ObjectNode positionJson(Position position) {
        ObjectNode node = JsonSupport.MAPPER.createObjectNode();
        node.put("line", position.line());
        node.put("character", position.character());
        return node;
    }

    private ObjectNode resultMessage(JsonNode id, JsonNode result) {
        ObjectNode message = JsonSupport.MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.set("id", id);
        message.set("result", result == null ? NullNode.getInstance() : result);
        return message;
    }

    private ObjectNode errorMessage(JsonNode id, int code, String message) {
        ObjectNode envelope = JsonSupport.MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", id == null ? NullNode.getInstance() : id);
        ObjectNode error = JsonSupport.MAPPER.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        envelope.set("error", error);
        return envelope;
    }

    private ObjectNode notificationMessage(String method, JsonNode params) {
        ObjectNode message = JsonSupport.MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params);
        return message;
    }

    private void sendResponse(JsonNode id, JsonNode result) throws IOException {
        io.writeMessage(resultMessage(id, result));
    }

    private void sendError(JsonNode id, int code, String message) throws IOException {
        io.writeMessage(errorMessage(id, code, message));
    }

    private void sendQuietly(JsonNode message) {
        try {
            io.writeMessage(message);
        } catch (IOException e) {
            // The stream is gone; nothing sensible to do.
        }
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    private void shutdownExecutors() {
        for (RequestTask task : inFlight.values()) {
            task.cancel();
        }
        requestExecutor.shutdownNow();
        notificationExecutor.shutdownNow();
        diagnosticsExecutor.shutdownNow();
        await(requestExecutor);
        await(notificationExecutor);
        await(diagnosticsExecutor);
    }

    private static void await(ExecutorService executor) {
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Tracks cancellation of one in-flight request. */
    private static final class RequestTask {
        private volatile boolean cancelled;

        void cancel() {
            synchronized (this) {
                cancelled = true;
            }
        }
    }

    private static final class UnknownMethodException extends Exception {
    }

    private static final class InvalidParamsException extends Exception {
        InvalidParamsException(String message) {
            super(message);
        }
    }
}
