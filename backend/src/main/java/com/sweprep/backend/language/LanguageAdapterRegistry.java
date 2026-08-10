package com.sweprep.backend.language;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Routes a language id (as chosen by the solver, or defaulted) to the one
 * {@link LanguageAdapter} that targets it - the seam that makes "the user can choose
 * which language to solve in" (issue #26) real: everything above this registry deals
 * in a plain {@code language} string, never in a concrete adapter class. Adding a
 * third language is a new {@code LanguageAdapter} bean, with nothing here to change.
 *
 * <p>Java is the default language (issue #26's explicit acceptance criterion): {@link
 * #DEFAULT_LANGUAGE} names it, and {@link #available()} always lists it first so a
 * language picker can render it as the initial selection without special-casing.
 */
@Component
public class LanguageAdapterRegistry {

    /** The language a solver gets when none is specified - Java stays the default. */
    public static final String DEFAULT_LANGUAGE = "java";

    private final Map<String, LanguageAdapter> byLanguageId;

    public LanguageAdapterRegistry(List<LanguageAdapter> adapters) {
        Map<String, LanguageAdapter> byId = new LinkedHashMap<>();
        // Java first, whatever order Spring injected the beans in, so `available()`
        // never depends on bean-wiring order to keep the "Java is the default" promise.
        adapters.stream()
                .sorted((a, b) -> Boolean.compare(
                        !a.languageId().equals(DEFAULT_LANGUAGE), !b.languageId().equals(DEFAULT_LANGUAGE)))
                .forEach(adapter -> byId.put(adapter.languageId(), adapter));
        // Map.copyOf does not preserve iteration order (it is explicitly unspecified for
        // 2+ entries), which would silently undo the sort above - Collections.unmodifiableMap
        // over the already-ordered LinkedHashMap is what actually keeps Java first.
        this.byLanguageId = Collections.unmodifiableMap(byId);
    }

    /** The adapter for {@code languageId}, or Java's when {@code languageId} is null/blank. */
    public LanguageAdapter forLanguage(String languageId) {
        String resolved = languageId == null || languageId.isBlank() ? DEFAULT_LANGUAGE : languageId;
        LanguageAdapter adapter = byLanguageId.get(resolved);
        if (adapter == null) {
            throw new UnsupportedLanguageException(resolved, byLanguageId.keySet());
        }
        return adapter;
    }

    /** Every language id a solver may pick, Java first. */
    public List<String> available() {
        return List.copyOf(byLanguageId.keySet());
    }

    /** Thrown when a request names a language no adapter targets. */
    public static class UnsupportedLanguageException extends RuntimeException {
        public UnsupportedLanguageException(String requested, java.util.Set<String> available) {
            super("Unknown language '" + requested + "'; available: " + String.join(", ", available));
        }
    }
}
