package com.auralink.config;

import java.time.Duration;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.CreationProviderProperties;

import lombok.RequiredArgsConstructor;

/** Dedicated no-proxy, no-retry blocking clients with provider-specific deadlines. */
@Configuration
@RequiredArgsConstructor
public class CreationProviderClientConfiguration {

    private final CreationProviderProperties properties;
    private final RestClientBuilderConfigurer configurer;

    @Bean(name = "seedreamProviderHttpClient", destroyMethod = "close")
    public CloseableHttpClient seedreamProviderHttpClient() {
        return createClient(properties.getSeedreamReadTimeout(), properties.getMaxConcurrentSeedream());
    }

    @Bean(name = "qwenProviderHttpClient", destroyMethod = "close")
    public CloseableHttpClient qwenProviderHttpClient() {
        return createClient(properties.getQwenReadTimeout(), properties.getMaxConcurrentQwen());
    }

    @Bean(name = "vmmProviderHttpClient", destroyMethod = "close")
    public CloseableHttpClient vmmProviderHttpClient() {
        return createClient(properties.getVmmReadTimeout(), properties.getMaxConcurrentVmm());
    }

    @Bean(name = "seedreamProviderRestClient")
    public RestClient seedreamProviderRestClient(
            @Qualifier("seedreamProviderHttpClient") CloseableHttpClient client) {
        return createRestClient(client);
    }

    @Bean(name = "qwenProviderRestClient")
    public RestClient qwenProviderRestClient(
            @Qualifier("qwenProviderHttpClient") CloseableHttpClient client) {
        return createRestClient(client);
    }

    @Bean(name = "vmmProviderRestClient")
    public RestClient vmmProviderRestClient(
            @Qualifier("vmmProviderHttpClient") CloseableHttpClient client) {
        return createRestClient(client);
    }

    private CloseableHttpClient createClient(Duration readTimeout, int maxConnections) {
        Duration connectTimeout = requirePositive(properties.getConnectTimeout(), "connect timeout");
        Duration boundedReadTimeout = requirePositive(readTimeout, "read timeout");
        if (maxConnections < 1) {
            throw new IllegalArgumentException("Provider connection limit must be positive");
        }

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeout))
                .setSocketTimeout(Timeout.of(boundedReadTimeout))
                .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setAuthenticationEnabled(false)
                .setConnectionRequestTimeout(Timeout.of(connectTimeout))
                .setResponseTimeout(Timeout.of(boundedReadTimeout))
                .setContentCompressionEnabled(false)
                .setHardCancellationEnabled(true)
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableAuthCaching()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    private RestClient createRestClient(CloseableHttpClient client) {
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(client);
        return configurer.configure(RestClient.builder())
                .requestFactory(requestFactory)
                .build();
    }

    private Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Provider " + name + " must be positive");
        }
        return duration;
    }
}
