-- Issue #82: the reference-solution reveal policy needs to tell a pre-pass reveal
-- (seen the solution before ever passing this attempt) from a post-pass one (seen it
-- after already passing cleanly). Defaults false so every existing attempt reads as
-- "never seen the solution before passing", the correct historical answer since the
-- feature did not exist before this migration.
ALTER TABLE attempt ADD COLUMN solution_seen boolean NOT NULL DEFAULT false;
