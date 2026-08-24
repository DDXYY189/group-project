package com.example.group_demo.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日期倒计时 Skill。
 * 触发关键词：倒计时、距离、还有多久
 * 业务逻辑：提取目标日期，计算当前日期到目标日期相隔天数。
 */
@Service
public class CountdownSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(CountdownSkill.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String[] KEYWORDS = {"倒计时", "距离", "还有多久"};

    // 支持的日期格式：12月4日、12月04日、2024年12月4日、2024-12-04、12/4、12-04
    private static final Pattern[] DATE_PATTERNS = {
        Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日"),
        Pattern.compile("(\\d{1,2})月(\\d{1,2})日"),
        Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})"),
        Pattern.compile("(\\d{1,2})[-/](\\d{1,2})")
    };

    private static final DateTimeFormatter FULL_FORMAT = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    @Override
    public String name() {
        return "countdown";
    }

    @Override
    public String[] keywords() {
        return KEYWORDS;
    }

    @Override
    public String execute(String userId, String userText) {
        log.info("【执行Skill】CountdownSkill userId={} userText={}", userId, userText);
        LocalDate targetDate = parseDate(userText);
        if (targetDate == null) {
            log.info("CountdownSkill 未识别到合法日期 userText={}", userText);
            return "请告诉我你要计算到哪一天的倒计时，例如：距离12月4日还有多久";
        }
        LocalDate today = LocalDate.now(ZONE);
        long daysBetween = ChronoUnit.DAYS.between(today, targetDate);
        String formattedDate = targetDate.format(FULL_FORMAT);
        String result;
        if (daysBetween > 0) {
            result = String.format("距离%s还有%d天", formattedDate, daysBetween);
        } else if (daysBetween < 0) {
            result = String.format("%s已经过去%d天了", formattedDate, Math.abs(daysBetween));
        } else {
            result = String.format("就是今天！%s", formattedDate);
        }
        log.info("CountdownSkill 计算完成: target={} days={} result={}", targetDate, daysBetween, result);
        return result;
    }

    /**
     * 从用户输入中提取并解析日期。
     * 支持格式：
     *   - 2024年12月4日 / 2024年12月04日
     *   - 12月4日 / 12月04日（当年）
     *   - 2024-12-04 / 2024/12/04
     *   - 12-04 / 12/4（当年）
     */
    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int currentYear = LocalDate.now(ZONE).getYear();

        for (int i = 0; i < DATE_PATTERNS.length; i++) {
            Matcher matcher = DATE_PATTERNS[i].matcher(text);
            if (matcher.find()) {
                try {
                    int year;
                    int month;
                    int day;
                    if (i == 0 || i == 2) {
                        // 带年份的格式
                        year = Integer.parseInt(matcher.group(1));
                        month = Integer.parseInt(matcher.group(2));
                        day = Integer.parseInt(matcher.group(3));
                    } else {
                        // 不带年份的格式，默认当年
                        year = currentYear;
                        month = Integer.parseInt(matcher.group(1));
                        day = Integer.parseInt(matcher.group(2));
                    }
                    LocalDate date = LocalDate.of(year, month, day);
                    // 如果不带年份且日期已过，假设是明年
                    if ((i == 1 || i == 3) && date.isBefore(LocalDate.now(ZONE))) {
                        date = date.plusYears(1);
                    }
                    return date;
                } catch (NumberFormatException | DateTimeParseException e) {
                    log.debug("日期解析失败 pattern={} text={}", i, text, e);
                }
            }
        }
        return null;
    }
}
