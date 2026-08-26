package com.auralink.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;

import com.auralink.api.v1.error.ApiV1AuthenticationEntryPoint;
import com.auralink.security.jwt.JwtAuthenticationFilter;
import com.auralink.security.jwt.JwtAuthenticationEntryPoint;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final ApiV1AuthenticationEntryPoint apiV1AuthenticationEntryPoint;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .exceptionHandling(exc -> exc
                .defaultAuthenticationEntryPointFor(
                    apiV1AuthenticationEntryPoint,
                    new AntPathRequestMatcher("/api/v1/**"))
                .defaultAuthenticationEntryPointFor(
                    jwtAuthenticationEntryPoint,
                    AnyRequestMatcher.INSTANCE))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                // 处理预检请求
                .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // 公开API
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/health", "/api/health/**").permitAll()
                .requestMatchers("/health", "/health/**").permitAll()
                .requestMatchers("/models").permitAll()
                .requestMatchers("/api/models").permitAll()
                .requestMatchers("/api/audios/**").permitAll()
                .requestMatchers("/api/files/**").permitAll()
                .requestMatchers("/api/paintings/**").permitAll()
                .requestMatchers("/api/upload-session/**").permitAll()

                // MediaAsset public reads still run the JWT filter so owners can
                // access private assets when an Authorization header is supplied.
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/assets/{assetId}",
                    "/api/v1/assets/{assetId}/content",
                    "/api/v1/assets/{assetId}/download").permitAll()
                .requestMatchers(HttpMethod.HEAD,
                    "/api/v1/assets/{assetId}",
                    "/api/v1/assets/{assetId}/content",
                    "/api/v1/assets/{assetId}/download").permitAll()

                // Private workflow definitions and authenticated capability discovery.
                .requestMatchers(
                    "/api/v1/workflow/**",
                    "/api/v1/me/workflows",
                    "/api/v1/me/workflows/**").authenticated()

                // Fixed public painting routes must precede the dynamic detail
                // matcher so /daily cannot be interpreted as a painting UUID.
                // The JWT filter still runs, allowing optional favorite state.
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/paintings",
                    "/api/v1/paintings/daily").permitAll()

                // 需要身份验证的API
                .requestMatchers("/api/upload-image").authenticated()
                .requestMatchers("/api/describe-image").authenticated()
                .requestMatchers("/api/generate-music").authenticated()
                .requestMatchers("/api/cleanup").authenticated()
                .requestMatchers("/describe_image").authenticated()
                .requestMatchers("/generate").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/assets/uploads").authenticated()
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/paintings/{paintingId}",
                    "/api/v1/paintings/{paintingId}/guide",
                    "/api/v1/me/favorites/paintings").authenticated()
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/paintings/{paintingId}/guide",
                    "/api/v1/paintings/{paintingId}/guide/audio").authenticated()
                .requestMatchers(HttpMethod.PUT,
                    "/api/v1/paintings/{paintingId}/favorite").authenticated()
                .requestMatchers(HttpMethod.DELETE,
                    "/api/v1/paintings/{paintingId}/favorite").authenticated()

                // 默认规则
                .anyRequest().authenticated()
            );

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
