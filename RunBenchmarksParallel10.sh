#!/usr/bin/env bash
set -euo pipefail

# Parallel benchmark runner (no AI source changes required).
#
# Model:
# - Each match runs on its own server instance (unique port per worker).
# - Both clients exit after receiving END (matches your current OthelloClientAI behavior).
# - Run up to PARALLEL matches concurrently (default 10).
# - Append per-match result to a single CSV by merging per-worker CSVs at the end.
#
# Usage:
#   bash RunBenchmarksParallel10.sh                 # default GAMES=20 PARALLEL=10
#   bash RunBenchmarksParallel10.sh 30              # total matches
#   GAMES=30 PARALLEL=10 PORT_BASE=26000 bash RunBenchmarksParallel10.sh
#   HOST=localhost MY_MODE=tt MY_SECONDS=3 MONTE_SECONDS=10 bash RunBenchmarksParallel10.sh 10
#
# Notes:
# - Requires: java, bash with /dev/tcp enabled.
# - Server version behavior (random pairing every 0.5s among connected clients) is avoided by
#   isolating each match into its own server instance/port.

GAMES="${1:-${GAMES:-20}}"
PARALLEL="${PARALLEL:-10}"
HOST="${HOST:-localhost}"
PORT_BASE="${PORT_BASE:-25033}"

MY_MODE="${MY_MODE:-tt}"
MY_SECONDS="${MY_SECONDS:-3}"
MONTE_SECONDS="${MONTE_SECONDS:-10}"

# Server per-move timeout (seconds). Must be >= both clients' per-move budgets.
SERVER_TIMEOUT="${SERVER_TIMEOUT:-12}"

# Max wall time per match before force-killing processes (seconds).
WALL_TIMEOUT="${WALL_TIMEOUT:-1200}"

# Force AWT/Swing GUI mode for the server monitor (helps when some environments default to headless).
JAVA_MONITOR_OPTS="${JAVA_MONITOR_OPTS:--Djava.awt.headless=false}"

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

timestamp="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="benchmarks/${timestamp}-parallel${PARALLEL}-monte${MONTE_SECONDS}s-vs-${MY_MODE}${MY_SECONDS}s"
mkdir -p "$OUT_DIR"

echo "[INFO] Output: ${OUT_DIR}"
echo "[INFO] Compiling Src/*.java ..."
javac -encoding UTF-8 Src/*.java

wait_port() {
  local host="$1"
  local port="$2"
  local tries="${3:-120}" # ~12s with 0.1 sleep
  local i=0
  while (( i < tries )); do
    if (echo >"/dev/tcp/${host}/${port}") >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
    i=$((i + 1))
  done
  return 1
}

kill_if_alive() {
  local pid="${1:-}"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    sleep 0.2 || true
    kill -9 "$pid" 2>/dev/null || true
  fi
}

wait_pid_timeout() {
  local pid="$1"
  local timeout_s="$2"
  local waited=0
  while kill -0 "$pid" 2>/dev/null; do
    if (( waited >= timeout_s )); then
      return 124
    fi
    sleep 0.2
    waited=$((waited + 1))
  done
  wait "$pid"
}

parse_winner_from_my_log() {
  local my_log="$1"
  local end_line
  end_line="$(rg -n "^END " "$my_log" 2>/dev/null | tail -n 1 || true)"
  if [[ -z "$end_line" ]]; then
    echo "no_end"
    return 0
  fi
  if [[ "$end_line" == *" win."* ]]; then
    echo "my"
  elif [[ "$end_line" == *" lose."* ]]; then
    echo "monte"
  elif [[ "$end_line" == *"draw"* || "$end_line" == *"DRAW"* ]]; then
    echo "draw"
  else
    echo "unknown"
  fi
}

# Worker: handles matches worker_id+1, worker_id+1+PARALLEL, ...
worker() {
  local worker_id="$1"
  local port=$((PORT_BASE + worker_id))
  local worker_csv="${OUT_DIR}/worker_${worker_id}.csv"

  echo "game,worker,host,port,connect_order,my_mode,my_seconds,monte_seconds,server_timeout,winner,my_exit,monte_exit" >"$worker_csv"

  local game="$((worker_id + 1))"
  while (( game <= GAMES )); do
    local order="my-first"
    if (( game % 2 == 0 )); then
      order="monte-first"
    fi

    local server_log="${OUT_DIR}/server_w${worker_id}_g${game}.log"
    local my_log="${OUT_DIR}/my_w${worker_id}_g${game}.log"
    local monte_log="${OUT_DIR}/monte_w${worker_id}_g${game}.log"

    # Start server with GUI monitor (will open one window per worker/server).
    java $JAVA_MONITOR_OPTS -jar Server/OthelloServer.jar -port "$port" -timeout "$SERVER_TIMEOUT" -monitor >"$server_log" 2>&1 &
    local server_pid="$!"

    if ! wait_port "$HOST" "$port" 120; then
      echo "[ERROR] Worker ${worker_id}: server failed on ${HOST}:${port} (game ${game})" >>"$server_log"
      kill_if_alive "$server_pid"
      echo "${game},${worker_id},${HOST},${port},${order},${MY_MODE},${MY_SECONDS},${MONTE_SECONDS},${SERVER_TIMEOUT},server_no_port,124,124" >>"$worker_csv"
      game=$((game + PARALLEL))
      continue
    fi

    # If GUI is not available, Java often throws a HeadlessException; make it visible.
    if rg -n "HeadlessException|No X11 DISPLAY|java\\.awt\\." "$server_log" >/dev/null 2>&1; then
      echo "[WARN] Worker ${worker_id}: server monitor failed to open (headless?). See: ${server_log}" >&2
    fi

    local my_cmd=(java -cp Src OthelloClientAI "$HOST" "$port" "$MY_MODE" "$MY_SECONDS")
    local monte_cmd=(java -cp TestSet OthelloMonteAI "$HOST" "$port" "$MONTE_SECONDS")

    if [[ "$order" == "my-first" ]]; then
      "${my_cmd[@]}" >"$my_log" 2>&1 &
      local my_pid="$!"
      sleep 0.1
      "${monte_cmd[@]}" >"$monte_log" 2>&1 &
      local monte_pid="$!"
    else
      "${monte_cmd[@]}" >"$monte_log" 2>&1 &
      local monte_pid="$!"
      sleep 0.1
      "${my_cmd[@]}" >"$my_log" 2>&1 &
      local my_pid="$!"
    fi

    local my_ec=0
    local monte_ec=0

    set +e
    wait_pid_timeout "$my_pid" "$WALL_TIMEOUT"
    my_ec="$?"
    if [[ "$my_ec" == "124" ]]; then
      kill_if_alive "$my_pid"
    fi
    wait_pid_timeout "$monte_pid" "$WALL_TIMEOUT"
    monte_ec="$?"
    if [[ "$monte_ec" == "124" ]]; then
      kill_if_alive "$monte_pid"
    fi
    set -e

    # Stop server for this match to avoid random re-matching / disconnect-forfeit edge cases.
    kill_if_alive "$server_pid"

    local winner
    winner="$(parse_winner_from_my_log "$my_log")"
    echo "${game},${worker_id},${HOST},${port},${order},${MY_MODE},${MY_SECONDS},${MONTE_SECONDS},${SERVER_TIMEOUT},${winner},${my_ec},${monte_ec}" >>"$worker_csv"

    game=$((game + PARALLEL))
  done
}

echo "[INFO] Running GAMES=${GAMES} with PARALLEL=${PARALLEL} (ports ${PORT_BASE}..$((PORT_BASE + PARALLEL - 1)))"

pids=()
for w in $(seq 0 $((PARALLEL - 1))); do
  worker "$w" &
  pids+=("$!")
done

fail=0
for pid in "${pids[@]}"; do
  if ! wait "$pid"; then
    fail=1
  fi
done

SUMMARY_CSV="${OUT_DIR}/summary.csv"
echo "game,worker,host,port,connect_order,my_mode,my_seconds,monte_seconds,server_timeout,winner,my_exit,monte_exit" >"$SUMMARY_CSV"
for w in $(seq 0 $((PARALLEL - 1))); do
  tail -n +2 "${OUT_DIR}/worker_${w}.csv" >>"$SUMMARY_CSV" || true
done

if [[ "$fail" == "1" ]]; then
  echo "[WARN] Some workers failed; see logs under ${OUT_DIR}" >&2
fi

echo "[DONE] Summary: ${SUMMARY_CSV}"
