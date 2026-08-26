package com.auralink.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.http.client.ClientHttpRequestFactory;

import com.auralink.config.properties.HttpClientProperties;

class AppConfigTest {

    @Test
    void createsLegacyAndFutureClientsFromTheConfiguredFactory() {
        HttpClientProperties properties = new HttpClientProperties();
        AppConfig config = new AppConfig(properties, new RestClientBuilderConfigurer());

        ClientHttpRequestFactory requestFactory = config.clientHttpRequestFactory();

        assertThat(config.restTemplate(requestFactory)).isNotNull();
        assertThat(config.restClientBuilder(requestFactory)).isNotNull();
    }

    @Test
    void rejectsTimeoutsThatCollapseToUnlimitedZeroMilliseconds() {
        HttpClientProperties properties = new HttpClientProperties();
        properties.setConnectTimeout(Duration.ofNanos(1));
        AppConfig config = new AppConfig(properties, new RestClientBuilderConfigurer());

        assertThatThrownBy(config::clientHttpRequestFactory)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1ms");
    }

    @Test
    void rejectsTimeoutsOutsideTheRequestFactoryIntegerRange() {
        HttpClientProperties properties = new HttpClientProperties();
        properties.setReadTimeout(Duration.ofMillis((long) Integer.MAX_VALUE + 1));
        AppConfig config = new AppConfig(properties, new RestClientBuilderConfigurer());

        assertThatThrownBy(config::clientHttpRequestFactory)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1ms");
    }
}
