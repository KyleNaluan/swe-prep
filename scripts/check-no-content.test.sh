#!/usr/bin/env bash
# Guards the leak guard: verifies check-no-content.sh flags both exercise- and
# lesson-shaped private content, and passes on a clean tree. Runs the real
# script inside throwaway git repos so the git ls-files scan is exercised for
# real (issue #46 broadened it to lesson content).
set -euo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-no-content.sh"

fail=0

setup_repo() {
  local dir
  dir="$(mktemp -d)"
  git -C "$dir" init -q
  cp "$script" "$dir/check-no-content.sh"
  printf '/content/\n' >"$dir/.gitignore"
  git -C "$dir" add .gitignore check-no-content.sh
  echo "$dir"
}

# 1. A committed lesson (kind:lesson + statement + checks, no grading) must fail
#    and name the file - the shape that previously slipped past the guard.
dir="$(setup_repo)"
printf '{"kind":"lesson","statement":"read me","checks":["ex-1"]}\n' >"$dir/leaked-lesson.json"
git -C "$dir" add leaked-lesson.json
if out="$(cd "$dir" && ./check-no-content.sh 2>&1)"; then
  echo "FAIL: lesson-shaped JSON was not flagged" >&2
  fail=1
elif ! printf '%s' "$out" | grep -q 'leaked-lesson.json'; then
  echo "FAIL: lesson error did not name the file: $out" >&2
  fail=1
fi
rm -rf "$dir"

# 2. A committed exercise (statement + grading) still fails - existing rule.
dir="$(setup_repo)"
printf '{"statement":"solve it","grading":{"kind":"answerKey"}}\n' >"$dir/leaked-exercise.json"
git -C "$dir" add leaked-exercise.json
if out="$(cd "$dir" && ./check-no-content.sh 2>&1)"; then
  echo "FAIL: exercise-shaped JSON was not flagged" >&2
  fail=1
elif ! printf '%s' "$out" | grep -q 'leaked-exercise.json'; then
  echo "FAIL: exercise error did not name the file: $out" >&2
  fail=1
fi
rm -rf "$dir"

# 3. An ordinary JSON with no content shape passes.
dir="$(setup_repo)"
printf '{"name":"config","value":1}\n' >"$dir/tsconfig.json"
git -C "$dir" add tsconfig.json
if ! (cd "$dir" && ./check-no-content.sh >/dev/null 2>&1); then
  echo "FAIL: benign JSON was flagged as content" >&2
  fail=1
fi
rm -rf "$dir"

if [ "$fail" -ne 0 ]; then
  echo "check-no-content.test.sh: FAILED" >&2
  exit 1
fi
echo "OK: check-no-content.sh flags exercise and lesson leaks, passes clean trees."
