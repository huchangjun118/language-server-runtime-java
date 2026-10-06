package com.github.huchangjun118.languageserverruntimejava.lsp;

/**
 * Tunables of the server, overridable via system properties:
 * <ul>
 *   <li>{@code lsp.diagnosticsDelayMs} - debounce delay before diagnostics are published (default 50)</li>
 *   <li>{@code lsp.computeDelayMs} - artificial delay inside definition/references computation (default 0)</li>
 *   <li>{@code lsp.maxMessageSize} - maximum accepted Content-Length in bytes (default 64 MiB)</li>
 * </ul>
 */
public record ServerConfig(long diagnosticsDelayMs, long computeDelayMs, long maxMessageSize) {
    public static ServerConfig defaults() {
        return new ServerConfig(50, 0, 64L * 1024 * 1024);
    }

    public static ServerConfig fromSystemProperties() {
        return new ServerConfig(
                Long.getLong("lsp.diagnosticsDelayMs", 50),
                Long.getLong("lsp.computeDelayMs", 0),
                Long.getLong("lsp.maxMessageSize", 64L * 1024 * 1024));
    }
}
