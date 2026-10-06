package com.github.huchangjun118.languageserverruntimejava.lsp;

/** Position encoding units negotiable during initialize (LSP 3.17). */
public enum PositionEncoding {
    UTF8("utf-8"),
    UTF16("utf-16"),
    UTF32("utf-32");

    private final String wireName;

    PositionEncoding(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static PositionEncoding fromWireName(String name) {
        for (PositionEncoding e : values()) {
            if (e.wireName.equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** Number of units one Unicode code point occupies in this encoding. */
    private int unitsOfCodePoint(int codePoint) {
        return switch (this) {
            case UTF32 -> 1;
            case UTF16 -> codePoint <= 0xFFFF ? 1 : 2;
            case UTF8 -> codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
        };
    }

    /** Total units of the given text in this encoding. */
    public int unitsOf(CharSequence s) {
        int units = 0;
        for (int i = 0; i < s.length();) {
            int cp = Character.codePointAt(s, i);
            units += unitsOfCodePoint(cp);
            i += Character.charCount(cp);
        }
        return units;
    }

    /**
     * Converts a unit offset within {@code line} into a Java char offset.
     * Never splits a surrogate pair / code point: a unit offset landing inside
     * a code point is clamped to that code point's start.
     */
    public int charOffsetForUnits(CharSequence line, int targetUnits) {
        int units = 0;
        for (int i = 0; i < line.length();) {
            int cp = Character.codePointAt(line, i);
            int u = unitsOfCodePoint(cp);
            if (units + u > targetUnits) {
                return i; // target falls inside this code point: clamp to its start
            }
            units += u;
            i += Character.charCount(cp);
            if (units == targetUnits) {
                return i;
            }
        }
        return line.length(); // clamp to line end
    }
}
