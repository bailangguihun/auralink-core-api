package com.auralink.guide.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.auralink.guide.knowledge.KnowledgeItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Strict parser, validator, and canonical serializer for cached/provider Guide JSON. */
@Component
public class GuideResultCodec {

    public static final int MAX_RESULT_BYTES = 65_536;
    public static final int MAX_SUMMARY_CHARACTERS = 2_000;
    public static final int MAX_SECTION_CHARACTERS = 4_000;
    public static final int MAX_HIGHLIGHT_CHARACTERS = 500;
    public static final int MAX_REFERENCE_COUNT = 5;

    private static final int MIN_HIGHLIGHT_COUNT = 2;
    private static final int MAX_HIGHLIGHT_COUNT = 5;
    private static final int MAX_SOURCE_ID_CHARACTERS = 256;
    private static final int MAX_SOURCE_TYPE_CHARACTERS = 64;
    private static final int MAX_REFERENCE_TITLE_CHARACTERS = 512;
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<\\s*/?\\s*[a-z][^>]*>");
    private static final Pattern HAN_CHARACTER = Pattern.compile("\\p{IsHan}");

    private final ObjectReader strictReader;
    private final ObjectWriter canonicalWriter;

    public GuideResultCodec(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        ObjectMapper strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.INDENT_OUTPUT);
        this.strictReader = strictMapper.readerFor(GuideResult.class);
        this.canonicalWriter = strictMapper.writerFor(GuideResult.class);
    }

    /** Parse and validate untrusted provider or cached JSON. */
    public GuideResult decode(
            String json,
            String expectedSchemaVersion,
            List<KnowledgeItem> allowedKnowledge) {
        if (json == null || json.isBlank()) {
            throw invalid("Guide result JSON is absent");
        }
        requireByteLimit(json, "Guide result JSON");
        try {
            return validate(strictReader.readValue(json), expectedSchemaVersion, allowedKnowledge);
        } catch (GuideResultValidationException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new GuideResultValidationException("Guide result JSON is malformed", exception);
        }
    }

    /** Validate and serialize with deterministic property ordering and no formatting whitespace. */
    public String encodeCanonical(
            GuideResult result,
            String expectedSchemaVersion,
            List<KnowledgeItem> allowedKnowledge) {
        GuideResult normalized = validate(result, expectedSchemaVersion, allowedKnowledge);
        try {
            String json = canonicalWriter.writeValueAsString(normalized);
            requireByteLimit(json, "Canonical Guide result");
            return json;
        } catch (JsonProcessingException exception) {
            throw new GuideResultValidationException("Guide result cannot be serialized", exception);
        }
    }

    /** Return a normalized immutable result after enforcing every public/cached invariant. */
    public GuideResult validate(
            GuideResult result,
            String expectedSchemaVersion,
            List<KnowledgeItem> allowedKnowledge) {
        if (result == null) {
            throw invalid("Guide result is absent");
        }
        String schemaVersion = requireText(
                result.schemaVersion(), "schemaVersion", 32, false);
        String expected = requireText(expectedSchemaVersion, "expected schemaVersion", 32, false);
        if (!expected.equals(schemaVersion)) {
            throw invalid("Guide schemaVersion does not match the requested schema");
        }

        String summary = requireChineseText(
                result.summary(), "summary", MAX_SUMMARY_CHARACTERS, true);
        GuideSections sections = normalizeSections(result.sections());
        List<String> highlights = normalizeHighlights(result.highlights());
        List<GuideKnowledgeReference> references = normalizeReferences(
                result.knowledgeReferences(), allowedKnowledge);

        GuideResult normalized = new GuideResult(
                schemaVersion,
                summary,
                sections,
                List.copyOf(highlights),
                List.copyOf(references));
        try {
            requireByteLimit(canonicalWriter.writeValueAsString(normalized), "Guide result");
        } catch (JsonProcessingException exception) {
            throw new GuideResultValidationException("Guide result cannot be serialized", exception);
        }
        return normalized;
    }

    private GuideSections normalizeSections(GuideSections sections) {
        if (sections == null) {
            throw invalid("sections is required");
        }
        return new GuideSections(
                optionalChineseText(sections.artistAndEra(), "sections.artistAndEra", MAX_SECTION_CHARACTERS),
                optionalChineseText(sections.subjectAndScene(), "sections.subjectAndScene", MAX_SECTION_CHARACTERS),
                optionalChineseText(sections.composition(), "sections.composition", MAX_SECTION_CHARACTERS),
                optionalChineseText(sections.brushworkAndInk(), "sections.brushworkAndInk", MAX_SECTION_CHARACTERS),
                optionalChineseText(sections.colorAndMaterial(), "sections.colorAndMaterial", MAX_SECTION_CHARACTERS),
                optionalChineseText(sections.artisticConception(), "sections.artisticConception", MAX_SECTION_CHARACTERS),
                optionalChineseText(sections.culturalMeaning(), "sections.culturalMeaning", MAX_SECTION_CHARACTERS),
                optionalChineseText(sections.musicAssociation(), "sections.musicAssociation", MAX_SECTION_CHARACTERS));
    }

    private List<String> normalizeHighlights(List<String> highlights) {
        if (highlights == null
                || highlights.size() < MIN_HIGHLIGHT_COUNT
                || highlights.size() > MAX_HIGHLIGHT_COUNT) {
            throw invalid("highlights must contain between 2 and 5 items");
        }
        List<String> normalized = new ArrayList<>(highlights.size());
        Set<String> unique = new HashSet<>();
        for (String highlight : highlights) {
            String safe = requireChineseText(
                    highlight, "highlights item", MAX_HIGHLIGHT_CHARACTERS, true);
            if (!unique.add(safe)) {
                throw invalid("highlights must not contain duplicates");
            }
            normalized.add(safe);
        }
        return normalized;
    }

    private List<GuideKnowledgeReference> normalizeReferences(
            List<GuideKnowledgeReference> references,
            List<KnowledgeItem> allowedKnowledge) {
        List<GuideKnowledgeReference> supplied = references == null ? List.of() : references;
        if (supplied.size() > MAX_REFERENCE_COUNT) {
            throw invalid("knowledgeReferences exceeds the allowed item count");
        }

        Map<String, KnowledgeItem> allowedById = new HashMap<>();
        for (KnowledgeItem item : allowedKnowledge == null ? List.<KnowledgeItem>of() : allowedKnowledge) {
            if (item != null && item.sourceId() != null && !item.sourceId().isBlank()) {
                KnowledgeItem duplicate = allowedById.putIfAbsent(item.sourceId(), item);
                if (duplicate != null) {
                    throw invalid("Supplied knowledge contains a duplicate sourceId");
                }
            }
        }

        List<GuideKnowledgeReference> normalized = new ArrayList<>(supplied.size());
        Set<String> referencedIds = new HashSet<>();
        for (GuideKnowledgeReference reference : supplied) {
            if (reference == null) {
                throw invalid("knowledgeReferences contains a null item");
            }
            String sourceId = requireText(
                    reference.sourceId(), "knowledgeReferences.sourceId", MAX_SOURCE_ID_CHARACTERS, false);
            String sourceType = requireText(
                    reference.sourceType(), "knowledgeReferences.sourceType", MAX_SOURCE_TYPE_CHARACTERS, false);
            String title = requireText(
                    reference.title(), "knowledgeReferences.title", MAX_REFERENCE_TITLE_CHARACTERS, true);
            if (!referencedIds.add(sourceId)) {
                throw invalid("knowledgeReferences must not repeat a sourceId");
            }
            KnowledgeItem allowed = allowedById.get(sourceId);
            if (allowed == null
                    || !sourceType.equals(allowed.sourceType())
                    || !title.equals(allowed.title())) {
                throw invalid("knowledgeReferences contains an unsupported reference");
            }
            normalized.add(new GuideKnowledgeReference(sourceId, sourceType, title));
        }
        return normalized;
    }

    private String optionalChineseText(String value, String field, int maxCharacters) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireChineseText(value, field, maxCharacters, true);
    }

    private String requireChineseText(
            String value,
            String field,
            int maxCharacters,
            boolean rejectMarkup) {
        String normalized = requireText(value, field, maxCharacters, rejectMarkup);
        if (!HAN_CHARACTER.matcher(normalized).find()) {
            throw invalid(field + " must contain Chinese text");
        }
        return normalized;
    }

    private String requireText(String value, String field, int maxCharacters, boolean rejectMarkup) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxCharacters) {
            throw invalid(field + " exceeds its character limit");
        }
        if (rejectMarkup) {
            rejectMarkup(normalized, field);
        }
        return normalized;
    }

    private void rejectMarkup(String value, String field) {
        if (value.contains("```") || HTML_TAG.matcher(value).find()) {
            throw invalid(field + " must not contain code fences or HTML");
        }
    }

    private void requireByteLimit(String value, String field) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw invalid(field + " exceeds the byte limit");
        }
    }

    private GuideResultValidationException invalid(String message) {
        return new GuideResultValidationException(message);
    }
}
