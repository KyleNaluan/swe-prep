package com.sweprep.backend.web;

import com.sweprep.backend.attempt.AttemptService;
import com.sweprep.backend.attempt.Submission;
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
 * {@code POST .../submissions} that grades and records a submission; giving up is a
 * {@code POST .../abandon}; and the whole history is read back from
 * {@code GET /api/attempts}. Grading and its verdict shape are unchanged - a
 * submission returns the same {@link RunResponse} the stateless run used to - but the
 * attempt and every submission now survive a restart and are queryable per user.
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
        Submission submission = attempts.submit(id, request.submission());
        return RunResponse.of(submission);
    }

    @PostMapping("/{id}/abandon")
    public AttemptView abandon(@PathVariable UUID id) {
        return withCount(attempts.abandon(id).id());
    }

    @PostMapping("/{id}/reveal")
    public AttemptView reveal(@PathVariable UUID id) {
        return withCount(attempts.recordFailingCaseReveal(id).id());
    }

    // Return the attempt with its live submission count after a state change, so the
    // editor can update its history row without a second round trip.
    private AttemptView withCount(UUID attemptId) {
        return attempts.history().stream()
                .filter(a -> a.attempt().id().equals(attemptId))
                .findFirst()
                .map(AttemptView::of)
                .orElseThrow();
    }
}
