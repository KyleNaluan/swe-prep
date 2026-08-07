package com.sweprep.backend.web;

import com.sweprep.backend.reps.WarmupService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The warm-up endpoint: the daily core (issues #3, #9, #18). {@code GET
 * /api/reps/warmup} hands the editor an ordered, interleaved set of reps to work
 * through - already filtered to the active families and gated so a derived rep only
 * appears once its problem has been attempted. The heavy lifting is in
 * {@link WarmupService}/{@code WarmupSelector}; this seam only exposes it.
 *
 * <p>Each rep is served as a plain {@link ExerciseSummary}; the editor then fetches the
 * full {@link ExerciseView} per rep and drives the same attempt/submission flow every
 * exercise uses, so a rep is never a parallel system (issue #18). Withholding the
 * explanation until a wrong answer or an explicit request stays the attempt layer's job
 * (issue #51), unchanged here.
 */
@RestController
@RequestMapping("/api/reps")
public class RepController {

    private final WarmupService warmup;

    public RepController(WarmupService warmup) {
        this.warmup = warmup;
    }

    @GetMapping("/warmup")
    public List<ExerciseSummary> warmup() {
        return warmup.warmup().stream().map(ExerciseSummary::of).toList();
    }
}
