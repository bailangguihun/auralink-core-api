package com.auralink.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;

import com.auralink.api.v1.error.ApiV1AuthenticationEntryPoint;
import com.auralink.security.jwt.JwtAuthenticationEntryPoint;
import com.auralink.security.jwt.JwtAuthenticationFilter;
import com.auralink.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        SecurityConfig.class,
        MediaAssetSecurityIntegrationTest.TestConfiguration.class
})
class MediaAssetSecurityIntegrationTest {

    private static final String ASSET_ID = "00000000-0000-0000-0000-000000000000";

    @jakarta.annotation.Resource
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void exactAssetReadRoutesAreAnonymousButOtherMethodsAreNot() throws Exception {
        mockMvc.perform(get("/api/v1/assets/{id}", ASSET_ID)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/assets/{id}/content", ASSET_ID)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/assets/{id}/download", ASSET_ID)).andExpect(status().isOk());
        mockMvc.perform(head("/api/v1/assets/{id}/content", ASSET_ID)).andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/assets/{id}", ASSET_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void uploadRequiresAuthenticationAndUsesV1ErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/assets/uploads"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/v1/assets/uploads"));
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class TestConfiguration {

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return request -> null;
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
        AssetRouteTestController assetRouteTestController() {
            return new AssetRouteTestController();
        }
    }

    @RestController
    @RequestMapping("/api/v1/assets")
    static class AssetRouteTestController {

        @GetMapping("/{assetId}")
        String metadata(@PathVariable String assetId) {
            return assetId;
        }

        @GetMapping("/{assetId}/content")
        String content(@PathVariable String assetId) {
            return assetId;
        }

        @GetMapping("/{assetId}/download")
        String download(@PathVariable String assetId) {
            return assetId;
        }

        @PostMapping("/uploads")
        String upload() {
            return "uploaded";
        }

        @PutMapping("/{assetId}")
        String update(@PathVariable String assetId) {
            return assetId;
        }
    }
}
