package com.example.demo.llm;

import com.example.demo.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * DashScope HTTP 调用底层封装。
 * 文本对话/语音识别走 OpenAI 兼容端点；图片生成/语音合成走 DashScope 原生端点。
 */
@Component
public class DashScopeClient {

    private final LlmProperties props;
    // Spring Boot 4.x 自动配置的是 Jackson 3.x(tools.jackson)，而本模块依赖的微信 SDK
    // 传递引入的是 Jackson 2.x(com.fasterxml)。这里直接实例化 2.x 的 ObjectMapper，
    // 避免与 Spring Boot 的 3.x bean 冲突。
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public DashScopeClient(LlmProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public LlmProperties props() {
        return props;
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    private String bearer() {
        return "Bearer " + props.getApiKey();
    }

    /**
     * OpenAI 兼容的 chat/completions 调用（文本对话、语音识别均使用）。
     */
    public JsonNode chatCompletions(Object requestBody) throws Exception {
        String json = objectMapper.writeValueAsString(requestBody);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/compatible-mode/v1/chat/completions"))
                .header("Authorization", bearer())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(120))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parseResponse(resp);
    }

    /**
     * DashScope 原生端点 POST（图片生成创建任务、语音合成）。
     *
     * @param async 是否为异步任务（图片生成需传 true，语音合成传 false）
     */
    public JsonNode postNative(String path, Object requestBody, boolean async) throws Exception {
        String json = objectMapper.writeValueAsString(requestBody);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + path))
                .header("Authorization", bearer())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (async) {
            builder.header("X-DashScope-Async", "enable");
        }
        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return parseResponse(resp);
    }

    /**
     * 查询异步任务结果（图片生成轮询用）。
     */
    public JsonNode getTask(String taskId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/api/v1/tasks/" + taskId))
                .header("Authorization", bearer())
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parseResponse(resp);
    }

    /**
     * 下载二进制内容（图片/音频结果 URL）。
     */
    public byte[] download(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("下载失败: HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private JsonNode parseResponse(HttpResponse<String> resp) throws Exception {
        String body = resp.body();
        JsonNode node = objectMapper.readTree(body);
        if (resp.statusCode() / 100 != 2) {
            String code = pathText(node, "code");
            String message = pathText(node, "message");
            if (code == null && node.has("error")) {
                code = pathText(node.path("error"), "code");
                message = pathText(node.path("error"), "message");
            }
            throw new RuntimeException("DashScope 调用失败 HTTP " + resp.statusCode()
                    + (code != null ? " [" + code + "]" : "")
                    + (message != null ? ": " + message : ""));
        }
        return node;
    }

    private String pathText(JsonNode node, String field) {
        return node != null && node.has(field) ? node.get(field).asText() : null;
    }
}
