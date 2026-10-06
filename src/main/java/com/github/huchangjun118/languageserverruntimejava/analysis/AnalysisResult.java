package com.github.huchangjun118.languageserverruntimejava.analysis;

import com.github.huchangjun118.languageserverruntimejava.protocol.Types.Range;

import java.util.List;

/** Result of analyzing one document snapshot. */
public record AnalysisResult(List<DiagnosticItem> diagnostics, List<SymbolItem> symbols) {

    /** One diagnostic with its range and message. */
    public record DiagnosticItem(Range range, String code, String message) {
    }

    /** One identifier occurrence: name, range, and whether it is a definition. */
    public record SymbolItem(String name, Range range, boolean definition, int line) {
    }
}
