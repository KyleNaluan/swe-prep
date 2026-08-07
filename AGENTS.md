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

The seam that runs a submission lives under `backend/src/main/java/com/sweprep/backend/` in five packages: `exercise` (language-neutral model), `content` (loading), `language` (adapters), `runner` (execution) and `grader` (pass/fail), wired to the editor by `web`.
Issue #13 established the runner/adapter shape; issue #14 made the domain model real and turned on content loading.

An `Exercise` (issue #14) carries prompt, `domain`, `topics`, `Difficulty`, a `Form` (`REP` vs `CHALLENGE`, an attribute never a subtype), a `Response` spec (how it is answered) and a `Grading` spec (how it is judged). `Response` and `Grading` are independent sealed interfaces: `Response.Code` carries a `Signature`, `Response.Choice` a list of options; `Grading.TestCases` carries cases + a `Comparison`, `Grading.AnswerKey` a fixed expected value. A coding problem is Code+TestCases; a concept question is Choice+AnswerKey - but a code response judged by an answer key (predict-output) is valid, which is why the two are not one choice. `AnswerKeyGrader` compares the submitted answer against the expected value under the exercise's `Comparison`, trying both the raw text and, when the submission is valid JSON, its parsed form, so string, numeric, and structured answer keys all grade correctly and a multiple-choice option that looks like JSON (`true`, `1`) is never mis-graded against a string answer key.

Contracts, kept deliberately apart (see the map, issue #6):

- `LanguageAdapter` generates both the editor stub and the harness from a `Signature`; neither is hand-written per problem.
- `Runner` only compiles and executes (`ExecutionRequest` to `ExecutionResult`) and knows nothing about test cases or verdicts.
- `Grader` is polymorphic over the grading spec: each declares `supports(Exercise)` and `GraderRegistry` routes each exercise to the one that handles it. `TestCaseGrader` runs code; `AnswerKeyGrader` compares a submitted answer to a fixed value and has **no `Runner` dependency at all** - the demonstration (issue #14) that grader and runner are separate pieces. `COMPILE_ERROR`, `TIMEOUT`, a test failure and an `ERROR` (ran but reported no result) are distinct outcomes.

Comparison is the grader's job, not the harness's (issue #31). The harness only records each case's raw return value; the grader compares it to the expected value under the exercise's declared rule.

- A grading spec declares a `Comparison` (`exercise` package): a sealed interface with `exact`, `orderInsensitiveSequence` and `setEquality`, defaulting to `exact`. A problem-specific rule is added by adding one more permitted implementation, not by redesigning - so extend it there rather than reaching for per-content workarounds. Two Sum declares `orderInsensitiveSequence` instead of pinning an order in its statement; 3Sum declares `setEquality` (with each triplet still stated ascending, so its elements compare exactly).
- All rules share `JsonEquality.equal`, which compares numbers by magnitude (`decimalValue().compareTo`), so `5`, `5.0` and a wider int type are one answer. Never compare answers with Jackson's `JsonNode.equals` - it keys on the concrete node type and fails numerically-equal answers.

Sharp edges worth knowing before touching this:

- The harness classpath is resolved from the `CodeSource` of the Jackson classes it imports, not from `java.class.path`, because Surefire may hand the JVM a booter jar instead of the real dependency jars. See `JavaLanguageAdapter.jacksonClasspath`.
- The harness writes each case's result to a dedicated file (an `ExecutionRequest.outputFiles` entry the runner reads back), never to the submission's own stdout. This is deliberate: stdout is capped at 1MB (`LocalJavaRunner.MAX_CAPTURED_BYTES`, keep it) to stop a runaway print loop exhausting the backend heap, and putting the result on that same channel let a noisy-but-correct solution truncate away its own result.
- The execution timeout is `sweprep.grader.timeout` (default `PT10S`); the local runner is unsandboxed by design (single user, issue #2's swap point).

## Content: private, loaded from a local path

Problem content lives only in the separate private repo `swe-prep-content`, never here (public-engine/private-content, issues #4/#14).
`FileExerciseCatalog` (`content` package) reads every top-level `*.json` in `sweprep.content.path` (default `../content`, gitignored `/content/`; override with `SWEPREP_CONTENT_PATH`), parsing each with the hand-rolled `ExerciseParser` so every failure names the file and field. See `swe-prep-content/README.md` for the on-disk format.

- Loading is lazy and cached; a *failed* load is not cached, so cloning content after boot works without a restart. Missing path, malformed file, duplicate id, etc. all surface as `ContentException`, mapped to a 500-with-message by `ContentErrorHandler` so the editor shows a clear error.
- **Never commit problem content here.** `scripts/check-no-content.sh` fails CI and `./test.sh` if any is (tracked `content/`, un-ignored `content/`, or any tracked JSON carrying both `"statement"` and `"grading"`). Backend tests use synthetic fixtures (`testsupport/Fixtures`, temp-dir JSON); the real seeded set is only exercised by `RealContentSmokeTest`, which *skips* when no local clone is present.
- Person-owned tables carry a `user_id` and exactly one user is seeded (migration `V2__app_user.sql`); `app_user` is the person table its own PK is that id. Persisting attempts is #15, not this ticket.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
