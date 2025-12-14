import java.awt.Point;
import java.io.*;
import java.net.*;
import java.util.*;

class OthelloAI_6323041{
    private static final int[][] weightMatrix = {
        {120, -20,  20,   5,   5,  20, -20, 120}, 
        {-20, -40,  -5,  -5,  -5,  -5, -40, -20}, 
        { 20,  -5,  15,   3,   3,  15,  -5,  20},
        {  5,  -5,   3,   3,   3,   3,  -5,   5},
        {  5,  -5,   3,   3,   3,   3,  -5,   5},
        { 20,  -5,  15,   3,   3,  15,  -5,  20},
        {-20, -40,  -5,  -5,  -5,  -5, -40, -20},
        {120, -20,  20,   5,   5,  20, -20, 120}
    };

     private static final int[][] DIRECTIONS = {
        {-1, 0}, {-1, 1},{-1, -1},
        {0, 1}, {0, -1},
        {1, 1},{1, 0}, {1, -1}
    };
    
    //read board from stringstream
    public static int[][] getboard(String boardStr){
        int[][] rboard = new int[8][8];
        String[] dataStr = boardStr.trim().split("\\s+");
        
        if (dataStr.length != 64) {
            System.err.println("Error: Board string does not contain 64 numbers! Found: " + dataStr.length);
            return rboard;
        }
        int k = 0;
        for(int i = 0; i < 8; i++){
            for(int j = 0; j < 8; j++){
                rboard[j][i] = Integer.parseInt(dataStr[k]);
                k++;
            }
        }
        return  rboard;
    }
    
    //check if (r,c)is in bound
    private static boolean isInBounds(int r, int c) {
        return r >= 0 && r < 8 && c >= 0 && c < 8;
    }

    //get legal positions for this board
    public static Map<Point, Integer> getLegalPos(int[][] board, int my){
        Map<Point, Integer> legalPos = new HashMap<>();

        int op = (-1) * my;
        //find legal pos
        for(int r = 0; r < 8; r++){
            for(int c = 0; c < 8; c++){
                if(board[r][c] != 0){
                    continue;
                }
                //socre for this pos
                int score = 0;

                //loop for ever dir
                for(int[] dir : DIRECTIONS){
                    int dr = dir[0];
                    int dc = dir[1];

                    //go next pos
                    int scoreOndir = 0;
                    int currentR = r + dr;
                    int currentC = c + dc;

                    //if in board and is op color 
                    //score on this dir +1 
                    //move on more step
                    while(isInBounds(currentR, currentC) && board[currentR][currentC] == op){
                        scoreOndir++;
                        currentR += dr;
                        currentC += dc;
                    }

                    //if meet my color and met op color
                    //this dir is ok add scoreOn this line to score for this pos
                    if(scoreOndir > 0 && isInBounds(currentR, currentC) && board[currentR][currentC] == my){
                        score += scoreOndir;
                    }
                }

                //if got score add this pos to legal pos
                if(score > 0){
                    legalPos.put(new Point(c, r), score);
                }
            }
        }
        return legalPos;
    }

    //copy board to simulation
    public static int[][] copyBoard(int[][] board){
        //copy board
        int[][] newBoard = new int[8][8];
        for (int i = 0; i < 8; i++) {
            System.arraycopy(board[i], 0, newBoard[i], 0, 8);
        }
        return newBoard;    
    }
    
    //get board after put in (put.x, put.y)
    public static void simBoard(int[][] newBoard, Point put, int my, int op){
        //put on newBoard
        int r = put.x;
        int c = put.y;
        newBoard[r][c] = my;

        //loop for ever dir
        for(int[] dir : DIRECTIONS){
            int dr = dir[0];
            int dc = dir[1];

            //go next pos
            int currentR = r + dr;
            int currentC = c + dc;

            //if in board and is op color 
            //move on more step
            boolean foundOp = false;
            while(isInBounds(currentR, currentC) && newBoard[currentR][currentC] == op){
                foundOp = true;
                currentR += dr;
                currentC += dc;
            }

            //if meet my color and met op color
            //inverse them
            if(foundOp && isInBounds(currentR, currentC) && newBoard[currentR][currentC] == my){
                int flipR = r + dr;
                int flipC = c + dc;
                while(flipR != currentR || flipC != currentC){
                    newBoard[flipR][flipC] = my;
                    flipR += dr;
                    flipC += dc;
                }
            }
        }
    }


    public static Point getBestPos(Map<Point, Integer> legalPos,int[][] board, int my){
        Point bestPos = new Point(0, 0);
        int op = (-1)*my;
        int bestScore = Integer.MIN_VALUE;

        for (Map.Entry<Point, Integer> entry : legalPos.entrySet()) {
            int[][] newBoard = copyBoard(board);

            //put this step
            simBoard(newBoard, entry.getKey(), my, op);

            //found op`s possable put numbers
            Map<Point, Integer> opPoss = getLegalPos(newBoard, op);
            int opMoveNum = opPoss.size();
            int currentScore = weightMatrix[entry.getKey().x][entry.getKey().y] + entry.getValue() - (opMoveNum * 5);

            if( currentScore > bestScore){
                bestPos = entry.getKey(); 
                bestScore = currentScore;
            }
        }
        return bestPos;
    }

    public static void main(String[] args) {
        Socket s;
        InputStream sIn;
        OutputStream sOut;
        BufferedReader br;
        PrintWriter pw;
        StringTokenizer stn;
        int[][] board = new int[8][8];

        try {
            s = new Socket(args[0], Integer.parseInt(args[1]));

            sIn = s.getInputStream();
            sOut = s.getOutputStream();
            br = new BufferedReader(new InputStreamReader(sIn));
            pw = new PrintWriter(new OutputStreamWriter(sOut),true);

            pw.println("NICK 6323041");
                        
            String serverResponse = br.readLine();

            stn = new StringTokenizer(serverResponse, " ",false);
            String msg = stn.nextToken();
            String color = stn.nextToken();
            System.out.println("Message = " + msg);
            System.out.println("Color = " + color);

            while(true){
                serverResponse = br.readLine();
                if((serverResponse) == null){
                    System.out.println("over");
                    break;
                }

                //System.out.println("Server: " + serverResponse);
                if(serverResponse.startsWith("BOARD ")){
                    String boardStr = serverResponse.substring("BOARD ".length());
                    board = getboard(boardStr);
                }else if(serverResponse.equals("TURN " + color)){
                    int mycolor = Integer.parseInt(color);
                    Map<Point, Integer> legalPos = getLegalPos(board, mycolor);
                    Point putPos =  getBestPos(legalPos, board, mycolor);
                    pw.println("PUT " + putPos.x + " " + putPos.y);
                    pw.flush();
                }else if(serverResponse.equals("ERROR 3")) {
                    System.out.println("NOT My Turn");
                }else if(serverResponse.equals("ERROR 4")||serverResponse.equals("ERROR 2")){
                    System.out.println("Command Error");
                }else if(serverResponse.equals("ERROR 1")){
                    System.out.println("Syntax Error");
                }else if(serverResponse.startsWith("END")){
                    System.out.println(serverResponse);
                    break;
                }else if(serverResponse.startsWith("CLOSE")){
                    System.out.println(serverResponse);
                }
            }
        } catch (IOException e) {
            System.err.println("Caught IOException");
			System.exit(1);
        }
    }
}
