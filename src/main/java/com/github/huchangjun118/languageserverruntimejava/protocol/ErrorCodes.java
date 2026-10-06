package com.github.huchangjun118.languageserverruntimejava.protocol;

/** JSON-RPC / LSP error codes. */
public final class ErrorCodes {
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int SERVER_NOT_INITIALIZED = -32002;
    public static final int REQUEST_CANCELLED = -32800;
    public static final int CONTENT_MODIFIED = -32801;

    private ErrorCodes() {
    }
}
