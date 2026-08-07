-- Day completion: the durable record behind the daily session loop (issue #19).
--
-- Completing the warm-up marks the day complete, and nothing after it (declining the
-- optional main exercise, abandoning a main part-way) can un-complete it. That fact is
-- read on every app open to show the streak, so it is stored as its own tiny record
-- rather than derived by scanning and counting attempt history: one row per completed
-- day, looked up by primary key. Deriving "is today done?" from attempts would couple
-- day-completion to whatever heuristic counts as "enough reps", a rule that will churn
-- as the reps and schedulers evolve; a discrete "finished the warm-up" event does not.
--
-- Person-owned like every other row (issue #14): the warm-up that completes the day is
-- one user's. The (user_id, completed_on) primary key makes marking a day idempotent -
-- a second warm-up, or re-opening the app, is a no-op that never moves completed_at.
CREATE TABLE day_completion (
    user_id      UUID        NOT NULL REFERENCES app_user (id),
    completed_on DATE        NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, completed_on)
);
