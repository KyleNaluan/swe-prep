package com.sweprep.backend.language;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The routing seam issue #26 asks for: a language id resolves to the one adapter
 * that targets it, Java is the default when none is given, and an unrecognised
 * language fails clearly rather than silently falling back to something else.
 */
class LanguageAdapterRegistryTest {

    private final LanguageAdapterRegistry registry =
            new LanguageAdapterRegistry(List.of(new PythonLanguageAdapter(), new JavaLanguageAdapter()));

    @Test
    void resolvesEachRegisteredAdapterByItsLanguageId() {
        assertThat(registry.forLanguage("java")).isInstanceOf(JavaLanguageAdapter.class);
        assertThat(registry.forLanguage("python")).isInstanceOf(PythonLanguageAdapter.class);
    }

    @Test
    void defaultsToJavaWhenNoLanguageIsGiven() {
        assertThat(registry.forLanguage(null)).isInstanceOf(JavaLanguageAdapter.class);
        assertThat(registry.forLanguage("")).isInstanceOf(JavaLanguageAdapter.class);
        assertThat(registry.forLanguage("  ")).isInstanceOf(JavaLanguageAdapter.class);
    }

    @Test
    void javaIsAlwaysFirstInTheAvailableListRegardlessOfBeanOrder() {
        // Constructed above with Python listed before Java, on purpose - the promise
        // "Java remains the default" (issue #26) must not depend on Spring's bean
        // injection order.
        assertThat(registry.available()).startsWith("java");
        assertThat(registry.available()).containsExactlyInAnyOrder("java", "python");
    }

    @Test
    void anUnknownLanguageFailsClearlyRatherThanFallingBackSilently() {
        assertThatThrownBy(() -> registry.forLanguage("cobol"))
                .isInstanceOf(LanguageAdapterRegistry.UnsupportedLanguageException.class)
                .hasMessageContaining("cobol")
                .hasMessageContaining("java")
                .hasMessageContaining("python");
    }
}
