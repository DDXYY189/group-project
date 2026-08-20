package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TranslateToolTest {

    private final TranslateTool tool = new TranslateTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nameIsUnique() {
        assertEquals("translate", tool.name());
    }

    @Test
    void jsonSchemaHasRequiredFields() {
        Map<String, Object> schema = tool.jsonSchema();
        assertEquals("function", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) schema.get("function");
        assertEquals("translate", fn.get("name"));
        assertNotNull(fn.get("description"));
        assertNotNull(fn.get("parameters"));
    }

    @Test
    void parametersContainTextAndTargetLang() {
        Map<String, Object> params = tool.parameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");
        assertTrue(properties.containsKey("text"));
        assertTrue(properties.containsKey("target_lang"));
        assertTrue(properties.containsKey("source_lang"));
    }

    @Test
    void throwsWhenTextMissing() {
        JsonNode args = mapper.valueToTree(Map.of("target_lang", "zh"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("u1", args));
    }

    @Test
    void throwsWhenTargetLangMissing() {
        JsonNode args = mapper.valueToTree(Map.of("text", "hello"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("u1", args));
    }

    @Test
    void throwsWhenTextTooLong() {
        String longText = "a".repeat(501);
        JsonNode args = mapper.valueToTree(Map.of(
            "text", longText,
            "target_lang", "zh"
        ));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("u1", args));
    }

    @Test
    void relayToUserDefaultsFalse() {
        assertFalse(tool.relayToUser());
    }
}
