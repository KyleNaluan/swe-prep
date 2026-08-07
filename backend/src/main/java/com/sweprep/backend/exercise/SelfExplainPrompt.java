package com.sweprep.backend.exercise;

import java.util.Objects;

/**
 * One ungraded self-explanation / elaborative-interrogation prompt embedded in a
 * {@link Lesson} (issue #41, design revision t3 section 1.1, delta D3).
 *
 * <p>Reading is the lowest-utility study activity; a self-explanation prompt turns it into a
 * generative one at essentially zero cost. The learner is asked to explain or predict
 * something in their own words, then reveals the {@code modelAnswer} to compare against. It
 * is emphatically part of <em>reading</em>: a lesson is still {@link Lesson READ}, never
 * attempted - there is no response spec, no grader, no verdict, no SRS entry, and nothing is
 * recorded. The reveal is a client-side act the lesson renderer performs; the model answer
 * is not withheld-by-default the way a machine-graded check's explanation is (issue #51),
 * because there is no verdict to protect.
 *
 * @param prompt      the question posed to the reader ("explain why...", "predict what...")
 * @param modelAnswer the answer revealed for self-comparison after the reader has thought
 */
public record SelfExplainPrompt(String prompt, String modelAnswer) {

    public SelfExplainPrompt {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(modelAnswer, "modelAnswer");
    }
}
