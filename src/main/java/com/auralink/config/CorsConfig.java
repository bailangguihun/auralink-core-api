package com.auralink.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.auralink.config.properties.CorsProperties;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    private final CorsProperties corsProperties;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        validateCredentialPolicy(corsProperties.getAllowedOrigins(), corsProperties.isAllowCredentials());

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.copyOf(corsProperties.getAllowedOrigins()));
        configuration.setAllowedMethods(List.copyOf(corsProperties.getAllowedMethods()));
        configuration.setAllowedHeaders(List.copyOf(corsProperties.getAllowedHeaders()));
        configuration.setExposedHeaders(List.copyOf(corsProperties.getExposedHeaders()));
        configuration.setAllowCredentials(corsProperties.isAllowCredentials());
        configuration.setMaxAge(corsProperties.getMaxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void validateCredentialPolicy(List<String> allowedOrigins, boolean allowCredentials) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalStateException("At least one CORS origin must be configured");
        }
        if (allowCredentials && allowedOrigins.stream().anyMatch("*"::equals)) {
            throw new IllegalStateException("Credentialed CORS must not use the wildcard origin");
        }
    }
}
