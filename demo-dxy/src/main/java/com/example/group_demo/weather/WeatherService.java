package com.example.group_demo.weather;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.group_demo.config.RestClientFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 天气查询服务：心知天气(Seniverse)为主，Open-Meteo 为备用。
 * 支持实时天气 + 3天预报 + 出行建议。
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    /** 中国主要城市坐标（Open-Meteo 地理编码不支持部分中国城市） */
    private static final Map<String, double[]> CITY_COORDS = Map.ofEntries(
        Map.entry("北京", new double[]{39.9075, 116.39723}),
        Map.entry("上海", new double[]{31.22222, 121.45806}),
        Map.entry("广州", new double[]{23.12911, 113.26438}),
        Map.entry("深圳", new double[]{22.54554, 114.06867}),
        Map.entry("杭州", new double[]{30.29365, 120.16142}),
        Map.entry("南京", new double[]{32.06167, 118.77778}),
        Map.entry("武汉", new double[]{30.58333, 114.26667}),
        Map.entry("成都", new double[]{30.66667, 104.06667}),
        Map.entry("重庆", new double[]{29.56284, 106.55273}),
        Map.entry("西安", new double[]{34.25833, 108.92861}),
        Map.entry("天津", new double[]{39.14222, 117.17667}),
        Map.entry("苏州", new double[]{31.29933, 120.61944}),
        Map.entry("沭阳", new double[]{34.86167, 118.58611}),
        Map.entry("宿迁", new double[]{33.96333, 118.27556}),
        Map.entry("长沙", new double[]{28.19875, 112.97150}),
        Map.entry("郑州", new double[]{34.75356, 113.62750}),
        Map.entry("青岛", new double[]{36.06711, 120.38260}),
        Map.entry("大连", new double[]{38.91250, 121.60222}),
        Map.entry("厦门", new double[]{24.47978, 118.08194}),
        Map.entry("昆明", new double[]{25.03889, 102.71833}),
        Map.entry("哈尔滨", new double[]{45.75000, 126.65000}),
        Map.entry("沈阳", new double[]{41.80583, 123.43278}),
        Map.entry("济南", new double[]{36.86700, 116.20000}),
        Map.entry("福州", new double[]{26.06139, 119.30611}),
        Map.entry("合肥", new double[]{31.82057, 117.22722}),
        Map.entry("南宁", new double[]{22.81667, 108.31667}),
        Map.entry("海口", new double[]{20.03333, 110.33333}),
        Map.entry("三亚", new double[]{18.25278, 109.51222}),
        Map.entry("拉萨", new double[]{29.65000, 91.10000}),
        Map.entry("乌鲁木齐", new double[]{43.80000, 87.58333}),
        Map.entry("石家庄", new double[]{38.04167, 114.47861}),
        Map.entry("长春", new double[]{43.86667, 125.31667}),
        Map.entry("宁波", new double[]{29.87528, 121.54417}),
        Map.entry("温州", new double[]{27.99944, 120.66667}),
        Map.entry("无锡", new double[]{31.56667, 120.28333})
    );

    private final WeatherProperties properties;
    private final RestClient restClient;

    public WeatherService(WeatherProperties properties) {
        this.properties = properties;
        this.restClient = RestClientFactory.builder().build();
    }

    /**
     * 查询城市天气：实时天气 + 3天预报 + 出行建议。
     * 心知天气 API 为主，Open-Meteo 为备用。
     */
    public String getWeatherText(String location) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("心知天气 API key 未配置，使用 Open-Meteo 备用源");
            return getWeatherFromOpenMeteo(location);
        }

        try {
            StringBuilder sb = new StringBuilder();

            // 1. 实时天气
            WeatherResponse nowResp = queryNow(apiKey, location);
            if (nowResp == null || nowResp.results() == null || nowResp.results().isEmpty()) {
                log.warn("心知天气返回空结果，切换备用源");
                return getWeatherFromOpenMeteo(location);
            }

            WeatherResponse.Result result = nowResp.results().get(0);
            String city = result.location() != null && result.location().name() != null
                    ? result.location().name() : location;
            WeatherResponse.Now now = result.now();
            sb.append(String.format("%s实时天气：%s，气温%s°C", city, now.text(), now.temperature()));

            // 2. 3天预报
            DailyWeatherResponse dailyResp = queryDaily(apiKey, location);
            if (dailyResp != null && dailyResp.results() != null && !dailyResp.results().isEmpty()) {
                List<DailyWeatherResponse.Daily> dailyList = dailyResp.results().get(0).daily();
                if (dailyList != null && !dailyList.isEmpty()) {
                    DailyWeatherResponse.Daily today = dailyList.get(0);
                    sb.append(String.format("，%s/%s，最高%s°C最低%s°C",
                            today.textDay(), today.textNight(),
                            today.high(), today.low()));
                    if (today.humidity() != null && !today.humidity().isEmpty()) {
                        sb.append("，湿度").append(today.humidity()).append("%");
                    }
                    if (today.windDirection() != null && !today.windDirection().isEmpty()) {
                        sb.append("，").append(today.windDirection()).append("风");
                        if (today.windScale() != null && !today.windScale().isEmpty()) {
                            sb.append(today.windScale()).append("级");
                        }
                    }

                    if (dailyList.size() >= 2) {
                        DailyWeatherResponse.Daily tomorrow = dailyList.get(1);
                        sb.append(String.format("；明天%s/%s，%s~%s°C",
                                tomorrow.textDay(), tomorrow.textNight(),
                                tomorrow.low(), tomorrow.high()));
                    }
                    if (dailyList.size() >= 3) {
                        DailyWeatherResponse.Daily day3 = dailyList.get(2);
                        sb.append(String.format("；后天%s/%s，%s~%s°C",
                                day3.textDay(), day3.textNight(),
                                day3.low(), day3.high()));
                    }
                }
            }

            // 3. 出行建议
            sb.append("。出行建议：").append(buildAdvice(now.text(), now.temperature())).append("祝你心情愉快～");

            String reply = sb.toString();
            log.info("天气查询成功 city={} reply={}", city, reply);
            return reply;
        } catch (Exception e) {
            log.warn("心知天气查询异常，切换备用源: {}", e.getMessage());
            return getWeatherFromOpenMeteo(location);
        }
    }

    /** 查询心知天气实时天气 */
    private WeatherResponse queryNow(String apiKey, String location) {
        Map<String, Object> params = Map.of(
            "key", apiKey,
            "location", location,
            "language", properties.getLanguage(),
            "unit", properties.getUnit()
        );
        return restClient.get()
            .uri(properties.getBaseUrl() + "?key={key}&location={location}&language={language}&unit={unit}",
                params)
            .retrieve()
            .body(WeatherResponse.class);
    }

    /** 查询心知天气3天预报 */
    private DailyWeatherResponse queryDaily(String apiKey, String location) {
        String dailyUrl = properties.getBaseUrl().replace("now.json", "daily.json");
        Map<String, Object> params = Map.of(
            "key", apiKey,
            "location", location,
            "language", properties.getLanguage(),
            "unit", properties.getUnit(),
            "start", "0",
            "days", "3"
        );
        try {
            return restClient.get()
                .uri(dailyUrl + "?key={key}&location={location}&language={language}&unit={unit}&start={start}&days={days}",
                    params)
                .retrieve()
                .body(DailyWeatherResponse.class);
        } catch (Exception e) {
            log.warn("3天预报查询失败: {}", e.getMessage());
            return null;
        }
    }

    /** Open-Meteo 备用天气源 */
    private String getWeatherFromOpenMeteo(String city) {
        try {
            double[] coords = geocodeCity(city);
            if (coords == null) {
                return "天气查询失败：无法定位城市 " + city;
            }

            String weatherUrl = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&timezone=Asia%%2FShanghai",
                coords[0], coords[1]);

            OpenMeteoResponse response = restClient.get()
                .uri(weatherUrl)
                .retrieve()
                .body(OpenMeteoResponse.class);

            if (response == null || response.current() == null) {
                return "天气查询失败：Open-Meteo 返回为空";
            }

            OpenMeteoResponse.Current cur = response.current();
            String desc = wmoToChinese(cur.weatherCode());
            String reply = String.format("%s实时天气：%s，温度%s°C，体感%s°C，湿度%s%%，风速%skm/h",
                city, desc, cur.temperature(), cur.apparentTemperature(),
                cur.relativeHumidity(), cur.windSpeed());
            reply += "。出行建议：" + buildAdvice(desc, cur.temperature()) + "祝你心情愉快～";
            log.info("Open-Meteo 天气查询成功 city={} reply={}", city, reply);
            return reply;
        } catch (Exception e) {
            log.warn("Open-Meteo 查询异常: {}", e.getMessage());
            return "天气查询失败，请稍后重试。";
        }
    }

    /** 城市地理编码：先查静态映射，再查 Open-Meteo 地理编码 API */
    private double[] geocodeCity(String city) {
        double[] coords = CITY_COORDS.get(city);
        if (coords != null) {
            log.info("坐标映射: {} -> ({}, {})", city, coords[0], coords[1]);
            return coords;
        }
        try {
            String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                + java.net.URLEncoder.encode(city, java.nio.charset.StandardCharsets.UTF_8)
                + "&count=1&language=zh";
            GeoResponse geo = restClient.get().uri(url).retrieve().body(GeoResponse.class);
            if (geo != null && geo.results() != null && !geo.results().isEmpty()) {
                GeoResponse.GeoResult first = geo.results().get(0);
                double lat = first.latitude();
                double lon = first.longitude();
                log.info("地理编码: {} -> ({}, {})", city, lat, lon);
                return new double[]{lat, lon};
            }
        } catch (Exception e) {
            log.warn("地理编码异常: {}", e.getMessage());
        }
        return null;
    }

    /** WMO 天气码转中文描述 */
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

    private String buildAdvice(String weatherText, String temperature) {
        List<String> tips = new ArrayList<>();
        String text = weatherText == null ? "" : weatherText;

        if (text.contains("雨") || text.contains("雷")) {
            tips.add("记得带伞，雨天路滑注意安全");
        }
        if (text.contains("雪")) {
            tips.add("注意保暖，路面可能湿滑");
        }
        if (text.contains("晴") || text.contains("多云") || text.contains("少云")) {
            tips.add("注意防晒，记得补水");
        }
        if (text.contains("雾") || text.contains("霾")) {
            tips.add("能见度低，出行注意安全");
        }

        try {
            int temp = (int) Double.parseDouble(temperature);
            if (temp >= 30) {
                tips.add("天气炎热，注意防暑");
            } else if (temp <= 10) {
                tips.add("天气较冷，注意保暖");
            }
        } catch (Exception ignored) {
        }

        if (tips.isEmpty()) {
            tips.add("注意补水，保持好心情");
        }
        return String.join("，", tips) + "。";
    }

    // === 心知天气实时天气响应 ===
    public record WeatherResponse(List<Result> results) {
        public record Result(Location location, Now now,
                @JsonProperty("last_update") String lastUpdate) {
        }
        public record Location(String name) {
        }
        public record Now(String text, String temperature) {
        }
    }

    // === 心知天气3天预报响应 ===
    public record DailyWeatherResponse(List<DailyResult> results) {
        public record DailyResult(Location location, List<Daily> daily) {
        }
        public record Location(String name) {
        }
        public record Daily(
                @JsonProperty("text_day") String textDay,
                @JsonProperty("text_night") String textNight,
                String high, String low,
                String humidity,
                @JsonProperty("wind_direction") String windDirection,
                @JsonProperty("wind_scale") String windScale) {
        }
    }

    // === Open-Meteo 响应 ===
    public record OpenMeteoResponse(Current current) {
        public record Current(
                @JsonProperty("temperature_2m") String temperature,
                @JsonProperty("apparent_temperature") String apparentTemperature,
                @JsonProperty("relative_humidity_2m") String relativeHumidity,
                @JsonProperty("weather_code") int weatherCode,
                @JsonProperty("wind_speed_10m") String windSpeed) {
        }
    }

    // === Open-Meteo 地理编码响应 ===
    public record GeoResponse(List<GeoResult> results) {
        public record GeoResult(double latitude, double longitude) {
        }
    }
}
