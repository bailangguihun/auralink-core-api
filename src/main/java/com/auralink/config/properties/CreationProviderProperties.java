package com.auralink.config.properties;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** Bounded, non-secret runtime settings for creation provider adapters. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "auralink.creation-providers")
public class CreationProviderProperties {

    private boolean enabled = false;

    @NotNull
    private Path stagingDir = Path.of("/tmp/auralink-provider-staging");

    @Min(1)
    private long maxImageInputBytes = 10L * 1024L * 1024L;

    @Min(1)
    private long maxImageOutputBytes = 25L * 1024L * 1024L;

    @Min(1)
    private long maxAudioOutputBytes = 256L * 1024L * 1024L;

    @Min(1)
    private int maxTextChars = 20_000;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(5);

    @NotNull
    private Duration qwenReadTimeout = Duration.ofMinutes(3);

    @NotNull
    private Duration seedreamReadTimeout = Duration.ofMinutes(5);

    @NotNull
    private Duration vmmReadTimeout = Duration.ofMinutes(10);

    @Min(1)
    private int maxConcurrentSeedream = 2;

    @Min(1)
    private int maxConcurrentQwen = 4;

    @Min(1)
    private int maxConcurrentVmm = 1;

    private String seedreamDefaultSize = "2K";
    private String seedreamOutputFormat = "png";
    private boolean seedreamWatermark = true;
}
