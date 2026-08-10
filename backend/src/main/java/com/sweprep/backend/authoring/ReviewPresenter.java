package com.sweprep.backend.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.authoring.RepDeriver.DerivationResult;
import com.sweprep.backend.content.AnswerTellChecker;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints a full, readable review of one {@link DerivationResult} - the mechanism
 * behind issue #24's third acceptance criterion, "derived reps and their
 * explanations are presented for human verification before being accepted."
 * Every field a human needs to judge a rep (statement, every option with its
 * correct/distractor status and target misconception, the answer key,
 * explanation) is printed before {@link AuthorContentCli} ever asks whether to
 * write anything, and every derived {@code choice} rep is additionally swept for
 * set-level answer tells (issue #60/#65/#67's {@link AnswerTellChecker}) so a
 * reviewer sees the same advisory findings {@code RealContentSmokeTest} would
 * raise against real content, before the content exists on disk at all.
 */
final class ReviewPresenter {

    private final PrintStream out;
    private final AnswerTellChecker tellChecker = new AnswerTellChecker(new ObjectMapper());

    ReviewPresenter(PrintStream out) {
        this.out = out;
    }

    void present(DerivationResult result) {
        out.println("================================================================");
        out.println("CHALLENGE: " + result.challenge().id());
        out.println("================================================================");
        printExercise(result.challenge());

        out.println();
        out.println("================================================================");
        out.println("DERIVED REPS (" + result.reps().size() + ")");
        out.println("================================================================");
        for (Exercise rep : result.reps()) {
            out.println();
            out.println("--- " + rep.id() + " (derivedFrom=" + rep.derivedFrom() + ") ---");
            printExercise(rep);
        }

        if (!result.skipped().isEmpty()) {
            out.println();
            out.println("SKIPPED (no confident derivation - author by hand if you want these):");
            result.skipped().forEach(s -> out.println("  - " + s));
        }

        List<AnswerTellChecker.Finding> findings = allFindings(result);
        out.println();
        if (findings.isEmpty()) {
            out.println("Answer-tell sweep: clean (no set-level tell findings).");
        } else {
            out.println("Answer-tell findings (advisory - review before accepting):");
            findings.forEach(f -> out.println("  - [" + f.tell() + "] " + f.message()));
        }
    }

    private List<AnswerTellChecker.Finding> allFindings(DerivationResult result) {
        List<Exercise> all = new ArrayList<>();
        all.add(result.challenge());
        all.addAll(result.reps());
        return tellChecker.checkAll(all);
    }

    private void printExercise(Exercise exercise) {
        out.println("title:       " + exercise.title());
        out.println("statement:");
        exercise.statement().lines().forEach(line -> out.println("  " + line));
        out.println("response:    " + describeResponse(exercise.response()));
        out.println("grading:     " + describeGrading(exercise.grading()));
        if (exercise.explanation() != null) {
            out.println("explanation: " + exercise.explanation());
        }
    }

    private String describeResponse(Response response) {
        return switch (response) {
            case Response.Code code -> "code, signature=" + code.signature();
            case Response.Choice choice -> "choice\n" + choice.options().stream()
                    .map(o -> "               "
                            + (o.hasMisconception() ? "[distractor] " : "[CORRECT]    ")
                            + o.text()
                            + (o.hasMisconception() ? "  (misconception: " + o.misconception() + ")" : ""))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("               (no options)");
            case Response.FreeText ignored -> "freeText";
            case Response.Query ignored -> "query";
        };
    }

    private String describeGrading(Grading grading) {
        return switch (grading) {
            case Grading.TestCases testCases -> "testCases, " + testCases.cases().size() + " case(s), "
                    + testCases.comparison();
            case Grading.AnswerKey answerKey -> "answerKey, expected=" + answerKey.expected();
            case Grading.SelfCheck ignored -> "selfCheck";
            case Grading.ResultSet resultSet -> "resultSet, fixture=" + resultSet.fixture();
        };
    }
}
