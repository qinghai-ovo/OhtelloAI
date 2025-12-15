#!/usr/bin/env bash
set -euo pipefail

# Batch benchmark runner:
# - Runs N matches between TestSet/OthelloMonteAI (10s) and your client AI in tt mode (3s)
# - Assumes you already started the server (default: localhost:25033)
# - Saves per-match logs and copies Server/score_desktop.txt for later analysis
#
# Usage examples:
#   ./RunBenchmarks.sh 20
#   HOST=localhost PORT=25033 GAMES=30 MONTE_SECONDS=10 MY_SECONDS=3 MY_MODE=tt ./RunBenchmarks.sh
#
# Notes:
# - Requires Java in PATH.
# - Will compile Src/*.java once (outputs *.class).

GAMES="${1:-${GAMES:-20}}"
HOST="${HOST:-localhost}"
PORT="${PORT:-25033}"
MONTE_SECONDS="${MONTE_SECONDS:-10}"
MY_SECONDS="${MY_SECONDS:-3}"
MY_MODE="${MY_MODE:-tt}"
SWAP_ORDER="${SWAP_ORDER:-1}" # 1=alternate who connects first

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

timestamp="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="benchmarks/${timestamp}-monte${MONTE_SECONDS}s-vs-${MY_MODE}${MY_SECONDS}s"
mkdir -p "$OUT_DIR"

SUMMARY_CSV="${OUT_DIR}/summary.csv"
echo "game,host,port,connect_order,my_mode,my_seconds,monte_seconds,my_nick,monte_nick,my_w,my_l,monte_w,monte_l,winner" >"$SUMMARY_CSV"

wait_port() {
  local host="$1"
  local port="$2"
  local tries="${3:-80}" # ~8s with 0.1 sleep
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

expected_my_nick() {
  case "$MY_MODE" in
    ab|alphabeta|baseline) echo "AlphaBeta" ;;
    *) echo "ABTT" ;;
  esac
}

echo "[INFO] Output: ${OUT_DIR}"
echo "[INFO] Compiling Src/*.java ..."
javac -encoding UTF-8 Src/*.java

echo "[INFO] Using external server at ${HOST}:${PORT}"
if ! wait_port "$HOST" "$PORT" 100; then
  echo "[ERROR] Server is not reachable at ${HOST}:${PORT}" >&2
  exit 1
fi

for game in $(seq 1 "$GAMES"); do
  port="$PORT"
  order="my-first"
  if [[ "$SWAP_ORDER" == "1" ]] && (( game % 2 == 0 )); then
    order="monte-first"
  fi

  echo "[INFO] Game ${game}/${GAMES} on ${HOST}:${port} (${order})"

  rm -f Server/score_desktop.txt || true
  my_log="${OUT_DIR}/my_${game}.log"
  monte_log="${OUT_DIR}/monte_${game}.log"

  # Commands:
  my_cmd=(java -cp Src OthelloClientAI "$HOST" "$port" "$MY_MODE" "$MY_SECONDS")
  monte_cmd=(java -cp TestSet OthelloMonteAI "$HOST" "$port" "$MONTE_SECONDS")

  # Start clients (order can affect who is black/white depending on server implementation).
  if [[ "$order" == "my-first" ]]; then
    "${my_cmd[@]}" >"$my_log" 2>&1 &
    my_pid="$!"
    sleep 0.1
    "${monte_cmd[@]}" >"$monte_log" 2>&1 &
    monte_pid="$!"
  else
    "${monte_cmd[@]}" >"$monte_log" 2>&1 &
    monte_pid="$!"
    sleep 0.1
    "${my_cmd[@]}" >"$my_log" 2>&1 &
    my_pid="$!"
  fi

  set +e
  wait "$my_pid"
  my_ec="$?"
  wait "$monte_pid"
  monte_ec="$?"
  set -e

  # Give the server a moment to reset its internal match state before next game.
  sleep 0.5

  score_src="Server/score_desktop.txt"
  score_dst="${OUT_DIR}/score_${game}.csv"
  if [[ -f "$score_src" ]]; then
    cp "$score_src" "$score_dst"
  fi

  # With an external server, score file may be cumulative across games.
  # For per-game outcome, prefer parsing the END line from your client log.
  my_nick="$(expected_my_nick)"
  monte_nick=""
  my_w=""
  my_l=""
  monte_w=""
  monte_l=""
  winner=""

  end_line="$(rg -n \"^END \" \"$my_log\" 2>/dev/null | tail -n 1 | sed 's/^.*END /END /' || true)"
  if [[ -n "$end_line" ]]; then
    if [[ "$end_line" == *" win."* ]]; then
      winner="my"
    elif [[ "$end_line" == *" lose."* ]]; then
      winner="monte"
    else
      winner="unknown"
    fi
  elif [[ -f "$score_dst" ]]; then
    line1="$(head -n 1 "$score_dst" | tr -d '\r')"
    line2="$(tail -n 1 "$score_dst" | tr -d '\r')"

    IFS=',' read -r n1 w1 l1 <<<"$line1"
    IFS=',' read -r n2 w2 l2 <<<"$line2"

    if [[ "$n1" == "$my_nick" ]]; then
      my_w="$w1"; my_l="$l1"; monte_nick="$n2"; monte_w="$w2"; monte_l="$l2"
    elif [[ "$n2" == "$my_nick" ]]; then
      my_w="$w2"; my_l="$l2"; monte_nick="$n1"; monte_w="$w1"; monte_l="$l1"
    else
      # Fallback: assume one side is MonteAI_*
      if [[ "$n1" == MonteAI_* ]]; then
        monte_nick="$n1"; monte_w="$w1"; monte_l="$l1"
        my_nick="$n2"; my_w="$w2"; my_l="$l2"
      else
        monte_nick="$n2"; monte_w="$w2"; monte_l="$l2"
        my_nick="$n1"; my_w="$w1"; my_l="$l1"
      fi
    fi

    winner="cumulative_score_file"
  else
    winner="no_score_file"
  fi

  echo "${game},${HOST},${port},${order},${MY_MODE},${MY_SECONDS},${MONTE_SECONDS},${my_nick},${monte_nick},${my_w},${my_l},${monte_w},${monte_l},${winner}" >>"$SUMMARY_CSV"

  if [[ "$my_ec" != "0" || "$monte_ec" != "0" ]]; then
    echo "[WARN] Non-zero exit codes: my=${my_ec} monte=${monte_ec} (game ${game}). See logs in ${OUT_DIR}" >&2
  fi
done

echo "[DONE] Summary: ${SUMMARY_CSV}"
