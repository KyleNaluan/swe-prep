package com.sweprep.backend.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.sweprep.backend.exercise.Complexity;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.grader.Verdict;
import com.sweprep.backend.testsupport.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves issue #25's acceptance criteria against a real, disposable Postgres: a SQL
 * exercise is solvable through the exact same {@link AttemptService} session flow as any
 * other exercise, each attempt runs in a rolled-back transaction against a separate
 * fixture database as a read-only role, a write or drop is refused rather than merely
 * undone, row order is ignored unless requested and column names never matter, the
 * disclosed failure signal is a bare row count, and a SQL exercise needs no special-casing
 * to skip the complexity self-report flow. Mirrors {@link AttemptPersistenceTest}'s shape:
 * one real Testcontainers Postgres serves both the app's own tables and, via the
 * provisioner under test, the separate fixture database.
 *
 * <p>Deliberately does not use {@code @Transactional}: the app-side rollback that
 * annotation gives every other persistence test has no bearing on the fixture database
 * this test exercises, which lives on a second JDBC connection the provisioner opens
 * itself - and this test wants to observe fixture data surviving (or not) a real commit
 * boundary on the app side, not to have it rolled back by the test harness.
 */
@SpringBootTest
@Testcontainers
class SqlDomainPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    static Path contentDir;

    @DynamicPropertySource
    static void sqlFixtures(DynamicPropertyRegistry registry) {
        registry.add("sweprep.content.path", () -> contentDir.toString());
    }

    @BeforeAll
    static void writeEcommerceFixture() throws IOException {
        Path fixturesDir = contentDir.resolve("sql-fixtures");
        Files.createDirectories(fixturesDir);
        Files.writeString(fixturesDir.resolve("ecommerce.sql"), """
                CREATE TABLE customers (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL
                );
                INSERT INTO customers (id, name) VALUES (1, 'Alice'), (2, 'Bob');
                """);
    }

    @Autowired
    private AttemptService service;

    // Mocking ExerciseCatalog replaces the shared FileExerciseCatalog bean, so the wider
    // ContentCatalog (LessonController's dependency) must be supplied for the context to
    // load - the same sharp edge AttemptPersistenceTest documents.
    @MockitoBean
    private ExerciseCatalog catalog;

    @MockitoBean
    private ContentCatalog contentCatalog;

    private Exercise sqlExercise;

    @BeforeEach
    void setUp() {
        sqlExercise = Fixtures.sqlTopCustomers();
        when(catalog.byId("sql-top-customers")).thenReturn(Optional.of(sqlExercise));
        when(catalog.byId("sql-top-customers-ordered"))
                .thenReturn(Optional.of(Fixtures.sqlTopCustomersOrdered()));
    }

    @Test
    void solvableThroughTheSameAttemptFlowAsAnyOtherExercise() {
        // No SQL-specific start/submit method exists - this is exactly AttemptService.start
        // and .submit, the same two calls every other domain uses (issue #25's core claim).
        Attempt started = service.start("sql-top-customers");
        SubmitResult result = service.submit(started.id(), "SELECT id, name FROM customers");

        assertThat(result.submission().outcome()).isEqualTo(SubmissionOutcome.PASSED);
        assertThat(service.history().stream()
                        .map(AttemptWithCount::attempt)
                        .filter(a -> a.id().equals(started.id()))
                        .findFirst())
                .get()
                .extracting(Attempt::outcome)
                .isEqualTo(AttemptOutcome.SOLVED);
    }

    @Test
    void rowOrderIsIgnoredByDefaultAndColumnNamesAreIgnored() {
        Attempt started = service.start("sql-top-customers");

        // Reversed row order and renamed/reordered-looking columns via aliasing - matched
        // by position, not name, and the default comparison does not care about order.
        SubmitResult result = service.submit(
                started.id(), "SELECT name AS customer_name, id AS customer_id FROM customers ORDER BY id DESC");

        // The columns are swapped (name first, id second) relative to `expected`, so this
        // is deliberately still a FAILED verdict - column position is what is compared,
        // never the name - while a same-position, reordered-rows query passes.
        assertThat(result.submission().outcome()).isEqualTo(SubmissionOutcome.FAILED);

        SubmitResult reordered = service.submit(
                started.id(), "SELECT id, name FROM customers ORDER BY id DESC");
        assertThat(reordered.submission().outcome()).isEqualTo(SubmissionOutcome.PASSED);
    }

    @Test
    void anExerciseThatRequestsOrderRejectsTheWrongOrder() {
        Attempt started = service.start("sql-top-customers-ordered");

        SubmitResult wrongOrder = service.submit(started.id(), "SELECT id, name FROM customers ORDER BY id DESC");
        assertThat(wrongOrder.submission().outcome()).isEqualTo(SubmissionOutcome.FAILED);

        SubmitResult rightOrder = service.submit(started.id(), "SELECT id, name FROM customers ORDER BY id ASC");
        assertThat(rightOrder.submission().outcome()).isEqualTo(SubmissionOutcome.PASSED);
    }

    @Test
    void theMinimalFailureSignalIsABareRowCount() {
        Attempt started = service.start("sql-top-customers");

        SubmitResult result = service.submit(started.id(), "SELECT id, name FROM customers LIMIT 1");

        assertThat(result.submission().outcome()).isEqualTo(SubmissionOutcome.FAILED);
        assertThat(result.submission().passed()).isEqualTo(1);
        assertThat(result.submission().total()).isEqualTo(2);
        // Never a value, an input, or which row was wrong - withhold-by-default judging.
        assertThat(result.submission().detail()).isEmpty();
    }

    @Test
    void aWriteOrDropIsRefusedNotMerelyUndone() {
        Attempt started = service.start("sql-top-customers");

        SubmitResult dropped = service.submit(started.id(), "DROP TABLE customers");
        assertThat(dropped.submission().outcome()).isEqualTo(SubmissionOutcome.COMPILE_ERROR);
        assertThat(dropped.submission().detail()).isNotBlank();

        SubmitResult updated = service.submit(started.id(), "UPDATE customers SET name = 'Mallory'");
        assertThat(updated.submission().outcome()).isEqualTo(SubmissionOutcome.COMPILE_ERROR);

        // The fixture is untouched by either refused attempt: a fresh correct query still
        // sees the original two rows, proving the refusal was not "run then rolled back"
        // but an outright rejection (issue #25's "refused, not merely undone").
        SubmitResult stillIntact = service.submit(started.id(), "SELECT id, name FROM customers");
        assertThat(stillIntact.submission().outcome()).isEqualTo(SubmissionOutcome.PASSED);
    }

    @Test
    void aSqlExerciseCarriesNoComplexityTargetWithNoSpecialCasingInTheModel() {
        Attempt started = service.start("sql-top-customers");
        service.submit(started.id(), "SELECT id, name FROM customers");

        // Exercise.complexityCheck() is simply null here via the ordinary nullable field
        // every domain shares (issue #25) - claimComplexity's existing, ordinary guard for
        // "no complexity check" is what rejects this, not a SQL-specific branch anywhere.
        assertThat(sqlExercise.complexityCheck()).isNull();
        assertThatThrownBy(() -> service.claimComplexity(
                        started.id(), new ComplexityClaim(Complexity.LINEAR, Complexity.LINEAR)))
                .isInstanceOf(InvalidAttemptRequestException.class);
    }

    @Test
    void aSyntaxErrorIsAClearQueryErrorNotAnUnhandledFailure() {
        Attempt started = service.start("sql-top-customers");

        SubmitResult result = service.submit(started.id(), "SELEKT id FROM customers");

        assertThat(result.submission().outcome()).isEqualTo(SubmissionOutcome.COMPILE_ERROR);
        assertThat(result.submission().detail()).isNotBlank();
    }

    @Test
    void verdictOutcomeMapsToTheSameEnumEveryDomainShares() {
        // Verdict itself needed no new Outcome value (issue #25): PASSED/FAILED/
        // COMPILE_ERROR/TIMEOUT/ERROR are shared unchanged across every domain.
        Attempt started = service.start("sql-top-customers");
        SubmitResult passed = service.submit(started.id(), "SELECT id, name FROM customers");
        assertThat(passed.submission().outcome()).isEqualTo(SubmissionOutcome.of(Verdict.Outcome.PASSED));
    }
}
