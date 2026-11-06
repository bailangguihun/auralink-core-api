package com.auralink.api.v1.knowledge;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Validate before accessing storage; all search text is literal, never SQL. */
public record PaintingMetricQuery(String metric, int page, int size, String q, String sort, String revision) {
   public static final Set<String> METRICS = Set.of("geography", "dynasty", "category", "style", "imagery", "sentiment", "sentimentByDynasty");
   private static final Set<String> PARAMETERS = Set.of("page", "size", "q", "sort", "revision");

   public PaintingMetricQuery {
      if (metric == null || !METRICS.contains(metric) || page < 0 || size < 1 || size > 200 || q == null || q.length() > 200
         || sort == null || !Set.of("value", "name", "chronological").contains(sort)
         || (revision != null && !revision.matches("[0-9a-f]{64}"))) throw invalid();
      q = q.trim().toLowerCase(Locale.ROOT);
   }

   public static PaintingMetricQuery parse(String metric, Map<String, String> parameters) {
      if (!PARAMETERS.containsAll(parameters.keySet())) throw invalid();
      return new PaintingMetricQuery(metric, integer(parameters.getOrDefault("page", "0")),
         integer(parameters.getOrDefault("size", "100")), parameters.getOrDefault("q", ""),
         parameters.getOrDefault("sort", "value"), parameters.get("revision"));
   }

   private static int integer(String value) {
      if (value == null || !value.matches("[0-9]{1,10}")) throw invalid();
      try { return Integer.parseInt(value); }
      catch (NumberFormatException exception) { throw invalid(); }
   }

   private static ApiV1Exception invalid() {
      return new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "统计查询参数无效");
   }
}
