import java.awt.Point;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Pure network communication client that delegates move decisions to {@link OthelloMyAI}.
 *
 * Teacher server protocol (observed from Server/OthelloServer.jar):
 * - Client sends: "NICK <name>"
 * - Server sends: "START 1" or "START -1"
 * - Server sends: "BOARD <64 integers>"
 * - Server sends: "TURN <color>" where <color> is 1 or -1
 * - Client responds on its turn: "PUT x y"
 * - Game end: "END ..."
 */
public class OthelloClientAI {
    /**
     * Turn on local debug output (stderr).
     * Keep this false for normal matches to avoid noisy logs.
     */
    private static final boolean DEBUG = true;

    /**
     * Enable node-count statistics output (stderr).
     *
     * This does NOT affect the protocol messages sent to the server.
     * It only prints local diagnostics so you can compare:
     * - alpha-beta baseline (current stage)
     * - alpha-beta + transposition table (next stage)
     */
    private static final boolean STATS = true;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage:");
            System.err.println("  java OthelloClientAI <host> <port> [tt|ab|my] [seconds]");
            System.err.println("  java OthelloClientAI <port> [tt|ab|my] [seconds]   (host defaults to localhost)");
            System.exit(2);
        }

	        String host = "localhost";
	        int port;
	        int tailStart;

	        // Parse head:
	        // - If first arg is numeric => treat as <port> and host defaults to localhost
	        // - Otherwise => treat as <host> <port>
	        if (isInt(args[0])) {
	            port = Integer.parseInt(args[0]);
	            tailStart = 1;
	        } else {
	            if (args.length < 2) {
	                System.err.println("Usage:");
	                System.err.println("  java OthelloClientAI <host> <port> [tt|ab|my] [seconds]");
	                System.err.println("  java OthelloClientAI <port> [tt|ab|my] [seconds]");
	                System.exit(2);
	                return;
	            }
	            host = args[0];
	            port = Integer.parseInt(args[1]);
	            tailStart = 2;
	        }

        // Tail:
        // - optional [mode]
        // - optional [seconds] (recognized only if it's the last argument and numeric)
        String mode = "tt";
        double seconds = 1.0; // default per-move time budget
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
                System.err.println("Usage:");
                System.err.println("  java OthelloClientAI <host> <port> [tt|ab|my] [seconds]");
                System.err.println("  java OthelloClientAI <port> [tt|ab|my] [seconds]");
                System.exit(2);
                return;
        }

        long timeLimitMillis = secondsToMillis(seconds);
        ai.setTimeLimitMillis(timeLimitMillis);

        int[][] board = new int[8][8];
        int myColor = 0;

        // Node-count statistics for this match (local only).
        long totalNodes = 0;
        long maxNodes = 0;
        int myTurns = 0;

        try (Socket socket = new Socket(host, port);
             BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter pw = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

            pw.println("NICK " + ai.nickname());

            String startLine = br.readLine();
            if (startLine == null) return;
            String[] startParts = startLine.trim().split("\\s+");
            if (startParts.length >= 2 && startParts[0].equalsIgnoreCase("START")) {
                myColor = Integer.parseInt(startParts[1]);
            } else if (startParts.length >= 2) {
                // Backward-compatible with older clients that expected "<msg> <color>"
                myColor = Integer.parseInt(startParts[1]);
            } else {
                throw new IllegalStateException("Unexpected server start line: " + startLine);
            }

            for (String line; (line = br.readLine()) != null; ) {
                if (line.startsWith("BOARD ")) {
                    // Parsing is independent from the AI implementation; keep it local to the client.
                    board = parseBoardPayload(line.substring("BOARD ".length()));
                    continue;
                }

                if (line.equals("TURN " + myColor)) {
                    Point move = ai.chooseMove(board, myColor);
                    if (move == null) {
                        // Server normally won't ask when no move exists; if it does, fail closed.
                        pw.println("CLOSE");
                        break;
                    }
                    long nodes = ai.getLastSearchNodes();
                    totalNodes += nodes;
                    myTurns++;
                    maxNodes = Math.max(maxNodes, nodes);

                    if (DEBUG) {
                        System.err.println("AI chose: (" + move.x + "," + move.y + "), nodes=" + nodes);
                    } else if (STATS) {
                        long avg = totalNodes / myTurns;
                        System.err.println("TURN#" + myTurns + " nodes=" + nodes + " avg=" + avg + " max=" + maxNodes);
                    }
                    pw.println("PUT " + move.x + " " + move.y);
                    continue;
                }

                if (line.startsWith("END")) {
                    System.out.println(line);
                    if (STATS && myTurns > 0) {
                        long avg = totalNodes / myTurns;
                        System.err.println("STATS myTurns=" + myTurns + " totalNodes=" + totalNodes + " avgNodes=" + avg + " maxNodes=" + maxNodes);
                    }
                    // Be polite: tell server we're done before closing the socket.
                    // Some server implementations log "connection reset" if the client closes immediately.
                    pw.println("CLOSE");
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Parse the server BOARD payload into board[x][y]. */
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
