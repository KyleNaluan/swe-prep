package com.sweprep.backend.exercise;

/**
 * The role family a piece of content serves, the tag the user-facing family filter
 * selects on (design revision t3, section 2). Content carries a <em>list</em> of
 * these, since one concept can serve more than one role.
 *
 * <p>The tag is a closed enumeration on purpose: it is the value the selector
 * matches when it suppresses an inactive family from the required core and from
 * auto-seeding while leaving it fully reachable through browse and the optional
 * tiers. Which families are <em>active</em> is a user setting decided elsewhere
 * (that filter is a separate ticket); this type only names the taxonomy.
 *
 * <p>{@link #CORE} and {@link #PROFESSIONAL} are the always-active substrate -
 * {@code CORE} is not deselectable and {@code PROFESSIONAL} is default-on - so
 * that the daily habit always trains the universal "any SWE job" material; the
 * rest are selectable. "Full-stack" is not a family but the union of
 * {@link #BACKEND} and {@link #FRONTEND}.
 */
public enum Family {
    /** The universal Tier-1 core; always active, not deselectable. */
    CORE,
    /** STAR, teamwork, reading code, debugging, SDLC; always active, default-on. */
    PROFESSIONAL,
    /** Backend-flavored breadth and depth. */
    BACKEND,
    /** Frontend breadth. */
    FRONTEND,
    /** Data breadth. */
    DATA,
    /** DevOps and cloud breadth. */
    DEVOPS,
    /** Mobile. */
    MOBILE,
    /** Systems and embedded breadth. */
    SYSTEMS,
    /** AI/ML depth (design revision t3, section 3). */
    AIML
}
