package com.example.wechatbot.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final RestTemplate restTemplate;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.text-model:qwen-turbo}")
    private String textModel;

    @Value("${llm.vision-model:qwen-vl-plus}")
    private String visionModel;

    @Value("${llm.base-url:https://dashscope.aliyuncs.com/api/v1}")
    private String baseUrl;

    @Value("${llm.tts-model:cosyvoice-v2}")
    private String ttsModel;

    @Value("${llm.tts-voice:longxiaochun}")
    private String ttsVoice;

    /** 图片生成模型，默认通义万相 wanx-v1 */
    @Value("${llm.image-model:wanx-v1}")
    private String imageModel;

    /**
     * 千问图像生成与编辑模型（qwen-image-3.0-pro）
     * 同时支持文生图（T2I）和图生图/图像编辑（I2I），通过 multimodal-generation/generation 端点调用
     * I2I 模式：传入原图 + 编辑指令，模型理解后执行编辑，不会重绘整张图
     *
     * 【费用提示】千问图生图接口按量计费，测试环境控制调用次数
     * 详见 https://help.aliyun.com/zh/model-studio/qwen-image-generation-and-editing-api-reference
     */
    private static final String IMAGE_EDIT_MODEL = "qwen-image-3.0-pro";

    /**
     * 图生图相似度参数（strength）
     * 取值 0.0~1.0，值越小越忠于原图（保留更多原图内容），值越大修改幅度越大
     * 固定设置 0.25，确保模型严格保留原图主体，仅执行用户的修改指令
     */
    private static final double IMAGE_EDIT_STRENGTH = 0.25;

    /**
     * 图生图强制约束提示词（拼接在用户指令前面）
     * 约束模型严格保留原图全部主体，仅修改背景区域，禁止删除/重绘主体
     */
    private static final String IMAGE_EDIT_FIXED_PROMPT =
            "【强制规则】严格完整保留原图里面全部主体物体、形象、姿态、画风，主体一丝一毫不能修改，仅修改背景区域，禁止删除、重绘主体，只执行用户的修改指令。";

    private static final String SYSTEM_PROMPT =
            "你是一个智能微信助手。请用简洁友好的中文回复用户, 回复不超过200字。" +
            "你具备发送MP3音频文件的能力，当用户要求语音回复、音频文件回复时，请简短回复后由语音系统自动发送MP3音频。" +
            "禁止回复'我无法直接发送MP3文件'或'我不能发送MP3'之类的话。";

    public LlmService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isReady() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public String chat(String userMessage) {
        if (!isReady()) {
            log.warn("DashScope API Key 未配置, 无法调用大模型");
            return "[API Key 未配置, 请在 application.yml 中设置 llm.api-key]";
        }

        String url = baseUrl.replace("/api/v1", "/compatible-mode/v1") + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", textModel);
        body.put("messages", new Object[]{
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userMessage)
        });

        try {
            JSONObject resp = postRequest(url, body);
            JSONArray choices = resp.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                return choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");
            }
            return "[大模型未返回有效内容]";
        } catch (Exception e) {
            log.error("文本对话调用失败: {}", e.getMessage(), e);
            return "[调用大模型失败: " + e.getMessage() + "]";
        }
    }

    public String chatWithImage(byte[] imageBytes, String prompt) {
        if (!isReady()) {
            return "[API Key 未配置]";
        }

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:image/png;base64," + base64Image;

        String url = baseUrl.replace("/api/v1", "/compatible-mode/v1") + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", visionModel);
        body.put("messages", new Object[]{
                Map.of("role", "system", "content", "你是一个图片分析助手, 请用中文简洁描述。"),
                Map.of("role", "user", "content", new Object[]{
                        Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)),
                        Map.of("type", "text", "text", prompt != null ? prompt : "请描述这张图片的内容")
                })
        });

        try {
            JSONObject resp = postRequest(url, body);
            JSONArray choices = resp.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                return choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");
            }
            return "[图片理解未返回有效内容]";
        } catch (Exception e) {
            log.error("图片理解调用失败: {}", e.getMessage(), e);
            return "[图片理解失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 文本转语音（TTS）
     * 调用 DashScope CosyVoice 非实时语音合成 API，将文本转为 MP3 格式音频
     * API 文档: https://help.aliyun.com/zh/model-studio/cosyvoice-tts-http-api
     *
     * 【费用提示】TTS语音合成按量计费，测试控制调用次数
     *
     * @param text 待合成的文本（超300字将截断）
     * @return MP3 格式音频字节，失败返回 null
     */
    public byte[] textToSpeech(String text) {
        if (!isReady()) {
            log.warn("DashScope API Key 未配置, 无法调用语音合成");
            return null;
        }

        // 截断超长文本，避免 TTS 超时
        String truncatedText = text.length() > 300 ? text.substring(0, 300) : text;

        // DashScope 原生 TTS 接口地址
        String ttsUrl = baseUrl + "/services/audio/tts/SpeechSynthesizer";

        // 构建请求体：model + input（含文本、音色、格式、采样率）
        Map<String, Object> input = new HashMap<>();
        input.put("text", truncatedText);
        input.put("voice", ttsVoice);
        input.put("format", "mp3");
        input.put("sample_rate", 16000);

        Map<String, Object> body = new HashMap<>();
        body.put("model", ttsModel);
        body.put("input", input);

        // 构建 HTTP 请求
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);

        try {
            // 调用百炼TTS语音合成接口
            // 【费用提示】TTS语音合成按量计费，测试控制调用次数
            log.info("调用 TTS: model={}, voice={}, text长度={}", ttsModel, ttsVoice, truncatedText.length());
            ResponseEntity<String> resp = restTemplate.exchange(ttsUrl, HttpMethod.POST, entity, String.class);
            String respBody = resp.getBody();
            log.info("TTS 原始响应: {}", respBody);

            JSONObject respJson = JSON.parseObject(respBody);

            // 检查是否返回了错误
            JSONObject error = respJson.getJSONObject("error");
            if (error != null) {
                log.error("TTS API 返回错误: {}", error.toJSONString());
                return null;
            }

            // 解析音频下载 URL
            JSONObject output = respJson.getJSONObject("output");
            if (output == null) {
                log.warn("TTS 未返回 output 字段, 完整响应: {}", respBody);
                return null;
            }
            JSONObject audio = output.getJSONObject("audio");
            if (audio == null) {
                log.warn("TTS 未返回 audio 字段, output: {}", output.toJSONString());
                return null;
            }
            String audioUrl = audio.getString("url");
            if (audioUrl == null || audioUrl.isEmpty()) {
                log.warn("TTS 未返回音频下载URL, audio: {}", audio.toJSONString());
                return null;
            }

            // 从返回的 URL 下载 MP3 音频文件（URL 有效期 24 小时）
            // 使用 downloadByUrl（HttpURLConnection）而非 restTemplate，避免 URL 参数被重编码导致 OSS 签名失效
            log.info("TTS 合成成功, 下载音频: {}", audioUrl);
            byte[] audioBytes = downloadByUrl(audioUrl);
            if (audioBytes == null || audioBytes.length == 0) {
                log.warn("TTS 音频下载为空");
                return null;
            }
            log.info("TTS 音频下载完成, 大小: {} bytes", audioBytes.length);
            return audioBytes;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // 捕获 TTS API 调用的 HTTP 错误（4xx/5xx），打印 API 返回的具体错误信息
            log.error("TTS API HTTP 错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.error("语音合成调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 文生图：调用千问 qwen-image-3.0-pro 文生图接口，根据文字描述生成图片
     * API 是异步的：先提交任务获取 task_id，再轮询任务状态，最后下载图片
     * 【费用提示】千问图生图接口按量计费，测试环境控制调用次数
     * @param prompt 画图提示词
     * @return 图片字节（PNG），失败返回 null
     */
    public byte[] generateImage(String prompt) {
        if (!isReady()) {
            log.warn("API Key 未配置, 无法调用文生图");
            return null;
        }

        // 千问文生图接口地址（异步）
        String submitUrl = baseUrl + "/services/aigc/image-generation/generation";

        // 构建 messages 格式请求体（qwen-image-3.0-pro 使用对话式格式）
        List<Map<String, String>> content = new ArrayList<>();
        content.add(Collections.singletonMap("text", prompt));

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", content);

        Map<String, Object> input = new HashMap<>();
        input.put("messages", Collections.singletonList(message));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("n", 1);                 // 生成 1 张
        parameters.put("prompt_extend", true);   // 提示词智能改写

        Map<String, Object> body = new HashMap<>();
        body.put("model", IMAGE_EDIT_MODEL);
        body.put("input", input);
        body.put("parameters", parameters);

        // 构建 HTTP 请求
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("X-DashScope-Async", "enable");  // 异步任务
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);

        try {
            log.info("提交文生图任务: model={}, prompt={}", IMAGE_EDIT_MODEL, prompt);
            ResponseEntity<String> resp = restTemplate.exchange(submitUrl, HttpMethod.POST, entity, String.class);
            String respBody = resp.getBody();
            log.info("文生图任务提交响应: {}", respBody);

            JSONObject respJson = JSON.parseObject(respBody);
            JSONObject output = respJson.getJSONObject("output");
            if (output == null) {
                log.error("文生图任务提交失败, 无 output 字段");
                return null;
            }
            String taskId = output.getString("task_id");
            if (taskId == null || taskId.isEmpty()) {
                log.error("文生图任务提交失败, 无 task_id");
                return null;
            }
            log.info("文生图任务已提交, task_id={}", taskId);

            return pollImageTask(taskId);
        } catch (Exception e) {
            log.error("文生图调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 轮询图片生成任务，直到完成或超时
     * 最多轮询 60 次，每次间隔 2 秒（共 2 分钟超时）
     * @param taskId 任务ID
     * @return 图片字节，失败返回 null
     */
    private byte[] pollImageTask(String taskId) {
        String taskUrl = baseUrl + "/tasks/" + taskId;
        HttpHeaders pollHeaders = new HttpHeaders();
        pollHeaders.setBearerAuth(apiKey);
        HttpEntity<Void> pollEntity = new HttpEntity<>(pollHeaders);

        try {
            for (int i = 0; i < 60; i++) {
                Thread.sleep(2000);
                ResponseEntity<String> pollResp = restTemplate.exchange(taskUrl, HttpMethod.GET, pollEntity, String.class);
                String pollBody = pollResp.getBody();
                log.debug("轮询任务状态: {}", pollBody);

                JSONObject pollJson = JSON.parseObject(pollBody);
                JSONObject pollOutput = pollJson.getJSONObject("output");
                if (pollOutput == null) {
                    log.warn("轮询返回无 output 字段: {}", pollBody);
                    continue;
                }
                String taskStatus = pollOutput.getString("task_status");
                log.info("图片生成任务状态: {} (第{}次轮询)", taskStatus, i + 1);

                if ("SUCCEEDED".equals(taskStatus)) {
                    // 任务成功，获取图片 URL
                    // 新格式（qwen-image-3.0-pro）：output.choices[0].message.content[0].image
                    // 旧格式（wanx-v1）：output.results[0].url
                    String imageUrl = null;

                    JSONArray choices = pollOutput.getJSONArray("choices");
                    if (choices != null && !choices.isEmpty()) {
                        // 新格式：choices.message.content[].image
                        JSONArray msgContent = choices.getJSONObject(0)
                                .getJSONObject("message").getJSONArray("content");
                        if (msgContent != null && !msgContent.isEmpty()) {
                            imageUrl = msgContent.getJSONObject(0).getString("image");
                        }
                    }

                    if (imageUrl == null) {
                        // 旧格式：results[0].url
                        JSONArray results = pollOutput.getJSONArray("results");
                        if (results != null && !results.isEmpty()) {
                            imageUrl = results.getJSONObject(0).getString("url");
                        }
                    }

                    if (imageUrl == null) {
                        log.error("图片生成成功但无图片URL, 响应: {}", pollBody);
                        return null;
                    }
                    log.info("图片生成成功, 下载图片: {}", imageUrl);

                    byte[] imageBytes = downloadByUrl(imageUrl);
                    if (imageBytes == null || imageBytes.length == 0) {
                        log.error("图片下载为空");
                        return null;
                    }
                    log.info("图片下载完成, 大小: {} bytes", imageBytes.length);
                    return imageBytes;

                } else if ("FAILED".equals(taskStatus)) {
                    log.error("图片生成任务失败: {}", pollBody);
                    return null;
                }
                // PENDING / RUNNING 状态继续轮询
            }

            log.error("图片生成任务超时（2分钟内未完成）");
            return null;
        } catch (Exception e) {
            log.error("轮询任务失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 图片编辑：先理解原图内容，再根据用户指令生成新图片
     * 两步流程：
     * 1. 用视觉模型（qwen-vl-plus）理解原图，得到详细文字描述
     * 2. 将描述 + 用户编辑指令组合成提示词，用通义万相（wanx-v1）生成新图片
     * @param imageBytes      原图字节
     * @param userInstruction 用户编辑指令（如"把背景改成黑色"）
     * @return 编辑后的图片字节，失败返回 null
     */
    public byte[] editImage(byte[] imageBytes, String userInstruction) {
        if (!isReady()) {
            log.warn("API Key 未配置, 无法编辑图片");
            return null;
        }

        try {
            // 第一步：用视觉模型理解原图，提取详细描述
            log.info("图片编辑-第一步: 理解原图内容");
            String description = chatWithImage(imageBytes,
                    "详细描述这张图片中的主体、风格、颜色、背景等所有视觉细节，越详细越好");
            log.info("图片描述结果: {}", description);

            // 第二步：组合描述 + 用户指令，生成新图片
            String prompt = description + "。" + userInstruction
                    + "。根据以上描述生成高质量图片，画面细节丰富，构图美观，只专注画面生成，不要多余文字输出";
            log.info("图片编辑-第二步: 生成新图片, prompt={}", prompt);

            byte[] editedImage = generateImage(prompt);
            if (editedImage == null || editedImage.length == 0) {
                log.error("图片编辑生成失败");
                return null;
            }
            log.info("图片编辑成功, 大小: {} bytes", editedImage.length);
            return editedImage;
        } catch (Exception e) {
            log.error("图片编辑失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 图生图（img2img）：基于用户上传的原图，调用千问 qwen-image-3.0-pro 图生图接口进行编辑
     *
     * 【实现说明】
     * - 调用 qwen-image-3.0-pro 模型的 I2I（图生图/图像编辑）能力
     * - 通过 image-generation/generation 端点异步提交，messages 格式传入原图URL + 编辑指令
     * - strength=0.25：低值表示严格忠于原图，保留原图主体不被篡改
     * - 强制固定正向提示词（IMAGE_EDIT_FIXED_PROMPT）拼接用户编辑指令
     * - 反向提示词 negative_prompt 进一步约束模型不删除/不重绘主体
     *
     * 【费用提示】千问图生图接口按量计费，测试环境控制调用次数
     *
     * @param imageBytes      用户上传的原图字节
     * @param userInstruction 用户编辑指令（如"把背景改成白色"）
     * @return 编辑后的图片字节，失败返回 null
     * @throws RuntimeException 图片资源上传失败时抛出，消息为"图片资源上传失败，请重试"
     */
    public byte[] generateImageEdit(byte[] imageBytes, String userInstruction) {
        if (!isReady()) {
            log.warn("API Key 未配置, 无法调用图生图");
            return null;
        }

        try {
            // 第一步：上传用户上传原图到 DashScope，获取公网 URL
            log.info("图生图-第一步: 上传原图到 DashScope");
            String imageUrl = uploadImageToDashScope(imageBytes);
            log.info("原图上传成功, url={}", imageUrl);

            // 第二步：拼接强制固定正向提示词 + 用户编辑指令
            String prompt = IMAGE_EDIT_FIXED_PROMPT + userInstruction;
            log.info("图生图-第二步: 提交任务, model={}, strength={}", IMAGE_EDIT_MODEL, IMAGE_EDIT_STRENGTH);

            // 第三步：调用 qwen-image-3.0-pro 图生图接口（I2I）
            // 端点：/services/aigc/image-generation/generation（异步）
            // 请求格式：messages 数组，content 包含 image + text
            String submitUrl = baseUrl + "/services/aigc/image-generation/generation";

            // 构建 messages 格式请求体（qwen-image-3.0-pro 使用对话式格式）
            List<Map<String, String>> content = new ArrayList<>();
            content.add(Collections.singletonMap("image", imageUrl));  // 原图URL
            content.add(Collections.singletonMap("text", prompt));      // 强制提示词 + 用户编辑指令

            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", content);

            Map<String, Object> input = new HashMap<>();
            input.put("messages", Collections.singletonList(message));

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("n", 1);                                    // 生成1张
            parameters.put("strength", IMAGE_EDIT_STRENGTH);          // 相似度0.25，严格忠于原图
            parameters.put("prompt_extend", true);                    // 提示词智能改写
            parameters.put("negative_prompt", "删除主体，重绘主体，篡改角色，丢失原图内容，变形，模糊");  // 反向提示词

            Map<String, Object> body = new HashMap<>();
            body.put("model", IMAGE_EDIT_MODEL);
            body.put("input", input);
            body.put("parameters", parameters);

            // 构建 HTTP 请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            headers.set("X-DashScope-Async", "enable");                // 异步任务
            headers.set("X-DashScope-OssResourceResolve", "enable");  // 解析 oss:// URL
            HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);

            // 提交图生图任务
            ResponseEntity<String> resp = restTemplate.exchange(submitUrl, HttpMethod.POST, entity, String.class);
            String respBody = resp.getBody();
            log.info("图生图任务提交响应: {}", respBody);

            // 解析 task_id
            JSONObject respJson = JSON.parseObject(respBody);
            JSONObject output = respJson.getJSONObject("output");
            if (output == null) {
                log.error("图生图任务提交失败, 无 output 字段, 响应: {}", respBody);
                return null;
            }
            String taskId = output.getString("task_id");
            if (taskId == null || taskId.isEmpty()) {
                log.error("图生图任务提交失败, 无 task_id, 响应: {}", respBody);
                return null;
            }
            log.info("图生图任务已提交, task_id={}", taskId);

            // 第四步：轮询任务状态
            byte[] editedImage = pollImageTask(taskId);
            if (editedImage == null || editedImage.length == 0) {
                log.error("图生图生成失败");
                return null;
            }
            log.info("图生图成功, 大小: {} bytes", editedImage.length);
            return editedImage;
        } catch (RuntimeException e) {
            // 捕获图片上传 CDN 失败异常，向上抛出由调用方给用户提示
            log.error("图生图异常: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            // 其他异常做捕获，返回 null（调用方给用户友好提示）
            log.error("图生图失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 上传图片到 DashScope 临时存储空间，获取 oss:// URL
     * 用于图生图接口传入用户上传原图
     *
     * API 文档: https://help.aliyun.com/zh/model-studio/get-temporary-file-url
     * 两步流程：
     * 1. GET /api/v1/uploads?action=getPolicy&model=xxx → 获取 OSS 上传凭证
     * 2. POST 到 upload_host（OSS 地址），multipart 表单上传文件 → 返回 200 表示成功
     * 3. 拼接 oss:// + upload_dir + / + filename 得到临时 URL（有效期 48 小时）
     *
     * @param imageBytes 图片字节
     * @return oss:// 格式的临时 URL
     * @throws RuntimeException 上传失败时抛出，消息为"图片资源上传失败，请重试"
     */
    private String uploadImageToDashScope(byte[] imageBytes) {
        // 第一步：获取上传凭证（使用图像编辑模型名）
        String policyUrl = baseUrl + "/uploads?action=getPolicy&model=" + IMAGE_EDIT_MODEL;
        HttpHeaders policyHeaders = new HttpHeaders();
        policyHeaders.setBearerAuth(apiKey);
        policyHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> policyEntity = new HttpEntity<>(policyHeaders);

        String uploadHost;
        String uploadDir;
        String ossAccessKeyId;
        String signature;
        String policy;
        String xOssObjectAcl;
        String xOssForbidOverwrite;

        try {
            ResponseEntity<String> policyResp = restTemplate.exchange(
                    policyUrl, HttpMethod.GET, policyEntity, String.class);
            String policyBody = policyResp.getBody();
            log.info("获取上传凭证响应: {}", policyBody);

            JSONObject policyJson = JSON.parseObject(policyBody);
            JSONObject policyData = policyJson.getJSONObject("data");
            if (policyData == null) {
                log.error("上传凭证获取失败, 无 data 字段: {}", policyBody);
                throw new RuntimeException("图片资源上传失败，请重试");
            }
            uploadHost = policyData.getString("upload_host");
            uploadDir = policyData.getString("upload_dir");
            ossAccessKeyId = policyData.getString("oss_access_key_id");
            signature = policyData.getString("signature");
            policy = policyData.getString("policy");
            xOssObjectAcl = policyData.getString("x_oss_object_acl");
            xOssForbidOverwrite = policyData.getString("x_oss_forbid_overwrite");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取上传凭证失败: {}", e.getMessage(), e);
            throw new RuntimeException("图片资源上传失败，请重试");
        }

        // 第二步：上传文件到 OSS（multipart 表单）
        String fileName = "edit_ref_" + System.currentTimeMillis() + ".png";
        String key = uploadDir + "/" + fileName;

        // 构建 multipart 表单数据
        MultiValueMap<String, Object> formData = new LinkedMultiValueMap<>();
        formData.add("OSSAccessKeyId", ossAccessKeyId);
        formData.add("Signature", signature);
        formData.add("policy", policy);
        formData.add("x-oss-object-acl", xOssObjectAcl);
        formData.add("x-oss-forbid-overwrite", xOssForbidOverwrite);
        formData.add("key", key);
        formData.add("success_action_status", "200");

        // 图片字节封装为 ByteArrayResource（需重写 getFilename 让 Spring 识别为文件部分）
        ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        formData.add("file", imageResource);

        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> uploadEntity = new HttpEntity<>(formData, uploadHeaders);

        try {
            ResponseEntity<String> uploadResp = restTemplate.postForEntity(uploadHost, uploadEntity, String.class);
            log.info("文件上传到 OSS 响应: status={}", uploadResp.getStatusCode());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("文件上传到 OSS 失败: {}", e.getMessage(), e);
            throw new RuntimeException("图片资源上传失败，请重试");
        }

        // 第三步：拼接 oss:// URL（有效期 48 小时）
        String ossUrl = "oss://" + key;
        log.info("文件上传成功, oss_url={}", ossUrl);
        return ossUrl;
    }

    /**
     * 通过原始 URL 下载文件（绕过 RestTemplate，避免 URL 签名参数被重新编码）
     * 用于下载 OSS 签名 URL 返回的图片，防止 403 SignatureDoesNotMatch
     */
    private byte[] downloadByUrl(String urlStr) {
        try {
            java.net.URL url = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            try (java.io.InputStream is = conn.getInputStream();
                 java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                return baos.toByteArray();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            log.error("URL 下载失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private JSONObject postRequest(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return JSON.parseObject(resp.getBody());
    }

    // ===================== 测试入口 =====================
    // 密钥从环境变量 DASHSCOPE_API_KEY 读取，禁止硬编码
    // 测试命令：mvn compile exec:java -Dexec.mainClass="com.example.wechatbot.service.LlmService"
    //
    // 【费用提示】千问图生图按量计费，新用户有免费额度，测试环境注意控制调用次数
    // wanx-v1 计费：0.16元/张，新用户免费额度 500 张
    // 【费用提示】TTS语音合成按量计费，测试控制调用次数
    // 详见 https://help.aliyun.com/zh/model-studio/cosyvoice-tts-http-api
    public static void main(String[] args) {
        // 从环境变量读取 API Key，禁止硬编码
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.out.println("【错误】未设置环境变量 DASHSCOPE_API_KEY，请先配置 DashScope API Key");
            System.out.println("配置方式：set DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxx");
            return;
        }

        // 手动构造 LlmService（非 Spring 环境），密钥从环境变量读取
        LlmService service = new LlmService(new RestTemplate());
        try {
            java.lang.reflect.Field f;
            f = LlmService.class.getDeclaredField("apiKey"); f.setAccessible(true); f.set(service, apiKey);
            f = LlmService.class.getDeclaredField("textModel"); f.setAccessible(true); f.set(service, "qwen-turbo");
            f = LlmService.class.getDeclaredField("visionModel"); f.setAccessible(true); f.set(service, "qwen-vl-plus");
            f = LlmService.class.getDeclaredField("baseUrl"); f.setAccessible(true); f.set(service, "https://dashscope.aliyuncs.com/api/v1");
            f = LlmService.class.getDeclaredField("ttsModel"); f.setAccessible(true); f.set(service, "cosyvoice-v2");
            f = LlmService.class.getDeclaredField("ttsVoice"); f.setAccessible(true); f.set(service, "longxiaochun_v2");
            f = LlmService.class.getDeclaredField("imageModel"); f.setAccessible(true); f.set(service, "wanx-v1");
        } catch (Exception e) {
            System.out.println("【错误】反射注入失败: " + e.getMessage());
            return;
        }

        System.out.println("====== 千问 API 四场景测试 ======\n");
        System.out.println("【费用提示】千问图生图接口按量计费，测试环境控制调用次数\n");

        // 图片生成测试开关：设为 true 才执行图片生成测试（会产生计费）
        boolean runImageTests = false;

        // 场景1：普通文字对话（text 意图 → chat 接口）
        System.out.println("--- 场景1: 普通文字对话（text → chat）---");
        String reply = service.chat("你好，介绍一下你自己");
        System.out.println("回复: " + reply);
        System.out.println();

        // 场景2：TTS语音合成（voice 意图 → textToSpeech 接口，生成 MP3 文件）
        // 【费用提示】TTS语音合成按量计费，测试控制调用次数
        System.out.println("--- 场景2: TTS语音合成（voice → textToSpeech 生成 MP3）---");
        // 输入一段问候文本，测试语音合成
        String ttsText = "你好，我是你的微信助手，很高兴认识你！祝你今天一切顺利！";
        System.out.println("朗读文本: " + ttsText);
        byte[] audio = service.textToSpeech(ttsText);
        System.out.println("音频大小: " + (audio != null ? audio.length + " bytes" : "null（合成失败）"));
        // 将生成的MP3保存到本地文件验证
        if (audio != null && audio.length > 0) {
            try {
                java.io.File ttsFile = new java.io.File("tts_test_output.mp3");
                java.nio.file.Files.write(ttsFile.toPath(), audio);
                System.out.println("MP3已保存到本地文件: " + ttsFile.getAbsolutePath());
            } catch (Exception e) {
                System.out.println("保存MP3文件失败: " + e.getMessage());
            }
        }
        System.out.println();

        // 场景3：全新画图（image_gen 意图 → generateImage 文生图接口）
        System.out.println("--- 场景3: 全新画图（image_gen → generateImage qwen-image-3.0-pro）---");
        if (runImageTests) {
            byte[] image = service.generateImage("一只可爱的橘猫坐在窗台上");
            System.out.println("图片大小: " + (image != null ? image.length + " bytes" : "null（生成失败）"));
        } else {
            System.out.println("【已跳过】会产生计费，设 runImageTests=true 执行");
        }
        System.out.println();

        // 场景4：编辑已有图片（image_edit 意图 → generateImageEdit 图生图 img2img）
        // 调用 qwen-image-3.0-pro I2I 接口，传入原图 + 编辑指令
        System.out.println("--- 场景4: 编辑已有图片（image_edit → generateImageEdit qwen-image-3.0-pro img2img）---");
        if (runImageTests) {
            try {
                // 创建 256x256 蓝色测试图片（模拟用户上传原图）
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                g.setColor(java.awt.Color.BLUE);
                g.fillRect(0, 0, 256, 256);
                g.dispose();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(img, "png", baos);
                byte[] testImage = baos.toByteArray();
                System.out.println("测试原图大小: " + testImage.length + " bytes");
                System.out.println("model=" + IMAGE_EDIT_MODEL + ", strength=" + IMAGE_EDIT_STRENGTH);
                byte[] editedImage = service.generateImageEdit(testImage, "把背景改成白色");
                System.out.println("编辑后图片大小: " + (editedImage != null ? editedImage.length + " bytes" : "null（编辑失败）"));
            } catch (Exception e) {
                System.out.println("编辑图片异常: " + e.getMessage());
            }
        } else {
            System.out.println("【已跳过】会产生计费，设 runImageTests=true 执行");
        }
        System.out.println();

        System.out.println("====== 测试结束 ======");
    }
}
