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

# 3. No exercise-shaped JSON committed anywhere: the content format is an object
#    carrying a "statement" together with a "grading" spec. Any tracked JSON with
#    both is problem content that has leaked out of the private repo.
suspects=""
while IFS= read -r file; do
  [ -n "$file" ] || continue
  if grep -lq '"statement"' -- "$file" 2>/dev/null \
      && grep -lq '"grading"' -- "$file" 2>/dev/null; then
    suspects="${suspects}${file}"$'\n'
  fi
done < <(git ls-files -- '*.json')

if [ -n "$suspects" ]; then
  echo "ERROR: files look like committed exercise content (have both \"statement\" and \"grading\"):" >&2
  printf '%s' "$suspects" | sed 's/^/    /' >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "" >&2
  echo "Private exercise content must live only in the swe-prep-content repo (issue #4/#14)." >&2
  exit 1
fi

echo "OK: no private exercise content is committed to this repo."
