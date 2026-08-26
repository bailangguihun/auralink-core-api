package com.auralink.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import com.auralink.config.properties.CorsProperties;

class CorsConfigTest {

    private CorsProperties properties;
    private CorsFilter filter;

    @BeforeEach
    void setUp() {
        properties = new CorsProperties();
        properties.setAllowedOrigins(List.of(
                "http://localhost:3000",
                "https://fanhualy.top",
                "https://*.fanhualy.top"));
        CorsConfigurationSource source = new CorsConfig(properties).corsConfigurationSource();
        filter = new CorsFilter(source);
    }

    @Test
    void allowsLegacyFrontendOriginWithCredentials() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader("Origin", "http://localhost:3000");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void rejectsPreflightFromUnconfiguredOrigin() throws Exception {
        MockHttpServletRequest request = preflight("https://unconfigured.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void handlesAuthorizationPreflightWithConfiguredMethodsAndHeaders() throws Exception {
        MockHttpServletRequest request = preflight("https://fanhualy.top");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://fanhualy.top");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("POST");
        assertThat(response.getHeader("Access-Control-Allow-Headers"))
                .containsIgnoringCase("Authorization")
                .containsIgnoringCase("Content-Type");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void acceptsConfiguredLegacySubdomainPattern() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader("Origin", "https://app.fanhualy.top");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://app.fanhualy.top");
    }

    @Test
    void refusesCredentialedGlobalWildcard() {
        properties.setAllowedOrigins(List.of("*"));
        properties.setAllowCredentials(true);

        assertThatThrownBy(() -> new CorsConfig(properties).corsConfigurationSource())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    private MockHttpServletRequest preflight(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/upload-image");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "Authorization, Content-Type");
        return request;
    }
}
