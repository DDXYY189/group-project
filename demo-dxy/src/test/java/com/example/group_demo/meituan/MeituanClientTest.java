package com.example.group_demo.meituan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeituanClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mockModeReturnsHotelAndRestaurantRecommendations() throws Exception {
        MeituanProperties properties = new MeituanProperties();
        properties.setEnabled(true);
        properties.setMockEnabled(true);
        properties.setAppKey("");
        MeituanClient client = new MeituanClient(properties);

        String hotelsJson = client.searchHotels("上海", "2026-04-01", "2026-04-04", "5000");
        JsonNode hotels = objectMapper.readTree(hotelsJson).path("hotels");
        assertTrue(hotels.isArray());
        assertTrue(hotels.size() > 0);
        assertTrue(hotels.get(0).path("name").asText().contains("上海"));
        assertTrue(hotels.get(0).hasNonNull("price"));
        assertTrue(hotels.get(0).path("tags").isArray());

        String restaurantsJson = client.searchRestaurants("上海", "本帮菜", "5000");
        JsonNode restaurants = objectMapper.readTree(restaurantsJson).path("restaurants");
        assertTrue(restaurants.isArray());
        assertTrue(restaurants.size() > 0);
        assertTrue(restaurants.get(0).path("name").asText().contains("上海"));
        assertTrue(restaurants.get(0).hasNonNull("avgPrice"));
    }

    @Test
    void disabledModeReturnsEmptyLists() throws Exception {
        MeituanProperties properties = new MeituanProperties();
        properties.setEnabled(false);
        MeituanClient client = new MeituanClient(properties);

        JsonNode hotels = objectMapper.readTree(client.searchHotels("上海", null, null, null))
            .path("hotels");
        JsonNode restaurants = objectMapper.readTree(client.searchRestaurants("上海", null, null))
            .path("restaurants");
        assertEquals(0, hotels.size());
        assertEquals(0, restaurants.size());
    }
}
