package com.auralink.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** Safety and schema limits for private reusable workflow definitions. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "auralink.workflows")
public class WorkflowProperties {

    private boolean enabled = false;

    @Min(1)
    @Max(1)
    private int schemaVersion = 1;

    @Min(1_024)
    private int maxGraphBytes = 65_536;

    @Min(2)
    private int maxNodes = 16;

    @Min(1)
    private int maxEdges = 15;

    @Min(1)
    private int maxNameChars = 120;

    @Min(0)
    private int maxDescriptionChars = 2_000;
}
