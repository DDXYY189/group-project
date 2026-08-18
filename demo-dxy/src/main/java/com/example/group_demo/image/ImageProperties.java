package com.example.group_demo.image;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "image")
public class ImageProperties {

    private String baseUrl = "https://dashscope.aliyuncs.com";
    private String model = "wanx2.1-t2i-turbo";
    private String editModel = "qwen-image-edit";
    private String size = "1024*1024";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getEditModel() {
        return editModel;
    }

    public void setEditModel(String editModel) {
        this.editModel = editModel;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }
}
