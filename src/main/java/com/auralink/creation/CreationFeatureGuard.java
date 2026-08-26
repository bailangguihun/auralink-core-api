package com.auralink.creation;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.CreationExecutionProperties;

import lombok.RequiredArgsConstructor;

/** Consistent disabled-feature boundary for all Creation admission paths. */
@Component
@RequiredArgsConstructor
public class CreationFeatureGuard {

    private final CreationExecutionProperties properties;

    public void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ApiV1Exception(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.CREATIONS_DISABLED,
                    "创作执行功能当前未启用");
        }
    }
}
