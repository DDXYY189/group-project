package com.example.group_demo.tool.chain;

import com.example.group_demo.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ToolChainService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ToolChainService.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)\\}\\}");
    private static final Pattern NUMBERED_LINE = Pattern.compile("^\\d+\\.\\s*(.+)$");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolRegistry toolRegistry;
    private final Map<String, ToolChain> chains = new LinkedHashMap<>();

    public ToolChainService(ToolRegistry toolRegistry, List<ToolChain> chains) {
        this.toolRegistry = toolRegistry;
        if (chains != null) {
            for (ToolChain chain : chains) {
                if (this.chains.put(chain.id(), chain) != null) {
                    throw new IllegalStateException("重复的链 ID: " + chain.id());
                }
            }
        }
    }

    @Override
    public void afterPropertiesSet() {
        for (ToolChain chain : chains.values()) {
            toolRegistry.register(new ChainRunnerTool(this, chain));
        }
    }

    public List<Map<String, Object>> summaries() {
        return chains.values().stream().map(chain -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", chain.id());
            summary.put("description", chain.description());
            summary.put("steps", chain.steps().stream().map(ToolChain.Step::toolName).toList());
            return summary;
        }).toList();
    }

    public String run(String userId, String chainId, JsonNode input) {
        ToolChain chain = chains.get(chainId);
        if (chain == null) {
            throw new IllegalArgumentException("链不存在: " + chainId);
        }
        JsonNode chainInput = input == null || input.isNull() || input.isMissingNode()
            ? objectMapper.nullNode() : input;
        JsonNode previous = null;
        List<String> stepLogs = new ArrayList<>();
        List<ToolChain.Step> steps = chain.steps();
        for (int i = 0; i < steps.size(); i++) {
            ToolChain.Step step = steps.get(i);
            String stepNo = "步骤" + (i + 1);
            try {
                String arguments = resolveArguments(step.argumentsTemplate(), chainInput, previous);
                String result = toolRegistry.executeStrict(userId, step.toolName(), arguments);
                previous = toStructured(step.resultMode(), result);
                stepLogs.add(stepNo + " " + step.toolName() + "：" + result);
                log.info("链式步骤成功 chain={} {} tool={}", chainId, stepNo, step.toolName());
            } catch (Exception e) {
                throw new IllegalStateException("链 " + chainId + " 第 " + (i + 1) + " 步 "
                    + step.toolName() + " 执行失败: " + e.getMessage(), e);
            }
        }
        return "链式流程执行成功（" + chainId + "，共 " + steps.size() + " 步）：\n"
            + String.join("\n", stepLogs);
    }

    private String resolveArguments(String template, JsonNode input, JsonNode previous) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String value = resolveValue(expression, input, previous);
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        String json = resolved.toString();
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("步骤参数模板解析后不是合法 JSON: " + json, e);
        }
        return json;
    }

    private String resolveValue(String expression, JsonNode input, JsonNode previous) {
        int dot = expression.indexOf('.');
        String source = dot < 0 ? expression : expression.substring(0, dot);
        String path = dot < 0 ? "" : expression.substring(dot + 1);
        JsonNode root;
        switch (source) {
            case "input" -> root = input;
            case "prev" -> root = previous;
            default -> throw new IllegalArgumentException("未知占位符来源: " + source);
        }
        if (root == null || root.isNull()) {
            throw new IllegalArgumentException("占位符 " + expression + " 引用的结果为空");
        }
        JsonNode node = resolvePath(root, path);
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException("无法解析占位符: " + expression);
        }
        if (node.isTextual()) {
            return new String(JsonStringEncoder.getInstance().quoteAsString(node.textValue()));
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("占位符值序列化失败: " + expression, e);
        }
    }

    private JsonNode resolvePath(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        String normalized = path.replace("[", ".").replace("]", "");
        JsonNode current = root;
        for (String segment : normalized.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            if (current == null || current.isNull()) {
                return null;
            }
            if (current.isArray()) {
                try {
                    current = current.get(Integer.parseInt(segment));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                current = current.get(segment);
            }
        }
        return current;
    }

    private JsonNode toStructured(String resultMode, String result) {
        String trimmed = result == null ? "" : result.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readTree(trimmed);
            } catch (JsonProcessingException ignored) {
                // 非结构化文本，按 resultMode 处理
            }
        }
        if ("numbered_items".equals(resultMode)) {
            List<String> items = new ArrayList<>();
            for (String line : result.split("\\R")) {
                Matcher matcher = NUMBERED_LINE.matcher(line.trim());
                if (matcher.matches()) {
                    items.add(matcher.group(1).trim());
                }
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("result", result);
            node.put("items", items);
            node.put("first", items.isEmpty() ? result : items.get(0));
            return objectMapper.valueToTree(node);
        }
        return objectMapper.valueToTree(Map.of("result", result == null ? "" : result));
    }
}
