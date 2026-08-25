package com.example.group_demo.travel;

import java.util.List;

public record TravelAgentResult(
        String status,
        String question,
        String reply,
        String htmlUrl,
        String pageId,
        TravelPlan plan,
        List<String> steps,
        int todoCount,
        boolean imageGenerated,
        boolean voiceGenerated) {

    public static TravelAgentResult needMoreInfo(String question) {
        String text = question == null || question.isBlank()
                ? "请告诉我你想去哪个城市，计划玩几天，我来生成完整方案。"
                : question.trim();
        return new TravelAgentResult("need_more_info", text, text, null, null, null,
                List.of(), 0, false, false);
    }

    public static TravelAgentResult error(String message) {
        String text = message == null || message.isBlank() ? "旅行规划执行失败" : message;
        return new TravelAgentResult("error", null, text, null, null, null,
                List.of(), 0, false, false);
    }
}
