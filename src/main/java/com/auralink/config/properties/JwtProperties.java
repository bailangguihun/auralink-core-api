package com.auralink.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "auralink.jwt")
public class JwtProperties {

    private String secret;
    private long expiration = 604_800_000L;
}
