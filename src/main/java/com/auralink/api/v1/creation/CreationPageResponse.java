package com.auralink.api.v1.creation;

import java.util.List;

import org.springframework.data.domain.Page;

/** Stable page envelope that does not expose Spring Data or JPA details. */
public record CreationPageResponse(
        List<CreationSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean hasNext) {

    public CreationPageResponse {
        items = List.copyOf(items);
    }

    public static CreationPageResponse from(Page<?> source, List<CreationSummaryResponse> items) {
        return new CreationPageResponse(
                items,
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.isFirst(),
                source.isLast(),
                source.hasNext());
    }
}
