package com.sweprep.backend.commit;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Commits a solved problem's real solution to the private content repo's local clone
 * (issue #22, decision #7 item 1) - the mechanic that turns the GitHub contribution
 * graph into a true external record the app cannot fake.
 *
 * <p><b>Hard rule: only real work is ever committed.</b> Three separate guards enforce
 * this rather than one: {@link #commitSolution} only runs for a {@link Response.Code}
 * exercise (there is a real source file to commit - a picked option or a predicted
 * value is not a "solution" artifact); it is called by {@code AttemptService} only on
 * the submission that <em>freshly</em> solves an attempt, never on a failed run; and
 * {@link CommitCommand#setAllowEmpty(boolean)} is set {@code false}, so resubmitting
 * byte-identical code (nothing to commit) is rejected - as an {@link EmptyCommitException}
 * on a full-index commit, or, since {@link CommitCommand#setOnly} builds a temporary
 * index, as a {@link JGitInternalException} carrying the same "no changes" case through
 * a different code path - and reported as a skip either way, never an empty, contentless
 * commit.
 *
 * <p>Every failure mode - the clone is not present, it is not a git repository, the
 * push has no reachable remote - degrades to a skipped or committed-but-not-pushed
 * {@link SolutionCommitResult}, logged and swallowed. This must
 * never throw: it runs after the submission's own transaction has already committed
 * (see {@code AttemptService#submit}), so a slow or failing git operation can neither
 * hold a database connection open nor fail grading that already succeeded.
 */
@Component
public class SolutionCommitService {

    private static final Logger log = LoggerFactory.getLogger(SolutionCommitService.class);

    private final SolutionCommitProperties properties;

    public SolutionCommitService(SolutionCommitProperties properties) {
        this.properties = properties;
    }

    /** Commits {@code code} as the solution for a freshly solved {@code exercise}. */
    public SolutionCommitResult commitSolution(Exercise exercise, String code) {
        if (!properties.enabled()) {
            return SolutionCommitResult.ofSkipped("auto-commit disabled");
        }
        if (!(exercise.response() instanceof Response.Code)) {
            return SolutionCommitResult.ofSkipped("not a coding exercise; no solution artifact to commit");
        }
        if (code == null || code.isBlank()) {
            return SolutionCommitResult.ofSkipped("no source to commit");
        }

        Path repoDir = Path.of(properties.repoPath());
        if (!Files.isDirectory(repoDir.resolve(".git"))) {
            log.warn(
                    "Solution auto-commit skipped for '{}': {} is not a git repository clone",
                    exercise.id(),
                    repoDir);
            return SolutionCommitResult.ofSkipped("content clone not found or not a git repository");
        }

        String relativePath = properties.solutionsDir() + "/" + exercise.id() + ".java";
        try (Git git = Git.open(repoDir.toFile())) {
            Path solutionFile = repoDir.resolve(relativePath);
            Files.createDirectories(solutionFile.getParent());
            Files.writeString(solutionFile, code);
            git.add().addFilepattern(relativePath).call();

            CommitCommand commit = git.commit()
                    .setOnly(relativePath)
                    .setMessage("Solve " + exercise.id() + ": " + exercise.title())
                    .setAllowEmpty(false);
            if (properties.authorName() != null && properties.authorEmail() != null) {
                PersonIdent identity = new PersonIdent(properties.authorName(), properties.authorEmail());
                commit.setAuthor(identity).setCommitter(identity);
            }
            commit.call();
            log.info("Committed solution for '{}' to {}", exercise.id(), repoDir);

            if (!properties.push()) {
                return SolutionCommitResult.ofCommitted();
            }
            try {
                // Named explicitly (the current branch, not the push.default-dependent
                // implicit refspec) so this pushes on a freshly initialised clone with no
                // upstream tracking configured, not only on a `git clone`-style one.
                String branch = git.getRepository().getBranch();
                Iterable<PushResult> pushResults = git.push().setRemote("origin").add(branch).call();
                String rejection = firstRejection(pushResults);
                if (rejection != null) {
                    log.warn("Solution for '{}' committed locally but push was rejected: {}", exercise.id(), rejection);
                    return SolutionCommitResult.ofCommittedButPushFailed("committed locally; push rejected: " + rejection);
                }
                return SolutionCommitResult.ofCommitted();
            } catch (GitAPIException e) {
                log.warn("Solution for '{}' committed locally but push failed: {}", exercise.id(), e.getMessage());
                return SolutionCommitResult.ofCommittedButPushFailed("committed locally; push failed: " + e.getMessage());
            }
        } catch (EmptyCommitException e) {
            return SolutionCommitResult.ofSkipped("solution unchanged since the last commit");
        } catch (JGitInternalException e) {
            // CommitCommand#setOnly builds a temporary index and reports "nothing to
            // commit" through this unchecked exception rather than EmptyCommitException
            // - same case as above (a resubmission with byte-identical code), reached by
            // a different code path. Anything else wrapped in here is a genuine internal
            // git error and is still swallowed to a skip, per this class's "never throw" rule.
            if (e.getMessage() != null && e.getMessage().contains("No changes")) {
                return SolutionCommitResult.ofSkipped("solution unchanged since the last commit");
            }
            log.warn("Solution auto-commit failed for '{}': {}", exercise.id(), e.getMessage());
            return SolutionCommitResult.ofSkipped("commit failed: " + e.getMessage());
        } catch (IOException | GitAPIException e) {
            log.warn("Solution auto-commit failed for '{}': {}", exercise.id(), e.getMessage());
            return SolutionCommitResult.ofSkipped("commit failed: " + e.getMessage());
        }
    }

    /**
     * JGit reports a rejected push (non-fast-forward, permission/hook refusal, remote
     * moved) as a per-ref {@link RemoteRefUpdate.Status} on the returned result rather
     * than throwing, so a status that is neither {@code OK} nor {@code UP_TO_DATE} is a
     * silent push failure the caller must surface. Returns a description of the first
     * such rejection, or {@code null} when every ref update succeeded.
     */
    private static String firstRejection(Iterable<PushResult> pushResults) {
        for (PushResult pushResult : pushResults) {
            for (RemoteRefUpdate update : pushResult.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                    String message = update.getMessage();
                    return update.getRemoteName() + ": " + status + (message != null ? " (" + message + ")" : "");
                }
            }
        }
        return null;
    }
}
