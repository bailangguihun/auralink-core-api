package com.auralink.api.v1.workflow;

import java.util.List;

import org.springframework.data.domain.Page;

/** Stable page envelope that does not serialize Spring Data internals. */
public record WorkflowPageResponse(
        List<WorkflowSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean hasNext) {

    public WorkflowPageResponse {
        items = List.copyOf(items);
    }

    public static WorkflowPageResponse from(
            Page<?> page,
            List<WorkflowSummaryResponse> items) {
        return new WorkflowPageResponse(
                items,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.hasNext());
    }
}
