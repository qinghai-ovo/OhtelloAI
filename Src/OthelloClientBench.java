import java.awt.Point;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Benchmark-friendly client:
 * - Selects an AI implementation like {@link OthelloClientAI}
 * - DOES NOT send "CLOSE" on END, and does not exit on END
 *   (teacher server treats disconnect/CLOSE as a forfeit in its continuous-matchmaking mode).
 *
 * Intended usage: run one game per dedicated server instance, then let the benchmark harness
 * terminate the server so this client exits when the socket closes.
 */
public class OthelloClientBench {
    private static final boolean DEBUG = false;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java OthelloClientBench <host> <port> [tt|ab|my] [seconds]");
            System.exit(2);
        }

        String host;
        int port;
        int tailStart;

        if (isInt(args[0])) {
            host = "localhost";
            port = Integer.parseInt(args[0]);
            tailStart = 1;
        } else {
            host = args[0];
            port = Integer.parseInt(args[1]);
            tailStart = 2;
        }

        String mode = "tt";
        double seconds = 1.0;
        int tailEnd = args.length;
        if (tailEnd > tailStart) {
            Double sec = tryParseDouble(args[tailEnd - 1]);
            if (sec != null) {
                seconds = sec;
                tailEnd--;
            }
        }
        if (tailEnd > tailStart) {
            mode = args[tailStart].trim().toLowerCase();
        }

        OthelloAgent ai;
        switch (mode) {
            case "tt":
            case "transposition":
                ai = new OthelloTTAI();
                break;
            case "my":
                ai = new OthelloMyAI();
                break;
            case "ab":
            case "alphabeta":
            case "baseline":
                ai = new OthelloAlphaBetaAI();
                break;
            default:
                System.err.println("Unknown AI mode: " + mode);
                System.err.println("Usage: java OthelloClientBench <host> <port> [tt|ab|my] [seconds]");
                System.exit(2);
                return;
        }

        ai.setTimeLimitMillis(secondsToMillis(seconds));

        int[][] board = new int[8][8];
        int myColor = 0;

        try (Socket socket = new Socket(host, port);
             BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter pw = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

            pw.println("NICK " + ai.nickname());

            for (String line; (line = br.readLine()) != null; ) {
                if (line.startsWith("START ")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        myColor = Integer.parseInt(parts[1]);
                    }
                    continue;
                }

                if (line.startsWith("BOARD ")) {
                    board = parseBoardPayload(line.substring("BOARD ".length()));
                    continue;
                }

                if (line.equals("TURN " + myColor)) {
                    Point move = ai.chooseMove(board, myColor);
                    if (move == null) {
                        // If server ever asks when no move exists, send an invalid move is worse than staying silent.
                        // Fall back to CLOSE (rare; depends on server behavior).
                        pw.println("CLOSE");
                        break;
                    }
                    if (DEBUG) {
                        System.err.println("AI chose: (" + move.x + "," + move.y + ")");
                    }
                    pw.println("PUT " + move.x + " " + move.y);
                    continue;
                }

                if (line.startsWith("END")) {
                    System.out.println(line);
                    // Do NOT send CLOSE; keep waiting until the benchmark harness closes the server/socket.
                    continue;
                }
            }
        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int[][] parseBoardPayload(String payload) {
        int[][] board = new int[8][8];
        String[] parts = payload.trim().split("\\s+");
        if (parts.length != 64) return board;
        int k = 0;
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                board[x][y] = Integer.parseInt(parts[k++]);
            }
        }
        return board;
    }

    private static Double tryParseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long secondsToMillis(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return 1000L;
        double ms = seconds * 1000.0;
        if (ms > Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, (long) ms);
    }
}

