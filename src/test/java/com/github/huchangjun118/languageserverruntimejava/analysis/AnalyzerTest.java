package com.github.huchangjun118.languageserverruntimejava.analysis;

import com.github.huchangjun118.languageserverruntimejava.analysis.AnalysisResult.DiagnosticItem;
import com.github.huchangjun118.languageserverruntimejava.analysis.AnalysisResult.SymbolItem;
import com.github.huchangjun118.languageserverruntimejava.text.PositionEncoding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerTest {

    private final Analyzer analyzer = new Analyzer();

    private static List<String> codes(AnalysisResult result) {
        return result.diagnostics().stream().map(DiagnosticItem::code).toList();
    }

    @Test
    void cleanDocumentHasNoDiagnostics() {
        AnalysisResult result = analyzer.analyze("def foo\nref foo\ndef bar\nref bar\n",
                PositionEncoding.UTF_16);
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(4, result.symbols().size());
    }

    @Test
    void undefinedReferenceIsReported() {
        AnalysisResult result = analyzer.analyze("ref missing\n", PositionEncoding.UTF_16);
        assertEquals(List.of(Analyzer.CODE_UNDEFINED_REFERENCE), codes(result));
        DiagnosticItem d = result.diagnostics().get(0);
        assertEquals(0, d.range().start().line());
        assertEquals(4, d.range().start().character());
        assertEquals(11, d.range().end().character());
    }

    @Test
    void referenceMustComeAfterDefinition() {
        // Forward reference: defined later, but referenced first.
        AnalysisResult result = analyzer.analyze("ref a\ndef a\n", PositionEncoding.UTF_16);
        assertEquals(List.of(Analyzer.CODE_UNDEFINED_REFERENCE), codes(result));
    }

    @Test
    void duplicateDefinitionIsReported() {
        AnalysisResult result = analyzer.analyze("def a\ndef a\nref a\n", PositionEncoding.UTF_16);
        assertEquals(List.of(Analyzer.CODE_DUPLICATE_DEFINITION), codes(result));
        assertEquals(1, result.diagnostics().get(0).range().start().line());
    }

    @Test
    void malformedAndUnknownKeywordLines() {
        AnalysisResult result = analyzer.analyze("hello a\nset a b\ndef\n", PositionEncoding.UTF_16);
        assertEquals(List.of(Analyzer.CODE_UNKNOWN_KEYWORD, Analyzer.CODE_MALFORMED_LINE,
                Analyzer.CODE_MALFORMED_LINE), codes(result));
    }

    @Test
    void invalidIdentifierIsReported() {
        AnalysisResult result = analyzer.analyze("def 9bad\n", PositionEncoding.UTF_16);
        assertEquals(List.of(Analyzer.CODE_INVALID_IDENTIFIER), codes(result));
    }

    @Test
    void symbolRangesUseNegotiatedEncoding() {
        // 'déf' as keyword is unknown; use identifier with multibyte char before it.
        String text = "def héllo\nref héllo\n";
        AnalysisResult utf8 = analyzer.analyze(text, PositionEncoding.UTF_8);
        SymbolItem first = utf8.symbols().get(0);
        // 'h' starts after "def " = 4 bytes; 'héllo' is 6 bytes in UTF-8.
        assertEquals(4, first.range().start().character());
        assertEquals(10, first.range().end().character());

        AnalysisResult utf16 = analyzer.analyze(text, PositionEncoding.UTF_16);
        SymbolItem first16 = utf16.symbols().get(0);
        assertEquals(4, first16.range().start().character());
        assertEquals(9, first16.range().end().character());
    }

    @Test
    void definitionsAndReferencesAreDistinguishable() {
        AnalysisResult result = analyzer.analyze("def a\nref a\nref a\n", PositionEncoding.UTF_16);
        long defs = result.symbols().stream().filter(SymbolItem::definition).count();
        long refs = result.symbols().stream().filter(s -> !s.definition()).count();
        assertEquals(1, defs);
        assertEquals(2, refs);
    }
}
