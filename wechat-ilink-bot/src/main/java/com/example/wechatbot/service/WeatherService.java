package com.example.wechatbot.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 心知天气（Seniverse）天气数据服务
 * 官网：https://www.seniverse.com/
 * API 文档：https://seniverse.yuque.com/hyper_data/api
 *
 * 调用心知天气公开 HTTP 接口，根据城市名称获取实时天气数据。
 *
 * 【重要】API Key 从配置文件/环境变量读取，禁止写死在代码中。
 * 配置位置：application-local.yml 中的 seniverse.api-key
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    /**
     * 【此处填入心知天气API Key，从环境变量/配置文件读取，禁止写死在代码】
     * 在 application-local.yml 中配置：seniverse.api-key: 你的API Key
     * 或通过环境变量 SENIVERSE_API_KEY 注入
     */
    @Value("${seniverse.api-key:${SENIVERSE_API_KEY:}}")
    private String apiKey;

    /** 心知天气 API 基础地址 */
    private static final String BASE_URL = "https://api.seniverse.com/v3";

    /**
     * 获取指定城市的实时天气
     * 接口：/weather/now.json
     * 文档：https://seniverse.yuque.com/hyper_data/api/weather_realtime
     *
     * @param city 城市名称（中文或拼音，如 "北京" 或 "beijing"）
     * @return 天气信息字符串，包含温度、天气描述、风向等；失败返回错误提示
     */
    public String getWeather(String city) {
        // 检查 API Key 是否已配置
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("心知天气 API Key 未配置, 请在 application-local.yml 中设置 seniverse.api-key");
            return "[天气服务未配置 API Key]";
        }

        if (city == null || city.trim().isEmpty()) {
            return "[城市名称不能为空]";
        }

        // 构建请求 URL
        // 参数：key=API密钥, location=城市, language=zh-Hans(简体中文), unit=c(摄氏度)
        String url;
        HttpURLConnection conn = null;
        try {
            String encodedCity = URLEncoder.encode(city.trim(), StandardCharsets.UTF_8.name());
            url = BASE_URL + "/weather/now.json?key=" + apiKey
                    + "&location=" + encodedCity
                    + "&language=zh-Hans&unit=c";

            log.info("查询天气: city={}", city);

            // 发起 HTTP GET 请求
            URL requestUrl = new URL(url);
            conn = (HttpURLConnection) requestUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);  // 连接超时 10 秒
            conn.setReadTimeout(10000);     // 读取超时 10 秒

            int code = conn.getResponseCode();
            String body = readResponse(conn, code);

            // HTTP 状态码非 200，接口返回错误
            if (code != 200) {
                log.error("心知天气接口返回错误: code={}, body={}", code, body);
                return "[天气查询失败: HTTP " + code + "]";
            }

            // 解析 JSON 响应
            return parseWeatherResponse(body, city);

        } catch (java.net.UnknownHostException e) {
            log.error("网络异常: 无法连接心知天气服务器: {}", e.getMessage());
            return "[网络异常: 无法连接天气服务]";
        } catch (java.net.SocketTimeoutException e) {
            log.error("网络超时: 心知天气接口响应超时");
            return "[网络超时: 天气服务响应超时]";
        } catch (Exception e) {
            log.error("天气查询失败: {}", e.getMessage(), e);
            return "[天气查询失败: " + e.getMessage() + "]";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 解析心知天气返回的 JSON 数据
     * 响应格式示例：
     * {
     *   "results": [{
     *     "location": { "name": "北京", "id": "...", "country": "CN" },
     *     "now": { "text": "晴", "code": "0", "temperature": "25" },
     *     "last_update": "2024-08-18T15:00:00+08:00"
     *   }]
     * }
     */
    private String parseWeatherResponse(String body, String city) {
        JSONObject json = JSON.parseObject(body);
        JSONArray results = json.getJSONArray("results");
        if (results == null || results.isEmpty()) {
            log.warn("未找到城市天气数据: {}", city);
            return "[未找到城市: " + city + " 的天气数据]";
        }

        JSONObject firstResult = results.getJSONObject(0);

        // 城市信息
        JSONObject location = firstResult.getJSONObject("location");
        String cityName = location.getString("name");

        // 实时天气
        JSONObject now = firstResult.getJSONObject("now");
        String text = now.getString("text");           // 天气描述，如"晴"
        String temperature = now.getString("temperature"); // 温度，如"25"

        // 更新时间
        String lastUpdate = firstResult.getString("last_update");

        // 拼装结果
        String result = String.format(
                "%s 实时天气: %s, 温度 %s°C, 更新时间: %s",
                cityName, text, temperature, lastUpdate
        );
        log.info("天气查询成功: {}", result);
        return result;
    }

    /**
     * 读取 HTTP 响应内容
     * 成功读 inputStream，失败读 errorStream
     */
    private String readResponse(HttpURLConnection conn, int code) throws Exception {
        InputStream is;
        if (code == 200) {
            is = conn.getInputStream();
        } else {
            is = conn.getErrorStream();
        }
        if (is == null) {
            return "";
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * 检查 API Key 是否已配置
     */
    public boolean isReady() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    // ==================== 测试入口 ====================

    /**
     * 独立测试方法，演示调用心知天气接口
     *
     * 运行前请设置环境变量：
     *   Windows:  set SENIVERSE_API_KEY=你的心知天气API Key
     *   Linux:    export SENIVERSE_API_KEY=你的心知天气API Key
     *
     * 或在 application-local.yml 中配置：
     *   seniverse:
     *     api-key: 你的心知天气API Key
     */
    public static void main(String[] args) {
        // 从环境变量读取 API Key
        String key = System.getenv("SENIVERSE_API_KEY");
        if (key == null || key.trim().isEmpty()) {
            System.err.println("请先设置环境变量 SENIVERSE_API_KEY");
            System.err.println("Windows:  set SENIVERSE_API_KEY=你的心知天气API Key");
            System.err.println("Linux:    export SENIVERSE_API_KEY=你的心知天气API Key");
            return;
        }

        // 手动构建 WeatherService（不走 Spring 注入）
        WeatherService weatherService = new WeatherService();
        try {
            java.lang.reflect.Field field = WeatherService.class.getDeclaredField("apiKey");
            field.setAccessible(true);
            field.set(weatherService, key);
        } catch (Exception e) {
            System.err.println("注入 apiKey 失败: " + e.getMessage());
            return;
        }

        System.out.println("====== 心知天气测试 ======\n");

        // 测试案例1：查询北京天气
        test(weatherService, "北京");

        // 测试案例2：查询上海天气
        test(weatherService, "上海");

        // 测试案例3：查询深圳天气
        test(weatherService, "深圳");

        // 测试案例4：不存在的城市（异常测试）
        test(weatherService, "不存在的城市XYZ");

        System.out.println("====== 测试结束 ======");
    }

    private static void test(WeatherService service, String city) {
        System.out.println("查询城市: " + city);
        String result = service.getWeather(city);
        System.out.println("结果: " + result);
        System.out.println();
    }
}
