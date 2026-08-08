package com.sweprep.backend.role;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.exercise.Family;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * Proves the role→families expansion (issue #40): presets expand to family sets, "Full-stack" is
 * the union of backend and frontend (not a family), AI/ML is a first-class standalone preset, and
 * the always-on CORE/PROFESSIONAL are never part of any preset.
 */
class RolePresetTest {

    @Test
    void fullStackIsTheUnionOfBackendAndFrontend() {
        assertThat(RolePreset.FULL_STACK.families())
                .containsExactlyInAnyOrder(Family.BACKEND, Family.FRONTEND);
    }

    @Test
    void aiMlIsAFirstClassStandalonePreset() {
        assertThat(RolePreset.AI_ML.families()).containsExactly(Family.AIML);
        // The captain's target leads the list and includes AI/ML alongside full-stack.
        assertThat(RolePreset.values()[0]).isEqualTo(RolePreset.FULL_STACK_AI_ML);
        assertThat(RolePreset.FULL_STACK_AI_ML.families())
                .containsExactlyInAnyOrder(Family.BACKEND, Family.FRONTEND, Family.AIML);
    }

    @Test
    void noPresetTurnsOnTheAlwaysOnFamilies() {
        for (RolePreset preset : RolePreset.values()) {
            assertThat(preset.families())
                    .as("preset %s must not include always-on families", preset)
                    .doesNotContain(Family.CORE, Family.PROFESSIONAL);
        }
    }

    @Test
    void everythingIsExactlyTheSelectableFamilies() {
        assertThat(RolePreset.EVERYTHING.families())
                .isEqualTo(RolePreset.selectableFamilies())
                .doesNotContain(Family.CORE, Family.PROFESSIONAL)
                .contains(Family.DATA, Family.DEVOPS, Family.MOBILE, Family.SYSTEMS);
    }

    @Test
    void byIdRoundTripsAndMatchingLabelsAStoredSet() {
        assertThat(RolePreset.byId("full-stack-ai-ml")).contains(RolePreset.FULL_STACK_AI_ML);
        assertThat(RolePreset.byId("nope")).isEmpty();
        assertThat(RolePreset.matching(EnumSet.of(Family.BACKEND, Family.FRONTEND)))
                .contains(RolePreset.FULL_STACK);
        // A set matching no preset simply has no label.
        assertThat(RolePreset.matching(EnumSet.of(Family.DATA, Family.MOBILE))).isEmpty();
    }
}
