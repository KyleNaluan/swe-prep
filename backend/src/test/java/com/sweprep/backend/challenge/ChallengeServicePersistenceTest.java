package com.sweprep.backend.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sweprep.backend.attempt.Attempt;
import com.sweprep.backend.attempt.AttemptOutcome;
import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.AttemptRepository.ChallengeAttemptRow;
import com.sweprep.backend.attempt.AttemptService;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.attempt.SelfCheckReveal;
import com.sweprep.backend.attempt.SelfRating;
import com.sweprep.backend.attempt.SubmissionRepository;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.scheduler.ChallengeQuality;
import com.sweprep.backend.testsupport.Fixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the priority scorer (issue #21) against a real, disposable Postgres, through the
 * same real grade path a solved challenge actually takes: {@link
 * AttemptRepository#challengeReviews} and {@link AttemptRepository#firstChallengeAttemptDates}
 * read back what {@link AttemptService#submit} and {@link AttemptService#abandon} actually
 * wrote, and {@link ChallengeService} turns that into a selection. The scoring arithmetic
 * itself is proven in isolation by {@code ChallengePriorityTest}; this test's job is the
 * plumbing those pure rules cannot check for themselves, mirroring {@code
 * RepDueServicePersistenceTest}'s shape for the sibling rep scheduler.
 */
@SpringBootTest
@Testcontainers
@Transactional
class ChallengeServicePersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // A content clone for ReferenceSolutionCatalog (issue #82) to read from - the
    // reveal-solution tests below need a real solutions/<id>.java file on disk, the same
    // convention the content-authoring tool writes. Never a real problem's solution text:
    // just Fixtures.PAIR_SOLUTION, already an invented sample used elsewhere in this suite.
    @TempDir
    static Path contentDir;

    @DynamicPropertySource
    static void referenceSolution(DynamicPropertyRegistry registry) {
        registry.add("sweprep.content.path", () -> contentDir.toString());
    }

    @BeforeAll
    static void writeReferenceSolution() throws IOException {
        Path solutionsDir = contentDir.resolve("solutions");
        Files.createDirectories(solutionsDir);
        Files.writeString(
                solutionsDir.resolve("pair-in-any-order.java"), Fixtures.PAIR_SOLUTION, StandardCharsets.UTF_8);
    }

    @Autowired
    private AttemptService attemptService;

    @Autowired
    private AttemptRepository attempts;

    @Autowired
    private SubmissionRepository submissions;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private CurrentUser currentUser;

    @MockitoBean
    private ExerciseCatalog catalog;

    // Mocking ExerciseCatalog replaces the shared FileExerciseCatalog bean, so the wider
    // ContentCatalog (LessonController's dependency) must be supplied for the context to load.
    @MockitoBean
    private ContentCatalog contentCatalog;

    private Exercise challenge;

    @BeforeEach
    void setUp() {
        challenge = Fixtures.pairInAnyOrder(); // Form.CHALLENGE, Code + TestCases.
        when(catalog.byId(challenge.id())).thenReturn(Optional.of(challenge));
        when(catalog.all()).thenReturn(List.of(challenge));
    }

    @Test
    void aChallengeNeverAttemptedIsSelected() {
        assertThat(challengeService.selectMain(currentUser.id())).contains(challenge);
    }

    @Test
    void aChallengeJustSolvedThroughTheRealGradePathIsNotSelectedToday() {
        Attempt attempt = attemptService.start(challenge.id());
        attemptService.submit(attempt.id(), Fixtures.PAIR_SOLUTION);

        // The minimum-interval floor excludes anything attempted today, whatever its score.
        assertThat(challengeService.selectMain(currentUser.id())).isEmpty();
    }

    @Test
    void solvingCleanlyOnTheFirstTryReducesToAPerfectQualityReview() {
        Attempt attempt = attemptService.start(challenge.id());
        attemptService.submit(attempt.id(), Fixtures.PAIR_SOLUTION);

        List<ChallengeAttemptRow> reviews = attempts.challengeReviews(currentUser.id());
        assertThat(reviews).hasSize(1);
        ChallengeAttemptRow review = reviews.get(0);
        assertThat(review.exerciseId()).isEqualTo(challenge.id());
        assertThat(review.outcome()).isEqualTo(AttemptOutcome.SOLVED);
        assertThat(review.hintsTaken()).isZero();
        assertThat(review.failingCaseRevealed()).isFalse();

        int submissionCount = submissions.countByAttempt(review.attemptId());
        int quality = ChallengeQuality.derive(
                review.outcome() == AttemptOutcome.SOLVED,
                submissionCount,
                review.hintsTaken(),
                review.failingCaseRevealed(),
                review.solutionSeen(),
                review.complexityClaimCorrect());
        assertThat(quality).isEqualTo(ChallengeQuality.PERFECT);
    }

    // --- Reference-solution reveal (issue #82) ---------------------------------------

    @Test
    void revealingTheSolutionBeforePassingTaintsTheSubsequentCleanReviewsQuality() {
        Attempt attempt = attemptService.start(challenge.id());
        attemptService.revealSolution(attempt.id());
        attemptService.submit(attempt.id(), Fixtures.PAIR_SOLUTION);

        ChallengeAttemptRow review = attempts.challengeReviews(currentUser.id()).get(0);
        assertThat(review.solutionSeen()).isTrue();

        int submissionCount = submissions.countByAttempt(review.attemptId());
        int quality = ChallengeQuality.derive(
                review.outcome() == AttemptOutcome.SOLVED,
                submissionCount,
                review.hintsTaken(),
                review.failingCaseRevealed(),
                review.solutionSeen(),
                review.complexityClaimCorrect());
        // Otherwise a clean first-try pass, but seeing the solution first weakens it below
        // even a WEAK_PASS - "schedules the problem back soon".
        assertThat(quality).isEqualTo(ChallengeQuality.SOLUTION_SEEN);
    }

    @Test
    void revealingTheSolutionAfterAlreadyPassingIsUnrestrictedAndDoesNotTaintTheReview() {
        Attempt attempt = attemptService.start(challenge.id());
        attemptService.submit(attempt.id(), Fixtures.PAIR_SOLUTION);
        attemptService.revealSolution(attempt.id());

        ChallengeAttemptRow review = attempts.challengeReviews(currentUser.id()).get(0);
        assertThat(review.solutionSeen()).isFalse();

        int submissionCount = submissions.countByAttempt(review.attemptId());
        int quality = ChallengeQuality.derive(
                review.outcome() == AttemptOutcome.SOLVED,
                submissionCount,
                review.hintsTaken(),
                review.failingCaseRevealed(),
                review.solutionSeen(),
                review.complexityClaimCorrect());
        assertThat(quality).isEqualTo(ChallengeQuality.PERFECT);
    }

    @Test
    void abandoningAnUnsolvedChallengeRecordsAnIncorrectReview() {
        Attempt attempt = attemptService.start(challenge.id());
        attemptService.abandon(attempt.id());

        ChallengeAttemptRow review = attempts.challengeReviews(currentUser.id()).get(0);
        assertThat(review.exerciseId()).isEqualTo(challenge.id());
        assertThat(review.outcome()).isEqualTo(AttemptOutcome.ABANDONED);
    }

    @Test
    void aSelfCheckChallengeThatJustEndedExplainedIsExcludedByTheMinimumIntervalFloor() {
        // A self-check challenge (issue #41's produce-then-reveal-then-rate item) ends
        // EXPLAINED, never SOLVED/ABANDONED - it carries no machine correctness verdict.
        // Before the fix, challengeReviews excluded EXPLAINED entirely, so such a challenge
        // was permanently invisible to the scorer and could be re-selected the very next
        // day. This proves the fix: the floor now applies to it exactly as it would to a
        // machine-graded challenge solved today.
        Exercise selfCheck = Fixtures.explainChallenge();
        when(catalog.byId(selfCheck.id())).thenReturn(Optional.of(selfCheck));
        when(catalog.all()).thenReturn(List.of(selfCheck));

        Attempt attempt = attemptService.start(selfCheck.id());
        SelfCheckReveal reveal = attemptService.revealSelfCheck(attempt.id(), "my explanation");
        attemptService.rateSelfCheck(attempt.id(), reveal.submission().id(), SelfRating.PARTIAL);

        List<ChallengeAttemptRow> reviews = attempts.challengeReviews(currentUser.id());
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).outcome()).isEqualTo(AttemptOutcome.EXPLAINED);

        assertThat(challengeService.selectMain(currentUser.id())).isEmpty();
    }

    @Test
    void anAttemptStillInProgressIsNotYetACompletedReviewAndIsStillSelectable() {
        attemptService.start(challenge.id());

        assertThat(attempts.challengeReviews(currentUser.id())).isEmpty();
        assertThat(challengeService.selectMain(currentUser.id())).contains(challenge);
    }

    @Test
    void startingAnAttemptRecordsTodayAsItsFirstIntroduction() {
        attemptService.start(challenge.id());

        assertThat(attempts.firstChallengeAttemptDates(currentUser.id())).containsKey(challenge.id());
    }

    @Test
    void unrelatedUsersHistoryNeverLeaksIntoAnotherUsersSelection() {
        UUID otherUser = UUID.randomUUID();
        assertThat(otherUser).isNotEqualTo(currentUser.id());

        Attempt attempt = attemptService.start(challenge.id());
        attemptService.submit(attempt.id(), Fixtures.PAIR_SOLUTION);

        // The current user just solved it (excluded by the floor); a different user's
        // selection is computed independently and must still see it as never attempted.
        assertThat(challengeService.selectMain(otherUser)).contains(challenge);
    }
}
