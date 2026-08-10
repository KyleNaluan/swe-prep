#!/usr/bin/env bash
# The daily-cue check (issue #23): fires a notification on any day today's session
# is not yet complete, and stays silent on a day already complete. A systemd user
# timer (scripts/daily-cue/) is what supplies the "configurable time" acceptance
# criterion by running this once a day at a chosen hour - this script does the one
# thing the timer can't do itself: ask the real session record (issue #19's
# SessionService, via GET /api/session) whether today is done, never a parallel
# flag of its own.
set -euo pipefail

: "${SWEPREP_DAILY_CUE_BASE_URL:=http://localhost:8080}"
# Deliberately a bare command name, not a fixed notify-send call: which channel
# actually delivers a "notification" on a given machine varies (desktop
# notify-send, a Windows toast bridge from WSL, a push service reachable over the
# tailnet), so the systemd service unit is the one place that picks it, via
# Environment=. notify-send is the sane default for a normal Linux desktop.
: "${SWEPREP_DAILY_CUE_NOTIFY_CMD:=notify-send}"

# Fetches today's session status from the running backend and prints the raw
# JSON body. Isolated from decide() so the decision logic can be unit-tested
# against fixture JSON without a live backend.
fetch_session_json() {
  curl --fail --silent --show-error "${SWEPREP_DAILY_CUE_BASE_URL}/api/session"
}

# Pure: given a GET /api/session response body, decides whether to notify.
# Prints "notify" or "skip" on stdout. /api/session's shape is our own
# SessionStatus record - a flat, known JSON object - so a targeted grep is
# enough without adding a jq dependency for one boolean field (the same
# reasoning scripts/check-no-content.sh makes for JSON it already trusts).
decide() {
  local json="$1"
  if grep -q '"dayComplete":true' <<<"$json"; then
    echo "skip"
  elif grep -q '"dayComplete":false' <<<"$json"; then
    echo "notify"
  else
    echo "could not find a dayComplete field in: $json" >&2
    return 1
  fi
}

main() {
  local json
  if ! json="$(fetch_session_json)"; then
    echo "ERROR: could not reach swe-prep backend at ${SWEPREP_DAILY_CUE_BASE_URL}/api/session - is it running? No cue fired, since whether today is complete can't be determined." >&2
    exit 2
  fi

  local decision
  decision="$(decide "$json")" || exit 1

  if [ "$decision" = "notify" ]; then
    # Unquoted on purpose: SWEPREP_DAILY_CUE_NOTIFY_CMD may itself carry flags
    # (e.g. "notify-send -u normal -a swe-prep"), so it is word-split rather
    # than passed as one argument.
    $SWEPREP_DAILY_CUE_NOTIFY_CMD "swe-prep" "Today's session isn't done yet - open swe-prep for the warm-up."
    echo "OK: notified - today's session is not yet complete."
  else
    echo "OK: today's session is already complete - no cue."
  fi
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
