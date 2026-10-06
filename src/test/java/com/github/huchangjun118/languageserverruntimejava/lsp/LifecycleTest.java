package com.github.huchangjun118.languageserverruntimejava.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleTest {

    @Test
    void requestBeforeInitializeIsRejected() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            var params = Json.obj();
            params.set("textDocument", Json.obj().put("uri", "file:///a.txt"));
            params.set("position", Json.obj().put("line", 0).put("character", 0));
            client.sendRequest(7, "textDocument/definition", params);
            JsonNode response = client.awaitResponse(7, 5000);
            assertEquals(JsonRpc.SERVER_NOT_INITIALIZED, response.path("error").path("code").asInt());
        }
    }

    @Test
    void initializeAdvertisesCapabilitiesAndDefaultEncoding() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.sendRequest(1, "initialize", Json.obj().set("capabilities", Json.obj()));
            JsonNode response = client.awaitResponse(1, 5000);
            JsonNode caps = response.path("result").path("capabilities");
            assertEquals("utf-16", caps.path("positionEncoding").asText());
            assertEquals(2, caps.path("textDocumentSync").path("change").asInt());
            assertTrue(caps.path("textDocumentSync").path("openClose").asBoolean());
            assertTrue(caps.path("definitionProvider").asBoolean());
            assertTrue(caps.path("referencesProvider").asBoolean());
        }
    }

    @Test
    void requestAfterShutdownIsRejectedAndExitCodeIsZero() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            client.sendRequest(2, "shutdown", null);
            JsonNode shutdownResponse = client.awaitResponse(2, 5000);
            assertTrue(shutdownResponse.has("result"));

            var params = Json.obj();
            params.set("textDocument", Json.obj().put("uri", "file:///a.txt"));
            params.set("position", Json.obj().put("line", 0).put("character", 0));
            client.sendRequest(3, "textDocument/definition", params);
            JsonNode response = client.awaitResponse(3, 5000);
            assertEquals(JsonRpc.INVALID_REQUEST, response.path("error").path("code").asInt());

            client.sendNotification("exit", null);
            assertTrue(client.serverTerminated.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(java.util.List.of(0), client.exitCodes);
        }
    }

    @Test
    void exitWithoutShutdownUsesCodeOne() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            client.sendNotification("exit", null);
            assertTrue(client.serverTerminated.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(java.util.List.of(1), client.exitCodes);
        }
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            client.sendRequest(9, "workspace/unknownThing", Json.obj());
            JsonNode response = client.awaitResponse(9, 5000);
            assertEquals(JsonRpc.METHOD_NOT_FOUND, response.path("error").path("code").asInt());
        }
    }

    @Test
    void doubleInitializeIsRejected() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            client.sendRequest(2, "initialize", Json.obj());
            JsonNode response = client.awaitResponse(2, 5000);
            assertEquals(JsonRpc.INVALID_REQUEST, response.path("error").path("code").asInt());
        }
    }
}
