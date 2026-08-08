package com.sweprep.backend.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.Family;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the role service's logic without a database (issue #40): an unset user is treated as every
 * family active (no restriction), selecting a preset stores its expanded set, and the status labels
 * the active set with the preset it matches.
 */
class RoleServiceTest {

    private final UUID user = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void anUnsetUserGetsEverySelectableFamily() {
        RoleService service = service(Set.of());
        assertThat(service.activeFamilies(user)).isEqualTo(RolePreset.selectableFamilies());
    }

    @Test
    void anUnsetStatusReportsNotChosen() {
        RoleStatus status = service(Set.of()).status();
        assertThat(status.chosen()).isFalse();
        assertThat(status.currentPreset()).isEqualTo("everything");
        assertThat(status.presets()).extracting(RoleStatus.Preset::id).contains("full-stack-ai-ml");
    }

    @Test
    void selectingAPresetStoresItsExpandedFamilies() {
        RoleRepository repository = mock(RoleRepository.class);
        when(repository.activeFamilies(user)).thenReturn(Set.of());
        RoleService service = new RoleService(repository, currentUser());

        service.selectPreset(RolePreset.FULL_STACK_AI_ML);

        verify(repository)
                .replaceActiveFamilies(
                        eq(user), eq(EnumSet.of(Family.BACKEND, Family.FRONTEND, Family.AIML)));
    }

    @Test
    void statusLabelsTheActiveSetWithItsMatchingPreset() {
        RoleStatus status = service(EnumSet.of(Family.BACKEND, Family.FRONTEND)).status();
        assertThat(status.chosen()).isTrue();
        assertThat(status.currentPreset()).isEqualTo("full-stack");
        assertThat(status.activeFamilies()).containsExactlyInAnyOrder("BACKEND", "FRONTEND");
    }

    private RoleService service(Set<Family> stored) {
        RoleRepository repository = mock(RoleRepository.class);
        when(repository.activeFamilies(user)).thenReturn(stored);
        return new RoleService(repository, currentUser());
    }

    private CurrentUser currentUser() {
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(user);
        return currentUser;
    }
}
