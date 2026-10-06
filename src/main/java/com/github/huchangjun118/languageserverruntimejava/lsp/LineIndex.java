package com.github.huchangjun118.languageserverruntimejava.lsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps between absolute char offsets and (line, character) positions for a
 * snapshot of text, honoring the negotiated position encoding.
 * Lines are split on \r\n, \r or \n.
 */
public final class LineIndex {
    private final String text;
    private final int[] lineStarts;

    private LineIndex(String text, int[] lineStarts) {
        this.text = text;
        this.lineStarts = lineStarts;
    }

    public static LineIndex of(String text) {
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
        return new LineIndex(text, starts.stream().mapToInt(Integer::intValue).toArray());
    }

    public int lineCount() {
        return lineStarts.length;
    }

    /** Start offset of a line (clamped to a valid line). */
    public int lineStart(int line) {
        return lineStarts[clampLine(line)];
    }

    /** End offset of a line, excluding its line terminator. */
    public int lineEnd(int line) {
        line = clampLine(line);
        int end = line + 1 < lineStarts.length ? lineStarts[line + 1] : text.length();
        // Strip the terminator.
        while (end > lineStarts[line]) {
            char c = text.charAt(end - 1);
            if (c == '\n' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    public String lineText(int line) {
        line = clampLine(line);
        return text.substring(lineStarts[line], lineEnd(line));
    }

    /** Converts an LSP position to an absolute char offset, clamped into range. */
    public int offsetAt(Types.Position pos, PositionEncoding enc) {
        int line = clampLine(pos.line());
        int start = lineStarts[line];
        String lineText = text.substring(start, lineEnd(line));
        return start + enc.charOffsetForUnits(lineText, Math.max(0, pos.character()));
    }

    /** Converts an absolute char offset to an LSP position. */
    public Types.Position positionAt(int offset, PositionEncoding enc) {
        offset = Math.max(0, Math.min(offset, text.length()));
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (lineStarts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        int character = enc.unitsOf(text.substring(lineStarts[lo], offset));
        return new Types.Position(lo, character);
    }

    private int clampLine(int line) {
        return Math.max(0, Math.min(line, lineStarts.length - 1));
    }
}
