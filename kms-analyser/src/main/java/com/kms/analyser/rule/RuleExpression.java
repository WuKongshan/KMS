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
 */
public final class RuleExpression {

    private static final Map<String, Node> CACHE = new ConcurrentHashMap<>();

    private RuleExpression() {
    }

    /**
     * Evaluate an expression against the given variable bindings.
     *
     * @throws IllegalArgumentException on syntax errors
     */
    public static boolean evaluate(String expression, Map<String, Double> variables) {
        Node ast = CACHE.computeIfAbsent(expression, RuleExpression::parse);
        return truthy(ast.eval(variables));
    }

    // ------------------------------------------------------------------
    // AST
    // ------------------------------------------------------------------

    private interface Node {
        /** Result as a double (comparisons yield 1.0/0.0); null = unknown. */
        Double eval(Map<String, Double> vars);
    }

    private record NumberNode(double value) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            return value;
        }
    }

    private record VarNode(String name) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            return vars.get(name);
        }
    }

    private record CmpNode(String op, Node left, Node right) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            Double l = left.eval(vars);
            Double r = right.eval(vars);
            if (l == null || r == null) {
                return null; // unknown operand -> comparison does not fire
            }
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

    private record AndNode(Node left, Node right) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            if (!truthy(left.eval(vars))) {
                return 0.0; // short-circuit; unknown counts as false
            }
            return truthy(right.eval(vars)) ? 1.0 : 0.0;
        }
    }

    private record OrNode(Node left, Node right) implements Node {
        @Override
        public Double eval(Map<String, Double> vars) {
            if (truthy(left.eval(vars))) {
                return 1.0;
            }
            return truthy(right.eval(vars)) ? 1.0 : 0.0;
        }
    }

    private static boolean truthy(Double v) {
        return v != null && v != 0.0;
    }

    // ------------------------------------------------------------------
    // Tokenizer
    // ------------------------------------------------------------------

    private enum TokenType { NUMBER, IDENT, OP, LPAREN, RPAREN, END }

    private record Token(TokenType type, String text, int pos) {
    }

    private static List<Token> tokenize(String expression) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(expression.charAt(i + 1)))) {
                int start = i;
                while (i < n && (Character.isDigit(expression.charAt(i)) || expression.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(TokenType.NUMBER, expression.substring(start, i), start));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(expression.charAt(i)) || expression.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(TokenType.IDENT, expression.substring(start, i), start));
                continue;
            }
            // two-char operators first
            if (i + 1 < n) {
                String two = expression.substring(i, i + 2);
                if (two.equals(">=") || two.equals("<=") || two.equals("==") || two.equals("!=")
                        || two.equals("&&") || two.equals("||")) {
                    tokens.add(new Token(TokenType.OP, two, i));
                    i += 2;
                    continue;
                }
            }
            switch (c) {
                case '>', '<', '!' -> tokens.add(new Token(TokenType.OP, String.valueOf(c), i));
                case '(' -> tokens.add(new Token(TokenType.LPAREN, "(", i));
                case ')' -> tokens.add(new Token(TokenType.RPAREN, ")", i));
                default -> throw new IllegalArgumentException("Unexpected character '" + c
                        + "' at position " + i + " in expression: " + expression);
            }
            i++;
        }
        tokens.add(new Token(TokenType.END, "", n));
        return tokens;
    }

    // ------------------------------------------------------------------
    // Parser (recursive descent)
    // ------------------------------------------------------------------

    private static final class Parser {

        private final String source;
        private final List<Token> tokens;
        private int pos;

        Parser(String source, List<Token> tokens) {
            this.source = source;
            this.tokens = tokens;
        }

        Node parseOr() {
            Node left = parseAnd();
            while (isOp("||") || isWord("or")) {
                next();
                left = new OrNode(left, parseAnd());
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseUnary();
            while (isOp("&&") || isWord("and")) {
                next();
                left = new AndNode(left, parseUnary());
            }
            return left;
        }

        private Node parseUnary() {
            if (isOp("!")) {
                next();
                return new NotNode(parseUnary());
            }
            Node left = parsePrimary();
            Token t = peek();
            if (t.type() == TokenType.OP && isComparison(t.text())) {
                next();
                return new CmpNode(t.text(), left, parsePrimary());
            }
            return left;
        }

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
                    Node inner = parseOr();
                    expect(TokenType.RPAREN);
                    return inner;
                }
                default -> throw error("Expected number, variable or '(' but got '"
                        + t.text() + "'", t);
            }
        }

        private static boolean isComparison(String op) {
            return op.equals(">") || op.equals(">=") || op.equals("<") || op.equals("<=")
                    || op.equals("==") || op.equals("!=");
        }

        private boolean isOp(String op) {
            Token t = peek();
            return t.type() == TokenType.OP && t.text().equals(op);
        }

        private boolean isWord(String word) {
            Token t = peek();
            return t.type() == TokenType.IDENT && t.text().equalsIgnoreCase(word);
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private void expect(TokenType type) {
            Token t = next();
            if (t.type() != type) {
                throw error("Expected " + type + " but got '" + t.text() + "'", t);
            }
        }

        private IllegalArgumentException error(String message, Token t) {
            return new IllegalArgumentException(message + " at position " + t.pos()
                    + " in expression: " + source);
        }
    }

    private static Node parse(String expression) {
        Parser parser = new Parser(expression, tokenize(expression));
        Node node = parser.parseOr();
        Token end = parser.next();
        if (end.type() != TokenType.END) {
            throw parser.error("Unexpected token '" + end.text() + "'", end);
        }
        return node;
    }
}
