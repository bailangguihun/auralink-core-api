package com.auralink.service.painting;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.api.v1.painting.PaintingPageResponse;
import com.auralink.catalog.DynastyNormalizer;
import com.auralink.catalogauth.CatalogAuthStore;
import com.auralink.catalogread.CatalogReadStore;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.entity.Painting;
import com.auralink.repository.PaintingFavoriteRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.service.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GalleryDailyOrderTest {
   private CatalogReadStore store;
   private ObjectProvider<CatalogReadStore> reads;
   private ObjectProvider<CatalogAuthStore> auth;
   private PaintingRepository paintings;
   private CurrentUserService users;
   private PaintingProperties properties;

   @BeforeEach
   @SuppressWarnings("unchecked")
   void setup() {
      store = mock(CatalogReadStore.class);
      reads = mock(ObjectProvider.class);
      auth = mock(ObjectProvider.class);
      paintings = mock(PaintingRepository.class);
      users = mock(CurrentUserService.class);
      properties = new PaintingProperties();
      when(reads.getIfAvailable()).thenReturn(store);
      when(users.findCurrentUser()).thenReturn(Optional.empty());
      when(store.list(any())).thenAnswer(invocation -> {
         CatalogReadStore.CatalogQuery query = invocation.getArgument(0);
         return new CatalogReadStore.CatalogPage(List.of(), query.page(), query.size(), 72);
      });
   }

   private PaintingQueryService service(String instant) {
      return new PaintingQueryService(paintings, mock(PaintingFavoriteRepository.class), users,
         mock(PaintingResponseMapper.class), properties, new DynastyNormalizer(), reads, auth,
         Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
   }

   private PaintingPageResponse list(PaintingQueryService service, int page, String sort, String direction, String date) {
      return service.listPaintings(null, null, null, null, null, null, null, null, null, null, null,
         page, 24, sort, direction, date);
   }

   private CatalogReadStore.CatalogQuery captured() {
      var capture = ArgumentCaptor.forClass(CatalogReadStore.CatalogQuery.class);
      verify(store).list(capture.capture());
      return capture.getValue();
   }

   @Test void initialPageUsesCoreShanghaiDateRatherThanUtcDate() {
      var result = list(service("2026-10-19T16:00:00Z"), 0, "daily", "asc", null);
      assertEquals("2026-10-20", result.orderDate());
      assertEquals("2026-10-20", captured().orderDate().toString());
   }

   @Test void sameDayAnchorKeepsRequestedPageAndDirection() {
      var result = list(service("2026-10-19T15:59:59Z"), 2, "daily", "DESC", "2026-10-19");
      assertEquals(2, result.page());
      assertEquals("2026-10-19", result.orderDate());
      var query = captured();
      assertEquals(2, query.page());
      assertEquals("daily", query.sort());
      assertEquals("desc", query.direction());
   }

   @Test void midnightResetsOldAnchorToTodaysFirstPage() {
      var result = list(service("2026-10-19T16:00:00Z"), 2, "daily", "asc", "2026-10-19");
      assertEquals(0, result.page());
      assertTrue(result.first());
      assertEquals("2026-10-20", result.orderDate());
      assertEquals(0, captured().page());
   }

   @Test void unanchoredLaterPageCannotMixAnUnknownEarlierOrder() {
      var result = list(service("2026-10-19T12:00:00Z"), 2, "daily", "asc", null);
      assertEquals(0, result.page());
      assertEquals(0, captured().page());
   }

   @Test void futureAnchorCannotSelectAnotherDaysSequence() {
      var result = list(service("2026-10-19T12:00:00Z"), 2, "daily", "asc", "2026-10-20");
      assertEquals(0, result.page());
      assertEquals("2026-10-19", result.orderDate());
      assertEquals("2026-10-19", captured().orderDate().toString());
   }

   @Test void configuredZoneDeterminesTheDay() {
      properties.setDailyZone(ZoneId.of("UTC"));
      assertEquals("2026-10-19", list(service("2026-10-19T16:00:00Z"), 0, "daily", "asc", null).orderDate());
   }

   @Test void malformedDatesAndInvalidDirectionsFailBeforeDatabaseReads() {
      var service = service("2026-10-19T12:00:00Z");
      for (String date : List.of("2026-02-30", "2026-1-01", "2026-10-19 ", "", "2026-10-19' OR 1=1")) {
         ApiV1Exception error = assertThrows(ApiV1Exception.class, () -> list(service, 1, "daily", "asc", date));
         assertEquals(ApiErrorCode.INVALID_PAINTING_QUERY, error.getCode());
      }
      assertThrows(ApiV1Exception.class, () -> list(service, 0, "daily", "random", null));
      verifyNoInteractions(store);
   }

   @Test void sourceOrderRetainsItsPageAndWireShape() throws Exception {
      var result = list(service("2026-10-19T12:00:00Z"), 2, "source", "asc", "not-a-date");
      assertEquals(2, result.page());
      assertNull(result.orderDate());
      assertNull(captured().orderDate());
      var wire = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result));
      assertFalse(wire.has("orderDate"));
      assertEquals(8, wire.size());
   }

   @Test void backwardsCompatibleConstructorsStillOmitDate() {
      var response = new PaintingPageResponse(List.of(), 0, 24, 0, 0, true, true, false);
      assertNull(response.orderDate());
      var query = new CatalogReadStore.CatalogQuery(null, null, null, null, null, null, null, null, null, null, null,
         0, 24, "source", "asc");
      assertNull(query.orderDate());
   }

   @Test void httpQueryBindsTheAnchorAndReturnsTheResetPage() throws Exception {
      var controller = new com.auralink.api.v1.painting.PaintingController(service("2026-10-19T16:00:00Z"),
         mock(PaintingFavoriteService.class));
      var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
         .setControllerAdvice(new com.auralink.api.v1.error.ApiV1ExceptionHandler()).build();
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/paintings")
            .param("sort", "daily").param("page", "2").param("orderDate", "2026-10-19"))
         .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
         .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.orderDate").value("2026-10-20"))
         .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page").value(0));
      assertEquals(0, captured().page());
   }

   @Test void legacyJpaSourceOrderRemainsAvailableAndDailyIsExplicitlyUnsupported() {
      when(reads.getIfAvailable()).thenReturn(null);
      when(paintings.findAll(any(Specification.class), any(Pageable.class)))
         .thenReturn(new PageImpl<Painting>(List.of(), org.springframework.data.domain.PageRequest.of(1, 24), 0));
      var service = service("2026-10-19T12:00:00Z");
      assertNull(list(service, 1, "source", "asc", null).orderDate());
      var capture = ArgumentCaptor.forClass(Pageable.class);
      verify(paintings).findAll(any(Specification.class), capture.capture());
      assertEquals(1, capture.getValue().getPageNumber());
      assertNotNull(capture.getValue().getSort().getOrderFor("sourceKey"));
      assertNotNull(capture.getValue().getSort().getOrderFor("publicId"));
      ApiV1Exception error = assertThrows(ApiV1Exception.class, () -> list(service, 0, "daily", "asc", null));
      assertEquals(ApiErrorCode.INVALID_SORT, error.getCode());
      verifyNoInteractions(store);
   }

   @Test void dailyRecommendationContinuesToUseSourceOrderWithoutDate() {
      var service = service("2026-10-19T12:00:00Z");
      // Empty mocked pages deliberately produce not-found after both source queries.
      assertThrows(ApiV1Exception.class, service::getDailyPainting);
      var capture = ArgumentCaptor.forClass(CatalogReadStore.CatalogQuery.class);
      verify(store, times(2)).list(capture.capture());
      for (var query : capture.getAllValues()) {
         assertEquals("source", query.sort());
         assertEquals("asc", query.direction());
         assertNull(query.orderDate());
      }
   }
}
