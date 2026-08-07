package com.sweprep.backend.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.attempt.Attempt;
import com.sweprep.backend.attempt.AttemptNotFoundException;
import com.sweprep.backend.attempt.AttemptOutcome;
import com.sweprep.backend.attempt.AttemptService;
import com.sweprep.backend.attempt.AttemptWithCount;
import com.sweprep.backend.attempt.IllegalAttemptStateException;
import com.sweprep.backend.attempt.Submission;
import com.sweprep.backend.grader.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the attempt lifecycle over the HTTP wire with a mocked service: a sitting is
 * opened, a submission returns its verdict, history is listed, and the two failure
 * shapes map to the right status (unknown attempt → 404, an already-ended attempt →
 * 409). The service's own behaviour is proven against a real database in
 * {@code AttemptPersistenceTest}.
 */
@WebMvcTest(AttemptController.class)
class AttemptControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private AttemptService service;

    private static Attempt attempt(UUID id, AttemptOutcome outcome) {
        return new Attempt(
                id,
                UUID.randomUUID(),
                "two-sum",
                "Two Sum",
                "algorithms",
                "CHALLENGE",
                outcome,
                Instant.parse("2026-08-06T10:00:00Z"),
                outcome == AttemptOutcome.IN_PROGRESS ? null : Instant.parse("2026-08-06T10:05:00Z"),
                0,
                false,
                null,
                null,
                null);
    }

    @Test
    void startsAnAttempt() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.start("two-sum")).thenReturn(attempt(id, AttemptOutcome.IN_PROGRESS));

        mockMvc.perform(post("/api/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new StartAttemptRequest("two-sum"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.exerciseId").value("two-sum"))
                .andExpect(jsonPath("$.outcome").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.submissionCount").value(0));
    }

    @Test
    void submittingReturnsTheVerdict() throws Exception {
        UUID id = UUID.randomUUID();
        Submission submission = new Submission(
                UUID.randomUUID(), id, Instant.now(), "class Solution {}",
                Verdict.Outcome.FAILED, 3, 4, "");
        when(service.submit(eq(id), any())).thenReturn(submission);

        mockMvc.perform(post("/api/attempts/" + id + "/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RunRequest("class Solution {}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.passed").value(3))
                .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void listsHistoryWithSubmissionCounts() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.history())
                .thenReturn(List.of(new AttemptWithCount(attempt(id, AttemptOutcome.SOLVED), 2)));

        mockMvc.perform(get("/api/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].outcome").value("SOLVED"))
                .andExpect(jsonPath("$[0].submissionCount").value(2));
    }

    @Test
    void abandoningAnUnknownAttemptIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.abandon(id)).thenThrow(new AttemptNotFoundException("No attempt with id " + id));

        mockMvc.perform(post("/api/attempts/" + id + "/abandon"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("No attempt")));
    }

    @Test
    void submittingToAnEndedAttemptConflicts() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.submit(eq(id), any()))
                .thenThrow(new IllegalAttemptStateException("Attempt " + id + " has already ended (SOLVED)"));

        mockMvc.perform(post("/api/attempts/" + id + "/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RunRequest("x"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already ended")));
    }
}
