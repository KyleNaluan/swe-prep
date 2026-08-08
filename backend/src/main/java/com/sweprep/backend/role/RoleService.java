package com.sweprep.backend.role;

import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.Family;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The user's role choice, expressed as the active family set the warm-up and auto-seeding draw
 * from (issue #40, design revision t3 section 2). This is the single source of the active families:
 * the {@code WarmupService} reads it here instead of from config, so there is one filter, sourced
 * from the user's durable choice, not two parallel ones.
 *
 * <p>The user chooses a named {@link RolePreset} (a role, not a checklist); the preset expands to a
 * family set that {@link RoleRepository} stores. Until the user has chosen, the effective set is
 * <em>every selectable family</em> - no restriction - so nothing is suppressed before a choice is
 * made, exactly the pre-#40 behaviour. {@link Family#CORE} and {@link Family#PROFESSIONAL} are
 * always-on and handled by the selector; they are never part of the stored or effective set here.
 */
@Service
public class RoleService {

    private final RoleRepository repository;
    private final CurrentUser currentUser;

    public RoleService(RoleRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    /**
     * The selectable families the warm-up and auto-seeding should draw from for the given user, on
     * top of the always-on core. An unset user (no stored choice) gets every selectable family, so
     * the filter only ever narrows once the user has picked a role.
     */
    public Set<Family> activeFamilies(UUID userId) {
        Set<Family> stored = repository.activeFamilies(userId);
        return stored.isEmpty() ? RolePreset.selectableFamilies() : stored;
    }

    /** Applies a role preset for the current user, storing its expanded family set. */
    public RoleStatus selectPreset(RolePreset preset) {
        repository.replaceActiveFamilies(currentUser.id(), preset.families());
        return status();
    }

    /** The current user's role status: the presets on offer, the active set, and which preset it is. */
    public RoleStatus status() {
        UUID userId = currentUser.id();
        Set<Family> stored = repository.activeFamilies(userId);
        boolean chosen = !stored.isEmpty();
        Set<Family> effective = chosen ? EnumSet.copyOf(stored) : RolePreset.selectableFamilies();
        String matchedPreset =
                RolePreset.matching(effective).map(RolePreset::id).orElse(null);
        return RoleStatus.of(effective, chosen, matchedPreset);
    }
}
