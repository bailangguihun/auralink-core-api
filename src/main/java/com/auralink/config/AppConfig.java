package com.auralink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.Collections;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        // 设置连接超时时间（15秒）
        factory.setConnectTimeout(15000);

        // 设置读取超时时间（120秒）- 增加到120秒以适应大型模型生成
        factory.setReadTimeout(120000);

        // 使用配置的工厂创建RestTemplate
        RestTemplate restTemplate = new RestTemplate(factory);

        return restTemplate;
    }

    @Bean(name = "springCorsFilter")
    public CorsFilter springCorsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        // 允许凭证
        config.setAllowCredentials(false); // 微信小程序通常不需要cookies

        // 允许所有来源（微信小程序请求通常没有标准的Origin头部）
        config.addAllowedOriginPattern("*");

        // 允许的HTTP方法
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH"));

        // 允许的头部（包含微信小程序可能发送的头部）
        config.setAllowedHeaders(Arrays.asList(
            "*" // 允许所有头部，包括微信小程序的特殊头部
        ));

        // 暴露的响应头
        config.setExposedHeaders(Arrays.asList(
            "Access-Control-Allow-Origin",
            "Access-Control-Allow-Methods",
            "Access-Control-Allow-Headers",
            "Access-Control-Max-Age",
            "Access-Control-Allow-Credentials",
            "Authorization",
            "Content-Disposition",
            "Content-Type",
            "Content-Length"
        ));

        // 预检请求的有效期，单位为秒
        config.setMaxAge(3600L);

        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    @Bean
    @ConfigurationProperties(prefix = "auralink.jwt")
    public JwtConfig jwtConfig() {
        return new JwtConfig();
    }

    @Bean
    @ConfigurationProperties(prefix = "auralink.service")
    public ServiceConfig serviceConfig() {
        return new ServiceConfig();
    }

    @Bean
    @ConfigurationProperties(prefix = "auralink.storage")
    public StorageConfig storageConfig() {
        return new StorageConfig();
    }

    @Bean
    @ConfigurationProperties(prefix = "auralink.paintings")
    public PaintingConfig paintingConfig() {
        return new PaintingConfig();
    }

    @Getter
    @Setter
    public static class JwtConfig {
        private String secret;
        private long expiration;
    }

    @Getter
    @Setter
    public static class ServiceConfig {
        private String vmmUrl;
        private String nonvmmUrl;
    }

    @Getter
    @Setter
    public static class StorageConfig {
        private String uploadDir;
        private String audioDir;
    }

    @Getter
    @Setter
    public static class PaintingConfig {
        private String metadataCsvPath;
        private String pictureDir;
        private String imageBaseUrl;
        private Integer defaultLimit = 500;
        private Integer maxLimit = 2000;
    }
}
