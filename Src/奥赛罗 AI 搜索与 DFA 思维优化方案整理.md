

## 0. 项目背景与目标

- 语言与环境：
    
    - 使用 Java 实现奥赛罗（Reversi）AI
        
    - 在老师提供的奥赛罗服务器环境中对弈/测试
        
- 当前核心：传统 αβ 剪枝搜索（Minimax + αβ）
    
- 你的主要想法：
    

> 在搜索过程中，不同落子顺序往往会到达**相同的棋面状态**。  
> 能否用「类似 DFA 的有限状态思维」，把“相同状态”识别并共享，从而：
> 
> - 避免重复计算子树
>     
> - 在同样时间内搜索更深的层数或更多分支
>     

这在博弈搜索中的标准概念就是：  
**转置表（Transposition Table）+ 状态编码（如 Zobrist 哈希）**，从“搜索树”转向“搜索图”。

---

## 1. 基础结构：奥赛罗 AI 的逻辑模块

### 1.1 棋盘与状态表示

典型状态包含：

- 当前棋盘布局
    
- 当前行动方（黑/白）
    
- 轮次信息（可选，用于评估或开局表）
    

在代码中通常有：

- `Board` 类（或 `int[][] board` 等）
    
- 常用操作：
    
    - 生成合法着法集合：`List<Move> getLegalMoves(Player p)`
        
    - 执行落子并生成新棋面：`Board applyMove(Move m, Player p)`
        
    - 判断终局：`boolean isTerminal()`
        
    - 评估局面：`int evaluate(Player me)`
        

### 1.2 评估函数（Evaluation）

基本版评估函数可能包括：

- 盘面子数差：`myDiscs - oppDiscs`
    
- 行动力（mobility）：`myMoves - oppMoves`
    
- 盘面位置权重（如角、边、中心等不同权值）
    

这一部分在后续可以与「DFA/模式识别」结合做强化，但现在先作为 baseline。

### 1.3 αβ 剪枝搜索

基本递归结构（伪代码）：

`int alphaBeta(Board state, int depth, int alpha, int beta, Player current) {     if (depth == 0 || state.isTerminal()) {         return evaluate(state);     }      List<Move> moves = state.getLegalMoves(current);     if (moves.isEmpty()) {         // 无棋可下，可能 pass 或终局         // 视规则处理：要么返回 evaluate，要么切换行动方递归     }      if (current == MAX_PLAYER) {         int value = -INF;         for (Move m : moves) {             Board next = state.applyMove(m, current);             value = Math.max(value,                 alphaBeta(next, depth - 1, alpha, beta, opposite(current)));             alpha = Math.max(alpha, value);             if (alpha >= beta) break; // β 剪枝         }         return value;     } else {         int value = +INF;         for (Move m : moves) {             Board next = state.applyMove(m, current);             value = Math.min(value,                 alphaBeta(next, depth - 1, alpha, beta, opposite(current)));             beta = Math.min(beta, value);             if (alpha >= beta) break; // α 剪枝         }         return value;     } }`

这个版本**不记忆任何状态**，所有路径下来的状态都“重新算”。

---

## 2. “DFA 思维”的核心：把树变成图

### 2.1 把奥赛罗当作巨大的有限状态机（DFA）

在概念层面，可以这样形式化：

- 状态集合 Q：所有可能的棋盘局面 + 行动方
    
- 字母表 Σ：所有可能合法落子（包括 pass）
    
- 转移函数 δ：  
    `δ(状态, 落子) = 下一状态`（按奥赛罗规则翻子）
    
- 初始状态 q₀：开局局面（4 子摆放 + 先手）
    
- 接受状态 F：终局局面集合（双方都无合法着法）
    

αβ 搜索本质上是在这个巨大的 DFA 上进行有限深度的枚举，只是：

- 传统实现是“树形展开”，不主动共享状态
    
- 你的目标是显式地构建“访问过的状态子图”，让所有路径共享相同节点
    

### 2.2 转置表的作用

**Transposition Table = 运行时构建的“已访问状态表”**

- key：代表棋面状态的编码（例如一个 `long` 型哈希值）
    
- value：该状态的搜索结果信息（见 3.2）
    

在 αβ 搜索中的使用策略：

- 每进入一个递归节点前：
    
    - 根据当前 state 计算 key
        
    - 在转置表中查询
        
        - 若有足够深度的结果，直接复用，无需展开子树
            
- 每返回一个递归节点时：
    
    - 根据最终得到的节点值（和搜索深度等信息），写回转置表
        

这样，**任何路径到达同一状态，都只需一次真正的搜索**，其他路径都只做查表。

---

## 3. 状态编码：Zobrist 哈希与等价归约

### 3.1 基本 Zobrist 哈希

为了在 Java 中高效给棋盘编码，常用 Zobrist 哈希方案：

- 预生成一个随机表 `zobrist[64][3]`：
    
    - 64 = 棋盘格子数
        
    - 3 = {空, 黑, 白} 三种状态
        
    - 每个元素是一个 64bit 随机数（`long`）
        
- 计算哈希：
    
    - 初始 hash = 0
        
    - 遍历棋盘所有格子 `(i, j)`：
        
        - 根据该格子-状态取一个随机数
            
        - `hash ^= zobrist[index][stateId];`
            
    - 再把 “当前行动方” 也编码进去（例如再 XOR 1 个随机数）
        

利用点：

- 每次落子 & 翻子时，可以**增量更新哈希**，不必重算整盘：
    
    - 某格子从状态 A 变为状态 B：
        
        - `hash ^= zobrist[pos][A];`
            
        - `hash ^= zobrist[pos][B];`
            

这个 `hash` 就是状态在“巨 DFA”中的节点 ID。

### 3.2 转置表项的内容设计

在 Java 中可以定义：

`class TTEntry {     long key;        // 哈希值，用于碰撞检查     int depth;       // 记录搜索到的深度     int value;       // 评估值（以当前根玩家视角）     byte flag;       // EXACT / LOWERBOUND / UPPERBOUND     Move bestMove;   // 当前状态下搜索得到的最佳着法（可选） }`

使用方式：

- flag:
    
    - EXACT：这个值是准确值
        
    - LOWERBOUND：值 ≥ value（通常来自 β-cut 分支）
        
    - UPPERBOUND：值 ≤ value（通常来自 α-cut 分支）
        
- 查表时：
    
    - 若 `entry.key == hash` 且 `entry.depth >= 当前所需深度`：
        
        - 根据 flag 决定如何更新当前 α/β 或直接返回值
            

---

## 4. DFA 思维的“升级版”：状态等价与商自动机

在自动机理论中，如果两个状态对所有后缀输入都表现一致，可以合并为一个等价类状态（商自动机）。

在奥赛罗中，完全等价难以判断，但可以引入两个实用的“近似等价”思想：

### 4.1 棋盘对称性归约（Symmetry Canonicalization）

奥赛罗棋盘有 8 种对称变换（旋转 + 镜像），许多局面对称后本质上是一样的。

做法：

- 实现一个 `Board canon(Board s)`：
    
    - 对 s 应用 8 种对称变换
        
    - 将每个变换后的棋面编码为串
        
    - 选择字典序最小的那一个作为“规范代表”
        
- 哈希 key 不直接对原棋面做，而是对 `canon(s)` 做
    

效果：

- 所有对称等价的局面会被映射到同一哈希 key → 同一 TT 项
    
- 从自动机观点看，就是对状态集合做了一个“按对称等价类划分”的商自动机
    

代价：

- 需要额外的对称变换与比较开销
    
- 但在搜索较深时，对称局面数量巨大，这种合并能增加 TT 命中率、减少存储
    

### 4.2 抽象状态 / 局部模式（可选扩展）

更进一步，可以只保留某些局部特征作为“抽象状态”：

- 例如：
    
    - 各角是否已被占（己方/对方/空）
        
    - 四条边是“稳定边”还是“易被翻转边”
        
    - 行动力（合法着法数）大致分为“低/中/高”三档
        
- 把这些特征组成有限个状态：
    
    - `AbstractState = (cornerPattern, edgePattern, mobilityClass, ...)`
        

这样，除了 TT 记录“精确状态”的搜索值外，你还可以维护一个：

`Map<AbstractState, AbstractStats>`

例如：

- 在多次搜索中统计某个 AbstractState 出现时的平均评价、胜率范围
    
- 在搜索中遇到某个 AbstractState 时，可以：
    
    - 用这些统计作为评估函数的一部分
        
    - 或在某些极端情况下直接启发式剪枝（例如“强劣势模式”）
        

这一步就很接近“把整个游戏状态压缩到一个较小的 DFA 上”，  
不过它更像是一个**抽象解释 / 模式识别**层，属于后期的研究型扩展。

---

## 5. 与评估函数的结合：基于模式的 DFA 线扫描（可选扩展）

除了状态共享，你还可以把 DFA 思维放到**局面评估**里：

- 把每一条行/列/对角线视作一个长度 8 的字符串：
    
    - 字母表 Σ = {E (空), B (黑), W (白)}
        
- 许多评估特征本质上是“模式识别”，例：
    
    - `B...B` 且两端无 W → 近似稳定子
        
    - `E B+ W` → 潜在翻转 / 行动力点
        
    - `W+ B` → 受压局面等等
        

可以为某一类模式构造 DFA：

- 输入：线上的 8 个格子状态序列
    
- 状态：记录“当前读到的模式前缀”和“累积的特征计数”
    
- 输出：一条线对评估函数的贡献（特征值）
    

实现上可以：

- 设计一个状态转移表（数组），使得一趟扫描一条线就同时统计多个特征
    
- 全局评估值 = 对所有行/列/对角线调用该 DFA 并累加
    

好处：

- 把本来分散在多重 `for + if` 中的逻辑集中到状态机
    
- 对应自动机课程中的：
    
    - 正则模式 → NFA → DFA → 最小化 → 以表驱动方式实现
        

这适合作为项目的“第二阶段优化”：在已有 αβ+TT 之上，进一步用 DFA 改写评估函数内部逻辑。

---

## 6. 实际开发中的分阶段计划（建议）

### 阶段 1：现有 Java 奥赛罗 AI 的基线整理

- 梳理并清理：
    
    - 棋盘表示类 `Board`
        
    - 搜索函数 `alphaBeta(...)`
        
    - 评估函数 `evaluate(...)`
        
- 加入简单的节点计数统计（便于后续做对比）
    

### 阶段 2：实现转置表（核心：避免重复状态搜索）

- 实现 Zobrist 哈希（全局随机表 + 增量更新）
    
- 定义 `TTEntry` 类结构
    
- 在 `alphaBeta` 中加入：
    
    - 节点入口：查表 + 提前返回/更新 αβ
        
    - 节点出口：写表 + 更新 bestMove
        
- 做实验：
    
    - 固定搜索深度，比较有/无 TT 时的：
        
        - 节点数
            
        - 耗时
            
        - 搜索到的最佳着法是否一致
            

### 阶段 3：加入对称归约（可选）

- 实现 8 种对称变换与 `canon(Board)` 函数
    
- 把 TT key 切换为 `hash(canon(board))`
    
- 再次进行对比实验，看：
    
    - TT 命中率变化
        
    - 节点数 / 时间变化
        

### 阶段 4：探索 DFA 化的评估 / 抽象状态（兴趣扩展）

- 选一种模式（例如边线模式、稳定子识别），构造 DFA 实现
    
- 将其嵌入评估函数中，对比：
    
    - 评估时间
        
    - 对实战棋力的影响（自对弈或对简单 AI 的胜率）