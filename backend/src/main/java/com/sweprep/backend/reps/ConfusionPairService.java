package com.sweprep.backend.reps;

import com.sweprep.backend.attempt.SubmissionRepository;
import com.sweprep.backend.exercise.ExerciseCatalog;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Wires the persistence and catalog inputs into the pure {@link ConfusionPairs#derive}
 * (issue #39): it reads the user's wrong Choice answers from the {@link
 * SubmissionRepository} and hands them, with the loaded catalog, to the derivation.
 * Keeping the derivation itself pure is what lets its rules - including the cold-history
 * fallback - be unit-tested without a database; this service is only the wiring.
 */
@Service
public class ConfusionPairService {

    private final SubmissionRepository submissions;
    private final ExerciseCatalog catalog;

    public ConfusionPairService(SubmissionRepository submissions, ExerciseCatalog catalog) {
        this.submissions = submissions;
        this.catalog = catalog;
    }

    /** The confusion relation for one user, derived from their recorded wrong answers. */
    public ConfusionPairs forUser(UUID userId) {
        return ConfusionPairs.derive(submissions.failedResponses(userId), catalog.all());
    }
}
