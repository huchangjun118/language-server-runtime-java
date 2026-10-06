package com.github.huchangjun118.languageserverruntimejava.lsp;

/** JSON-RPC / LSP error codes. */
public final class JsonRpc {
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    /** LSP: request failed because the server is not initialized yet. */
    public static final int SERVER_NOT_INITIALIZED = -32002;
    /** LSP: request was cancelled by the client. */
    public static final int REQUEST_CANCELLED = -32800;
    /** LSP: request failed because the document was modified meanwhile. */
    public static final int CONTENT_MODIFIED = -32801;

    private JsonRpc() {
    }
}
