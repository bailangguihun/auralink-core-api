package com.auralink.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.auralink.config.properties.JwtProperties;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.config.properties.StorageProperties;

class ConfigDataImportTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitEnvironmentFileIsImportedAsPropertiesWithoutDotenvDependency() throws IOException {
        Path envFile = tempDir.resolve("round2-test.env");
        Files.writeString(envFile, String.join("\n",
                "AURALINK_JWT_SECRET=test-only-import-secret",
                "AURALINK_JWT_EXPIRATION_MS=234567",
                "AURALINK_PAINTING_CSV_PATH=/tmp/imported-paintings.csv",
                "AURALINK_PAINTING_PICTURE_DIR=/tmp/imported-pictures",
                "AURALINK_PAINTING_CATALOG_IMPORT_ENABLED=true",
                "AURALINK_PAINTING_CATALOG_IMPORT_FAIL_ON_ERROR=false",
                "AURALINK_PAINTING_CATALOG_IMPORT_BATCH_SIZE=222",
                "AURALINK_PAINTING_DAILY_ZONE=Asia/Shanghai",
                "AURALINK_LEGACY_FRONTEND_AUDIO_DIR=/tmp/imported-legacy-audio",
                ""));

        String previous = System.getProperty("AURALINK_ENV_FILE");
        System.setProperty("AURALINK_ENV_FILE", envFile.toAbsolutePath().toString());
        try {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(BoundPropertiesConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        JwtProperties jwt = context.getBean(JwtProperties.class);
                        PaintingProperties paintings = context.getBean(PaintingProperties.class);
                        StorageProperties storage = context.getBean(StorageProperties.class);

                        assertThat(jwt.getSecret()).isEqualTo("test-only-import-secret");
                        assertThat(jwt.getExpiration()).isEqualTo(234_567L);
                        assertThat(paintings.getMetadataCsvPath()).isEqualTo("/tmp/imported-paintings.csv");
                        assertThat(paintings.getPictureDir()).isEqualTo("/tmp/imported-pictures");
                        assertThat(paintings.isImportEnabled()).isTrue();
                        assertThat(paintings.isImportFailOnError()).isFalse();
                        assertThat(paintings.getImportBatchSize()).isEqualTo(222);
                        assertThat(paintings.getDailyZone().getId()).isEqualTo("Asia/Shanghai");
                        assertThat(storage.getLegacyFrontendAudioDir()).isEqualTo("/tmp/imported-legacy-audio");
                    });
        } finally {
            if (previous == null) {
                System.clearProperty("AURALINK_ENV_FILE");
            } else {
                System.setProperty("AURALINK_ENV_FILE", previous);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({JwtProperties.class, PaintingProperties.class, StorageProperties.class})
    static class BoundPropertiesConfiguration {
    }
}
