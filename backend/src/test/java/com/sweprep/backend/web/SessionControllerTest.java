package com.sweprep.backend.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sweprep.backend.session.SessionService;
import com.sweprep.backend.session.SessionStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the session endpoints over the HTTP wire with a mocked service (issue #19):
 * status reads the day/streak, and completing the warm-up returns the freshly completed
 * status. The service's own behaviour is proven against a real database in
 * {@code SessionPersistenceTest}.
 */
@WebMvcTest(SessionController.class)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionService service;

    @Test
    void statusReportsAnIncompleteDay() throws Exception {
        when(service.status()).thenReturn(new SessionStatus(false, null, 4));

        mockMvc.perform(get("/api/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayComplete").value(false))
                .andExpect(jsonPath("$.completedAt").doesNotExist())
                .andExpect(jsonPath("$.streak").value(4));
    }

    @Test
    void completingTheWarmupReturnsTheCompletedStatus() throws Exception {
        when(service.completeWarmup())
                .thenReturn(new SessionStatus(true, Instant.parse("2026-08-07T09:00:00Z"), 5));

        mockMvc.perform(post("/api/session/complete-warmup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayComplete").value(true))
                .andExpect(jsonPath("$.completedAt").value("2026-08-07T09:00:00Z"))
                .andExpect(jsonPath("$.streak").value(5));
    }
}
