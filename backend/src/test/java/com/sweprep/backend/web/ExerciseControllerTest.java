package com.sweprep.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.testsupport.Fixtures;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the content endpoints over the real (in-memory) catalog: exercises are
 * listed and served to the editor with the right shape. Grading is no longer here -
 * it moved to {@link AttemptControllerTest} because every run is now a submission
 * within a persisted attempt (issue #15). The catalog is stubbed with synthetic demo
 * exercises so no real content is committed (issue #14).
 */
@WebMvcTest(ExerciseController.class)
@Import({JavaLanguageAdapter.class, ExerciseControllerTest.Config.class})
class ExerciseControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        ExerciseCatalog catalog() {
            return Fixtures.catalogOf(Fixtures.pairInAnyOrder(), Fixtures.concept());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsEveryExercise() throws Exception {
        mockMvc.perform(get("/api/exercises"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id=='pair-in-any-order')].form").value("CHALLENGE"))
                .andExpect(jsonPath("$[?(@.id=='concept-demo')].form").value("REP"));
    }

    @Test
    void servesACodeExerciseWithACompilingStub() throws Exception {
        mockMvc.perform(get("/api/exercises/pair-in-any-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Pair In Any Order"))
                .andExpect(jsonPath("$.response.kind").value("code"))
                .andExpect(jsonPath("$.response.language").value("java"))
                .andExpect(jsonPath("$.response.stub").value(Matchers.containsString("class Solution")))
                .andExpect(jsonPath("$.response.stub").value(Matchers.containsString("pair")))
                // The hint-ladder rung names travel so the editor can offer them, but no
                // bodies do - a rung's text is disclosed only when the solver takes it.
                .andExpect(jsonPath("$.hints").value(Matchers.contains("Pattern", "Approach", "Key insight")))
                .andExpect(jsonPath("$.hints[*].body").doesNotExist())
                // This check carries no explanation, so hasExplanation is false.
                .andExpect(jsonPath("$.hasExplanation").value(false));
    }

    @Test
    void servesAChoiceExerciseWithItsOptions() throws Exception {
        mockMvc.perform(get("/api/exercises/concept-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.kind").value("choice"))
                .andExpect(jsonPath("$.response.options").value(Matchers.contains("A", "B", "C")))
                .andExpect(jsonPath("$.response.stub").doesNotExist())
                // The check carries an explanation, so the editor is told one exists -
                // but its text never travels up front (issue #51).
                .andExpect(jsonPath("$.hasExplanation").value(true))
                .andExpect(jsonPath("$.explanation").doesNotExist());
    }

    @Test
    void anUnknownExerciseIsNotFound() throws Exception {
        mockMvc.perform(get("/api/exercises/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
