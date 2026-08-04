# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Add durable project-specific notes here as they are discovered through real work.

## Stack

Spring Boot 3.4.x on Java 21, Postgres, React + Vite, Monaco editor, run locally and reached over a tailnet.
Java 21 and a running Postgres instance are required for local development.

## Design decisions

The planning map at [issue #1](https://github.com/KyleNaluan/swe-prep/issues/1) is the authoritative source for design decisions.
Nine decisions are recorded there as closed tickets.
Do not restate its contents here; point to it instead.

Two invariants from the map are the ones most likely to be violated by someone who has not read it:

- A `Grader` decides pass/fail; a `Runner` only executes.
  Exercises that run no code need no `Runner` at all.
- Test cases are language-neutral JSON data with a per-language harness, never hand-written per language.

`CLAUDE.md` is a symlink to this file.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
