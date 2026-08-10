package com.sweprep.backend.readiness;

import com.sweprep.backend.exercise.Family;

/**
 * One family's slice of the readiness picture (issue #45, design revision t3 section 4.4):
 * so a chosen role's preparation is legible on its own rather than averaged into one
 * catalog-wide number. Both axes are the same objective, machine-verdict-only measures as
 * the catalog-wide {@link ReadinessSummary} - just scoped to exercises tagged with this
 * family - so a family with nothing tagged in a given axis simply reports {@code 0/0}
 * rather than being omitted.
 *
 * @param family           the role family this line reports on
 * @param checksToCriterion this family's {@code REP}-form exercises reaching the
 *                          successive-relearning criterion (issue #38)
 * @param solvedCold        this family's {@code CHALLENGE}-form exercises solved with no
 *                          help taken
 */
public record FamilyReadiness(Family family, Progress checksToCriterion, Progress solvedCold) {}
