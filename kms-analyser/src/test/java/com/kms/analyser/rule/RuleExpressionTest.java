package com.kms.analyser.rule;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the rule expression engine (spec 4.3: condition expressions such
 * as "value > upper_threshold" from t_alert_rule).
 *
 * <p>【中文】规则表达式引擎的单元测试（规格 4.3：t_alert_rule 里
 * "value > upper_threshold" 这类条件表达式）。5 个测试方法分别覆盖比较算符、
 * 逻辑算符与关键字写法、否定与括号、三值逻辑（未知永不触发），
 * 以及 4 类语法错误必须抛异常。纯内存测试，不依赖任何数据库。
 */
class RuleExpressionTest {

    // 固定的变量绑定：值 95 高于上限 90（阈值超限）、低于下限 10 不成立，
    // 波动率 12.5 高于波动阈值 10（波动超限）——后面各用例都基于这组"双超限"数据构造断言
    private static final Map<String, Double> VARS = Map.of(
            "value", 95.0,
            "upper_threshold", 90.0,
            "lower_threshold", 10.0,
            "volatility", 12.5,
            "volatility_threshold", 10.0);

    // 比较算符：>、<、>=、!=（== 与 <= 走的是同一套 CmpNode 分支）
    @Test
    void simpleThresholdComparison() {
        assertTrue(RuleExpression.evaluate("value > upper_threshold", VARS));
        assertFalse(RuleExpression.evaluate("value < lower_threshold", VARS));
        assertTrue(RuleExpression.evaluate("value >= 95", VARS));
        assertTrue(RuleExpression.evaluate("value != lower_threshold", VARS));
    }

    // 逻辑算符：&& / || 与关键字 and / or 等价，同时验证短路语义
    // （"value > 99 or volatility > 10" 靠右侧为真，"&&" 那行靠右侧为假而整体为假）
    @Test
    void logicalOperators() {
        assertTrue(RuleExpression.evaluate("value > upper_threshold && volatility > volatility_threshold", VARS));
        assertTrue(RuleExpression.evaluate("value > upper_threshold and volatility > volatility_threshold", VARS));
        assertTrue(RuleExpression.evaluate("value > 99 or volatility > 10", VARS));
        assertFalse(RuleExpression.evaluate("value > 99 or volatility > 99", VARS));
        assertFalse(RuleExpression.evaluate("value > upper_threshold && volatility > 99", VARS));
    }

    // 否定与括号：括号内的表达式整体参与运算（由 parsePrimary 的 LPAREN 分支
    // 递归回 parseOr 实现），"!(...)" 由 NotNode 取反
    @Test
    void negationAndParentheses() {
        assertTrue(RuleExpression.evaluate("!(value < upper_threshold)", VARS));
        assertTrue(RuleExpression.evaluate("(value > upper_threshold) && (volatility > volatility_threshold)", VARS));
        assertTrue(RuleExpression.evaluate("volatility > 10 && (value > 90 || value < 5)", VARS));
    }

    // 三值逻辑最关键的断言：阈值变量缺失时比较结果为"未知"，整个表达式求值为 false——
    // 也就是"没配阈值 = 永远不会因为阈值触发"，既不抛 NPE 也不会误报
    @Test
    void unknownVariableNeverFires() {
        // upper_threshold missing -> comparison unknown -> expression false
        // upper_threshold 缺失 → 比较结果"未知" → 整个表达式为 false
        assertFalse(RuleExpression.evaluate("value > upper_threshold", Map.of("value", 95.0)));
        assertFalse(RuleExpression.evaluate("value > missing && volatility > volatility_threshold",
                Map.of("value", 95.0, "volatility", 12.5)));
    }

    // 语法错误必须显式抛 IllegalArgumentException，绝不能被静默忽略
    // （否则规则写错只会表现为"一直不报警"，很难被发现）。四类错误：
    // ① 缺少右操作数 ② 连续两个比较算符 ③ 括号未闭合 ④ 非法字符 $
    @Test
    void syntaxErrorsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.evaluate("value >", VARS));
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.evaluate("value > > 5", VARS));
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.evaluate("(value > 5", VARS));
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.evaluate("value $ 5", VARS));
    }
}
