package com.auralink.provider.qwen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.creation.provider.PaintingMetadataContext;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderImageInput;
import com.auralink.creation.provider.ProviderTextOutput;
import com.auralink.provider.LocalProviderHttpFixture;
import com.auralink.provider.ProviderBulkheads;
import com.auralink.provider.ProviderTestFixtures;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.provider.http.ProviderHttpExecutor;
import com.auralink.provider.validation.ProviderDataUrlEncoder;
import com.auralink.provider.validation.ProviderInputValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class QwenPaintingToPoemProviderAdapterTest {

    @TempDir
    Path temporaryDirectory;

    private ObjectMapper mapper;
    private CreationProviderProperties creation;
    private ProviderProperties providers;
    private ProviderArtifactStagingService staging;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        creation = ProviderTestFixtures.properties(temporaryDirectory.resolve("staging"));
        providers = new ProviderProperties();
        providers.getQwen().setApiKey("test-qwen-secret");
        providers.getQwen().setModel("qwen3-vl-plus");
        staging = ProviderTestFixtures.staging(creation);
    }

    @Test
    void sendsValidatedImageAndOnlySafeOptionalPaintingMetadata() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/chat/completions")) {
            fixture.respondJson(200, envelope(validPoem()));
            QwenPaintingToPoemProviderAdapter adapter = adapter(fixture);
            ProviderArtifact source = inputPainting();
            String paintingId = UUID.randomUUID().toString();
            PaintingMetadataContext metadata = new PaintingMetadataContext(
                    paintingId, "秋山图", "佚名", "宋", "山水", "秋山孤亭",
                    "北方山水", "淡墨", "高远构图", "清寂", "远山含秋意", "疏朗清远");

            var result = adapter.execute(new ProviderExecutionRequest(
                    "qwen-poem-adapter",
                    WorkflowOperation.PAINTING_TO_POEM,
                    "qwen3-vl-plus",
                    new ProviderImageInput(source, WorkflowModality.PAINTING, metadata)));

            ProviderTextOutput output = (ProviderTextOutput) result.output();
            assertThat(result.outputModality()).isEqualTo(WorkflowModality.POEM);
            assertThat(output.lines()).hasSize(4);
            assertThat(output.text()).isEqualTo(String.join("\n", output.lines()));
            JsonNode request = mapper.readTree(fixture.lastRequest().body());
            JsonNode content = request.path("messages").get(1).path("content");
            assertThat(content.get(0).path("image_url").path("url").asText())
                    .startsWith("data:image/png;base64,");
            assertThat(content.get(1).path("text").asText())
                    .contains(paintingId, "秋山图", "佚名", "【元数据开始】", "【元数据结束】")
                    .doesNotContain("storageKey", "userId", temporaryDirectory.toString());
            assertThat(request.path("messages").get(0).path("content").asText())
                    .contains("只根据提供的图像", "证据不足时使用克制意象");
            assertThat(request.path("enable_thinking").asBoolean()).isFalse();
            assertThat(request.has("tools")).isFalse();
            source.close();
        }
    }

    @Test
    void invalidPoemResultFailsSafelyAfterExactlyOneQwenCall() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/chat/completions")) {
            fixture.respondJson(200, envelope("{\"schemaVersion\":\"1\",\"title\":null,"
                    + "\"lines\":[\"一句\",\"二句\"],\"text\":\"一句\\n二句\"}"));
            ProviderArtifact source = inputPainting();

            ProviderExecutionException failure = assertThrows(
                    ProviderExecutionException.class,
                    () -> adapter(fixture).execute(new ProviderExecutionRequest(
                            "qwen-poem-invalid",
                            WorkflowOperation.PAINTING_TO_POEM,
                            "qwen3-vl-plus",
                            new ProviderImageInput(source, WorkflowModality.PAINTING, null))));

            assertThat(failure.category()).isEqualTo(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);
            QwenResponseValidationDiagnostic diagnostic =
                    (QwenResponseValidationDiagnostic) failure.safeDiagnostic();
            assertThat(diagnostic.validationStage()).isEqualTo(QwenResponseValidationStage.POEM_SCHEMA);
            assertThat(diagnostic.validationCode())
                    .isEqualTo(QwenResponseValidationCode.QWEN_LINES_COUNT_INVALID);
            assertThat(diagnostic.responseShape().providerEnvelopePresent()).isTrue();
            assertThat(diagnostic.responseShape().choicesPresent()).isTrue();
            assertThat(diagnostic.responseShape().choiceCount()).isEqualTo(1);
            assertThat(diagnostic.responseShape().messagePresent()).isTrue();
            assertThat(diagnostic.responseShape().contentPresent()).isTrue();
            assertThat(diagnostic.responseShape().lineCount()).isEqualTo(2);
            assertThat(failure.getMessage() + diagnostic).doesNotContain("一句", "二句");
            assertThat(fixture.requestCount()).isEqualTo(1);
            source.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optionalReasoningContentContinuationCases")
    void optionalReasoningContentDoesNotBypassContentOrPoemValidation(
            String name,
            String reasoningContentJson,
            String content,
            QwenResponseValidationStage expectedStage,
            QwenResponseValidationCode expectedCode) throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/chat/completions")) {
            fixture.respondJson(200, envelope(content, reasoningContentJson));
            ProviderArtifact source = inputPainting();
            try {
                ProviderExecutionRequest request = new ProviderExecutionRequest(
                        "qwen-poem-optional-" + name.replace(' ', '-'),
                        WorkflowOperation.PAINTING_TO_POEM,
                        "qwen3-vl-plus",
                        new ProviderImageInput(source, WorkflowModality.PAINTING, null));
                if (expectedCode == null) {
                    ProviderTextOutput output = (ProviderTextOutput) adapter(fixture).execute(request).output();
                    assertThat(output.lines()).hasSize(4);
                } else {
                    ProviderExecutionException failure = assertThrows(
                            ProviderExecutionException.class,
                            () -> adapter(fixture).execute(request));
                    QwenResponseValidationDiagnostic diagnostic =
                            (QwenResponseValidationDiagnostic) failure.safeDiagnostic();
                    assertThat(failure.category()).isEqualTo(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);
                    assertThat(diagnostic.validationStage()).isEqualTo(expectedStage);
                    assertThat(diagnostic.validationCode()).isEqualTo(expectedCode);
                }
                assertThat(fixture.requestCount()).isEqualTo(1);
            } finally {
                source.close();
            }
        }
    }

    @Test
    void rejectsUnsafeMetadataBeforeQwenCall() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/chat/completions")) {
            fixture.respondJson(200, envelope(validPoem()));
            ProviderArtifact source = inputPainting();
            PaintingMetadataContext metadata = new PaintingMetadataContext(
                    null, "秋山\u0000图", null, null, null, null,
                    null, null, null, null, null, null);

            assertThatThrownBy(() -> adapter(fixture).execute(new ProviderExecutionRequest(
                    "qwen-poem-metadata",
                    WorkflowOperation.PAINTING_TO_POEM,
                    "qwen3-vl-plus",
                    new ProviderImageInput(source, WorkflowModality.PAINTING, metadata))))
                    .isInstanceOf(ProviderExecutionException.class)
                    .extracting(exception -> ((ProviderExecutionException) exception).category())
                    .isEqualTo(ProviderErrorCategory.PROVIDER_REJECTED);
            assertThat(fixture.requestCount()).isZero();
            source.close();
        }
    }

    @Test
    void rejectsNonUuidPaintingIdentifierBeforeQwenCall() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/chat/completions")) {
            fixture.respondJson(200, envelope(validPoem()));
            ProviderArtifact source = inputPainting();
            PaintingMetadataContext metadata = new PaintingMetadataContext(
                    "internal-id-42", "秋山图", null, null, null, null,
                    null, null, null, null, null, null);

            assertThatThrownBy(() -> adapter(fixture).execute(new ProviderExecutionRequest(
                    "qwen-poem-id",
                    WorkflowOperation.PAINTING_TO_POEM,
                    "qwen3-vl-plus",
                    new ProviderImageInput(source, WorkflowModality.PAINTING, metadata))))
                    .isInstanceOf(ProviderExecutionException.class)
                    .extracting(exception -> ((ProviderExecutionException) exception).category())
                    .isEqualTo(ProviderErrorCategory.PROVIDER_REJECTED);
            assertThat(fixture.requestCount()).isZero();
            source.close();
        }
    }

    private QwenPaintingToPoemProviderAdapter adapter(LocalProviderHttpFixture fixture) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2_000);
        requestFactory.setReadTimeout(2_000);
        QwenCreationHttpClient client = new QwenCreationHttpClient(
                RestClient.builder().requestFactory(requestFactory).build(),
                new ProviderHttpExecutor(mapper),
                mapper,
                creation,
                providers,
                () -> fixture.uri("/chat/completions"),
                new ProviderBulkheads(creation));
        return new QwenPaintingToPoemProviderAdapter(
                new ProviderInputValidator(creation),
                new ProviderDataUrlEncoder(),
                creation,
                client,
                mock(QwenEndpointPolicy.class),
                new PaintingToPoemPromptBuilder(),
                new PaintingPoemResultValidator(mapper, creation));
    }

    private ProviderArtifact inputPainting() {
        return staging.stageInputImage(
                new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png");
    }

    private String envelope(String content) throws Exception {
        return envelope(content, null);
    }

    private String envelope(String content, String reasoningContentJson) throws Exception {
        var root = mapper.createObjectNode();
        var message = root.putArray("choices").addObject().putObject("message");
        message.put("content", content);
        if (reasoningContentJson != null) {
            message.set("reasoning_content", mapper.readTree(reasoningContentJson));
        }
        return mapper.writeValueAsString(root);
    }

    private static Stream<Arguments> optionalReasoningContentContinuationCases() {
        return Stream.of(
                Arguments.of(
                        "absent reasoning with content reasoning marker",
                        null,
                        validPoem().replace("秋山淡墨入寒云", "思考过程不应出现"),
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER),
                Arguments.of(
                        "empty reasoning with markdown content",
                        "\"\"",
                        "```json\\n" + validPoem() + "\\n```",
                        QwenResponseValidationStage.CONTENT,
                        QwenResponseValidationCode.QWEN_CONTENT_MARKDOWN_FENCE),
                Arguments.of(
                        "null reasoning with five-line poem",
                        "null",
                        fiveLinePoem(),
                        QwenResponseValidationStage.POEM_SCHEMA,
                        QwenResponseValidationCode.QWEN_LINES_COUNT_INVALID),
                Arguments.of(
                        "empty reasoning with valid poem",
                        "\"\"",
                        validPoem(),
                        null,
                        null));
    }

    private static String validPoem() {
        return "{\"schemaVersion\":\"1\",\"title\":null,"
                + "\"lines\":[\"秋山淡墨入寒云\",\"孤亭一角近松门\",\"流水无声穿石过\",\"疏钟遥送月黄昏\"],"
                + "\"text\":\"秋山淡墨入寒云\\n孤亭一角近松门\\n流水无声穿石过\\n疏钟遥送月黄昏\"}";
    }

    private static String fiveLinePoem() {
        return "{\"schemaVersion\":\"1\",\"title\":null,"
                + "\"lines\":[\"秋山淡墨入寒云\",\"孤亭一角近松门\",\"流水无声穿石过\",\"疏钟遥送月黄昏\",\"第五句含烟\"],"
                + "\"text\":\"秋山淡墨入寒云\\n孤亭一角近松门\\n流水无声穿石过\\n疏钟遥送月黄昏\\n第五句含烟\"}";
    }
}
