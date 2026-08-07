#!/usr/bin/env bash
# Fails if any private exercise content is committed to this public repo.
#
# The public-engine/private-content decision (issue #4) and issue #14 forbid ever
# committing a problem statement, test data, reference solution or generator here.
# This is the mechanical enforcement that acceptance criterion asks for, run in CI
# and by ./test.sh, so the rule holds by check rather than by discipline.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

fail=0

# 1. The content directory must stay untracked - it is a gitignored local clone.
tracked_content="$(git ls-files -- content/ || true)"
if [ -n "$tracked_content" ]; then
  echo "ERROR: exercise content is tracked under content/ - it must never be committed:" >&2
  echo "$tracked_content" | sed 's/^/    /' >&2
  fail=1
fi

# 2. content/ must be gitignored so a clone there is never accidentally staged.
if ! git check-ignore -q content/probe.json; then
  echo "ERROR: content/ is not gitignored (add /content/ to .gitignore)." >&2
  fail=1
fi

# 3. No content-shaped JSON committed anywhere. Two shapes leak from the private
#    repo (issue #46): an exercise is an object carrying a "statement" together
#    with a "grading" spec; a lesson has no "grading" but carries a
#    "kind":"lesson" discriminator, or a "statement" together with its "checks".
exercise_suspects=""
lesson_suspects=""
while IFS= read -r file; do
  [ -n "$file" ] || continue
  has_statement=0
  grep -lq '"statement"' -- "$file" 2>/dev/null && has_statement=1
  if [ "$has_statement" -eq 1 ] && grep -lq '"grading"' -- "$file" 2>/dev/null; then
    exercise_suspects="${exercise_suspects}${file}"$'\n'
  elif grep -Elq '"kind"[[:space:]]*:[[:space:]]*"lesson"' -- "$file" 2>/dev/null \
      || { [ "$has_statement" -eq 1 ] && grep -lq '"checks"' -- "$file" 2>/dev/null; }; then
    lesson_suspects="${lesson_suspects}${file}"$'\n'
  fi
done < <(git ls-files -- '*.json')

if [ -n "$exercise_suspects" ]; then
  echo "ERROR: files look like committed exercise content (have both \"statement\" and \"grading\"):" >&2
  printf '%s' "$exercise_suspects" | sed 's/^/    /' >&2
  fail=1
fi

if [ -n "$lesson_suspects" ]; then
  echo "ERROR: files look like committed lesson content (have \"kind\":\"lesson\", or \"statement\" and \"checks\"):" >&2
  printf '%s' "$lesson_suspects" | sed 's/^/    /' >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "" >&2
  echo "Private exercise content must live only in the swe-prep-content repo (issue #4/#14)." >&2
  exit 1
fi

echo "OK: no private exercise content is committed to this repo."
