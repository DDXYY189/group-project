package com.example.group_demo.meituan;

import com.example.group_demo.config.RestClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 美团开放平台客户端。返回给上层的是统一结构的 JSON 字符串：
 * 酒店 {"hotels":[...]}，美食 {"restaurants":[...]}。
 * 未配置凭证或开启 mock 时返回示例数据，方便先演示页面效果。
 */
@Service
public class MeituanClient {

    private static final Logger log = LoggerFactory.getLogger(MeituanClient.class);

    private final MeituanProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;
    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public MeituanClient(MeituanProperties properties) {
        this.properties = properties;
        this.restClient = RestClientFactory.builder().build();
    }

    public String searchHotels(String city, String checkIn, String checkOut, String budget) {
        if (!properties.isEnabled() || city == null || city.isBlank()) {
            return empty("hotels");
        }
        String cacheKey = cacheKey("hotels", city, budget);
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.json();
        }
        String json;
        if (properties.isMockEnabled()) {
            json = mockHotels(city);
        } else {
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("check_in", orBlank(checkIn));
            extra.put("check_out", orBlank(checkOut));
            extra.put("budget", orBlank(budget));
            json = callApi("hotel", city, extra);
        }
        json = json == null ? empty("hotels") : json;
        cache.put(cacheKey, new CachedEntry(json,
            System.currentTimeMillis() + properties.getCacheTtlSeconds() * 1000L));
        return json;
    }

    public String searchRestaurants(String city, String cuisine, String budget) {
        if (!properties.isEnabled() || city == null || city.isBlank()) {
            return empty("restaurants");
        }
        String cacheKey = cacheKey("restaurants", city, budget);
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.json();
        }
        String json;
        if (properties.isMockEnabled()) {
            json = mockRestaurants(city);
        } else {
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("cuisine", orBlank(cuisine));
            extra.put("budget", orBlank(budget));
            json = callApi("food", city, extra);
        }
        json = json == null ? empty("restaurants") : json;
        cache.put(cacheKey, new CachedEntry(json,
            System.currentTimeMillis() + properties.getCacheTtlSeconds() * 1000L));
        return json;
    }

    /**
     * 真实接口调用。美团开放平台常见签名是：参数按 key 排序拼接后追加 secret，
     * 再做 MD5；不同接口的参数名可能不同，拿到文档后只需调整本方法和字段映射。
     */
    private String callApi(String type, String city, Map<String, String> extra) {
        if (properties.getAppKey() == null || properties.getAppKey().isBlank()) {
            log.warn("美团 app-key 未配置，跳过真实接口调用");
            return null;
        }
        String endpoint = "hotel".equals(type)
            ? properties.getHotelEndpoint() : properties.getFoodEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("美团 {} 接口地址未配置", type);
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("appKey", properties.getAppKey());
        params.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        if (notBlank(properties.getAuthToken())) {
            params.put("appAuthToken", properties.getAuthToken());
        }
        params.put("city", city);
        params.putAll(extra);
        params.put("sign", sign(params));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        try {
            String response = restClient.post()
                .uri(properties.getBaseUrl() + endpoint)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
            String result = normalizeResponse(response, "hotel".equals(type) ? "hotels" : "restaurants");
            log.info("美团 API 调用成功 type={} city={}", type, city);
            return result;
        } catch (Exception e) {
            log.warn("美团 API 调用失败 type={} city={}", type, city, e);
            return null;
        }
    }

    private String sign(Map<String, String> params) {
        String raw = params.entrySet().stream()
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining("&")) + orBlank(properties.getSecret());
        return md5Hex(raw);
    }

    private static String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                    .append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }

    /**
     * 把美团返回体规整成内部统一的推荐 JSON；不同接口字段名不同，
     * 这里做了常见字段名的兼容，遇到新字段只需补 firstText 里的别名。
     */
    private String normalizeResponse(String response, String kind) {
        if (response == null || response.isBlank()) {
            return empty(kind);
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.has("code") && root.path("code").asInt(0) != 0) {
                log.warn("美团接口返回错误 code={} msg={}", root.path("code").asInt(),
                    root.path("msg").asText());
                return null;
            }
            JsonNode data = root.has("data") ? root.path("data") : root;
            JsonNode items = findItems(data, kind);
            if (items == null || !items.isArray()) {
                return empty(kind);
            }
            ArrayNode list = objectMapper.createArrayNode();
            for (JsonNode item : items) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                list.add(canonicalItem(item, kind));
            }
            ObjectNode result = objectMapper.createObjectNode();
            result.set(kind, list);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("美团响应解析失败", e);
            return null;
        }
    }

    private JsonNode findItems(JsonNode data, String kind) {
        if (data.isArray()) {
            return data;
        }
        for (String key : List.of("list", "items", kind, "records", "poiList", "result")) {
            if (data.has(key)) {
                JsonNode node = data.get(key);
                if (node.isArray()) {
                    return node;
                }
            }
        }
        return null;
    }

    private ObjectNode canonicalItem(JsonNode item, String kind) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("name", firstText(item, "name", "title", "poiName", "storeName"));
        out.put("address", firstText(item, "address", "addr", "location", "addressText"));
        out.put("rating", firstText(item, "rating", "score", "avgScore", "commentScore"));
        out.put("imageUrl", firstText(item, "imageUrl", "picUrl", "picture", "imgUrl", "photo"));
        out.put("detailUrl", firstText(item, "detailUrl", "url", "dealUrl", "link", "poiUrl"));
        out.put("distance", firstText(item, "distance", "distanceText", "distanceDesc"));
        String price = firstText(item, "price", "priceInfo", "minPrice", "lowestPrice",
            "avgPrice", "averagePrice", "perPrice");
        if ("hotels".equals(kind)) {
            out.put("price", withUnit(price, "元/晚"));
        } else {
            out.put("avgPrice", withUnit(price, "元/人"));
        }
        ArrayNode tags = objectMapper.createArrayNode();
        JsonNode tagsNode = item.path("tags");
        if (tagsNode.isArray()) {
            tagsNode.forEach(tag -> tags.add(tag.asText()));
        } else if (tagsNode.isTextual()) {
            for (String tag : tagsNode.asText().split("[,，、/]")) {
                if (!tag.isBlank()) {
                    tags.add(tag.trim());
                }
            }
        }
        out.set("tags", tags);
        return out;
    }

    private static String firstText(JsonNode item, String... keys) {
        for (String key : keys) {
            JsonNode value = item.get(key);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (notBlank(text)) {
                    return text.trim();
                }
            }
        }
        return "";
    }

    private static String withUnit(String price, String unit) {
        if (price == null || price.isBlank()) {
            return "";
        }
        return price.matches("\\d+(\\.\\d+)?") ? price + unit : price;
    }

    private String mockHotels(String city) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode hotels = objectMapper.createArrayNode();
        hotels.add(mockHotel(city, "市中心精选酒店", "商圈核心地段，步行可达主要景点",
            "458元/晚", "4.8", List.of("市中心", "免费停车"), "距主要景点约 1km"));
        hotels.add(mockHotel(city, "景区周边度假酒店", "临近景区入口，环境安静",
            "329元/晚", "4.6", List.of("景区周边", "含早餐"), "距景区约 800m"));
        hotels.add(mockHotel(city, "青年旅舍与民宿", "老城区特色民宿，性价比高",
            "189元/晚", "4.5", List.of("性价比", "当地特色"), "距美食街约 500m"));
        result.set("hotels", hotels);
        return result.toString();
    }

    private ObjectNode mockHotel(String city, String suffix, String address,
                                 String price, String rating, List<String> tags, String distance) {
        ObjectNode hotel = objectMapper.createObjectNode();
        hotel.put("name", city + suffix);
        hotel.put("address", city + address);
        hotel.put("price", price);
        hotel.put("rating", rating);
        hotel.put("imageUrl", "");
        hotel.put("detailUrl",
            "https://www.meituan.com/hotel/" + Math.abs(city.hashCode()) % 1000);
        hotel.put("distance", distance);
        ArrayNode tagArray = hotel.putArray("tags");
        tags.forEach(tagArray::add);
        return hotel;
    }

    private String mockRestaurants(String city) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode restaurants = objectMapper.createArrayNode();
        restaurants.add(mockRestaurant(city, "地道本帮菜馆", "老城区美食街，本地人常去",
            "88元/人", "4.7", List.of("本地特色", "人气高"), "距市中心约 1km"));
        restaurants.add(mockRestaurant(city, "风味小吃集合店", "汇集当地小吃，适合尝鲜",
            "42元/人", "4.6", List.of("小吃", "平价"), "距地铁站约 300m"));
        restaurants.add(mockRestaurant(city, "江景融合餐厅", "可看夜景，适合晚餐约会",
            "168元/人", "4.8", List.of("夜景", "环境好"), "距江边约 600m"));
        result.set("restaurants", restaurants);
        return result.toString();
    }

    private ObjectNode mockRestaurant(String city, String suffix, String address,
                                      String price, String rating, List<String> tags, String distance) {
        ObjectNode restaurant = objectMapper.createObjectNode();
        restaurant.put("name", city + suffix);
        restaurant.put("address", city + address);
        restaurant.put("avgPrice", price);
        restaurant.put("rating", rating);
        restaurant.put("imageUrl", "");
        restaurant.put("detailUrl",
            "https://www.meituan.com/restaurant/" + Math.abs(city.hashCode()) % 1000);
        restaurant.put("distance", distance);
        ArrayNode tagArray = restaurant.putArray("tags");
        tags.forEach(tagArray::add);
        return restaurant;
    }

    private static String empty(String kind) {
        return "hotels".equals(kind) ? "{\"hotels\":[]}" : "{\"restaurants\":[]}";
    }

    private static String cacheKey(String type, String city, String budget) {
        return type + "|" + city + "|" + orBlank(budget);
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    private static String orBlank(String text) {
        return text == null ? "" : text;
    }

    private record CachedEntry(String json, long expiresAt) {
    }
}
