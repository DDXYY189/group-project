package com.example.group_demo.scheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提醒时间解析器：既支持 yyyy-MM-dd HH:mm 等标准格式，
 * 也支持“晚上7点”“明天早上9点”“下周一 8:30”“5分钟后”等自然语言。
 */
public final class ReminderTimeParser {

    private static final DateTimeFormatter SPACE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SPACE_SECONDS_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern CLOCK_TIME = Pattern.compile("(\\d{1,2})[:：](\\d{2})");
    private static final Pattern HOUR_MINUTE = Pattern.compile(
        "(\\d{1,2}|[零一二两三四五六七八九十]+)\\s*点\\s*(半|(\\d{1,2}|[零一二两三四五六七八九十]+)\\s*分?)?");
    private static final Pattern RELATIVE_DURATION = Pattern.compile(
        "(\\d+|[零一二两三四五六七八九十半]+)\\s*(个)?\\s*(分钟|小时|天)后");
    private static final Map<Character, Integer> CHINESE_DIGITS = Map.ofEntries(
        Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2), Map.entry('两', 2),
        Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6),
        Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9)
    );
    private static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};

    private ReminderTimeParser() {
    }

    public static Long parse(String value) {
        return parse(value, System.currentTimeMillis(), ZoneId.systemDefault());
    }

    public static Long parse(String value, long now, ZoneId zoneId) {
        Long standard = tryParseStandard(value);
        if (standard != null) {
            return standard;
        }
        Long natural = parseNatural(value, now, zoneId);
        if (natural != null) {
            return natural;
        }
        throw new IllegalArgumentException("触发时间格式不正确: " + value);
    }

    /**
     * 从自然语言中提取一天内的时间，供“每天提醒”使用。
     */
    public static LocalTime parseTimeText(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return null;
        }
        LocalTime time = parseTime(text);
        if (time == null) {
            return null;
        }
        int hour = time.getHour();
        if (containsAny(text, "中午")) {
            if (hour < 12) {
                return time.withHour(hour + 12);
            }
        } else if (containsAny(text, "下午", "傍晚")) {
            if (hour < 12) {
                return time.withHour(hour + 12);
            }
        } else if (containsAny(text, "晚上", "今晚", "明晚", "夜里", "深夜")) {
            if (hour < 12) {
                return time.withHour(hour + 12);
            }
        }
        return time;
    }

    private static Long tryParseStandard(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.matches("\\d+")) {
            return Long.parseLong(text);
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
            // 继续尝试本地时间格式
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 继续尝试空格格式
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(text, SPACE_FORMAT);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 继续尝试带秒格式
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(text, SPACE_SECONDS_FORMAT);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long parseNatural(String value, long now, ZoneId zoneId) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return null;
        }

        Long relative = parseRelativeDuration(text);
        if (relative != null) {
            return now + relative;
        }

        ZonedDateTime nowZoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId);
        LocalDate base = nowZoned.toLocalDate();
        LocalTime time = parseTimeText(text);
        int offset = dayOffset(text);
        Integer weekday = targetWeekday(text);
        boolean hasDay = offset >= 0 || weekday != null;

        if (time == null) {
            if (weekday == null && offset < 0) {
                return null;
            }
            time = LocalTime.of(9, 0);
        }

        LocalDate date = weekday != null
            ? base.plusDays(daysUntil(weekday, nowZoned.getDayOfWeek().getValue()))
            : base.plusDays(Math.max(offset, 0));
        ZonedDateTime result = date.atTime(time).atZone(zoneId);
        if (!hasDay && result.toInstant().toEpochMilli() <= now) {
            result = result.plusDays(1);
        }
        return result.toInstant().toEpochMilli();
    }

    private static Long parseRelativeDuration(String text) {
        Matcher matcher = RELATIVE_DURATION.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String amountText = matcher.group(1);
        String unit = matcher.group(3);
        long minutes;
        if ("半".equals(amountText)) {
            minutes = 30;
        } else {
            int amount = chineseNumber(amountText);
            if (amount < 0) {
                return null;
            }
            minutes = switch (unit) {
                case "天" -> amount * 24L * 60L;
                case "小时" -> amount * 60L;
                default -> amount;
            };
        }
        return minutes * 60_000L;
    }

    private static LocalTime parseTime(String text) {
        Matcher clock = CLOCK_TIME.matcher(text);
        if (clock.find()) {
            int hour = Integer.parseInt(clock.group(1));
            int minute = Integer.parseInt(clock.group(2));
            if (hour > 23 || minute > 59) {
                return null;
            }
            return LocalTime.of(hour, minute);
        }

        Matcher hourMinute = HOUR_MINUTE.matcher(text);
        if (!hourMinute.find()) {
            return null;
        }
        int hour = chineseNumber(hourMinute.group(1));
        if (hour < 0 || hour > 23) {
            return null;
        }
        int minute = 0;
        String tail = hourMinute.group(2);
        if (tail != null) {
            if (tail.contains("半")) {
                minute = 30;
            } else {
                int parsed = chineseNumber(tail.replace("分", "").trim());
                if (parsed >= 0) {
                    minute = parsed;
                }
            }
        }
        if (minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    private static int dayOffset(String text) {
        if (text.contains("大后天")) {
            return 3;
        }
        if (text.contains("后天")) {
            return 2;
        }
        if (text.contains("明天") || text.contains("明早") || text.contains("明晚")
            || text.contains("明日")) {
            return 1;
        }
        if (text.contains("今天") || text.contains("今日") || text.contains("今晚")) {
            return 0;
        }
        return -1;
    }

    private static Integer targetWeekday(String text) {
        for (int i = 1; i <= 7; i++) {
            String name = WEEKDAYS[i - 1];
            if (text.contains("星期" + name) || text.contains("周" + name)
                || text.contains("礼拜" + name)) {
                return i;
            }
        }
        return null;
    }

    private static int daysUntil(int targetDay, int todayDay) {
        int diff = (targetDay - todayDay + 7) % 7;
        return diff == 0 ? 7 : diff;
    }

    private static int chineseNumber(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.matches("\\d+")) {
            return Integer.parseInt(value);
        }
        int result = value.startsWith("十") ? 10 : 0;
        int start = value.startsWith("十") ? 1 : 0;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '十') {
                result = result == 0 ? 10 : result * 10;
                continue;
            }
            Integer digit = CHINESE_DIGITS.get(c);
            if (digit == null) {
                return -1;
            }
            result += digit;
        }
        return result;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
