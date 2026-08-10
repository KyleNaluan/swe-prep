package com.sweprep.backend.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweprep.backend.language.LanguageAdapterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The runner-side mirror of {@code LanguageAdapterRegistryTest} (issue #26). */
class RunnerRegistryTest {

    private final RunnerRegistry registry =
            new RunnerRegistry(List.of(new LocalJavaRunner(), new LocalPythonRunner("python3")));

    @Test
    void resolvesEachRegisteredRunnerByItsLanguageId() {
        assertThat(registry.forLanguage("java")).isInstanceOf(LocalJavaRunner.class);
        assertThat(registry.forLanguage("python")).isInstanceOf(LocalPythonRunner.class);
    }

    @Test
    void defaultsToJavaWhenNoLanguageIsGiven() {
        assertThat(registry.forLanguage(null)).isInstanceOf(LocalJavaRunner.class);
        assertThat(registry.forLanguage("")).isInstanceOf(LocalJavaRunner.class);
    }

    @Test
    void anUnknownLanguageFailsClearlyRatherThanFallingBackSilently() {
        assertThatThrownBy(() -> registry.forLanguage("cobol"))
                .isInstanceOf(LanguageAdapterRegistry.UnsupportedLanguageException.class)
                .hasMessageContaining("cobol");
    }
}
