package com.sweprep.backend.web;

/**
 * The body of {@code PUT /api/role}: the id of the {@link com.sweprep.backend.role.RolePreset} the
 * user picked (issue #40). The user picks a role, so the request carries a preset id, never a list
 * of families - the server owns the role→families expansion.
 *
 * @param preset the preset id, e.g. {@code "full-stack-ai-ml"}
 */
public record RoleSelectionRequest(String preset) {}
