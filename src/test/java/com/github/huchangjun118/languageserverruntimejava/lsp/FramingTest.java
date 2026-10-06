package com.github.huchangjun118.languageserverruntimejava.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FramingTest {

    private static String frame(String json) {
        return "Content-Length: " + json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + "\r\n\r\n" + json;
    }

    private static final String INITIALIZE =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"capabilities\":{}}}";

    @Test
    void garbageBeforeValidFrameIsReportedAndFrameProcessed() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.sendRaw("garbage without a colon\r\n\r\n");
            client.sendRaw(frame(INITIALIZE));
            JsonNode response = client.awaitResponse(1, 5000);
            assertTrue(response.has("result"), "valid frame after garbage must be processed");
            JsonNode error = client.awaitNullIdResponse(5000);
            assertEquals(JsonRpc.PARSE_ERROR, error.path("error").path("code").asInt());
            assertTrue(error.path("error").path("message").asText().contains("Framing error"));
        }
    }

    @Test
    void invalidContentLengthIsReportedAndStreamResynchronizes() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.sendRaw("Content-Length: not-a-number\r\n\r\n{\"jsonrpc\":\"2.0\"}");
            client.sendRaw(frame(INITIALIZE));
            JsonNode response = client.awaitResponse(1, 5000);
            assertTrue(response.has("result"));
            JsonNode error = client.awaitNullIdResponse(5000);
            assertEquals(JsonRpc.PARSE_ERROR, error.path("error").path("code").asInt());
        }
    }

    @Test
    void truncatedBodyIsReportedAndNextMessageStillWorks() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            // Declares 200 bytes; the following frames are partially swallowed,
            // then the reader resynchronizes on the last (intact) frame.
            client.sendRaw("Content-Length: 200\r\n\r\n{}");
            client.sendRaw(frame("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\"}"));
            client.sendRaw(frame(INITIALIZE)); // swallowed by the 200-byte read
            client.sendRaw(frame(INITIALIZE)); // partially swallowed
            client.sendRaw(frame(INITIALIZE)); // intact: resynchronization target
            JsonNode response = client.awaitResponse(1, 5000);
            assertTrue(response.has("result"), "server must resynchronize after a truncated body");
            JsonNode error = client.awaitNullIdResponse(5000);
            assertEquals(JsonRpc.PARSE_ERROR, error.path("error").path("code").asInt());
        }
    }

    @Test
    void nonJsonBodyYieldsParseErrorWithNullId() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.sendRaw(frame("this is not json"));
            client.sendRaw(frame(INITIALIZE));
            JsonNode response = client.awaitResponse(1, 5000);
            assertTrue(response.has("result"));
            JsonNode error = client.awaitNullIdResponse(5000);
            assertEquals(JsonRpc.PARSE_ERROR, error.path("error").path("code").asInt());
        }
    }

    @Test
    void contentTypeHeaderIsAccepted() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.sendRaw("Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n"
                    + frame(INITIALIZE));
            JsonNode response = client.awaitResponse(1, 5000);
            assertTrue(response.has("result"));
        }
    }

    @Test
    void oversizedContentLengthIsRejected() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1024))) {
            client.sendRaw("Content-Length: 999999\r\n\r\n");
            client.sendRaw(frame(INITIALIZE));
            JsonNode response = client.awaitResponse(1, 5000);
            assertTrue(response.has("result"));
            JsonNode error = client.awaitNullIdResponse(5000);
            assertEquals(JsonRpc.PARSE_ERROR, error.path("error").path("code").asInt());
        }
    }
}
