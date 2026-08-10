package com.sweprep.backend.commit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configures solution auto-commit (issue #22, decision issue #7 item 1): every solved
 * coding problem is committed - real artifact, real solution - to the private content
 * repo's local clone, so the GitHub contribution graph becomes a true external record
 * the app cannot fake.
 *
 * <p>{@code repoPath} deliberately defaults to {@code sweprep.content.path} (see
 * {@code application.yml}) rather than naming a second clone: decision #7 is explicit
 * that solutions go to the <em>same</em> private {@code swe-prep-content} repo the
 * problem content is cloned from, just in a different directory ({@link #solutionsDir}).
 * Tests override it to a disposable local repo fixture, never the shared clone.
 *
 * @param enabled      whether auto-commit runs at all; off degrades to "solved, not
 *                     committed" rather than failing a submission (a missing or
 *                     unwritable clone must never block grading)
 * @param repoPath     the local git clone to commit into
 * @param push         whether to push after committing. Pushing is what actually makes
 *                     a commit show on the contribution graph; tests point {@code
 *                     origin} at a local bare repo fixture so this is exercised without
 *                     ever reaching GitHub (see the class javadoc)
 * @param solutionsDir the subdirectory within the clone solutions are written to,
 *                     kept separate from the problem content itself
 * @param authorName   commit author name; {@code null} leaves JGit to resolve identity
 *                     from the clone's own git config (the normal case - a real clone
 *                     already has the captain's committer identity configured, which is
 *                     what makes the commit attributable to a real GitHub account)
 * @param authorEmail  commit author email; must match a verified email on the target
 *                     GitHub account for the commit to count on the contribution graph.
 *                     {@code null} has the same config-derived fallback as {@link
 *                     #authorName}
 */
@ConfigurationProperties(prefix = "sweprep.commit")
public record SolutionCommitProperties(
        Boolean enabled, String repoPath, Boolean push, String solutionsDir, String authorName, String authorEmail) {

    public SolutionCommitProperties {
        enabled = enabled == null || enabled;
        push = push == null || push;
        solutionsDir = (solutionsDir == null || solutionsDir.isBlank()) ? "solutions" : solutionsDir;
        authorName = (authorName == null || authorName.isBlank()) ? null : authorName;
        authorEmail = (authorEmail == null || authorEmail.isBlank()) ? null : authorEmail;
    }
}
