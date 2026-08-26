package com.auralink.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "auralink.providers")
public class ProviderProperties {

    private Provider seedream = new Provider();
    private Provider qwen = new Provider();
    private Provider paintingMusic = new Provider();
    private Provider guide = new Provider();
    private Provider video = new Provider();

    @Getter
    @Setter
    public static class Provider {
        private String apiKey = "";
        private String baseUrl = "";
        private String model = "";
        private String outputRoot = "";
        private boolean enabled;
    }
}
