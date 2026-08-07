-- The single application user (issue #14).
--
-- swe-prep is single-user forever (the destination on the planning map, issue #1),
-- but every row that belongs to a person still carries a user_id from the very
-- first table onward. That is not multi-user tax: it is the one cheap decision
-- that keeps a small shared instance a swap rather than a rewrite (issue #1's
-- out-of-scope note), and it is paid here by seeding exactly one user that later
-- person-owned tables (attempts, in issue #15) will reference.
--
-- This table IS the person table, so its own primary key is the user_id every
-- later person-owned table foreign-keys to. Exactly one row is seeded, with a
-- fixed id so those references are stable across environments.

CREATE TABLE app_user (
    id         UUID PRIMARY KEY,
    username   TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO app_user (id, username)
VALUES ('00000000-0000-0000-0000-000000000001', 'captain');
