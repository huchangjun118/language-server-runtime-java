package com.github.huchangjun118.languageserverruntimejava.lsp;

import java.util.List;

/** Mutable state of one open text document. All methods are synchronized. */
public final class TextDocument {
    private final String uri;
    private String text;
    private int version;

    public TextDocument(String uri, String text, int version) {
        this.uri = uri;
        this.text = text;
        this.version = version;
    }

    public String uri() {
        return uri;
    }

    public synchronized int version() {
        return version;
    }

    public synchronized String text() {
        return text;
    }

    /** One content change: null range means full replacement. */
    public record Change(Types.Range range, String text) {
    }

    /**
     * Applies the changes of one didChange notification. Changes are applied
     * sequentially in array order, each against the result of the previous
     * one, exactly as the client produced them. The client-provided version
     * is authoritative and stored as-is (jumps or regressions do not block
     * the update, so the final text never diverges from the client).
     */
    public synchronized void applyChanges(List<Change> changes, int newVersion, PositionEncoding enc) {
        String current = text;
        for (Change change : changes) {
            if (change.range() == null) {
                current = change.text();
                continue;
            }
            LineIndex index = LineIndex.of(current);
            int start = index.offsetAt(change.range().start(), enc);
            int end = index.offsetAt(change.range().end(), enc);
            if (end < start) {
                end = start; // tolerate inverted ranges
            }
            current = current.substring(0, start) + change.text() + current.substring(end);
        }
        this.text = current;
        this.version = newVersion;
    }
}
