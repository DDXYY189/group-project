package com.youkeda.wechatbotdemo;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * 图片生成服务：调用阿里云百炼（DashScope）的 wanx-v1 文生图接口生成图片。
 */
public class ImageService {

    private final String apiKey;
    private final ImageSynthesis imageSynthesis = new ImageSynthesis();

    public ImageService(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 根据文字提示生成一张图片，返回图片的二进制字节数组。
     *
     * @param prompt 图片提示词
     * @return 图片文件字节数组
     */
    public byte[] generateImage(String prompt) throws Exception {
        ImageSynthesisParam param = ImageSynthesisParam.builder()
                .model(ImageSynthesis.Models.WANX_V1)
                .prompt(prompt)
                .apiKey(apiKey)
                .n(1)
                .size("1024*1024")
                .build();

        // 1. 提交异步生图任务
        ImageSynthesisResult taskResult = imageSynthesis.call(param);
        String taskId = taskResult.getOutput().getTaskId();
        System.out.println("提交生图任务，taskId = " + taskId);

        // 2. 等待任务完成
        ImageSynthesisResult finalResult = imageSynthesis.wait(taskResult, apiKey);
        String taskStatus = finalResult.getOutput().getTaskStatus();
        System.out.println("生图任务状态: " + taskStatus);

        if (!"SUCCEEDED".equalsIgnoreCase(taskStatus)) {
            throw new RuntimeException("图片生成失败，状态: " + taskStatus
                    + ", message: " + finalResult.getOutput().getMessage());
        }

        // 3. 从结果中取出图片 URL
        String imageUrl = null;
        if (finalResult.getOutput().getResults() != null) {
            for (Map<String, String> result : finalResult.getOutput().getResults()) {
                if (result.containsKey("url") && result.get("url") != null && !result.get("url").isBlank()) {
                    imageUrl = result.get("url");
                    break;
                }
                if (result.containsKey("image_url") && result.get("image_url") != null && !result.get("image_url").isBlank()) {
                    imageUrl = result.get("image_url");
                    break;
                }
            }
        }
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new RuntimeException("图片生成结果中没有图片 URL");
        }

        System.out.println("图片生成完成，URL: " + imageUrl);
        return downloadImage(imageUrl);
    }

    /**
     * 从网络下载图片到内存。
     */
    private byte[] downloadImage(String imageUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);

        try (InputStream inputStream = connection.getInputStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }
            return outputStream.toByteArray();
        }
    }
}
