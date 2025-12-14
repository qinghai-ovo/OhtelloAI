import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Pure AI logic (no networking).
 *
 * Coordinate convention in this project (matches the teacher server):
 * - board[x][y]
 * - send move as: "PUT x y"
 *
 * Stage 1 baseline (per your md plan):
 * - Clear board representation + move generation + apply-move
 * - Add a basic evaluation function
 * - Replace the old plain recursion with alpha-beta pruning
 * - Add node counting statistics (for later comparison)
 */
public class OthelloMyAI implements OthelloAgent {
    private static final int SIZE = 8;

    private static final int[][] WEIGHT = {
            {120, -20, 20, 5, 5, 20, -20, 120},
            {-20, -40, -5, -5, -5, -5, -40, -20},
            {20, -5, 15, 3, 3, 15, -5, 20},
            {5, -5, 3, 3, 3, 3, -5, 5},
            {5, -5, 3, 3, 3, 3, -5, 5},
            {20, -5, 15, 3, 3, 15, -5, 20},
            {-20, -40, -5, -5, -5, -5, -40, -20},
            {120, -20, 20, 5, 5, 20, -20, 120}
    };

    private static final int[][] DIRECTIONS = {
            {-1, 0}, {-1, 1}, {-1, -1},
            {0, 1}, {0, -1},
            {1, 1}, {1, 0}, {1, -1}
    };

    /**
     * Search depth for alpha-beta.
     * A small depth is safer for the teacher server time limits; tune later.
     */
    private static final int SEARCH_DEPTH = 10;

    /**
     * Transposition table (TT) hard cap.
     * This implementation uses a simple HashMap; when it grows too large we clear it to avoid OOM.
     *
     * If you want a stronger TT later:
     * - replace with a fixed-size array + replacement scheme
     * - or persist across turns instead of clearing every chooseMove()
     */
    private static final int TT_MAX_SIZE = 200_000;

    /**
     * Zobrist hashing:
     * - Each (square, pieceColor) pair has a random 64-bit number.
     * - The board key is XOR of all occupied squares plus an extra "side-to-move" random.
     *
     * Notes:
     * - We only need numbers for black/white; empty squares contribute nothing.
     * - We use a fixed seed so results are deterministic across runs (easier debugging/comparison).
     */
    private static final long[][] ZOBRIST = new long[64][2]; // [pos][0=black,1=white]
    private static final long Z_SIDE_BLACK;
    private static final long Z_SIDE_WHITE;

    static {
        SplittableRandom r = new SplittableRandom(0x6323041L);
        for (int i = 0; i < 64; i++) {
            ZOBRIST[i][0] = r.nextLong();
            ZOBRIST[i][1] = r.nextLong();
        }
        Z_SIDE_BLACK = r.nextLong();
        Z_SIDE_WHITE = r.nextLong();
    }

    /**
     * Node counter for the most recent call to {@link #chooseMove(int[][], int)}.
     * This is useful to verify that alpha-beta pruning is working and to compare with TT later.
     */
    private long lastSearchNodes = 0;

    /**
     * Per-search transposition table.
     * Key is the Zobrist hash of (board + side-to-move).
     */
    private Map<Long, TTEntry> tt = new HashMap<>();

    public String nickname() {
        return "ABTT";
    }

    /**
     * @param board board[x][y]
     * @param myColor 1 (black) or -1 (white)
     */
    public Point chooseMove(int[][] board, int myColor) {
        // New search => clear TT.
        // Keeping TT per-move avoids stale entries across different root positions,
        // and keeps behavior easy to reason about while you validate correctness.
        tt.clear();

        // Root: generate legal moves for "me". If none exist, return null.
        // (Teacher server usually avoids sending TURN when you have no moves,
        // but keeping this makes the AI logic complete.)
        List<Point> legalMoves = getLegalMoves(board, myColor);
        if (legalMoves.isEmpty()) {
            lastSearchNodes = 0;
            return null;
        }

        // Simple move ordering: evaluate by square weight (high first).
        // Ordering helps alpha-beta prune earlier.
        legalMoves.sort(Comparator.comparingInt((Point p) -> WEIGHT[p.x][p.y]).reversed());

        SearchStats stats = new SearchStats();
        Point bestMove = legalMoves.get(0);
        int bestValue = Integer.MIN_VALUE;

        // Standard alpha-beta at root.
        // Value is always from "myColor" perspective.
        int alpha = Integer.MIN_VALUE;
        int beta = Integer.MAX_VALUE;

        for (Point move : legalMoves) {
            int[][] next = copyBoard(board);
            applyMove(next, move, myColor);

            // After my move, opponent moves next.
            int value = alphaBeta(next, -myColor, SEARCH_DEPTH - 1, alpha, beta, myColor, stats, tt);
            if (value > bestValue) {
                bestValue = value;
                bestMove = move;
            }
            alpha = Math.max(alpha, bestValue);
        }

        lastSearchNodes = stats.nodes;
        return bestMove;
    }

    /** Read-only stats for external debugging (optional). */
    public long getLastSearchNodes() {
        return lastSearchNodes;
    }

    /** Parse the server BOARD payload into board[x][y]. */
    public static int[][] parseBoardPayload(String payload) {
        int[][] board = new int[SIZE][SIZE];
        String[] parts = payload.trim().split("\\s+");
        if (parts.length != 64) return board;
        int k = 0;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                board[x][y] = Integer.parseInt(parts[k++]);
            }
        }
        return board;
    }

    /**
     * Legal move generator (teacher rule set).
     * A move is legal if it flips >=1 opponent discs in any of the 8 directions.
     */
    public static List<Point> getLegalMoves(int[][] board, int myColor) {
        List<Point> moves = new ArrayList<>();
        int opColor = -myColor;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                if (board[x][y] != 0) continue;
                if (flipsIfPlace(board, x, y, myColor, opColor) > 0) {
                    moves.add(new Point(x, y));
                }
            }
        }
        return moves;
    }

    /**
     * Legacy helper kept for compatibility with older code and for potential evaluation features.
     * Returns move -> flipped count.
     */
    public static Map<Point, Integer> getLegalMovesWithFlipCount(int[][] board, int myColor) {
        Map<Point, Integer> legal = new HashMap<>();
        int opColor = -myColor;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                if (board[x][y] != 0) continue;
                int flips = flipsIfPlace(board, x, y, myColor, opColor);
                if (flips > 0) {
                    legal.put(new Point(x, y), flips);
                }
            }
        }
        return legal;
    }

    private static int flipsIfPlace(int[][] board, int x, int y, int myColor, int opColor) {
        int total = 0;
        for (int[] d : DIRECTIONS) {
            int dx = d[0];
            int dy = d[1];
            int cx = x + dx;
            int cy = y + dy;
            int count = 0;
            while (inBounds(cx, cy) && board[cx][cy] == opColor) {
                count++;
                cx += dx;
                cy += dy;
            }
            if (count > 0 && inBounds(cx, cy) && board[cx][cy] == myColor) {
                total += count;
            }
        }
        return total;
    }

    public static int[][] copyBoard(int[][] board) {
        int[][] next = new int[SIZE][SIZE];
        for (int x = 0; x < SIZE; x++) {
            System.arraycopy(board[x], 0, next[x], 0, SIZE);
        }
        return next;
    }

    /**
     * Apply a move to a board in-place.
     *
     * IMPORTANT:
     * - Coordinates are (x,y)
     * - Callers should only pass legal moves (otherwise it may place a disc without flips)
     */
    public static void applyMove(int[][] board, Point move, int myColor) {
        int opColor = -myColor;
        int x = move.x;
        int y = move.y;
        board[x][y] = myColor;

        for (int[] d : DIRECTIONS) {
            int dx = d[0];
            int dy = d[1];
            int cx = x + dx;
            int cy = y + dy;

            boolean foundOp = false;
            while (inBounds(cx, cy) && board[cx][cy] == opColor) {
                foundOp = true;
                cx += dx;
                cy += dy;
            }
            if (!foundOp || !inBounds(cx, cy) || board[cx][cy] != myColor) continue;

            int fx = x + dx;
            int fy = y + dy;
            while (fx != cx || fy != cy) {
                board[fx][fy] = myColor;
                fx += dx;
                fy += dy;
            }
        }
    }

    /**
     * Alpha-beta search (minimax with pruning), returning a value from the "myColor" perspective.
     *
     * Conventions:
     * - currentColor: player to move at this node
     * - maximizing player is myColor; minimizing player is -myColor
     *
     * Terminal / pass handling:
     * - If depth==0 => evaluate
     * - If current player has no legal move:
     *     - if opponent also has no legal move => terminal => evaluate
     *     - else => pass turn (same board), continue search
     */
    private static int alphaBeta(
            int[][] board,
            int currentColor,
            int depth,
            int alpha,
            int beta,
            int myColor,
            SearchStats stats,
            Map<Long, TTEntry> tt
    ) {
        stats.nodes++;

        // Depth-limited leaf.
        if (depth <= 0) {
            return evaluate(board, myColor);
        }

        // Compute TT key for this node (board + side to move).
        long key = computeKey(board, currentColor);

        // TT probe:
        // - If we have a stored result at sufficient depth, we can:
        //   - return EXACT immediately
        //   - or tighten alpha/beta with LOWER/UPPER bounds
        // - If the window becomes empty, we can cutoff early.
        TTEntry cached = null;
        if (tt != null) {
            stats.ttProbes++;
            cached = tt.get(key);
            if (cached != null && cached.depth >= depth) {
                stats.ttHits++;
                if (cached.flag == TTEntry.EXACT) {
                    return cached.value;
                }
                if (cached.flag == TTEntry.LOWERBOUND) {
                    alpha = Math.max(alpha, cached.value);
                } else if (cached.flag == TTEntry.UPPERBOUND) {
                    beta = Math.min(beta, cached.value);
                }
                if (alpha >= beta) {
                    stats.ttCutoffs++;
                    return cached.value;
                }
            }
        }

        // Remember the original window so we can set the TT flag on store.
        int alphaOrig = alpha;
        int betaOrig = beta;

        // Terminal checks.
        List<Point> moves = getLegalMoves(board, currentColor);
        if (moves.isEmpty() && getLegalMoves(board, -currentColor).isEmpty()) {
            int value = evaluate(board, myColor);
            storeTT(tt, key, depth, value, TTEntry.EXACT, null);
            return value;
        }

        // Pass move: no legal moves for current player, but opponent can move.
        if (moves.isEmpty()) {
            int value = alphaBeta(board, -currentColor, depth - 1, alpha, beta, myColor, stats, tt);
            storeTT(tt, key, depth, value, TTEntry.EXACT, null);
            return value;
        }

        // Move ordering: helps pruning. (Heuristic: square weight)
        moves.sort(Comparator.comparingInt((Point p) -> WEIGHT[p.x][p.y]).reversed());

        // If TT has a suggested bestMove, try it first (often improves alpha-beta cutoffs).
        if (cached != null && cached.bestMove != null) {
            int idx = moves.indexOf(cached.bestMove);
            if (idx > 0) {
                Point bm = moves.remove(idx);
                moves.add(0, bm);
            }
        }

        boolean isMax = (currentColor == myColor);
        Point bestMove = null;
        if (isMax) {
            int best = Integer.MIN_VALUE;
            for (Point m : moves) {
                int[][] next = copyBoard(board);
                applyMove(next, m, currentColor);
                int value = alphaBeta(next, -currentColor, depth - 1, alpha, beta, myColor, stats, tt);
                if (value > best) {
                    best = value;
                    bestMove = m;
                }
                alpha = Math.max(alpha, best);
                if (alpha >= beta) break; // beta cut
            }
            // Store TT with appropriate bound type (EXACT/LOWER/UPPER) based on the original window.
            storeTT(tt, key, depth, best, flagFromWindow(best, alphaOrig, betaOrig), bestMove);
            return best;
        } else {
            int best = Integer.MAX_VALUE;
            for (Point m : moves) {
                int[][] next = copyBoard(board);
                applyMove(next, m, currentColor);
                int value = alphaBeta(next, -currentColor, depth - 1, alpha, beta, myColor, stats, tt);
                if (value < best) {
                    best = value;
                    bestMove = m;
                }
                beta = Math.min(beta, best);
                if (alpha >= beta) break; // alpha cut
            }
            // Store TT with appropriate bound type (EXACT/LOWER/UPPER) based on the original window.
            storeTT(tt, key, depth, best, flagFromWindow(best, alphaOrig, betaOrig), bestMove);
            return best;
        }
    }

    /**
     * Convert a returned minimax value to a TT flag:
     * - If it fails low (<= alphaOrig): upper bound
     * - If it fails high (>= betaOrig): lower bound
     * - Otherwise: exact value inside window
     */
    private static byte flagFromWindow(int value, int alphaOrig, int betaOrig) {
        if (value <= alphaOrig) return TTEntry.UPPERBOUND;
        if (value >= betaOrig) return TTEntry.LOWERBOUND;
        return TTEntry.EXACT;
    }

    /**
     * Store (or update) a transposition table entry.
     *
     * Replacement policy (simple):
     * - If an entry exists at this key and its recorded depth is deeper, keep it.
     * - Otherwise overwrite.
     *
     * Memory policy (simple):
     * - When the map grows beyond TT_MAX_SIZE, clear everything.
     */
    private static void storeTT(Map<Long, TTEntry> tt, long key, int depth, int value, byte flag, Point bestMove) {
        if (tt == null) return;
        if (tt.size() > TT_MAX_SIZE) {
            tt.clear();
        }
        TTEntry e = tt.get(key);
        if (e == null) {
            e = new TTEntry();
            tt.put(key, e);
        } else if (e.depth > depth) {
            // Keep deeper results.
            return;
        }
        e.key = key;
        e.depth = depth;
        e.value = value;
        e.flag = flag;
        e.bestMove = bestMove;
    }

    /**
     * Compute a Zobrist key for the current node.
     *
     * Mapping from (x,y) to ZOBRIST index:
     * - idx increments with y inner loop, x outer loop: idx = x * 8 + y
     */
    private static long computeKey(int[][] board, int currentColor) {
        long h = 0L;
        int idx = 0;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                int v = board[x][y];
                if (v == 1) {
                    h ^= ZOBRIST[idx][0];
                } else if (v == -1) {
                    h ^= ZOBRIST[idx][1];
                }
                idx++;
            }
        }
        h ^= (currentColor == 1) ? Z_SIDE_BLACK : Z_SIDE_WHITE;
        return h;
    }

    private static boolean inBounds(int x, int y) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE;
    }

    /**
     * Stage 1 evaluation (baseline):
     * - Positional weights (static table)
     * - Mobility difference (number of legal moves)
     * - Disc difference (piece count)
     *
     * This is intentionally simple; later stages can introduce more features and TT.
     */
    private static int evaluate(int[][] board, int myColor) {
        int opColor = -myColor;

        int positional = 0;
        int myDiscs = 0;
        int opDiscs = 0;

        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                int v = board[x][y];
                if (v == myColor) {
                    positional += WEIGHT[x][y];
                    myDiscs++;
                } else if (v == opColor) {
                    positional -= WEIGHT[x][y];
                    opDiscs++;
                }
            }
        }

        // Mobility: legal move count difference.
        int mobility = getLegalMoves(board, myColor).size() - getLegalMoves(board, opColor).size();

        // Disc difference is more relevant near endgame; keeping a small weight in baseline.
        int discDiff = myDiscs - opDiscs;

        // Weights are deliberately modest to keep scale stable for alpha-beta.
        return positional + (mobility * 5) + discDiff;
    }

    /** Mutable stats holder passed through alpha-beta recursion. */
    private static final class SearchStats {
        long nodes = 0;
        long ttProbes = 0;
        long ttHits = 0;
        long ttCutoffs = 0;
    }

    /**
     * TT entry stored per (board + side-to-move) key.
     *
     * Flag meanings:
     * - EXACT: stored value is exact for this depth
     * - LOWERBOUND: true value >= stored value (fail-high)
     * - UPPERBOUND: true value <= stored value (fail-low)
     */
    private static final class TTEntry {
        static final byte EXACT = 0;
        static final byte LOWERBOUND = 1;
        static final byte UPPERBOUND = 2;

        long key;
        int depth;
        int value;
        byte flag;
        Point bestMove;
    }
}
