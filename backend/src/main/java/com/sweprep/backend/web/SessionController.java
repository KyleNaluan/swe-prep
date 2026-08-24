package com.sweprep.backend.web;

import com.sweprep.backend.session.DayHistory;
import com.sweprep.backend.session.SessionService;
import com.sweprep.backend.session.SessionStatus;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The daily session loop's endpoints (issue #19). {@code GET /api/session} reads today's
 * status (day complete? streak?) and is called on app open - it must never gate the
 * first rep, so the app renders the warm-up immediately and reads this in the background.
 * {@code POST /api/session/complete-warmup} marks the day complete when the warm-up is
 * finished; it is idempotent, so the app can call it once the set is done without
 * tracking whether it already has.
 *
 * <p>Completing the warm-up is the whole required core: the optional main exercise and
 * the open continuation live on the existing exercise/attempt endpoints and never touch
 * completion - declining the main, or abandoning one part-way (recorded as abandonment
 * through {@code POST /api/attempts/{id}/abandon}, issue #15), leaves the day complete.
 *
 * <p>{@code status} also reports the capped repair mechanic (issue #22): a missed day
 * can be repaired by a double session (the warm-up plus a solved challenge) the next
 * day. Nothing new to trigger it - it falls out of the existing warm-up completion and
 * challenge-solving paths, so there is no separate "repair" endpoint.
 *
 * <p>{@code GET /api/session/history} serves the Direction C day ribbon and the
 * Direction A year-record grid (issue #90) from the same projection of {@code
 * day_completion} - the client slices the trailing 30 days for the ribbon and renders
 * the whole response for the grid.
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionService session;

    public SessionController(SessionService session) {
        this.session = session;
    }

    @GetMapping
    public SessionStatus status() {
        return session.status();
    }

    @PostMapping("/complete-warmup")
    public SessionStatus completeWarmup() {
        return session.completeWarmup();
    }

    @GetMapping("/history")
    public List<DayHistory> history() {
        return session.history();
    }
}
