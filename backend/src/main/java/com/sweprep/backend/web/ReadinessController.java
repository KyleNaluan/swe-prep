package com.sweprep.backend.web;

import com.sweprep.backend.readiness.ReadinessService;
import com.sweprep.backend.readiness.ReadinessSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The readiness picture (issue #45): {@code GET /api/readiness} reports the objective
 * competence axes, the concepts-covered axis, the per-family breakdown, the separate
 * self-check "explained" count, and (issue #22) the shaky/stale topic lists. This is
 * meant to be the primary progress surface, not a secondary screen tucked behind
 * Practice - the frontend renders it as its own tab and surfaces a summary on the
 * day-complete landing.
 */
@RestController
@RequestMapping("/api/readiness")
public class ReadinessController {

    private final ReadinessService readiness;

    public ReadinessController(ReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping
    public ReadinessSummary summary() {
        return readiness.summary();
    }
}
