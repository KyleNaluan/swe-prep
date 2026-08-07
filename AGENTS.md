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

Backend tests spin up a real, disposable Postgres via Testcontainers rather than mocking the datasource. The `api.version` system property pinned in `backend/pom.xml`'s surefire config is a workaround for the dev machine, not stray config: Testcontainers' Docker connectivity probe hardcodes an old API version that this machine's Docker Engine (new enough to enforce a stricter MinAPIVersion) rejects outright, so without it the probe fails before ever checking what the daemon supports. Don't remove it. It's confirmed *unnecessary* on GitHub Actions' hosted `ubuntu-latest` runners (Docker Engine 28.0.4 there accepts the hardcoded probe version fine) - keep the pin anyway, since it's harmless where it isn't needed and required on this box.

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

## Execution: exercise to verdict

The seam that runs a submission lives under `backend/src/main/java/com/sweprep/backend/` in four packages: `exercise` (language-neutral model), `language` (adapters), `runner` (execution) and `grader` (pass/fail), wired to the editor by `web`.
The first concrete instances are `JavaLanguageAdapter`, `LocalJavaRunner` and `TestCaseGrader`; issue #13 established their shape.

Contracts, kept deliberately apart (see the map, issue #6):

- `LanguageAdapter` generates both the editor stub and the harness from a `Signature`; neither is hand-written per problem.
- `Runner` only compiles and executes (`ExecutionRequest` to `ExecutionResult`) and knows nothing about test cases or verdicts.
- `Grader` writes the cases, invokes the runner, and interprets the outcome into a `Verdict`. `COMPILE_ERROR`, `TIMEOUT`, a test failure and an `ERROR` (ran but reported no result) are distinct outcomes.

Comparison is the grader's job, not the harness's (issue #31). The harness only records each case's raw return value; the grader compares it to the expected value under the exercise's declared rule.

- An exercise declares a `Comparison` (`exercise` package): a sealed interface with `exact`, `orderInsensitiveSequence` and `setEquality`, defaulting to `exact`. A problem-specific rule is added by adding one more permitted implementation, not by redesigning - so extend it there rather than reaching for per-content workarounds. Two Sum declares `orderInsensitiveSequence` instead of pinning an order in its statement.
- All rules share `JsonEquality.equal`, which compares numbers by magnitude (`decimalValue().compareTo`), so `5`, `5.0` and a wider int type are one answer. Never compare answers with Jackson's `JsonNode.equals` - it keys on the concrete node type and fails numerically-equal answers.

Sharp edges worth knowing before touching this:

- The harness classpath is resolved from the `CodeSource` of the Jackson classes it imports, not from `java.class.path`, because Surefire may hand the JVM a booter jar instead of the real dependency jars. See `JavaLanguageAdapter.jacksonClasspath`.
- The harness writes each case's result to a dedicated file (an `ExecutionRequest.outputFiles` entry the runner reads back), never to the submission's own stdout. This is deliberate: stdout is capped at 1MB (`LocalJavaRunner.MAX_CAPTURED_BYTES`, keep it) to stop a runaway print loop exhausting the backend heap, and putting the result on that same channel let a noisy-but-correct solution truncate away its own result.
- The execution timeout is `sweprep.grader.timeout` (default `PT10S`); the local runner is unsandboxed by design (single user, issue #2's swap point).

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
