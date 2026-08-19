package com.example.demo_wkx.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LlmService {

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${llm.model:deepseek-chat}")
    private String model;

    @Value("${llm.system-prompt:你是一个友好的微信AI助手，请用简洁的中文回答用户的问题。}")
    private String systemPrompt;

    @Value("${llm.vision-base-url:}")
    private String visionBaseUrl;

    @Value("${llm.vision-api-key:}")
    private String visionApiKey;

    @Value("${llm.vision-model:}")
    private String visionModel;

    @Value("${weather.api-key:}")
    private String weatherApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, List<Object[]>> conversationHistory = new ConcurrentHashMap<>();

    private static final String[] CITIES = {
        "北京", "上海", "广州", "深圳", "杭州", "南京", "武汉", "成都", "重庆", "西安",
        "天津", "苏州", "长沙", "郑州", "青岛", "大连", "厦门", "昆明", "哈尔滨", "沈阳",
        "济南", "福州", "贵阳", "南昌", "太原", "兰州", "合肥", "南宁", "海口", "三亚",
        "拉萨", "乌鲁木齐", "银川", "西宁", "呼和浩特", "石家庄", "长春", "宁波", "温州",
        "佛山", "东莞", "珠海", "无锡", "烟台", "唐山", "赣州", "泉州", "保定", "临沂"
    };

    private static final java.util.Map<String, String> CITY_CODES = new java.util.HashMap<>();
    static {
        CITY_CODES.put("北京", "101010100");
        CITY_CODES.put("上海", "101020100");
        CITY_CODES.put("广州", "101280101");
        CITY_CODES.put("深圳", "101280601");
        CITY_CODES.put("杭州", "101210101");
        CITY_CODES.put("南京", "101190101");
        CITY_CODES.put("武汉", "101200101");
        CITY_CODES.put("成都", "101270101");
        CITY_CODES.put("重庆", "101040100");
        CITY_CODES.put("西安", "101110101");
        CITY_CODES.put("天津", "101030100");
        CITY_CODES.put("苏州", "101190401");
        CITY_CODES.put("沭阳", "101181102");
        CITY_CODES.put("宿迁", "101181100");
        CITY_CODES.put("长沙", "101250101");
        CITY_CODES.put("郑州", "101180101");
        CITY_CODES.put("青岛", "101120201");
        CITY_CODES.put("大连", "101070201");
        CITY_CODES.put("厦门", "101230201");
        CITY_CODES.put("昆明", "101290101");
        CITY_CODES.put("哈尔滨", "101050101");
        CITY_CODES.put("沈阳", "101090101");
        CITY_CODES.put("济南", "101120101");
        CITY_CODES.put("福州", "101230101");
        CITY_CODES.put("合肥", "101220101");
        CITY_CODES.put("南宁", "101300101");
        CITY_CODES.put("海口", "101310101");
        CITY_CODES.put("三亚", "101310201");
        CITY_CODES.put("拉萨", "101140101");
        CITY_CODES.put("乌鲁木齐", "101130101");
        CITY_CODES.put("石家庄", "101090201");
        CITY_CODES.put("长春", "101060101");
        CITY_CODES.put("宁波", "101210401");
        CITY_CODES.put("温州", "101210701");
        CITY_CODES.put("无锡", "101190301");
    }

    private boolean isWeatherQuery(String message) {
        String[] keywords = {"天气", "气温", "几度", "下雨", "下雪", "冷不冷", "热不", "穿什么",
                "带伞", "防晒", "湿度", "风力", "雾霾", "晴", "阴天", "暴雨"};
        for (String kw : keywords) {
            if (message.contains(kw)) return true;
        }
        return false;
    }

    private String extractCity(String message) {
        for (String city : CITIES) {
            if (message.contains(city)) return city;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("([\\u4e00-\\u9fa5]{2,6})的?(天气|气温|几度|温度)");
        java.util.regex.Matcher m = p.matcher(message);
        if (m.find()) {
            return m.group(1);
        }
        return "北京";
    }

    private String translateWeather(String desc) {
        if (desc == null || desc.isEmpty()) return "未知";
        String d = desc.toLowerCase();
        if (d.contains("sunny") || d.contains("clear")) return "晴天";
        if (d.contains("partly cloudy")) return "多云";
        if (d.contains("cloudy")) return "多云";
        if (d.contains("overcast")) return "阴天";
        if (d.contains("patchy rain") || d.contains("light rain") || d.contains("light drizzle")) return "小雨";
        if (d.contains("heavy rain")) return "大雨";
        if (d.contains("rain") || d.contains("drizzle")) return "雨";
        if (d.contains("snow")) return "雪";
        if (d.contains("fog")) return "雾";
        if (d.contains("mist")) return "薄雾";
        if (d.contains("thunder")) return "雷阵雨";
        if (d.contains("wind")) return "大风";
        return desc;
    }

    public String getWeather(String city) {
        if (weatherApiKey == null || weatherApiKey.isEmpty() || weatherApiKey.equals("YOUR_SENIVERSE_API_KEY_HERE")) {
            System.err.println("❌ 心知天气API Key未配置，请在application.properties中设置weather.api-key");
            return getWeatherFromOpenMeteo(city);
        }

        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String nowUrl = String.format(
                    "https://api.seniverse.com/v3/weather/now.json?key=%s&location=%s&language=zh-Hans&unit=c",
                    weatherApiKey, encodedCity);
            System.out.println("🌤️ 心知天气URL: " + nowUrl);

            HttpRequest nowReq = HttpRequest.newBuilder()
                    .uri(URI.create(nowUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> nowResp = httpClient.send(nowReq, HttpResponse.BodyHandlers.ofString());
            System.out.println("🌤️ 心知天气状态码: " + nowResp.statusCode());

            if (nowResp.statusCode() != 200) {
                System.err.println("❌ 心知天气API返回: " + nowResp.statusCode() + " - " + nowResp.body());
                return getWeatherFromOpenMeteo(city);
            }

            JsonNode root = objectMapper.readTree(nowResp.body());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                System.err.println("❌ 心知天气返回空结果");
                return getWeatherFromOpenMeteo(city);
            }

            JsonNode now = results.path(0).path("now");
            String text = now.path("text").asText("未知");
            String temp = now.path("temperature").asText("未知");
            String code = now.path("code").asText("");

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s实时天气：%s，气温%s°C", city, text, temp));

            String dailyUrl = String.format(
                    "https://api.seniverse.com/v3/weather/daily.json?key=%s&location=%s&language=zh-Hans&unit=c&start=0&days=3",
                    weatherApiKey, encodedCity);
            HttpRequest dailyReq = HttpRequest.newBuilder()
                    .uri(URI.create(dailyUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> dailyResp = httpClient.send(dailyReq, HttpResponse.BodyHandlers.ofString());
            if (dailyResp.statusCode() == 200) {
                JsonNode dailyRoot = objectMapper.readTree(dailyResp.body());
                JsonNode dailyArr = dailyRoot.path("results").path(0).path("daily");
                if (dailyArr.isArray() && !dailyArr.isEmpty()) {
                    JsonNode today = dailyArr.path(0);
                    String high = today.path("high").asText("");
                    String low = today.path("low").asText("");
                    String textDay = today.path("text_day").asText("");
                    String textNight = today.path("text_night").asText("");
                    String windDir = today.path("wind_direction").asText("");
                    String windScale = today.path("wind_scale").asText("");
                    String humidity = today.path("humidity").asText("");
                    sb.append(String.format("，%s/%s，最高%s°C最低%s°C", textDay, textNight, high, low));
                    if (!humidity.isEmpty()) sb.append("，湿度").append(humidity).append("%");
                    if (!windDir.isEmpty()) sb.append("，").append(windDir).append("风");
                    if (!windScale.isEmpty()) sb.append(windScale).append("级");

                    if (dailyArr.size() >= 2) {
                        JsonNode tomorrow = dailyArr.path(1);
                        sb.append(String.format("；明天%s/%s，%s~%s°C",
                                tomorrow.path("text_day").asText(""),
                                tomorrow.path("text_night").asText(""),
                                tomorrow.path("low").asText(""),
                                tomorrow.path("high").asText("")));
                    }
                    if (dailyArr.size() >= 3) {
                        JsonNode day3 = dailyArr.path(2);
                        sb.append(String.format("；后天%s/%s，%s~%s°C",
                                day3.path("text_day").asText(""),
                                day3.path("text_night").asText(""),
                                day3.path("low").asText(""),
                                day3.path("high").asText("")));
                    }
                }
            }

            String result = sb.toString();
            System.out.println("🌤️ 心知天气结果: " + result);
            return result;
        } catch (Exception e) {
            System.err.println("❌ 心知天气获取异常: " + e.getMessage());
            e.printStackTrace();
            return getWeatherFromOpenMeteo(city);
        }
    }

    private String getWeatherFromOpenMeteo(String city) {
        try {
            double[] coords = geocodeCity(city);
            if (coords == null) return null;

            String weatherUrl = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&timezone=Asia%%2FShanghai",
                    coords[0], coords[1]);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(weatherUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode cur = root.path("current");
                String temp = cur.path("temperature_2m").asText();
                String feels = cur.path("apparent_temperature").asText();
                String humidity = cur.path("relative_humidity_2m").asText();
                String wind = cur.path("wind_speed_10m").asText();
                int code = cur.path("weather_code").asInt();
                String desc = wmoToChinese(code);
                return String.format("%s实时天气：%s，温度%s°C，体感%s°C，湿度%s%%，风速%skm/h",
                        city, desc, temp, feels, humidity, wind);
            }
            return null;
        } catch (Exception e) {
            System.err.println("❌ Open-Meteo备用天气异常: " + e.getMessage());
            return null;
        }
    }

    private static final java.util.Map<String, double[]> CITY_COORDS = new java.util.HashMap<>();
    static {
        CITY_COORDS.put("北京", new double[]{39.9075, 116.39723});
        CITY_COORDS.put("上海", new double[]{31.22222, 121.45806});
        CITY_COORDS.put("广州", new double[]{23.12911, 113.26438});
        CITY_COORDS.put("深圳", new double[]{22.54554, 114.06867});
        CITY_COORDS.put("杭州", new double[]{30.29365, 120.16142});
        CITY_COORDS.put("南京", new double[]{32.06167, 118.77778});
        CITY_COORDS.put("武汉", new double[]{30.58333, 114.26667});
        CITY_COORDS.put("成都", new double[]{30.66667, 104.06667});
        CITY_COORDS.put("重庆", new double[]{29.56284, 106.55273});
        CITY_COORDS.put("西安", new double[]{34.25833, 108.92861});
        CITY_COORDS.put("天津", new double[]{39.14222, 117.17667});
        CITY_COORDS.put("苏州", new double[]{31.29933, 120.61944});
        CITY_COORDS.put("沭阳", new double[]{34.86167, 118.58611});
        CITY_COORDS.put("宿迁", new double[]{33.96333, 118.27556});
        CITY_COORDS.put("长沙", new double[]{28.19875, 112.97150});
        CITY_COORDS.put("郑州", new double[]{34.75356, 113.62750});
        CITY_COORDS.put("青岛", new double[]{36.06711, 120.38260});
        CITY_COORDS.put("大连", new double[]{38.91250, 121.60222});
        CITY_COORDS.put("厦门", new double[]{24.47978, 118.08194});
        CITY_COORDS.put("昆明", new double[]{25.03889, 102.71833});
        CITY_COORDS.put("哈尔滨", new double[]{45.75000, 126.65000});
        CITY_COORDS.put("沈阳", new double[]{41.80583, 123.43278});
        CITY_COORDS.put("济南", new double[]{36.86700, 116.20000});
        CITY_COORDS.put("福州", new double[]{26.06139, 119.30611});
        CITY_COORDS.put("合肥", new double[]{31.82057, 117.22722});
        CITY_COORDS.put("南宁", new double[]{22.81667, 108.31667});
        CITY_COORDS.put("海口", new double[]{20.03333, 110.33333});
        CITY_COORDS.put("三亚", new double[]{18.25278, 109.51222});
        CITY_COORDS.put("拉萨", new double[]{29.65000, 91.10000});
        CITY_COORDS.put("乌鲁木齐", new double[]{43.80000, 87.58333});
        CITY_COORDS.put("石家庄", new double[]{38.04167, 114.47861});
        CITY_COORDS.put("长春", new double[]{43.86667, 125.31667});
        CITY_COORDS.put("宁波", new double[]{29.87528, 121.54417});
        CITY_COORDS.put("温州", new double[]{27.99944, 120.66667});
        CITY_COORDS.put("无锡", new double[]{31.56667, 120.28333});
    }

    private double[] geocodeCity(String city) {
        double[] coords = CITY_COORDS.get(city);
        if (coords != null) {
            System.out.println("📍 坐标映射: " + city + " -> (" + coords[0] + ", " + coords[1] + ")");
            return coords;
        }
        try {
            String url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(city, StandardCharsets.UTF_8) + "&count=1&language=zh";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode results = root.path("results");
                if (results.isArray() && !results.isEmpty()) {
                    double lat = results.path(0).path("latitude").asDouble();
                    double lon = results.path(0).path("longitude").asDouble();
                    System.out.println("📍 地理编码: " + city + " -> (" + lat + ", " + lon + ")");
                    return new double[]{lat, lon};
                }
            }
        } catch (Exception e) {
            System.err.println("❌ 地理编码异常: " + e.getMessage());
        }
        return null;
    }

    private String wmoToChinese(int code) {
        switch (code) {
            case 0: return "晴天";
            case 1: return "多云";
            case 2: return "阴天";
            case 3: return "阴";
            case 45: case 48: return "雾";
            case 51: case 53: case 55: return "毛毛雨";
            case 56: case 57: return "冻毛毛雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 66: case 67: return "冻雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 77: return "阵雪";
            case 80: return "阵雨";
            case 81: return "中阵雨";
            case 82: return "大阵雨";
            case 85: return "阵雪";
            case 86: return "大阵雪";
            case 95: return "雷阵雨";
            case 96: case 99: return "雷阵雨伴冰雹";
            default: return "未知天气(" + code + ")";
        }
    }

    private String searchCityCode(String cityName) {
        String code = CITY_CODES.get(cityName);
        if (code != null) {
            System.out.println("🔍 城市映射: " + cityName + " -> " + code);
            return code;
        }
        try {
            String url = "http://toy1.weather.com.cn/search?cityname=" +
                    URLEncoder.encode(cityName, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Referer", "http://www.weather.com.cn")
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"id\":\"(\\d+)\"").matcher(body);
                if (m.find()) {
                    System.out.println("🔍 城市搜索: " + cityName + " -> " + m.group(1));
                    return m.group(1);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ 城市搜索异常: " + e.getMessage());
        }
        return null;
    }

    private String getWeatherByCode(String city, String cityCode) {
        try {
            String url = "http://t.weather.itboy.net/api/weather/city/" + cityCode;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                if (root.path("status").asInt() == 1000) {
                    JsonNode data = root.path("data");
                    JsonNode forecast = data.path("forecast").path(0);
                    String temp = data.path("wendu").asText();
                    String desc = forecast.path("type").asText();
                    String high = forecast.path("high").asText();
                    String low = forecast.path("low").asText();
                    String fengxiang = forecast.path("fengxiang").asText();
                    String fengli = forecast.path("fengli").asText();
                    String shidu = data.path("shidu").asText();
                    String pm25 = data.path("pm25").asText();
                    String quality = data.path("quality").asText();
                    return String.format("%s实时天气：%s，当前温度%s°C，湿度%s，%s %s，最高%s，最低%s，空气质量%s(%s)",
                            city, desc, temp, shidu, fengxiang, fengli, high, low, pm25, quality);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ 天气查询异常: " + e.getMessage());
        }
        return null;
    }

    private String getWeatherFromWttr(String city) {
        try {
            String url = "https://wttr.in/" + URLEncoder.encode(city, StandardCharsets.UTF_8) + "?format=j1&lang=zh";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode current = root.path("current_condition").path(0);
                String temp = current.path("temp_C").asText();
                String feelsLike = current.path("FeelsLikeC").asText();
                String humidity = current.path("humidity").asText();
                String windSpeed = current.path("windspeedKmph").asText();
                String desc = current.path("lang_zh").path(0).path("value").asText();
                if (desc.isEmpty()) {
                    desc = translateWeather(current.path("weatherDesc").path(0).path("value").asText());
                }
                return String.format("%s实时天气：%s，温度%s°C，体感%s°C，湿度%s%%，风速%skm/h",
                        city, desc, temp, feelsLike, humidity, windSpeed);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private ArrayNode getToolDefinitions() {
        ArrayNode tools = objectMapper.createArrayNode();

        ObjectNode weatherTool = objectMapper.createObjectNode();
        weatherTool.put("type", "function");
        ObjectNode weatherFunc = objectMapper.createObjectNode();
        weatherFunc.put("name", "get_weather");
        weatherFunc.put("description", "查询指定城市的实时天气信息，包括当前温度、天气状况、湿度、风向和未来3天预报。当用户询问天气、气温、穿什么衣服、是否需要带伞等问题时调用此工具。");
        ObjectNode weatherParams = objectMapper.createObjectNode();
        weatherParams.put("type", "object");
        ObjectNode weatherProps = objectMapper.createObjectNode();
        ObjectNode cityProp = objectMapper.createObjectNode();
        cityProp.put("type", "string");
        cityProp.put("description", "城市名称，如：北京、上海、广州、深圳、杭州、沭阳等");
        weatherProps.set("city", cityProp);
        weatherParams.set("properties", weatherProps);
        ArrayNode weatherRequired = objectMapper.createArrayNode();
        weatherRequired.add("city");
        weatherParams.set("required", weatherRequired);
        weatherFunc.set("parameters", weatherParams);
        weatherTool.set("function", weatherFunc);
        tools.add(weatherTool);

        ObjectNode timeTool = objectMapper.createObjectNode();
        timeTool.put("type", "function");
        ObjectNode timeFunc = objectMapper.createObjectNode();
        timeFunc.put("name", "get_current_time");
        timeFunc.put("description", "获取当前北京时间，包含公历日期时间、星期、农历日期（天干地支年、生肖）、节气等信息。当用户询问现在几点、今天日期、农历日期、星期几等问题时调用此工具。");
        ObjectNode timeParams = objectMapper.createObjectNode();
        timeParams.put("type", "object");
        timeParams.set("properties", objectMapper.createObjectNode());
        timeParams.set("required", objectMapper.createArrayNode());
        timeFunc.set("parameters", timeParams);
        timeTool.set("function", timeFunc);
        tools.add(timeTool);

        return tools;
    }

    private String executeTool(String toolName, String arguments) {
        try {
            JsonNode args = objectMapper.readTree(arguments);

            switch (toolName) {
                case "get_weather": {
                    String city = args.path("city").asText("北京");
                    System.out.println("🔧 [Function Calling] 执行 get_weather, city=" + city);
                    String weather = getWeather(city);
                    return weather != null ? weather : "天气查询失败，请稍后重试。";
                }
                case "get_current_time": {
                    System.out.println("🔧 [Function Calling] 执行 get_current_time");
                    java.time.LocalDateTime now = java.time.LocalDateTime.now();
                    String gregorian = now.format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
                    String[] weekNames = {"日", "一", "二", "三", "四", "五", "六"};
                    String weekDay = "星期" + weekNames[now.getDayOfWeek().getValue() % 7];
                    com.nlf.calendar.Solar solar = com.nlf.calendar.Solar.fromDate(new java.util.Date());
                    com.nlf.calendar.Lunar lunar = solar.getLunar();
                    String lunarDate = lunar.getYearInGanZhi() + "年" +
                            lunar.getMonthInChinese() + "月" +
                            lunar.getDayInChinese() +
                            "（" + lunar.getYearShengXiao() + "年）";
                    String jieQi = lunar.getJieQi();
                    return String.format("当前北京时间：%s %s\n农历：%s\n节气：%s",
                            gregorian, weekDay, lunarDate,
                            jieQi != null ? jieQi : "无");
                }
                default:
                    System.err.println("⚠ 未知工具: " + toolName);
                    return "未知工具: " + toolName;
            }
        } catch (Exception e) {
            System.err.println("❌ 工具执行异常: " + e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }

    public String chat(String userId, String userMessage) {
        return chat(userId, userMessage, false);
    }

    public String chat(String userId, String userMessage, boolean fromVoice) {
        try {
            List<Object[]> history = conversationHistory.computeIfAbsent(userId, k -> new ArrayList<>());

            ArrayNode messages = objectMapper.createArrayNode();

            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            String currentTime = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss")
            );
            com.nlf.calendar.Solar solar = com.nlf.calendar.Solar.fromDate(new java.util.Date());
            com.nlf.calendar.Lunar lunar = solar.getLunar();
            String lunarDate = lunar.getYearInGanZhi() + "年" + lunar.getMonthInChinese() + "月" + lunar.getDayInChinese() + "（" + lunar.getYearShengXiao() + "年）";
            String constellationKnowledge = "\n\n星座四象分类（请严格按此分类回答，不要混淆）：\n" +
                "火象星座：白羊座(3.21-4.19)、狮子座(7.23-8.22)、射手座(11.22-12.21)\n" +
                "土象星座：金牛座(4.20-5.20)、处女座(8.23-9.22)、摩羯座(12.22-1.19)\n" +
                "风象星座：双子座(5.21-6.21)、天秤座(9.23-10.23)、水瓶座(1.20-2.18)\n" +
                "水象星座：巨蟹座(6.22-7.22)、天蝎座(10.24-11.21)、双鱼座(2.19-3.20)";
            String intentPrompt = "\n\n【回复方式判断】请在回复内容最前面加上意图标签：\n" +
                "- [TEXT] 普通文字回复（默认，用于知识问答、闲聊、信息查询等）\n" +
                "- [VOICE] 语音回复（当用户发送语音消息，或用户明确要求语音回复/朗读时使用）\n" +
                "- [IMAGE:图片描述提示词] 生成图片（当用户要求画图、生成图片、创作视觉内容时使用）\n" +
                "标签后紧跟实际回复内容，不要解释你选择了哪种方式。\n" +
                "示例：\n用户:\"你好\" → [TEXT]你好！很高兴认识你...\n" +
                "用户:\"画一只猫\" → [IMAGE:一只可爱的橘猫在阳光下打盹，毛茸茸的]\n" +
                "用户:(语音)\"今天天气怎么样\" → [VOICE]今天北京天气晴朗，气温25度...";
            String voiceHint = fromVoice ? "\n（注意：用户本次发送的是语音消息，建议用[VOICE]标签回复）" : "";
            systemMsg.put("content", systemPrompt + "\n当前北京时间是：" + currentTime + "，农历" + lunarDate +
                "。当用户询问当前时间或日期时，请同时告诉用户公历和农历日期。" + constellationKnowledge + intentPrompt + voiceHint);
            messages.add(systemMsg);

            int startIdx = Math.max(0, history.size() - 10);
            for (int i = startIdx; i < history.size(); i++) {
                Object[] pair = history.get(i);
                ObjectNode userMsg = objectMapper.createObjectNode();
                userMsg.put("role", "user");
                userMsg.put("content", (String) pair[0]);
                messages.add(userMsg);

                ObjectNode assistantMsg = objectMapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", (String) pair[1]);
                messages.add(assistantMsg);
            }

            ObjectNode currentMsg = objectMapper.createObjectNode();
            currentMsg.put("role", "user");
            currentMsg.put("content", userMessage);
            messages.add(currentMsg);

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.set("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 2048);
            requestBody.put("temperature", 0.7);
            requestBody.set("tools", getToolDefinitions());
            requestBody.put("tool_choice", "auto");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "LLM请求失败，状态码: " + response.statusCode() + "，请检查API Key配置。";
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode message = root.path("choices").path(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");

            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                System.out.println("🔧 LLM 请求调用 " + toolCalls.size() + " 个工具");

                ObjectNode assistantToolMsg = objectMapper.createObjectNode();
                assistantToolMsg.put("role", "assistant");
                if (!message.path("content").isNull() && !message.path("content").asText("").isEmpty()) {
                    assistantToolMsg.put("content", message.path("content").asText(""));
                } else {
                    assistantToolMsg.putNull("content");
                }
                assistantToolMsg.set("tool_calls", toolCalls);
                messages.add(assistantToolMsg);

                for (JsonNode toolCall : toolCalls) {
                    String toolCallId = toolCall.path("id").asText();
                    String toolName = toolCall.path("function").path("name").asText();
                    String arguments = toolCall.path("function").path("arguments").asText("{}");

                    System.out.println("🔧 工具调用: " + toolName + " | 参数: " + arguments);
                    String toolResult = executeTool(toolName, arguments);
                    System.out.println("🔧 工具结果: " + toolResult);

                    ObjectNode toolResultMsg = objectMapper.createObjectNode();
                    toolResultMsg.put("role", "tool");
                    toolResultMsg.put("tool_call_id", toolCallId);
                    toolResultMsg.put("content", toolResult);
                    messages.add(toolResultMsg);
                }

                ObjectNode secondRequest = objectMapper.createObjectNode();
                secondRequest.put("model", model);
                secondRequest.set("messages", messages);
                secondRequest.put("stream", false);
                secondRequest.put("max_tokens", 2048);
                secondRequest.put("temperature", 0.7);
                secondRequest.set("tools", getToolDefinitions());
                secondRequest.put("tool_choice", "auto");

                HttpRequest secondReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(secondRequest)))
                        .timeout(Duration.ofSeconds(60))
                        .build();

                HttpResponse<String> secondResp = httpClient.send(secondReq, HttpResponse.BodyHandlers.ofString());

                if (secondResp.statusCode() != 200) {
                    System.err.println("❌ 第二次LLM请求失败: " + secondResp.statusCode());
                    return "工具调用完成，但LLM生成回复失败。状态码: " + secondResp.statusCode();
                }

                JsonNode secondRoot = objectMapper.readTree(secondResp.body());
                String reply = secondRoot.path("choices").path(0).path("message").path("content").asText();

                String cleanReply = stripIntentTag(reply);
                history.add(new Object[]{userMessage, cleanReply});
                while (history.size() > 20) {
                    history.remove(0);
                }

                return reply;
            } else {
                String reply = message.path("content").asText();

                String cleanReply = stripIntentTag(reply);
                history.add(new Object[]{userMessage, cleanReply});
                while (history.size() > 20) {
                    history.remove(0);
                }

                return reply;
            }
        } catch (Exception e) {
            return "LLM处理异常: " + e.getMessage();
        }
    }

    private String stripIntentTag(String reply) {
        if (reply == null) return "";
        if (reply.startsWith("[TEXT]")) {
            return reply.substring(6).trim();
        } else if (reply.startsWith("[VOICE]")) {
            return reply.substring(7).trim();
        } else if (reply.startsWith("[IMAGE:")) {
            int end = reply.indexOf("]");
            if (end > 0) {
                return "已为用户生成图片: " + reply.substring(7, end).trim();
            }
        }
        return reply;
    }

    public byte[] generateImage(String prompt) {
        try {
            String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
            String imageUrl = "https://image.pollinations.ai/prompt/" + encodedPrompt
                    + "?width=1024&height=1024&nologo=true";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(90))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                return response.body();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public String describeImage(byte[] imageBytes) {
        try {
            if (imageBytes == null || imageBytes.length == 0) {
                return "收到图片，但无法下载图片数据。";
            }

            String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);
            System.out.println("✅ 图片数据获取成功，大小: " + imageBytes.length + " bytes");

            String useBaseUrl = (visionBaseUrl != null && !visionBaseUrl.isEmpty()) ? visionBaseUrl : baseUrl;
            String useApiKey = (visionApiKey != null && !visionApiKey.isEmpty()) ? visionApiKey : apiKey;
            String useModel = (visionModel != null && !visionModel.isEmpty()) ? visionModel : model;

            boolean visionConfigured = (visionBaseUrl != null && !visionBaseUrl.isEmpty());

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");

            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", "请描述这张图片的内容，用中文简洁回答。");
            content.add(textPart);

            ObjectNode imagePart = objectMapper.createObjectNode();
            imagePart.put("type", "image_url");
            ObjectNode imgUrlNode = objectMapper.createObjectNode();
            imgUrlNode.put("url", "data:image/jpeg;base64," + base64Image);
            imagePart.set("image_url", imgUrlNode);
            content.add(imagePart);

            userMsg.set("content", content);
            messages.add(userMsg);

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", useModel);
            requestBody.set("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 1024);

            System.out.println("📤 调用视觉API: " + useBaseUrl + " | 模型: " + useModel);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(useBaseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + useApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                return root.path("choices").path(0).path("message").path("content").asText();
            } else {
                System.err.println("❌ 视觉API返回: " + response.statusCode() + " - " + response.body());
                if (!visionConfigured) {
                    return "收到你的图片！当前文本模型(" + useModel + ")不支持图片理解。\n" +
                           "要启用图片分析，请在application.properties中配置视觉API：\n" +
                           "1. Google Gemini(免费): https://aistudio.google.com 获取Key\n" +
                           "   llm.vision-base-url=https://generativelanguage.googleapis.com/v1beta/openai\n" +
                           "   llm.vision-model=gemini-1.5-flash\n" +
                           "   llm.vision-api-key=你的Gemini Key";
                }
                return "图片分析失败，API返回: " + response.statusCode();
            }
        } catch (Exception e) {
            System.err.println("❌ 图片分析异常: " + e.getMessage());
            return "图片分析失败: " + e.getMessage();
        }
    }

    public String transcribeAudio(byte[] audioData, String format) {
        try {
            if (audioData == null || audioData.length == 0) {
                return null;
            }

            String boundary = "----FormBoundary" + System.currentTimeMillis();
            String fileName = "voice." + (format != null ? format : "amr");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            String partHeader = "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                    "paraformer-v2\r\n" +
                    "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n";
            bos.write(partHeader.getBytes(StandardCharsets.UTF_8));
            bos.write(audioData);
            String partFooter = "\r\n--" + boundary + "--\r\n";
            bos.write(partFooter.getBytes(StandardCharsets.UTF_8));

            byte[] bodyBytes = bos.toByteArray();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/audio/transcriptions"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                return root.path("text").asText();
            } else {
                System.err.println("❌ 语音识别API返回: " + response.statusCode() + " - " + response.body());
                return null;
            }
        } catch (Exception e) {
            System.err.println("❌ 语音识别异常: " + e.getMessage());
            return null;
        }
    }

    public byte[] textToSpeech(String text) {
        try {
            if (text == null || text.isEmpty()) return null;

            ObjectNode input = objectMapper.createObjectNode();
            input.put("text", text);
            input.put("voice", "longfei_v3");
            input.put("format", "mp3");
            input.put("sample_rate", 16000);

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", "cosyvoice-v3-flash");
            requestBody.set("input", input);

            String ttsUrl = baseUrl.replace("/compatible-mode/v1", "/api/v1") + "/services/audio/tts/SpeechSynthesizer";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ttsUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                String audioUrl = json.path("output").path("audio").path("url").asText(null);
                if (audioUrl != null && !audioUrl.isEmpty()) {
                    System.out.println("🔊 TTS合成成功，audio URL: " + audioUrl);
                    HttpRequest dlReq = HttpRequest.newBuilder()
                            .uri(URI.create(audioUrl))
                            .timeout(Duration.ofSeconds(30))
                            .build();
                    HttpResponse<byte[]> dlResp = httpClient.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());
                    if (dlResp.statusCode() == 200) {
                        System.out.println("🔊 音频下载完成，大小: " + dlResp.body().length + " bytes");
                        return dlResp.body();
                    }
                }
                System.err.println("❌ TTS响应中无音频URL: " + response.body());
                return null;
            } else {
                System.err.println("❌ TTS返回: " + response.statusCode() + " - " + response.body());
                return null;
            }
        } catch (Exception e) {
            System.err.println("❌ TTS异常: " + e.getMessage());
            return null;
        }
    }

    public void clearHistory(String userId) {
        conversationHistory.remove(userId);
    }
}
