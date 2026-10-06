package com.github.huchangjun118.languageserverruntimejava.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyzer for the minimal declarative language: each non-blank line is
 * {@code def <identifier>} (a definition) or {@code ref <identifier>}
 * (a reference to a previously defined identifier).
 */
public final class Analyzer {
    public static final String KEYWORD_DEF = "def";
    public static final String KEYWORD_REF = "ref";
    public static final String SOURCE = "minils";
    public static final String CODE_DUPLICATE_DEFINITION = "duplicate-definition";
    public static final String CODE_UNDEFINED_REFERENCE = "undefined-reference";
    public static final String CODE_UNKNOWN_KEYWORD = "unknown-keyword";

    private Analyzer() {
    }

    /** One {@code keyword identifier} statement; offsets are Java char offsets of the identifier. */
    public record Token(String keyword, String name, int nameStart, int nameEnd, int line) {
    }

    public record Analysis(String text, LineIndex index, PositionEncoding encoding,
                           List<Token> tokens, List<Types.Diagnostic> diagnostics) {

        /** Token whose identifier span contains the given char offset. */
        public Token tokenAt(int offset) {
            for (Token t : tokens) {
                if (offset >= t.nameStart() && offset <= t.nameEnd()) {
                    return t;
                }
            }
            return null;
        }

        public List<Token> definitionsOf(String name) {
            List<Token> result = new ArrayList<>();
            for (Token t : tokens) {
                if (KEYWORD_DEF.equals(t.keyword()) && t.name().equals(name)) {
                    result.add(t);
                }
            }
            return result;
        }

        public List<Token> referencesOf(String name) {
            List<Token> result = new ArrayList<>();
            for (Token t : tokens) {
                if (KEYWORD_REF.equals(t.keyword()) && t.name().equals(name)) {
                    result.add(t);
                }
            }
            return result;
        }

        public Types.Range rangeOf(Token t) {
            return new Types.Range(
                    index.positionAt(t.nameStart(), encoding),
                    index.positionAt(t.nameEnd(), encoding));
        }

        public Types.Location locationOf(String uri, Token t) {
            return new Types.Location(uri, rangeOf(t));
        }
    }

    public static Analysis analyze(String text, PositionEncoding encoding) {
        LineIndex index = LineIndex.of(text);
        List<Token> tokens = new ArrayList<>();
        List<Types.Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Token> firstDefinition = new LinkedHashMap<>();

        for (int line = 0; line < index.lineCount(); line++) {
            String lineText = index.lineText(line);
            int lineStart = index.lineStart(line);
            int i = 0;
            while (i < lineText.length() && Character.isWhitespace(lineText.charAt(i))) {
                i++;
            }
            if (i >= lineText.length()) {
                continue; // blank line
            }
            int kwStart = i;
            while (i < lineText.length() && !Character.isWhitespace(lineText.charAt(i))) {
                i++;
            }
            String keyword = lineText.substring(kwStart, i);
            while (i < lineText.length() && Character.isWhitespace(lineText.charAt(i))) {
                i++;
            }
            if (i >= lineText.length()) {
                continue; // keyword without identifier: not a statement
            }
            int nameStart = i;
            while (i < lineText.length() && !Character.isWhitespace(lineText.charAt(i))) {
                i++;
            }
            String name = lineText.substring(nameStart, i);
            Token token = new Token(keyword, name, lineStart + nameStart, lineStart + i, line);
            tokens.add(token);

            Types.Range nameRange = new Types.Range(
                    index.positionAt(token.nameStart(), encoding),
                    index.positionAt(token.nameEnd(), encoding));
            switch (keyword) {
                case KEYWORD_DEF -> {
                    Token existing = firstDefinition.get(name);
                    if (existing != null) {
                        diagnostics.add(new Types.Diagnostic(nameRange,
                                Types.Diagnostic.SEVERITY_ERROR, CODE_DUPLICATE_DEFINITION, SOURCE,
                                "Duplicate definition of '" + name + "' (first defined on line "
                                        + (existing.line() + 1) + ")"));
                    } else {
                        firstDefinition.put(name, token);
                    }
                }
                case KEYWORD_REF -> {
                    if (!firstDefinition.containsKey(name)) {
                        diagnostics.add(new Types.Diagnostic(nameRange,
                                Types.Diagnostic.SEVERITY_ERROR, CODE_UNDEFINED_REFERENCE, SOURCE,
                                "Undefined reference to '" + name + "'"));
                    }
                }
                default -> diagnostics.add(new Types.Diagnostic(nameRange,
                        Types.Diagnostic.SEVERITY_WARNING, CODE_UNKNOWN_KEYWORD, SOURCE,
                        "Unknown keyword '" + keyword + "' (expected 'def' or 'ref')"));
            }
        }
        return new Analysis(text, index, encoding, tokens, diagnostics);
    }
}
