#!/usr/bin/env bash
# Usage:
#   bluebirdconnector         ... runs without diagnostics, normal mode
#   bluebirdconnector -v      ... runs with diagnostics printed to terminal, blocking the terminal

set -euo pipefail

BB_DIR=~csci131/share/BlueBirdConnector/bin
LAUNCHER=./launcher

usage() {
  echo "Usage: $(basename "$0") [-v]" >&2
  exit 2
}

verbose=0
if [[ "${1-}" == "-v" ]]; then
  verbose=1
  shift
elif [[ "${1-:-}" == "-"* ]]; then
  usage
fi

run_foreground() {
  cd "$BB_DIR" || { echo "Error: cannot cd to $BB_DIR" >&2; exit 1; }
  exec "$LAUNCHER"
}

run_detached() {
  cd "$BB_DIR" || { echo "Error: cannot cd to $BB_DIR" >&2; exit 1; }

  # Prefer setsid if available (new session, no controlling TTY)
  if command -v setsid >/dev/null 2>&1; then
    nohup setsid "$LAUNCHER" </dev/null >/dev/null 2>&1 &
  else
    # Fallback: nohup + background is still fine for terminal detachment
    nohup "$LAUNCHER" </dev/null >/dev/null 2>&1 &
  fi

  # Disown if job control is available (interactive shells)
  if [[ -o monitor ]]; then disown; fi

  # No output — stay silent so we don't interfere with the terminal
  exit 0
}

if (( verbose )); then
  export BLUEBIRD_LOG_LEVEL=INFO
  run_foreground
else
  if [[ -n "${BLUEBIRD_LOG_LEVEL:-}" ]]; then
    run_foreground
  else
    run_detached
  fi
fi


