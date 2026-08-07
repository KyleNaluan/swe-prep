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

4. Start the frontend (runs on `:5173`):

   ```sh
   cd frontend
   npm install
   npm run dev
   ```

5. Open `http://localhost:5173` — pick an exercise from the selector. A coding
   exercise opens in a Monaco editor: write a solution and press Run to have the
   backend compile and execute it against the test cases and report
   `N of M tests passed`. A concept exercise shows multiple-choice options and is
   graded with no code execution at all. Each sitting and every press of Run is
   recorded as durable practice history, shown in a History panel below the editor
   and surviving a restart; a Give up button abandons an unsolved sitting.

If Postgres isn't reachable, the backend fails to start rather than coming up in
a degraded state. If the content path is missing or a file is malformed, the app
still starts and the editor shows a clear error naming the cause.

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
