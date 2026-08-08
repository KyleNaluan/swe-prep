-- The active role families: the user's own answer to "what am I preparing for?" (issue #40).
--
-- The family tag on content (design revision t3, section 2) only becomes a product feature
-- once the user can say which families the daily habit should train. That choice has to be
-- durable - read on every warm-up build - so it lives here rather than in config, which is a
-- single global default, not a per-user setting.
--
-- One row per active family, keyed by (user_id, family): the set the warm-up selector and the
-- SRS auto-seeding draw from, on top of the always-on CORE and PROFESSIONAL (which are never
-- stored - they are active for everyone regardless of this table). The user picks a named role
-- preset in the UI (issue #40: a role, not a checklist); the preset expands to a family set and
-- that expansion is what is stored, so the stored shape is the exact set the selector needs and
-- future per-family control needs no new migration. No rows for a user means "not chosen yet",
-- which the service reads as no restriction (every family eligible), matching the pre-#40
-- behaviour so nothing is suppressed before the user has chosen.
--
-- Person-owned like every other row (issue #14). family is free text, not a DB CHECK, for the
-- same reason attempt.outcome is (issue #15): a new family is added in the Java enum alone, with
-- no migration. Deactivating a family is deleting its row here; the review-debt-first rule that
-- already-due reviews survive deactivation lives in the selector, not in this table.
CREATE TABLE active_family (
    user_id UUID NOT NULL REFERENCES app_user (id),
    family  TEXT NOT NULL,
    PRIMARY KEY (user_id, family)
);
