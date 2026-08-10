package com.sweprep.backend.web;

import com.sweprep.backend.challenge.ChallengeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The main-exercise-selection endpoint (issue #21): {@code GET /api/challenges/next}
 * hands the editor today's single highest-priority {@code CHALLENGE}, chosen by {@link
 * ChallengeService} rather than picked client-side. The editor still fetches the full
 * {@link ExerciseView} for whatever id this returns and drives the same attempt/submission
 * flow every exercise uses - this seam only decides <em>which</em> exercise, the same
 * relationship {@link RepController} has to the warm-up set.
 */
@RestController
@RequestMapping("/api/challenges")
public class ChallengeController {

    private final ChallengeService challenges;

    public ChallengeController(ChallengeService challenges) {
        this.challenges = challenges;
    }

    @GetMapping("/next")
    public ChallengeSelectionResponse next() {
        return new ChallengeSelectionResponse(
                challenges.selectMain().map(ExerciseSummary::of).orElse(null));
    }
}
