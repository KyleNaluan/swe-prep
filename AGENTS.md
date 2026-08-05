# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Add durable project-specific notes here as they are discovered through real work.

## Stack

Spring Boot 3.4.x on Java 21, Postgres, React + Vite, Monaco editor, run locally and reached over a tailnet.
Java 21 on `PATH` and Docker are required for local development; see `README.md` for the run and test commands.

## Layout

One repo, two toolchains, each left to own its usual root rather than nested under a shared build tool:

- `backend/` - Spring Boot, Maven (`./mvnw`). Own package tree starts at `com.sweprep.backend`.
- `frontend/` - React + Vite, npm.
- `docker-compose.yml` at the repo root - Postgres for local dev, disposable and reproducible. Maps to host port 5433, not 5432, so it doesn't collide with any other local Postgres already running on the machine.
- `test.sh` at the repo root - the one documented command that runs both suites.

Schema migrations use Flyway (`backend/src/main/resources/db/migration/`), never Hibernate `ddl-auto`, starting from the very first migration. This is a hard invariant, not a style preference - see issue #12.

Backend tests spin up a real, disposable Postgres via Testcontainers rather than mocking the datasource. The `api.version` system property pinned in `backend/pom.xml`'s surefire config is a required workaround, not stray config: Testcontainers' Docker connectivity probe hardcodes an old API version that current Docker Engine releases reject outright, so without it the probe fails before ever checking what the daemon supports. Don't remove it.

CI (`.github/workflows/ci.yml`) runs `./test.sh` on every push and pull request, on GitHub-hosted `ubuntu-latest` runners with Docker preinstalled - so the backend's Testcontainers suite genuinely runs there, not just locally.

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
