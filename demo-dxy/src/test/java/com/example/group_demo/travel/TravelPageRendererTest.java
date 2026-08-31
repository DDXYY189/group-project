package com.example.group_demo.travel;

import com.example.group_demo.amap.AmapClient.MapData;
import com.example.group_demo.amap.AmapClient.MapPath;
import com.example.group_demo.amap.AmapClient.MapPoint;
import com.example.group_demo.amap.AmapProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelPageRendererTest {

    private TravelPageRenderer newRenderer(String jsKey, String securityCode) {
        AmapProperties properties = new AmapProperties();
        properties.setJsKey(jsKey);
        properties.setSecurityJsCode(securityCode);
        return new TravelPageRenderer(properties);
    }

    private TravelPlan minimalPlan() {
        return new TravelPlan("上海", 1, List.of(), null, List.of(), List.of(),
            List.of(), null, List.of(), List.of(), List.of());
    }

    private MapData sampleData() {
        return new MapData(
            List.of(new MapPoint("外滩", 1, 121.492127, 31.233516)),
            List.of(new MapPath(1, "#0F766E", List.of(new double[]{121.492127, 31.233516}))));
    }

    @Test
    void rendersInteractiveMapWhenJsKeyConfigured() {
        String html = newRenderer("test-js-key", "my-secret").render(
            minimalPlan(), "trip-x", null, null, null, sampleData());

        assertTrue(html.contains("<div id=\"trip-map\""), "应渲染地图容器");
        assertTrue(html.contains("webapi.amap.com/maps?v=2.0&key=test-js-key"), "应加载高德 JS API");
        assertTrue(html.contains("window._AMapSecurityConfig={securityJsCode:'my-secret'}"),
            "应注入安全密钥");
        assertTrue(html.contains("window.__TRIP_MAP_DATA__="), "应注入地图数据");
        assertTrue(html.contains("\"lng\":121.492127"), "点位经纬度应被序列化");
        assertTrue(html.contains("\"color\":\"#0F766E\""), "路线颜色应被序列化");
        assertTrue(html.contains("initTripMap();"), "应在加载 SDK 后调用初始化");
        assertTrue(html.contains("可拖动、缩放"), "应提示交互说明");
    }

    @Test
    void fallsBackToStaticImageWithoutJsKey() {
        String html = newRenderer("", "").render(
            minimalPlan(), "trip-x", null, null, "./trip-x-map.png", sampleData());

        assertTrue(html.contains("trip-map-img"), "未配置 js-key 时应回退静态图");
        assertTrue(!html.contains("webapi.amap.com"), "不应加载 JS API");
    }

    @Test
    void omitsMapSectionWithoutAnyMapData() {
        String html = newRenderer("test-js-key", "").render(
            minimalPlan(), "trip-x", null, null, null, null);

        assertTrue(!html.contains("行程地图"), "无地图数据时应省略地图区块");
        assertTrue(!html.contains("<section class=\"trip-map\">"), "不应渲染地图 section");
    }
}
