package com.auralink.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "auralink.service")
public class ServiceProperties {

    private String vmmUrl = "http://localhost:5001";
    private String nonvmmUrl = "http://localhost:5002";

    public String getQwenServiceUrl() {
        return nonvmmUrl;
    }
}
