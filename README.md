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

Prerequisites: Java 21 on `PATH`, Docker running, Node 24.

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
   `N of M tests passed`. A failing run reports only that count, never a case's
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
   graded with no code execution at all. An "explain it in your own words" exercise
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

## Content

Problem content — statements, test data, reference solutions, generators — lives
in a **separate private repo**, `swe-prep-content`, and is loaded from a local
clone at `sweprep.content.path` (default `../content`, i.e. `<repo-root>/content`;
override with `SWEPREP_CONTENT_PATH`). That path is gitignored. No problem content
of any kind is ever committed to this public repo; `scripts/check-no-content.sh`
enforces it in CI and in `./test.sh`. See the public-engine/private-content
decision, [issue #4](https://github.com/KyleNaluan/swe-prep/issues/4).

## Running the tests

```sh
./test.sh
```

First asserts no private content is committed (`scripts/check-no-content.sh`),
then runs the backend suite (Spring Boot tests against a real, disposable Postgres
container via Testcontainers — needs Docker) and then the frontend suite
(Vitest).

CI runs this same `./test.sh` on every push and pull request; see `AGENTS.md`.

## Design decisions

The planning map at [issue #1](https://github.com/KyleNaluan/swe-prep/issues/1) is the authoritative record of design decisions for this project.
