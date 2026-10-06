package com.github.huchangjun118.languageserverruntimejava.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.huchangjun118.languageserverruntimejava.protocol.Types.Position;
import com.github.huchangjun118.languageserverruntimejava.protocol.Types.Range;
import com.github.huchangjun118.languageserverruntimejava.text.PositionEncoding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks open documents. All mutations are serialized so change notifications
 * take effect in arrival order.
 */
public class DocumentManager {

    private final Map<String, TextDocument> documents = new ConcurrentHashMap<>();

    /** Opens a document. Returns the stored document. */
    public TextDocument open(String uri, int version, String text) {
        TextDocument document = new TextDocument(uri, version, text);
        documents.put(uri, document);
        return snapshot(document);
    }

    /**
     * Applies a didChange notification. Returns the new version if the change
     * was applied, or empty if it was a duplicate/rolled back version or the
     * document is not open.
     */
    public Optional<Integer> change(String uri, int version, List<JsonNode> contentChanges,
                                    PositionEncoding encoding) {
        TextDocument document = documents.get(uri);
        if (document == null) {
            return Optional.empty();
        }
        synchronized (document) {
            if (version <= document.version()) {
                // Duplicate delivery or rolled back version: ignore so the
                // document cannot drift away from the client's state.
                return Optional.empty();
            }
            List<RangedEdit> pending = new ArrayList<>();
            for (JsonNode change : contentChanges) {
                if (change == null || !change.isObject()) {
                    continue;
                }
                JsonNode rangeNode = change.get("range");
                String newText = change.path("text").isTextual() ? change.get("text").asText() : "";
                if (rangeNode != null && rangeNode.isObject()) {
                    pending.add(toRangedEdit(document, rangeNode, newText, encoding));
                } else {
                    // Full document update: flush pending ranged edits first.
                    applyRangedEdits(document, pending);
                    document.setText(newText, version);
                }
            }
            applyRangedEdits(document, pending);
            document.setVersion(version);
            return Optional.of(version);
        }
    }

    /** Closes a document. */
    public void close(String uri) {
        documents.remove(uri);
    }

    /** Current snapshot of a document, if open. Safe to read off the lock. */
    public Optional<TextDocument> get(String uri) {
        TextDocument document = documents.get(uri);
        if (document == null) {
            return Optional.empty();
        }
        synchronized (document) {
            return Optional.of(snapshot(document));
        }
    }

    /** Current version of a document, or empty if not open. */
    public Optional<Integer> versionOf(String uri) {
        TextDocument document = documents.get(uri);
        if (document == null) {
            return Optional.empty();
        }
        synchronized (document) {
            return Optional.of(document.version());
        }
    }

    private static TextDocument snapshot(TextDocument document) {
        return new TextDocument(document.uri(), document.version(), document.text());
    }

    /** One ranged edit resolved to absolute offsets of the current text. */
    private record RangedEdit(int start, int end, String newText) {
    }

    private static RangedEdit toRangedEdit(TextDocument document, JsonNode rangeNode, String newText,
                                           PositionEncoding encoding) {
        Range range = new Range(toPosition(rangeNode.get("start")), toPosition(rangeNode.get("end")));
        int start = document.offsetAt(range.start(), encoding);
        int end = document.offsetAt(range.end(), encoding);
        if (end < start) {
            end = start;
        }
        return new RangedEdit(start, end, newText);
    }

    private static Position toPosition(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new Position(0, 0);
        }
        return new Position(node.path("line").asInt(0), node.path("character").asInt(0));
    }

    /**
     * Applies ranged edits that were all computed against the same base text.
     * Edits may arrive unordered and overlapping; they are applied from the
     * end of the document backwards, and overlapping regions are clamped so
     * every edit is applied at most once and the result is deterministic.
     */
    private static void applyRangedEdits(TextDocument document, List<RangedEdit> edits) {
        if (edits.isEmpty()) {
            return;
        }
        List<RangedEdit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(RangedEdit::start).reversed()
                .thenComparing(Comparator.comparingInt(RangedEdit::end).reversed()));
        String text = document.text();
        int minTouchedStart = Integer.MAX_VALUE;
        for (RangedEdit edit : sorted) {
            int start = Math.min(edit.start(), minTouchedStart);
            int end = Math.min(edit.end(), minTouchedStart);
            if (end < start) {
                end = start;
            }
            text = text.substring(0, start) + edit.newText() + text.substring(end);
            minTouchedStart = start;
        }
        document.setText(text, document.version());
    }
}
