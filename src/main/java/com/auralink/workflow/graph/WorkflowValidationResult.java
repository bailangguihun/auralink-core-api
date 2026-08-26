package com.auralink.workflow.graph;

import java.util.List;

import com.auralink.api.v1.error.ApiViolationDetail;

/** Immutable validation outcome shared by every definition entry point. */
public record WorkflowValidationResult(
        boolean valid,
        String normalizedName,
        String normalizedDescription,
        WorkflowCanonicalization canonicalization,
        List<ApiViolationDetail> violations) {

    public WorkflowValidationResult {
        violations = List.copyOf(violations);
    }
}
