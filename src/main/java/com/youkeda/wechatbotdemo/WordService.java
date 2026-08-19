package com.youkeda.wechatbotdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 查单词服务：调用有道词典公开接口（免费、无需 Key），返回英文单词的中文释义。
 */
public class WordService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 查询英文单词的中文释义。
     *
     * @param word 英文单词，如 "hello"
     * @return 释义文本
     */
    public String lookup(String word) throws Exception {
        String w = word.trim();
        String url = "https://dict.youdao.com/suggest?q="
                + URLEncoder.encode(w, StandardCharsets.UTF_8)
                + "&num=1&doctype=json";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        try {
            try (InputStream in = conn.getInputStream()) {
                JsonNode root = MAPPER.readTree(in);
                // 有道接口返回结构：data.entries[0].{entry, explain}
                JsonNode entries = root.path("data").path("entries");
                if (entries.isArray() && !entries.isEmpty()) {
                    JsonNode first = entries.get(0);
                    String entry = first.path("entry").asText(w);
                    String explain = first.path("explain").asText("");
                    if (!explain.isBlank()) {
                        return entry + "：" + explain;
                    }
                }
                return "没查到 \"" + w + "\" 的释义，确认一下拼写？";
            }
        } finally {
            conn.disconnect();
        }
    }
}
