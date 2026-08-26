package com.auralink.provider.qwen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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

/**
 * Strict four-line Chinese poem validation without claiming regulated verse.
 *
 * <p>The deterministic rejection order is: forbidden content; JSON root,
 * unknown, and duplicate structure; schema version; title; lines container;
 * line count; line types; blank lines; line length; Chinese dominance;
 * duplicate lines; text presence/type; text consistency; configured size.</p>
 */
@Component
@RequiredArgsConstructor
public class PaintingPoemResultValidator {

    private static final Set<String> FIELDS = Set.of("schemaVersion", "title", "lines", "text");
    private static final int MAX_LINE_CHARS = 100;
    private static final long MAX_STRUCTURED_JSON_OVERHEAD_CHARS = 16L * 1024L;

    private final ObjectMapper objectMapper;
    private final CreationProviderProperties properties;

    public PaintingPoemResult validate(String rawJson) {
        QwenResponseShapeDiagnostic.Builder shape = QwenResponseShapeDiagnostic.builder()
                .contentPresent(rawJson != null);
        if (rawJson != null) {
            shape.contentType(QwenSafeValueType.STRING).contentLength(rawJson.length());
        }
        return validate(rawJson, shape.build());
    }

    PaintingPoemResult validate(QwenResponseContent response) {
        if (response == null) {
            return validate((String) null);
        }
        return validate(response.content(), response.responseShape());
    }

    private PaintingPoemResult validate(
            String rawJson,
            QwenResponseShapeDiagnostic baseShape) {
        QwenResponseShapeDiagnostic.Builder shape = baseShape.toBuilder();
        if (rawJson == null) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_MISSING,
                    shape);
        }
        if (rawJson.isBlank()) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_BLANK,
                    shape);
        }

        boolean markdown = QwenResponseInspection.hasMarkdownFence(rawJson);
        boolean html = QwenResponseInspection.hasHtml(rawJson);
        boolean reasoning = QwenResponseInspection.hasReasoningMarker(rawJson);
        boolean aiSelfReference = QwenResponseInspection.hasAiSelfReference(rawJson);
        boolean outsideJson = QwenResponseInspection.hasLeadingOrTrailingContent(rawJson);
        shape.hasMarkdownFence(markdown)
                .hasHtml(html)
                .hasReasoningMarker(reasoning)
                .hasAiSelfReference(aiSelfReference)
                .hasLeadingOrTrailingContent(outsideJson);

        long rawContentLimit = Math.min(
                QwenResponseShapeDiagnostic.MAX_SAFE_COUNT_OR_LENGTH,
                (long) properties.getMaxTextChars() + MAX_STRUCTURED_JSON_OVERHEAD_CHARS);
        if (rawJson.length() > rawContentLimit) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_TOO_LARGE,
                    shape);
        }

        final JsonNode root;
        try {
            root = StrictProviderJson.parse(objectMapper, rawJson);
            shape.jsonParsed(true).topLevelType(QwenSafeValueType.from(root));
        } catch (Exception exception) {
            shape.jsonParsed(false);
            if (markdown) {
                throw invalid(
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_MARKDOWN_FENCE,
                        shape);
            }
            if (html) {
                throw invalid(
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_HTML,
                        shape);
            }
            if (reasoning) {
                throw invalid(
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER,
                        shape);
            }
            if (aiSelfReference) {
                throw invalid(
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_OUTPUT_FORBIDDEN_CONTENT,
                        shape);
            }
            if (outsideJson || QwenResponseInspection.isTrailingTokenFailure(exception)) {
                shape.hasLeadingOrTrailingContent(true);
                throw invalid(
                        QwenResponseValidationStage.JSON_SYNTAX,
                        QwenResponseValidationCode.QWEN_JSON_TRAILING_CONTENT,
                        shape);
            }
            if (QwenResponseInspection.isDuplicateFieldFailure(exception)) {
                shape.duplicateFieldCount(1);
                throw invalid(
                        QwenResponseValidationStage.JSON_STRUCTURE,
                        QwenResponseValidationCode.QWEN_JSON_DUPLICATE_FIELD,
                        shape);
            }
            throw invalid(
                    QwenResponseValidationStage.JSON_SYNTAX,
                    QwenResponseValidationCode.QWEN_JSON_PARSE_FAILED,
                    shape);
        }

        if (markdown) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_MARKDOWN_FENCE,
                    shape);
        }
        if (html) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_HTML,
                    shape);
        }
        if (reasoning) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER,
                    shape);
        }
        if (aiSelfReference) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_OUTPUT_FORBIDDEN_CONTENT,
                    shape);
        }
        if (ChineseTextRules.containsForbiddenMarkupOrLeakage(rawJson)
                || ChineseTextRules.containsUnsupportedAuthorshipClaim(rawJson)) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_OUTPUT_FORBIDDEN_CONTENT,
                    shape);
        }
        if (root == null || !root.isObject()) {
            throw invalid(
                    QwenResponseValidationStage.JSON_STRUCTURE,
                    QwenResponseValidationCode.QWEN_JSON_ROOT_NOT_OBJECT,
                    shape);
        }

        // Scan decoded values as well as the raw JSON so Unicode/JSON escaping
        // cannot hide forbidden content. Only the four allowlisted poem fields
        // are inspected, and no decoded value leaves this method.
        ForbiddenFacts decodedForbidden = inspectDecodedKnownContent(root);
        shape.hasMarkdownFence(markdown || decodedForbidden.markdown())
                .hasHtml(html || decodedForbidden.html())
                .hasReasoningMarker(reasoning || decodedForbidden.reasoning())
                .hasAiSelfReference(aiSelfReference || decodedForbidden.aiSelfReference());
        if (decodedForbidden.markdown()) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_MARKDOWN_FENCE,
                    shape);
        }
        if (decodedForbidden.html()) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_HTML,
                    shape);
        }
        if (decodedForbidden.reasoning()) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER,
                    shape);
        }
        if (decodedForbidden.aiSelfReference() || decodedForbidden.otherForbidden()) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_OUTPUT_FORBIDDEN_CONTENT,
                    shape);
        }

        collectAvailableShape(root, shape);
        int unknownFields = countUnknownFields(root);
        shape.unknownFieldCount(unknownFields);
        if (unknownFields > 0) {
            throw invalid(
                    QwenResponseValidationStage.JSON_STRUCTURE,
                    QwenResponseValidationCode.QWEN_JSON_UNKNOWN_FIELDS,
                    shape);
        }

        boolean schemaPresent = root.has("schemaVersion");
        shape.schemaVersionPresent(schemaPresent);
        if (!schemaPresent) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SCHEMA,
                    QwenResponseValidationCode.QWEN_SCHEMA_VERSION_MISSING,
                    shape);
        }
        JsonNode schemaNode = root.get("schemaVersion");
        shape.schemaVersionType(QwenSafeValueType.from(schemaNode));
        if (!schemaNode.isTextual()) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SCHEMA,
                    QwenResponseValidationCode.QWEN_SCHEMA_VERSION_TYPE_INVALID,
                    shape);
        }
        String schemaVersion = schemaNode.textValue().trim();
        if (!"1".equals(schemaVersion)) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SCHEMA,
                    QwenResponseValidationCode.QWEN_SCHEMA_VERSION_UNSUPPORTED,
                    shape);
        }

        boolean titlePresent = root.has("title");
        shape.titlePresent(titlePresent);
        if (!titlePresent) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SCHEMA,
                    QwenResponseValidationCode.QWEN_TITLE_MISSING,
                    shape);
        }
        JsonNode titleNode = root.get("title");
        shape.titleType(QwenSafeValueType.from(titleNode));
        String title = null;
        if (!titleNode.isNull()) {
            if (!titleNode.isTextual()) {
                throw invalid(
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_TITLE_TYPE_INVALID,
                        shape);
            }
            title = titleNode.textValue().trim();
            shape.titleLength(title.length());
            if (title.isEmpty()) {
                throw invalid(
                        QwenResponseValidationStage.POEM_SEMANTICS,
                        QwenResponseValidationCode.QWEN_TITLE_BLANK,
                        shape);
            }
            if (title.length() > MAX_LINE_CHARS) {
                throw invalid(
                        QwenResponseValidationStage.POEM_SEMANTICS,
                        QwenResponseValidationCode.QWEN_TITLE_TOO_LONG,
                        shape);
            }
            if (!ChineseTextRules.containsChinese(title)) {
                throw invalid(
                        QwenResponseValidationStage.POEM_SEMANTICS,
                        QwenResponseValidationCode.QWEN_TITLE_NON_CHINESE,
                        shape);
            }
        }

        boolean linesPresent = root.has("lines");
        shape.linesPresent(linesPresent);
        if (!linesPresent) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SCHEMA,
                    QwenResponseValidationCode.QWEN_LINES_MISSING,
                    shape);
        }
        JsonNode linesNode = root.get("lines");
        shape.linesType(QwenSafeValueType.from(linesNode));
        if (!linesNode.isArray()) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SCHEMA,
                    QwenResponseValidationCode.QWEN_LINES_TYPE_INVALID,
                    shape);
        }

        LineFacts lineFacts = inspectLines(linesNode);
        shape.lineCount(linesNode.size())
                .stringLineCount(lineFacts.stringCount())
                .nonblankLineCount(lineFacts.nonblankCount())
                .chineseDominantLineCount(lineFacts.chineseDominantCount())
                .duplicateLineCount(lineFacts.duplicateCount());
        if (lineFacts.minimumLength() != null) {
            shape.minimumLineLength(lineFacts.minimumLength())
                    .maximumLineLength(lineFacts.maximumLength());
        }
        if (linesNode.size() != 4) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SCHEMA,
                    QwenResponseValidationCode.QWEN_LINES_COUNT_INVALID,
                    shape);
        }
        if (lineFacts.stringCount() != linesNode.size()) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SCHEMA,
                    QwenResponseValidationCode.QWEN_LINE_TYPE_INVALID,
                    shape);
        }
        if (lineFacts.nonblankCount() != linesNode.size()) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SEMANTICS,
                    QwenResponseValidationCode.QWEN_LINE_BLANK,
                    shape);
        }
        if (lineFacts.maximumLength() != null && lineFacts.maximumLength() > MAX_LINE_CHARS) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SEMANTICS,
                    QwenResponseValidationCode.QWEN_LINE_TOO_LONG,
                    shape);
        }
        if (lineFacts.chineseDominantCount() != linesNode.size()) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SEMANTICS,
                    QwenResponseValidationCode.QWEN_LINE_NON_CHINESE,
                    shape);
        }
        if (lineFacts.duplicateCount() > 0) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SEMANTICS,
                    QwenResponseValidationCode.QWEN_LINE_DUPLICATE,
                    shape);
        }

        boolean textPresent = root.has("text");
        shape.textPresent(textPresent);
        if (!textPresent) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SCHEMA,
                    QwenResponseValidationCode.QWEN_TEXT_MISSING,
                    shape);
        }
        JsonNode textNode = root.get("text");
        shape.textType(QwenSafeValueType.from(textNode));
        if (!textNode.isTextual()) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SCHEMA,
                    QwenResponseValidationCode.QWEN_TEXT_TYPE_INVALID,
                    shape);
        }
        String text = textNode.textValue().trim();
        shape.textLength(text.length());
        if (text.isEmpty()) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SEMANTICS,
                    QwenResponseValidationCode.QWEN_TEXT_BLANK,
                    shape);
        }
        boolean textMatches = text.equals(String.join("\n", lineFacts.normalizedLines()));
        shape.textMatchesLines(textMatches);
        if (!textMatches) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SEMANTICS,
                    QwenResponseValidationCode.QWEN_TEXT_MISMATCH,
                    shape);
        }

        int total = text.length() + (title == null ? 0 : title.length());
        if (total > properties.getMaxTextChars()) {
            throw invalid(
                    QwenResponseValidationStage.POEM_SEMANTICS,
                    QwenResponseValidationCode.QWEN_CONTENT_TOO_LARGE,
                    shape);
        }
        return new PaintingPoemResult(schemaVersion, title, lineFacts.normalizedLines(), text);
    }

    private void collectAvailableShape(
            JsonNode root,
            QwenResponseShapeDiagnostic.Builder shape) {
        boolean schemaPresent = root.has("schemaVersion");
        shape.schemaVersionPresent(schemaPresent);
        if (schemaPresent) {
            shape.schemaVersionType(QwenSafeValueType.from(root.get("schemaVersion")));
        }

        boolean titlePresent = root.has("title");
        shape.titlePresent(titlePresent);
        if (titlePresent) {
            JsonNode titleNode = root.get("title");
            shape.titleType(QwenSafeValueType.from(titleNode));
            if (titleNode.isTextual()) {
                shape.titleLength(titleNode.textValue().trim().length());
            }
        }

        boolean linesPresent = root.has("lines");
        shape.linesPresent(linesPresent);
        LineFacts lineFacts = null;
        if (linesPresent) {
            JsonNode linesNode = root.get("lines");
            shape.linesType(QwenSafeValueType.from(linesNode));
            if (linesNode.isArray()) {
                lineFacts = inspectLines(linesNode);
                shape.lineCount(linesNode.size())
                        .stringLineCount(lineFacts.stringCount())
                        .nonblankLineCount(lineFacts.nonblankCount())
                        .chineseDominantLineCount(lineFacts.chineseDominantCount())
                        .duplicateLineCount(lineFacts.duplicateCount());
                if (lineFacts.minimumLength() != null) {
                    shape.minimumLineLength(lineFacts.minimumLength())
                            .maximumLineLength(lineFacts.maximumLength());
                }
            }
        }

        boolean textPresent = root.has("text");
        shape.textPresent(textPresent);
        if (textPresent) {
            JsonNode textNode = root.get("text");
            shape.textType(QwenSafeValueType.from(textNode));
            if (textNode.isTextual()) {
                String text = textNode.textValue().trim();
                shape.textLength(text.length());
                JsonNode linesNode = root.get("lines");
                if (lineFacts != null && linesNode != null
                        && lineFacts.stringCount() == linesNode.size()) {
                    shape.textMatchesLines(
                            text.equals(String.join("\n", lineFacts.normalizedLines())));
                }
            }
        }
    }

    private int countUnknownFields(JsonNode root) {
        int count = 0;
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            if (!FIELDS.contains(names.next())) {
                count++;
            }
        }
        return count;
    }

    private ForbiddenFacts inspectDecodedKnownContent(JsonNode root) {
        ForbiddenFacts facts = inspectDecodedText(root.get("title"));
        JsonNode linesNode = root.get("lines");
        if (linesNode != null && linesNode.isArray()) {
            for (JsonNode lineNode : linesNode) {
                facts = facts.merge(inspectDecodedText(lineNode));
            }
        }
        return facts.merge(inspectDecodedText(root.get("text")));
    }

    private ForbiddenFacts inspectDecodedText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return ForbiddenFacts.NONE;
        }
        String value = node.textValue();
        boolean markdown = QwenResponseInspection.hasMarkdownFence(value);
        boolean html = QwenResponseInspection.hasHtml(value);
        boolean reasoning = QwenResponseInspection.hasReasoningMarker(value);
        boolean aiSelfReference = QwenResponseInspection.hasAiSelfReference(value);
        boolean otherForbidden = ChineseTextRules.containsForbiddenMarkupOrLeakage(value)
                || ChineseTextRules.containsUnsupportedAuthorshipClaim(value);
        return new ForbiddenFacts(
                markdown,
                html,
                reasoning,
                aiSelfReference,
                otherForbidden);
    }

    private LineFacts inspectLines(JsonNode linesNode) {
        List<String> normalized = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        int strings = 0;
        int nonblank = 0;
        int chineseDominant = 0;
        int duplicates = 0;
        Integer minimum = null;
        Integer maximum = null;
        for (JsonNode lineNode : linesNode) {
            if (lineNode == null || !lineNode.isTextual()) {
                continue;
            }
            strings++;
            String line = lineNode.textValue().trim();
            normalized.add(line);
            int length = line.length();
            minimum = minimum == null ? length : Math.min(minimum, length);
            maximum = maximum == null ? length : Math.max(maximum, length);
            if (!line.isEmpty()) {
                nonblank++;
            }
            if (QwenResponseInspection.isChineseDominant(line)) {
                chineseDominant++;
            }
            if (!distinct.add(line)) {
                duplicates++;
            }
        }
        return new LineFacts(
                List.copyOf(normalized),
                strings,
                nonblank,
                chineseDominant,
                duplicates,
                minimum,
                maximum);
    }

    private ProviderExecutionException invalid(
            QwenResponseValidationStage stage,
            QwenResponseValidationCode code,
            QwenResponseShapeDiagnostic.Builder shape) {
        return ProviderExecutionException.fromSafeDiagnostic(
                ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                "Qwen painting-to-poem response failed strict validation",
                new QwenResponseValidationDiagnostic(stage, code, shape.build()));
    }

    private record LineFacts(
            List<String> normalizedLines,
            int stringCount,
            int nonblankCount,
            int chineseDominantCount,
            int duplicateCount,
            Integer minimumLength,
            Integer maximumLength) {
    }

    private record ForbiddenFacts(
            boolean markdown,
            boolean html,
            boolean reasoning,
            boolean aiSelfReference,
            boolean otherForbidden) {

        private static final ForbiddenFacts NONE =
                new ForbiddenFacts(false, false, false, false, false);

        private ForbiddenFacts merge(ForbiddenFacts other) {
            return new ForbiddenFacts(
                    markdown || other.markdown,
                    html || other.html,
                    reasoning || other.reasoning,
                    aiSelfReference || other.aiSelfReference,
                    otherForbidden || other.otherForbidden);
        }
    }
}
