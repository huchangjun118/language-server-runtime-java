package com.github.huchangjun118.languageserverruntimejava.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionCodecTest {

    // "a" + emoji (surrogate pair, 4 UTF-8 bytes) + combining accent + "b"
    private static final String TEXT = "a😀éb";

    @Test
    void utf16UsesJavaOffsets() {
        assertEquals(0, PositionCodec.characterToOffset(TEXT, 0, PositionEncoding.UTF_16));
        assertEquals(3, PositionCodec.characterToOffset(TEXT, 3, PositionEncoding.UTF_16));
        assertEquals(TEXT.length(), PositionCodec.characterToOffset(TEXT, 100, PositionEncoding.UTF_16));
        assertEquals(3, PositionCodec.offsetToCharacter(TEXT, 3, PositionEncoding.UTF_16));
    }

    @Test
    void utf32CountsCodePoints() {
        // code points: a(0) emoji(1) e(2) combining(3) b(4)
        assertEquals(1, PositionCodec.characterToOffset(TEXT, 1, PositionEncoding.UTF_32));
        assertEquals(3, PositionCodec.characterToOffset(TEXT, 2, PositionEncoding.UTF_32));
        assertEquals(5, PositionCodec.characterToOffset(TEXT, 4, PositionEncoding.UTF_32));
        assertEquals(1, PositionCodec.offsetToCharacter(TEXT, 1, PositionEncoding.UTF_32));
        assertEquals(2, PositionCodec.offsetToCharacter(TEXT, 3, PositionEncoding.UTF_32));
        assertEquals(4, PositionCodec.offsetToCharacter(TEXT, 5, PositionEncoding.UTF_32));
    }

    @Test
    void utf8CountsBytes() {
        // bytes: a=1, emoji=4, e=1, combining=2, b=1
        assertEquals(1, PositionCodec.characterToOffset(TEXT, 1, PositionEncoding.UTF_8));
        assertEquals(3, PositionCodec.characterToOffset(TEXT, 5, PositionEncoding.UTF_8));
        assertEquals(4, PositionCodec.characterToOffset(TEXT, 6, PositionEncoding.UTF_8));
        assertEquals(5, PositionCodec.characterToOffset(TEXT, 8, PositionEncoding.UTF_8));
        assertEquals(6, PositionCodec.characterToOffset(TEXT, 9, PositionEncoding.UTF_8));
        assertEquals(1, PositionCodec.offsetToCharacter(TEXT, 1, PositionEncoding.UTF_8));
        assertEquals(5, PositionCodec.offsetToCharacter(TEXT, 3, PositionEncoding.UTF_8));
        assertEquals(9, PositionCodec.offsetToCharacter(TEXT, 6, PositionEncoding.UTF_8));
    }

    @Test
    void utf8SnapsIntoCodePointStart() {
        // Byte offset 2 falls inside the 4-byte emoji (bytes 1..4): snap back to its start.
        assertEquals(1, PositionCodec.characterToOffset(TEXT, 2, PositionEncoding.UTF_8));
        assertEquals(1, PositionCodec.characterToOffset(TEXT, 4, PositionEncoding.UTF_8));
    }

    @Test
    void neverSplitsSurrogatePair() {
        // Offset 2 is between the high and low surrogate: snap back to pair start.
        assertEquals(1, PositionCodec.offsetToCharacter(TEXT, 2, PositionEncoding.UTF_16));
        assertEquals(1, PositionCodec.offsetToCharacter(TEXT, 2, PositionEncoding.UTF_32));
        assertEquals(1, PositionCodec.offsetToCharacter(TEXT, 2, PositionEncoding.UTF_8));
    }

    @Test
    void roundTrips() {
        for (PositionEncoding encoding : PositionEncoding.values()) {
            for (int offset = 0; offset <= TEXT.length(); offset++) {
                int character = PositionCodec.offsetToCharacter(TEXT, offset, encoding);
                int back = PositionCodec.characterToOffset(TEXT, character, encoding);
                // Round trip lands on a code point boundary <= original offset.
                assertEquals(PositionCodec.offsetToCharacter(TEXT, back, encoding), character);
            }
        }
    }
}
