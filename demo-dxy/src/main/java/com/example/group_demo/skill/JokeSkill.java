package com.example.group_demo.skill;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class JokeSkill implements BotSkill {

    private static final List<String> JOKES = List.of(
        "程序员最讨厌的数字是 0，因为 0 代表 false；最喜欢的是 1，因为 1 代表 true。但最害怕的是 404。",
        "产品经理对程序员说：「这个需求很简单，就改一行代码。」程序员：「那一行代码叫 try-catch-finally。」",
        "为什么程序员总分不清万圣节和圣诞节？因为 Oct 31 == Dec 25（八进制 31 = 十进制 25）。",
        "一个 SQL 查询走进酒吧，看到两张桌子，走过去问：「我能 Join 你们吗？」",
        "程序员去面试，面试官问：「你有什么缺点？」程序员：「我比较直接。」面试官：「能不能举个例子？」程序员：「不能。」",
        "世界上有 10 种人，一种懂二进制，一种不懂。",
        "程序员的女朋友说：「你跟我说说你的前任吧。」程序员：「404 Not Found。」",
        "调试代码就像当侦探：你是凶手，同时又是侦探。"
    );

    @Override
    public String name() {
        return "joke";
    }

    @Override
    public String description() {
        return "讲一个程序员笑话";
    }

    @Override
    public List<String> keywords() {
        return List.of("笑话", "讲个笑话", "讲笑话", "逗我开心", "来个笑话");
    }

    @Override
    public String execute(String userId, String userText) {
        return JOKES.get(ThreadLocalRandom.current().nextInt(JOKES.size()));
    }
}
