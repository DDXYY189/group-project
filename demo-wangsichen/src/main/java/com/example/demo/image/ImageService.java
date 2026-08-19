package com.example.demo.image;

import com.example.demo.config.ImageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private final ImageProperties properties;
    private final RestClient restClient;

    public ImageService(ImageProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    public byte[] generateImage(String prompt) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置图片生成 API Key");
            return null;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", properties.getModel());
            body.put("prompt", prompt);
            body.put("image_size", properties.getImageSize());
            body.put("batch_size", 1);

            JsonNode response = restClient.post()
                    .uri(properties.getBaseUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            String url = response.path("images").path(0).path("url").asText("");
            if (url.isBlank()) {
                log.warn("图片生成响应中没有 url：{}", response);
                return null;
            }

            return restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(byte[].class);
        } catch (Exception e) {
            log.error("生成图片失败", e);
            return null;
        }
    }
}
