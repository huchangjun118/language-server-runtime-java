package com.github.huchangjun118.languageserverruntimejava.protocol;

/** Common LSP value types. */
public final class Types {

    /** Zero based line/character position. */
    public record Position(int line, int character) implements Comparable<Position> {
        @Override
        public int compareTo(Position other) {
            int byLine = Integer.compare(line, other.line);
            return byLine != 0 ? byLine : Integer.compare(character, other.character);
        }
    }

    /** Half open range between two positions. */
    public record Range(Position start, Position end) {
    }

    private Types() {
    }
}
