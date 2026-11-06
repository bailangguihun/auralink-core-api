package com.auralink.api.v1.knowledge;

import java.util.List;

/** One bounded window over a complete public aggregate snapshot. */
public record PaintingMetricPage(
   int schemaVersion, String metric, List<PaintingStatisticsResponse.Item> items,
   int page, int size, int totalItems, int totalPages, boolean hasMore, String revision
) {
   public PaintingMetricPage { items = List.copyOf(items); }
}
