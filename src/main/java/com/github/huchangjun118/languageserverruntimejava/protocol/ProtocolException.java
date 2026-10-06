package com.github.huchangjun118.languageserverruntimejava.protocol;

/** Raised when the base protocol framing is violated. */
public class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }
}
