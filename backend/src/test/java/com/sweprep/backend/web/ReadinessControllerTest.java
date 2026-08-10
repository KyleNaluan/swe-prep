package com.sweprep.backend.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.readiness.FamilyReadiness;
import com.sweprep.backend.readiness.Progress;
import com.sweprep.backend.readiness.ReadinessService;
import com.sweprep.backend.readiness.ReadinessSummary;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the readiness endpoint over the HTTP wire with a mocked service (issue #45): the
 * shape carries the objective axes, the concepts-covered axis, the separate self-check
 * count, and the per-family breakdown. The service's own derivation is proven in {@code
 * ReadinessServiceTest}.
 */
@WebMvcTest(ReadinessController.class)
class ReadinessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReadinessService readiness;

    @Test
    void reportsTheReadinessPicture() throws Exception {
        when(readiness.summary()).thenReturn(new ReadinessSummary(
                new Progress(4, 10),
                new Progress(2, 5),
                new Progress(1, 3),
                7,
                List.of(new FamilyReadiness(Family.BACKEND, new Progress(2, 4), new Progress(1, 2)))));

        mockMvc.perform(get("/api/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksToCriterion.achieved").value(4))
                .andExpect(jsonPath("$.checksToCriterion.total").value(10))
                .andExpect(jsonPath("$.solvedCold.achieved").value(2))
                .andExpect(jsonPath("$.conceptsCovered.achieved").value(1))
                .andExpect(jsonPath("$.selfCheckExplainedCount").value(7))
                .andExpect(jsonPath("$.families[0].family").value("BACKEND"))
                .andExpect(jsonPath("$.families[0].checksToCriterion.achieved").value(2));
    }
}
