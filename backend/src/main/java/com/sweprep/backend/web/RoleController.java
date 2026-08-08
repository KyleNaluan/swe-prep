package com.sweprep.backend.web;

import com.sweprep.backend.role.RolePreset;
import com.sweprep.backend.role.RoleService;
import com.sweprep.backend.role.RoleStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The role filter the user controls (issue #40): {@code GET /api/role} reports the presets on
 * offer and the current selection, {@code PUT /api/role} picks a preset. Picking a role is all the
 * user does - the preset expands to a family set server-side (a role, not a checklist) - and that
 * choice is what the warm-up and auto-seeding then draw from. Nothing here hides content: every
 * family stays reachable through browse and the optional tiers whatever the active set is.
 */
@RestController
@RequestMapping("/api/role")
public class RoleController {

    private final RoleService roles;

    public RoleController(RoleService roles) {
        this.roles = roles;
    }

    @GetMapping
    public RoleStatus status() {
        return roles.status();
    }

    @PutMapping
    public RoleStatus select(@RequestBody RoleSelectionRequest request) {
        String id = request == null ? null : request.preset();
        if (id == null || id.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A 'preset' is required to choose a role");
        }
        RolePreset preset = RolePreset.byId(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "No such role preset '" + id + "'"));
        return roles.selectPreset(preset);
    }
}
