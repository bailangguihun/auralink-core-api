package com.auralink.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.auralink.config.properties.CreationProviderProperties;

class CreationProviderPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsAreDisabledBoundedAndNonSecret() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            CreationProviderProperties properties = context.getBean(CreationProviderProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getStagingDir())
                    .isEqualTo(Path.of("/tmp/auralink-provider-staging"));
            assertThat(properties.getMaxImageInputBytes()).isEqualTo(10_485_760);
            assertThat(properties.getMaxImageOutputBytes()).isEqualTo(26_214_400);
            assertThat(properties.getMaxAudioOutputBytes()).isEqualTo(268_435_456);
            assertThat(properties.getMaxTextChars()).isEqualTo(20_000);
            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getQwenReadTimeout()).isEqualTo(Duration.ofMinutes(3));
            assertThat(properties.getSeedreamReadTimeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.getVmmReadTimeout()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.getSeedreamDefaultSize()).isEqualTo("2K");
            assertThat(properties.getSeedreamOutputFormat()).isEqualTo("png");
            assertThat(properties.isSeedreamWatermark()).isTrue();
        });
    }

    @Test
    void reviewedPropertiesBindWithoutProviderCredentials() {
        contextRunner.withPropertyValues(
                "auralink.creation-providers.enabled=true",
                "auralink.creation-providers.staging-dir=/tmp/round8-staging",
                "auralink.creation-providers.max-image-input-bytes=1234",
                "auralink.creation-providers.connect-timeout=123ms",
                "auralink.creation-providers.max-concurrent-seedream=3",
                "auralink.creation-providers.seedream-default-size=4K",
                "auralink.creation-providers.seedream-output-format=jpeg",
                "auralink.creation-providers.seedream-watermark=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CreationProviderProperties properties =
                            context.getBean(CreationProviderProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getStagingDir()).isEqualTo(Path.of("/tmp/round8-staging"));
                    assertThat(properties.getMaxImageInputBytes()).isEqualTo(1234);
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofMillis(123));
                    assertThat(properties.getMaxConcurrentSeedream()).isEqualTo(3);
                    assertThat(properties.getSeedreamDefaultSize()).isEqualTo("4K");
                    assertThat(properties.getSeedreamOutputFormat()).isEqualTo("jpeg");
                    assertThat(properties.isSeedreamWatermark()).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CreationProviderProperties.class)
    static class TestConfiguration {
    }
}
