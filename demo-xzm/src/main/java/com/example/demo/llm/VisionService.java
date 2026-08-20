package com.example.demo.llm;

import com.example.demo.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 图片理解服务：基于 Qwen-VL 多模态模型，把图片转 Base64 后发给大模型理解。
 * 走 DashScope OpenAI 兼容接口，messages 中 image_url 支持 data:image/...;base64,... 形式。
 */
@Service
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);

    private final DashScopeClient client;
    private final LlmProperties props;

    public VisionService(DashScopeClient client, LlmProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * 理解图片内容。
     *
     * @param imageBytes 图片二进制
     * @param question   用户针对图片的提问，为空则默认"描述这张图片"
     * @return 模型对图片的理解文本
     */
    public String understand(byte[] imageBytes, String question) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUri = "data:image/jpeg;base64," + b64;
        String q = (question == null || question.isBlank()) ? "请描述这张图片的内容。" : question;

        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日"));
        String system = "你是一个接入微信的多模态智能助手，能看懂图片。回答简洁友好。当前日期：" + today;

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", q),
                        Map.of("type", "image_url",
                                "image_url", Map.of("url", dataUri))
                ))
        );

        Map<String, Object> request = Map.of(
                "model", props.getVision().getModel(),
                "messages", messages
        );

        try {
            JsonNode resp = client.chatCompletions(request);
            String reply = resp.path("choices").path(0).path("message").path("content").asText("").trim();
            if (reply.isEmpty()) {
                reply = "（模型未识别到内容）";
            }
            log.info("图片理解结果: {}", reply);
            return reply;
        } catch (Exception e) {
            log.error("图片理解失败: {}", e.getMessage(), e);
            return "图片理解失败: " + e.getMessage();
        }
    }
}
