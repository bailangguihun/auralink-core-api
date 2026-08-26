package com.auralink.config;

import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.GuideProperties;

/** Dedicated timeout boundary for the internal Guide service without changing legacy clients. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuideProperties.class)
public class GuideClientConfiguration {

    @Bean
    @Qualifier("guideRestClient")
    public RestClient guideRestClient(RestClient.Builder sharedBuilder, GuideProperties properties) {
        validateInternalEndpoint(properties);
        int connectTimeoutMs = toMilliseconds(
                properties.getConnectTimeout(), "Guide connect timeout");
        int providerReadTimeoutMs = toMilliseconds(
                properties.getReadTimeout(), "Guide provider read timeout");
        int providerTotalTimeoutMs = toMilliseconds(
                properties.getTotalTimeout(), "Guide provider total timeout");
        int retryBackoffMs = toMilliseconds(
                properties.getRetryBackoff(), "Guide retry backoff");
        int internalReadTimeoutMs = toMilliseconds(
                properties.getInternalReadTimeout(), "Guide internal read timeout");
        long requiredBudgetMs = (long) providerTotalTimeoutMs
                + providerReadTimeoutMs
                + connectTimeoutMs;
        if (internalReadTimeoutMs <= requiredBudgetMs) {
            throw new IllegalArgumentException(
                    "Guide internal read timeout must exceed the bounded provider deadline budget");
        }
        SimpleClientHttpRequestFactory requestFactory = guideRequestFactory(
                connectTimeoutMs, internalReadTimeoutMs);
        return sharedBuilder.clone()
                .requestFactory(requestFactory)
                .build();
    }

    SimpleClientHttpRequestFactory guideRequestFactory(
            int connectTimeoutMs,
            int internalReadTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // This is a loopback-only authenticated hop. Never allow ambient JVM
        // proxy settings to receive the internal authentication header.
        requestFactory.setProxy(Proxy.NO_PROXY);
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(internalReadTimeoutMs);
        return requestFactory;
    }

    private void validateInternalEndpoint(GuideProperties properties) {
        try {
            URI endpoint = new URI(properties.getServiceUrl());
            String scheme = endpoint.getScheme() == null
                    ? ""
                    : endpoint.getScheme().toLowerCase(Locale.ROOT);
            String endpointHost = canonicalLoopback(endpoint.getHost());
            String bindHost = canonicalLoopback(properties.getServiceHost());
            int endpointPort = endpoint.getPort() < 0 ? 80 : endpoint.getPort();
            String path = endpoint.getPath();
            if (!"http".equals(scheme)
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null
                    || (path != null && !path.isBlank() && !"/".equals(path))
                    || !endpointHost.equals(bindHost)
                    || endpointPort != properties.getServicePort()) {
                throw new IllegalArgumentException(
                        "Guide service URL must match the configured loopback bind address");
            }
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IllegalArgumentException("Guide service URL is invalid", exception);
        }
    }

    private String canonicalLoopback(String host) {
        if (host == null) {
            throw new IllegalArgumentException("Guide service host is invalid");
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if ("127.0.0.1".equals(normalized)) {
            return "127.0.0.1";
        }
        if ("::1".equals(normalized)) {
            return "::1";
        }
        throw new IllegalArgumentException("Guide service host must be a loopback literal");
    }

    private int toMilliseconds(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        long milliseconds = duration.toMillis();
        if (milliseconds < 1 || milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is outside the supported range");
        }
        return Math.toIntExact(milliseconds);
    }
}
