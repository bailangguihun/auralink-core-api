package com.auralink.api.v1.knowledge;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.api.v1.error.ApiV1ExceptionHandler;
import com.auralink.catalogread.CatalogReadStore;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC/error contract; real SecurityConfig authentication is separately exercised by local HTTP acceptance. */
class PaintingStatisticsControllerTest {
   @SuppressWarnings("unchecked")
   private static ObjectProvider<CatalogReadStore> provider(CatalogReadStore store) {
      var provider = (ObjectProvider<CatalogReadStore>) mock(ObjectProvider.class);
      when(provider.getIfAvailable()).thenReturn(store);
      return provider;
   }

   @Test void rejectsEveryQueryParameterBeforeStoreAccess() throws Exception {
      var store=mock(CatalogReadStore.class);
      var mvc=MockMvcBuilders.standaloneSetup(new PaintingStatisticsController(provider(store)))
         .setControllerAdvice(new ApiV1ExceptionHandler()).build();
      mvc.perform(get("/api/v1/knowledge/painting-statistics").param("page","0"))
         .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
      verifyNoInteractions(store);
   }

   @Test void disabledStoreIsExplicitlyUnavailable() throws Exception {
      var mvc=MockMvcBuilders.standaloneSetup(new PaintingStatisticsController(provider(null)))
         .setControllerAdvice(new ApiV1ExceptionHandler()).build();
      mvc.perform(get("/api/v1/knowledge/painting-statistics"))
         .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CATALOG_READ_UNAVAILABLE"));
   }

   @Test void returnsContractAndPreventsCaching() throws Exception {
      var store=mock(CatalogReadStore.class);
      when(store.paintingStatistics()).thenReturn(new PaintingStatisticsResponse(1,
         new PaintingStatisticsResponse.Population("gallery-displayable","Gallery 可展示画作",0),
         new PaintingStatisticsResponse.AuthorNames(0,0),Map.of()));
      var mvc=MockMvcBuilders.standaloneSetup(new PaintingStatisticsController(provider(store))).build();
      mvc.perform(get("/api/v1/knowledge/painting-statistics"))
         .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
         .andExpect(jsonPath("$.schemaVersion").value(1)).andExpect(jsonPath("$.population.count").value(0));
   }

   @Test void storageFailureIsJsonWithoutSqlOrConnectionDetails() throws Exception {
      var store=mock(CatalogReadStore.class);
      when(store.paintingStatistics()).thenThrow(new ApiV1Exception(HttpStatus.SERVICE_UNAVAILABLE,
         ApiErrorCode.CATALOG_READ_UNAVAILABLE,"画作目录暂时不可用"));
      var mvc=MockMvcBuilders.standaloneSetup(new PaintingStatisticsController(provider(store)))
         .setControllerAdvice(new ApiV1ExceptionHandler()).build();
      mvc.perform(get("/api/v1/knowledge/painting-statistics"))
         .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CATALOG_READ_UNAVAILABLE"))
         .andExpect(jsonPath("$.message").value("画作目录暂时不可用"));
   }

   @Test void metricPageReturnsFullCountAndRevisionWithoutHttpCaching() throws Exception {
      var store = mock(CatalogReadStore.class);
      when(store.paintingMetric(any())).thenReturn(new PaintingMetricPage(1, "style",
         List.of(new PaintingStatisticsResponse.Item("低频词", 1)), 67, 100, 6762, 68, false, "a".repeat(64)));
      var mvc = MockMvcBuilders.standaloneSetup(new PaintingStatisticsController(provider(store))).build();
      mvc.perform(get("/api/v1/knowledge/painting-statistics/style").param("page", "67").param("size", "100"))
         .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
         .andExpect(jsonPath("$.totalItems").value(6762)).andExpect(jsonPath("$.revision").value("a".repeat(64)))
         .andExpect(jsonPath("$.items[0].name").value("低频词")).andExpect(jsonPath("$.hasMore").value(false));
      verify(store).paintingMetric(new PaintingMetricQuery("style", 67, 100, "", "value", null));
   }

   @Test void metricParametersAreValidatedBeforeStorageAccess() throws Exception {
      var store = mock(CatalogReadStore.class);
      var mvc = MockMvcBuilders.standaloneSetup(new PaintingStatisticsController(provider(store)))
         .setControllerAdvice(new ApiV1ExceptionHandler()).build();
      for (var parameters : List.of(Map.of("page", "-1"), Map.of("page", "2147483648"), Map.of("page", "1.5"),
         Map.of("size", "0"), Map.of("size", "201"), Map.of("sort", "random"), Map.of("q", "a".repeat(201)),
         Map.of("revision", "invalid"), Map.of("token", "unsupported"))) {
         var request = get("/api/v1/knowledge/painting-statistics/style");
         parameters.forEach(request::param);
         mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
      }
      mvc.perform(get("/api/v1/knowledge/painting-statistics/unknown"))
         .andExpect(status().isBadRequest());
      verifyNoInteractions(store);
   }

   @Test void metricDefaultsAndPinnedRevisionReachStore() throws Exception {
      var store = mock(CatalogReadStore.class);
      when(store.paintingMetric(any())).thenReturn(new PaintingMetricPage(1, "imagery", List.of(), 0, 100, 0, 0, false, "a".repeat(64)));
      var mvc = MockMvcBuilders.standaloneSetup(new PaintingStatisticsController(provider(store))).build();
      mvc.perform(get("/api/v1/knowledge/painting-statistics/imagery").param("q", "  ALPHA%_  ").param("revision", "a".repeat(64)))
         .andExpect(status().isOk()).andExpect(jsonPath("$.totalPages").value(0));
      verify(store).paintingMetric(new PaintingMetricQuery("imagery", 0, 100, "alpha%_", "value", "a".repeat(64)));
   }

   @Test void revisionConflictIsExplicitAndDisabledStoreRemainsUnavailable() throws Exception {
      var store = mock(CatalogReadStore.class);
      when(store.paintingMetric(any())).thenThrow(new ApiV1Exception(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, "统计数据已更新，请重新读取"));
      var mvc = MockMvcBuilders.standaloneSetup(new PaintingStatisticsController(provider(store)))
         .setControllerAdvice(new ApiV1ExceptionHandler()).build();
      mvc.perform(get("/api/v1/knowledge/painting-statistics/style").param("revision", "a".repeat(64)))
         .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
      var disabled = MockMvcBuilders.standaloneSetup(new PaintingStatisticsController(provider(null)))
         .setControllerAdvice(new ApiV1ExceptionHandler()).build();
      disabled.perform(get("/api/v1/knowledge/painting-statistics/style"))
         .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CATALOG_READ_UNAVAILABLE"));
   }
}
