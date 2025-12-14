
// import java.awt.*;
// import java.awt.event.*;
// import javax.swing.*;

// class OthelloClient extends JFrame{
//     final static int BLACK = 1;
//     final static int WHITE = -1;

//     private JTextField tf;
//     private JTextArea ta;
//     private JLabel label;
//     private OthelloCanvas canvas;

//     public OthelloClient() {
// 	this.setSize(640,320);
// 	this.addWindowListener(new WindowAdapter() {
// 		public void windowClosing(WindowEvent e)  {
// 		    /* ウインドウが閉じられた時の処理 */
// 		    System.exit(0);
// 		}
// 	    });	 
// 	tf = new JTextField(40);
// 	tf.addActionListener(new ActionListener(){
// 		public void actionPerformed(ActionEvent e){
// 		    /* テキストフィールドに文字が入力された時の処理 */
// 		    if(tf.getText().equals("quit")){
// 			System.exit(0);
// 		    }

// 		    //ここに送信部分追加

// 		    System.out.println(tf.getText());
// 		    tf.setText(""); //テキストフィールドの文字を初期化
// 		}
// 	    }
// 	    );
// 	ta = new JTextArea(18,40);
// 	ta.setLineWrap(true);
// 	ta.setEditable(false);
// 	label = new JLabel();
	
// 	JPanel mainp = (JPanel)getContentPane();
// 	JPanel ep = new JPanel();
// 	JPanel wp = new JPanel();
// 	canvas = new OthelloCanvas();
// 	GridLayout gl = new GridLayout(1,2);
// 	gl.setHgap(5);
// 	mainp.setLayout(gl);
// 	ep.setLayout(new BorderLayout());
// 	ep.add(new JScrollPane(ta),BorderLayout.CENTER);
// 	ep.add(tf,BorderLayout.SOUTH);
// 	wp.setLayout(new BorderLayout());
// 	wp.add(label,BorderLayout.SOUTH);
// 	wp.add(canvas,BorderLayout.CENTER);
// 	mainp.add(wp);
// 	mainp.add(ep);
// 	this.setVisible(true);

// 	//受信部分追加

//     }

//     public static void main(String args[]) {
// 	new OthelloClient();
//     }
// }

// class OthelloCanvas extends JPanel {
//     private final static int startx = 20;
//     private final static int starty = 10;
//     private final static int gap = 30;
//     private byte[][] board ={
// 	{0,0,0,0,0,0,0,0},
// 	{0,0,0,0,0,0,0,0},
// 	{0,0,0,0,0,0,0,0},
// 	{0,0,0,1,-1,0,0,0},
// 	{0,0,0,-1,1,0,0,0},
// 	{0,0,0,0,0,0,0,0},
// 	{0,0,0,0,0,0,0,0},
// 	{0,0,0,0,0,0,0,0}
//     };  //サンプルデータ

//     public OthelloCanvas(){
// 	this.addMouseListener(new MouseAdapter() {
// 		public void mousePressed(MouseEvent e) {
// 		    /* 盤目上でマウスがクリックされた時の処理 */
// 		    Point p = e.getPoint();
// 		    System.out.println(""+p); //デバッグ用表示

// 		    //ここに送信部分追加
// 		}
// 	    });
//     }

//     public void paintComponent(Graphics g){
// 	g.setColor(new Color(0,180,0));
// 	g.fillRect(startx,starty,gap*8,gap*8);

// 	g.setColor(Color.BLACK);
// 	for(int i=0;i<9;i++){
// 	    g.drawLine(startx,starty+i*gap,startx+8*gap,starty+i*gap);
// 	    g.drawLine(startx+i*gap,starty,startx+i*gap,starty+8*gap);
// 	}
// 	for(int i=0;i<8;i++){
// 	    for(int j=0;j<8;j++){
// 		if(board[i][j]==OthelloClient.BLACK){
// 		    g.setColor(Color.BLACK);
// 		    g.fillOval(startx+gap*i,starty+gap*j,gap,gap);
// 		}else if(board[i][j]==OthelloClient.WHITE){
// 		    g.setColor(Color.WHITE);
// 		    g.fillOval(startx+gap*i,starty+gap*j,gap,gap);
// 		}
// 	    }
// 	}
//     }
// }