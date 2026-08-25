package com.example.group_demo.travel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TravelJsonParserTest {

    @Test
    void extractsJsonFromPlainText() {
        JsonNode node = TravelJsonParser.extract(
            "好的，行程如下：{\"destination\":\"上海\",\"days\":3}");
        assertEquals("上海", TravelJsonParser.text(node, "destination"));
        assertEquals(3, node.path("days").asInt());
    }

    @Test
    void extractsJsonFromMarkdownFence() {
        JsonNode node = TravelJsonParser.extract("""
            以下是我生成的方案：
            ```json
            {"destination":"杭州","days":2}
            ```
            """);
        assertEquals("杭州", TravelJsonParser.text(node, "destination"));
        assertEquals(2, node.path("days").asInt());
    }

    @Test
    void rejectsTextWithoutJson() {
        assertThrows(IllegalArgumentException.class,
            () -> TravelJsonParser.extract("抱歉，我没有找到相关信息。"));
    }
}
