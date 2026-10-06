package com.github.huchangjun118.languageserverruntimejava.text;

/**
 * Converts between LSP (line, character) positions and Java string offsets
 * for a given position encoding. Surrogate pairs and combining characters
 * are handled by walking Unicode code points.
 */
public final class PositionCodec {

    private PositionCodec() {
    }

    /**
     * Converts an LSP character index within {@code lineText} to a Java
     * (UTF-16) string offset, clamped to the line bounds.
     */
    public static int characterToOffset(String lineText, int character, PositionEncoding encoding) {
        if (character <= 0) {
            return 0;
        }
        return switch (encoding) {
            case UTF_16 -> Math.min(character, lineText.length());
            case UTF_32 -> {
                int codePoints = lineText.codePointCount(0, lineText.length());
                yield lineText.offsetByCodePoints(0, Math.min(character, codePoints));
            }
            case UTF_8 -> {
                int offset = 0;
                int units = 0;
                int length = lineText.length();
                while (offset < length) {
                    int codePoint = lineText.codePointAt(offset);
                    int size = utf8Size(codePoint);
                    if (units + size > character) {
                        break; // target falls inside this code point: snap to its start
                    }
                    units += size;
                    offset += Character.charCount(codePoint);
                    if (units == character) {
                        break;
                    }
                }
                yield offset;
            }
        };
    }

    /** Converts a Java string offset within {@code lineText} to an LSP character index. */
    public static int offsetToCharacter(String lineText, int offset, PositionEncoding encoding) {
        int clamped = Math.max(0, Math.min(offset, lineText.length()));
        // Never split a surrogate pair: snap back to the pair start.
        if (clamped > 0 && clamped < lineText.length()
                && Character.isLowSurrogate(lineText.charAt(clamped))
                && Character.isHighSurrogate(lineText.charAt(clamped - 1))) {
            clamped--;
        }
        return switch (encoding) {
            case UTF_16 -> clamped;
            case UTF_32 -> lineText.codePointCount(0, clamped);
            case UTF_8 -> {
                int units = 0;
                for (int i = 0; i < clamped; ) {
                    int codePoint = lineText.codePointAt(i);
                    units += utf8Size(codePoint);
                    i += Character.charCount(codePoint);
                }
                yield units;
            }
        };
    }

    private static int utf8Size(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }
}
