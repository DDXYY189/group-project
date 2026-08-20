package com.example.group_demo.tool.chain;

import com.example.group_demo.tool.BotTool;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 把一条工具链暴露为普通 BotTool，LLM 一次调用即可触发整条链。
 */
final class ChainRunnerTool implements BotTool {

    private final ToolChainService chainService;
    private final ToolChain chain;

    ChainRunnerTool(ToolChainService chainService, ToolChain chain) {
        this.chainService = chainService;
        this.chain = chain;
    }

    @Override
    public String name() {
        return chain.id();
    }

    @Override
    public String description() {
        return "链式工具：" + chain.description() + "。该工具会自动按顺序执行多个步骤，调用后无需再调用其他工具。";
    }

    @Override
    public Map<String, Object> parameters() {
        return chain.inputSchema();
    }

    @Override
    public boolean relayToUser() {
        return true;
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        return chainService.run(userId, chain.id(), arguments);
    }
}
