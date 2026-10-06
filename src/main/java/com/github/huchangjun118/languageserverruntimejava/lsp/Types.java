package com.github.huchangjun118.languageserverruntimejava.lsp;

import com.fasterxml.jackson.annotation.JsonInclude;

/** LSP structure types (records serialize straight to JSON via Jackson). */
public final class Types {
    private Types() {
    }

    public record Position(int line, int character) {
    }

    public record Range(Position start, Position end) {
    }

    public record Location(String uri, Range range) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Diagnostic(Range range, Integer severity, String code, String source, String message) {
        public static final int SEVERITY_ERROR = 1;
        public static final int SEVERITY_WARNING = 2;
    }
}
