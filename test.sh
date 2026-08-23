#!/usr/bin/env bash
# Runs the whole test suite: backend (Spring Boot, via Testcontainers Postgres)
# then frontend (Vitest). Requires Docker to be running.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "== no committed content =="
./scripts/check-no-content.sh
./scripts/check-no-content.test.sh

echo "== daily cue =="
./scripts/daily-cue.test.sh

echo "== backend =="
# Default to the project's Maven wrapper for local dev, but allow the
# environment to point at an already-installed Maven ($MVN). CI sets MVN=mvn
# to use the runner's preinstalled Maven, so the wrapper never re-downloads
# the Apache Maven distribution from Maven Central on every run (which is
# rate-limited and returned HTTP 429). The api.version pin lives in the pom's
# surefire config, so either binary runs the suite identically.
(cd backend && "${MVN:-./mvnw}" test)

echo "== frontend =="
(cd frontend && npm test)
