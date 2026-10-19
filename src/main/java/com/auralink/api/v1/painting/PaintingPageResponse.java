package com.auralink.api.v1.painting;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.data.domain.Page;

public record PaintingPageResponse(
   List<PaintingSummaryResponse> items, int page, int size, long totalElements, int totalPages, boolean first, boolean last, boolean hasNext,
   @JsonInclude(JsonInclude.Include.NON_NULL) String orderDate
) {
   public PaintingPageResponse {
      items = List.copyOf(items);
   }

   public PaintingPageResponse(List<PaintingSummaryResponse> items, int page, int size, long totalElements,
      int totalPages, boolean first, boolean last, boolean hasNext) {
      this(items, page, size, totalElements, totalPages, first, last, hasNext, null);
   }

   public PaintingPageResponse withOrderDate(String date) {
      return new PaintingPageResponse(items, page, size, totalElements, totalPages, first, last, hasNext, date);
   }

   public static PaintingPageResponse from(Page<?> source, List<PaintingSummaryResponse> items) {
      return new PaintingPageResponse(
         items, source.getNumber(), source.getSize(), source.getTotalElements(), source.getTotalPages(), source.isFirst(), source.isLast(), source.hasNext()
      );
   }
}
