package com.sweprep.backend.exercise;

import java.util.Objects;

/**
 * One selectable option of a {@link Response.Choice}: the {@code text} the learner
 * reads and picks, and - for a <em>distractor</em> (a wrong option) - the specific,
 * nameable {@code misconception} it is engineered to catch (issue #42).
 *
 * <p>A competitive multiple-choice question is the only kind the evidence rescues from
 * the recognition penalty (Little &amp; Bjork 2012), and a competitive question is one
 * whose every wrong option is genuinely tempting because it encodes a mistake a real
 * learner actually makes. A lazily written distractor that nobody would pick turns the
 * question into a giveaway and teaches nothing. So the bar (design revision t3, delta D4;
 * the topics report's Role-3 distractor gate) is that <b>every distractor must carry the
 * misconception it targets</b>, written down and reviewed alongside the question. This
 * record is where that declaration lives, adjacent to the option it explains.
 *
 * <p>The correct option targets no misconception, so its {@code misconception} is
 * {@code null}; a distractor's must be present and non-blank. Whether an option is the
 * correct one is not knowable from the option alone (it is decided by the exercise's
 * {@link Grading.AnswerKey}), so the "every distractor is annotated" rule is enforced
 * where both are in view - the content loader ({@code ExerciseParser}), which rejects a
 * distractor that declares no misconception. That is the mechanical half of the gate:
 * whether a misconception was <em>declared</em> is checked by machine; whether it is
 * genuinely <em>plausible</em> is the human red-team judgement the authoring checklist
 * owns.
 *
 * <p>The misconception is authoring and verification metadata (the topics report's
 * "Claims Ledger"), <b>never shipped to the learner</b> - the editor is served only the
 * option {@code text} (see {@code ResponseView}), exactly as a check's explanation, a
 * self-check's model answer and a hint's body are all withheld until the moment they are
 * meant to be seen.
 */
public record Option(String text, String misconception) {

    public Option {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("option text must not be blank");
        }
        // A blank misconception is treated as none, so "declared but empty" cannot pass
        // the loader gate by looking non-null - it collapses to the same "not declared".
        misconception = misconception == null || misconception.isBlank() ? null : misconception;
    }

    /** The correct option - it targets no misconception. */
    public static Option correct(String text) {
        return new Option(text, null);
    }

    /** A wrong option that encodes the named misconception; the name must be non-blank. */
    public static Option distractor(String text, String misconception) {
        if (misconception == null || misconception.isBlank()) {
            throw new IllegalArgumentException(
                    "a distractor must name the specific misconception it targets (issue #42)");
        }
        return new Option(text, misconception);
    }

    /** Whether this option declares a target misconception (i.e. is an annotated distractor). */
    public boolean hasMisconception() {
        return misconception != null;
    }
}
