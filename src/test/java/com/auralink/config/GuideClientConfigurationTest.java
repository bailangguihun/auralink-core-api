package com.auralink.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Proxy;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.GuideProperties;

import jakarta.validation.Validation;

class GuideClientConfigurationTest {

    @Test
    void createsOnlyTheQualifiedRestClientWithDedicatedTimeouts() throws Exception {
        GuideProperties properties = new GuideProperties();
        properties.setConnectTimeout(Duration.ofMillis(750));
        properties.setReadTimeout(Duration.ofSeconds(5));
        GuideClientConfiguration configuration = new GuideClientConfiguration();

        RestClient client = configuration.guideRestClient(RestClient.builder(), properties);

        assertThat(client).isNotNull();
        var method = GuideClientConfiguration.class.getMethod(
                "guideRestClient", RestClient.Builder.class, GuideProperties.class);
        assertThat(method.getAnnotation(Bean.class)).isNotNull();
        assertThat(method.getAnnotation(Qualifier.class).value()).isEqualTo("guideRestClient");
        assertThat(GuideClientConfiguration.class.getDeclaredMethods())
                .filteredOn(candidate -> candidate.getAnnotation(Bean.class) != null)
                .hasSize(1);
    }

    @Test
    void loopbackGuideClientExplicitlyBypassesAmbientJvmProxies() {
        GuideProperties properties = new GuideProperties();
        GuideClientConfiguration configuration = new GuideClientConfiguration();

        SimpleClientHttpRequestFactory requestFactory = configuration.guideRequestFactory(750, 5_000);

        assertThat(ReflectionTestUtils.getField(requestFactory, "proxy"))
                .isSameAs(Proxy.NO_PROXY);
    }

    @Test
    void rejectsNonPositiveOrUnsupportedTimeouts() {
        GuideClientConfiguration configuration = new GuideClientConfiguration();
        GuideProperties properties = new GuideProperties();
        properties.setConnectTimeout(Duration.ZERO);

        assertThatThrownBy(() -> configuration.guideRestClient(RestClient.builder(), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofMillis((long) Integer.MAX_VALUE + 1L));
        assertThatThrownBy(() -> configuration.guideRestClient(RestClient.builder(), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported range");
    }

    @Test
    void rejectsOuterTimeoutThatCannotCoverTheBoundedProviderBudget() {
        GuideClientConfiguration configuration = new GuideClientConfiguration();
        GuideProperties properties = new GuideProperties();
        properties.setInternalReadTimeout(Duration.ofSeconds(370));

        assertThatThrownBy(() -> configuration.guideRestClient(RestClient.builder(), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deadline budget");
    }

    @Test
    void rejectsServiceUrlThatDivergesFromThePythonBindAddress() {
        GuideClientConfiguration configuration = new GuideClientConfiguration();
        GuideProperties properties = new GuideProperties();
        properties.setServiceUrl("http://127.0.0.1:5004");

        assertThatThrownBy(() -> configuration.guideRestClient(RestClient.builder(), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match");

        properties.setServiceUrl("http://127.0.0.1:5003");
        properties.setServiceHost("127.0.0.2");
        assertThatThrownBy(() -> configuration.guideRestClient(RestClient.builder(), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback literal");

        properties.setServiceHost("127.0.0.1");
        properties.setServiceUrl("http://localhost:5003");
        assertThatThrownBy(() -> configuration.guideRestClient(RestClient.builder(), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback literal");
    }

    @Test
    void sharedQuotedValuesNormalizeAndKnowledgeLimitsMatchPythonContract() {
        GuideProperties properties = new GuideProperties();
        properties.setInternalToken("'a=b'");
        assertThat(properties.getInternalToken()).isEqualTo("a=b");
        properties.setServiceUrl("");
        assertThat(properties.getServiceUrl()).isEqualTo("http://127.0.0.1:5003");
        properties.setServiceHost("::1");
        properties.setServicePort(5_004);
        assertThat(properties.getServiceUrl()).isEqualTo("http://[::1]:5004");

        properties.setMaxKnowledgeItems(6);
        properties.setMaxKnowledgeChars(8_001);
        properties.setSchemaVersion("2");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("maxKnowledgeItems", "maxKnowledgeChars", "schemaVersion");
        }
    }
}
