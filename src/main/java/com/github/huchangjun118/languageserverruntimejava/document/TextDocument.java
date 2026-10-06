package com.github.huchangjun118.languageserverruntimejava.document;

import com.github.huchangjun118.languageserverruntimejava.protocol.Types.Position;
import com.github.huchangjun118.languageserverruntimejava.protocol.Types.Range;
import com.github.huchangjun118.languageserverruntimejava.text.PositionCodec;
import com.github.huchangjun118.languageserverruntimejava.text.PositionEncoding;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable text of one open document, plus its sync version. All access must
 * be serialized by the owning {@link DocumentManager}.
 */
public class TextDocument {

    private final String uri;
    private String text;
    private int version;

    public TextDocument(String uri, int version, String text) {
        this.uri = uri;
        this.text = text == null ? "" : text;
        this.version = version;
    }

    public String uri() {
        return uri;
    }

    public int version() {
        return version;
    }

    public String text() {
        return text;
    }

    /** Replaces the whole content. */
    public void setText(String text, int version) {
        this.text = text == null ? "" : text;
        this.version = version;
    }

    void setVersion(int version) {
        this.version = version;
    }

    /**
     * Applies one ranged edit against the current text.
     */
    public void applyEdit(Range range, String newText, PositionEncoding encoding) {
        int start = offsetAt(range.start(), encoding);
        int end = offsetAt(range.end(), encoding);
        if (end < start) {
            // Tolerate inverted ranges by treating them as empty insertions.
            end = start;
        }
        this.text = this.text.substring(0, start) + (newText == null ? "" : newText)
                + this.text.substring(end);
    }

    /** Converts an LSP position to an absolute offset, clamped to the text. */
    public int offsetAt(Position position, PositionEncoding encoding) {
        List<Integer> lineStarts = lineStarts(text);
        int line = Math.max(0, Math.min(position.line(), lineStarts.size() - 1));
        int lineStart = lineStarts.get(line);
        int lineEnd = lineEnd(text, lineStart);
        String lineText = text.substring(lineStart, lineEnd);
        return lineStart + PositionCodec.characterToOffset(lineText, position.character(), encoding);
    }

    /** Converts an absolute offset to an LSP position. */
    public Position positionAt(int offset, PositionEncoding encoding) {
        int clamped = Math.max(0, Math.min(offset, text.length()));
        List<Integer> lineStarts = lineStarts(text);
        int low = 0;
        int high = lineStarts.size() - 1;
        int line = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lineStarts.get(mid) <= clamped) {
                line = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        int lineStart = lineStarts.get(line);
        int lineEnd = lineEnd(text, lineStart);
        String lineText = text.substring(lineStart, Math.max(lineStart, Math.min(lineEnd, text.length())));
        int withinLine = Math.min(clamped, lineEnd) - lineStart;
        return new Position(line, PositionCodec.offsetToCharacter(lineText, withinLine, encoding));
    }

    /** Start offsets of every line; handles \n, \r\n and \r terminators. */
    public static List<Integer> lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                starts.add(i + 1);
            } else if (c == '\n') {
                starts.add(i + 1);
            }
        }
        return starts;
    }

    /** End offset (exclusive) of the line content starting at {@code lineStart}. */
    public static int lineEnd(String text, int lineStart) {
        int i = lineStart;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                break;
            }
            i++;
        }
        return i;
    }
}
