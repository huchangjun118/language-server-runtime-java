package com.github.huchangjun118.languageserverruntimejava.text;

/** Position encodings supported by this server. */
public enum PositionEncoding {
    UTF_8("utf-8"),
    UTF_16("utf-16"),
    UTF_32("utf-32");

    private final String wireName;

    PositionEncoding(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static PositionEncoding fromWireName(String name) {
        for (PositionEncoding encoding : values()) {
            if (encoding.wireName.equals(name)) {
                return encoding;
            }
        }
        return null;
    }
}
