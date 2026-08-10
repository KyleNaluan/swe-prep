#!/usr/bin/env bash
# The single command issue #24 asks for: takes a problem spec (statement, cases,
# reference solution) and derives a complete content entry - the challenge plus
# its warm-up reps - into a local clone of the private swe-prep-content repo,
# after presenting everything for human review and requiring an explicit accept.
#
# Usage:
#   scripts/author-content.sh <problem-spec.json> [content-dir] [--yes]
#
# content-dir defaults to $SWEPREP_CONTENT_PATH if not given. See
# backend/src/main/java/com/sweprep/backend/authoring/ProblemSpecParser.java for
# the problem-spec JSON format.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <problem-spec.json> [content-dir] [--yes]" >&2
  exit 2
fi

problem="$1"
shift

exec_args=("--problem" "$problem")
if [ "$#" -gt 0 ] && [ "$1" != "--yes" ]; then
  exec_args+=("--content-dir" "$1")
  shift
fi
if [ "$#" -gt 0 ] && [ "$1" = "--yes" ]; then
  exec_args+=("--yes")
  shift
fi

exec_args_joined="$(printf '%s ' "${exec_args[@]}")"

cd "$repo_root/backend"
./mvnw -q compile exec:java \
  -Dexec.mainClass=com.sweprep.backend.authoring.AuthorContentCli \
  -Dexec.args="${exec_args_joined% }"
