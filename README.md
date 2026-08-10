# swe-prep

A single-user, habit-forming interview-prep app that runs your code.

The daily loop:

- A ~4-minute warm-up of ~8 quick reps.
  Finishing the warm-up alone completes the day.
- An optional ~30-minute main exercise.
- Open-ended continuation after that, for anyone who wants more.

## Stack

- Spring Boot 3.4.x on Java 21, Maven, in `backend/`
- Postgres, via the `docker-compose.yml` at the repo root
- React + Vite, in `frontend/`
- Monaco editor
- Run locally, reached over a tailnet

See `AGENTS.md` for the repo layout decision and other project-intrinsic notes.

## Running locally

Prerequisites: Java 21 on `PATH`, Docker running, Node 24, Python 3 on `PATH` (for solving in Python - Java remains the default language and needs no extra setup).

1. Start Postgres:

   ```sh
   docker compose up -d
   ```

2. Clone the private content repo. Exercises are loaded from a local path, never
   committed here (see the content note below):

   ```sh
   git clone https://github.com/KyleNaluan/swe-prep-content.git content
   # or clone it anywhere and set SWEPREP_CONTENT_PATH to that path
   ```

3. Start the backend (runs on `:8080`, applies Flyway migrations on boot):

   ```sh
   cd backend
   ./mvnw spring-boot:run
   ```

4. Start the frontend (runs on `:5173`, and only `:5173`: the dev server is pinned with
   `strictPort` so it fails loudly if that port is taken instead of silently moving to
   another one and leaving every API call refused with nothing explaining why):

   ```sh
   cd frontend
   npm install
   npm run dev
   ```

5. Open `http://localhost:5173`. The app opens straight on the daily warm-up, with
   Today/Readiness/Practice/Learn tabs to switch surfaces and a role picker in the header for
   choosing which roles you are preparing for (issue #40): the required warm-up and
   auto-seeding then draw only from those families (plus the always-on core), while
   everything stays reachable through the Practice/Learn browse tabs - it hides nothing.
   The warm-up (issue #18) walks an
   interleaved set of ~8 quick reps one at a time - multiple-choice or a short
   predict-the-output box - driving the same attempt flow every exercise uses; a
   wrong answer shows the check's explanation at once and re-queues that rep later
   in the set, while a correct answer withholds the explanation but keeps it one
   keystroke away. Finishing the warm-up completes the day (issue #19): a
   day-complete landing celebrates that first, then offers an optional ~30-minute
   main exercise as a bonus - declining keeps the day done and the streak intact, and the
   landing links straight into the Readiness surface at the moment progress is most salient. The
   Practice tab is the free-practice editor: pick an exercise
   from the selector. A coding
   exercise opens in a Monaco editor: write a solution and press Run to have the
   backend compile and execute it against the test cases and report
   `N of M tests passed`. Java is the default language; a code exercise's header also
   offers a language picker (issue #26) - switching languages regenerates the stub from
   the exercise's own language-neutral signature, with no change to its test cases. A
   failing run reports only that count, never a case's
   input, expected, or actual value: reasoning about your own code without an oracle
   is the interview skill being trained. Help is always available and never penalised
   - an ordered hint ladder whose rungs you reveal one at a time, and, after a failing
   run, a failing-case reveal that first asks for a one-line hypothesis before
   disclosing the case; a "stuck?" nudge quietly offers the next hint after repeated
   failures. A check that carries an explanation of why the answer is right shows it
   automatically on a wrong answer, and offers it one click away once the sitting has
   ended; asking is recorded but never affects your score - it is not a hint. Runtime is
   shown next to the verdict for interest only, never as part of pass/fail. Once a
   coding exercise is solved, if it carries a complexity target you are asked to state
   your solution's time and space complexity before that target is revealed (issue #17)
   - articulating it is itself the interview skill - and your time claim is checked by
   measuring how the solution actually scales; the app only ever says the measurement is
   "consistent" with your claim or explicitly inconclusive, never "correct", because
   timing cannot tell some growth rates (like O(n) vs O(n log n)) apart. A concept exercise shows multiple-choice options and is
   graded with no code execution at all. A SQL exercise (issue #25) is the same Monaco
   editor and Run flow, worded in rows instead of tests: the submitted query runs against
   a shared fixture schema on a separate database, as a read-only role, inside a
   transaction that is always rolled back, so a `DROP TABLE` is refused rather than
   merely undone; row order only matters when the exercise asks for it, and a failing
   run reports only a bare row count. An "explain it in your own words" exercise
   (issue #41) is different again: it takes free text, then reveals a model answer for
   you to self-rate against - production practice that is deliberately never machine-graded,
   so it lives only in the optional tiers, never the required warm-up. The Learn tab
   (issue #46) is the reading surface: a lesson is read, never attempted, and its
   embedded self-explanation prompts each ask you to answer in your own words before
   revealing a model answer. The Readiness tab (issue #45) is the honest progress
   picture, a first-class surface rather than a screen tucked behind Practice: plain
   X/Y counts of checks retrieved to criterion and challenges solved cold (no hints, no
   failing case revealed), concepts (lessons) read, and a per-family breakdown - never a
   score, points, badges, or levels, and a broken streak is only ever described, never
   framed as a loss. Each sitting and every press of Run is
   recorded as durable practice history, shown in a History panel below the editor
   (with the hints taken) and surviving a restart; a Give up button abandons an
   unsolved sitting.

If Postgres isn't reachable, the backend fails to start rather than coming up in
a degraded state. If the content path is missing or a file is malformed, the app
still starts and the editor shows a clear error naming the cause. If the browser
can't reach the backend at all - it's down, or this page was opened from an origin
the backend doesn't allow - the app shows that too, naming the cause, instead of an
unexplained blank screen (issue #34).

### Reaching it from another device over the tailnet

The frontend dev server binds every interface, not just `localhost`, so opening
`http://<tailnet-hostname-or-IP>:5173` from your phone or another machine works the
same as `localhost:5173` does. The frontend's API calls go through the dev server's
own proxy to the backend on the same host, so this needs no CORS configuration at
all - the browser only ever talks to whatever origin loaded the page. The backend's
own CORS list (`sweprep.web.allowed-origins`, `backend/src/main/resources/application.yml`)
only matters for a client that talks to it directly, bypassing the frontend.

### Daily cue (optional)

`scripts/daily-cue/install.sh` installs a systemd user timer that fires a
notification at a time you choose, on any day today's session (issue #19)
isn't complete yet - so the decision to practise isn't made fresh each day
(issue #23). It reuses the same scheduled-user-service mechanism the app's own
autostart already relies on (decision issue #2), rather than a separate one.

```sh
scripts/daily-cue/install.sh --time 09:00
```

This only installs a user-level unit for your account
(`~/.config/systemd/user/`) and needs the backend reachable at
`http://localhost:8080` when the timer fires (override with
`SWEPREP_DAILY_CUE_BASE_URL` in the installed service unit). `install.sh`
prints how to change the time later, how to check the schedule survived a
reboot without a login session (`loginctl enable-linger`), and where the logs
are; `notify-send` is the default notification command and is swappable per
machine via `SWEPREP_DAILY_CUE_NOTIFY_CMD` in the service unit's
`Environment=` line.

## Content

Problem content — statements, test data, reference solutions, generators — lives
in a **separate private repo**, `swe-prep-content`, and is loaded from a local
clone at `sweprep.content.path` (default `../content`, i.e. `<repo-root>/content`;
override with `SWEPREP_CONTENT_PATH`). That path is gitignored. No problem content
of any kind is ever committed to this public repo; `scripts/check-no-content.sh`
enforces it in CI and in `./test.sh`. See the public-engine/private-content
decision, [issue #4](https://github.com/KyleNaluan/swe-prep/issues/4).

### Content authoring

Authoring a problem produces its warm-up reps rather than someone hand-writing
them (issue #24): `scripts/author-content.sh <problem-spec.json> [content-dir] [--yes]`
takes one problem - a statement, test cases, a reference solution, and optionally
an input generator, the authoring unit - and derives a complete content entry:
the `CHALLENGE` exercise plus up to five warm-up reps (pattern-identification,
complexity, fill-in-the-blank, spot-the-bug, predict-output), each mechanically
derived from the reference solution (or, for pattern-identification, from the
problem's own declared topics) rather than drafted by hand. Every derivation runs
through the exact compiler/runner pair a learner's submission is graded with, so
"the reference solution passes its own cases" and "this mutation genuinely breaks
the solution" are both checked empirically, never assumed. Everything derived -
statement, options, the correct answer, every distractor's misconception, and any
answer-tell findings - is printed for review before anything is written; declining
at the prompt writes nothing. The tool refuses to write anywhere inside this
public repo, matching the public-engine/private-content split above - point
`content-dir` at your `swe-prep-content` clone (or set `SWEPREP_CONTENT_PATH` and
omit it). See
`backend/src/main/java/com/sweprep/backend/authoring/ProblemSpecParser.java` for
the problem-spec JSON format, and `AGENTS.md` for the derivation approach.

## Habit layer: solution auto-commit, readiness, and streak repair

The no-invented-currency decision ([issue #7](https://github.com/KyleNaluan/swe-prep/issues/7))
replaces XP and points with three honest mechanics. Solving a coding challenge
commits the real solution to the same `swe-prep-content` clone (under a
`learner-solutions/` subdirectory - deliberately distinct from the authoring
tool's own `solutions/` reference-solution path so a learner solution never
clobbers one - `sweprep.commit.*` config, default on) and pushes it,
so the GitHub contribution graph becomes a true external record — real artifacts
only, never a synthetic "practice happened" marker. The readiness picture
(`GET /api/readiness`) reports pattern coverage, shaky topics, staleness and
solved-cold counts as plain counts and lists, never a score. A missed day can be
repaired by a double session (the warm-up plus a solved challenge) the next day,
capped at `sweprep.streak.max-repairs-per-month` (default 2) — see `AGENTS.md`
for the full design.

## Running the tests

```sh
./test.sh
```

First asserts no private content is committed (`scripts/check-no-content.sh`)
and runs the shell-script suites (the content guard's own tests and the
daily-cue check, `scripts/daily-cue.test.sh`), then runs the backend suite
(Spring Boot tests against a real, disposable Postgres container via
Testcontainers — needs Docker) and then the frontend suite (Vitest).

CI runs this same `./test.sh` on every push and pull request; see `AGENTS.md`.

## Design decisions

The planning map at [issue #1](https://github.com/KyleNaluan/swe-prep/issues/1) is the authoritative record of design decisions for this project.
