package com.sweprep.backend.authoring;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;

/**
 * The mechanical half of issue #24's fifth acceptance criterion: the tool must
 * never write into this public engine repository (issue #4/#14's public-engine,
 * private-content split; {@code scripts/check-no-content.sh} is the same
 * invariant enforced from the other side, over what actually lands in a commit).
 * A destination the author points the tool at is checked <em>before</em> {@link
 * ContentWriter} touches anything, not caught after the fact.
 *
 * <p>The public repo's root is located from where this class was actually loaded
 * from (its {@link CodeSource}), walking up to the directory carrying this repo's
 * own layout signature (a sibling {@code backend/} and {@code frontend/} plus
 * {@code docker-compose.yml} - see this repo's {@code AGENTS.md}, "Layout") -
 * rather than trusted from the process's working directory, which a caller could
 * have started from anywhere. If that root cannot be located at all, the guard
 * fails closed (refuses) rather than silently skipping the check: an
 * unrecognised environment is exactly the case this criterion exists to be safe
 * against, not a reason to proceed anyway.
 */
final class SafetyGuard {

    private SafetyGuard() {}

    /**
     * Refuses (throws {@link AuthoringException}) when {@code contentDir} does not
     * exist, is not a directory, or resolves inside this public repository's working
     * tree. Never mutates anything - a pure check, run before any file is written.
     */
    static void requireSafeContentDir(Path contentDir) {
        if (!Files.isDirectory(contentDir)) {
            throw new AuthoringException(
                    "content directory " + contentDir + " does not exist. Clone the private "
                            + "swe-prep-content repo there first (see README.md's \"Content authoring\" "
                            + "section) - this tool never creates that clone for you.");
        }
        Path realContentDir = realPath(contentDir);
        Path publicRepoRoot = findPublicRepoRoot()
                .orElseThrow(() -> new AuthoringException(
                        "could not locate this public repo's root to verify " + contentDir
                                + " is outside it; refusing to proceed rather than risk writing content "
                                + "into the public engine repo (issue #4/#14)"));
        Path realPublicRoot = realPath(publicRepoRoot);
        if (realContentDir.equals(realPublicRoot) || realContentDir.startsWith(realPublicRoot)) {
            throw new AuthoringException(
                    "refusing to write content into " + contentDir + " - it is inside this public repo ("
                            + realPublicRoot + "). Problem content must live only in a clone of the private "
                            + "swe-prep-content repo (issue #4/#14); point --content-dir at that clone instead.");
        }
    }

    /**
     * Walks up from where this class was loaded from until it finds a directory
     * whose immediate children match this repo's known layout: sibling {@code
     * backend/} and {@code frontend/} directories plus a root {@code
     * docker-compose.yml}. Empty when no such ancestor exists (e.g. this class was
     * packaged into a jar copied somewhere with no repo around it at all).
     */
    private static java.util.Optional<Path> findPublicRepoRoot() {
        Path start = codeSourceLocation();
        if (start == null) {
            return java.util.Optional.empty();
        }
        Path candidate = Files.isDirectory(start) ? start : start.getParent();
        while (candidate != null) {
            if (looksLikeSwePrepRoot(candidate)) {
                return java.util.Optional.of(candidate);
            }
            candidate = candidate.getParent();
        }
        return java.util.Optional.empty();
    }

    private static boolean looksLikeSwePrepRoot(Path candidate) {
        return Files.isDirectory(candidate.resolve("backend"))
                && Files.isDirectory(candidate.resolve("frontend"))
                && Files.isRegularFile(candidate.resolve("docker-compose.yml"))
                && Files.isRegularFile(candidate.resolve("backend").resolve("pom.xml"));
    }

    private static Path codeSourceLocation() {
        CodeSource codeSource = SafetyGuard.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return null;
        }
        try {
            return Path.of(codeSource.getLocation().toURI());
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new AuthoringException("Cannot resolve real path of " + path, e);
        }
    }
}
