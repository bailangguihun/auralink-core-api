package com.auralink.ops.round81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.ProviderTestFixtures;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.provider.qwen.QwenResponseShapeDiagnostic;
import com.auralink.provider.qwen.QwenResponseValidationCode;
import com.auralink.provider.qwen.QwenResponseValidationDiagnostic;
import com.auralink.provider.qwen.QwenResponseValidationStage;
import com.auralink.provider.qwen.QwenSafeValueType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class Round81ProviderValidationCoordinatorDiagnosticTest {

    private static final String SYNTHETIC_POEM_MARKER = "ROUND81_PRIVATE_POEM_MARKER_8A4";
    private static final String SYNTHETIC_SECRET_MARKER = "ROUND81_PRIVATE_SECRET_MARKER_8A4";
    private static final String FOUR_LINES =
            "[\"私密诗句甲\",\"私密诗句乙\",\"私密诗句丙\",\"私密诗句丁\"]";
    private static final String FOUR_LINE_TEXT =
            "\"私密诗句甲\\n私密诗句乙\\n私密诗句丙\\n私密诗句丁\"";

    @TempDir
    Path temporaryDirectory;

    @Test
    void invalidPoemResponseCarriesExactStructuralReasonIntoFailureEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PaintingPoemResultValidator validator = new PaintingPoemResultValidator(
                mapper,
                ProviderTestFixtures.properties(temporaryDirectory.resolve("staging")));
        String rawProviderContent = "{\"schemaVersion\":1,\"title\":\""
                + SYNTHETIC_POEM_MARKER
                + "\",\"lines\":[\"私密诗句甲\",\"私密诗句乙\",\"私密诗句丙\",\"私密诗句丁\"],"
                + "\"text\":\"私密诗句甲\\n私密诗句乙\\n私密诗句丙\\n私密诗句丁\"}";

        ProviderExecutionException failure = assertThrows(
                ProviderExecutionException.class,
                () -> validator.validate(rawProviderContent));
        assertThat(failure.category()).isEqualTo(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);

        Round81ProviderCallLedger ledger = new Round81ProviderCallLedger();
        ledger.record(URI.create("https://provider.invalid/chat/completions"));
        Round81ProviderValidationCoordinator coordinator = new Round81ProviderValidationCoordinator(
                null, null, null, mapper, ledger, null);
        Method writer = Round81ProviderValidationCoordinator.class.getDeclaredMethod(
                "writeSafeFailure",
                Path.class,
                Round81ValidationOperation.class,
                RuntimeException.class,
                boolean.class);
        writer.setAccessible(true);
        writer.invoke(
                coordinator,
                temporaryDirectory,
                Round81ValidationOperation.PAINTING_TO_POEM,
                failure,
                true);

        JsonNode evidence = mapper.readTree(Files.readAllBytes(temporaryDirectory.resolve("failure.json")));
        assertThat(evidence.path("validationStage").asText()).isEqualTo("POEM_SCHEMA");
        assertThat(evidence.path("validationCode").asText())
                .isEqualTo("QWEN_SCHEMA_VERSION_TYPE_INVALID");
        assertThat(evidence.path("responseShape").path("schemaVersionPresent").asBoolean()).isTrue();
        assertThat(evidence.path("responseShape").path("schemaVersionType").asText()).isEqualTo("NUMBER");

        String serialized = mapper.writeValueAsString(evidence);
        assertThat(serialized)
                .doesNotContain(SYNTHETIC_POEM_MARKER)
                .doesNotContain("私密诗句甲")
                .doesNotContain(rawProviderContent);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureEvidenceCases")
    void writesOnlySafeFailureEvidenceForRepresentativePoemRejections(
            String name,
            String rawProviderContent,
            QwenResponseValidationStage expectedStage,
            QwenResponseValidationCode expectedCode) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PaintingPoemResultValidator validator = new PaintingPoemResultValidator(
                mapper,
                ProviderTestFixtures.properties(temporaryDirectory.resolve(name + "-staging")));
        ProviderExecutionException failure = assertThrows(
                ProviderExecutionException.class,
                () -> validator.validate(rawProviderContent));
        QwenResponseValidationDiagnostic diagnostic =
                (QwenResponseValidationDiagnostic) failure.safeDiagnostic();
        assertThat(diagnostic.validationStage()).isEqualTo(expectedStage);
        assertThat(diagnostic.validationCode()).isEqualTo(expectedCode);

        Path runDirectory = Files.createDirectory(temporaryDirectory.resolve(name));
        writeFailure(mapper, runDirectory, failure);

        Path evidencePath = runDirectory.resolve("failure.json");
        JsonNode evidence = mapper.readTree(Files.readAllBytes(evidencePath));
        assertCommonFailureEvidence(evidence, expectedStage, expectedCode);
        assertThat(evidence.path("responseShape").isObject()).isTrue();
        assertPrivateMode(evidencePath);
        assertNoLeakage(mapper.writeValueAsString(evidence), rawProviderContent);
    }

    @Test
    void writesSafeMalformedEnvelopeDiagnosticWithoutInventingContentFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        QwenResponseValidationDiagnostic diagnostic = new QwenResponseValidationDiagnostic(
                QwenResponseValidationStage.CHOICES,
                QwenResponseValidationCode.QWEN_CHOICES_MISSING,
                QwenResponseShapeDiagnostic.builder()
                        .providerEnvelopePresent(true)
                        .choicesPresent(false)
                        .build());
        ProviderExecutionException failure = ProviderExecutionException.fromSafeDiagnostic(
                ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                "Qwen response failed strict validation",
                diagnostic);
        Path runDirectory = Files.createDirectory(temporaryDirectory.resolve("malformed-envelope"));

        writeFailure(mapper, runDirectory, failure);

        JsonNode evidence = mapper.readTree(Files.readAllBytes(runDirectory.resolve("failure.json")));
        assertCommonFailureEvidence(
                evidence,
                QwenResponseValidationStage.CHOICES,
                QwenResponseValidationCode.QWEN_CHOICES_MISSING);
        assertThat(evidence.path("responseShape").path("providerEnvelopePresent").asBoolean()).isTrue();
        assertThat(evidence.path("responseShape").path("choicesPresent").asBoolean()).isFalse();
        assertThat(evidence.path("responseShape").has("contentLength")).isFalse();
        assertNoLeakage(mapper.writeValueAsString(evidence), "PRIVATE_RAW_ENVELOPE_BODY");
    }

    @Test
    void writesOnlySafeFactsForMessageReasoningRejections() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path nonblankRun = Files.createDirectory(temporaryDirectory.resolve("nonblank-reasoning"));
        ProviderExecutionException nonblankFailure = ProviderExecutionException.fromSafeDiagnostic(
                ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                "Qwen response failed strict validation",
                new QwenResponseValidationDiagnostic(
                        QwenResponseValidationStage.MESSAGE,
                        QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER,
                        QwenResponseShapeDiagnostic.builder()
                                .providerEnvelopePresent(true)
                                .choicesPresent(true)
                                .choiceCount(1)
                                .messagePresent(true)
                                .reasoningContentPresent(true)
                                .reasoningContentType(QwenSafeValueType.STRING)
                                .reasoningContentNonblank(true)
                                .hasReasoningMarker(true)
                                .build()));

        writeFailure(mapper, nonblankRun, nonblankFailure);

        JsonNode nonblankEvidence = mapper.readTree(Files.readAllBytes(nonblankRun.resolve("failure.json")));
        assertCommonFailureEvidence(
                nonblankEvidence,
                QwenResponseValidationStage.MESSAGE,
                QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER);
        assertThat(nonblankEvidence.path("responseShape").path("reasoningContentPresent").asBoolean())
                .isTrue();
        assertThat(nonblankEvidence.path("responseShape").path("reasoningContentType").asText())
                .isEqualTo("STRING");
        assertThat(nonblankEvidence.path("responseShape").path("reasoningContentNonblank").asBoolean())
                .isTrue();
        assertNoLeakage(mapper.writeValueAsString(nonblankEvidence), "ROUND81_PRIVATE_REASONING_MARKER");

        Path objectRun = Files.createDirectory(temporaryDirectory.resolve("object-reasoning"));
        ProviderExecutionException objectFailure = ProviderExecutionException.fromSafeDiagnostic(
                ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                "Qwen response failed strict validation",
                new QwenResponseValidationDiagnostic(
                        QwenResponseValidationStage.MESSAGE,
                        QwenResponseValidationCode.QWEN_REASONING_CONTENT_TYPE_INVALID,
                        QwenResponseShapeDiagnostic.builder()
                                .providerEnvelopePresent(true)
                                .choicesPresent(true)
                                .choiceCount(1)
                                .messagePresent(true)
                                .reasoningContentPresent(true)
                                .reasoningContentType(QwenSafeValueType.OBJECT)
                                .build()));

        writeFailure(mapper, objectRun, objectFailure);

        JsonNode objectEvidence = mapper.readTree(Files.readAllBytes(objectRun.resolve("failure.json")));
        assertCommonFailureEvidence(
                objectEvidence,
                QwenResponseValidationStage.MESSAGE,
                QwenResponseValidationCode.QWEN_REASONING_CONTENT_TYPE_INVALID);
        assertThat(objectEvidence.path("responseShape").path("reasoningContentType").asText())
                .isEqualTo("OBJECT");
        assertThat(objectEvidence.path("responseShape").path("reasoningContentNonblank").isMissingNode())
                .isTrue();
        assertNoLeakage(mapper.writeValueAsString(objectEvidence), "ROUND81_PRIVATE_REASONING_OBJECT");

        Path arrayRun = Files.createDirectory(temporaryDirectory.resolve("array-reasoning"));
        ProviderExecutionException arrayFailure = ProviderExecutionException.fromSafeDiagnostic(
                ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                "Qwen response failed strict validation",
                new QwenResponseValidationDiagnostic(
                        QwenResponseValidationStage.MESSAGE,
                        QwenResponseValidationCode.QWEN_REASONING_CONTENT_TYPE_INVALID,
                        QwenResponseShapeDiagnostic.builder()
                                .providerEnvelopePresent(true)
                                .choicesPresent(true)
                                .choiceCount(1)
                                .messagePresent(true)
                                .reasoningContentPresent(true)
                                .reasoningContentType(QwenSafeValueType.ARRAY)
                                .build()));

        writeFailure(mapper, arrayRun, arrayFailure);

        JsonNode arrayEvidence = mapper.readTree(Files.readAllBytes(arrayRun.resolve("failure.json")));
        assertCommonFailureEvidence(
                arrayEvidence,
                QwenResponseValidationStage.MESSAGE,
                QwenResponseValidationCode.QWEN_REASONING_CONTENT_TYPE_INVALID);
        assertThat(arrayEvidence.path("responseShape").path("reasoningContentType").asText())
                .isEqualTo("ARRAY");
        assertThat(arrayEvidence.path("responseShape").path("reasoningContentNonblank").isMissingNode())
                .isTrue();
        assertNoLeakage(mapper.writeValueAsString(arrayEvidence), "ROUND81_PRIVATE_REASONING_ARRAY");
    }

    private void writeFailure(
            ObjectMapper mapper,
            Path runDirectory,
            ProviderExecutionException failure) throws Exception {
        Round81ProviderCallLedger ledger = new Round81ProviderCallLedger();
        ledger.record(URI.create("https://provider.invalid/chat/completions"));
        Round81ProviderValidationCoordinator coordinator = new Round81ProviderValidationCoordinator(
                null, null, null, mapper, ledger, null);
        Method writer = Round81ProviderValidationCoordinator.class.getDeclaredMethod(
                "writeSafeFailure",
                Path.class,
                Round81ValidationOperation.class,
                RuntimeException.class,
                boolean.class);
        writer.setAccessible(true);
        writer.invoke(
                coordinator,
                runDirectory,
                Round81ValidationOperation.PAINTING_TO_POEM,
                failure,
                true);
    }

    private void assertCommonFailureEvidence(
            JsonNode evidence,
            QwenResponseValidationStage expectedStage,
            QwenResponseValidationCode expectedCode) {
        assertThat(evidence.path("status").asText()).isEqualTo("FAILED");
        assertThat(evidence.path("safeErrorCategory").asText())
                .isEqualTo("PROVIDER_INVALID_RESPONSE");
        assertThat(evidence.path("operation").asText()).isEqualTo("PAINTING_TO_POEM");
        assertThat(evidence.path("providerCode").asText()).isEqualTo("qwen3-vl-plus");
        assertThat(evidence.path("providerFamily").asText()).isEqualTo("QWEN");
        assertThat(evidence.path("localCallCount").asInt()).isEqualTo(1);
        assertThat(evidence.path("calls").path("qwen").asInt()).isEqualTo(1);
        assertThat(evidence.path("calls").path("seedream").asInt()).isZero();
        assertThat(evidence.path("calls").path("vmm").asInt()).isZero();
        assertThat(evidence.path("retryHandlerInvoked").asBoolean()).isFalse();
        assertThat(evidence.path("validationStage").asText()).isEqualTo(expectedStage.name());
        assertThat(evidence.path("validationCode").asText()).isEqualTo(expectedCode.name());
        assertThat(evidence.path("cleanupComplete").asBoolean()).isTrue();
        assertThat(evidence.path("stagingEmpty").asBoolean()).isTrue();
        assertThat(evidence.path("providerArtifactClosed").asBoolean()).isTrue();
    }

    private void assertNoLeakage(String serialized, String rawProviderContent) {
        assertThat(serialized)
                .doesNotContain(
                        SYNTHETIC_POEM_MARKER,
                        SYNTHETIC_SECRET_MARKER,
                        "私密诗句甲",
                        rawProviderContent,
                        "PRIVATE_RAW_ENVELOPE_BODY",
                        "PRIVATE_PROMPT_MARKER",
                        "PRIVATE_BASE64_MARKER",
                        "PRIVATE_API_KEY_MARKER",
                        "PRIVATE_MODEL_VALUE",
                        "Authorization",
                        "Bearer",
                        "data:image",
                        "http://",
                        "https://",
                        "ProviderExecutionException",
                        "stackTrace",
                        temporaryDirectory.toString())
                .doesNotContain("\"prompt\"", "\"model\"", "\"baseUrl\"");
    }

    private void assertPrivateMode(Path evidencePath) throws Exception {
        try {
            assertThat(Files.getPosixFilePermissions(evidencePath)).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Production validation runs are POSIX; portable filesystems still test containment.
        }
    }

    private static Stream<Arguments> failureEvidenceCases() {
        String base = poem("\"1\"", FOUR_LINES, FOUR_LINE_TEXT, null);
        String fiveLines =
                "[\"私密诗句甲\",\"私密诗句乙\",\"私密诗句丙\",\"私密诗句丁\",\"私密诗句戊\"]";
        String fiveText = "\"私密诗句甲\\n私密诗句乙\\n私密诗句丙\\n私密诗句丁\\n私密诗句戊\"";
        return Stream.of(
                Arguments.of(
                        "numeric-schema",
                        poem("1", FOUR_LINES, FOUR_LINE_TEXT, null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_SCHEMA_VERSION_TYPE_INVALID),
                Arguments.of(
                        "five-lines",
                        poem("\"1\"", fiveLines, fiveText, null),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_LINES_COUNT_INVALID),
                Arguments.of(
                        "markdown-fence",
                        "```json\n" + base + "\n```",
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_MARKDOWN_FENCE),
                Arguments.of(
                        "unknown-field",
                        poem("\"1\"", FOUR_LINES, FOUR_LINE_TEXT,
                                "\"extra\":\"" + SYNTHETIC_SECRET_MARKER + "\""),
                        QwenResponseValidationStage.JSON_STRUCTURE,
                        QwenResponseValidationCode.QWEN_JSON_UNKNOWN_FIELDS),
                Arguments.of(
                        "text-mismatch",
                        poem("\"1\"", FOUR_LINES, "\"私密正文不匹配\"", null),
                        QwenResponseValidationStage.POEM_SEMANTICS,
                        QwenResponseValidationCode.QWEN_TEXT_MISMATCH));
    }

    private static String poem(
            String schema,
            String lines,
            String text,
            String extra) {
        StringBuilder value = new StringBuilder("{\"schemaVersion\":")
                .append(schema)
                .append(",\"title\":\"私密题名")
                .append(SYNTHETIC_POEM_MARKER)
                .append("\",\"lines\":")
                .append(lines)
                .append(",\"text\":")
                .append(text);
        if (extra != null) {
            value.append(',').append(extra);
        }
        return value.append('}').toString();
    }
}
