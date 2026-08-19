package com.example.demo.wechat;

public record InboundMessage(String userId, String contextToken, String text, boolean voice) {
}
