package com.sweprep.backend.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sweprep.backend.reps.WarmupService;
import com.sweprep.backend.testsupport.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the warm-up endpoint over the HTTP wire: it serves the ordered set the
 * {@link WarmupService} builds, each rep as a lightweight summary the editor then
 * fetches in full. The selection rules themselves (interleave, families, gating) are
 * proven directly in {@code WarmupSelectorTest}; here the service is mocked so this
 * test is only about the seam and the shape.
 */
@WebMvcTest(RepController.class)
class RepControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WarmupService warmup;

    @Test
    void servesTheWarmupSetAsSummariesInOrder() throws Exception {
        when(warmup.warmup())
                .thenReturn(List.of(Fixtures.patternIdRep(), Fixtures.complexityRep()));

        mockMvc.perform(get("/api/reps/warmup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("rep-pattern-id"))
                .andExpect(jsonPath("$[0].form").value("REP"))
                .andExpect(jsonPath("$[1].id").value("rep-complexity"))
                // A summary is lightweight: no statement or response spec travels here.
                .andExpect(jsonPath("$[0].statement").doesNotExist())
                .andExpect(jsonPath("$[0].response").doesNotExist());
    }

    @Test
    void anEmptySetIsServedAsAnEmptyArray() throws Exception {
        when(warmup.warmup()).thenReturn(List.of());

        mockMvc.perform(get("/api/reps/warmup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
