package com.sweprep.backend.runner;

import com.sweprep.backend.language.LanguageAdapterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Routes a language id to the one {@link Runner} that executes it, mirroring {@link
 * LanguageAdapterRegistry} on the runner side of the same seam: a language's adapter
 * generates the harness, its runner executes it, and both are looked up by the same
 * id (see {@link Runner#languageId()}). Adding a language's runner is a new bean here,
 * never a change to a caller.
 */
@Component
public class RunnerRegistry {

    private final Map<String, Runner> byLanguageId;

    public RunnerRegistry(List<Runner> runners) {
        Map<String, Runner> byId = new LinkedHashMap<>();
        for (Runner runner : runners) {
            byId.put(runner.languageId(), runner);
        }
        this.byLanguageId = Map.copyOf(byId);
    }

    /** The runner for {@code languageId}, or Java's when {@code languageId} is null/blank. */
    public Runner forLanguage(String languageId) {
        String resolved = languageId == null || languageId.isBlank()
                ? LanguageAdapterRegistry.DEFAULT_LANGUAGE
                : languageId;
        Runner runner = byLanguageId.get(resolved);
        if (runner == null) {
            throw new LanguageAdapterRegistry.UnsupportedLanguageException(resolved, byLanguageId.keySet());
        }
        return runner;
    }
}
