#!/usr/bin/env python3
import argparse
import csv
import glob
import os
import signal
import socket
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from threading import Event, Thread

sys.dont_write_bytecode = True

def wait_port(host: str, port: int, timeout_s: float = 12.0) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=0.5):
                return True
        except OSError:
            time.sleep(0.1)
    return False

def wait_server_ready(log_path: Path, timeout_s: float = 12.0) -> bool:
    """
    DO NOT probe readiness by opening a TCP connection: the teacher server treats every TCP
    connection as a player, which would corrupt matchmaking. Instead, wait for the server to
    print its startup banner into its own log.
    """
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            text = log_path.read_text(errors="ignore")
            if "Othello Server" in text and "tcp port:" in text:
                return True
        except FileNotFoundError:
            pass
        time.sleep(0.1)
    return False

def kill_tree(proc: subprocess.Popen, grace_s: float = 0.3) -> None:
    if proc is None:
        return
    try:
        pgid = os.getpgid(proc.pid)
    except Exception:
        pgid = None

    def _kill(sig):
        try:
            if pgid is not None:
                os.killpg(pgid, sig)
            else:
                proc.send_signal(sig)
        except Exception:
            pass

    _kill(signal.SIGTERM)
    time.sleep(grace_s)
    _kill(signal.SIGKILL)

def parse_winner_from_log(path: Path) -> str:
    if not path.exists():
        return "no_log"
    winner = "no_end"
    for line in path.read_text(errors="ignore").splitlines():
        if line.startswith("END "):
            if " win." in line:
                winner = "my"
            elif " lose." in line:
                winner = "monte"
            elif "draw" in line.lower():
                winner = "draw"
            else:
                winner = "unknown"
    return winner

def tee_lines(proc: subprocess.Popen, log_path: Path, prefix: str, stop_event: Event) -> None:
    with log_path.open("w", encoding="utf-8", errors="replace") as fp:
        assert proc.stdout is not None
        for line in proc.stdout:
            fp.write(line)
            fp.flush()
            if not stop_event.is_set():
                print(f"{prefix}{line}", end="", flush=True)

def wait_end_in_log(path: Path, timeout_s: float) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            if "END " in path.read_text(errors="ignore"):
                return True
        except FileNotFoundError:
            pass
        time.sleep(0.1)
    return False

def run_one_game(game: int, worker: int, host: str, port: int, out_dir: Path,
                 my_mode: str, my_seconds: float, monte_seconds: int,
                 server_timeout: int, wall_timeout_s: int, monitor: bool,
                 stream_server_log: bool, stop_event: Event):
    order = "my-first" if game % 2 == 1 else "monte-first"
    server_log = out_dir / f"server_w{worker}_g{game}.log"
    my_log = out_dir / f"my_w{worker}_g{game}.log"
    monte_log = out_dir / f"monte_w{worker}_g{game}.log"

    server_cmd = ["java", "-Djava.awt.headless=false", "-jar", "Server/OthelloServer.jar",
                  "-port", str(port), "-timeout", str(server_timeout)]
    if monitor:
        server_cmd.append("-monitor")

    if stop_event.is_set():
        return dict(game=game, worker=worker, host=host, port=port, connect_order=order,
                    my_mode=my_mode, my_seconds=my_seconds, monte_seconds=monte_seconds,
                    server_timeout=server_timeout, winner="stopped",
                    my_exit=130, monte_exit=130)

    server = subprocess.Popen(
        server_cmd,
        stdout=subprocess.PIPE if stream_server_log else server_log.open("w"),
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        start_new_session=True,
    )

    tee_thread = None
    if stream_server_log:
        tee_thread = Thread(
            target=tee_lines,
            args=(server, server_log, f"[S w{worker} g{game}] ", stop_event),
            daemon=True,
        )
        tee_thread.start()

    try:
        if not wait_server_ready(server_log, timeout_s=12.0):
            return dict(game=game, worker=worker, host=host, port=port, connect_order=order,
                        my_mode=my_mode, my_seconds=my_seconds, monte_seconds=monte_seconds,
                        server_timeout=server_timeout, winner="server_no_port",
                        my_exit=124, monte_exit=124)

        # Use the benchmark client wrapper that does not send CLOSE/exit on END.
        # The server will be stopped by this script after END is observed.
        my_cmd = ["java", "-cp", "Src", "OthelloClientBench", host, str(port), my_mode, str(my_seconds)]
        monte_cmd = ["java", "-cp", "TestSet", "OthelloMonteAI", host, str(port), str(monte_seconds)]

        if order == "my-first":
            my = subprocess.Popen(my_cmd, stdout=my_log.open("w"), stderr=subprocess.STDOUT, start_new_session=True)
            time.sleep(0.1)
            monte = subprocess.Popen(monte_cmd, stdout=monte_log.open("w"), stderr=subprocess.STDOUT, start_new_session=True)
        else:
            monte = subprocess.Popen(monte_cmd, stdout=monte_log.open("w"), stderr=subprocess.STDOUT, start_new_session=True)
            time.sleep(0.1)
            my = subprocess.Popen(my_cmd, stdout=my_log.open("w"), stderr=subprocess.STDOUT, start_new_session=True)

        # Wait until END appears in my_log (or timeout), then stop the server so both clients exit.
        got_end = wait_end_in_log(my_log, timeout_s=wall_timeout_s)
        if not got_end:
            kill_tree(my)
            kill_tree(monte)
            return dict(game=game, worker=worker, host=host, port=port, connect_order=order,
                        my_mode=my_mode, my_seconds=my_seconds, monte_seconds=monte_seconds,
                        server_timeout=server_timeout, winner="no_end",
                        my_exit=124, monte_exit=124)

        kill_tree(server)

        def wait_short(p: subprocess.Popen) -> int:
            try:
                return p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                kill_tree(p)
                return 124

        my_ec = wait_short(my)
        monte_ec = wait_short(monte)

        winner = parse_winner_from_log(my_log)
        return dict(game=game, worker=worker, host=host, port=port, connect_order=order,
                    my_mode=my_mode, my_seconds=my_seconds, monte_seconds=monte_seconds,
                    server_timeout=server_timeout, winner=winner,
                    my_exit=my_ec, monte_exit=monte_ec)
    finally:
        # server may already be killed after END; safe to call twice.
        kill_tree(server)
        if tee_thread is not None:
            tee_thread.join(timeout=1.0)

def worker_loop(worker: int, games: int, parallel: int, host: str, port_base: int, out_dir: Path,
                my_mode: str, my_seconds: float, monte_seconds: int,
                server_timeout: int, wall_timeout_s: int, monitor: bool,
                stream_server_log: bool, stop_event: Event):
    port = port_base + worker
    results = []
    game = worker + 1
    while game <= games and not stop_event.is_set():
        results.append(run_one_game(
            game=game,
            worker=worker,
            host=host,
            port=port,
            out_dir=out_dir,
            my_mode=my_mode,
            my_seconds=my_seconds,
            monte_seconds=monte_seconds,
            server_timeout=server_timeout,
            wall_timeout_s=wall_timeout_s,
            monitor=monitor,
            stream_server_log=stream_server_log,
            stop_event=stop_event,
        ))
        game += parallel
    return results

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("games", nargs="?", type=int, default=20)
    ap.add_argument("--parallel", type=int, default=10)
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port-base", type=int, default=25033)
    ap.add_argument("--my-mode", default="tt")
    ap.add_argument("--my-seconds", type=float, default=3.0)
    ap.add_argument("--monte-seconds", type=int, default=10)
    ap.add_argument("--server-timeout", type=int, default=12)
    ap.add_argument("--wall-timeout", type=int, default=1200)
    ap.add_argument("--monitor", action="store_true")
    ap.add_argument("--no-stream-server-log", action="store_true", help="do not print server logs in real time")
    args = ap.parse_args()

    ts = time.strftime("%Y%m%d-%H%M%S")
    out_dir = Path("benchmarks") / f"{ts}-py-parallel{args.parallel}-monte{args.monte_seconds}s-vs-{args.my_mode}{args.my_seconds}s"
    out_dir.mkdir(parents=True, exist_ok=True)
    summary_csv = out_dir / "summary.csv"

    sources = sorted(glob.glob("Src/*.java"))
    if not sources:
        raise SystemExit("[ERROR] No Java sources found under Src/*.java")
    subprocess.check_call(["javac", "-encoding", "UTF-8", *sources])

    stop_event = Event()
    def _on_signal(_sig, _frame):
        stop_event.set()
    signal.signal(signal.SIGINT, _on_signal)
    signal.signal(signal.SIGTERM, _on_signal)

    results = []
    try:
        with ThreadPoolExecutor(max_workers=args.parallel) as ex:
            futures = [
                ex.submit(
                    worker_loop,
                    worker,
                    args.games,
                    args.parallel,
                    args.host,
                    args.port_base,
                    out_dir,
                    args.my_mode,
                    args.my_seconds,
                    args.monte_seconds,
                    args.server_timeout,
                    args.wall_timeout,
                    args.monitor,
                    (not args.no_stream_server_log),
                    stop_event,
                )
                for worker in range(args.parallel)
            ]
            for f in as_completed(futures):
                results.extend(f.result())
    except KeyboardInterrupt:
        print("[WARN] Interrupted, partial results will be written.")
    finally:
        results.sort(key=lambda r: r["game"])
        with summary_csv.open("w", newline="") as fp:
            w = csv.DictWriter(fp, fieldnames=[
                "game","worker","host","port","connect_order",
                "my_mode","my_seconds","monte_seconds","server_timeout",
                "winner","my_exit","monte_exit"
            ])
            w.writeheader()
            w.writerows(results)
        print(f"[DONE] {summary_csv}")

if __name__ == "__main__":
    main()
