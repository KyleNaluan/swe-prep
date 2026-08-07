package com.sweprep.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.grader.AnswerKeyGrader;
import com.sweprep.backend.grader.GraderRegistry;
import com.sweprep.backend.grader.TestCaseGrader;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.runner.LocalJavaRunner;
import com.sweprep.backend.testsupport.Fixtures;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the flow end to end through the HTTP wire, over the real (in-memory)
 * catalog and the real graders: exercises are listed and served to the editor, a
 * code answer is compiled and run, and a concept answer is graded with no runner.
 * The catalog is stubbed with synthetic demo exercises so no real content is
 * committed (issue #14); the loader itself is proven in {@code FileExerciseCatalogTest}.
 */
@WebMvcTest(ExerciseController.class)
@Import({
    JavaLanguageAdapter.class,
    LocalJavaRunner.class,
    TestCaseGrader.class,
    AnswerKeyGrader.class,
    GraderRegistry.class,
    ExerciseControllerTest.Config.class
})
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

    @Autowired
    private ObjectMapper mapper;

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
                .andExpect(jsonPath("$.response.stub").value(Matchers.containsString("pair")));
    }

    @Test
    void servesAChoiceExerciseWithItsOptions() throws Exception {
        mockMvc.perform(get("/api/exercises/concept-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.kind").value("choice"))
                .andExpect(jsonPath("$.response.options").value(Matchers.contains("A", "B", "C")))
                .andExpect(jsonPath("$.response.stub").doesNotExist());
    }

    @Test
    void runsAndPassesACorrectCodeSubmission() throws Exception {
        mockMvc.perform(post("/api/exercises/pair-in-any-order/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RunRequest(Fixtures.PAIR_SOLUTION))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PASSED"))
                .andExpect(jsonPath("$.passed").value(3))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void gradesTheConceptExerciseWithNoRunner() throws Exception {
        mockMvc.perform(post("/api/exercises/concept-demo/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RunRequest("B"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PASSED"))
                .andExpect(jsonPath("$.passed").value(1))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void anUnknownExerciseIsNotFound() throws Exception {
        mockMvc.perform(get("/api/exercises/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
