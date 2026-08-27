package com.example.group_demo.tool.chain;

import java.util.List;
import java.util.Map;

/**
 * 一条确定性的多步工具链。每一步使用参数模板，模板中可引用
 * {{input.xxx}}（链入口参数）和 {{prev.xxx}}（上一步执行结果）。
 */
public record ToolChain(String id, String description, Map<String, Object> inputSchema,
                        List<Step> steps) {

    public ToolChain {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("链 id 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("链描述不能为空: " + id);
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("链至少需要一个步骤: " + id);
        }
        steps = List.copyOf(steps);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }

    public record Step(String toolName, String argumentsTemplate, String resultMode) {

        public Step {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("步骤工具名不能为空");
            }
            if (argumentsTemplate == null || argumentsTemplate.isBlank()) {
                throw new IllegalArgumentException("步骤参数模板不能为空: " + toolName);
            }
            resultMode = resultMode == null || resultMode.isBlank() ? "text" : resultMode;
        }

        public Step(String toolName, String argumentsTemplate) {
            this(toolName, argumentsTemplate, "text");
        }
    }
}
