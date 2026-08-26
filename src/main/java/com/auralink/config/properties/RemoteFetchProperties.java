package com.auralink.config.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "auralink.remote-fetch")
public class RemoteFetchProperties {

    private long maxBytes = 25L * 1024L * 1024L;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration totalTimeout = Duration.ofMinutes(2);
    private int maxRedirects = 3;
}
