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
    private static final boolean DEBUG = false;

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
        if (args.length < 2) {
            System.err.println("Usage: java OthelloClientAI <host> <port>");
            System.exit(2);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);

        OthelloMyAI ai = new OthelloMyAI();
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
                    board = OthelloMyAI.parseBoardPayload(line.substring("BOARD ".length()));
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
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
            System.exit(1);
        }
    }
}
