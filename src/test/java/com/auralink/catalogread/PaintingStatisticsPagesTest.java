package com.auralink.catalogread;

import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.api.v1.knowledge.PaintingMetricQuery;
import com.auralink.api.v1.knowledge.PaintingStatisticsResponse;
import com.auralink.api.v1.knowledge.PaintingStatisticsResponse.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class PaintingStatisticsPagesTest {
   private static PaintingStatisticsResponse snapshot(List<Item> items) {
      var metric = new PaintingStatisticsResponse.Metric("available", null, 1000, 0, 0, items, false, "幅次", "fixture");
      return new PaintingStatisticsResponse(1, new PaintingStatisticsResponse.Population("gallery-displayable", "fixture", 1000),
         new PaintingStatisticsResponse.AuthorNames(20, 1000), Map.of("style", metric));
   }

   private static PaintingMetricQuery query(int page, int size, String q, String sort, String revision) {
      return new PaintingMetricQuery("style", page, size, q, sort, revision);
   }

   @Test void traversesEveryItemBeyondLegacyAndParserLimitsWithoutDuplicates() {
      var source = IntStream.range(0, 417).mapToObj(i -> new Item(String.format("词%04d", i), 417 - i)).toList();
      var calls = new AtomicInteger();
      var pages = new PaintingStatisticsPages(() -> { calls.incrementAndGet(); return snapshot(source); });
      var first = pages.read(query(0, 100, "", "value", null));
      assertEquals(417, first.totalItems());
      assertEquals(5, first.totalPages());
      assertEquals(64, first.revision().length());
      var names = new ArrayList<String>();
      for (int i = 0; i < first.totalPages(); i++) {
         var page = pages.read(query(i, 100, "", "value", first.revision()));
         assertEquals(i < 4, page.hasMore());
         names.addAll(page.items().stream().map(Item::name).toList());
      }
      assertEquals(source.stream().map(Item::name).toList(), names);
      assertEquals(1, calls.get());
      var outside = pages.read(query(Integer.MAX_VALUE, 200, "", "value", first.revision()));
      assertTrue(outside.items().isEmpty());
      assertEquals(Integer.MAX_VALUE, outside.page());
      assertFalse(outside.hasMore());
   }

   @Test void searchIncludesTailAndTreatsWildcardsAsLiteralCharacters() {
      var pages = new PaintingStatisticsPages(() -> snapshot(List.of(new Item("Alpha_100%", 1), new Item("ALPHABET", 100), new Item("tail", .5))));
      assertEquals(List.of("Alpha_100%", "ALPHABET"), pages.read(query(0, 100, " alpha ", "value", null)).items()
         .stream().sorted(java.util.Comparator.comparingDouble(Item::value)).map(Item::name).toList());
      assertEquals(List.of(new Item("Alpha_100%", 1)), pages.read(query(0, 100, "_100%", "name", null)).items());
      assertEquals(List.of(new Item("tail", .5)), pages.read(query(0, 100, "TAIL", "value", null)).items());
      var empty = pages.read(query(0, 100, "absent", "value", null));
      assertEquals(0, empty.totalItems());
      assertEquals(0, empty.totalPages());
      assertFalse(empty.hasMore());
   }

   @Test void sortHasStableTieBreaksAndPreservesUnrecognizedDynastyText() {
      var pages = new PaintingStatisticsPages(() -> snapshot(List.of(new Item("清代", 7), new Item("宋代", 9),
         new Item("唐代", 7), new Item("清末某年", 7), new Item("未识别原值", 7))));
      var byTime = pages.read(query(0, 100, "", "chronological", null)).items().stream().map(Item::name).toList();
      assertEquals(List.of("唐代", "宋代", "清代", "未识别原值", "清末某年"), byTime);
      var byValue = pages.read(query(0, 100, "", "value", null)).items();
      assertEquals("宋代", byValue.get(0).name());
      assertEquals(List.of("唐代", "未识别原值", "清代", "清末某年"), byValue.subList(1, byValue.size()).stream().map(Item::name).toList());
   }

   @Test void refreshKeepsRevisionForSameValuesAndRejectsChangedSnapshot() {
      var time = new AtomicLong();
      var version = new AtomicInteger();
      var pages = new PaintingStatisticsPages(() -> snapshot(List.of(new Item("公开词", version.get()))), time::get);
      String first = pages.read(query(0, 100, "", "value", null)).revision();
      time.set(30_000_000_000L);
      assertEquals(first, pages.read(query(0, 100, "", "value", first)).revision());
      version.incrementAndGet(); time.set(60_000_000_000L);
      ApiV1Exception conflict = assertThrows(ApiV1Exception.class, () -> pages.read(query(1, 100, "", "value", first)));
      assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
      assertNotEquals(first, pages.read(query(0, 100, "", "value", null)).revision());
   }

   @Test void failedReadIsNotCachedAndExpiredDataIsNotServed() {
      var time = new AtomicLong();
      var calls = new AtomicInteger();
      var pages = new PaintingStatisticsPages(() -> {
         if (calls.incrementAndGet() == 2) throw new IllegalStateException("failed refresh");
         return snapshot(List.of(new Item("公开词", calls.get())));
      }, time::get);
      pages.read(query(0, 100, "", "value", null));
      time.set(30_000_000_000L);
      assertThrows(IllegalStateException.class, () -> pages.read(query(0, 100, "", "value", null)));
      assertEquals(3, pages.read(query(0, 100, "", "value", null)).items().get(0).value());
      assertEquals(3, calls.get());
   }

   @Test void freshOverviewSeedsPagesAndStillObservesChangedValues() {
      var calls = new AtomicInteger();
      var pages = new PaintingStatisticsPages(() -> snapshot(List.of(new Item("词", calls.incrementAndGet()))));
      assertEquals(1, pages.refresh().metrics().get("style").items().get(0).value());
      String firstRevision = pages.read(query(0, 100, "", "value", null)).revision();
      assertEquals(1, calls.get(), "Page following the overview must reuse its complete aggregation");
      assertEquals(2, pages.refresh().metrics().get("style").items().get(0).value());
      assertNotEquals(firstRevision, pages.read(query(0, 100, "", "value", null)).revision());
      assertEquals(2, calls.get());
   }

   @Test void simultaneousPagesShareOneAggregateRead() throws Exception {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var calls = new AtomicInteger();
      var pages = new PaintingStatisticsPages(() -> {
         calls.incrementAndGet(); entered.countDown();
         try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
         catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
         return snapshot(List.of(new Item("词", 1)));
      });
      var executor = Executors.newFixedThreadPool(4);
      try {
         var first = executor.submit(() -> pages.read(query(0, 100, "", "value", null)));
         assertTrue(entered.await(5, TimeUnit.SECONDS));
         var second = executor.submit(() -> pages.read(query(0, 100, "", "name", null)));
         var third = executor.submit(() -> pages.read(query(0, 100, "词", "value", null)));
         release.countDown();
         assertEquals(first.get(5, TimeUnit.SECONDS).revision(), second.get(5, TimeUnit.SECONDS).revision());
         assertEquals(first.get().revision(), third.get(5, TimeUnit.SECONDS).revision());
         assertEquals(1, calls.get());
      } finally { release.countDown(); executor.shutdownNow(); }
   }
}
