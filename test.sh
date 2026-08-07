#!/usr/bin/env bash
# Runs the whole test suite: backend (Spring Boot, via Testcontainers Postgres)
# then frontend (Vitest). Requires Docker to be running.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "== no committed content =="
./scripts/check-no-content.sh

echo "== backend =="
(cd backend && ./mvnw test)

echo "== frontend =="
(cd frontend && npm test)
