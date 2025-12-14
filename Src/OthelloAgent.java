import java.awt.Point;

/**
 * Minimal common interface so the client can swap AI implementations via args.
 *
 * All implementations must:
 * - decide a move for a given board and color
 * - expose the node count for the last search (for comparison/benchmarking)
 */
public interface OthelloAgent {
    String nickname();

    /**
     * @param board board[x][y]
     * @param myColor 1 (black) or -1 (white)
     * @return chosen move (x,y), or null if no legal move exists
     */
    Point chooseMove(int[][] board, int myColor);

    /** Node counter for the most recent {@link #chooseMove(int[][], int)}. */
    long getLastSearchNodes();
}
