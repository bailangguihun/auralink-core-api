package com.auralink.catalogread;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

final class PostgresCatalogReadStore implements CatalogReadStore {
   static final String CURRENT_SOURCE = "FROM catalog_batch_current_revisions current_revision\nJOIN catalog_source_records sr\n  ON sr.batch_id = current_revision.batch_id AND sr.revision = current_revision.revision\nJOIN catalog_paintings p ON p.public_id = sr.painting_id\n  -- A stable painting may retain source membership in several batches.\n  -- Its own current revision is the canonical Gallery representation,\n  -- so a second batch head cannot produce a duplicate Gallery row.\n AND p.current_batch_id = sr.batch_id AND p.current_revision = sr.revision\nLEFT JOIN catalog_media_assets ma ON ma.public_id = p.image_asset_id\n";
   static final String GALLERY_WHERE = " WHERE sr.painting_id IS NOT NULL AND p.catalog_status = 'ACTIVE' AND p.image_display_status = 'READY'";
   private static final String BASE_SELECT = "SELECT p.public_id::text AS painting_id,\n       sr.source_record_key,\n       sr.record_number,\n       p.title,\n       p.author_name,\n       p.category,\n       p.catalog_status,\n       p.image_display_status,\n       sr.record_state,\n       ma.public_id::text AS image_asset_id,\n       ma.source_batch_id::text AS image_batch_id,\n       ma.source_revision AS image_revision,\n       ma.relative_path AS image_relative_path,\n       ma.storage_uri AS image_storage_uri,\n       ma.content_sha256 AS image_sha256,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.sequence') AS legacy_sequence,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.image_storage_name') AS legacy_image_storage_name,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'source.external_catalogue_id') AS external_catalogue_id,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'source.relative_image') AS relative_image,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.author_birth_year_text') AS author_birth_year,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.author_birth_place') AS author_birth_place,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.author_school') AS author_school,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.creation_year_text') AS creation_year,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.creation_dynasty_raw') AS creation_dynasty_raw,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.actual_size') AS actual_size,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.collection_institution') AS collection_institution,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.subject') AS subject,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.painting_school') AS painting_school,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.style') AS style,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.color') AS color,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.composition') AS composition,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.artistic_conception') AS artistic_conception,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.brushwork') AS brushwork,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.ink_method') AS ink_method,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.painting_material') AS painting_material,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.pigment') AS pigment,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.seal') AS seal,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.cultural_symbol') AS cultural_symbol,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.generated_text') AS generated_text,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.music_scene_description') AS music_scene_description,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.collection_platform') AS collection_platform\n";
   private static final String GROUP_BY = "GROUP BY p.public_id, sr.batch_id, sr.revision, sr.annotation_file_id, sr.record_number,\n         sr.source_record_key, sr.record_state, ma.public_id, ma.source_batch_id, ma.source_revision,\n         ma.relative_path, ma.storage_uri, ma.content_sha256\n";
   private final NamedParameterJdbcTemplate jdbc;

   PostgresCatalogReadStore(NamedParameterJdbcTemplate jdbc) {
      this.jdbc = jdbc;
   }

   @Override
   public CatalogReadStore.CatalogPage list(CatalogReadStore.CatalogQuery query) {
      MapSqlParameterSource parameters = this.parameters(query);
      String where = this.listWhere(query, parameters);
      long total = this.scalarLong("SELECT count(*) " + CURRENT_SOURCE + where, parameters);
      if (total == 0L) {
         return new CatalogReadStore.CatalogPage(List.of(), query.page(), query.size(), 0L);
      }

      String sql = BASE_SELECT + CURRENT_SOURCE
         + " LEFT JOIN catalog_annotation_revisions ar ON ar.batch_id = sr.batch_id AND ar.revision = sr.revision AND ar.annotation_file_id = sr.annotation_file_id AND ar.record_number = sr.record_number "
         + where + GROUP_BY + orderBy(query) + " LIMIT :limit OFFSET :offset";
      parameters.addValue("limit", query.size());
      parameters.addValue("offset", Math.multiplyExact(query.page(), query.size()));
      List<CatalogReadStore.CatalogPainting> rows = this.query(sql, parameters, this::mapPaintingBase);
      return new CatalogReadStore.CatalogPage(rows, query.page(), query.size(), total);
   }

   @Override
   public com.auralink.api.v1.knowledge.PaintingStatisticsResponse paintingStatistics() {
      try {
         return new PostgresPaintingStatistics(this.jdbc.getJdbcTemplate()).read();
      } catch (DataAccessException exception) {
         throw unavailable(exception);
      }
   }

   @Override
   public Optional<CatalogReadStore.CatalogPainting> findPainting(String publicId) {
      MapSqlParameterSource parameters = new MapSqlParameterSource("paintingId", publicId);
      String sql = "SELECT p.public_id::text AS painting_id,\n       sr.source_record_key,\n       sr.record_number,\n       p.title,\n       p.author_name,\n       p.category,\n       p.catalog_status,\n       p.image_display_status,\n       sr.record_state,\n       ma.public_id::text AS image_asset_id,\n       ma.source_batch_id::text AS image_batch_id,\n       ma.source_revision AS image_revision,\n       ma.relative_path AS image_relative_path,\n       ma.storage_uri AS image_storage_uri,\n       ma.content_sha256 AS image_sha256,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.sequence') AS legacy_sequence,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.image_storage_name') AS legacy_image_storage_name,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'source.external_catalogue_id') AS external_catalogue_id,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'source.relative_image') AS relative_image,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.author_birth_year_text') AS author_birth_year,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.author_birth_place') AS author_birth_place,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.author_school') AS author_school,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.creation_year_text') AS creation_year,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.creation_dynasty_raw') AS creation_dynasty_raw,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.actual_size') AS actual_size,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.collection_institution') AS collection_institution,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.subject') AS subject,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.painting_school') AS painting_school,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.style') AS style,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.color') AS color,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.composition') AS composition,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.artistic_conception') AS artistic_conception,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.brushwork') AS brushwork,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.ink_method') AS ink_method,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.painting_material') AS painting_material,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.pigment') AS pigment,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.seal') AS seal,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.cultural_symbol') AS cultural_symbol,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.generated_text') AS generated_text,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.music_scene_description') AS music_scene_description,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.collection_platform') AS collection_platform\nFROM catalog_batch_current_revisions current_revision\nJOIN catalog_source_records sr\n  ON sr.batch_id = current_revision.batch_id AND sr.revision = current_revision.revision\nJOIN catalog_paintings p ON p.public_id = sr.painting_id\n  -- A stable painting may retain source membership in several batches.\n  -- Its own current revision is the canonical Gallery representation,\n  -- so a second batch head cannot produce a duplicate Gallery row.\n AND p.current_batch_id = sr.batch_id AND p.current_revision = sr.revision\nLEFT JOIN catalog_media_assets ma ON ma.public_id = p.image_asset_id\n LEFT JOIN catalog_annotation_revisions ar ON ar.batch_id = sr.batch_id AND ar.revision = sr.revision AND ar.annotation_file_id = sr.annotation_file_id AND ar.record_number = sr.record_number  WHERE sr.painting_id IS NOT NULL AND p.public_id = CAST(:paintingId AS uuid) GROUP BY p.public_id, sr.batch_id, sr.revision, sr.annotation_file_id, sr.record_number,\n         sr.source_record_key, sr.record_state, ma.public_id, ma.source_batch_id, ma.source_revision,\n         ma.relative_path, ma.storage_uri, ma.content_sha256\n";
      List<CatalogReadStore.CatalogPainting> result = this.query(sql, parameters, this::mapPaintingBase);
      if (result.isEmpty()) {
         return Optional.empty();
      }

      CatalogReadStore.CatalogPainting base = result.get(0);
      return Optional.of(this.withAnnotations(base));
   }

   @Override
   public List<CatalogReadStore.CatalogPainting> findPaintings(List<String> publicIds) {
      if (publicIds != null && !publicIds.isEmpty()) {
         MapSqlParameterSource parameters = new MapSqlParameterSource("paintingIds", publicIds);
         String sql = "SELECT p.public_id::text AS painting_id,\n       sr.source_record_key,\n       sr.record_number,\n       p.title,\n       p.author_name,\n       p.category,\n       p.catalog_status,\n       p.image_display_status,\n       sr.record_state,\n       ma.public_id::text AS image_asset_id,\n       ma.source_batch_id::text AS image_batch_id,\n       ma.source_revision AS image_revision,\n       ma.relative_path AS image_relative_path,\n       ma.storage_uri AS image_storage_uri,\n       ma.content_sha256 AS image_sha256,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.sequence') AS legacy_sequence,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.image_storage_name') AS legacy_image_storage_name,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'source.external_catalogue_id') AS external_catalogue_id,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'source.relative_image') AS relative_image,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.author_birth_year_text') AS author_birth_year,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.author_birth_place') AS author_birth_place,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.author_school') AS author_school,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.creation_year_text') AS creation_year,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.creation_dynasty_raw') AS creation_dynasty_raw,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.actual_size') AS actual_size,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.collection_institution') AS collection_institution,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.subject') AS subject,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.painting_school') AS painting_school,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.style') AS style,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.color') AS color,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.composition') AS composition,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.artistic_conception') AS artistic_conception,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.brushwork') AS brushwork,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.ink_method') AS ink_method,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.painting_material') AS painting_material,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.pigment') AS pigment,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.seal') AS seal,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.cultural_symbol') AS cultural_symbol,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.generated_text') AS generated_text,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.music_scene_description') AS music_scene_description,\n       MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.collection_platform') AS collection_platform\nFROM catalog_batch_current_revisions current_revision\nJOIN catalog_source_records sr\n  ON sr.batch_id = current_revision.batch_id AND sr.revision = current_revision.revision\nJOIN catalog_paintings p ON p.public_id = sr.painting_id\n  -- A stable painting may retain source membership in several batches.\n  -- Its own current revision is the canonical Gallery representation,\n  -- so a second batch head cannot produce a duplicate Gallery row.\n AND p.current_batch_id = sr.batch_id AND p.current_revision = sr.revision\nLEFT JOIN catalog_media_assets ma ON ma.public_id = p.image_asset_id\n LEFT JOIN catalog_annotation_revisions ar ON ar.batch_id = sr.batch_id AND ar.revision = sr.revision AND ar.annotation_file_id = sr.annotation_file_id AND ar.record_number = sr.record_number  WHERE sr.painting_id IS NOT NULL AND p.public_id::text IN (:paintingIds) GROUP BY p.public_id, sr.batch_id, sr.revision, sr.annotation_file_id, sr.record_number,\n         sr.source_record_key, sr.record_state, ma.public_id, ma.source_batch_id, ma.source_revision,\n         ma.relative_path, ma.storage_uri, ma.content_sha256\n";
         Map<String, CatalogReadStore.CatalogPainting> byId = new LinkedHashMap<>();

         for (CatalogReadStore.CatalogPainting painting : this.query(sql, parameters, this::mapPaintingBase)) {
            byId.putIfAbsent(painting.publicId(), painting);
         }

         return publicIds.stream().map(byId::get).filter(Objects::nonNull).toList();
      } else {
         return List.of();
      }
   }

   @Override
   public Optional<CatalogReadStore.CatalogMediaAsset> findMedia(String publicId) {
      String sql = "SELECT public_id::text AS asset_id, source_batch_id::text AS batch_id, source_revision,\n       relative_path, storage_uri, content_sha256\nFROM catalog_media_assets\nWHERE public_id = CAST(:assetId AS uuid) AND media_status = 'ACTIVE'\n";
      List<CatalogReadStore.CatalogMediaAsset> results = this.query(
         sql,
         new MapSqlParameterSource("assetId", publicId),
         (rs, row) -> new CatalogReadStore.CatalogMediaAsset(
            rs.getString("asset_id"),
            rs.getString("batch_id"),
            rs.getInt("source_revision"),
            rs.getString("relative_path"),
            rs.getString("storage_uri"),
            rs.getString("content_sha256"),
            mimeType(rs.getString("relative_path"))
         )
      );
      return results.stream().findFirst();
   }

   private CatalogReadStore.CatalogPainting withAnnotations(CatalogReadStore.CatalogPainting base) {
      String sql = "SELECT fd.field_key, fd.label, fd.value_type, fd.unit, fd.field_group, fd.display_order,\n       fd.visibility, fd.filterability, fd.original_column_name,\n       ar.raw_value, ar.normalized_value, ar.value_status, ar.assertion_status\nFROM catalog_source_records sr\nJOIN catalog_batch_current_revisions current_revision\n  ON current_revision.batch_id = sr.batch_id AND current_revision.revision = sr.revision\nJOIN catalog_annotation_field_definitions fd\n  ON fd.batch_id = sr.batch_id AND fd.revision = sr.revision\n AND fd.annotation_file_id = sr.annotation_file_id\nJOIN catalog_annotation_revisions ar\n  ON ar.batch_id = fd.batch_id AND ar.revision = fd.revision\n AND ar.annotation_file_id = fd.annotation_file_id AND ar.record_number = sr.record_number\n AND ar.field_key = fd.field_key\nWHERE sr.painting_id = CAST(:paintingId AS uuid)\n  AND sr.source_record_key = :sourceKey\nORDER BY fd.display_order, fd.field_key\n";
      MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("paintingId", base.publicId()).addValue("sourceKey", base.sourceRecordKey());
      List<CatalogReadStore.CatalogAnnotation> annotations = this.query(
         sql,
         parameters,
         (rs, row) -> new CatalogReadStore.CatalogAnnotation(
            rs.getString("field_key"),
            rs.getString("label"),
            rs.getString("value_type"),
            rs.getString("unit"),
            rs.getString("field_group"),
            rs.getInt("display_order"),
            rs.getString("visibility"),
            rs.getString("filterability"),
            rs.getString("original_column_name"),
            rs.getString("raw_value"),
            rs.getString("normalized_value"),
            rs.getString("value_status"),
            rs.getString("assertion_status")
         )
      );
      return new CatalogReadStore.CatalogPainting(
         base.publicId(),
         base.sourceRecordKey(),
         base.sourceRecordNumber(),
         base.sourceSequence(),
         base.imageStorageName(),
         base.title(),
         base.authorName(),
         base.category(),
         base.catalogStatus(),
         base.recordState(),
         base.imageDisplayStatus(),
         base.image(),
         base.values(),
         annotations
      );
   }

   private CatalogReadStore.CatalogPainting mapPaintingBase(ResultSet rs, int rowNumber) throws SQLException {
      Map<String, String> values = new LinkedHashMap<>();
      put(values, "legacy.sequence", rs.getString("legacy_sequence"));
      put(values, "legacy.image_storage_name", rs.getString("legacy_image_storage_name"));
      put(values, "source.external_catalogue_id", rs.getString("external_catalogue_id"));
      put(values, "source.relative_image", rs.getString("relative_image"));

      for (String key : List.of(
         "author_birth_year",
         "author_birth_place",
         "author_school",
         "creation_year",
         "creation_dynasty_raw",
         "actual_size",
         "collection_institution",
         "subject",
         "painting_school",
         "style",
         "color",
         "composition",
         "artistic_conception",
         "brushwork",
         "ink_method",
         "painting_material",
         "pigment",
         "seal",
         "cultural_symbol",
         "generated_text",
         "music_scene_description",
         "collection_platform"
      )) {
         put(values, "legacy." + key, rs.getString(key));
      }

      CatalogReadStore.CatalogMediaAsset media = rs.getString("image_asset_id") == null
         ? null
         : new CatalogReadStore.CatalogMediaAsset(
            rs.getString("image_asset_id"),
            rs.getString("image_batch_id"),
            rs.getInt("image_revision"),
            rs.getString("image_relative_path"),
            rs.getString("image_storage_uri"),
            rs.getString("image_sha256"),
            mimeType(rs.getString("image_relative_path"))
         );
      String sourceSequence = first(values.get("legacy.sequence"), values.get("source.external_catalogue_id"), Integer.toString(rs.getInt("record_number")));
      String imageStorageName = first(values.get("legacy.image_storage_name"), values.get("source.relative_image"), rs.getString("image_relative_path"));
      return new CatalogReadStore.CatalogPainting(
         rs.getString("painting_id"),
         rs.getString("source_record_key"),
         rs.getInt("record_number"),
         sourceSequence,
         imageStorageName,
         rs.getString("title"),
         rs.getString("author_name"),
         rs.getString("category"),
         rs.getString("catalog_status"),
         rs.getString("record_state"),
         rs.getString("image_display_status"),
         media,
         values,
         List.of()
      );
   }

   private static void put(Map<String, String> values, String key, String value) {
      if (value != null) {
         values.put(key, value);
      }
   }

   private MapSqlParameterSource parameters(CatalogReadStore.CatalogQuery query) {
      if ("daily".equals(query.sort()) && query.orderDate() == null) {
         throw new IllegalArgumentException("validated daily order date is required");
      }
      return new MapSqlParameterSource()
         .addValue("orderDate", query.orderDate() == null ? null : query.orderDate().toString())
         .addValue("keyword", like(query.keyword()))
         .addValue("dynasty", query.dynasty())
         .addValue("category", query.category())
         .addValue("author", query.author())
         .addValue("subject", query.subject())
         .addValue("paintingSchool", query.paintingSchool())
         .addValue("style", query.style())
         .addValue("artisticConception", query.artisticConception())
         .addValue("paintingMaterial", query.paintingMaterial())
         .addValue("collectionInstitution", query.collectionInstitution())
         .addValue("collectionPlatform", query.collectionPlatform());
   }

   private String listWhere(CatalogReadStore.CatalogQuery query, MapSqlParameterSource parameters) {
      StringBuilder where = new StringBuilder(GALLERY_WHERE);
      if (query.keyword() != null) {
         // PostgreSQL E-string syntax makes the SQL escape exactly one backslash.
         // User input stays bound through like(); %, _ and backslashes remain literal.
         where.append(
            " AND (p.title ILIKE :keyword ESCAPE E'\\\\' OR p.author_name ILIKE :keyword ESCAPE E'\\\\' OR EXISTS (SELECT 1 FROM catalog_annotation_revisions keyword_ar WHERE keyword_ar.batch_id = sr.batch_id AND keyword_ar.revision = sr.revision AND keyword_ar.annotation_file_id = sr.annotation_file_id AND keyword_ar.record_number = sr.record_number AND keyword_ar.field_key = 'legacy.subject' AND keyword_ar.normalized_value ILIKE :keyword ESCAPE E'\\\\'))"
         );
      }

      equals(where, query.dynasty(), "legacy.creation_dynasty_raw", "dynasty");
      equalsColumn(where, query.category(), "p.category", "category");
      equalsColumn(where, query.author(), "p.author_name", "author");
      equals(where, query.subject(), "legacy.subject", "subject");
      equals(where, query.paintingSchool(), "legacy.painting_school", "paintingSchool");
      equals(where, query.style(), "legacy.style", "style");
      equals(where, query.artisticConception(), "legacy.artistic_conception", "artisticConception");
      equals(where, query.paintingMaterial(), "legacy.painting_material", "paintingMaterial");
      equals(where, query.collectionInstitution(), "legacy.collection_institution", "collectionInstitution");
      equals(where, query.collectionPlatform(), "legacy.collection_platform", "collectionPlatform");
      return where.toString();
   }

   private static void equals(StringBuilder where, String value, String fieldKey, String parameter) {
      if (value != null) {
         where.append(
               " AND EXISTS (SELECT 1 FROM catalog_annotation_revisions filter_ar WHERE filter_ar.batch_id = sr.batch_id AND filter_ar.revision = sr.revision AND filter_ar.annotation_file_id = sr.annotation_file_id AND filter_ar.record_number = sr.record_number AND filter_ar.field_key = '"
            )
            .append(fieldKey)
            .append("' AND lower(filter_ar.normalized_value) = lower(:")
            .append(parameter)
            .append("))");
      }
   }

   private static void equalsColumn(StringBuilder where, String value, String column, String parameter) {
      if (value != null) {
         where.append(" AND lower(").append(column).append(") = lower(:").append(parameter).append(")");
      }
   }

   private static String orderBy(CatalogReadStore.CatalogQuery query) {
      String direction = "desc".equals(query.direction()) ? "DESC" : "ASC";

      String column = switch (query.sort()) {
         // Rank the complete matching set before LIMIT/OFFSET. A stable ID tie-breaker
         // keeps page boundaries deterministic even in the event of a hash collision.
         case "daily" -> "md5(:orderDate || ':' || p.public_id::text) COLLATE \"C\"";
         case "source" -> "sr.source_record_key";
         case "title" -> "p.title";
         case "author" -> "p.author_name";
         case "dynasty" -> "MAX(ar.normalized_value) FILTER (WHERE ar.field_key = 'legacy.creation_dynasty_raw')";
         default -> throw new IllegalArgumentException("validated sort is required");
      };
      return " ORDER BY " + column + " " + direction + " NULLS LAST, p.public_id ASC";
   }

   private long scalarLong(String sql, MapSqlParameterSource parameters) {
      try {
         Long value = (Long)this.jdbc.queryForObject(sql, parameters, Long.class);
         return value == null ? 0L : value;
      } catch (DataAccessException exception) {
         throw unavailable(exception);
      }
   }

   private <T> List<T> query(String sql, MapSqlParameterSource parameters, RowMapper<T> mapper) {
      try {
         return this.jdbc.query(sql, parameters, mapper);
      } catch (DataAccessException exception) {
         throw unavailable(exception);
      }
   }

   private static ApiV1Exception unavailable(DataAccessException exception) {
      return new ApiV1Exception(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.CATALOG_READ_UNAVAILABLE, "画作目录暂时不可用");
   }

   private static String like(String value) {
      return value == null ? null : "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
   }

   private static String first(String... values) {
      for (String value : values) {
         if (value != null && !value.isBlank()) {
            return value;
         }
      }

      return null;
   }

   private static String mimeType(String relativePath) {
      if (relativePath == null) {
         return "application/octet-stream";
      }

      String lower = relativePath.toLowerCase(Locale.ROOT);
      return lower.endsWith(".png") ? "image/png" : "image/jpeg";
   }
}
