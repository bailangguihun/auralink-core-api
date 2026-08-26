package com.auralink.provider.vmm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderImageInput;
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

class AuralinkVmmProviderAdapterTest {

    @TempDir
    Path temporaryDirectory;

    private ObjectMapper mapper;
    private CreationProviderProperties creation;
    private ProviderProperties providers;
    private ProviderArtifactStagingService staging;
    private Path outputRoot;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        creation = ProviderTestFixtures.properties(temporaryDirectory.resolve("staging"));
        providers = new ProviderProperties();
        outputRoot = temporaryDirectory.resolve("vmm-output");
        Files.createDirectory(outputRoot);
        providers.getPaintingMusic().setOutputRoot(outputRoot.toString());
        staging = ProviderTestFixtures.staging(creation);
    }

    @Test
    void usesExactActiveContractIgnoresAbsolutePathAndStagesValidWave() throws Exception {
        Files.write(outputRoot.resolve("generated.wav"), ProviderTestFixtures.wave());
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image")) {
            configureBase(fixture);
            fixture.respondJson(200, "{\"success\":true,\"fileName\":\"generated.wav\","
                    + "\"full_path\":\"/outside/secret/generated.wav\",\"message\":\"ok\"}");
            AuralinkVmmProviderAdapter adapter = adapter(fixture, 2_000);

            ProviderArtifact source = inputPainting();
            var result = adapter.execute(request(source, "vmm-success"));
            ProviderBinaryOutput output = (ProviderBinaryOutput) result.output();

            assertThat(result.outputModality()).isEqualTo(WorkflowModality.AUDIO);
            assertThat(output.mimeType()).isEqualTo("audio/wav");
            assertThat(output.byteLength()).isEqualTo(ProviderTestFixtures.wave().length);
            assertThat(output.sha256()).matches("[0-9a-f]{64}");
            assertThat(Files.exists(outputRoot.resolve("generated.wav"))).isFalse();
            assertThat(fixture.requestCount()).isEqualTo(1);
            JsonNode body = mapper.readTree(fixture.lastRequest().body());
            assertThat(fixture.lastRequest().uri().getPath()).isEqualTo("/api/generate_with_image");
            assertThat(body.path("duration").asInt()).isEqualTo(30);
            assertThat(body.path("image").asText()).startsWith("data:image/png;base64,");
            assertThat(body.has("imageUrl")).isFalse();
            assertThat(fixture.lastRequest().bodyText())
                    .doesNotContain("/outside/secret", outputRoot.toString(), "file://");
            output.artifact().close();
            source.close();
        }
        assertStagingEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape.wav", "sub/escape.wav", "sub\\escape.wav", ".hidden.wav", "evil.mp3"})
    void rejectsUnsafeFileNamesWithoutReadingOutsideRoot(String fileName) throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image")) {
            configureBase(fixture);
            fixture.respondJson(200, "{\"success\":true,\"fileName\":"
                    + mapper.writeValueAsString(fileName) + ",\"full_path\":\"/tmp/ignored\"}");
            ProviderArtifact source = inputPainting();

            assertCategory(() -> adapter(fixture, 2_000).execute(request(source, "vmm-name")),
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);
            assertThat(fixture.requestCount()).isEqualTo(1);
            source.close();
        }
    }

    @Test
    void rejectsSymlinkEscapeAndDoesNotReadExternalWave() throws Exception {
        Path outside = temporaryDirectory.resolve("outside.wav");
        Files.write(outside, ProviderTestFixtures.wave());
        Files.createSymbolicLink(outputRoot.resolve("linked.wav"), outside);
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image")) {
            configureBase(fixture);
            fixture.respondJson(200, "{\"success\":true,\"fileName\":\"linked.wav\"}");
            ProviderArtifact source = inputPainting();

            assertCategory(() -> adapter(fixture, 2_000).execute(request(source, "vmm-symlink")),
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);
            assertThat(Files.readAllBytes(outside)).isEqualTo(ProviderTestFixtures.wave());
            assertThat(Files.exists(outputRoot.resolve("linked.wav"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .isFalse();
            source.close();
        }
    }

    @Test
    void rejectsMissingOutput() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image")) {
            configureBase(fixture);
            fixture.respondJson(200, "{\"success\":true,\"fileName\":\"missing.wav\"}");
            ProviderArtifact source = inputPainting();
            assertCategory(() -> adapter(fixture, 2_000).execute(request(source, "vmm-missing")),
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);
            source.close();
        }
    }

    @Test
    void rejectsInvalidWaveAndCleansContainedSourceOutput() throws Exception {
        Files.writeString(outputRoot.resolve("invalid.wav"), "not-wave");
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image")) {
            configureBase(fixture);
            fixture.respondJson(200, "{\"success\":true,\"fileName\":\"invalid.wav\"}");
            ProviderArtifact source = inputPainting();
            assertCategory(() -> adapter(fixture, 2_000).execute(request(source, "vmm-invalid")),
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);
            assertThat(Files.exists(outputRoot.resolve("invalid.wav"))).isFalse();
            source.close();
        }
        assertStagingEmpty();
    }

    @Test
    void rejectsOversizedWaveAndCleansContainedSourceOutput() throws Exception {
        creation.setMaxAudioOutputBytes(16);
        Files.write(outputRoot.resolve("large.wav"), ProviderTestFixtures.wave());
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image")) {
            configureBase(fixture);
            fixture.respondJson(200, "{\"success\":true,\"fileName\":\"large.wav\"}");
            ProviderArtifact source = inputPainting();
            assertCategory(() -> adapter(fixture, 2_000).execute(request(source, "vmm-large")),
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);
            assertThat(Files.exists(outputRoot.resolve("large.wav"))).isFalse();
            source.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "{}",
            "{\"success\":true}",
            "{\"success\":true,\"fileName\":\"\"}",
            "{\"success\":true,\"success\":true,\"fileName\":\"duplicate.wav\"}",
            "{\"success\":true,\"fileName\":\"one.wav\"} {\"success\":true,\"fileName\":\"two.wav\"}"
    })
    void rejectsMalformedOrMissingResponse(String response) throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image")) {
            configureBase(fixture);
            fixture.respondJson(200, response);
            ProviderArtifact source = inputPainting();
            assertCategory(() -> adapter(fixture, 2_000).execute(request(source, "vmm-response")),
                    ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);
            assertThat(fixture.requestCount()).isEqualTo(1);
            source.close();
        }
    }

    @Test
    void mapsExplicitVmmRejectionWithoutRetry() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image")) {
            configureBase(fixture);
            fixture.respondJson(200, "{\"success\":false,\"error\":\"raw-sensitive-error\"}");
            ProviderArtifact source = inputPainting();
            assertThatThrownBy(() -> adapter(fixture, 2_000).execute(request(source, "vmm-reject")))
                    .isInstanceOf(ProviderExecutionException.class)
                    .satisfies(exception -> {
                        ProviderExecutionException provider = (ProviderExecutionException) exception;
                        assertThat(provider.category()).isEqualTo(ProviderErrorCategory.PROVIDER_REJECTED);
                        assertThat(provider.getMessage()).doesNotContain("raw-sensitive-error");
                    });
            assertThat(fixture.requestCount()).isEqualTo(1);
            source.close();
        }
    }

    @Test
    void timesOutWithoutRetryAfterGenerationMayHaveStarted() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image")) {
            configureBase(fixture);
            fixture.respondAfter(200, "{\"success\":true,\"fileName\":\"late.wav\"}", 500);
            ProviderArtifact source = inputPainting();
            assertCategory(() -> adapter(fixture, 50).execute(request(source, "vmm-timeout")),
                    ProviderErrorCategory.PROVIDER_TIMEOUT);
            assertThat(fixture.requestCount()).isEqualTo(1);
            source.close();
        }
    }

    @Test
    void unavailableServiceIsClassifiedWithoutRetry() throws Exception {
        LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image");
        configureBase(fixture);
        java.net.URI stoppedEndpoint = fixture.uri("/api/generate_with_image");
        fixture.close();
        ProviderArtifact source = inputPainting();
        VmmEndpointResolver resolver = new VmmEndpointResolver() {
            @Override public java.net.URI resolveGenerationEndpoint() { return stoppedEndpoint; }
            @Override public Path resolveOutputRoot() { return outputRoot; }
        };
        assertCategory(() -> adapter(resolver, 100).execute(request(source, "vmm-down")),
                ProviderErrorCategory.PROVIDER_UNAVAILABLE);
        source.close();
    }

    @Test
    void unsafeOrMissingOutputRootFailsBeforeVmmSubmission() throws Exception {
        try (LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/api/generate_with_image")) {
            configureBase(fixture);
            fixture.respondJson(200, "{\"success\":true,\"fileName\":\"unused.wav\"}");
            Files.delete(outputRoot);
            ProviderArtifact source = inputPainting();

            assertCategory(() -> adapter(fixture, 2_000).execute(request(source, "vmm-root")),
                    ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID);
            assertThat(fixture.requestCount()).isZero();
            source.close();
        }
    }

    private void configureBase(LocalProviderHttpFixture fixture) {
        String base = fixture.uri("/").toString();
        providers.getPaintingMusic().setBaseUrl(base.substring(0, base.length() - 1));
    }

    private AuralinkVmmProviderAdapter adapter(LocalProviderHttpFixture fixture, int timeoutMillis) {
        VmmEndpointPolicy policy = new VmmEndpointPolicy(creation, providers);
        return adapter(policy, timeoutMillis);
    }

    private AuralinkVmmProviderAdapter adapter(VmmEndpointResolver resolver, int timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        VmmHttpClient client = new VmmHttpClient(
                restClient,
                new ProviderHttpExecutor(mapper),
                mapper,
                creation,
                resolver);
        VmmEndpointPolicy readinessPolicy = new VmmEndpointPolicy(creation, providers);
        return new AuralinkVmmProviderAdapter(
                new ProviderInputValidator(creation),
                new ProviderDataUrlEncoder(),
                staging,
                creation,
                client,
                readinessPolicy,
                new ProviderBulkheads(creation));
    }

    private ProviderArtifact inputPainting() {
        return staging.stageInputImage(
                new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png");
    }

    private ProviderExecutionRequest request(ProviderArtifact source, String requestId) {
        return new ProviderExecutionRequest(
                requestId,
                WorkflowOperation.PAINTING_TO_MUSIC,
                "auralink-vmm",
                new ProviderImageInput(source, WorkflowModality.PAINTING, null));
    }

    private void assertCategory(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            ProviderErrorCategory category) {
        assertThatThrownBy(callable)
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(category);
    }

    private void assertStagingEmpty() throws Exception {
        if (!Files.exists(creation.getStagingDir())) {
            return;
        }
        try (Stream<Path> files = Files.list(creation.getStagingDir())) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
