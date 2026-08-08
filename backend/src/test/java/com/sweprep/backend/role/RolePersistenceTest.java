package com.sweprep.backend.role;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.Family;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the role choice survives against a real, disposable Postgres (issue #40): selecting a
 * preset stores its expanded family set durably, re-selecting replaces the set wholesale rather
 * than accumulating, and an unset user reads back as "every family active" so nothing is suppressed
 * before a choice is made.
 */
@SpringBootTest
@Testcontainers
@Transactional
class RolePersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RoleService service;

    @Autowired
    private RoleRepository repository;

    @Autowired
    private CurrentUser currentUser;

    @Test
    void anUnsetUserIsEveryFamilyActive() {
        assertThat(repository.activeFamilies(currentUser.id())).isEmpty();
        assertThat(service.activeFamilies(currentUser.id())).isEqualTo(RolePreset.selectableFamilies());
        assertThat(service.status().chosen()).isFalse();
    }

    @Test
    void selectingAPresetStoresItDurablyAndLabelsIt() {
        RoleStatus status = service.selectPreset(RolePreset.FULL_STACK_AI_ML);

        assertThat(status.chosen()).isTrue();
        assertThat(status.currentPreset()).isEqualTo("full-stack-ai-ml");
        // A fresh read from the repository (not the returned status) still sees the choice.
        assertThat(repository.activeFamilies(currentUser.id()))
                .containsExactlyInAnyOrder(Family.BACKEND, Family.FRONTEND, Family.AIML);
    }

    @Test
    void reselectingReplacesTheSetWholesaleRatherThanAccumulating() {
        service.selectPreset(RolePreset.FULL_STACK_AI_ML);
        service.selectPreset(RolePreset.BACKEND);

        // FRONTEND and AIML are gone, not merged: deactivating a family is exactly this swap.
        assertThat(repository.activeFamilies(currentUser.id())).isEqualTo(EnumSet.of(Family.BACKEND));
        assertThat(service.status().currentPreset()).isEqualTo("backend");
    }
}
