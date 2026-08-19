package com.example.demo.tool;

/**
 * 一个极简、安全的中缀算术表达式求值器。
 *
 * <p>只支持数字、四则运算、括号和正负号，不执行任何脚本，避免把用户输入直接
 * 交给 JS 引擎等危险方式。
 */
final class ExpressionEvaluator {

    private final String s;
    private int pos;

    private ExpressionEvaluator(String s) {
        this.s = s;
    }

    static double eval(String expression) {
        return new ExpressionEvaluator(expression).parseExpression();
    }

    private double parseExpression() {
        double value = parseTerm();
        while (true) {
            skipWhitespace();
            if (peek('+')) {
                pos++;
                value += parseTerm();
            } else if (peek('-')) {
                pos++;
                value -= parseTerm();
            } else {
                break;
            }
        }
        return value;
    }

    private double parseTerm() {
        double value = parseFactor();
        while (true) {
            skipWhitespace();
            if (peek('*')) {
                pos++;
                value *= parseFactor();
            } else if (peek('/')) {
                pos++;
                value /= parseFactor();
            } else {
                break;
            }
        }
        return value;
    }

    private double parseFactor() {
        skipWhitespace();
        if (peek('(')) {
            pos++;
            double value = parseExpression();
            skipWhitespace();
            expect(')');
            return value;
        }
        if (peek('+')) {
            pos++;
            return parseFactor();
        }
        if (peek('-')) {
            pos++;
            return -parseFactor();
        }
        return parseNumber();
    }

    private double parseNumber() {
        skipWhitespace();
        int start = pos;
        while (pos < s.length()
                && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("在位置 " + pos + " 期望一个数字");
        }
        return Double.parseDouble(s.substring(start, pos));
    }

    private void skipWhitespace() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private boolean peek(char c) {
        return pos < s.length() && s.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw new IllegalArgumentException("期望字符 '" + c + "'");
        }
        pos++;
    }
}
