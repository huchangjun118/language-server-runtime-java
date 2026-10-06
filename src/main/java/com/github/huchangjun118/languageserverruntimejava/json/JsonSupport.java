package com.github.huchangjun118.languageserverruntimejava.json;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared Jackson ObjectMapper. */
public final class JsonSupport {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {
    }
}
