package com.example.group_demo.image;

import com.example.group_demo.llm.LlmProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private final LlmProperties llmProperties;
    private final ImageProperties imageProperties;
    private final RestClient restClient;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public ImageService(LlmProperties llmProperties, ImageProperties imageProperties) {
        this.llmProperties = llmProperties;
        this.imageProperties = imageProperties;
        this.restClient = RestClient.builder().build();
    }

    public byte[] generateImage(String prompt) throws IOException, InterruptedException {
        String apiKey = llmProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LLM API key 未配置");
        }

        Map<String, Object> requestBody = Map.of(
            "model", imageProperties.getModel(),
            "input", Map.of("prompt", prompt),
            "parameters", Map.of("size", imageProperties.getSize(), "n", 1)
        );

        TaskResponse submit = restClient.post()
            .uri(imageProperties.getBaseUrl() + "/api/v1/services/aigc/text2image/image-synthesis")
            .header("Authorization", "Bearer " + apiKey)
            .header("X-DashScope-Async", "enable")
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(TaskResponse.class);

        String taskId = submit == null || submit.output() == null ? null : submit.output().taskId();
        if (taskId == null) {
            throw new IllegalStateException("图片任务创建失败");
        }

        String imageUrl = null;
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            TaskResponse poll = restClient.get()
                .uri(imageProperties.getBaseUrl() + "/api/v1/tasks/{taskId}", taskId)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .body(TaskResponse.class);
            if (poll != null && poll.output() != null) {
                String status = poll.output().taskStatus();
                log.info("图片任务 {} status={}", taskId, status);
                if ("SUCCEEDED".equals(status)
                    && poll.output().results() != null && !poll.output().results().isEmpty()) {
                    imageUrl = poll.output().results().get(0).url();
                    break;
                }
                if ("FAILED".equals(status)) {
                    throw new IllegalStateException("图片生成失败 task=" + taskId);
                }
            }
            Thread.sleep(2000);
        }

        if (imageUrl == null) {
            throw new IllegalStateException("图片生成超时");
        }
        byte[] image = downloadImage(imageUrl);
        log.info("图片生成成功 size={}", image.length);
        return image;
    }

    public byte[] editImage(byte[] imageBytes, String instruction)
        throws IOException, InterruptedException {
        String apiKey = llmProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LLM API key 未配置");
        }

        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> requestBody = Map.of(
            "model", imageProperties.getEditModel(),
            "input", Map.of("messages", List.of(
                Map.of("role", "user", "content", List.of(
                    Map.of("image", dataUri),
                    Map.of("text", instruction)
                ))
            )),
            "parameters", Map.of("result_format", "message")
        );

        EditResponse response = restClient.post()
            .uri(imageProperties.getBaseUrl() + "/api/v1/services/aigc/multimodal-generation/generation")
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(EditResponse.class);

        if (response == null || response.output() == null || response.output().choices() == null
            || response.output().choices().isEmpty() || response.output().choices().get(0).message() == null
            || response.output().choices().get(0).message().content() == null
            || response.output().choices().get(0).message().content().isEmpty()) {
            throw new IllegalStateException("图像编辑返回结果为空");
        }

        String imageUrl = response.output().choices().get(0).message().content().get(0).image();
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalStateException("图像编辑结果缺少图片地址");
        }
        byte[] edited = downloadImage(imageUrl);
        log.info("图像编辑成功 size={}", edited.length);
        return edited;
    }

    private byte[] downloadImage(String imageUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("图片下载失败 status=" + response.statusCode());
        }
        byte[] image = response.body();
        if (image == null || image.length == 0) {
            throw new IllegalStateException("图片下载内容为空");
        }
        if (!isImageBytes(image)) {
            throw new IllegalStateException("图片内容不是有效图片，前16字节=" + toHex(image));
        }
        return image;
    }

    private boolean isImageBytes(byte[] bytes) {
        if (bytes.length < 4) {
            return false;
        }
        boolean png = (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        boolean jpeg = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
        boolean webp = bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F';
        return png || jpeg || webp;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(16, bytes.length); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    public record TaskResponse(@JsonProperty("output") Output output) {
        public record Output(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("task_status") String taskStatus,
            List<Result> results) {
        }

        public record Result(String url) {
        }
    }

    public record EditResponse(@JsonProperty("output") Output output) {
        public record Output(List<Choice> choices) {
        }

        public record Choice(Message message) {
        }

        public record Message(List<Content> content) {
        }

        public record Content(String image) {
        }
    }
}
