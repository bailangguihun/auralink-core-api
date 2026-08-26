package com.auralink.provider.qwen;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.validation.ChineseTextRules;
import com.auralink.provider.validation.StrictProviderJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/** Strict schema-one JSON validation for poem-to-painting interpretation. */
@Component
@RequiredArgsConstructor
public class PaintingPromptPlanValidator {

    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "subject",
            "scene",
            "composition",
            "colorPalette",
            "brushwork",
            "artisticConception",
            "finalPrompt");
    private static final int MAX_FIELD_CHARS = 4_000;

    private final ObjectMapper objectMapper;
    private final CreationProviderProperties properties;

    public PaintingPromptPlan validate(String rawJson) {
        JsonNode root = parseObject(rawJson, "Qwen Painting prompt plan is not valid JSON");
        requireExactFields(root);
        String schemaVersion = text(root, "schemaVersion");
        if (!"1".equals(schemaVersion)) {
            throw invalid("Qwen Painting prompt plan schema is unsupported");
        }
        String subject = validatedChinese(root, "subject");
        String scene = validatedChinese(root, "scene");
        String composition = validatedChinese(root, "composition");
        String colorPalette = validatedChinese(root, "colorPalette");
        String brushwork = validatedChinese(root, "brushwork");
        String artisticConception = validatedChinese(root, "artisticConception");
        String finalPrompt = validatedChinese(root, "finalPrompt");

        int total = subject.length() + scene.length() + composition.length()
                + colorPalette.length() + brushwork.length()
                + artisticConception.length() + finalPrompt.length();
        if (total > properties.getMaxTextChars()) {
            throw invalid("Qwen Painting prompt plan exceeds the configured character limit");
        }
        if (java.util.stream.Stream.of(
                        subject, scene, composition, colorPalette,
                        brushwork, artisticConception, finalPrompt)
                .anyMatch(ChineseTextRules::containsUnsupportedAuthorshipClaim)) {
            throw invalid("Qwen Painting prompt plan contains an unsupported authorship claim");
        }
        return new PaintingPromptPlan(
                schemaVersion,
                subject,
                scene,
                composition,
                colorPalette,
                brushwork,
                artisticConception,
                finalPrompt);
    }

    private JsonNode parseObject(String rawJson, String message) {
        if (rawJson == null || rawJson.isBlank() || rawJson.contains("```")) {
            throw invalid(message);
        }
        try {
            JsonNode root = StrictProviderJson.parse(objectMapper, rawJson);
            if (root == null || !root.isObject()) {
                throw invalid(message);
            }
            return root;
        } catch (ProviderExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                    message,
                    exception);
        }
    }

    private void requireExactFields(JsonNode root) {
        Set<String> actual = new HashSet<>();
        Iterator<String> fields = root.fieldNames();
        fields.forEachRemaining(actual::add);
        if (!actual.equals(FIELDS)) {
            throw invalid("Qwen Painting prompt plan fields are invalid");
        }
    }

    private String validatedChinese(JsonNode root, String field) {
        String value = text(root, field);
        if (value.length() > MAX_FIELD_CHARS
                || !ChineseTextRules.containsChinese(value)
                || ChineseTextRules.containsForbiddenMarkupOrLeakage(value)) {
            throw invalid("Qwen Painting prompt plan content is invalid");
        }
        return value;
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().trim().isEmpty()) {
            throw invalid("Qwen Painting prompt plan field is missing");
        }
        return value.textValue().trim();
    }

    private ProviderExecutionException invalid(String message) {
        return new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                message);
    }
}
