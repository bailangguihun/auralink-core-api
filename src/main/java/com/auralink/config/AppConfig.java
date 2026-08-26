package com.auralink.config;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import com.auralink.config.properties.HttpClientProperties;

import lombok.RequiredArgsConstructor;

/**
 * Shared blocking HTTP-client infrastructure.
 *
 * <p>The legacy services continue to use {@link RestTemplate}. New provider
 * adapters can inject the prototype-scoped {@link RestClient.Builder} without
 * coupling configuration to a particular provider.</p>
 */
@Configuration
@RequiredArgsConstructor
public class AppConfig {

    private final HttpClientProperties httpClientProperties;
    private final RestClientBuilderConfigurer restClientBuilderConfigurer;

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(toIntMilliseconds(httpClientProperties.getConnectTimeout(), "connect timeout"));
        factory.setReadTimeout(toIntMilliseconds(httpClientProperties.getReadTimeout(), "read timeout"));
        return factory;
    }

    @Bean
    public RestTemplate restTemplate(ClientHttpRequestFactory requestFactory) {
        return new RestTemplate(requestFactory);
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder(ClientHttpRequestFactory requestFactory) {
        return restClientBuilderConfigurer.configure(RestClient.builder())
                .requestFactory(requestFactory);
    }

    private int toIntMilliseconds(java.time.Duration duration, String propertyName) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        long milliseconds = duration.toMillis();
        if (milliseconds < 1 || milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(propertyName + " must be between 1ms and "
                    + Integer.MAX_VALUE + "ms");
        }
        return (int) milliseconds;
    }
}
