package com.sweprep.backend.role;

import com.sweprep.backend.exercise.Family;
import java.util.List;
import java.util.Set;

/**
 * The role-filter state the API hands the UI (issue #40): the presets on offer, the families
 * currently active, and which preset (if any) that active set corresponds to. Everything the role
 * picker needs to render "you are preparing for X, here are the other roles you could pick".
 *
 * @param presets       every named role the user can pick, in display order
 * @param activeFamilies the selectable families currently active (never {@code CORE}/{@code
 *                       PROFESSIONAL}, which are always-on)
 * @param currentPreset id of the preset the active set matches, or {@code null} if it matches none
 * @param chosen        whether the user has picked a role yet; {@code false} means "all active by
 *                      default", so the UI can show it as an unconfirmed default rather than a choice
 */
public record RoleStatus(
        List<Preset> presets, List<String> activeFamilies, String currentPreset, boolean chosen) {

    /** One selectable role in the API: its id, label, and the families it turns on. */
    public record Preset(String id, String label, List<String> families) {
        static Preset of(RolePreset preset) {
            return new Preset(
                    preset.id(),
                    preset.label(),
                    preset.families().stream().map(Family::name).sorted().toList());
        }
    }

    static RoleStatus of(Set<Family> active, boolean chosen, String currentPreset) {
        List<Preset> presets =
                java.util.Arrays.stream(RolePreset.values()).map(Preset::of).toList();
        List<String> activeNames = active.stream().map(Family::name).sorted().toList();
        return new RoleStatus(presets, activeNames, currentPreset, chosen);
    }
}
