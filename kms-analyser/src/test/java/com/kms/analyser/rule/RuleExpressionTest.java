package com.kms.analyser.rule;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the rule expression engine (spec 4.3: condition expressions such
 * as "value > upper_threshold" from t_alert_rule).
 */
class RuleExpressionTest {

    private static final Map<String, Double> VARS = Map.of(
            "value", 95.0,
            "upper_threshold", 90.0,
            "lower_threshold", 10.0,
            "volatility", 12.5,
            "volatility_threshold", 10.0);

    @Test
    void simpleThresholdComparison() {
        assertTrue(RuleExpression.evaluate("value > upper_threshold", VARS));
        assertFalse(RuleExpression.evaluate("value < lower_threshold", VARS));
        assertTrue(RuleExpression.evaluate("value >= 95", VARS));
        assertTrue(RuleExpression.evaluate("value != lower_threshold", VARS));
    }

    @Test
    void logicalOperators() {
        assertTrue(RuleExpression.evaluate("value > upper_threshold && volatility > volatility_threshold", VARS));
        assertTrue(RuleExpression.evaluate("value > upper_threshold and volatility > volatility_threshold", VARS));
        assertTrue(RuleExpression.evaluate("value > 99 or volatility > 10", VARS));
        assertFalse(RuleExpression.evaluate("value > 99 or volatility > 99", VARS));
        assertFalse(RuleExpression.evaluate("value > upper_threshold && volatility > 99", VARS));
    }

    @Test
    void negationAndParentheses() {
        assertTrue(RuleExpression.evaluate("!(value < upper_threshold)", VARS));
        assertTrue(RuleExpression.evaluate("(value > upper_threshold) && (volatility > volatility_threshold)", VARS));
        assertTrue(RuleExpression.evaluate("volatility > 10 && (value > 90 || value < 5)", VARS));
    }

    @Test
    void unknownVariableNeverFires() {
        // upper_threshold missing -> comparison unknown -> expression false
        assertFalse(RuleExpression.evaluate("value > upper_threshold", Map.of("value", 95.0)));
        assertFalse(RuleExpression.evaluate("value > missing && volatility > volatility_threshold",
                Map.of("value", 95.0, "volatility", 12.5)));
    }

    @Test
    void syntaxErrorsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.evaluate("value >", VARS));
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.evaluate("value > > 5", VARS));
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.evaluate("(value > 5", VARS));
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.evaluate("value $ 5", VARS));
    }
}
