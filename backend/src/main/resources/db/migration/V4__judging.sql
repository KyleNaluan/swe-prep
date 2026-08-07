-- Judging: hint ladder, failing-case reveal, runtime (issue #16, decided in #5).
--
-- The attempt already records the hint rungs climbed (hints_taken) and whether the
-- failing case was revealed (failing_case_revealed) from V3, so those need no change:
-- this ticket only wires the behaviour that populates them. Two new columns are added
-- for the pieces V3 did not anticipate.

-- The one-line hypothesis the solver types before revealing the failing case (issue
-- #16, from the pedagogy audit): an act of generation before the reveal. It is
-- ungraded and never penalised, and may be NULL when the solver skipped it. Recorded
-- so the schedulers (issue #8) can later read how a sitting went.
ALTER TABLE attempt ADD COLUMN reveal_hypothesis TEXT;

-- How long a submission's run took, in milliseconds. Reported for interest only -
-- correctness is pass or fail and runtime is never part of the verdict (issue #16/#5).
-- 0 for a submission that ran no code (an answer-key exercise).
ALTER TABLE submission ADD COLUMN runtime_millis BIGINT NOT NULL DEFAULT 0;
