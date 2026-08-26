package com.auralink.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
        PaintingSecurityIntegrationTest.TestConfiguration.class
})
class PaintingSecurityIntegrationTest {

    private static final String PAINTING_ID = "00000000-0000-0000-0000-000000000000";

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
    void onlyExactGalleryListAndDailyGetRoutesAreAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/paintings"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/paintings/daily"))
                .andExpect(status().isOk());

        assertUnauthorized(get("/api/v1/paintings/{paintingId}", PAINTING_ID),
                "/api/v1/paintings/" + PAINTING_ID);
        assertUnauthorized(get("/api/v1/paintings/daily/extra"),
                "/api/v1/paintings/daily/extra");
        assertUnauthorized(head("/api/v1/paintings"), "/api/v1/paintings");
        assertUnauthorized(post("/api/v1/paintings"), "/api/v1/paintings");
    }

    @Test
    void favoriteMutationsAndCurrentUserFavoriteListRequireAuthentication() throws Exception {
        assertUnauthorized(
                put("/api/v1/paintings/{paintingId}/favorite", PAINTING_ID),
                "/api/v1/paintings/" + PAINTING_ID + "/favorite");
        assertUnauthorized(
                delete("/api/v1/paintings/{paintingId}/favorite", PAINTING_ID),
                "/api/v1/paintings/" + PAINTING_ID + "/favorite");
        assertUnauthorized(
                get("/api/v1/me/favorites/paintings"),
                "/api/v1/me/favorites/paintings");
    }

    @Test
    void authenticatedCallerCanReachEveryProtectedPaintingRoute() throws Exception {
        mockMvc.perform(get("/api/v1/paintings/{paintingId}", PAINTING_ID).with(user("owner")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/paintings/{paintingId}/favorite", PAINTING_ID).with(user("owner")))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/paintings/{paintingId}/favorite", PAINTING_ID).with(user("owner")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/favorites/paintings").with(user("owner")))
                .andExpect(status().isOk());
    }

    private void assertUnauthorized(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String expectedPath) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value(expectedPath));
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
        PaintingRouteTestController paintingRouteTestController() {
            return new PaintingRouteTestController();
        }

        @Bean
        FavoritePaintingRouteTestController favoritePaintingRouteTestController() {
            return new FavoritePaintingRouteTestController();
        }
    }

    @RestController
    @RequestMapping("/api/v1/paintings")
    static class PaintingRouteTestController {

        @GetMapping
        String list() {
            return "paintings";
        }

        @PostMapping
        String create() {
            return "not-public";
        }

        @GetMapping("/daily")
        String daily() {
            return "daily";
        }

        @GetMapping("/daily/extra")
        String dailyPrefixLookalike() {
            return "not-public";
        }

        @GetMapping("/{paintingId}")
        String detail(@PathVariable String paintingId) {
            return paintingId;
        }

        @PutMapping("/{paintingId}/favorite")
        String favorite(@PathVariable String paintingId) {
            return paintingId;
        }

        @DeleteMapping("/{paintingId}/favorite")
        String unfavorite(@PathVariable String paintingId) {
            return paintingId;
        }
    }

    @RestController
    @RequestMapping("/api/v1/me/favorites")
    static class FavoritePaintingRouteTestController {

        @GetMapping("/paintings")
        String favorites() {
            return "favorites";
        }
    }
}
