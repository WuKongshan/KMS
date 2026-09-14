package com.kms.analyser.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny rule-expression engine for {@code t_alert_rule} condition expressions
 * (spec 4.3). Grammar:
 *
 * <pre>
 * expr    := orExpr
 * orExpr  := andExpr (("||" | "or") andExpr)*
 * andExpr := unary   (("&amp;&amp;" | "and") unary)*
 * unary   := "!" unary | comparison
 * comparison := primary (("&gt;" | "&gt;=" | "&lt;" | "&lt;=" | "==" | "!=") primary)?
 * primary := NUMBER | IDENT | "(" expr ")"
 * </pre>
 *
 * <p>IDENT resolves against the variable map (value, upper_threshold,
 * lower_threshold, volatility, volatility_threshold). A comparison against an
 * unknown (null) variable never fires — a missing threshold cannot be
 * breached. The result is boolean; bare numbers count as true when non-zero.
 *
 * <p>【中文】自研的极简规则表达式引擎：把 {@code t_alert_rule.expression} 字段里的
 * 字符串（例如 {@code value > upper_threshold}）解析成 AST 并求值。
 *
 * <p>【中文】为什么不用 SpEL / Nashorn 这类现成脚本引擎：表达式本身是数据库里的数据，
 * 任何拿到达梦写权限的人都能改它，交给脚本引擎求值等价于把"数据库写权限"提升为
 * "远程代码执行（RCE）"。本解析器只认识 6 种 token（NUMBER / IDENT / OP / LPAREN /
 * RPAREN / END）和 6 种节点（Number / Var / Cmp / Not / And / Or），没有反射、
 * 方法调用、类型引用与 IO，攻击面为零。
 *
 * <p>【中文】文法共 5 层，完整 EBNF 见上面的英文段落，这里用中文复述一遍：
 * expr 就是 orExpr；orExpr 由 andExpr 与 "||"/"or" 循环组成；andExpr 由 unary 与
 * "&amp;&amp;"/"and" 循环组成；unary 是 "!" 递归或一次 comparison；comparison 的两个操作数
 * 都只能是 primary；primary 是数字、标识符或用括号包起来的整个 expr。越靠下的规则优先级越高。
 *
 * <p>【中文】优先级靠"调用层级"编码：{@code parseOr()} 调 {@code parseAnd()}，
 * 再调 {@code parseUnary()}、{@code parsePrimary()}——谁在调用链外层谁的优先级就低，
 * 所以 {@code a || b &amp;&amp; c} 自然解析为 {@code a || (b &amp;&amp; c)}，无需优先级表。
 *
 * <p>【中文】核心设计是三值逻辑：{@code Node.eval()} 返回 {@code Double} 而不是 boolean，
 * 用 {@code null} 表示"未知"。变量查不到返回 null → CmpNode 返回 null →
 * {@code truthy(null)} 为 false。这样当 {@code disk_free} 的 upper_threshold 在库里是
 * NULL 时，{@code value > upper_threshold} 永不触发：既避免了 NPE，也避免了把 null
 * 当成 0 参与比较而引发的误报洪水。
 */
public final class RuleExpression {

    // AST 缓存：key 是表达式字符串，value 是解析好的语法树。
    // 表达式存在数据库里基本不变，而分析循环每 30 秒就要为每个指标求值一次，
    // 缓存把"每轮重新解析"摊薄成"只解析一次"。
    // 用 ConcurrentHashMap 是因为定时任务线程之间可能并发进入；
    // 缓存本身是安全的：AST 只取决于表达式字符串，与变量取值无关（变量在 eval 时才传入）。
    private static final Map<String, Node> CACHE = new ConcurrentHashMap<>();

    // 工具类：私有构造，禁止实例化（对外只暴露静态方法 evaluate）
    private RuleExpression() {
    }

    /**
     * Evaluate an expression against the given variable bindings.
     *
     * <p>【中文】对给定的变量绑定求值：先以表达式字符串为 key 查 AST 缓存（未命中才解析），
     * 再对 AST 求值，最后统一经 {@code truthy()} 把三值结果折算成布尔值。
     *
     * <p>【中文】变量不在绑定表里不算错误，只会让结果"未知"（即 false）；
     * 只有表达式存在语法错误时才抛异常。
     *
     * @throws IllegalArgumentException on syntax errors
     */
    public static boolean evaluate(String expression, Map<String, Double> variables) {
        // computeIfAbsent 是原子的：并发下同一表达式也只会解析一次；
        // 语法错误时异常向外抛、不写缓存，所以规则在库里改好之后立刻恢复，无需重启
        Node ast = CACHE.computeIfAbsent(expression, RuleExpression::parse);
        // eval 返回的是三值 Double，必须经 truthy 折算：null 与 0.0 → false，其余 → true
        return truthy(ast.eval(variables));
    }

    // ------------------------------------------------------------------
    // AST
    // ------------------------------------------------------------------
    // AST 节点层：共 6 种节点，全部是 private 内部类型，外部只能通过 evaluate 使用。
    // 所有节点的 eval 都返回 Double——1.0 为真、0.0 为假、null 为"未知"（三值逻辑）。

    private interface Node {
        /**
         * Result as a double (comparisons yield 1.0/0.0); null = unknown.
         *
         * <p>【中文】求值结果为 double：比较运算产出 1.0 / 0.0，null 表示"未知"
         * （变量缺失，或阈值列在数据库里是 NULL）。
         */
        Double eval(Map<String, Double> vars);
    }

    // 数字字面量节点：直接返回字面量本身，永远是"已知"的
    private record NumberNode(double value) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            return value;
        }
    }

    // 变量节点：从变量绑定表里按名字取值。取不到（没绑定，或变量名写错）就返回 null，
    // "未知"会沿着 AST 向上传播，最终被 truthy 判为 false
    private record VarNode(String name) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            // Map.get 未命中返回 null，正好就是三值逻辑里"未知"的表示，无需额外判断
            return vars.get(name);
        }
    }

    // 比较节点：左右子树先各自求值，只要有一侧"未知"整体就"未知"
    private record CmpNode(String op, Node left, Node right) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            Double l = left.eval(vars);
            Double r = right.eval(vars);
            if (l == null || r == null) {
                return null; // unknown operand -> comparison does not fire
                // 任一操作数未知（如阈值列为 NULL）→ 结果也是"未知"，比较不成立
            }
            // 比较成立返回 1.0、不成立返回 0.0，统一用 double 承载三值逻辑；
            // == / != 用的是 Double.equals 而非 ==：NaN 与 ±0.0 的语义按包装类型规则走
            return switch (op) {
                case ">" -> l > r ? 1.0 : 0.0;
                case ">=" -> l >= r ? 1.0 : 0.0;
                case "<" -> l < r ? 1.0 : 0.0;
                case "<=" -> l <= r ? 1.0 : 0.0;
                case "==" -> l.equals(r) ? 1.0 : 0.0;
                case "!=" -> !l.equals(r) ? 1.0 : 0.0;
                default -> throw new IllegalStateException("Unknown operator: " + op);
            };
        }
    }

    // 取反节点：子表达式"未知"时结果仍是"未知"，
    // 刻意不把未知强行取反成 true（否则 !(value > upper_threshold) 会在无阈值时误报）
    private record NotNode(Node child) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            Double v = child.eval(vars);
            if (v == null) {
                return null;
            }
            return truthy(v) ? 0.0 : 1.0;
        }
    }

    // 与节点：短路求值——左子树只要不为真（含"未知"）就直接返回 0.0，右子树不再求值
    private record AndNode(Node left, Node right) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            if (!truthy(left.eval(vars))) {
                return 0.0; // short-circuit; unknown counts as false
                // 短路：左操作数为假就不再计算右侧；"未知"在这里按假处理
            }
            return truthy(right.eval(vars)) ? 1.0 : 0.0;
        }
    }

    // 或节点：同样短路——左子树为真就直接返回 1.0，右子树不再求值
    private record OrNode(Node left, Node right) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            if (truthy(left.eval(vars))) {
                return 1.0;
            }
            return truthy(right.eval(vars)) ? 1.0 : 0.0;
        }
    }

    // 唯一的"真相判定入口"：null（未知）与 0.0 都算假，其余算真。
    // 所有布尔语义都必须经过这里，避免各处对 null 做出不一致的解释。
    private static boolean truthy(Double v) {
        // v != null 让"未知"短路为 false；v != 0.0 会自动拆箱，按数值比较
        return v != null && v != 0.0;
    }

    // ------------------------------------------------------------------
    // Tokenizer
    // ------------------------------------------------------------------
    // 词法分析：把表达式字符串切成 token 列表。
    // 每个 token 都携带 pos（在原始字符串中的起始下标），仅用于报错定位，不参与语法分析。

    // 6 种 token 类型：数字、标识符（变量名或 and/or 关键字）、算符、
    // 左括号、右括号，以及末尾的 END 哨兵
    private enum TokenType { NUMBER, IDENT, OP, LPAREN, RPAREN, END }

    // token 三元组：(类型, 原始文本, 起始位置)
    private record Token(TokenType type, String text, int pos) {
    }

    private static List<Token> tokenize(String expression) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                // 跳过空白：库里的表达式可能被排版成多行或带多余空格
                i++;
                continue;
            }
            // 数字字面量：12、3.14 都支持，也支持省略整数部分的 .5；
            // 首字符判定里 "." 后面必须紧跟数字，否则单独的 '.' 会走下面的非法字符分支
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(expression.charAt(i + 1)))) {
                int start = i;
                while (i < n && (Character.isDigit(expression.charAt(i)) || expression.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(TokenType.NUMBER, expression.substring(start, i), start));
                continue;
            }
            // 标识符：首字符允许字母或下划线——下划线必须算首字符，
            // 否则 upper_threshold 会被切成 upper 和 threshold 两段，变量名彻底解析错
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(expression.charAt(i)) || expression.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(TokenType.IDENT, expression.substring(start, i), start));
                continue;
            }
            // two-char operators first
            // 必须先尝试双字符算符，否则 ">=" 会被拆成 ">" 和 "="，语义完全不同
            if (i + 1 < n) {
                String two = expression.substring(i, i + 2);
                if (two.equals(">=") || two.equals("<=") || two.equals("==") || two.equals("!=")
                        || two.equals("&&") || two.equals("||")) {
                    tokens.add(new Token(TokenType.OP, two, i));
                    i += 2;
                    continue;
                }
            }
            // 双字符分支没命中，剩下的就是单字符算符与括号
            switch (c) {
                case '>', '<', '!' -> tokens.add(new Token(TokenType.OP, String.valueOf(c), i));
                case '(' -> tokens.add(new Token(TokenType.LPAREN, "(", i));
                case ')' -> tokens.add(new Token(TokenType.RPAREN, ")", i));
                default -> throw new IllegalArgumentException("Unexpected character '" + c
                        + "' at position " + i + " in expression: " + expression);
            }
            i++;
        }
        // 末尾追加 END 哨兵：有了它，parser 的 peek()/next() 就不必做越界判断，
        // "表达式提前结束"会自然表现为"读到了 END"，归入统一的语法错误处理
        tokens.add(new Token(TokenType.END, "", n));
        return tokens;
    }

    // ------------------------------------------------------------------
    // Parser (recursive descent)
    // ------------------------------------------------------------------
    // 语法分析：递归下降，优先级由调用层级编码（外层调用 = 低优先级）。

    private static final class Parser {

        // source 是原始表达式文本，只在拼装报错信息时用到；tokens 是词法分析的产物
        private final String source;
        private final List<Token> tokens;
        // 当前读取位置（token 列表下标）；解析过程中只前进、不回退
        private int pos;

        Parser(String source, List<Token> tokens) {
            this.source = source;
            this.tokens = tokens;
        }

        // orExpr := andExpr (("||" | "or") andExpr)* ——规则里优先级最低的一层。
        // 循环左结合：反复用"已解析的左子树 + 新解析的右子树"构造 OrNode，
        // 所以 a || b || c 得到 ((a||b)||c)；算符与关键字 or 两种写法等价。
        Node parseOr() {
            Node left = parseAnd();
            while (isOp("||") || isWord("or")) {
                next();
                left = new OrNode(left, parseAnd());
            }
            return left;
        }

        // andExpr := unary (("&&" | "and") unary)* ——AND 层级比 OR 深一层，
        // 因此 "&&" 的优先级高于 "||"（a || b && c 解析为 a || (b && c)）
        private Node parseAnd() {
            Node left = parseUnary();
            while (isOp("&&") || isWord("and")) {
                next();
                left = new AndNode(left, parseUnary());
            }
            return left;
        }

        // unary := "!" unary | comparison ——比较运算两侧只能是 primary，
        // 刻意不支持 a > b > c 这类链式比较：它会在 parse() 的"多余 token"检查处
        // 直接报语法错误，而不是产生一个有歧义的结果
        private Node parseUnary() {
            // "!" 右递归，所以 !!a 这类写法按右结合解析
            if (isOp("!")) {
                next();
                return new NotNode(parseUnary());
            }
            Node left = parsePrimary();
            Token t = peek();
            // 前瞻：下一个 token 若为比较算符，就消费它并构造 CmpNode，
            // 两个操作数都只解析到 primary 层，所以不会形成链式比较
            if (t.type() == TokenType.OP && isComparison(t.text())) {
                next();
                return new CmpNode(t.text(), left, parsePrimary());
            }
            // 后面没跟比较算符就直接返回 primary：裸变量/裸数字也是合法表达式（非 0 即真）
            return left;
        }

        // primary := NUMBER | IDENT | "(" expr ")" ——文法里优先级最高的一层
        private Node parsePrimary() {
            Token t = next();
            switch (t.type()) {
                case NUMBER -> {
                    return new NumberNode(Double.parseDouble(t.text()));
                }
                case IDENT -> {
                    return new VarNode(t.text());
                }
                case LPAREN -> {
                    // 括号内重新从最低优先级的 parseOr() 开始解析
                    // ——这正是括号能提升优先级的原因；再强制右括号配对
                    Node inner = parseOr();
                    expect(TokenType.RPAREN);
                    return inner;
                }
                default -> throw error("Expected number, variable or '(' but got '"
                        + t.text() + "'", t);
            }
        }

        // 6 种比较算符的判定表，tokenize 与 parser 用的是同一套写法
        private static boolean isComparison(String op) {
            return op.equals(">") || op.equals(">=") || op.equals("<") || op.equals("<=")
                    || op.equals("==") || op.equals("!=");
        }

        // 前瞻判断：只看当前 token 不消费（LL(1)），配合 next() 决定是否进入循环
        private boolean isOp(String op) {
            Token t = peek();
            return t.type() == TokenType.OP && t.text().equals(op);
        }

        // 关键字判定（and / or）大小写不敏感：AND、Or 都能识别
        private boolean isWord(String word) {
            Token t = peek();
            return t.type() == TokenType.IDENT && t.text().equalsIgnoreCase(word);
        }

        // peek 只看不取、next 取走并前移；因为末尾有 END 哨兵，这里不会下标越界
        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        // expect 断言下一个 token 的类型，用于强制括号闭合等结构性要求
        private void expect(TokenType type) {
            Token t = next();
            if (t.type() != type) {
                throw error("Expected " + type + " but got '" + t.text() + "'", t);
            }
        }

        // 统一构造语法错误：消息 + position + 原始表达式，
        // 这样日志里能直接看出是库里哪条规则的第几个字符写错了
        private IllegalArgumentException error(String message, Token t) {
            return new IllegalArgumentException(message + " at position " + t.pos()
                    + " in expression: " + source);
        }
    }

    // 解析入口：词法分析 → 语法分析 → 校验 token 恰好耗尽。
    // parseOr() 返回后必须正好停在 END 上，否则说明有多余内容
    // （例如 "value > 5 5"、缺少算符），此时报错而不是默默忽略。
    private static Node parse(String expression) {
        Parser parser = new Parser(expression, tokenize(expression));
        Node node = parser.parseOr();
        Token end = parser.next();
        if (end.type() != TokenType.END) {
            // 表达式没被完全消费：报"多余 token"并带上它的位置
            throw parser.error("Unexpected token '" + end.text() + "'", end);
        }
        return node;
    }
}
