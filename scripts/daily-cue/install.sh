#!/usr/bin/env bash
# Installs the daily-cue systemd user service + timer (issue #23) for the
# current user. This only writes user-level units under
# ~/.config/systemd/user/ and enables them for this account - it never touches
# anything system-wide, matching how the app's own autostart (decision issue
# #2) already runs as a user unit rather than a system one. This is a deploy
# step for whoever operates the machine, not something run from CI or a
# worktree.
#
# Usage: scripts/daily-cue/install.sh [--time HH:MM] [--repo-path PATH]
#   --time HH:MM    24-hour local time the cue fires at. Default: 09:00.
#   --repo-path PATH  Where this repo lives on the machine the timer runs on.
#                      Default: this script's own checkout.
set -euo pipefail

time="09:00"
repo_path=""

while [ $# -gt 0 ]; do
  case "$1" in
    --time)
      time="$2"
      shift 2
      ;;
    --repo-path)
      repo_path="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--time HH:MM] [--repo-path PATH]" >&2
      exit 1
      ;;
  esac
done

if ! [[ "$time" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]]; then
  echo "ERROR: --time must be 24-hour HH:MM (got: $time)" >&2
  exit 1
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -z "$repo_path" ]; then
  repo_path="$(cd "$script_dir/../.." && pwd)"
fi

if [ ! -x "$repo_path/scripts/daily-cue.sh" ]; then
  echo "ERROR: $repo_path/scripts/daily-cue.sh not found or not executable - pass --repo-path if the repo lives somewhere else on this machine." >&2
  exit 1
fi

unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$unit_dir"

sed "s#__REPO_PATH__#${repo_path}#g" "$script_dir/swe-prep-daily-cue.service" >"$unit_dir/swe-prep-daily-cue.service"
sed "s#__TIME__#${time}#g" "$script_dir/swe-prep-daily-cue.timer" >"$unit_dir/swe-prep-daily-cue.timer"

systemctl --user daemon-reload
systemctl --user enable --now swe-prep-daily-cue.timer

echo "Installed. The daily cue fires at ${time} local time on any day today's session isn't complete yet."
echo ""

linger="$(loginctl show-user "$USER" -p Linger --value 2>/dev/null || echo unknown)"
if [ "$linger" != "yes" ]; then
  echo "NOTE: linger is not enabled for this user ($linger)."
  echo "  Without it, this timer - and the app's own autostart unit, decision issue #2 -"
  echo "  only run while you are logged in, which defeats 'survives a reboot' if the"
  echo "  machine reboots to a login screen you haven't reached yet. Enable it with:"
  echo "    sudo loginctl enable-linger \"\$USER\""
  echo ""
fi

echo "Status:  systemctl --user status swe-prep-daily-cue.timer"
echo "Logs:    journalctl --user -u swe-prep-daily-cue.service"
echo "Change the time: rerun this script with a new --time, or edit"
echo "  $unit_dir/swe-prep-daily-cue.timer directly and run:"
echo "    systemctl --user daemon-reload && systemctl --user restart swe-prep-daily-cue.timer"
