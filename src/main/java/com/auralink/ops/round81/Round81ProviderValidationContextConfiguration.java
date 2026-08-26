package com.auralink.ops.round81;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.auralink.config.CreationProviderClientConfiguration;
import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.config.properties.RemoteFetchProperties;
import com.auralink.creation.provider.ProviderAdapterRegistry;
import com.auralink.provider.ProviderBulkheads;
import com.auralink.provider.artifact.AudioOutputValidator;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.provider.composite.QwenSeedreamCompositeProviderAdapter;
import com.auralink.provider.http.ProviderHttpExecutor;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.provider.qwen.PaintingPromptPlanValidator;
import com.auralink.provider.qwen.PaintingToPoemPromptBuilder;
import com.auralink.provider.qwen.QwenCreationHttpClient;
import com.auralink.provider.qwen.QwenEndpointPolicy;
import com.auralink.provider.qwen.QwenEndpointResolver;
import com.auralink.provider.qwen.QwenPaintingPromptPlanner;
import com.auralink.provider.qwen.QwenPaintingToPoemProviderAdapter;
import com.auralink.provider.seedream.ImageToPaintingPromptBuilder;
import com.auralink.provider.seedream.PoemPlanSeedreamPromptBuilder;
import com.auralink.provider.seedream.SafeSeedreamResultFetcher;
import com.auralink.provider.seedream.SeedreamEndpointPolicy;
import com.auralink.provider.seedream.SeedreamEndpointResolver;
import com.auralink.provider.seedream.SeedreamHttpClient;
import com.auralink.provider.seedream.SeedreamImageGenerator;
import com.auralink.provider.seedream.SeedreamProviderAdapter;
import com.auralink.provider.seedream.SeedreamResultFetcher;
import com.auralink.provider.seedream.TextToPaintingPromptBuilder;
import com.auralink.provider.validation.ProviderDataUrlEncoder;
import com.auralink.provider.validation.ProviderInputValidator;
import com.auralink.provider.vmm.AuralinkVmmProviderAdapter;
import com.auralink.provider.vmm.VmmEndpointPolicy;
import com.auralink.provider.vmm.VmmEndpointResolver;
import com.auralink.provider.vmm.VmmHttpClient;
import com.auralink.service.SafeRemoteResourceFetcher;
import com.auralink.service.media.ImageContentValidator;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Explicit non-web provider-only graph used by the server-local validation CLI.
 *
 * <p>No component scan or general application auto-configuration is enabled.
 * Controllers, security, servlet infrastructure, DataSource/JPA, Flyway,
 * repositories, catalog runners, and Guide clients are absent by construction.</p>
 */
@ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        RestClientAutoConfiguration.class
})
@EnableConfigurationProperties({
        CreationProviderProperties.class,
        ProviderProperties.class,
        RemoteFetchProperties.class,
        MediaAssetProperties.class
})
@Import({
        CreationProviderClientConfiguration.class,
        WorkflowCapabilityRegistry.class,
        ProviderBulkheads.class,
        ProviderDataUrlEncoder.class,
        ProviderInputValidator.class,
        ImageContentValidator.class,
        AudioOutputValidator.class,
        ProviderArtifactStagingService.class,
        SafeRemoteResourceFetcher.class,
        TextToPaintingPromptBuilder.class,
        ImageToPaintingPromptBuilder.class,
        PoemPlanSeedreamPromptBuilder.class,
        SeedreamEndpointPolicy.class,
        SeedreamHttpClient.class,
        SeedreamImageGenerator.class,
        SeedreamProviderAdapter.class,
        QwenEndpointPolicy.class,
        QwenCreationHttpClient.class,
        PaintingPromptPlanValidator.class,
        QwenPaintingPromptPlanner.class,
        PaintingToPoemPromptBuilder.class,
        PaintingPoemResultValidator.class,
        QwenPaintingToPoemProviderAdapter.class,
        QwenSeedreamCompositeProviderAdapter.class,
        VmmEndpointPolicy.class,
        VmmHttpClient.class,
        AuralinkVmmProviderAdapter.class,
        ProviderAdapterRegistry.class
})
final class Round81ProviderValidationContextConfiguration {

    private Round81ProviderValidationContextConfiguration() {
    }

    @Bean
    Round81ProviderCallLedger round81ProviderCallLedger() {
        return new Round81ProviderCallLedger();
    }

    @Bean
    ProviderHttpExecutor round81ProviderHttpExecutor(
            ObjectMapper objectMapper,
            Round81ProviderCallLedger ledger) {
        return new Round81CountingProviderHttpExecutor(objectMapper, ledger);
    }

    @Bean
    Round81MockSupport round81MockSupport(org.springframework.core.env.Environment environment) {
        return new Round81MockSupport(environment);
    }

    @Bean
    @Primary
    SeedreamEndpointResolver round81SeedreamEndpointResolver(
            SeedreamEndpointPolicy livePolicy,
            Round81MockSupport mockSupport) {
        return mockSupport.enabled()
                ? () -> mockSupport.endpoint("/images/generations")
                : livePolicy;
    }

    @Bean
    @Primary
    QwenEndpointResolver round81QwenEndpointResolver(
            QwenEndpointPolicy livePolicy,
            Round81MockSupport mockSupport) {
        return mockSupport.enabled()
                ? () -> mockSupport.endpoint("/chat/completions")
                : livePolicy;
    }

    @Bean
    @Primary
    VmmEndpointResolver round81VmmEndpointResolver(
            VmmEndpointPolicy livePolicy,
            Round81MockSupport mockSupport) {
        return mockSupport.enabled()
                ? new VmmEndpointResolver() {
                    @Override
                    public java.net.URI resolveGenerationEndpoint() {
                        return mockSupport.endpoint("/api/generate_with_image");
                    }

                    @Override
                    public java.nio.file.Path resolveOutputRoot() {
                        return livePolicy.resolveOutputRoot();
                    }
                }
                : livePolicy;
    }

    @Bean
    SeedreamResultFetcher round81SeedreamResultFetcher(
            SafeRemoteResourceFetcher remoteResourceFetcher,
            ProviderArtifactStagingService stagingService,
            CreationProviderProperties properties,
            Round81MockSupport mockSupport) {
        if (mockSupport.enabled()) {
            return new Round81MockSeedreamResultFetcher(stagingService, properties, mockSupport);
        }
        return new SafeSeedreamResultFetcher(remoteResourceFetcher, stagingService);
    }

    @Bean
    Round81ResultRetainer round81ResultRetainer(
            ObjectMapper objectMapper,
            CreationProviderProperties properties,
            ImageContentValidator imageValidator,
            AudioOutputValidator audioValidator) {
        return new Round81ResultRetainer(objectMapper, properties, imageValidator, audioValidator);
    }

    @Bean
    Round81ProviderValidationCoordinator round81ProviderValidationCoordinator(
            ProviderAdapterRegistry registry,
            ProviderArtifactStagingService stagingService,
            CreationProviderProperties properties,
            ObjectMapper objectMapper,
            Round81ProviderCallLedger ledger,
            Round81ResultRetainer resultRetainer) {
        return new Round81ProviderValidationCoordinator(
                registry, stagingService, properties, objectMapper, ledger, resultRetainer);
    }
}
