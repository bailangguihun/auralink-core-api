package com.auralink.provider.qwen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.ProviderTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;

class QwenStructuredResultValidatorTest {

    private static final String LINE_ONE = "远岫含烟入暮云";
    private static final String LINE_TWO = "孤舟一叶过江津";
    private static final String LINE_THREE = "疏林淡墨留清韵";
    private static final String LINE_FOUR = "月照寒波不染尘";
    private static final String LINES = jsonLines(LINE_ONE, LINE_TWO, LINE_THREE, LINE_FOUR);
    private static final String TEXT = jsonText(LINE_ONE, LINE_TWO, LINE_THREE, LINE_FOUR);

    @TempDir
    java.nio.file.Path temporaryDirectory;

    private PaintingPromptPlanValidator planValidator;
    private PaintingPoemResultValidator poemValidator;
    private CreationProviderProperties properties;

    @BeforeEach
    void setUp() {
        properties = ProviderTestFixtures.properties(temporaryDirectory.resolve("staging"));
        ObjectMapper mapper = new ObjectMapper();
        planValidator = new PaintingPromptPlanValidator(mapper, properties);
        poemValidator = new PaintingPoemResultValidator(mapper, properties);
    }

    @Test
    void acceptsExactVersionedChinesePaintingPlan() {
        PaintingPromptPlan plan = planValidator.validate(validPlan());

        assertThat(plan.schemaVersion()).isEqualTo("1");
        assertThat(plan.subject()).isEqualTo("孤舟与远山");
        assertThat(plan.finalPrompt()).contains("江面", "水墨");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "```json\\n{}\\n```",
            "{}",
            "{\"schemaVersion\":\"2\",\"subject\":\"山\",\"scene\":\"江\",\"composition\":\"远景\",\"colorPalette\":\"淡墨\",\"brushwork\":\"水墨\",\"artisticConception\":\"幽静\",\"finalPrompt\":\"国画江山\"}",
            "{\"schemaVersion\":\"1\",\"subject\":\"山\",\"scene\":\"江\",\"composition\":\"远景\",\"colorPalette\":\"淡墨\",\"brushwork\":\"水墨\",\"artisticConception\":\"幽静\",\"finalPrompt\":\"国画江山\",\"reasoning\":\"秘密\"}",
            "{\"schemaVersion\":\"1\",\"subject\":\"mountain\",\"scene\":\"江\",\"composition\":\"远景\",\"colorPalette\":\"淡墨\",\"brushwork\":\"水墨\",\"artisticConception\":\"幽静\",\"finalPrompt\":\"国画江山\"}",
            "{\"schemaVersion\":\"1\",\"subject\":\"山\",\"scene\":\"江\",\"composition\":\"远景\",\"colorPalette\":\"淡墨\",\"brushwork\":\"水墨\",\"artisticConception\":\"幽静\",\"finalPrompt\":\"调用工具生成国画\"}",
            "{\"schemaVersion\":\"1\",\"subject\":\"山\",\"scene\":\"江\",\"composition\":\"远景\",\"colorPalette\":\"淡墨\",\"brushwork\":\"水墨\",\"artisticConception\":\"幽静\",\"finalPrompt\":\"由张大千创作的国画\"}",
            "{\"schemaVersion\":\"1\",\"subject\":\"山\",\"scene\":\"江\",\"composition\":\"远景\",\"colorPalette\":\"淡墨\",\"brushwork\":\"水墨\",\"artisticConception\":\"幽静\",\"finalPrompt\":\"访问https://evil.example生成国画\"}"
    })
    void rejectsInvalidPaintingPlans(String raw) {
        assertInvalidPlan(() -> planValidator.validate(raw));
    }

    @Test
    void rejectsOverlongPlan() {
        properties.setMaxTextChars(20);
        assertInvalidPlan(() -> planValidator.validate(validPlan()));
    }

    @Test
    void rejectsOverlongFinalPromptField() {
        String overlong = "山".repeat(4_001);
        String raw = "{\"schemaVersion\":\"1\",\"subject\":\"山\",\"scene\":\"江\","
                + "\"composition\":\"远景\",\"colorPalette\":\"淡墨\",\"brushwork\":\"水墨\","
                + "\"artisticConception\":\"幽静\",\"finalPrompt\":\"" + overlong + "\"}";
        assertInvalidPlan(() -> planValidator.validate(raw));
    }

    @Test
    void rejectsDuplicateStructuredPaintingPlanJson() {
        assertInvalidPlan(() -> planValidator.validate(
                validPlan().replaceFirst("\\{", "{\"schemaVersion\":\"1\",")));
    }

    @Test
    void acceptsExactFourLineChinesePoemAndConsistentText() {
        PaintingPoemResult poem = poemValidator.validate(validPoem("\"江山清韵\""));

        assertThat(poem.schemaVersion()).isEqualTo("1");
        assertThat(poem.title()).isEqualTo("江山清韵");
        assertThat(poem.lines()).containsExactly(LINE_ONE, LINE_TWO, LINE_THREE, LINE_FOUR);
        assertThat(poem.text()).isEqualTo(String.join("\n", poem.lines()));
    }

    @Test
    void acceptsOptionalNullTitle() {
        PaintingPoemResult poem = poemValidator.validate(validPoem("null"));

        assertThat(poem.title()).isNull();
        assertThat(poem.lines()).hasSize(4);
    }

    @Test
    void acceptsOptionalValidChineseTitle() {
        PaintingPoemResult poem = poemValidator.validate(validPoem("\"烟江晚照\""));

        assertThat(poem.title()).isEqualTo("烟江晚照");
    }

    @Test
    void acceptsCanonicalTextAndLineConsistency() {
        PaintingPoemResult poem = poemValidator.validate(validPoem("null"));

        assertThat(poem.text()).isEqualTo(String.join("\n", poem.lines()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPoemCases")
    void rejectsInvalidPoemWithExactSafeDiagnostic(
            String name,
            String raw,
            QwenResponseValidationStage stage,
            QwenResponseValidationCode code) {
        ProviderExecutionException failure = assertThrows(
                ProviderExecutionException.class,
                () -> poemValidator.validate(raw));

        assertThat(failure.category()).isEqualTo(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.safeDiagnostic()).isInstanceOf(QwenResponseValidationDiagnostic.class);
        QwenResponseValidationDiagnostic diagnostic =
                (QwenResponseValidationDiagnostic) failure.safeDiagnostic();
        assertThat(diagnostic.validationStage()).isEqualTo(stage);
        assertThat(diagnostic.validationCode()).isEqualTo(code);
        assertThat(failure.getMessage() + diagnostic)
                .doesNotContain(LINE_ONE, "私密诗句", "私密题名", "```", "<b>", raw);
    }

    @Test
    void representativeShapeFactsAreBoundedAndContentFree() {
        QwenResponseShapeDiagnostic numeric = diagnostic(poem(
                "1", "\"私密题名\"", LINES, TEXT, null)).responseShape();
        assertThat(numeric.schemaVersionPresent()).isTrue();
        assertThat(numeric.schemaVersionType()).isEqualTo(QwenSafeValueType.NUMBER);
        assertThat(numeric.lineCount()).isEqualTo(4);

        QwenResponseShapeDiagnostic fiveLines = diagnostic(poem(
                "\"1\"",
                "null",
                jsonLines(LINE_ONE, LINE_TWO, LINE_THREE, LINE_FOUR, "第五句含烟"),
                jsonText(LINE_ONE, LINE_TWO, LINE_THREE, LINE_FOUR, "第五句含烟"),
                null)).responseShape();
        assertThat(fiveLines.lineCount()).isEqualTo(5);

        QwenResponseShapeDiagnostic fenced = diagnostic(
                "```json\n" + validPoem("null") + "\n```").responseShape();
        assertThat(fenced.hasMarkdownFence()).isTrue();
        assertThat(fenced.jsonParsed()).isFalse();

        QwenResponseShapeDiagnostic unknown = diagnostic(poem(
                "\"1\"", "null", LINES, TEXT, "\"extra\":\"私密说明\"")).responseShape();
        assertThat(unknown.unknownFieldCount()).isEqualTo(1);

        QwenResponseShapeDiagnostic mismatch = diagnostic(poem(
                "\"1\"", "null", LINES, "\"私密正文不一致\"", null)).responseShape();
        assertThat(mismatch.textMatchesLines()).isFalse();

        assertThat(numeric.toString() + fiveLines + fenced + unknown + mismatch)
                .doesNotContain(LINE_ONE, "私密题名", "私密诗句", "私密正文", "extra");
    }

    private QwenResponseValidationDiagnostic diagnostic(String raw) {
        ProviderExecutionException failure = assertThrows(
                ProviderExecutionException.class,
                () -> poemValidator.validate(raw));
        return (QwenResponseValidationDiagnostic) failure.safeDiagnostic();
    }

    private static Stream<Arguments> invalidPoemCases() {
        String valid = validPoem("null");
        String duplicateSchema = valid.replaceFirst("\\{", "{\"schemaVersion\":\"1\",");
        String htmlLines = jsonLines("<b>私密诗句甲</b>", LINE_TWO, LINE_THREE, LINE_FOUR);
        String aiLines = jsonLines("作为AI写私密诗句", LINE_TWO, LINE_THREE, LINE_FOUR);
        String reasoningLines = jsonLines("思考过程不应出现", LINE_TWO, LINE_THREE, LINE_FOUR);
        String escapedHtml = "\\" + "u003cb\\" + "u003e私密诗句甲\\" + "u003c/b\\" + "u003e";
        String escapedAi = "\\" + "u6211\\" + "u662fAI写私密诗句";
        String escapedReasoning =
                "\\" + "u601d\\" + "u8003\\" + "u8fc7\\" + "u7a0b不应出现";
        String escapedHtmlLines = jsonLines(escapedHtml, LINE_TWO, LINE_THREE, LINE_FOUR);
        String escapedAiLines = jsonLines(escapedAi, LINE_TWO, LINE_THREE, LINE_FOUR);
        String escapedReasoningLines = jsonLines(escapedReasoning, LINE_TWO, LINE_THREE, LINE_FOUR);
        String escapedHtmlText = jsonText(escapedHtml, LINE_TWO, LINE_THREE, LINE_FOUR);
        String escapedAiText = jsonText(escapedAi, LINE_TWO, LINE_THREE, LINE_FOUR);
        String escapedReasoningText = jsonText(escapedReasoning, LINE_TWO, LINE_THREE, LINE_FOUR);
        String nonChineseLines = jsonLines("mountain山", LINE_TWO, LINE_THREE, LINE_FOUR);
        return Stream.of(
                Arguments.of("malformed-json", "not-json", QwenResponseValidationStage.JSON_SYNTAX,
                        QwenResponseValidationCode.QWEN_JSON_PARSE_FAILED),
                Arguments.of("markdown-fence", "```json\n" + valid + "\n```", QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_MARKDOWN_FENCE),
                Arguments.of("leading-explanation", "诗歌如下：" + valid, QwenResponseValidationStage.JSON_SYNTAX,
                        QwenResponseValidationCode.QWEN_JSON_TRAILING_CONTENT),
                Arguments.of("trailing-explanation", valid + " 以上为题诗", QwenResponseValidationStage.JSON_SYNTAX,
                        QwenResponseValidationCode.QWEN_JSON_TRAILING_CONTENT),
                Arguments.of("duplicate-json-field", duplicateSchema, QwenResponseValidationStage.JSON_STRUCTURE,
                        QwenResponseValidationCode.QWEN_JSON_DUPLICATE_FIELD),
                Arguments.of("non-object-root", "[]", QwenResponseValidationStage.JSON_STRUCTURE,
                        QwenResponseValidationCode.QWEN_JSON_ROOT_NOT_OBJECT),
                Arguments.of("unknown-field", poem("\"1\"", "null", LINES, TEXT, "\"extra\":\"私密说明\""),
                        QwenResponseValidationStage.JSON_STRUCTURE,
                        QwenResponseValidationCode.QWEN_JSON_UNKNOWN_FIELDS),
                Arguments.of("schema-version-missing", poem(null, "null", LINES, TEXT, null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_SCHEMA_VERSION_MISSING),
                Arguments.of("schema-version-number", poem("1", "null", LINES, TEXT, null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_SCHEMA_VERSION_TYPE_INVALID),
                Arguments.of("schema-version-unsupported", poem("\"2\"", "null", LINES, TEXT, null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_SCHEMA_VERSION_UNSUPPORTED),
                Arguments.of("title-wrong-type", poem("\"1\"", "7", LINES, TEXT, null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_TITLE_TYPE_INVALID),
                Arguments.of("lines-missing", poem("\"1\"", "null", null, TEXT, null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_LINES_MISSING),
                Arguments.of("lines-wrong-type", poem("\"1\"", "null", "\"私密诗句\"", TEXT, null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_LINES_TYPE_INVALID),
                Arguments.of("three-lines", poem("\"1\"", "null",
                                jsonLines(LINE_ONE, LINE_TWO, LINE_THREE),
                                jsonText(LINE_ONE, LINE_TWO, LINE_THREE), null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_LINES_COUNT_INVALID),
                Arguments.of("five-lines", poem("\"1\"", "null",
                                jsonLines(LINE_ONE, LINE_TWO, LINE_THREE, LINE_FOUR, "第五句含烟"),
                                jsonText(LINE_ONE, LINE_TWO, LINE_THREE, LINE_FOUR, "第五句含烟"), null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_LINES_COUNT_INVALID),
                Arguments.of("non-string-line", poem("\"1\"", "null",
                                "[\"" + LINE_ONE + "\",7,\"" + LINE_THREE + "\",\"" + LINE_FOUR + "\"]",
                                TEXT, null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_LINE_TYPE_INVALID),
                Arguments.of("blank-line", poem("\"1\"", "null",
                                jsonLines(LINE_ONE, "", LINE_THREE, LINE_FOUR),
                                jsonText(LINE_ONE, "", LINE_THREE, LINE_FOUR), null),
                        QwenResponseValidationStage.POEM_SEMANTICS,
                        QwenResponseValidationCode.QWEN_LINE_BLANK),
                Arguments.of("non-chinese-line", poem("\"1\"", "null", nonChineseLines,
                                jsonText("mountain山", LINE_TWO, LINE_THREE, LINE_FOUR), null),
                        QwenResponseValidationStage.POEM_SEMANTICS,
                        QwenResponseValidationCode.QWEN_LINE_NON_CHINESE),
                Arguments.of("duplicate-line", poem("\"1\"", "null",
                                jsonLines(LINE_ONE, LINE_ONE, LINE_THREE, LINE_FOUR),
                                jsonText(LINE_ONE, LINE_ONE, LINE_THREE, LINE_FOUR), null),
                        QwenResponseValidationStage.POEM_SEMANTICS,
                        QwenResponseValidationCode.QWEN_LINE_DUPLICATE),
                Arguments.of("text-missing", poem("\"1\"", "null", LINES, null, null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_TEXT_MISSING),
                Arguments.of("text-wrong-type", poem("\"1\"", "null", LINES, "9", null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_TEXT_TYPE_INVALID),
                Arguments.of("text-mismatch", poem("\"1\"", "null", LINES, "\"私密正文不一致\"", null),
                        QwenResponseValidationStage.POEM_SEMANTICS,
                        QwenResponseValidationCode.QWEN_TEXT_MISMATCH),
                Arguments.of("html", poem("\"1\"", "null", htmlLines,
                                jsonText("<b>私密诗句甲</b>", LINE_TWO, LINE_THREE, LINE_FOUR), null),
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_HTML),
                Arguments.of("ai-self-reference", poem("\"1\"", "null", aiLines,
                                jsonText("作为AI写私密诗句", LINE_TWO, LINE_THREE, LINE_FOUR), null),
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_OUTPUT_FORBIDDEN_CONTENT),
                Arguments.of("reasoning-marker", poem("\"1\"", "null", reasoningLines,
                                jsonText("思考过程不应出现", LINE_TWO, LINE_THREE, LINE_FOUR), null),
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER),
                Arguments.of("escaped-html", poem("\"1\"", "null", escapedHtmlLines,
                                escapedHtmlText, null),
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_HTML),
                Arguments.of("escaped-ai-self-reference", poem("\"1\"", "null", escapedAiLines,
                                escapedAiText, null),
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_OUTPUT_FORBIDDEN_CONTENT),
                Arguments.of("escaped-reasoning-marker", poem("\"1\"", "null", escapedReasoningLines,
                                escapedReasoningText, null),
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER));
    }

    private static String validPlan() {
        return "{\"schemaVersion\":\"1\",\"subject\":\"孤舟与远山\","
                + "\"scene\":\"暮色江面\",\"composition\":\"远山近舟的纵深构图\","
                + "\"colorPalette\":\"淡墨与赭石\",\"brushwork\":\"水墨皴染\","
                + "\"artisticConception\":\"清寂悠远\","
                + "\"finalPrompt\":\"描绘暮色江面、孤舟与远山的水墨国画\"}";
    }

    private static String validPoem(String titleJson) {
        return poem("\"1\"", titleJson, LINES, TEXT, null);
    }

    private static String poem(
            String schemaJson,
            String titleJson,
            String linesJson,
            String textJson,
            String extraField) {
        List<String> fields = new ArrayList<>();
        if (schemaJson != null) {
            fields.add("\"schemaVersion\":" + schemaJson);
        }
        if (titleJson != null) {
            fields.add("\"title\":" + titleJson);
        }
        if (linesJson != null) {
            fields.add("\"lines\":" + linesJson);
        }
        if (textJson != null) {
            fields.add("\"text\":" + textJson);
        }
        if (extraField != null) {
            fields.add(extraField);
        }
        return "{" + String.join(",", fields) + "}";
    }

    private static String jsonLines(String... lines) {
        return "[" + Stream.of(lines)
                .map(line -> "\"" + line + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String jsonText(String... lines) {
        return "\"" + String.join("\\n", lines) + "\"";
    }

    private void assertInvalidPlan(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);
    }
}
