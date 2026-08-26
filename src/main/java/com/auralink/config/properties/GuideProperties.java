package com.auralink.config.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "auralink.guide")
public class GuideProperties {

    private boolean enabled = false;

    private String serviceUrl = "";

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(5);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(120);

    @NotNull
    private Duration totalTimeout = Duration.ofSeconds(250);

    /**
     * Outer Spring-to-guide-service budget. This must cover the guide service's
     * single shared provider deadline plus bounded local/internal overhead.
     */
    @NotNull
    private Duration internalReadTimeout = Duration.ofSeconds(380);

    @NotNull
    private Duration retryBackoff = Duration.ofMillis(200);

    @Min(1)
    @Max(8)
    private int maxConcurrentGenerations = 2;

    @Min(1)
    @Max(100)
    private int userGenerationLimit = 3;

    @NotNull
    private Duration userGenerationWindow = Duration.ofMinutes(10);

    @Min(1)
    @Max(10_000)
    private int globalGenerationLimit = 30;

    @NotNull
    private Duration globalGenerationWindow = Duration.ofHours(1);

    @NotBlank
    @Pattern(regexp = "1")
    private String schemaVersion = "1";

    @Min(0)
    @Max(5)
    private int maxKnowledgeItems = 5;

    @Min(0)
    @Max(8_000)
    private int maxKnowledgeChars = 8_000;

    @NotBlank
    private String poetryGraphPath = "../frontend/public/data/poetry-graph.json";

    @NotBlank
    private String poetryStatsPath = "../frontend/public/data/poetry-stats.json";

    private String internalToken = "";

    @NotBlank
    private String serviceHost = "127.0.0.1";

    @Min(1)
    @Max(65_535)
    private int servicePort = 5_003;

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = normalizeSimpleQuotedValue(serviceUrl);
    }

    /** Derive the internal URL after all bind properties have been bound. */
    public String getServiceUrl() {
        if (serviceUrl != null && !serviceUrl.isBlank()) {
            return serviceUrl;
        }
        String host = serviceHost == null ? "" : serviceHost.strip();
        String authority = "::1".equals(host) ? "[::1]" : host;
        return "http://" + authority + ":" + servicePort;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = normalizeSimpleQuotedValue(schemaVersion);
    }

    public void setPoetryGraphPath(String poetryGraphPath) {
        this.poetryGraphPath = normalizeSimpleQuotedValue(poetryGraphPath);
    }

    public void setPoetryStatsPath(String poetryStatsPath) {
        this.poetryStatsPath = normalizeSimpleQuotedValue(poetryStatsPath);
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = normalizeSimpleQuotedValue(internalToken);
    }

    public void setServiceHost(String serviceHost) {
        this.serviceHost = normalizeSimpleQuotedValue(serviceHost);
    }

    private String normalizeSimpleQuotedValue(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return (first == last && (first == '\'' || first == '"'))
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
