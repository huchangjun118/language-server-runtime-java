package com.github.huchangjun118.languageserverruntimejava;

import com.github.huchangjun118.languageserverruntimejava.server.LanguageServer;

/**
 * Entry point of the language server. Communicates over standard input/output.
 */
public final class Application {
    private Application() {
    }

    public static void main(String[] args) throws Exception {
        LanguageServer server = new LanguageServer(System.in, System.out);
        int exitCode = server.run();
        System.exit(exitCode);
    }
}
