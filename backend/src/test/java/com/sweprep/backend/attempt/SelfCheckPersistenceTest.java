package com.sweprep.backend.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.learned.LearnedService;
import com.sweprep.backend.learned.LearnedState.Status;
import com.sweprep.backend.testsupport.Fixtures;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the self-graded "explain in your own words" flow end to end against a real,
 * disposable Postgres (issue #41): produce-then-reveal commits the learner's text before the
 * model answer is handed back, a self-rating is recorded against that submission, the sitting
 * ends {@code EXPLAINED}, and - the boundary the design revision is emphatic about - a pile of
 * maximal self-ratings leaves the objective competence signal (issue #38) completely
 * untouched, because a self-check never writes a {@code PASSED} row for it to read.
 */
@SpringBootTest
@Testcontainers
@Transactional
class SelfCheckPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AttemptService attempts;

    @Autowired
    private SubmissionRepository submissions;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private LearnedService learned;

    @Autowired
    private CurrentUser currentUser;

    @MockitoBean
    private ExerciseCatalog catalog;

    // FileExerciseCatalog is one bean implementing both catalog seams; mocking ExerciseCatalog
    // replaces it, so the wider ContentCatalog (consumed by LessonController) must be supplied
    // too or the whole context fails to load. This test never reads it - lessons are elsewhere.
    @MockitoBean
    private ContentCatalog contentCatalog;

    @BeforeEach
    void setUp() {
        Exercise explain = Fixtures.explainChallenge(); // FreeText + SelfCheck: never graded.
        Exercise concept = Fixtures.concept(); // Choice + AnswerKey: machine-graded.
        when(catalog.byId("explain-gradient-descent")).thenReturn(Optional.of(explain));
        when(catalog.byId("concept-demo")).thenReturn(Optional.of(concept));
    }

    @Test
    void revealCommitsTheProducedTextThenHandsBackTheModelAnswer() {
        UUID attemptId = attempts.start("explain-gradient-descent").id();

        SelfCheckReveal reveal = attempts.revealSelfCheck(attemptId, "  my cold explanation  ");

        // The model answer is disclosed for self-comparison...
        assertThat(reveal.modelAnswer()).contains("steepest descent");
        // ...and the produced text was frozen (stripped) as its own committed submission,
        // marked SELF_RATED - never a machine verdict.
        Submission committed = submissions.findById(reveal.submission().id()).orElseThrow();
        assertThat(committed.response()).isEqualTo("my cold explanation");
        assertThat(committed.outcome()).isEqualTo(SubmissionOutcome.SELF_RATED);
    }

    @Test
    void ratingRecordsTheSelfRatingAndEndsTheSittingExplained() {
        UUID attemptId = attempts.start("explain-gradient-descent").id();
        SelfCheckReveal reveal = attempts.revealSelfCheck(attemptId, "my explanation");

        SelfCheckRating rated =
                attempts.rateSelfCheck(attemptId, reveal.submission().id(), SelfRating.PARTIAL);

        assertThat(rated.rating()).isEqualTo(SelfRating.PARTIAL);
        assertThat(rated.attempt().attempt().outcome()).isEqualTo(AttemptOutcome.EXPLAINED);
        // The rating is recorded against the submission (in detail), re-read from Postgres.
        Submission stored = submissions.findById(reveal.submission().id()).orElseThrow();
        assertThat(stored.detail()).isEqualTo("PARTIAL");
        assertThat(attemptRepository.findById(attemptId).orElseThrow().outcome())
                .isEqualTo(AttemptOutcome.EXPLAINED);
    }

    @Test
    void producingIsRequiredBeforeRevealing() {
        UUID attemptId = attempts.start("explain-gradient-descent").id();
        assertThatThrownBy(() -> attempts.revealSelfCheck(attemptId, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Produce an explanation");
    }

    @Test
    void revealingANonSelfCheckItemIsRejected() {
        UUID attemptId = attempts.start("concept-demo").id();
        assertThatThrownBy(() -> attempts.revealSelfCheck(attemptId, "text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a self-check");
    }

    @Test
    void ratingWithoutRevealingFirstIsRejected() {
        UUID attemptId = attempts.start("explain-gradient-descent").id();
        assertThatThrownBy(
                        () -> attempts.rateSelfCheck(attemptId, UUID.randomUUID(), SelfRating.NAILED_IT))
                .isInstanceOf(AttemptNotFoundException.class);
    }

    @Test
    void ratingIsIrreversibleOncePlaced() {
        UUID attemptId = attempts.start("explain-gradient-descent").id();
        SelfCheckReveal reveal = attempts.revealSelfCheck(attemptId, "my explanation");
        UUID submissionId = reveal.submission().id();
        attempts.rateSelfCheck(attemptId, submissionId, SelfRating.NAILED_IT);

        // The sitting is EXPLAINED (terminal), so a second rating cannot overwrite it.
        assertThatThrownBy(
                        () -> attempts.rateSelfCheck(attemptId, submissionId, SelfRating.MISSED))
                .isInstanceOf(IllegalAttemptStateException.class);
    }

    @Test
    void aPileOfMaximalSelfRatingsLeavesTheObjectiveSignalUnmoved() {
        // Practise the explain item hard: many sittings, each produced, revealed, and
        // self-rated NAILED_IT - the most a learner could ever claim.
        for (int i = 0; i < 5; i++) {
            UUID attemptId = attempts.start("explain-gradient-descent").id();
            SelfCheckReveal reveal = attempts.revealSelfCheck(attemptId, "explanation " + i);
            attempts.rateSelfCheck(attemptId, reveal.submission().id(), SelfRating.NAILED_IT);
        }

        // The competence signal reads only clean machine passes, and a self-check writes
        // none: no PASSED rows, so the item is still NEW no matter how it was self-rated.
        assertThat(submissions.cleanPassInstants(currentUser.id(), "explain-gradient-descent"))
                .isEmpty();
        assertThat(learned.stateFor("explain-gradient-descent").status()).isEqualTo(Status.NEW);
        assertThat(learned.statesForAll()).doesNotContainKey("explain-gradient-descent");

        // And submitting a self-check for a machine verdict is still refused outright, so
        // there is no back door to a PASSED row either.
        UUID another = attempts.start("explain-gradient-descent").id();
        assertThatThrownBy(() -> attempts.submit(another, "explanation"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everySelfRatedSubmissionStaysOutOfTheCleanPassQuery() {
        UUID attemptId = attempts.start("explain-gradient-descent").id();
        SelfCheckReveal reveal = attempts.revealSelfCheck(attemptId, "my explanation");
        attempts.rateSelfCheck(attemptId, reveal.submission().id(), SelfRating.NAILED_IT);

        // The submission exists and is counted for history, but it is SELF_RATED, so the
        // batch competence query the scheduler reads never returns it.
        List<Submission> all = submissions.findByAttempt(attemptId);
        assertThat(all).singleElement().satisfies(s ->
                assertThat(s.outcome()).isEqualTo(SubmissionOutcome.SELF_RATED));
        assertThat(learned.statesForAll()).doesNotContainKey("explain-gradient-descent");
    }
}
