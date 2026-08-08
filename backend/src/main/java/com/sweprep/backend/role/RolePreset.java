package com.sweprep.backend.role;

import com.sweprep.backend.exercise.Family;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * A named role the user is preparing for, which expands to a set of {@link Family}s (design
 * revision t3, section 2). The whole point of the presets is that the user picks a <em>role</em>,
 * not a checklist of tags: "Full-stack" is not a family, it is the union {@code BACKEND ∪
 * FRONTEND}, and "Full-stack + AI/ML" adds the captain's specialisation on top. Selecting a preset
 * stores its expanded family set (issue #40), so this enum is the only place the role→families
 * mapping lives.
 *
 * <p>The set a preset expands to is the <em>selectable</em> families only. {@link Family#CORE} and
 * {@link Family#PROFESSIONAL} are always active for everyone and are never part of a preset - they
 * are the universal substrate the daily habit always trains, whatever role is chosen.
 *
 * <p>Deliberately a small, honest set rather than one preset per permutation. AI/ML is first-class
 * for this captain (design revision t3, section 3): it leads the list and stands alone as its own
 * preset, never bolted onto a backend one. The niche breadth families ({@code DATA}, {@code DEVOPS},
 * {@code MOBILE}, {@code SYSTEMS}) are reached through {@link #EVERYTHING} and stay reachable
 * through browse and the optional tiers regardless of the chosen role.
 */
public enum RolePreset {

    /** The captain's declared target: full-stack plus his AI/ML specialisation (leads the list). */
    FULL_STACK_AI_ML("full-stack-ai-ml", "Full-stack + AI/ML",
            EnumSet.of(Family.BACKEND, Family.FRONTEND, Family.AIML)),
    /** AI/ML on its own - first-class depth, not a sample family (design revision t3, section 3). */
    AI_ML("ai-ml", "AI/ML", EnumSet.of(Family.AIML)),
    /** Backend roles. */
    BACKEND("backend", "Backend", EnumSet.of(Family.BACKEND)),
    /** Full-stack: the union of backend and frontend, not a family of its own. */
    FULL_STACK("full-stack", "Full-stack", EnumSet.of(Family.BACKEND, Family.FRONTEND)),
    /** Frontend roles. */
    FRONTEND("frontend", "Frontend", EnumSet.of(Family.FRONTEND)),
    /** Every selectable family: breadth across all roles (also how DATA/DEVOPS/MOBILE/SYSTEMS are trained). */
    EVERYTHING("everything", "Everything", selectableFamilies());

    private final String id;
    private final String label;
    private final Set<Family> families;

    RolePreset(String id, String label, Set<Family> families) {
        this.id = id;
        this.label = label;
        this.families = EnumSet.copyOf(families);
    }

    /** The stable id used in the JSON API, e.g. {@code "full-stack-ai-ml"}. */
    public String id() {
        return id;
    }

    /** The human-readable label the UI shows, e.g. {@code "Full-stack + AI/ML"}. */
    public String label() {
        return label;
    }

    /** The selectable families this role expands to (never {@code CORE}/{@code PROFESSIONAL}). */
    public Set<Family> families() {
        return EnumSet.copyOf(families);
    }

    /** The preset with this API id, if any. */
    public static Optional<RolePreset> byId(String id) {
        for (RolePreset preset : values()) {
            if (preset.id.equals(id)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    /**
     * The preset whose family set equals the given active set, if one matches exactly. Used only
     * to label the current selection for display; a stored set that matches no preset (possible if
     * a future UI ever allows per-family tweaks) simply has no matched preset.
     */
    public static Optional<RolePreset> matching(Set<Family> active) {
        Set<Family> selectable = EnumSet.copyOf(active);
        selectable.retainAll(selectableFamilies());
        for (RolePreset preset : values()) {
            if (preset.families.equals(selectable)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    /** Every family a user can select - all of them except the always-on {@code CORE}/{@code PROFESSIONAL}. */
    public static Set<Family> selectableFamilies() {
        Set<Family> all = EnumSet.allOf(Family.class);
        all.remove(Family.CORE);
        all.remove(Family.PROFESSIONAL);
        return all;
    }
}
