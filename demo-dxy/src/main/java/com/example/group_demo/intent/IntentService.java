package com.example.group_demo.intent;

import com.example.group_demo.llm.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);
    private static final String PROMPT =
        "你是意图识别器。根据用户输入返回 JSON，不要输出其他内容。"
            + "格式：{\"action\":\"text\"|\"voice\"|\"image\",\"reply\":\"给用户的回复\",\"image_prompt\":\"图片生成提示词\"}。"
            + "只有明确要求生成或画新图片时 action=image；识别、查看、描述已有图片不属于 image。"
            + "用户要求用语音回复时 action=voice；其余 action=text。";
    private static final String IMAGE_TEXT_PROMPT =
        "你是图像处理意图识别器。用户已经提供一张图片和一句说明。"
            + "如果用户要求修改图片（改背景、换颜色、摘掉眼镜、去掉水印、加上文字、修图、裁剪、调整风格等），"
            + "返回 {\"action\":\"edit\"}。"
            + "如果只是描述、识别、分析或提问图片内容，返回 {\"action\":\"understand\"}。只输出 JSON。";

    private final LlmService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentService(LlmService llmService) {
        this.llmService = llmService;
    }

    public Intent classify(String userText) {
        if (userText == null || userText.isBlank()) {
            return new Intent("text", null, null);
        }
        String text = userText.trim();

        if (containsAny(text, "画", "生成图片", "生成一张图", "作图", "设计图", "帮我画")) {
            return new Intent("image", "好的，我来生成图片。", text);
        }
        if (containsAny(text, "语音回复", "用语音", "说给我听", "语音回")) {
            return new Intent("voice", "好的，我用语音回复你。", null);
        }

        try {
            String raw = llmService.chatRaw(PROMPT, text);
            JsonNode node = objectMapper.readTree(raw);
            String action = node.path("action").asText("text");
            String reply = node.has("reply") && !node.get("reply").isNull()
                ? node.get("reply").asText() : null;
            String imagePrompt = node.has("image_prompt") && !node.get("image_prompt").isNull()
                ? node.get("image_prompt").asText() : null;
            return new Intent(action, reply, imagePrompt);
        } catch (Exception e) {
            log.warn("意图识别失败，回退为文本回复：{}", e.getMessage());
            return new Intent("text", null, null);
        }
    }

    public String classifyImageText(String userText) {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) {
            return "understand";
        }

        if (containsAny(text, "识别", "看看", "看一下", "描述", "解读", "分析",
            "介绍一下", "这是什么", "是什么")) {
            return "understand";
        }
        if (containsAny(text,
            "摘掉", "去掉", "移除", "清除", "删除", "加上", "戴上", "穿上", "变成",
            "改", "换", "加", "删", "去除", "修", "背景", "颜色", "尺寸", "风格",
            "调整", "旋转", "裁剪", "模糊", "锐化", "涂", "编辑", "处理")) {
            return "edit";
        }

        try {
            String raw = llmService.chatRaw(IMAGE_TEXT_PROMPT, text);
            JsonNode node = objectMapper.readTree(raw);
            if ("edit".equals(node.path("action").asText("understand"))) {
                return "edit";
            }
        } catch (Exception e) {
            log.warn("图像意图识别失败，默认理解：{}", e.getMessage());
        }
        return "understand";
    }

    private boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

}
