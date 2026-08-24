package com.example.group_demo.rag;

import java.util.LinkedHashMap;
import java.util.Map;

public record KnowledgeChunk(String id, String title, String content) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("title", title);
        map.put("content", content);
        return map;
    }
}
