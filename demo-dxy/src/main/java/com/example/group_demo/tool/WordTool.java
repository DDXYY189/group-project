package com.example.group_demo.tool;

import com.example.group_demo.config.RestClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class WordTool implements BotTool {

    private final RestClient restClient = RestClientFactory.builder().build();

    @Override
    public String name() {
        return "lookup_word";
    }

    @Override
    public String description() {
        return "查询英文单词的中文释义，当用户问某个英文单词是什么意思时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "word", Map.of(
                                "type", "string",
                                "description", "要查询的英文单词，例如：hello、apple"
                        )
                ),
                "required", List.of("word"),
                "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String word = arguments.path("word").asText("").trim();
        if (word.isEmpty()) {
            throw new IllegalArgumentException("缺少 word 参数");
        }
        return queryYoudao(word);
    }

    private String queryYoudao(String word) {
        JsonNode root = restClient.get()
                .uri("https://dict.youdao.com/suggest?q=" + word + "&num=1&doctype=json")
                .retrieve()
                .body(JsonNode.class);

        if (root == null || !root.has("data") || root.path("data").isEmpty()) {
            return "没有查到 \"" + word + "\" 的释义";
        }
        JsonNode entry = root.path("data").get(0);
        String title = entry.path("entry").asText(word);
        String explain = entry.path("explain").asText("");
        return title + "：" + explain;
    }
}
