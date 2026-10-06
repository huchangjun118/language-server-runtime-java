package com.github.huchangjun118.languageserverruntimejava.lsp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared Jackson mapper for JSON-RPC payloads. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }
}
