package com.auralink.guide.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.GuideProperties;
import com.auralink.guide.context.PaintingGuideContext;
import com.auralink.guide.knowledge.KnowledgeItem;
import com.auralink.guide.model.GuideResultCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class PythonGuideProviderTest {

    private static final String TOKEN = "test-only-internal-token";
    private static final KnowledgeItem KNOWLEDGE = new KnowledgeItem(
            "poetry:1", "POETRY", "山居秋暝", "明月松间照，清泉石上流。");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestToken = new AtomicReference<>();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private GuideProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        serverExecutor = Executors.newCachedThreadPool();
        properties = new GuideProperties();
        properties.setSchemaVersion("1");
        properties.setInternalToken(TOKEN);
        createServer();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void sendsExactAuthenticatedStructuredContractAndValidatesSuccess() throws Exception {
        installHandler(attempt -> successResponse(REQUEST_ID));
        server.start();

        GuideGenerationResult generated = provider(Duration.ofSeconds(2)).generate(REQUEST_ID, context());

        assertThat(generated.requestId()).isEqualTo(REQUEST_ID);
        assertThat(generated.result().summary()).isEqualTo("有依据的导览摘要");
        assertThat(requests).hasValue(1);
        assertThat(requestToken).hasValue(TOKEN);
        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.fieldNames()).toIterable().containsExactly(
                "requestId", "schemaVersion", "painting", "knowledge", "options");
        assertThat(sent.path("requestId").asText()).isEqualTo(REQUEST_ID);
        assertThat(sent.path("schemaVersion").asText()).isEqualTo("1");
        assertThat(sent.path("painting").fieldNames()).toIterable().containsExactly(
                "paintingId", "basic", "artist", "art", "officialAnnotations");
        assertThat(sent.path("painting").path("paintingId").asText()).isEqualTo(PAINTING_ID);
        assertThat(sent.path("painting").has("knowledge")).isFalse();
        assertThat(sent.path("knowledge").get(0).path("sourceId").asText()).isEqualTo("poetry:1");
        assertThat(sent.path("options").path("language").asText()).isEqualTo("zh-CN");
        assertThat(sent.path("options").path("detailLevel").asText()).isEqualTo("STANDARD");
        assertThat(requestBody.get()).doesNotContain(TOKEN);
    }

    @Test
    void classifiesTransientHttpFailureWithoutMultiplyingProviderAttempts() {
        installHandler(attempt -> new StubResponse(503, "{\"error\":\"temporary\"}"));
        server.start();

        assertThatThrownBy(() -> provider(Duration.ofSeconds(2)).generate(REQUEST_ID, context()))
                .isInstanceOfSatisfying(GuideProviderException.class, exception -> {
                    assertThat(exception.getFailure()).isEqualTo(
                            GuideProviderException.Failure.UNAVAILABLE);
                    assertThat(exception.isRetryable()).isFalse();
                });
        assertThat(requests).hasValue(1);
    }

    @Test
    void doesNotRetryAuthenticationRejection() {
        installHandler(attempt -> new StubResponse(401, "{\"status\":\"FAILED\"}"));
        server.start();

        assertThatThrownBy(() -> provider(Duration.ofSeconds(2)).generate(REQUEST_ID, context()))
                .isInstanceOfSatisfying(GuideProviderException.class, exception -> {
                    assertThat(exception.getFailure()).isEqualTo(
                            GuideProviderException.Failure.CONFIGURATION);
                    assertThat(exception.isRetryable()).isFalse();
                    assertThat(exception.getMessage()).doesNotContain(TOKEN);
                });
        assertThat(requests).hasValue(1);
    }

    @Test
    void returnsSafeTimeoutWithoutMultiplyingProviderAttempts() {
        installHandler(attempt -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return successResponse(REQUEST_ID);
        });
        server.start();

        assertThatThrownBy(() -> provider(Duration.ofMillis(40)).generate(REQUEST_ID, context()))
                .isInstanceOfSatisfying(GuideProviderException.class, exception -> {
                    assertThat(exception.getFailure()).isEqualTo(GuideProviderException.Failure.TIMEOUT);
                    assertThat(exception.isRetryable()).isFalse();
                });
        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsMismatchedRequestIdUnknownEnvelopeAndInvalidGuideJson() {
        assertInvalidResponse(successResponse(UUID.randomUUID().toString()));
        assertInvalidResponse(new StubResponse(200, successResponse(REQUEST_ID).body()
                .replaceFirst("\\{", "{\"provider\":\"forbidden\",")));
        assertInvalidResponse(new StubResponse(200, successResponse(REQUEST_ID).body()
                .replace("[\"观察构图\",\"留意水墨\"]", "[\"重复\",\"重复\"]")));
    }

    @Test
    void rejectsUnsupportedKnowledgeReferenceAndOversizedBody() {
        assertInvalidResponse(new StubResponse(200, successResponse(REQUEST_ID).body()
                .replace("poetry:1", "poetry:unsupported")));
        assertInvalidResponse(new StubResponse(
                200,
                "x".repeat(PythonGuideProvider.MAX_INTERNAL_RESPONSE_BYTES + 1)));
    }

    @Test
    void refusesMissingTokenAndMalformedRequestIdBeforeOpeningConnection() {
        installHandler(attempt -> successResponse(REQUEST_ID));
        server.start();
        properties.setInternalToken(" ");

        assertThatThrownBy(() -> provider(Duration.ofSeconds(1)).generate(REQUEST_ID, context()))
                .isInstanceOfSatisfying(GuideProviderException.class, exception ->
                        assertThat(exception.getFailure()).isEqualTo(
                                GuideProviderException.Failure.CONFIGURATION));
        properties.setInternalToken(TOKEN);
        assertThatThrownBy(() -> provider(Duration.ofSeconds(1)).generate("not-a-uuid", context()))
                .isInstanceOfSatisfying(GuideProviderException.class, exception ->
                        assertThat(exception.getFailure()).isEqualTo(
                                GuideProviderException.Failure.CONFIGURATION));
        assertThat(requests).hasValue(0);
    }

    @Test
    void refusesNonLoopbackServiceUrlWithoutDnsOrConnectionAttempt() {
        installHandler(attempt -> successResponse(REQUEST_ID));
        server.start();
        properties.setServiceUrl("https://guide.example.invalid");

        assertThatThrownBy(() -> provider(Duration.ofSeconds(1)).generate(REQUEST_ID, context()))
                .isInstanceOfSatisfying(GuideProviderException.class, exception -> {
                    assertThat(exception.getFailure()).isEqualTo(
                            GuideProviderException.Failure.CONFIGURATION);
                    assertThat(exception.getMessage()).isEqualTo("Guide service URL is invalid");
                });
        assertThat(requests).hasValue(0);

        properties.setServiceUrl("http://localhost:5003");
        assertThatThrownBy(() -> provider(Duration.ofSeconds(1)).generate(REQUEST_ID, context()))
                .isInstanceOfSatisfying(GuideProviderException.class, exception ->
                        assertThat(exception.getFailure()).isEqualTo(
                                GuideProviderException.Failure.CONFIGURATION));
        assertThat(requests).hasValue(0);
    }

    @Test
    void mapsSafePythonFailureCodesWithoutLeakingPythonMessage() {
        installHandler(attempt -> new StubResponse(504, """
                {"requestId":"%s","status":"FAILED","result":null,
                 "error":{"code":"UPSTREAM_TIMEOUT","message":"sensitive upstream detail"}}
                """.formatted(REQUEST_ID)));
        server.start();

        assertThatThrownBy(() -> provider(Duration.ofSeconds(2)).generate(REQUEST_ID, context()))
                .isInstanceOfSatisfying(GuideProviderException.class, exception -> {
                    assertThat(exception.getFailure()).isEqualTo(GuideProviderException.Failure.TIMEOUT);
                    assertThat(exception.isRetryable()).isFalse();
                    assertThat(exception.getMessage()).doesNotContain("sensitive");
                });
        assertThat(requests).hasValue(1);
    }

    @Test
    void mapsNonSuccessPythonConfigurationEnvelopeBeforeGenericHttpStatus() {
        installHandler(attempt -> new StubResponse(503, """
                {"requestId":"%s","status":"FAILED","result":null,
                 "error":{"code":"PROVIDER_NOT_CONFIGURED","message":"private detail"}}
                """.formatted(REQUEST_ID)));
        server.start();

        assertThatThrownBy(() -> provider(Duration.ofSeconds(2)).generate(REQUEST_ID, context()))
                .isInstanceOfSatisfying(GuideProviderException.class, exception -> {
                    assertThat(exception.getFailure()).isEqualTo(
                            GuideProviderException.Failure.CONFIGURATION);
                    assertThat(exception.getMessage()).doesNotContain("private");
                });
        assertThat(requests).hasValue(1);
    }

    @Test
    void mapsInternalAuthAndServiceFailureEnvelopesByTheirSafeCodes() {
        var cases = List.of(
                new FailureCase(401, "UNAUTHORIZED_INTERNAL_CALL",
                        GuideProviderException.Failure.CONFIGURATION),
                new FailureCase(500, "INTERNAL_SERVICE_ERROR",
                        GuideProviderException.Failure.UNAVAILABLE));
        for (FailureCase failure : cases) {
            if (server == null) {
                try {
                    createServer();
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }
            installHandler(attempt -> new StubResponse(failure.status(), """
                    {"requestId":"%s","status":"FAILED","result":null,
                     "error":{"code":"%s","message":"private detail"}}
                    """.formatted(REQUEST_ID, failure.code())));
            server.start();
            assertThatThrownBy(() -> provider(Duration.ofSeconds(2))
                    .generate(REQUEST_ID, context()))
                    .isInstanceOfSatisfying(GuideProviderException.class, exception ->
                            assertThat(exception.getFailure()).isEqualTo(failure.failure()));
            server.stop(0);
            server = null;
        }
    }

    private void assertInvalidResponse(StubResponse response) {
        if (server == null) {
            try {
                createServer();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to recreate loopback fixture", exception);
            }
        }
        installHandler(attempt -> response);
        server.start();
        try {
            assertThatThrownBy(() -> provider(Duration.ofSeconds(2)).generate(REQUEST_ID, context()))
                    .isInstanceOfSatisfying(GuideProviderException.class, exception -> {
                        assertThat(exception.getFailure())
                                .isEqualTo(GuideProviderException.Failure.INVALID_RESPONSE);
                        assertThat(exception.isRetryable()).isFalse();
                    });
        } finally {
            server.stop(0);
            server = null;
        }
    }

    private PythonGuideProvider provider(Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1_000);
        requestFactory.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        GuideResultCodec codec = new GuideResultCodec(objectMapper);
        return new PythonGuideProvider(restClient, properties, codec, objectMapper);
    }

    private void installHandler(IntFunction<StubResponse> responses) {
        server.createContext("/v1/guide/generate", exchange -> {
            int attempt = requests.incrementAndGet();
            requestToken.set(exchange.getRequestHeaders().getFirst(
                    PythonGuideProvider.INTERNAL_TOKEN_HEADER));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            StubResponse response = responses.apply(attempt);
            write(exchange, response);
        });
    }

    private void createServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(serverExecutor);
        properties.setServiceUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void write(HttpExchange exchange, StubResponse response) throws IOException {
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException ignored) {
            // Timeout tests intentionally close the client side before the fixture responds.
        } finally {
            exchange.close();
        }
    }

    private StubResponse successResponse(String requestId) {
        return new StubResponse(200, """
                {
                  "requestId":"%s",
                  "status":"SUCCESS",
                  "result":{
                    "schemaVersion":"1",
                    "summary":"有依据的导览摘要",
                    "sections":{
                      "artistAndEra":"画家与时代",
                      "subjectAndScene":null,
                      "composition":"构图层次",
                      "brushworkAndInk":null,
                      "colorAndMaterial":null,
                      "artisticConception":null,
                      "culturalMeaning":null,
                      "musicAssociation":null
                    },
                    "highlights":["观察构图","留意水墨"],
                    "knowledgeReferences":[
                      {"sourceId":"poetry:1","sourceType":"POETRY","title":"山居秋暝"}
                    ]
                  },
                  "error":null
                }
                """.formatted(requestId));
    }

    private PaintingGuideContext context() {
        return new PaintingGuideContext(
                PAINTING_ID,
                new PaintingGuideContext.Basic("溪山图", "北宋", "宋", "宋代", "100x50", "博物馆"),
                new PaintingGuideContext.Artist("某画家", null, null, "山水"),
                new PaintingGuideContext.Art(
                        "中国画", "山水", "北方山水", "雄浑", "水墨", "高远", "清远",
                        "皴法", "积墨", "绢本", null, null, "山水意象"),
                new PaintingGuideContext.OfficialAnnotations("官方注释", "清远琴声"),
                List.of(KNOWLEDGE));
    }

    private record StubResponse(int status, String body) {
    }

    private record FailureCase(
            int status,
            String code,
            GuideProviderException.Failure failure) {
    }

    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000000001";
    private static final String PAINTING_ID = "00000000-0000-0000-0000-000000000002";
}
