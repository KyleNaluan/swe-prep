package com.sweprep.backend.exercise;

import java.time.LocalDate;
import java.util.List;

/**
 * A loaded content item, independent of any language: the sealed supertype the
 * catalog, scheduler and browse surface treat uniformly (issue #46).
 *
 * <p>Content comes in two kinds that genuinely differ. An {@link Exercise} is
 * <em>attempted</em>: it always carries a way to respond and a way to be judged. A
 * {@link Lesson} is <em>read</em>, never attempted: it has no response and nothing
 * to grade. Forcing a lesson into {@code Exercise} would mean inventing a null
 * response and a grader that grades nothing, corrupting a deliberately clean model
 * (concepts-track spec section 1, and its three rejected shoehorns). So the two are
 * siblings under this supertype, sharing only the metadata they genuinely share.
 *
 * <p>The interface is {@code sealed} so every future exhaustive branch stays honest
 * when a third kind appears, and so the shared metadata lives in exactly one place -
 * the content-level {@link #family()}, {@link #stability()} and {@link #reviewed()}
 * tags (design revision t3) were hoisted here from {@code Exercise}, where issue #37
 * had to place them because this supertype did not yet exist.
 */
public sealed interface Content permits Exercise, Lesson {

    /** Stable identifier, unique across the whole content set. */
    String id();

    /** Short title shown above the body. */
    String title();

    /** The body - a prompt for an exercise, the taught text for a lesson - as Markdown. */
    String statement();

    /** The domain it belongs to, e.g. {@code "algorithms"} or {@code "fundamentals"}. */
    String domain();

    /** Topic tags used later by the scheduler; never assumed non-empty. */
    List<String> topics();

    /** How hard it is. */
    Difficulty difficulty();

    /**
     * The role families this content serves (design revision t3, section 2), the tag
     * the family filter selects on; empty when untagged, a list because one item can
     * serve several roles.
     */
    List<Family> family();

    /** How durable the subject matter is (design revision t3, section 3.4). */
    Stability stability();

    /**
     * The date {@link Stability#VOLATILE} content was last re-verified; {@code null}
     * for stable content, which needs none.
     */
    LocalDate reviewed();
}
