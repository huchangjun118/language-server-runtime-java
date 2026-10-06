package com.github.huchangjun118.languageserverruntimejava.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationConcurrencyTest {

    private static final String DOC = "def apple\nref apple\nref apple\n";

    private static JsonNode defParams(String uri) {
        var params = Json.obj();
        params.set("textDocument", Json.obj().put("uri", uri));
        params.set("position", Json.obj().put("line", 1).put("character", 5));
        return params;
    }

    @Test
    void cancelledRequestReturnsCancellationErrorAndNoResult() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 400, 1 << 20))) {
            client.initialize();
            String uri = "file:///cancel1.txt";
            client.didOpen(uri, DOC, 1);
            client.awaitDiagnostics(uri, 5000);

            client.sendRequest(42, "textDocument/definition", defParams(uri));
            client.cancelRequest(42);
            JsonNode response = client.awaitResponse(42, 5000);
            assertEquals(JsonRpc.REQUEST_CANCELLED, response.path("error").path("code").asInt());

            // No further message for id 1 may ever arrive (the slow computation
            // finishes after 400ms; wait well beyond that).
            Thread.sleep(800);
            long count = client.notifications().stream()
                    .filter(n -> String.valueOf(n.path("id").asText()).equals("42"))
                    .count();
            assertEquals(0, count);
        }
    }

    @Test
    void cancelOfUnknownIdIsIgnored() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            client.cancelRequest(999);
            // Server still healthy:
            client.sendRequest(2, "textDocument/definition", defParams("file:///none.txt"));
            JsonNode response = client.awaitResponse(2, 5000);
            assertTrue(response.has("result"));
        }
    }

    @Test
    void concurrentRequestsAllGetResponses() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 100, 1 << 20))) {
            client.initialize();
            String uri = "file:///conc1.txt";
            client.didOpen(uri, DOC, 1);
            client.awaitDiagnostics(uri, 5000);

            int n = 8;
            long start = System.currentTimeMillis();
            for (int id = 10; id < 10 + n; id++) {
                client.sendRequest(id, "textDocument/definition", defParams(uri));
            }
            for (int id = 10; id < 10 + n; id++) {
                JsonNode response = client.awaitResponse(id, 10000);
                assertTrue(response.has("result"), "request " + id + " must be answered");
                assertEquals(0, response.path("result").path("range").path("start").path("line").asInt());
            }
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < n * 100L, "requests must run concurrently, took " + elapsed + "ms");
        }
    }

    @Test
    void slowRequestDoesNotBlockNotificationsOrLaterRequests() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 500, 1 << 20))) {
            client.initialize();
            String uri = "file:///conc2.txt";
            client.didOpen(uri, "def a\nref a\n", 1);
            client.awaitDiagnostics(uri, 5000);

            client.sendRequest(1, "textDocument/definition", defParams(uri)); // slow: 500ms
            // While it runs, a document change must take effect in order...
            var edit = Json.obj();
            edit.put("text", "def b\nref b\n");
            client.didChange(uri, 2, Json.MAPPER.createArrayNode().add(edit));
            // ...and a later request on another document must complete without
            // waiting for the slow one.
            String uri2 = "file:///conc2b.txt";
            client.didOpen(uri2, "def c\n", 1);
            var params2 = Json.obj();
            params2.set("textDocument", Json.obj().put("uri", uri2));
            params2.set("position", Json.obj().put("line", 0).put("character", 4));
            client.sendRequest(2, "textDocument/references", params2);

            JsonNode fast = client.awaitResponse(2, 5000);
            assertTrue(fast.has("result"));
            JsonNode slow = client.awaitResponse(1, 5000);
            assertTrue(slow.has("result") || slow.has("error"));
            // The change was applied in arrival order even during the slow request.
            assertEquals("def b\nref b\n", client.server.documents().get(uri).text());
        }
    }

    @Test
    void changesFromOneDocumentApplyInArrivalOrder() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///order.txt";
            client.didOpen(uri, "", 1);
            // Rapid-fire full-replace changes; the last one must win.
            for (int v = 2; v <= 20; v++) {
                var edit = Json.obj();
                edit.put("text", "def v" + v + "\n");
                client.didChange(uri, v, Json.MAPPER.createArrayNode().add(edit));
            }
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                TextDocument doc = client.server.documents().get(uri);
                if (doc != null && doc.version() == 20) {
                    break;
                }
                Thread.sleep(5);
            }
            assertEquals("def v20\n", client.server.documents().get(uri).text());
        }
    }
}
