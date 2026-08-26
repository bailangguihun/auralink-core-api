package com.auralink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.auralink.config.properties.StorageProperties;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TimeZone;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@RequiredArgsConstructor
@Slf4j
public class Application {

    private final StorageProperties storageProperties;

    @PostConstruct
    void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        createDirectoryIfPossible(storageProperties.getUploadDir(), "upload");
        createDirectoryIfPossible(storageProperties.getAudioDir(), "audio");
        createDirectoryIfPossible(storageProperties.getLegacyFrontendAudioDir(), "legacy frontend audio");
    }

    private void createDirectoryIfPossible(String configuredPath, String directoryType) {
        try {
            Files.createDirectories(Path.of(configuredPath));
        } catch (IOException | RuntimeException exception) {
            // Preserve the inherited best-effort startup behavior; individual operations
            // still report a storage failure when the configured directory is unusable.
            log.warn("Unable to initialize configured {} directory", directoryType);
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
