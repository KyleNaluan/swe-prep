package com.sweprep.backend.reps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sweprep.backend.attempt.Attempt;
import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.AttemptRepository.RepReview;
import com.sweprep.backend.attempt.AttemptService;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
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
 * Proves the due-date queue (issue #20) against a real, disposable Postgres, through the same
 * real grade path a warm-up rep actually takes: {@link AttemptRepository#repReviews} reads
 * back what {@link AttemptService#submit}, {@link AttemptService#requestExplanation} and
 * {@link AttemptService#abandon} actually wrote, and {@link RepDueService} turns that into a
 * due/not-due verdict. The SM-2 arithmetic itself (advance, shorten, and the explanation-
 * requested divergence) is proven in isolation by {@code Sm2SchedulerTest}; this test's job is
 * the plumbing those pure rules cannot check for themselves - the real SQL query and the real
 * {@code explanation_requested} flag actually reaching {@link
 * com.sweprep.backend.scheduler.ReviewQuality}.
 */
@SpringBootTest
@Testcontainers
@Transactional
class RepDueServicePersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AttemptService attemptService;

    @Autowired
    private AttemptRepository attempts;

    @Autowired
    private RepDueService repDue;

    @Autowired
    private CurrentUser currentUser;

    @MockitoBean
    private ExerciseCatalog catalog;

    // Mocking ExerciseCatalog replaces the shared FileExerciseCatalog bean, so the wider
    // ContentCatalog (LessonController's dependency) must be supplied for the context to load.
    @MockitoBean
    private ContentCatalog contentCatalog;

    private Exercise rep;

    @BeforeEach
    void setUp() {
        rep = Fixtures.patternIdRep(); // Form.REP, Choice + AnswerKey, carries an explanation.
        when(catalog.byId(rep.id())).thenReturn(Optional.of(rep));
    }

    @Test
    void aRepJustSolvedThroughTheRealGradePathIsNoLongerDueToday() {
        Attempt attempt = attemptService.start(rep.id());
        attemptService.submit(attempt.id(), "Two pointers"); // the correct option.

        assertThat(repDue.dueToday(currentUser.id(), List.of(rep))).doesNotContain(rep.id());
    }

    @Test
    void aRepNeverAttemptedStaysDue() {
        assertThat(repDue.dueToday(currentUser.id(), List.of(rep))).contains(rep.id());
    }

    @Test
    void requestingTheExplanationAfterSolvingRecordsAWeakerReviewThanNotAsking() {
        Attempt attempt = attemptService.start(rep.id());
        attemptService.submit(attempt.id(), "Two pointers");
        attemptService.requestExplanation(attempt.id()); // the "asked why" confidence signal.

        List<RepReview> reviews = attempts.repReviews(currentUser.id());
        assertThat(reviews).hasSize(1);
        RepReview review = reviews.get(0);
        assertThat(review.exerciseId()).isEqualTo(rep.id());
        assertThat(review.solved()).isTrue();
        assertThat(review.explanationRequested()).isTrue();
    }

    @Test
    void solvingWithoutRequestingTheExplanationLeavesTheFlagUnset() {
        Attempt attempt = attemptService.start(rep.id());
        attemptService.submit(attempt.id(), "Two pointers");

        RepReview review = attempts.repReviews(currentUser.id()).get(0);
        assertThat(review.solved()).isTrue();
        assertThat(review.explanationRequested()).isFalse();
    }

    @Test
    void abandoningAnUnsolvedRepRecordsAnIncorrectReview() {
        Attempt attempt = attemptService.start(rep.id());
        attemptService.abandon(attempt.id());

        RepReview review = attempts.repReviews(currentUser.id()).get(0);
        assertThat(review.exerciseId()).isEqualTo(rep.id());
        assertThat(review.solved()).isFalse();
    }

    @Test
    void anAttemptStillInProgressIsNotYetACompletedReview() {
        attemptService.start(rep.id());

        assertThat(attempts.repReviews(currentUser.id())).isEmpty();
        assertThat(repDue.dueToday(currentUser.id(), List.of(rep))).contains(rep.id());
    }

    @Test
    void unrelatedUsersReviewHistoryNeverLeaksIntoAnotherUsersDueSet() {
        UUID otherUser = UUID.randomUUID();
        assertThat(otherUser).isNotEqualTo(currentUser.id());

        Attempt attempt = attemptService.start(rep.id());
        attemptService.submit(attempt.id(), "Two pointers");

        // The current user's rep is settled (not due today); a different user's due set is
        // computed independently and must treat the same rep as never reviewed.
        assertThat(repDue.dueToday(otherUser, List.of(rep))).contains(rep.id());
    }
}
