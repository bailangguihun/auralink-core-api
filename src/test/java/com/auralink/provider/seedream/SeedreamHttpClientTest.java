package com.auralink.provider.seedream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.LocalProviderHttpFixture;
import com.auralink.provider.ProviderTestFixtures;
import com.auralink.provider.http.ProviderHttpExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class SeedreamHttpClientTest {

    @TempDir
    java.nio.file.Path temporaryDirectory;

    private ObjectMapper mapper;
    private CreationProviderProperties creation;
    private ProviderProperties providers;
    private final List<CloseableHttpClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        creation = ProviderTestFixtures.properties(temporaryDirectory.resolve("staging"));
        providers = new ProviderProperties();
        providers.getSeedream().setApiKey("test-seedream-secret");
        providers.getSeedream().setModel("seedream-internal-model");
    }

    @AfterEach
    void closeClients() throws Exception {
        for (CloseableHttpClient client : clients) {
            client.close();
        }
    }

    @Test
    void sendsExactTextGenerationContractToFixedEndpoint() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/images/generations")) {
            fixture.respondJson(200, "{\"data\":[{\"url\":\"https://result.example/image.png\"}]}");
            SeedreamHttpClient client = client(fixture.uri("/images/generations"), 2_000);

            String url = client.generate("round8-request", "国画山水提示", null);

            assertThat(url).isEqualTo("https://result.example/image.png");
            assertThat(fixture.requestCount()).isEqualTo(1);
            var captured = fixture.lastRequest();
            assertThat(captured.method()).isEqualTo("POST");
            assertThat(captured.uri().getPath()).isEqualTo("/images/generations");
            assertThat(captured.firstHeader("Authorization"))
                    .startsWith("Bearer ");
            assertThat(captured.firstHeader("X-Auralink-Request-Id"))
                    .isEqualTo("round8-request");

            JsonNode body = mapper.readTree(captured.body());
            List<String> keys = new ArrayList<>();
            body.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactly(
                    "model", "prompt", "response_format", "size", "stream", "watermark");
            assertThat(body.size()).isEqualTo(6);
            assertThat(body.get("model").textValue()).isEqualTo("seedream-internal-model");
            assertThat(body.get("prompt").textValue()).isEqualTo("国画山水提示");
            assertThat(body.get("response_format").textValue()).isEqualTo("url");
            assertThat(body.get("size").textValue()).isEqualTo("2K");
            assertThat(body.get("stream").booleanValue()).isFalse();
            assertThat(body.get("watermark").booleanValue()).isTrue();
            assertThat(body.has("image")).isFalse();
            assertThat(body.has("output_format")).isFalse();
            assertThat(body.has("sequential_image_generation")).isFalse();
            assertThat(body.has("sequential_image_generation_options")).isFalse();
            assertThat(body.has("tools")).isFalse();
            assertThat(body.has("web_search")).isFalse();
            assertThat(body.has("callback")).isFalse();
            assertThat(body.has("n")).isFalse();
        }
    }

    @Test
    void sendsExactImageTransformationContractToFixedEndpoint() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/images/generations")) {
            fixture.respondJson(200, "{\"data\":[{\"url\":\"https://result.example/image.jpg\"}]}");
            SeedreamHttpClient client = client(fixture.uri("/images/generations"), 2_000);
            String dataUrl = "data:image/png;base64,iVBORw0KGgo=";
            String prompt = new ImageToPaintingPromptBuilder().build();
            creation.setSeedreamOutputFormat("jpeg");
            creation.setSeedreamWatermark(false);

            client.generate("round8-image", prompt, dataUrl);

            var captured = fixture.lastRequest();
            assertThat(captured.method()).isEqualTo("POST");
            assertThat(captured.uri().getPath()).isEqualTo("/images/generations");
            assertThat(captured.firstHeader("Authorization")).startsWith("Bearer ");

            JsonNode body = mapper.readTree(captured.body());
            List<String> keys = new ArrayList<>();
            body.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactly(
                    "model", "prompt", "image", "response_format", "size", "stream", "watermark");
            assertThat(body.size()).isEqualTo(7);
            assertThat(body.get("model").textValue()).isEqualTo("seedream-internal-model");
            assertThat(body.get("image").textValue()).isEqualTo(dataUrl);
            assertThat(body.get("image").isTextual()).isTrue();
            assertThat(body.get("image").isArray()).isFalse();
            assertThat(body.get("prompt").textValue().isBlank()).isFalse();
            assertThat(body.get("prompt").textValue().length())
                    .isLessThanOrEqualTo(creation.getMaxTextChars());
            assertThat(Stream.of(
                    "中国画（国画）", "保持主要主体身份", "主要主体数量", "核心构图", "空间关系",
                    "水墨", "笔触", "设色", "不添加无关对象", "不添加文字", "徽标", "界面边框", "不执行")
                    .allMatch(body.get("prompt").textValue()::contains)).isTrue();
            assertThat(body.get("response_format").textValue()).isEqualTo("url");
            assertThat(body.get("size").textValue()).isEqualTo("2K");
            assertThat(body.get("stream").booleanValue()).isFalse();
            assertThat(body.get("watermark").booleanValue()).isFalse();
            assertThat(body.has("sequential_image_generation")).isFalse();
            assertThat(body.has("sequential_image_generation_options")).isFalse();
            assertThat(body.has("output_format")).isFalse();
            assertThat(body.has("images")).isFalse();
            assertThat(body.has("n")).isFalse();
            assertThat(body.has("tools")).isFalse();
            assertThat(body.has("callback")).isFalse();
            assertThat(body.has("webhook")).isFalse();
            assertThat(body.has("unknown")).isFalse();
            assertThat(dataUrl.chars().noneMatch(Character::isWhitespace)).isTrue();
            assertThat(captured.bodyText().split("data:image/", -1).length - 1).isEqualTo(1);
            assertThat(captured.bodyText()).doesNotContain(
                    "test-seedream-secret", "file://", "storage-key", "internal-asset-url",
                    "ownerUserId", "owner_user_id");
        }
    }

    @ParameterizedTest
    @MethodSource("invalidSuccessfulBodies")
    void rejectsMalformedMissingMultipleOrAmbiguousOutputs(String body) throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/images/generations")) {
            fixture.respondJson(200, body);
            SeedreamHttpClient client = client(fixture.uri("/images/generations"), 2_000);

            assertThatThrownBy(() -> client.generate(
                    "round8-invalid", "固定图像转换提示", "data:image/png;base64,iVBORw0KGgo="))
                    .isInstanceOf(ProviderExecutionException.class)
                    .extracting(exception -> ((ProviderExecutionException) exception).category())
                    .isEqualTo(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);
            assertThat(fixture.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @MethodSource("failureStatuses")
    void classifiesHttpFailuresWithoutPaidRetry(
            int status,
            ProviderErrorCategory expected) throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/images/generations")) {
            fixture.respondJson(status, "{\"provider\":\"sensitive body\"}");
            SeedreamHttpClient client = client(fixture.uri("/images/generations"), 2_000);

            assertThatThrownBy(() -> client.generate(
                    "round8-status", "固定图像转换提示", "data:image/jpeg;base64,/9j/2Q=="))
                    .isInstanceOf(ProviderExecutionException.class)
                    .satisfies(exception -> {
                        ProviderExecutionException provider = (ProviderExecutionException) exception;
                        assertThat(provider.category()).isEqualTo(expected);
                        assertThat(provider.getMessage()).doesNotContain("sensitive body");
                    });
            assertThat(fixture.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @MethodSource("safeArkRejections")
    void retainsOnlySafeArkRejectionMetadataWithoutRetry(
            int status,
            ProviderErrorCategory expectedCategory,
            String expectedCode) throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/images/generations")) {
            String rawHeaderRequestId = "raw-ark-request-id-must-not-escape";
            String rawBody = ("""
                    {"error":{"code":"%s","message":"RAW_PROVIDER_MESSAGE_MUST_NOT_ESCAPE",\
                    "request_id":"RAW_BODY_REQUEST_ID_MUST_NOT_ESCAPE"},\
                    "model":"PRIVATE_MODEL_MUST_NOT_ESCAPE",\
                    "prompt":"PRIVATE_PROMPT_MUST_NOT_ESCAPE",\
                    "authorization":"PRIVATE_API_KEY_MUST_NOT_ESCAPE"}
                    """).formatted(expectedCode);
            fixture.respondJson(
                    status,
                    rawBody,
                    Map.of("X-Request-Id", rawHeaderRequestId));
            SeedreamHttpClient client = client(fixture.uri("/images/generations"), 2_000);

            ProviderExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    ProviderExecutionException.class,
                    () -> client.generate(
                            "round8-safe-rejection",
                            "固定图像转换提示",
                            "data:image/png;base64,iVBORw0KGgo="));

            assertThat(failure.category()).isEqualTo(expectedCategory);
            assertThat(failure.providerHttpStatus()).isEqualTo(status);
            assertThat(failure.providerErrorCode()).isEqualTo(expectedCode);
            assertThat(failure.safeRequestId())
                    .matches("sha256:[0-9a-f]{32}")
                    .doesNotContain(rawHeaderRequestId, "RAW_BODY_REQUEST_ID_MUST_NOT_ESCAPE");
            assertThat(failure.getMessage()).doesNotContain(
                    "RAW_PROVIDER_MESSAGE_MUST_NOT_ESCAPE",
                    "PRIVATE_MODEL_MUST_NOT_ESCAPE",
                    "PRIVATE_PROMPT_MUST_NOT_ESCAPE",
                    "PRIVATE_API_KEY_MUST_NOT_ESCAPE",
                    rawHeaderRequestId);
            assertThat(fixture.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void classifiesTimeoutAndDoesNotRetry() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/images/generations")) {
            fixture.respondAfter(
                    200,
                    "{\"data\":[{\"url\":\"https://result.example/image.png\"}]}",
                    500);
            SeedreamHttpClient client = client(fixture.uri("/images/generations"), 50);

            assertThatThrownBy(() -> client.generate(
                    "round8-timeout", "固定图像转换提示", "data:image/png;base64,iVBORw0KGgo="))
                    .isInstanceOf(ProviderExecutionException.class)
                    .extracting(exception -> ((ProviderExecutionException) exception).category())
                    .isEqualTo(ProviderErrorCategory.PROVIDER_TIMEOUT);
            assertThat(fixture.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void rejectsOversizedJsonResponseBeforeParsing() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/images/generations")) {
            fixture.respondBytes(200, "application/json", new byte[1024 * 1024 + 1]);
            SeedreamHttpClient client = client(fixture.uri("/images/generations"), 2_000);

            assertThatThrownBy(() -> client.generate(
                    "round8-large", "固定图像转换提示", "data:image/png;base64,iVBORw0KGgo="))
                    .isInstanceOf(ProviderExecutionException.class)
                    .extracting(exception -> ((ProviderExecutionException) exception).category())
                    .isEqualTo(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);
            assertThat(fixture.requestCount()).isEqualTo(1);
        }
    }

    private SeedreamHttpClient client(URI endpoint, int timeoutMillis) {
        return new SeedreamHttpClient(
                ProviderTestFixtures.restClient(timeoutMillis, clients),
                new ProviderHttpExecutor(mapper),
                mapper,
                creation,
                providers,
                () -> endpoint);
    }

    private static Stream<String> invalidSuccessfulBodies() {
        return Stream.of(
                "not-json",
                "{}",
                "{\"data\":[]}",
                "{\"data\":[{},{}]}",
                "{\"data\":[{}]}",
                "{\"data\":[{\"url\":\"\"}]}",
                "{\"data\":[{\"url\":\"https://result.example/a.png\",\"b64_json\":\"x\"}]}",
                "{\"data\":[{\"url\":\"file:///tmp/a.png\"}]}",
                "{\"data\":[],\"data\":[{\"url\":\"https://result.example/a.png\"}]}",
                "{\"data\":[]} {\"data\":[]}");
    }

    private static Stream<Arguments> failureStatuses() {
        return Stream.of(
                Arguments.of(400, ProviderErrorCategory.PROVIDER_REJECTED),
                Arguments.of(401, ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID),
                Arguments.of(403, ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID),
                Arguments.of(429, ProviderErrorCategory.PROVIDER_RATE_LIMITED),
                Arguments.of(500, ProviderErrorCategory.PROVIDER_UNAVAILABLE),
                Arguments.of(503, ProviderErrorCategory.PROVIDER_UNAVAILABLE));
    }

    private static Stream<Arguments> safeArkRejections() {
        return Stream.of(
                Arguments.of(400, ProviderErrorCategory.PROVIDER_REJECTED, "InvalidParameter"),
                Arguments.of(401, ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID, "AuthenticationError"),
                Arguments.of(403, ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID, "AccessDenied"));
    }
}
