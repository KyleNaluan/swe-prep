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

2. Start the backend (runs on `:8080`, applies Flyway migrations on boot):

   ```sh
   cd backend
   ./mvnw spring-boot:run
   ```

3. Start the frontend (runs on `:5173`):

   ```sh
   cd frontend
   npm install
   npm run dev
   ```

4. Open `http://localhost:5173` — it shows one coding exercise in a Monaco
   editor; write a solution and press Run to have the backend compile and
   execute it against the exercise's test cases and report `N of M tests passed`.

If Postgres isn't reachable, the backend fails to start rather than coming up
in a degraded state.

## Running the tests

```sh
./test.sh
```

Runs the backend suite (Spring Boot tests against a real, disposable Postgres
container via Testcontainers — needs Docker) and then the frontend suite
(Vitest).

CI runs this same `./test.sh` on every push and pull request; see `AGENTS.md`.

## Design decisions

The planning map at [issue #1](https://github.com/KyleNaluan/swe-prep/issues/1) is the authoritative record of design decisions for this project.
