package com.auralink.catalogread;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CatalogReadStore {
   com.auralink.api.v1.knowledge.PaintingStatisticsResponse paintingStatistics();

   CatalogReadStore.CatalogPage list(CatalogReadStore.CatalogQuery query);

   Optional<CatalogReadStore.CatalogPainting> findPainting(String publicId);

   List<CatalogReadStore.CatalogPainting> findPaintings(List<String> publicIds);

   Optional<CatalogReadStore.CatalogMediaAsset> findMedia(String publicId);

   record CatalogAnnotation(
      String fieldKey,
      String label,
      String type,
      String unit,
      String group,
      int order,
      String visibility,
      String filterability,
      String originalColumn,
      String rawValue,
      String normalizedValue,
      String valueStatus,
      String assertionStatus
   ) {
   }

   record CatalogMediaAsset(
      String publicId, String sourceBatchId, int sourceRevision, String relativePath, String storageUri, String contentSha256, String mimeType
   ) {
   }

   record CatalogPage(List<CatalogReadStore.CatalogPainting> items, int page, int size, long totalElements) {
      public CatalogPage {
         items = List.copyOf(items);
      }
   }

   record CatalogPainting(
      String publicId,
      String sourceRecordKey,
      int sourceRecordNumber,
      String sourceSequence,
      String imageStorageName,
      String title,
      String authorName,
      String category,
      String catalogStatus,
      String recordState,
      String imageDisplayStatus,
      CatalogReadStore.CatalogMediaAsset image,
      Map<String, String> values,
      List<CatalogReadStore.CatalogAnnotation> annotations
   ) {
      public CatalogPainting {
         values = Map.copyOf(values);
         annotations = List.copyOf(annotations);
      }

      public String value(String key) {
         return this.values.get(key);
      }

      public boolean imageReady() {
         return this.image != null && "READY".equals(this.imageDisplayStatus);
      }
   }

   record CatalogQuery(
      String keyword,
      String dynasty,
      String category,
      String author,
      String subject,
      String paintingSchool,
      String style,
      String artisticConception,
      String paintingMaterial,
      String collectionInstitution,
      String collectionPlatform,
      int page,
      int size,
      String sort,
      String direction,
      LocalDate orderDate
   ) {
      public CatalogQuery(String keyword, String dynasty, String category, String author, String subject,
         String paintingSchool, String style, String artisticConception, String paintingMaterial,
         String collectionInstitution, String collectionPlatform, int page, int size, String sort, String direction) {
         this(keyword, dynasty, category, author, subject, paintingSchool, style, artisticConception,
            paintingMaterial, collectionInstitution, collectionPlatform, page, size, sort, direction, null);
      }
   }
}
