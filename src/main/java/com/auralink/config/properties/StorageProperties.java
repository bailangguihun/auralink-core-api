package com.auralink.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "auralink.storage")
public class StorageProperties {

    private String uploadDir = "./temp_uploads";
    private String audioDir = "../VMM-frontend/project/public/audios";
    private String legacyFrontendAudioDir = "../frontend/public/audios";
}
