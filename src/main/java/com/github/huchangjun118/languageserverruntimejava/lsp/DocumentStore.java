package com.github.huchangjun118.languageserverruntimejava.lsp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of open documents, keyed by URI. */
public final class DocumentStore {
    private final Map<String, TextDocument> documents = new ConcurrentHashMap<>();

    public TextDocument open(String uri, String text, int version) {
        TextDocument doc = new TextDocument(uri, text, version);
        documents.put(uri, doc);
        return doc;
    }

    public TextDocument get(String uri) {
        return documents.get(uri);
    }

    public TextDocument close(String uri) {
        return documents.remove(uri);
    }

    public boolean isOpen(String uri) {
        return documents.containsKey(uri);
    }

    public int size() {
        return documents.size();
    }
}
