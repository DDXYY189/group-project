package com.example.demo.llm;

public record Decision(String intent, String content) {

    public static Decision text(String content) {
        return new Decision("text", content);
    }

    public static Decision voice(String content) {
        return new Decision("voice", content);
    }

    public static Decision image(String content) {
        return new Decision("image", content);
    }
}
