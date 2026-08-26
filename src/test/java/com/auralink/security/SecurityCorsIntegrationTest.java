package com.auralink.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.auralink.config.CorsConfig;
import com.auralink.config.properties.CorsProperties;
import com.auralink.api.v1.error.ApiV1AuthenticationEntryPoint;
import com.auralink.security.jwt.JwtAuthenticationEntryPoint;
import com.auralink.security.jwt.JwtAuthenticationFilter;
import com.auralink.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        SecurityConfig.class,
        CorsConfig.class,
        SecurityCorsIntegrationTest.TestWebConfiguration.class
})
class SecurityCorsIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void springSecurityUsesConfiguredCorsSourceForAuthorizedPreflightHeaders() throws Exception {
        mockMvc.perform(options("/secured-test")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsStringIgnoringCase("Authorization")));
    }

    @Test
    void springSecurityRejectsPreflightFromUnconfiguredOrigin() throws Exception {
        mockMvc.perform(options("/secured-test")
                        .header("Origin", "https://unconfigured.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void mediaRangeHeadersAreAcceptedAndExposedByCors() throws Exception {
        mockMvc.perform(options("/api/v1/assets/00000000-0000-0000-0000-000000000000/content")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Range, If-None-Match"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsStringIgnoringCase("Range")))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.containsStringIgnoringCase("Content-Range")))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.containsStringIgnoringCase("ETag")));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class TestWebConfiguration {

        @Bean
        CorsProperties corsProperties() {
            CorsProperties properties = new CorsProperties();
            properties.setAllowedOrigins(List.of("http://localhost:3000"));
            return properties;
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            JwtTokenProvider tokenProvider = org.mockito.Mockito.mock(JwtTokenProvider.class);
            UserDetailsService userDetailsService = org.mockito.Mockito.mock(UserDetailsService.class);
            return new JwtAuthenticationFilter(tokenProvider, userDetailsService);
        }

        @Bean
        JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint() {
            return new JwtAuthenticationEntryPoint(new ObjectMapper());
        }

        @Bean
        ApiV1AuthenticationEntryPoint apiV1AuthenticationEntryPoint() {
            return new ApiV1AuthenticationEntryPoint(new ObjectMapper().findAndRegisterModules());
        }

        @Bean
        SecuredTestController securedTestController() {
            return new SecuredTestController();
        }
    }

    @RestController
    static class SecuredTestController {

        @GetMapping("/secured-test")
        String secured() {
            return "ok";
        }
    }
}
