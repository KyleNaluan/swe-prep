package com.sweprep.backend.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.testsupport.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves reading a Lesson is idempotent per (user, lesson) against a real Postgres (issue #40):
 * the frontend fires the read best-effort on every open, so re-reads must refresh the one {@code
 * READ} record rather than pile up duplicates. The invariant is enforced in the DB - the partial
 * unique index {@code attempt_one_read_per_user_exercise} plus an {@code INSERT ... ON CONFLICT}
 * upsert - not by a read-then-write, so it holds even when two first-reads race concurrently.
 *
 * <p>Deliberately not {@code @Transactional}: each {@link LessonReadService#recordRead} must commit
 * in its own transaction for the concurrency test to exercise the real race, so the rows are cleaned
 * up by hand after each test. The catalog is mocked - real content lives only in the private repo
 * (issue #14) - and both catalog seams are mocked because the single {@code FileExerciseCatalog}
 * bean satisfies both and mocking one would leave the other unsatisfied.
 */
@SpringBootTest
@Testcontainers
class LessonReadPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private LessonReadService reads;

    @Autowired
    private AttemptRepository attempts;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private ContentCatalog content;

    @MockitoBean
    private ExerciseCatalog exercises;

    private UUID user;

    @BeforeEach
    void setUp() {
        user = currentUser.id();
        when(content.contentById("lesson-indexes"))
                .thenReturn(Optional.of(Fixtures.lessonWithPrompts()));
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DELETE FROM attempt WHERE user_id = :user").param("user", user).update();
    }

    @Test
    void reReadingRefreshesTheOneRecordWithoutInsertingADuplicate() {
        Attempt first = reads.recordRead("lesson-indexes");
        Attempt second = reads.recordRead("lesson-indexes");

        // Same record refreshed, not a second row: identity is preserved and the timestamp moves
        // forward (or is unchanged if the two reads land in the same instant).
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.endedAt()).isAfterOrEqualTo(first.endedAt());
        assertThat(countReadRows("lesson-indexes")).isEqualTo(1);
        // The seeding fact the selector reads back is unchanged - still exactly this lesson.
        assertThat(attempts.readLessonIds(user)).containsExactly("lesson-indexes");
    }

    @Test
    void concurrentFirstReadsOfTheSameLessonProduceOneRowWithNoError() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        // Release every thread at once so the first reads genuinely race.
                        barrier.await();
                        reads.recordRead("lesson-indexes");
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // No thread saw an error (no duplicate-key blow-up, no >1-row read), and the DB holds
        // exactly one READ row for the pair - the upsert on the partial unique index serialised
        // the racing inserts rather than letting two land.
        assertThat(errors).isEmpty();
        assertThat(countReadRows("lesson-indexes")).isEqualTo(1);
    }

    private long countReadRows(String exerciseId) {
        return jdbc.sql(
                        "SELECT count(*) FROM attempt "
                                + "WHERE user_id = :user AND exercise_id = :exerciseId "
                                + "AND outcome = 'READ'")
                .param("user", user)
                .param("exerciseId", exerciseId)
                .query(Long.class)
                .single();
    }
}
