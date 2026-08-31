package com.example.group_demo.controller;

import com.example.group_demo.travel.TravelProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/trips")
public class TravelPageController {

    private final TravelProperties properties;

    public TravelPageController(TravelProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/{id}.html")
    public ResponseEntity<Resource> html(@PathVariable String id) throws IOException {
        return serve(id + ".html", MediaType.TEXT_HTML);
    }

    @GetMapping("/{id}.mp3")
    public ResponseEntity<Resource> mp3(@PathVariable String id) throws IOException {
        return serve(id + ".mp3", MediaType.parseMediaType("audio/mpeg"));
    }

    @GetMapping("/{id}-hero.png")
    public ResponseEntity<Resource> hero(@PathVariable String id) throws IOException {
        return serve(id + "-hero.png", MediaType.IMAGE_PNG);
    }

    @GetMapping("/{id}-map.png")
    public ResponseEntity<Resource> map(@PathVariable String id) throws IOException {
        return serve(id + "-map.png", MediaType.IMAGE_PNG);
    }

    private ResponseEntity<Resource> serve(String fileName, MediaType mediaType) throws IOException {
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            return ResponseEntity.badRequest().build();
        }
        Path root = Path.of(properties.getPageDir()).toAbsolutePath().normalize();
        Path file = root.resolve(fileName).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(mediaType)
            .body(new FileSystemResource(file));
    }
}
