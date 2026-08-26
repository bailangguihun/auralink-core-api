package com.auralink.config.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "auralink.cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of(
            "https://fanhualy.top",
            "http://fanhualy.top",
            "https://api.fanhualy.top",
            "http://api.fanhualy.top",
            "http://localhost:3000",
            "https://localhost:3000",
            "https://*.fanhualy.top",
            "http://*.fanhualy.top");
    private List<String> allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");
    private List<String> allowedHeaders = List.of(
            "Origin", "Content-Type", "Accept", "Authorization", "X-Requested-With", "Cache-Control",
            "Range", "If-None-Match", "If-Modified-Since", "If-Range");
    private List<String> exposedHeaders = List.of(
            "Authorization", "Content-Disposition", "Content-Type", "Content-Length",
            "Accept-Ranges", "Content-Range", "ETag", "Cache-Control", "Last-Modified");
    private boolean allowCredentials = true;
    private long maxAgeSeconds = 3_600L;
}
