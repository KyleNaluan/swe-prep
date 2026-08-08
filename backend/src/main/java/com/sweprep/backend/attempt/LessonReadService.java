package com.sweprep.backend.attempt;

import com.sweprep.backend.exercise.Content;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Lesson;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that a Lesson was read (issue #40/#46). A Lesson is read, never attempted - there is no
 * response, grader or verdict - but the reading itself is a durable record: an {@link
 * AttemptOutcome#READ} attempt, the outcome added ahead of the lesson track precisely for this. It
 * carries no machine verdict and the objective competence signal is structurally blind to it, so
 * recording a read can never graduate a Check (the "learned" derivation, issue #38, only reads
 * {@code PASSED} rows).
 *
 * <p>Why it matters to the family filter: reading a Lesson from an inactive family opts <em>that
 * concept's</em> Checks into the warm-up (design revision t3 section 2.2), so the warm-up build
 * reads these {@code READ} records back to widen the eligible set. Recording it here is what turns
 * "the user read this lesson" into a queryable fact the selector can act on.
 */
@Service
public class LessonReadService {

    private final ContentCatalog content;
    private final AttemptRepository attempts;
    private final CurrentUser currentUser;

    public LessonReadService(
            ContentCatalog content, AttemptRepository attempts, CurrentUser currentUser) {
        this.content = content;
        this.attempts = attempts;
        this.currentUser = currentUser;
    }

    /**
     * Records the current user as having read the given Lesson, returning the stored attempt. The
     * id must be a Lesson - an Exercise is attempted, not read - so a non-lesson id is a {@link
     * AttemptNotFoundException} (404), the same shape the reader's GET already gives.
     */
    @Transactional
    public Attempt recordRead(String lessonId) {
        Content item = content
                .contentById(lessonId)
                .orElseThrow(() -> new AttemptNotFoundException("No lesson with id '" + lessonId + "'"));
        if (!(item instanceof Lesson lesson)) {
            throw new AttemptNotFoundException("Content '" + lessonId + "' is not a lesson to read");
        }
        Instant now = Instant.now();
        Attempt attempt = new Attempt(
                UUID.randomUUID(),
                currentUser.id(),
                lesson.id(),
                lesson.title(),
                lesson.domain(),
                "LESSON",
                AttemptOutcome.READ,
                now,
                now,
                0,
                false,
                null,
                false,
                null,
                null,
                null);
        attempts.insert(attempt);
        return attempt;
    }
}
