package com.sweprep.backend.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sweprep.backend.role.RolePreset;
import com.sweprep.backend.role.RoleService;
import com.sweprep.backend.role.RoleStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The role filter over the HTTP wire (issue #40): the status lists the presets on offer and the
 * current selection, and picking a role sends a preset id (never a checklist of families). An
 * unknown or missing preset is a 400. The role→families expansion is proven in {@code
 * RolePresetTest}; here the service is mocked so this is only about the seam and the shape.
 */
@WebMvcTest(RoleController.class)
class RoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleService roles;

    @Test
    void reportsThePresetsAndCurrentSelection() throws Exception {
        when(roles.status())
                .thenReturn(new RoleStatus(
                        List.of(new RoleStatus.Preset(
                                "full-stack-ai-ml", "Full-stack + AI/ML",
                                List.of("AIML", "BACKEND", "FRONTEND"))),
                        List.of("BACKEND", "FRONTEND"),
                        "full-stack",
                        true));

        mockMvc.perform(get("/api/role"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPreset").value("full-stack"))
                .andExpect(jsonPath("$.chosen").value(true))
                .andExpect(jsonPath("$.presets[0].id").value("full-stack-ai-ml"))
                .andExpect(jsonPath("$.activeFamilies").isArray());
    }

    @Test
    void selectingAPresetAppliesItAndReturnsTheNewStatus() throws Exception {
        when(roles.selectPreset(any()))
                .thenReturn(new RoleStatus(List.of(), List.of("AIML", "BACKEND", "FRONTEND"),
                        "full-stack-ai-ml", true));

        mockMvc.perform(put("/api/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"full-stack-ai-ml\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPreset").value("full-stack-ai-ml"));

        verify(roles).selectPreset(RolePreset.FULL_STACK_AI_ML);
    }

    @Test
    void anUnknownPresetIsRejected() throws Exception {
        mockMvc.perform(put("/api/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"nope\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMissingPresetIsRejected() throws Exception {
        mockMvc.perform(put("/api/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
