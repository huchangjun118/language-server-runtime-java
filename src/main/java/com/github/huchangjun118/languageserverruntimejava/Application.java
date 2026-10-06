package com.github.huchangjun118.languageserverruntimejava;

import com.github.huchangjun118.languageserverruntimejava.lsp.LspServer;
import com.github.huchangjun118.languageserverruntimejava.lsp.ServerConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;

/**
 * Entry point of the standalone language server.
 * Speaks the Language Server Protocol over stdin/stdout; all logging goes to
 * stderr so the protocol stream on stdout stays clean.
 */
public final class Application {
    private Application() {
    }

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.fromSystemProperties();
        LspServer server = new LspServer(
                new BufferedInputStream(System.in),
                new BufferedOutputStream(System.out),
                config);
        server.run();
    }
}
