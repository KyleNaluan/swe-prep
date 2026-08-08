package com.sweprep.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.session.SessionConfig;
import com.sweprep.backend.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves {@link WebConfig} allows a list of origins driven by one property, not a
 * single hardcoded localhost literal (issue #34): both a localhost origin and a
 * tailnet-shaped one are honoured, and an origin outside the configured list is
 * rejected rather than silently ignored.
 */
@WebMvcTest(ExerciseController.class)
@Import({
    JavaLanguageAdapter.class,
    WebConfig.class,
    DeterministicOptionShuffler.class,
    CurrentUser.class,
    SessionConfig.class,
    WebConfigTest.Config.class
})
@TestPropertySource(
        properties = "sweprep.web.allowed-origins=http://localhost:5173,http://100.64.1.2:5173")
class WebConfigTest {

    @TestConfiguration
    static class Config {
        @Bean
        ExerciseCatalog catalog() {
            return Fixtures.catalogOf(Fixtures.pairInAnyOrder());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowsEveryOriginInTheConfiguredList() throws Exception {
        mockMvc.perform(get("/api/exercises").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mockMvc.perform(get("/api/exercises").header("Origin", "http://100.64.1.2:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://100.64.1.2:5173"));
    }

    @Test
    void rejectsAnOriginOutsideTheConfiguredList() throws Exception {
        mockMvc.perform(get("/api/exercises").header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
    }
}
