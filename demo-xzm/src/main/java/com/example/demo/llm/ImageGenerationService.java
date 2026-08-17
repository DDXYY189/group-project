package com.example.demo.llm;

import com.example.demo.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 图片生成服务：通义万相 wanx-v1，异步创建任务并轮询结果，返回图片字节数据。
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    private final DashScopeClient client;
    private final LlmProperties props;

    public ImageGenerationService(DashScopeClient client, LlmProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * 根据提示词生成图片，返回第一张图片的字节数据。
     */
    public byte[] generate(String prompt) throws Exception {
        Map<String, Object> requestBody = Map.of(
                "model", props.getImage().getModel(),
                "input", Map.of("prompt", prompt),
                "parameters", Map.of(
                        "size", props.getImage().getSize(),
                        "n", props.getImage().getN()
                )
        );

        JsonNode createResp = client.postNative(
                "/api/v1/services/aigc/text2image/image-synthesis", requestBody, true);
        String taskId = createResp.path("output").path("task_id").asText(null);
        if (taskId == null) {
            throw new RuntimeException("创建图片任务失败: " + createResp);
        }
        log.info("图片任务已创建 taskId={}", taskId);

        String imageUrl = pollTask(taskId);
        return client.download(imageUrl);
    }

    private String pollTask(String taskId) throws Exception {
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(2000);
            JsonNode resp = client.getTask(taskId);
            String status = resp.path("output").path("task_status").asText("");
            log.debug("图片任务 {} 状态: {}", taskId, status);
            if ("SUCCEEDED".equals(status)) {
                JsonNode results = resp.path("output").path("results");
                if (results.isArray() && !results.isEmpty()) {
                    String url = results.get(0).path("url").asText(null);
                    if (url != null) {
                        return url;
                    }
                }
                throw new RuntimeException("图片任务成功但未返回URL: " + resp);
            } else if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                throw new RuntimeException("图片任务失败: " + resp.path("output").asText());
            }
        }
        throw new RuntimeException("图片任务轮询超时 taskId=" + taskId);
    }
}
