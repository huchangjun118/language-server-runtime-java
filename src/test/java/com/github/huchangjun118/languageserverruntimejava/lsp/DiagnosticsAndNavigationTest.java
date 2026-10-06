package com.github.huchangjun118.languageserverruntimejava.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsAndNavigationTest {

    private static final String DOC =
            "def apple\n"
            + "def banana\n"
            + "ref apple\n"
            + "def apple\n"   // duplicate definition
            + "ref cherry\n"  // undefined reference
            + "ref apple\n";

    private static com.fasterxml.jackson.databind.node.ObjectNode docParams(String uri, int line, int character) {
        var params = Json.obj();
        params.set("textDocument", Json.obj().put("uri", uri));
        params.set("position", Json.obj().put("line", line).put("character", character));
        return params;
    }

    @Test
    void duplicateAndUndefinedProduceDistinctDiagnostics() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///diag1.txt";
            client.didOpen(uri, DOC, 1);
            JsonNode notification = client.awaitDiagnostics(uri, 5000);
            JsonNode diagnostics = notification.path("params").path("diagnostics");
            assertEquals(2, diagnostics.size());
            JsonNode duplicate = diagnostics.get(0);
            assertEquals(Analyzer.CODE_DUPLICATE_DEFINITION, duplicate.path("code").asText());
            assertEquals(1, duplicate.path("severity").asInt());
            assertEquals(3, duplicate.path("range").path("start").path("line").asInt());
            assertEquals(4, duplicate.path("range").path("start").path("character").asInt());
            assertEquals(9, duplicate.path("range").path("end").path("character").asInt());
            JsonNode undefined = diagnostics.get(1);
            assertEquals(Analyzer.CODE_UNDEFINED_REFERENCE, undefined.path("code").asText());
            assertEquals(4, undefined.path("range").path("start").path("line").asInt());
        }
    }

    @Test
    void unknownKeywordProducesWarning() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///diag2.txt";
            client.didOpen(uri, "bogus thing\n", 1);
            JsonNode notification = client.awaitDiagnostics(uri, 5000);
            JsonNode diagnostics = notification.path("params").path("diagnostics");
            assertEquals(1, diagnostics.size());
            assertEquals(Analyzer.CODE_UNKNOWN_KEYWORD, diagnostics.get(0).path("code").asText());
            assertEquals(2, diagnostics.get(0).path("severity").asInt());
        }
    }

    @Test
    void diagnosticsUpdateAfterChangeAndClearOnClose() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///diag3.txt";
            client.didOpen(uri, "ref missing\n", 1);
            JsonNode first = client.awaitDiagnostics(uri, 5000);
            assertEquals(1, first.path("params").path("diagnostics").size());

            // Fix the error: define the identifier before the reference.
            var edit = Json.obj();
            var insertRange = Json.obj();
            insertRange.set("start", Json.obj().put("line", 0).put("character", 0));
            insertRange.set("end", Json.obj().put("line", 0).put("character", 0));
            edit.set("range", insertRange);
            edit.put("text", "def missing\n");
            client.didChange(uri, 2, Json.MAPPER.createArrayNode().add(edit));
            client.awaitNotification(n -> "textDocument/publishDiagnostics".equals(n.path("method").asText())
                    && uri.equals(n.path("params").path("uri").asText())
                    && n.path("params").path("diagnostics").size() == 0, 5000);

            // Closing clears the diagnostics with an empty publish.
            client.didClose(uri);
            List<JsonNode> publishes = client.diagnosticsFor(uri);
            assertTrue(publishes.size() >= 2);
            JsonNode last = publishes.get(publishes.size() - 1);
            assertEquals(0, last.path("params").path("diagnostics").size());
        }
    }

    @Test
    void staleDiagnosticsAreNotPublished() throws Exception {
        // Long debounce: rapid successive changes must only publish the last state.
        try (TestClient client = new TestClient(new ServerConfig(200, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///diag4.txt";
            client.didOpen(uri, "ref gone\n", 1);
            var edit = Json.obj();
            edit.put("text", "def ok\n"); // full replace, no errors
            client.didChange(uri, 2, Json.MAPPER.createArrayNode().add(edit));
            var edit2 = Json.obj();
            edit2.put("text", "def fine\nref fine\n");
            client.didChange(uri, 3, Json.MAPPER.createArrayNode().add(edit2));

            JsonNode notification = client.awaitDiagnostics(uri, 5000);
            assertEquals(0, notification.path("params").path("diagnostics").size());
            Thread.sleep(500); // longer than the debounce: nothing more may arrive
            List<JsonNode> publishes = client.diagnosticsFor(uri);
            assertEquals(1, publishes.size(), "superseded versions must not be published");
        }
    }

    @Test
    void definitionResolvesToFirstDefinition() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///nav1.txt";
            client.didOpen(uri, DOC, 1);
            client.awaitDiagnostics(uri, 5000);

            // "ref apple" on line 2, identifier starts at character 4
            client.sendRequest(10, "textDocument/definition", docParams(uri, 2, 6));
            JsonNode response = client.awaitResponse(10, 5000);
            JsonNode range = response.path("result").path("range");
            assertEquals(uri, response.path("result").path("uri").asText());
            assertEquals(0, range.path("start").path("line").asInt());
            assertEquals(4, range.path("start").path("character").asInt());
            assertEquals(9, range.path("end").path("character").asInt());
        }
    }

    @Test
    void definitionOfUndefinedReferenceIsNull() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///nav2.txt";
            client.didOpen(uri, DOC, 1);
            client.awaitDiagnostics(uri, 5000);
            client.sendRequest(11, "textDocument/definition", docParams(uri, 4, 5));
            JsonNode response = client.awaitResponse(11, 5000);
            assertTrue(response.path("result").isNull());
        }
    }

    @Test
    void referencesIncludeAllOccurrences() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///nav3.txt";
            client.didOpen(uri, DOC, 1);
            client.awaitDiagnostics(uri, 5000);

            var params = docParams(uri, 0, 5); // on the definition of apple
            params.set("context", Json.obj().put("includeDeclaration", true));
            client.sendRequest(12, "textDocument/references", params);
            JsonNode response = client.awaitResponse(12, 5000);
            JsonNode result = response.path("result");
            // apple: defs on lines 0 and 3, refs on lines 2 and 5
            assertEquals(4, result.size());
            assertEquals(0, result.get(0).path("range").path("start").path("line").asInt());
            assertEquals(3, result.get(1).path("range").path("start").path("line").asInt());
            assertEquals(2, result.get(2).path("range").path("start").path("line").asInt());
            assertEquals(5, result.get(3).path("range").path("start").path("line").asInt());

            // Without declarations: only the two references.
            var params2 = docParams(uri, 0, 5);
            params2.set("context", Json.obj().put("includeDeclaration", false));
            client.sendRequest(13, "textDocument/references", params2);
            JsonNode response2 = client.awaitResponse(13, 5000);
            assertEquals(2, response2.path("result").size());
        }
    }

    @Test
    void requestsOnUnknownDocumentReturnEmptyResults() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            client.sendRequest(14, "textDocument/definition", docParams("file:///nope.txt", 0, 0));
            JsonNode response = client.awaitResponse(14, 5000);
            assertTrue(response.path("result").isNull());
            var params = docParams("file:///nope.txt", 0, 0);
            params.set("context", Json.obj().put("includeDeclaration", true));
            client.sendRequest(15, "textDocument/references", params);
            JsonNode response2 = client.awaitResponse(15, 5000);
            assertEquals(0, response2.path("result").size());
        }
    }
}
