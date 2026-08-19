package com.example.demo.tool;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 计算器工具：安全地计算一个四则运算表达式，不需要任何外部 API。
 */
@Component
public class CalculatorTool implements Tool {

    @Override
    public String name() {
        return "calculate";
    }

    @Override
    public String description() {
        return "计算一个数学表达式，支持加减乘除和括号，例如 3*(4+5)。用户需要算术计算时使用。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "expression", Map.of(
                                "type", "string",
                                "description", "要计算的数学表达式，例如：1+2*3 或 (10-4)/2")),
                "required", List.of("expression"));
    }

    @Override
    public String execute(JsonNode arguments) {
        String expression = arguments.path("expression").asText("");
        if (expression.isBlank()) {
            return "没有提供表达式。";
        }

        try {
            double result = ExpressionEvaluator.eval(expression);
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return expression + " = " + (long) result;
            }
            return expression + " = " + result;
        } catch (Exception e) {
            return "无法计算表达式「" + expression + "」：" + e.getMessage();
        }
    }
}
