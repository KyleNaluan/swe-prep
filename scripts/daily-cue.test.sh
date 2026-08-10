#!/usr/bin/env bash
# Tests daily-cue.sh's decision logic (pure) and, end to end, its wiring of
# fetch -> decide -> notify - the same "run the real script for real" discipline
# as check-no-content.test.sh, so a change to the JSON shape or the curl/notify
# call is caught here rather than only discovered at the configured hour on a
# real machine. curl and the notify command are faked (PATH shadowing for curl,
# an env-var override for notify), never the script under test.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$script_dir/daily-cue.sh"

fail=0

# --- decide() is pure: source the script (the BASH_SOURCE guard means sourcing
# never runs main) and call it directly with fixture JSON. ---
# shellcheck disable=SC1090
source "$script"

if [ "$(decide '{"dayComplete":true,"completedAt":"2026-08-10T09:00:00Z","streak":4}')" != "skip" ]; then
  echo "FAIL: a complete day should skip the cue" >&2
  fail=1
fi

if [ "$(decide '{"dayComplete":false,"completedAt":null,"streak":3}')" != "notify" ]; then
  echo "FAIL: an incomplete day should fire the cue" >&2
  fail=1
fi

if decide '{"streak":3}' >/dev/null 2>&1; then
  echo "FAIL: JSON missing dayComplete should fail rather than silently skip" >&2
  fail=1
fi

# --- End to end: fake curl (via PATH shadowing) standing in for the backend,
# and a fake notify command (via env var) standing in for notify-send, so the
# whole script - not just decide() - is exercised for each scenario.
fakebin="$(mktemp -d)"
notify_log="$(mktemp)"

cat >"$fakebin/curl" <<'EOF'
#!/usr/bin/env bash
if [ "${CURL_FAIL:-}" = "1" ]; then
  echo "curl: (7) Failed to connect" >&2
  exit 7
fi
cat "$CURL_FIXTURE"
EOF
chmod +x "$fakebin/curl"

notify_stub="$fakebin/notify-stub"
cat >"$notify_stub" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$NOTIFY_LOG"
EOF
chmod +x "$notify_stub"

fixture="$(mktemp)"

run_script() {
  PATH="$fakebin:$PATH" NOTIFY_LOG="$notify_log" CURL_FIXTURE="$fixture" \
    SWEPREP_DAILY_CUE_NOTIFY_CMD="$notify_stub" bash "$script"
}

# 1. Day already complete -> no notification.
printf '{"dayComplete":true,"completedAt":"x","streak":1}' >"$fixture"
: >"$notify_log"
if ! run_script; then
  echo "FAIL: a reachable backend reporting a complete day should exit 0" >&2
  fail=1
fi
if [ -s "$notify_log" ]; then
  echo "FAIL: a complete day fired a notification: $(cat "$notify_log")" >&2
  fail=1
fi

# 2. Day not complete -> exactly one notification.
printf '{"dayComplete":false,"completedAt":null,"streak":1}' >"$fixture"
: >"$notify_log"
if ! run_script; then
  echo "FAIL: a reachable backend reporting an incomplete day should exit 0" >&2
  fail=1
fi
if [ ! -s "$notify_log" ]; then
  echo "FAIL: an incomplete day did not fire a notification" >&2
  fail=1
fi

# 3. Backend unreachable -> no notification, non-zero exit, no crash.
: >"$notify_log"
if CURL_FAIL=1 run_script 2>/dev/null; then
  echo "FAIL: an unreachable backend should exit non-zero" >&2
  fail=1
fi
if [ -s "$notify_log" ]; then
  echo "FAIL: an unreachable backend should not fire a notification" >&2
  fail=1
fi

rm -rf "$fakebin" "$notify_log" "$fixture"

if [ "$fail" -ne 0 ]; then
  echo "daily-cue.test.sh: FAILED" >&2
  exit 1
fi
echo "OK: daily-cue.sh skips a complete day, notifies an incomplete one, and stays silent when the backend is unreachable."
