package com.example.group_demo.tool;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 随机数 Skill：关键词直接路由，不走 LLM。
 * 用户说"帮我抽个随机数 / 抽签 / 掷骰子"即命中。
 */
@Service
public class RandomSkill implements BotSkill {

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    @Override
    public String name() {
        return "random_skill";
    }

    @Override
    public List<String> keywords() {
        return List.of("随机数", "抽签", "掷骰子", "骰子", "抽一个", "随机");
    }

    @Override
    public String execute(String userId, String text) {
        int min = 1;
        int max = 100;
        Matcher matcher = NUMBER.matcher(text);
        if (matcher.find()) {
            int first = Integer.parseInt(matcher.group());
            if (matcher.find()) {
                // 用户给了两个数字，小的是 min，大的是 max
                int second = Integer.parseInt(matcher.group());
                min = Math.min(first, second);
                max = Math.max(first, second);
            } else {
                // 只给了一个数字，当成 max
                max = first;
            }
        }
        if (min >= max) {
            min = 1;
            max = 100;
        }
        int result = ThreadLocalRandom.current().nextInt(min, max + 1);
        return "随机结果：" + min + " 到 " + max + " 之间，抽到了 " + result;
    }
}
