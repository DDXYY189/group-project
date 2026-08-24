package com.example.group_demo.rag;

import java.util.List;

public record KnowledgeDocument(String title, String content, List<String> keywords) {
}
