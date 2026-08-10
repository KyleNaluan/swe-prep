package com.sweprep.backend.readiness;

import java.util.List;

/**
 * The honest readiness picture (issue #45, design revision t3 section 4.4): what the app
 * shows in place of an invented currency. Every field here is either a machine-verdict-only
 * {@link Progress} or a plain count; none is a score, a badge, or a level, and none is
 * derived from a self-check.
 *
 * @param checksToCriterion the objective competence axis over every {@code REP}-form
 *                          exercise in the catalog: how many have reached the
 *                          successive-relearning criterion (issue #38) versus how many
 *                          exist. Built only from clean machine-verdict passes.
 * @param solvedCold        the objective competence axis over every {@code CHALLENGE}-form
 *                          exercise: how many have been solved with no hint taken and no
 *                          failing case revealed, versus how many exist.
 * @param conceptsCovered   how many Lessons have been read versus how many the catalog
 *                          holds - the axis that makes reading and verifying understanding
 *                          count as earned progress, not just solving code.
 * @param selfCheckExplainedCount how many distinct "explain in your own words" self-check
 *                          items this user has produced, revealed and self-rated (issue
 *                          #41). Deliberately a bare count, not a {@link Progress} against
 *                          a catalog total and never folded into {@link #checksToCriterion}
 *                          or {@link #solvedCold}: a self-rating is not a machine verdict,
 *                          so it must never inflate an objective axis.
 * @param families          the same two objective axes, broken out per role family
 *                          (design revision t3 section 4.4), so a chosen role's
 *                          preparation is legible rather than averaged into one number
 */
public record ReadinessSummary(
        Progress checksToCriterion,
        Progress solvedCold,
        Progress conceptsCovered,
        int selfCheckExplainedCount,
        List<FamilyReadiness> families) {}
