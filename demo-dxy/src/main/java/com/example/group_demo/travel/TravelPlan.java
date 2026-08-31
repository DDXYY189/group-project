package com.example.group_demo.travel;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化旅行方案，由 LLM 生成 JSON 后统一解析，供网页渲染和待办写入使用。
 */
public record TravelPlan(
        String destination,
        int days,
        List<String> dates,
        Budget budget,
        List<DayPlan> itinerary,
        List<String> tips,
        List<String> mustDos,
        String heroPrompt,
        List<HotelRecommendation> hotels,
        List<RestaurantRecommendation> restaurants,
        List<Attraction> attractions) {

    public TravelPlan {
        dates = dates == null ? List.of() : List.copyOf(dates);
        itinerary = itinerary == null ? List.of() : List.copyOf(itinerary);
        tips = tips == null ? List.of() : List.copyOf(tips);
        mustDos = mustDos == null ? List.of() : List.copyOf(mustDos);
        hotels = hotels == null ? List.of() : List.copyOf(hotels);
        restaurants = restaurants == null ? List.of() : List.copyOf(restaurants);
        attractions = attractions == null ? List.of() : List.copyOf(attractions);
    }

    public static TravelPlan fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("行程 JSON 不是对象");
        }
        List<DayPlan> itinerary = new ArrayList<>();
        JsonNode itineraryNode = node.get("itinerary");
        if (itineraryNode != null && itineraryNode.isArray()) {
            for (JsonNode day : itineraryNode) {
                itinerary.add(DayPlan.fromJson(day));
            }
        }
        return new TravelPlan(
                TravelJsonParser.text(node, "destination"),
                node.path("days").asInt(itinerary.size()),
                TravelJsonParser.textList(node.get("dates")),
                Budget.fromJson(node.get("budget")),
                itinerary,
                TravelJsonParser.textList(node.get("tips")),
                TravelJsonParser.textList(node.get("mustDos")),
                TravelJsonParser.text(node, "heroPrompt"),
                parseHotels(node.get("hotels")),
                parseRestaurants(node.get("restaurants")),
                parseAttractions(node.get("attractions")));
    }

    public TravelPlan withRecommendations(List<HotelRecommendation> hotelItems,
                                          List<RestaurantRecommendation> restaurantItems,
                                          List<Attraction> attractionItems) {
        return new TravelPlan(destination, days, dates, budget, itinerary, tips, mustDos,
            heroPrompt,
            hotelItems == null ? hotels : hotelItems,
            restaurantItems == null ? restaurants : restaurantItems,
            attractionItems == null ? attractions : attractionItems);
    }

    private static List<HotelRecommendation> parseHotels(JsonNode node) {
        List<HotelRecommendation> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                result.add(HotelRecommendation.fromJson(item));
            }
        }
        return result;
    }

    private static List<RestaurantRecommendation> parseRestaurants(JsonNode node) {
        List<RestaurantRecommendation> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                result.add(RestaurantRecommendation.fromJson(item));
            }
        }
        return result;
    }

    private static List<Attraction> parseAttractions(JsonNode node) {
        List<Attraction> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                result.add(Attraction.fromJson(item));
            }
        }
        return result;
    }

    public record Budget(String total, List<BudgetItem> items) {

        public Budget {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static Budget fromJson(JsonNode node) {
            if (node == null || !node.isObject()) {
                return new Budget("", List.of());
            }
            List<BudgetItem> items = new ArrayList<>();
            JsonNode itemsNode = node.get("items");
            if (itemsNode != null && itemsNode.isArray()) {
                for (JsonNode item : itemsNode) {
                    items.add(new BudgetItem(
                            TravelJsonParser.text(item, "name"),
                            TravelJsonParser.text(item, "amount")));
                }
            }
            return new Budget(TravelJsonParser.text(node, "total"), items);
        }
    }

    public record BudgetItem(String name, String amount) {
    }

    public record DayPlan(
            int day,
            String title,
            String weather,
            List<TimeSlot> schedule,
            String meals,
            String hotel,
            String notes) {

        public DayPlan {
            schedule = schedule == null ? List.of() : List.copyOf(schedule);
        }

        public static DayPlan fromJson(JsonNode node) {
            List<TimeSlot> schedule = new ArrayList<>();
            JsonNode scheduleNode = node == null ? null : node.get("schedule");
            if (scheduleNode != null && scheduleNode.isArray()) {
                for (JsonNode slot : scheduleNode) {
                    schedule.add(new TimeSlot(
                            TravelJsonParser.text(slot, "time"),
                            TravelJsonParser.text(slot, "item")));
                }
            }
            return new DayPlan(
                    node == null ? 0 : node.path("day").asInt(0),
                    node == null ? null : TravelJsonParser.text(node, "title"),
                    node == null ? null : TravelJsonParser.text(node, "weather"),
                    schedule,
                    node == null ? null : TravelJsonParser.text(node, "meals"),
                    node == null ? null : TravelJsonParser.text(node, "hotel"),
                    node == null ? null : TravelJsonParser.text(node, "notes"));
        }
    }

    public record TimeSlot(String time, String item) {
    }

    public record HotelRecommendation(
            String name,
            String address,
            String price,
            String rating,
            String imageUrl,
            String detailUrl,
            List<String> tags,
            String distance) {

        public HotelRecommendation {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        public static HotelRecommendation fromJson(JsonNode node) {
            return new HotelRecommendation(
                TravelJsonParser.text(node, "name"),
                TravelJsonParser.text(node, "address"),
                TravelJsonParser.text(node, "price"),
                TravelJsonParser.text(node, "rating"),
                TravelJsonParser.text(node, "imageUrl"),
                TravelJsonParser.text(node, "detailUrl"),
                TravelJsonParser.textList(node == null ? null : node.get("tags")),
                TravelJsonParser.text(node, "distance"));
        }
    }

    public record RestaurantRecommendation(
            String name,
            String address,
            String avgPrice,
            String rating,
            String imageUrl,
            String detailUrl,
            List<String> tags,
            String distance) {

        public RestaurantRecommendation {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        public static RestaurantRecommendation fromJson(JsonNode node) {
            return new RestaurantRecommendation(
                TravelJsonParser.text(node, "name"),
                TravelJsonParser.text(node, "address"),
                TravelJsonParser.text(node, "avgPrice"),
                TravelJsonParser.text(node, "rating"),
                TravelJsonParser.text(node, "imageUrl"),
                TravelJsonParser.text(node, "detailUrl"),
                TravelJsonParser.textList(node == null ? null : node.get("tags")),
                TravelJsonParser.text(node, "distance"));
        }
    }

    public record Attraction(int day, String name) {

        public static Attraction fromJson(JsonNode node) {
            return new Attraction(
                node == null ? 1 : node.path("day").asInt(1),
                TravelJsonParser.text(node, "name"));
        }
    }
}
