package com.auralink.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.stream.Stream;

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
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.provider.qwen.QwenEndpointPolicy;
import com.auralink.provider.seedream.SeedreamEndpointPolicy;
import com.auralink.provider.vmm.VmmEndpointPolicy;

class ProviderEndpointPolicyTest {

    @TempDir
    Path temporaryDirectory;

    private CreationProviderProperties creation;
    private ProviderProperties providers;

    @BeforeEach
    void setUp() throws Exception {
        creation = ProviderTestFixtures.properties(temporaryDirectory.resolve("staging"));
        providers = new ProviderProperties();
        providers.getSeedream().setApiKey("test-seedream-key");
        providers.getSeedream().setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        providers.getSeedream().setModel("seedream-test-model");
        providers.getQwen().setApiKey("test-qwen-key");
        providers.getQwen().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        providers.getQwen().setModel("qwen3-vl-plus");
        providers.getPaintingMusic().setBaseUrl("http://127.0.0.1:5001");
        providers.getPaintingMusic().setOutputRoot(temporaryDirectory.resolve("vmm-output").toString());
        java.nio.file.Files.createDirectory(temporaryDirectory.resolve("vmm-output"));
    }

    @Test
    void featureDisabledTakesPrecedenceWithoutInspectingSecretsOrCallingAnything() {
        creation.setEnabled(false);

        assertThat(new SeedreamEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.FEATURE_DISABLED);
        assertThat(new QwenEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.FEATURE_DISABLED);
        assertThat(new VmmEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.FEATURE_DISABLED);
    }

    @Test
    void missingExternalConfigurationIsReportedWithoutNamingOrReturningValues() {
        providers.getSeedream().setApiKey("");
        providers.getQwen().setModel("");

        assertThat(new SeedreamEndpointPolicy(creation, providers).readiness())
                .extracting(readiness -> readiness.state(), readiness -> readiness.reason())
                .containsExactly(
                        ProviderReadinessState.CONFIGURATION_MISSING,
                        "REQUIRED_CONFIGURATION_MISSING");
        assertThat(new QwenEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_MISSING);
    }

    @Test
    void acceptsOnlyReviewedSeedreamRootAndAppendsFixedPath() {
        providers.getSeedream().setBaseUrl("https://ark.cn-beijing.volces.com/api/v3/");
        SeedreamEndpointPolicy policy = new SeedreamEndpointPolicy(creation, providers);

        assertThat(policy.readiness().state())
                .isEqualTo(ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION);
        assertThat(policy.resolveGenerationEndpoint().toString())
                .isEqualTo("https://ark.cn-beijing.volces.com/api/v3/images/generations");
    }

    @ParameterizedTest
    @MethodSource("invalidSeedreamRoots")
    void rejectsUnsafeOrUnreviewedSeedreamRoots(String baseUrl) {
        providers.getSeedream().setBaseUrl(baseUrl);
        SeedreamEndpointPolicy policy = new SeedreamEndpointPolicy(creation, providers);

        assertThat(policy.readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_INVALID);
        assertThatThrownBy(policy::resolveGenerationEndpoint)
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID);
    }

    @Test
    void rejectsMalformedOrBlankSeedreamModelWithoutFallback() {
        providers.getSeedream().setModel("../seedream-4");
        assertThat(new SeedreamEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_INVALID);

        providers.getSeedream().setModel("");
        assertThat(new SeedreamEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_MISSING);
    }

    @ParameterizedTest
    @MethodSource("approvedQwenRoots")
    void acceptsReviewedRegionalQwenRoots(String root, String expectedEndpoint) {
        providers.getQwen().setBaseUrl(root);
        QwenEndpointPolicy policy = new QwenEndpointPolicy(creation, providers);

        assertThat(policy.readiness().state())
                .isEqualTo(ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION);
        assertThat(policy.resolveChatCompletionsEndpoint().toString()).isEqualTo(expectedEndpoint);
    }

    @ParameterizedTest
    @MethodSource("invalidQwenRoots")
    void rejectsUnsafeOrRegionallyUnapprovedQwenRoots(String root) {
        providers.getQwen().setBaseUrl(root);

        assertThat(new QwenEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_INVALID);
    }

    @Test
    void rejectsUnreviewedQwenModel() {
        providers.getQwen().setModel("qwen-plus");

        assertThat(new QwenEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_INVALID);
    }

    @Test
    void vmmAllowsConfiguredLoopbackOrPrivateLiteralButDoesNotClaimHealth() {
        VmmEndpointPolicy loopback = new VmmEndpointPolicy(creation, providers);
        assertThat(loopback.readiness().state())
                .isEqualTo(ProviderReadinessState.INTERNAL_SERVICE_NOT_VALIDATED);
        assertThat(loopback.resolveGenerationEndpoint().toString())
                .isEqualTo("http://127.0.0.1:5001/api/generate_with_image");

        providers.getPaintingMusic().setBaseUrl("http://10.1.2.3:5101/");
        assertThat(new VmmEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.INTERNAL_SERVICE_NOT_VALIDATED);
    }

    @ParameterizedTest
    @MethodSource("invalidVmmRoots")
    void vmmRejectsPublicArbitraryOrPathBearingRoots(String root) {
        providers.getPaintingMusic().setBaseUrl(root);

        assertThat(new VmmEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_INVALID);
    }

    @Test
    void vmmRequiresBothServiceUrlAndOutputRoot() {
        providers.getPaintingMusic().setOutputRoot("");

        assertThat(new VmmEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_MISSING);
    }

    @Test
    void vmmRejectsRelativeOutputRootInsteadOfResolvingAgainstProcessDirectory() {
        providers.getPaintingMusic().setOutputRoot("relative/vmm-output");

        assertThat(new VmmEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_INVALID);
    }

    @Test
    void readinessRejectsInvalidInternalBoundsWithoutAnyProviderCall() {
        creation.setSeedreamReadTimeout(java.time.Duration.ZERO);
        assertThat(new SeedreamEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_INVALID);

        creation.setSeedreamReadTimeout(java.time.Duration.ofMinutes(5));
        creation.setMaxConcurrentQwen(0);
        assertThat(new QwenEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_INVALID);

        creation.setMaxConcurrentQwen(4);
        creation.setMaxAudioOutputBytes(0);
        assertThat(new VmmEndpointPolicy(creation, providers).readiness().state())
                .isEqualTo(ProviderReadinessState.CONFIGURATION_INVALID);
    }

    private static Stream<String> invalidSeedreamRoots() {
        return Stream.of(
                "http://ark.cn-beijing.volces.com/api/v3",
                "https://user@ark.cn-beijing.volces.com/api/v3",
                "https://ark.cn-beijing.volces.com:444/api/v3",
                "https://ark.cn-beijing.volces.com/api/v3?x=1",
                "https://ark.cn-beijing.volces.com/api/v3#fragment",
                "https://ark.cn-beijing.volces.com/api/v4",
                "https://localhost/api/v3",
                "https://third-party.example/api/v3");
    }

    private static Stream<Arguments> approvedQwenRoots() {
        return Stream.of(
                Arguments.of(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
                Arguments.of(
                        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/",
                        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"));
    }

    private static Stream<String> invalidQwenRoots() {
        return Stream.of(
                "http://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://user@dashscope.aliyuncs.com/compatible-mode/v1",
                "https://dashscope.aliyuncs.com:443/compatible-mode/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1?x=1",
                "https://dashscope.aliyuncs.com/api/v1",
                "https://example.com/compatible-mode/v1",
                "http://127.0.0.1:8000/compatible-mode/v1");
    }

    private static Stream<String> invalidVmmRoots() {
        return Stream.of(
                "https://example.com",
                "file:///tmp/vmm",
                "http://user@127.0.0.1:5001",
                "http://127.0.0.1:5001/generate",
                "http://127.0.0.1:5001?x=1",
                "http://vmm.internal:5001");
    }
}
