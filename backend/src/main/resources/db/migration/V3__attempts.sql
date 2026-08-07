-- Persist practice history: attempts and submissions (issue #15).
--
-- This is the durable record the schedulers (issue #8) read later: a due-date SRS
-- queue for reps and a priority score for challenges, both collapsing each attempt
-- to a 0-5 quality score. Thin records here would quietly cripple those tickets, so
-- this schema deliberately over-captures: it holds every field the judging decision
-- (issue #5) says is recorded, including fields whose producing feature is not built
-- yet, so those tickets set a column rather than run a migration.
--
-- Two person-owned tables, so both carry the user through app_user (issue #14's
-- single-seeded-user invariant); the app is single-user forever but every
-- person-owned row still carries a user_id.

-- An Attempt is one sitting with an exercise - a rep or a challenge alike. It is
-- created the moment practice starts, so an abandoned attempt is a real recorded
-- outcome (ABANDONED) rather than indistinguishable from one never started (no row).
--
-- exercise_id references content that lives only in the private content repo (issue
-- #4/#14), never in this database, so it is a plain text id, not a foreign key. The
-- title, domain and form are snapshotted at creation so history stays readable and
-- the scheduler can route by form even if the content set later changes or a clone
-- is absent.
CREATE TABLE attempt (
    id                        UUID PRIMARY KEY,
    user_id                   UUID        NOT NULL REFERENCES app_user (id),
    exercise_id               TEXT        NOT NULL,
    exercise_title            TEXT        NOT NULL,
    domain                    TEXT        NOT NULL,
    form                      TEXT        NOT NULL,
    -- IN_PROGRESS until the sitting ends; SOLVED when a submission passes; ABANDONED
    -- when the solver gives up. The outcome the scheduler collapses to a quality score.
    outcome                   TEXT        NOT NULL DEFAULT 'IN_PROGRESS',
    started_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Set when the attempt reaches a terminal outcome; NULL while in progress.
    ended_at                  TIMESTAMPTZ,
    -- Hint-ladder rungs climbed (issue #5). The ladder itself is issue #16, not built
    -- yet, so this stays 0 until that ticket populates it - no migration needed then.
    hints_taken               INTEGER     NOT NULL DEFAULT 0,
    -- Whether the "show me the failing case" reveal was used (issue #5). Recorded,
    -- never penalised.
    failing_case_revealed     BOOLEAN     NOT NULL DEFAULT FALSE,
    -- The solver's self-reported complexity claim and what empirical measurement said,
    -- plus whether they agreed (issue #5). Measurement is issue #17, not built yet, so
    -- these stay NULL until that ticket populates them - again with no migration.
    complexity_claim          TEXT,
    measured_complexity       TEXT,
    complexity_claim_correct  BOOLEAN
);

-- Per-user history is the core query, newest first.
CREATE INDEX idx_attempt_user_started ON attempt (user_id, started_at DESC);

-- A Submission is one press of Run within an attempt: the response the solver sent
-- and the verdict it earned. Every submission is kept, not just the final one, so the
-- scheduler can see how many tries a sitting took.
CREATE TABLE submission (
    id           UUID PRIMARY KEY,
    attempt_id   UUID        NOT NULL REFERENCES attempt (id) ON DELETE CASCADE,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The full source the solver wrote, or the option they picked - the neutral
    -- response field, matching how it arrives on the wire.
    response     TEXT        NOT NULL,
    -- One of the grader's Verdict.Outcome names: PASSED, FAILED, COMPILE_ERROR,
    -- TIMEOUT, ERROR. These are distinct outcomes, not a single pass/fail bit.
    outcome      TEXT        NOT NULL,
    passed       INTEGER     NOT NULL,
    total        INTEGER     NOT NULL,
    detail       TEXT        NOT NULL DEFAULT ''
);

CREATE INDEX idx_submission_attempt ON submission (attempt_id, submitted_at);
