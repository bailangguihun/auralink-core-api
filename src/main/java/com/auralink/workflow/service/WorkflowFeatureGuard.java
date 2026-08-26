package com.auralink.workflow.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.WorkflowProperties;

import lombok.RequiredArgsConstructor;

/** Consistent feature-disabled behavior for definition mutation and validation. */
@Component
@RequiredArgsConstructor
public class WorkflowFeatureGuard {

    private final WorkflowProperties properties;

    public void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ApiV1Exception(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.WORKFLOWS_DISABLED,
                    "工作流定义功能当前未启用");
        }
    }
}
