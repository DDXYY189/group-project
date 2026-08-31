package com.example.group_demo.controller;

import com.example.group_demo.travel.TravelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TravelPageControllerTest {

    @Test
    void servesMapPng() throws IOException {
        Path dir = Files.createTempDirectory("trips-test-");
        Files.write(dir.resolve("trip-1-map.png"), new byte[]{1, 2, 3});
        TravelProperties properties = new TravelProperties();
        properties.setPageDir(dir.toString());
        TravelPageController controller = new TravelPageController(properties);

        ResponseEntity<Resource> response = controller.map("trip-1");

        assertEquals(200, response.getStatusCode().value());
        try (InputStream in = response.getBody().getInputStream()) {
            assertEquals(3, in.readAllBytes().length);
        }
    }
}
