#!/usr/bin/env bash
set -euo pipefail

# One-click-ish launcher for macOS/Linux terminals:
#   ./LaunchServer.sh
#
# Config via env vars:
#   PORT=25033 MONITOR=1 DEBUG=0 TIMEOUT=0 SCORE=0 ./LaunchServer.sh

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PORT="${PORT:-25033}"
MONITOR="${MONITOR:-1}"
DEBUG="${DEBUG:-0}"
TIMEOUT="${TIMEOUT:-10}"
SCORE="${SCORE:-0}"

if [[ ! -f "OthelloServer.jar" ]]; then
  echo "[ERROR] OthelloServer.jar not found in: $PWD" >&2
  exit 1
fi

args=( -port "$PORT" )
[[ "$MONITOR" == "1" ]] && args+=( -monitor )
[[ "$DEBUG" == "1" ]] && args+=( -debug )
[[ "$SCORE" == "1" ]] && args+=( -score )
[[ "$TIMEOUT" != "0" ]] && args+=( -timeout "$TIMEOUT" )

echo "Starting server: java -jar OthelloServer.jar ${args[*]}"
exec java -jar "OthelloServer.jar" "${args[@]}"

