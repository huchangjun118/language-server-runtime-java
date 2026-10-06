package com.github.huchangjun118.languageserverruntimejava.server;

/** Lifecycle states of the language server session. */
public enum ServerState {
    /** Before the initialize request. */
    CREATED,
    /** initialize received, session active. */
    INITIALIZED,
    /** shutdown received, waiting for exit. */
    SHUTDOWN
}
