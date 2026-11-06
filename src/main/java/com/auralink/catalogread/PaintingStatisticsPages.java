package com.auralink.catalogread;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.api.v1.knowledge.PaintingMetricPage;
import com.auralink.api.v1.knowledge.PaintingMetricQuery;
import com.auralink.api.v1.knowledge.PaintingStatisticsResponse;
import com.auralink.api.v1.knowledge.PaintingStatisticsResponse.Item;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/** A single bounded public snapshot per store, never user/profile data.
 * Synchronization shares one aggregate read; failures do not fill the cache.
 */
final class PaintingStatisticsPages {
   private static final long TTL_NANOS = 30_000_000_000L;
   private static final List<String> DYNASTIES = List.of("先秦", "秦", "汉", "西汉", "东汉", "三国", "魏晋", "西晋", "东晋",
      "南北朝", "隋", "唐", "五代", "五代十国", "宋", "北宋", "南宋", "辽", "金", "元", "明", "清", "民国", "近现代", "现代", "当代");
   private final Supplier<PaintingStatisticsResponse> loader;
   private final LongSupplier clock;
   private Snapshot snapshot;

   PaintingStatisticsPages(Supplier<PaintingStatisticsResponse> loader) { this(loader, System::nanoTime); }
   PaintingStatisticsPages(Supplier<PaintingStatisticsResponse> loader, LongSupplier clock) {
      this.loader = loader;
      this.clock = clock;
   }

   PaintingMetricPage read(PaintingMetricQuery query) {
      Snapshot current = current();
      if (query.revision() != null && !query.revision().equals(current.revision())) {
         throw new ApiV1Exception(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, "统计数据已更新，请重新读取");
      }
      List<Item> items = current.response().metrics().get(query.metric()).items().stream()
         .filter(item -> query.q().isEmpty() || item.name().toLowerCase(Locale.ROOT).contains(query.q()))
         .sorted(order(query.sort())).toList();
      int totalPages = (items.size() + query.size() - 1) / query.size();
      long requestedStart = (long) query.page() * query.size();
      int start = (int) Math.min(items.size(), requestedStart);
      int end = Math.min(items.size(), start + query.size());
      return new PaintingMetricPage(1, query.metric(), items.subList(start, end), query.page(), query.size(),
         items.size(), totalPages, end < items.size(), current.revision());
   }

   private synchronized Snapshot current() {
      long now = clock.getAsLong();
      if (snapshot != null && now - snapshot.loadedAt() < TTL_NANOS) return snapshot;
      refresh();
      return snapshot;
   }

   /** An overview explicitly reads fresh data and seeds the same page snapshot. */
   synchronized PaintingStatisticsResponse refresh() {
      // Drop expired data before attempting the refresh. A failed refresh cannot
      // serve stale statistics or retain an unbounded history of snapshots.
      snapshot = null;
      PaintingStatisticsResponse response = loader.get();
      Snapshot loaded = new Snapshot(response, revision(response), clock.getAsLong());
      snapshot = loaded;
      return response;
   }

   private static Comparator<Item> order(String sort) {
      Comparator<Item> name = Comparator.comparing(Item::name);
      return switch (sort) {
         case "name" -> name;
         case "chronological" -> Comparator.comparingInt((Item item) -> dynastyPosition(item.name())).thenComparing(name);
         default -> Comparator.comparingDouble(Item::value).reversed().thenComparing(name);
      };
   }

   private static int dynastyPosition(String name) {
      int position = DYNASTIES.indexOf(name);
      if (position < 0) position = DYNASTIES.indexOf(name.replaceFirst("[朝代]$", ""));
      return position < 0 ? Integer.MAX_VALUE : position;
   }

   private static String revision(PaintingStatisticsResponse response) {
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         add(digest, Long.toString(response.population().count()));
         add(digest, Long.toString(response.authorNames().count()));
         add(digest, Long.toString(response.authorNames().eligibleRecords()));
         response.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            add(digest, entry.getKey());
            var metric = entry.getValue();
            add(digest, metric.status());
            add(digest, Long.toString(metric.eligibleRecords()));
            add(digest, Long.toString(metric.missingOrRestrictedRecords()));
            add(digest, Long.toString(metric.unmatchedRecords()));
            metric.items().stream().sorted(Comparator.comparing(Item::name)).forEach(item -> {
               add(digest, item.name());
               add(digest, Double.toString(item.value()));
            });
         });
         return HexFormat.of().formatHex(digest.digest());
      } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
   }

   private static void add(MessageDigest digest, String text) {
      byte[] value = text.getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
      digest.update(value);
   }

   private record Snapshot(PaintingStatisticsResponse response, String revision, long loadedAt) {}
}
