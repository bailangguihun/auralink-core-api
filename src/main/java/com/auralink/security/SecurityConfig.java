package com.auralink.security;

import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.web.cors.CorsUtils;

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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            // 不使用Spring Security的CORS配置，而是完全依赖我们的CustomCorsFilter
            .cors(cors -> cors.disable())
            .exceptionHandling(exc -> exc.authenticationEntryPoint(jwtAuthenticationEntryPoint))
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

                // 需要身份验证的API
                .requestMatchers("/api/upload-image").authenticated()
                .requestMatchers("/api/describe-image").authenticated()
                .requestMatchers("/api/generate-music").authenticated()
                .requestMatchers("/api/cleanup").authenticated()
                .requestMatchers("/describe_image").authenticated()
                .requestMatchers("/generate").authenticated()

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
