package com.auralink.api.v1.knowledge;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.catalogread.CatalogReadStore;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authentication follows SecurityConfig's authenticated /api/v1 default. */
@RestController
public class PaintingStatisticsController {
   private final ObjectProvider<CatalogReadStore> stores;

   public PaintingStatisticsController(ObjectProvider<CatalogReadStore> stores) { this.stores = stores; }

   @GetMapping("/api/v1/knowledge/painting-statistics")
   public ResponseEntity<PaintingStatisticsResponse> statistics(@RequestParam Map<String, String> parameters) {
      if (!parameters.isEmpty()) {
         throw new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "统计接口不接受筛选或分页参数");
      }
      CatalogReadStore store = stores.getIfAvailable();
      if (store == null) {
         throw new ApiV1Exception(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.CATALOG_READ_UNAVAILABLE,
            "PostgreSQL 画作统计尚未启用");
      }
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.paintingStatistics());
   }

   @GetMapping("/api/v1/knowledge/painting-statistics/{metric}")
   public ResponseEntity<PaintingMetricPage> metric(@PathVariable String metric, @RequestParam Map<String, String> parameters) {
      PaintingMetricQuery query = PaintingMetricQuery.parse(metric, parameters);
      CatalogReadStore store = stores.getIfAvailable();
      if (store == null) {
         throw new ApiV1Exception(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.CATALOG_READ_UNAVAILABLE,
            "PostgreSQL 画作统计尚未启用");
      }
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.paintingMetric(query));
   }
}
