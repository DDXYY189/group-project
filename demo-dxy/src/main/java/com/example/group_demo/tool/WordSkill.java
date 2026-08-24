package com.example.group_demo.tool;

import com.example.group_demo.config.RestClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查单词 Skill：关键词直接路由，不走 LLM。
 * 用户说"hello是什么意思 / 帮我查单词apple"即命中，直接调有道接口。
 */
@Service
public class WordSkill implements BotSkill {

    private static final Pattern ENGLISH_WORD = Pattern.compile("[a-zA-Z]+");

    private final RestClient restClient = RestClientFactory.builder().build();

    @Override
    public String name() {
        return "word_skill";
    }

    @Override
    public List<String> keywords() {
        return List.of("是什么意思", "查单词", "什么意思");
    }

    @Override
    public String execute(String userId, String text) {
        Matcher matcher = ENGLISH_WORD.matcher(text);
        if (!matcher.find()) {
            return "请告诉我要查的英文单词，例如：hello是什么意思";
        }
        String word = matcher.group().toLowerCase();
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
