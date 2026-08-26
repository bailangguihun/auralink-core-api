package com.auralink.api.v1.painting;

import java.util.List;

import org.springframework.data.domain.Page;

/** Stable page envelope that does not expose Spring Data or JPA internals. */
public record PaintingPageResponse(
        List<PaintingSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean hasNext) {

    public PaintingPageResponse {
        items = List.copyOf(items);
    }

    public static PaintingPageResponse from(
            Page<?> source,
            List<PaintingSummaryResponse> items) {
        return new PaintingPageResponse(
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
