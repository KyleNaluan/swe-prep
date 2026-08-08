package com.sweprep.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.grader.GraderRegistry;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.session.SessionConfig;
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
 * Proves the other half of the first acceptance criterion: when content cannot be
 * loaded (missing or malformed path), the app reports a clear error to the editor -
 * a 500 whose body carries the plain-language cause, not a bare status.
 */
@WebMvcTest(ExerciseController.class)
@Import({
    JavaLanguageAdapter.class,
    GraderRegistry.class,
    DeterministicOptionShuffler.class,
    CurrentUser.class,
    SessionConfig.class,
    ContentErrorHandlerTest.Config.class
})
class ContentErrorHandlerTest {

    static final String MESSAGE =
            "Content directory not found: /nope. Clone the private swe-prep-content repo there.";

    @TestConfiguration
    static class Config {
        @Bean
        ExerciseCatalog catalog() {
            return Fixtures.failingCatalog(MESSAGE);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aContentFailureBecomesA500WithAReadableMessage() throws Exception {
        mockMvc.perform(get("/api/exercises"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value(Matchers.containsString("swe-prep-content")));
    }
}
