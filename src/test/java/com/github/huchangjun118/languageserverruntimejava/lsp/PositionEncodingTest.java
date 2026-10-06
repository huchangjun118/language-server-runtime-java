package com.github.huchangjun118.languageserverruntimejava.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionEncodingTest {

    // "a😀b" : a(1) + emoji(surrogate pair) + b
    private static final String SURROGATES = "a😀b";
    // "e" + combining acute = one grapheme, two code points
    private static final String COMBINING = "é";

    @Test
    void utf16Units() {
        LineIndex index = LineIndex.of(SURROGATES);
        // a=0, emoji=1..2 (2 utf-16 units), b=3
        assertEquals(new Types.Position(0, 3), index.positionAt(3, PositionEncoding.UTF16));
        assertEquals(3, index.offsetAt(new Types.Position(0, 3), PositionEncoding.UTF16));
        assertEquals(4, index.offsetAt(new Types.Position(0, 4), PositionEncoding.UTF16));
    }

    @Test
    void utf8Units() {
        LineIndex index = LineIndex.of(SURROGATES);
        // a=1 byte, emoji=4 bytes, b starts at unit 5
        assertEquals(new Types.Position(0, 5), index.positionAt(3, PositionEncoding.UTF8));
        assertEquals(3, index.offsetAt(new Types.Position(0, 5), PositionEncoding.UTF8));
    }

    @Test
    void utf32Units() {
        LineIndex index = LineIndex.of(SURROGATES);
        // a=1, emoji=1, b starts at unit 2
        assertEquals(new Types.Position(0, 2), index.positionAt(3, PositionEncoding.UTF32));
        assertEquals(3, index.offsetAt(new Types.Position(0, 2), PositionEncoding.UTF32));
    }

    @Test
    void combiningCharacters() {
        LineIndex index = LineIndex.of(COMBINING);
        // utf-16: e=1, combining mark=1 -> end is 2
        assertEquals(new Types.Position(0, 2), index.positionAt(2, PositionEncoding.UTF16));
        // utf-8: e=1, U+0301=2 bytes -> end is 3
        assertEquals(new Types.Position(0, 3), index.positionAt(2, PositionEncoding.UTF8));
        // utf-32: two code points
        assertEquals(new Types.Position(0, 2), index.positionAt(2, PositionEncoding.UTF32));
    }

    @Test
    void surrogatePairIsNeverSplit() {
        LineIndex index = LineIndex.of(SURROGATES);
        // unit offset 2 in utf-16 lands inside the emoji: clamps to its start (char offset 1)
        assertEquals(1, index.offsetAt(new Types.Position(0, 2), PositionEncoding.UTF16));
        // utf-8 unit 3 is inside the emoji as well
        assertEquals(1, index.offsetAt(new Types.Position(0, 3), PositionEncoding.UTF8));
    }

    @Test
    void crossLineAndEndOfLine() {
        LineIndex index = LineIndex.of("ab\r\ncd\nef");
        assertEquals(new Types.Position(1, 0), index.positionAt(4, PositionEncoding.UTF16));
        assertEquals(new Types.Position(2, 0), index.positionAt(7, PositionEncoding.UTF16));
        // end of line 0 (before the terminator)
        assertEquals(new Types.Position(0, 2), index.positionAt(2, PositionEncoding.UTF16));
        // position past line end clamps to line end
        assertEquals(2, index.offsetAt(new Types.Position(0, 99), PositionEncoding.UTF16));
        // position past last line clamps to the last line
        assertEquals(7, index.offsetAt(new Types.Position(99, 0), PositionEncoding.UTF16));
        assertEquals(9, index.offsetAt(new Types.Position(99, 99), PositionEncoding.UTF16));
    }

    @Test
    void roundTrip() {
        String text = "def 😀x\nref é\n\ndef y\n";
        LineIndex index = LineIndex.of(text);
        for (PositionEncoding enc : PositionEncoding.values()) {
            for (int offset = 0; offset <= text.length(); offset++) {
                Types.Position pos = index.positionAt(offset, enc);
                int back = index.offsetAt(pos, enc);
                // offsetAt(positionAt(o)) == o unless o splits a surrogate pair
                char before = offset > 0 ? text.charAt(offset - 1) : ' ';
                boolean splitsPair = offset > 0 && offset < text.length()
                        && Character.isHighSurrogate(before)
                        && Character.isLowSurrogate(text.charAt(offset));
                if (!splitsPair) {
                    assertEquals(offset, back, enc + " offset " + offset);
                }
            }
        }
    }

    @Test
    void negotiatedUtf8EncodingIsUsedForLocations() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize("utf-8");
            assertEquals(PositionEncoding.UTF8, client.server.positionEncoding());
            String uri = "file:///enc.txt";
            // identifier "😀x" defined on line 0, referenced on line 1
            client.didOpen(uri, "def 😀x\nref 😀x\n", 1);
            client.awaitDiagnostics(uri, 5000);

            // reference at line 1: "ref " = 4 utf-8 units, identifier starts at unit 4
            var params = Json.obj();
            params.set("textDocument", Json.obj().put("uri", uri));
            params.set("position", Json.obj().put("line", 1).put("character", 5));
            client.sendRequest(2, "textDocument/definition", params);
            JsonNode response = client.awaitResponse(2, 5000);
            JsonNode range = response.path("result").path("range");
            // definition identifier "😀x" on line 0 starts after "def " (4 utf-8 units)
            assertEquals(0, range.path("start").path("line").asInt());
            assertEquals(4, range.path("start").path("character").asInt());
            // end: 4 + 4 bytes (emoji) + 1 byte (x) = 9
            assertEquals(9, range.path("end").path("character").asInt());
        }
    }

    @Test
    void negotiatedUtf32EncodingIsUsedForLocations() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize("utf-32");
            String uri = "file:///enc32.txt";
            client.didOpen(uri, "def 😀x\nref 😀x\n", 1);
            client.awaitDiagnostics(uri, 5000);
            var params = Json.obj();
            params.set("textDocument", Json.obj().put("uri", uri));
            params.set("position", Json.obj().put("line", 1).put("character", 4));
            client.sendRequest(2, "textDocument/definition", params);
            JsonNode response = client.awaitResponse(2, 5000);
            JsonNode range = response.path("result").path("range");
            // utf-32: emoji counts once, so identifier spans units 4..6
            assertEquals(4, range.path("start").path("character").asInt());
            assertEquals(6, range.path("end").path("character").asInt());
        }
    }

    @Test
    void unsupportedEncodingFallsBackToUtf16() throws Exception {
        try (TestClient client = new TestClient(new ServerConfig(0, 0, 1 << 20))) {
            client.initialize("utf-7");
            assertEquals(PositionEncoding.UTF16, client.server.positionEncoding());
        }
    }
}
