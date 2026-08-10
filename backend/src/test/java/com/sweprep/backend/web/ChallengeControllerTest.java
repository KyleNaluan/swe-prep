package com.sweprep.backend.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sweprep.backend.challenge.ChallengeService;
import com.sweprep.backend.testsupport.Fixtures;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the main-exercise-selection endpoint over the HTTP wire (issue #21): it serves
 * whatever {@link ChallengeService} picks, as a lightweight summary, and a {@code null}
 * exercise (not an error) when nothing is currently selectable. The scoring rules
 * themselves are proven directly in {@code ChallengePriorityTest}; here the service is
 * mocked so this test is only about the seam and the shape, mirroring {@code
 * RepControllerTest}.
 */
@WebMvcTest(ChallengeController.class)
class ChallengeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChallengeService challenges;

    @Test
    void servesTheSelectedChallengeAsASummary() throws Exception {
        when(challenges.selectMain()).thenReturn(Optional.of(Fixtures.pairInAnyOrder()));

        mockMvc.perform(get("/api/challenges/next"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exercise.id").value("pair-in-any-order"))
                .andExpect(jsonPath("$.exercise.form").value("CHALLENGE"))
                // A summary is lightweight: no statement or response spec travels here.
                .andExpect(jsonPath("$.exercise.statement").doesNotExist())
                .andExpect(jsonPath("$.exercise.response").doesNotExist());
    }

    @Test
    void servesANullExerciseWhenNothingIsCurrentlySelectable() throws Exception {
        when(challenges.selectMain()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/challenges/next"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exercise").value(nullValue()));
    }
}
