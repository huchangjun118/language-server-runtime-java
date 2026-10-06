package com.github.huchangjun118.languageserverruntimejava.analysis;

import com.github.huchangjun118.languageserverruntimejava.analysis.AnalysisResult.DiagnosticItem;
import com.github.huchangjun118.languageserverruntimejava.analysis.AnalysisResult.SymbolItem;
import com.github.huchangjun118.languageserverruntimejava.document.TextDocument;
import com.github.huchangjun118.languageserverruntimejava.protocol.Types.Position;
import com.github.huchangjun118.languageserverruntimejava.protocol.Types.Range;
import com.github.huchangjun118.languageserverruntimejava.text.PositionCodec;
import com.github.huchangjun118.languageserverruntimejava.text.PositionEncoding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Analyzes the mini configuration language: each non blank line is
 * {@code def <name>} or {@code ref <name>}. References must refer to
 * identifiers defined on earlier lines.
 */
public class Analyzer {

    public static final String DEF_KEYWORD = "def";
    public static final String REF_KEYWORD = "ref";

    public static final String CODE_MALFORMED_LINE = "malformed-line";
    public static final String CODE_UNKNOWN_KEYWORD = "unknown-keyword";
    public static final String CODE_INVALID_IDENTIFIER = "invalid-identifier";
    public static final String CODE_DUPLICATE_DEFINITION = "duplicate-definition";
    public static final String CODE_UNDEFINED_REFERENCE = "undefined-reference";

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");

    /** Analyzes the given text. Positions in the result use {@code encoding}. */
    public AnalysisResult analyze(String text, PositionEncoding encoding) {
        List<DiagnosticItem> diagnostics = new ArrayList<>();
        List<SymbolItem> symbols = new ArrayList<>();
        Map<String, Integer> definedBeforeLine = new HashMap<>();

        List<Integer> lineStarts = TextDocument.lineStarts(text);
        for (int line = 0; line < lineStarts.size(); line++) {
            int start = lineStarts.get(line);
            int end = TextDocument.lineEnd(text, start);
            String lineText = text.substring(start, end);
            if (lineText.isBlank()) {
                continue;
            }
            analyzeLine(lineText, line, encoding, diagnostics, symbols, definedBeforeLine);
        }
        return new AnalysisResult(diagnostics, symbols);
    }

    private void analyzeLine(String lineText, int line, PositionEncoding encoding,
                             List<DiagnosticItem> diagnostics, List<SymbolItem> symbols,
                             Map<String, Integer> definedBeforeLine) {
        int firstNonSpace = 0;
        while (firstNonSpace < lineText.length() && Character.isWhitespace(lineText.charAt(firstNonSpace))) {
            firstNonSpace++;
        }
        // Split into keyword and identifier on whitespace.
        int keywordEnd = firstNonSpace;
        while (keywordEnd < lineText.length() && !Character.isWhitespace(lineText.charAt(keywordEnd))) {
            keywordEnd++;
        }
        int identStart = keywordEnd;
        while (identStart < lineText.length() && Character.isWhitespace(lineText.charAt(identStart))) {
            identStart++;
        }
        int identEnd = identStart;
        while (identEnd < lineText.length() && !Character.isWhitespace(lineText.charAt(identEnd))) {
            identEnd++;
        }
        String trailing = lineText.substring(identEnd).trim();

        String keyword = lineText.substring(firstNonSpace, keywordEnd);
        String identifier = lineText.substring(identStart, identEnd);
        Range lineRange = range(lineText, line, firstNonSpace, lineText.length(), encoding);

        if (identifier.isEmpty() || !trailing.isEmpty()) {
            diagnostics.add(new DiagnosticItem(lineRange, CODE_MALFORMED_LINE,
                    "expected '<keyword> <identifier>'"));
            return;
        }
        Range keywordRange = range(lineText, line, firstNonSpace, keywordEnd, encoding);
        boolean isDef = DEF_KEYWORD.equals(keyword);
        boolean isRef = REF_KEYWORD.equals(keyword);
        if (!isDef && !isRef) {
            diagnostics.add(new DiagnosticItem(keywordRange, CODE_UNKNOWN_KEYWORD,
                    "unknown keyword '" + keyword + "', expected 'def' or 'ref'"));
            return;
        }
        Range identRange = range(lineText, line, identStart, identEnd, encoding);
        if (!IDENTIFIER.matcher(identifier).matches()) {
            diagnostics.add(new DiagnosticItem(identRange, CODE_INVALID_IDENTIFIER,
                    "invalid identifier '" + identifier + "'"));
            return;
        }
        if (isDef) {
            if (definedBeforeLine.containsKey(identifier)) {
                diagnostics.add(new DiagnosticItem(identRange, CODE_DUPLICATE_DEFINITION,
                        "duplicate definition of '" + identifier + "', first defined on line "
                                + (definedBeforeLine.get(identifier) + 1)));
            } else {
                definedBeforeLine.put(identifier, line);
            }
            symbols.add(new SymbolItem(identifier, identRange, true, line));
        } else {
            Integer defLine = definedBeforeLine.get(identifier);
            if (defLine == null || defLine >= line) {
                diagnostics.add(new DiagnosticItem(identRange, CODE_UNDEFINED_REFERENCE,
                        "undefined identifier '" + identifier + "'"));
            }
            symbols.add(new SymbolItem(identifier, identRange, false, line));
        }
    }

    private static Range range(String lineText, int line, int startOffset, int endOffset,
                               PositionEncoding encoding) {
        return new Range(
                new Position(line, PositionCodec.offsetToCharacter(lineText, startOffset, encoding)),
                new Position(line, PositionCodec.offsetToCharacter(lineText, endOffset, encoding)));
    }
}
