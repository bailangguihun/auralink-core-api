package com.auralink.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.auralink.config.properties.CorsProperties;
import com.auralink.config.properties.HttpClientProperties;
import com.auralink.config.properties.JwtProperties;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.config.properties.RemoteFetchProperties;
import com.auralink.config.properties.ServiceProperties;
import com.auralink.config.properties.StorageProperties;
import com.auralink.config.properties.WorkflowProperties;

class ConfigurationPropertiesBindingTest {

    @Test
    void bindsOperationalAndSecurityBoundariesWithTypedValues() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("auralink.service.vmm-url", "http://vmm.internal:5101"),
                Map.entry("auralink.service.nonvmm-url", "http://qwen.internal:5102"),
                Map.entry("auralink.storage.upload-dir", "/tmp/auralink/uploads"),
                Map.entry("auralink.storage.audio-dir", "/tmp/auralink/audio"),
                Map.entry("auralink.storage.legacy-frontend-audio-dir", "/tmp/auralink/legacy-audio"),
                Map.entry("auralink.media-assets.managed-dir", "/tmp/auralink/media-assets"),
                Map.entry("auralink.media-assets.max-upload-bytes", "4096"),
                Map.entry("auralink.media-assets.max-generated-bytes", "8192"),
                Map.entry("auralink.media-assets.max-image-pixels", "10000"),
                Map.entry("auralink.media-assets.public-cache-seconds", "120"),
                Map.entry("auralink.jwt.secret", "test-only-secret"),
                Map.entry("auralink.jwt.expiration", "123456"),
                Map.entry("auralink.paintings.metadata-csv-path", "/tmp/auralink/paintings.csv"),
                Map.entry("auralink.paintings.picture-dir", "/tmp/auralink/pictures"),
                Map.entry("auralink.paintings.image-base-url", "https://images.example"),
                Map.entry("auralink.paintings.import-enabled", "true"),
                Map.entry("auralink.paintings.import-fail-on-error", "false"),
                Map.entry("auralink.paintings.import-batch-size", "321"),
                Map.entry("auralink.paintings.daily-zone", "Asia/Shanghai"),
                Map.entry("auralink.cors.allowed-origins", "https://legacy.example,http://localhost:3000"),
                Map.entry("auralink.cors.allow-credentials", "true"),
                Map.entry("auralink.http-client.connect-timeout", "2500ms"),
                Map.entry("auralink.http-client.read-timeout", "45s"),
                Map.entry("auralink.remote-fetch.max-bytes", "4096"),
                Map.entry("auralink.remote-fetch.connect-timeout", "750ms"),
                Map.entry("auralink.remote-fetch.read-timeout", "5s"),
                Map.entry("auralink.remote-fetch.total-timeout", "30s"),
                Map.entry("auralink.remote-fetch.max-redirects", "2"),
                Map.entry("auralink.workflows.enabled", "true"),
                Map.entry("auralink.workflows.schema-version", "1"),
                Map.entry("auralink.workflows.max-graph-bytes", "32768"),
                Map.entry("auralink.workflows.max-nodes", "12"),
                Map.entry("auralink.workflows.max-edges", "11"),
                Map.entry("auralink.workflows.max-name-chars", "100"),
                Map.entry("auralink.workflows.max-description-chars", "1500"),
                Map.entry("auralink.providers.seedream.base-url", "https://provider.example"),
                Map.entry("auralink.providers.video.enabled", "false"))));

        ServiceProperties services = bind(binder, "auralink.service", ServiceProperties.class);
        StorageProperties storage = bind(binder, "auralink.storage", StorageProperties.class);
        MediaAssetProperties mediaAssets = bind(
                binder, "auralink.media-assets", MediaAssetProperties.class);
        JwtProperties jwt = bind(binder, "auralink.jwt", JwtProperties.class);
        PaintingProperties paintings = bind(binder, "auralink.paintings", PaintingProperties.class);
        CorsProperties cors = bind(binder, "auralink.cors", CorsProperties.class);
        HttpClientProperties http = bind(binder, "auralink.http-client", HttpClientProperties.class);
        RemoteFetchProperties remoteFetch = bind(binder, "auralink.remote-fetch", RemoteFetchProperties.class);
        WorkflowProperties workflows = bind(binder, "auralink.workflows", WorkflowProperties.class);
        ProviderProperties providers = bind(binder, "auralink.providers", ProviderProperties.class);

        assertThat(services.getVmmUrl()).isEqualTo("http://vmm.internal:5101");
        assertThat(services.getQwenServiceUrl()).isEqualTo("http://qwen.internal:5102");
        assertThat(storage.getUploadDir()).isEqualTo("/tmp/auralink/uploads");
        assertThat(storage.getAudioDir()).isEqualTo("/tmp/auralink/audio");
        assertThat(storage.getLegacyFrontendAudioDir()).isEqualTo("/tmp/auralink/legacy-audio");
        assertThat(mediaAssets.getManagedDir()).isEqualTo("/tmp/auralink/media-assets");
        assertThat(mediaAssets.getMaxUploadBytes()).isEqualTo(4_096L);
        assertThat(mediaAssets.getMaxGeneratedBytes()).isEqualTo(8_192L);
        assertThat(mediaAssets.getMaxImagePixels()).isEqualTo(10_000L);
        assertThat(mediaAssets.getPublicCacheSeconds()).isEqualTo(120L);
        assertThat(jwt.getSecret()).isEqualTo("test-only-secret");
        assertThat(jwt.getExpiration()).isEqualTo(123_456L);
        assertThat(paintings.getMetadataCsvPath()).isEqualTo("/tmp/auralink/paintings.csv");
        assertThat(paintings.getPictureDir()).isEqualTo("/tmp/auralink/pictures");
        assertThat(paintings.getImageBaseUrl()).isEqualTo("https://images.example");
        assertThat(paintings.isImportEnabled()).isTrue();
        assertThat(paintings.isImportFailOnError()).isFalse();
        assertThat(paintings.getImportBatchSize()).isEqualTo(321);
        assertThat(paintings.getDailyZone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(cors.getAllowedOrigins()).containsExactly("https://legacy.example", "http://localhost:3000");
        assertThat(cors.isAllowCredentials()).isTrue();
        assertThat(http.getConnectTimeout()).isEqualTo(Duration.ofMillis(2_500));
        assertThat(http.getReadTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(remoteFetch.getMaxBytes()).isEqualTo(4_096);
        assertThat(remoteFetch.getConnectTimeout()).isEqualTo(Duration.ofMillis(750));
        assertThat(remoteFetch.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(remoteFetch.getTotalTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(remoteFetch.getMaxRedirects()).isEqualTo(2);
        assertThat(workflows.isEnabled()).isTrue();
        assertThat(workflows.getSchemaVersion()).isEqualTo(1);
        assertThat(workflows.getMaxGraphBytes()).isEqualTo(32_768);
        assertThat(workflows.getMaxNodes()).isEqualTo(12);
        assertThat(workflows.getMaxEdges()).isEqualTo(11);
        assertThat(workflows.getMaxNameChars()).isEqualTo(100);
        assertThat(workflows.getMaxDescriptionChars()).isEqualTo(1_500);
        assertThat(providers.getSeedream().getBaseUrl()).isEqualTo("https://provider.example");
        assertThat(providers.getSeedream().getApiKey()).isEmpty();
        assertThat(providers.getVideo().isEnabled()).isFalse();
    }

    @Test
    void workflowDefaultsAreDisabledAndMatchSchemaOneSafetyLimits() {
        WorkflowProperties workflows = new WorkflowProperties();

        assertThat(workflows.isEnabled()).isFalse();
        assertThat(workflows.getSchemaVersion()).isEqualTo(1);
        assertThat(workflows.getMaxGraphBytes()).isEqualTo(65_536);
        assertThat(workflows.getMaxNodes()).isEqualTo(16);
        assertThat(workflows.getMaxEdges()).isEqualTo(15);
        assertThat(workflows.getMaxNameChars()).isEqualTo(120);
        assertThat(workflows.getMaxDescriptionChars()).isEqualTo(2_000);
    }

    private static <T> T bind(Binder binder, String prefix, Class<T> type) {
        return binder.bind(prefix, Bindable.of(type))
                .orElseThrow(() -> new IllegalStateException("Configuration did not bind: " + prefix));
    }
}
