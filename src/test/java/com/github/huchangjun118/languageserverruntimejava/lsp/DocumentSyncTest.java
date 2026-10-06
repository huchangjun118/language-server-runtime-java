package com.github.huchangjun118.languageserverruntimejava.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentSyncTest {

    private static JsonNode range(int sl, int sc, int el, int ec) {
        var r = Json.obj();
        r.set("start", Json.obj().put("line", sl).put("character", sc));
        r.set("end", Json.obj().put("line", el).put("character", ec));
        return r;
    }

    private static ArrayNode changes(JsonNode... edits) {
        ArrayNode array = Json.MAPPER.createArrayNode();
        for (JsonNode e : edits) {
            array.add(e);
        }
        return array;
    }

    private static JsonNode edit(JsonNode range, String text) {
        var e = Json.obj();
        if (range != null) {
            e.set("range", range);
        }
        e.put("text", text);
        return e;
    }

    private static String textOf(TestClient client, String uri) {
        TextDocument doc = client.server.documents().get(uri);
        return doc == null ? null : doc.text();
    }

    @Test
    void singleRangeEdit() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///sync1.txt";
            client.didOpen(uri, "def alpha\nref beta\n", 1);
            client.didChange(uri, 2, changes(edit(range(1, 4, 1, 8), "alpha")));
            waitForVersion(client, uri, 2);
            assertEquals("def alpha\nref alpha\n", textOf(client, uri));
        }
    }

    @Test
    void multipleEditsInOneNotificationApplyInArrayOrder() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///sync2.txt";
            client.didOpen(uri, "def a\nref b\n", 1);
            // Ranges deliberately NOT sorted: later lines first. Each edit applies
            // to the result of the previous one (LSP semantics).
            client.didChange(uri, 2, changes(
                    edit(range(1, 4, 1, 5), "a"),   // line 1: "ref b" -> "ref a"
                    edit(range(0, 4, 0, 5), "z"))); // line 0: "def a" -> "def z"
            waitForVersion(client, uri, 2);
            assertEquals("def z\nref a\n", textOf(client, uri));
        }
    }

    @Test
    void overlappingEditsApplySequentially() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///sync3.txt";
            client.didOpen(uri, "def abcdef\n", 1);
            // Overlapping edits: first replaces "bcd" with "XYZ", then the
            // second edit targets the result of the first.
            client.didChange(uri, 2, changes(
                    edit(range(0, 5, 0, 8), "XYZ"),  // "def abcdef" -> "def aXYZef"
                    edit(range(0, 5, 0, 7), "q")));  // "def aXYZef" -> "def aqZef"
            waitForVersion(client, uri, 2);
            assertEquals("def aqZef\n", textOf(client, uri));
        }
    }

    @Test
    void versionRegressionAndJumpsDoNotBlockUpdates() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///sync4.txt";
            client.didOpen(uri, "def a\n", 10);
            // Version jumps backwards and forwards: the client version is
            // authoritative, content must still converge.
            client.didChange(uri, 3, changes(edit(range(0, 4, 0, 5), "b")));
            waitForVersion(client, uri, 3);
            assertEquals("def b\n", textOf(client, uri));
            client.didChange(uri, 99, changes(edit(range(0, 4, 0, 5), "c")));
            waitForVersion(client, uri, 99);
            assertEquals("def c\n", textOf(client, uri));
            assertEquals(99, client.server.documents().get(uri).version());
        }
    }

    @Test
    void crossLineEdit() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///sync5.txt";
            client.didOpen(uri, "def a\nref b\nref c\n", 1);
            // Delete from middle of line 0 to middle of line 2 (merges lines).
            client.didChange(uri, 2, changes(edit(range(0, 3, 2, 3), "")));
            waitForVersion(client, uri, 2);
            assertEquals("def c\n", textOf(client, uri));
        }
    }

    @Test
    void endOfLineEdit() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///sync6.txt";
            client.didOpen(uri, "def a\nref b\n", 1);
            // Insert at the very end of line 0 (position == line length).
            client.didChange(uri, 2, changes(edit(range(0, 5, 0, 5), " x")));
            waitForVersion(client, uri, 2);
            assertEquals("def a x\nref b\n", textOf(client, uri));
        }
    }

    @Test
    void fullReplaceChange() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///sync7.txt";
            client.didOpen(uri, "def a\n", 1);
            client.didChange(uri, 2, changes(edit(null, "ref z\ndef z\n")));
            waitForVersion(client, uri, 2);
            assertEquals("ref z\ndef z\n", textOf(client, uri));
        }
    }

    @Test
    void changeWithUtf8Encoding() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize("utf-8");
            String uri = "file:///sync8.txt";
            client.didOpen(uri, "def 😀x\n", 1);
            // "def " = 4 utf-8 units, emoji = 4 units: replace "😀x" (units 4..9) with "y"
            client.didChange(uri, 2, changes(edit(range(0, 4, 0, 9), "y")));
            waitForVersion(client, uri, 2);
            assertEquals("def y\n", textOf(client, uri));
        }
    }

    @Test
    void closeRemovesDocument() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize();
            String uri = "file:///sync9.txt";
            client.didOpen(uri, "def a\n", 1);
            client.awaitDiagnostics(uri, 5000);
            client.didClose(uri);
            long deadline = System.currentTimeMillis() + 5000;
            while (textOf(client, uri) != null && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertNull(textOf(client, uri));
        }
    }

    private static void waitForVersion(TestClient client, String uri, int version) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            TextDocument doc = client.server.documents().get(uri);
            if (doc != null && doc.version() == version) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("document did not reach version " + version);
    }
}
