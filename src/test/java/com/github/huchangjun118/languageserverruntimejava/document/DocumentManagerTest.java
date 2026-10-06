package com.github.huchangjun118.languageserverruntimejava.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.huchangjun118.languageserverruntimejava.json.JsonSupport;
import com.github.huchangjun118.languageserverruntimejava.text.PositionEncoding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentManagerTest {

    private final DocumentManager manager = new DocumentManager();

    private static JsonNode json(String raw) {
        try {
            return JsonSupport.MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<JsonNode> edits(String... raws) {
        return java.util.Arrays.stream(raws).map(DocumentManagerTest::json).toList();
    }

    private static String ranged(int sl, int sc, int el, int ec, String text) {
        return "{\"range\":{\"start\":{\"line\":" + sl + ",\"character\":" + sc
                + "},\"end\":{\"line\":" + el + ",\"character\":" + ec + "}},\"text\":\"" + text + "\"}";
    }

    @Test
    void openAndFullChange() {
        manager.open("file:///a", 1, "def foo\n");
        Optional<Integer> applied = manager.change("file:///a", 2,
                edits("{\"text\":\"def bar\\n\"}"), PositionEncoding.UTF_16);
        assertTrue(applied.isPresent());
        assertEquals("def bar\n", manager.get("file:///a").orElseThrow().text());
        assertEquals(2, manager.get("file:///a").orElseThrow().version());
    }

    @Test
    void incrementalSingleEdit() {
        manager.open("file:///a", 1, "def foo\nref foo\n");
        manager.change("file:///a", 2, edits(ranged(1, 4, 1, 7, "bar")), PositionEncoding.UTF_16);
        assertEquals("def foo\nref bar\n", manager.get("file:///a").orElseThrow().text());
    }

    @Test
    void outOfOrderEditsInOneNotification() {
        manager.open("file:///a", 1, "aaa bbb ccc");
        // Client sends edits against the same base version but unordered.
        manager.change("file:///a", 2, edits(
                ranged(0, 8, 0, 11, "CCC"),
                ranged(0, 0, 0, 3, "AAA"),
                ranged(0, 4, 0, 7, "BBB")), PositionEncoding.UTF_16);
        assertEquals("AAA BBB CCC", manager.get("file:///a").orElseThrow().text());
    }

    @Test
    void overlappingEditsAreDeterministic() {
        manager.open("file:///a", 1, "abcdef");
        // Overlapping edits: [0,4)->"XY" and [2,6)->"Z". Applied back-to-front,
        // the earlier edit is clamped so no region is replaced twice.
        manager.change("file:///a", 2, edits(
                ranged(0, 0, 0, 4, "XY"),
                ranged(0, 2, 0, 6, "Z")), PositionEncoding.UTF_16);
        assertEquals("XYZ", manager.get("file:///a").orElseThrow().text());
        // Same edits in the opposite order must give the same result.
        manager.open("file:///b", 1, "abcdef");
        manager.change("file:///b", 2, edits(
                ranged(0, 2, 0, 6, "Z"),
                ranged(0, 0, 0, 4, "XY")), PositionEncoding.UTF_16);
        assertEquals("XYZ", manager.get("file:///b").orElseThrow().text());
    }

    @Test
    void duplicateAndRolledBackVersionsAreIgnored() {
        manager.open("file:///a", 5, "one");
        assertTrue(manager.change("file:///a", 6, edits("{\"text\":\"two\"}"),
                PositionEncoding.UTF_16).isPresent());
        // Same version again (duplicate delivery).
        assertTrue(manager.change("file:///a", 6, edits("{\"text\":\"dup\"}"),
                PositionEncoding.UTF_16).isEmpty());
        // Rolled back version.
        assertTrue(manager.change("file:///a", 3, edits("{\"text\":\"old\"}"),
                PositionEncoding.UTF_16).isEmpty());
        assertEquals("two", manager.get("file:///a").orElseThrow().text());
        assertEquals(6, manager.get("file:///a").orElseThrow().version());
    }

    @Test
    void versionJumpsAreAccepted() {
        manager.open("file:///a", 1, "one");
        assertTrue(manager.change("file:///a", 42, edits("{\"text\":\"two\"}"),
                PositionEncoding.UTF_16).isPresent());
        assertEquals("two", manager.get("file:///a").orElseThrow().text());
        assertEquals(42, manager.get("file:///a").orElseThrow().version());
    }

    @Test
    void crossLineAndLineEndEdits() {
        manager.open("file:///a", 1, "line0\nline1\nline2\n");
        // Replace from middle of line 0 to middle of line 2 (cross-line edit).
        manager.change("file:///a", 2, edits(ranged(0, 2, 2, 3, "X")), PositionEncoding.UTF_16);
        assertEquals("liXe2\n", manager.get("file:///a").orElseThrow().text());
        // Insert at end of the (now single) line.
        manager.change("file:///a", 3, edits(ranged(0, 5, 0, 5, "!")), PositionEncoding.UTF_16);
        assertEquals("liXe2!\n", manager.get("file:///a").orElseThrow().text());
    }

    @Test
    void utf8PositionsWithMultibyteText() {
        manager.open("file:///a", 1, "def héllo\n");
        // In UTF-8, 'héllo' starts at byte 4; replace 'é' (bytes 5..7) with 'e'.
        manager.change("file:///a", 2, edits(ranged(0, 5, 0, 7, "e")), PositionEncoding.UTF_8);
        assertEquals("def hello\n", manager.get("file:///a").orElseThrow().text());
    }

    @Test
    void closeRemovesDocument() {
        manager.open("file:///a", 1, "x");
        manager.close("file:///a");
        assertTrue(manager.get("file:///a").isEmpty());
        assertTrue(manager.change("file:///a", 2, edits("{\"text\":\"y\"}"),
                PositionEncoding.UTF_16).isEmpty());
    }
}
