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

## Local dev topology: port, proxy, CORS (issue #34)

The frontend dev server (`frontend/vite.config.ts`) is pinned with `strictPort` (fails loudly if `:5173` is taken, rather than silently moving to `:5174` and leaving every API call refused with nothing explaining why) and bound to every interface with `host: true`, so it's reachable from another device over the tailnet at `http://<tailnet-host>:5173` exactly like `localhost:5173`. It also proxies `/api` and `/actuator` to the backend, so the browser's calls are same-origin regardless of which of those addresses loaded the page - `App.tsx`'s `API_BASE_URL` defaults to `''` for this reason, not a backend URL. CORS is therefore not load-bearing for the app's own calls in dev; it still matters for anything that talks to the backend directly. That config is `sweprep.web.allowed-origins` (`backend/src/main/java/com/sweprep/backend/web/WebConfig.java`), a single comma-separated property also referenced by the actuator's own CORS config in `application.yml` - one source, not two hardcoded literals. Every fetch in `App.tsx` goes through the `apiFetch` wrapper, which turns the browser's undescriptive `TypeError: Failed to fetch` (thrown alike for a down backend, a network failure, or a CORS block) into a message naming the target URL and the likely cause, so a blocked or failed call is never a silent blank screen.

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
- Person-owned tables carry a `user_id` and exactly one user is seeded (migration `V2__app_user.sql`); `app_user` is the person table its own PK is that id.

## Persistence: attempts and submissions

Practice history is durable (issue #15), in the `attempt` package over plain Spring JDBC (`JdbcClient`, not JPA) with the schema owned by Flyway migration `V3__attempts.sql`.
The record is deliberately the schedulers' input (issue #8) and over-captures on purpose, since thin records would cripple that later work.

- An `Attempt` is one sitting with an exercise; a `Submission` is one press of Run within it. Both are person-owned (`user_id` -> `app_user`). `exercise_id` is a plain text id, **not** a foreign key - content lives only in the private repo (issue #4/#14) - so the attempt snapshots `exercise_title`, `domain` and `form` at creation, keeping history readable even with no content clone.
- `AttemptOutcome` is `IN_PROGRESS | SOLVED | ABANDONED | READ`, stored as free text (no DB `CHECK`), so a new outcome is added in the enum alone with no migration - which is how `READ` (a lesson is read, not solved; carries no 0-5 quality, SRS ignores it, readiness counts it) was added ahead of the lesson track that produces it. Abandonment being a recorded outcome, distinct from a never-started absence (no row), is the ticket's core invariant.
- `hints_taken` and `failing_case_revealed` are populated by the judging track (#16, see below); `complexity_claim`/`measured_complexity`/`complexity_claim_correct` (measurement is #17) stay at their defaults until that ticket. `V4__judging.sql` adds `attempt.reveal_hypothesis` and `submission.runtime_millis`. None of these are penalised.
- `AttemptService` owns the lifecycle (grading is still delegated to `GraderRegistry`): `start` opens an attempt snapshotting the exercise, `submit` grades + stores a submission and marks the attempt `SOLVED` on the first passing verdict; `abandon`, `takeHint` and `revealFailingCase` act only on an `IN_PROGRESS` attempt (else `IllegalAttemptStateException` -> 409; unknown id -> `AttemptNotFoundException` -> 404, both mapped by `AttemptErrorHandler`). The current user is the single seeded one via the `CurrentUser` seam - the one place to change when auth ever lands.
- The web seam is `AttemptController` (`/api/attempts`: `GET` history, `POST` start, `POST /{id}/submissions|hints|abandon|reveal`; `reveal` takes `{ submission, hypothesis }`). Grading no longer lives on `ExerciseController` - every run is a persisted submission, so there is no stateless grade path to bypass it. The React editor starts an attempt lazily on the first Run (so glancing never creates an empty row), abandons an unsolved sitting on switch-away, offers the hint ladder and failing-case reveal, and renders a plain history table.

## Judging: withhold by default, hint ladder, reveal on request

The judging behaviour (issue #16, deciding #5) is a training decision, not a UI detail: a failing verdict reports **only the count of failing tests** - `Verdict`/`RunResponse` carry `passed`/`total` and never a case's input, expected or actual. Help is always available, always chosen, always recorded, and **never** reduces a score, blocks completion, or affects the streak.

- Hints are an ordered ladder on the `Exercise` (`List<Hint>`, content-authored, parsed by `ExerciseParser`; empty when absent). `ExerciseView` ships only rung **names**, never bodies - `POST .../hints` discloses one body per call and records the rung reached in `hints_taken`.
- The failing case is disclosed only by `POST .../reveal`, which grades the current `submission` and returns the first failing case (`GraderRegistry.firstFailingCase` -> `TestCaseGrader`; `AnswerKeyGrader` has none). The reveal records `failing_case_revealed` and the ungraded, skippable one-line `reveal_hypothesis` typed first (pedagogy audit). The editor also shows a non-penalised "stuck?" nudge offering the next hint after repeated failures.
- Runtime is `Verdict.runtimeMillis` (measured around `runner.execute`, persisted on the submission), shown for interest only - correctness is pass/fail and runtime is never part of the verdict.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
