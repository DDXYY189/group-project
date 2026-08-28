package com.example.group_demo.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelPageRendererTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rendersPlanWithAssetsAndEscapesContent() throws Exception {
        JsonNode json = objectMapper.readTree("""
            {
              "destination": "上海",
              "days": 2,
              "dates": ["4月1日", "4月2日"],
              "budget": {"total": "3000", "items": [{"name": "交通", "amount": "800"}]},
              "itinerary": [
                {
                  "day": 1,
                  "title": "外滩与老城厢",
                  "weather": "晴 20℃",
                  "schedule": [{"time": "09:00", "item": "抵达入住酒店"}],
                  "meals": "本帮菜",
                  "hotel": "南京东路附近",
                  "notes": "<script>alert(1)</script>"
                }
              ],
              "tips": ["提前订票"],
              "mustDos": ["下载地铁APP"],
              "heroPrompt": "上海外滩夜景"
            }
            """);
        TravelPlan plan = TravelPlan.fromJson(json);
        String html = new TravelPageRenderer().render(plan, "trip-demo",
            "./trip-demo-hero.png", "./trip-demo.mp3");

        assertTrue(html.contains("<title>上海 2 日游方案</title>"));
        assertTrue(html.contains("外滩与老城厢"));
        assertTrue(html.contains("trip-demo-hero.png"));
        assertTrue(html.contains("trip-demo.mp3"));
        assertTrue(html.contains("3000"));
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void rendersWithoutOptionalAssets() throws Exception {
        JsonNode json = objectMapper.readTree("""
            {"destination": "成都", "days": 1, "itinerary": [
              {"day": 1, "title": "市区一日", "schedule": [
                {"time": "10:00", "item": "宽窄巷子"}
              ]}
            ]}
            """);
        String html = new TravelPageRenderer().render(TravelPlan.fromJson(json), "trip-demo", null, null);
        assertTrue(html.contains("成都 1 日游"));
        assertFalse(html.contains("<audio"));
    }

    @Test
    void rendersMeituanRecommendations() throws Exception {
        JsonNode json = objectMapper.readTree("""
            {
              "destination": "上海",
              "days": 2,
              "dates": ["4月1日", "4月2日"],
              "itinerary": [
                {"day": 1, "title": "外滩", "schedule": [{"time": "09:00", "item": "抵达"}]}
              ],
              "hotels": [
                {"name": "<b>外滩</b>精选酒店", "address": "南京东路", "price": "458元/晚",
                 "rating": "4.8", "imageUrl": "https://p.meituan.net/h.jpg",
                 "detailUrl": "https://www.meituan.com/hotel/1", "tags": ["市中心", "免费停车"],
                 "distance": "距外滩500m"}
              ],
              "restaurants": [
                {"name": "本帮菜馆", "address": "城隍庙", "avgPrice": "88元/人",
                 "rating": "4.7", "detailUrl": "https://www.meituan.com/rest/1",
                 "tags": ["本帮菜"], "distance": "距外滩1km"}
              ]
            }
            """);
        String html = new TravelPageRenderer().render(TravelPlan.fromJson(json), "trip-demo", null, null);

        assertTrue(html.contains("住宿与美食推荐"));
        assertTrue(html.contains("&lt;b&gt;外滩&lt;/b&gt;精选酒店"));
        assertTrue(html.contains("458元/晚"));
        assertTrue(html.contains("88元/人"));
        assertTrue(html.contains("评分 4.8"));
        assertTrue(html.contains("https://www.meituan.com/hotel/1"));
        assertTrue(html.contains("https://p.meituan.net/h.jpg"));
        assertFalse(html.contains("<b>外滩</b>精选酒店"));
    }
}
