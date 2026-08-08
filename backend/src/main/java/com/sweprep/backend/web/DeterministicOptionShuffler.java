package com.sweprep.backend.web;

import com.sweprep.backend.attempt.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.ToLongFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The production {@link OptionShuffler} (issue #59): a permutation seeded deterministically
 * from the check's id, the current user, and the current calendar day.
 *
 * <p>The day is in the seed on purpose. These checks are consumed mainly through spaced
 * repetition, so a learner meets the same check many times; keying the order on the id
 * <em>alone</em> would fix the correct answer in one slot forever, and the learner would
 * learn "this one is slot 3" instead of the material - the very learnable-position failure
 * #59 exists to kill, just on a slower clock. Folding in the calendar day rotates the order
 * <b>between sittings</b> (review intervals are a day or more, so consecutive exposures get
 * different orders) while keeping it stable <em>within</em> a day: a refresh, a resume, or a
 * re-queued rep on the same day all see the identical order, so it never flips under the
 * learner mid-question. Nothing is persisted - the day comes from the injected {@link Clock},
 * the same {@code SessionConfig} clock the session loop reads "today" from (issue #19), and
 * the user from {@link CurrentUser}, so no attempt state is invented (the options render
 * before an attempt row exists). The user id also stops two learners sharing a position map.
 *
 * <p>It still defeats the tell it targets: file order put the key first in all 12 checks of
 * the batch, whereas this scatters the key across slots. It does not defeat "always pick the
 * longest" - that is the option <em>set</em>'s shape and is the separate quality check of
 * issue #60.
 *
 * <p>The seed function over the composite key is injectable so a test can force a known
 * permutation; the default derives it from {@link String#hashCode()}, which is contractually
 * stable across JVMs, so the shipped path is itself deterministic and directly assertable
 * once the clock and user are pinned.
 */
@Component
public class DeterministicOptionShuffler implements OptionShuffler {

    private final Clock clock;
    private final CurrentUser currentUser;
    private final ToLongFunction<String> seedFor;

    @Autowired
    public DeterministicOptionShuffler(Clock clock, CurrentUser currentUser) {
        this(clock, currentUser, key -> key.hashCode());
    }

    /** For tests: supply the seed for each composite key to force a known ordering. */
    DeterministicOptionShuffler(Clock clock, CurrentUser currentUser, ToLongFunction<String> seedFor) {
        this.clock = clock;
        this.currentUser = currentUser;
        this.seedFor = seedFor;
    }

    @Override
    public List<String> order(String key, List<String> options) {
        String composite = key + "|" + currentUser.id() + "|" + LocalDate.now(clock);
        List<String> shuffled = new ArrayList<>(options);
        Collections.shuffle(shuffled, new Random(seedFor.applyAsLong(composite)));
        return List.copyOf(shuffled);
    }
}
