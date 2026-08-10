package com.sweprep.backend.authoring;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves issue #24's fifth acceptance criterion mechanically: the tool refuses
 * to write into this public repository, and never fails closed on an ordinary
 * clone elsewhere. Surefire runs with the working directory at the {@code
 * backend/} module root (Maven's convention), so a path resolved under it (e.g.
 * {@code src}) is genuinely inside this checkout - the same repo the running
 * test classes were themselves compiled from - which is what lets this test
 * exercise the real guard rather than a stand-in.
 */
class SafetyGuardTest {

    @Test
    void refusesADestinationInsideThisPublicRepo() {
        Path insideThisRepo = Path.of("src").toAbsolutePath();

        assertThatThrownBy(() -> SafetyGuard.requireSafeContentDir(insideThisRepo))
                .isInstanceOf(AuthoringException.class)
                .hasMessageContaining("public repo");
    }

    @Test
    void refusesThePublicRepoRootItself() {
        Path repoRoot = Path.of("..").toAbsolutePath();

        assertThatThrownBy(() -> SafetyGuard.requireSafeContentDir(repoRoot))
                .isInstanceOf(AuthoringException.class)
                .hasMessageContaining("public repo");
    }

    @Test
    void acceptsAnOrdinaryDirectoryOutsideThisRepo(@TempDir Path outsideDir) {
        assertThatCode(() -> SafetyGuard.requireSafeContentDir(outsideDir)).doesNotThrowAnyException();
    }

    @Test
    void refusesAMissingDirectoryRatherThanCreatingOne(@TempDir Path parent) {
        Path missing = parent.resolve("not-yet-cloned");

        assertThatThrownBy(() -> SafetyGuard.requireSafeContentDir(missing))
                .isInstanceOf(AuthoringException.class)
                .hasMessageContaining("does not exist");
    }
}
