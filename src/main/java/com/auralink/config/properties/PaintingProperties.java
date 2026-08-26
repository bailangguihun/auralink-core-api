package com.auralink.config.properties;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "auralink.paintings")
public class PaintingProperties {

    private String metadataCsvPath = "../frontend/public/data/paintings.csv";
    private String pictureDir = "./picture";
    private String imageBaseUrl = "https://api.auralinks.top/api/paintings/images";
    private Integer defaultLimit = 500;
    private Integer maxLimit = 2_000;
    private boolean importEnabled = false;
    private boolean importFailOnError = true;

    @Min(1)
    @Max(1_000)
    private int importBatchSize = 100;

    private ZoneId dailyZone = ZoneId.of("Asia/Shanghai");
}
