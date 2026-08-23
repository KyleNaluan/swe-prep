package com.sweprep.backend.web;

import com.sweprep.backend.attempt.AttemptService;
import com.sweprep.backend.attempt.ComplexityClaim;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The editor's endpoints for durable practice history (issue #15).
 *
 * <p>A sitting is opened with {@code POST /api/attempts}; each press of Run is a
 * {@code POST .../submissions} that grades and records a submission; taking a hint is
 * a {@code POST .../hints}; revealing the failing case is a {@code POST .../reveal};
 * requesting the check's explanation is a {@code POST .../explanation}; giving up is a
 * {@code POST .../abandon}; and the whole history is read back from {@code GET
 * /api/attempts}.
 *
 * <p>Judging withholds by default (issues #16/#5): a submission's {@link RunResponse}
 * carries only the passing count, never a failing case's values. The hint ladder and
 * the failing-case reveal are the always-available, always-recorded help - both record
 * their use on the attempt and neither reduces a score or ends the sitting. The check's
 * explanation (issue #51) is the one thing disclosed automatically, on a wrong answer;
 * when correct it is one keystroke away via {@code .../explanation}, which records the
 * request as its own signal, distinct from taking a hint, and never penalises.
 */
@RestController
@RequestMapping("/api/attempts")
public class AttemptController {

    private final AttemptService attempts;

    public AttemptController(AttemptService attempts) {
        this.attempts = attempts;
    }

    @GetMapping
    public List<AttemptView> history() {
        return attempts.history().stream().map(AttemptView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttemptView start(@RequestBody StartAttemptRequest request) {
        return AttemptView.of(attempts.start(request.exerciseId()), 0);
    }

    @PostMapping("/{id}/submissions")
    public RunResponse submit(@PathVariable UUID id, @RequestBody RunRequest request) {
        return RunResponse.of(attempts.submit(id, request.submission(), request.language()));
    }

    @PostMapping("/{id}/hints")
    public HintResponse takeHint(@PathVariable UUID id) {
        return HintResponse.of(attempts.takeHint(id));
    }

    @PostMapping("/{id}/explanation")
    public ExplanationResponse explanation(@PathVariable UUID id) {
        return ExplanationResponse.of(attempts.requestExplanation(id));
    }

    /**
     * Records the solver's complexity self-report and, in the same response, reveals
     * the authored target and the empirical measurement result (issue #17). The target
     * is never available from any earlier call - see {@link ExerciseView#hasComplexityCheck}
     * - so it cannot already be in the client's hands when the claim prompt renders.
     */
    @PostMapping("/{id}/complexity")
    public ComplexityResponse claimComplexity(
            @PathVariable UUID id, @RequestBody ComplexityClaimRequest request) {
        return ComplexityResponse.of(
                attempts.claimComplexity(id, new ComplexityClaim(request.time(), request.space())),
                attempts.modelOpinionAvailable());
    }

    /**
     * Asks a language model for an independent reading of a solved attempt's time
     * complexity (issue #83), compared against the claim and the empirical
     * measurement already revealed by {@code .../complexity}. Advisory only, on
     * request, never persisted - see {@link com.sweprep.backend.attempt.AttemptService#secondOpinion}.
     */
    @PostMapping("/{id}/complexity/model-opinion")
    public ModelOpinionResponse modelOpinion(@PathVariable UUID id) {
        return ModelOpinionResponse.of(attempts.secondOpinion(id));
    }

    @PostMapping("/{id}/abandon")
    public AttemptView abandon(@PathVariable UUID id) {
        return AttemptView.of(attempts.abandon(id));
    }

    @PostMapping("/{id}/reveal")
    public RevealResponse reveal(@PathVariable UUID id, @RequestBody RevealRequest request) {
        return RevealResponse.of(attempts.revealFailingCase(
                id, request.submission(), request.hypothesis(), request.language()));
    }

    /**
     * Discloses the exercise's reference solution (issue #82): available on request at
     * any time, recorded, and never penalised. A reveal before this attempt has ever
     * passed marks it solution-seen (a low spacing quality and exclusion from "solved
     * cold" until a later clean pass); a reveal after it is already solved is
     * unrestricted and unrecorded.
     */
    @PostMapping("/{id}/solution")
    public ReferenceSolutionResponse revealSolution(@PathVariable UUID id) {
        return ReferenceSolutionResponse.of(attempts.revealSolution(id));
    }

    /**
     * Commits a self-check explanation and reveals the model answer for self-comparison
     * (issue #41). Nothing is machine-graded; the produced text is frozen as a submission
     * before the answer is handed back.
     */
    @PostMapping("/{id}/self-check/reveal")
    public SelfCheckRevealResponse revealSelfCheck(
            @PathVariable UUID id, @RequestBody SelfCheckRevealRequest request) {
        return SelfCheckRevealResponse.of(attempts.revealSelfCheck(id, request.produced()));
    }

    /**
     * Records the learner's self-rating of a revealed self-check answer, ending the sitting
     * as {@code EXPLAINED} (issue #41). The rating is a generation signal, never the
     * objective competence number.
     */
    @PostMapping("/{id}/self-check/rating")
    public SelfCheckRatingResponse rateSelfCheck(
            @PathVariable UUID id, @RequestBody SelfCheckRatingRequest request) {
        return SelfCheckRatingResponse.of(
                attempts.rateSelfCheck(id, request.submission(), request.rating()));
    }
}
