package com.auralink.catalogread;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Runs real keyword/count/page SQL only in disposable schemas of the isolated test database. */
@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/assistant_foundation_test")
class GalleryKeywordPostgresTest {
   private JdbcTemplate admin;
   private JdbcTemplate jdbc;
   private String schema;
   private UUID batch;
   private PostgresCatalogReadStore store;

   @BeforeEach void setup() {
      String url = System.getenv("ASSISTANT_TEST_JDBC_URL");
      String user = System.getenv("ASSISTANT_TEST_JDBC_USER");
      String password = System.getenv("ASSISTANT_TEST_JDBC_PASSWORD");
      admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
      schema = "gallery_keyword_" + UUID.randomUUID().toString().replace("-", "");
      admin.execute("CREATE SCHEMA " + schema);
      jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password));
      jdbc.execute("CREATE TABLE catalog_batch_current_revisions(batch_id uuid, revision int)");
      jdbc.execute("CREATE TABLE catalog_source_records(batch_id uuid, revision int, annotation_file_id text, record_number int, painting_id uuid, source_record_key text, record_state text)");
      jdbc.execute("CREATE TABLE catalog_paintings(public_id uuid PRIMARY KEY, current_batch_id uuid, current_revision int, title text, author_name text, category text, catalog_status text, image_display_status text, image_asset_id uuid)");
      jdbc.execute("CREATE TABLE catalog_media_assets(public_id uuid PRIMARY KEY, source_batch_id uuid, source_revision int, relative_path text, storage_uri text, content_sha256 text, media_status text)");
      jdbc.execute("CREATE TABLE catalog_annotation_revisions(batch_id uuid, revision int, annotation_file_id text, record_number int, field_key text, raw_value text, normalized_value text, value_status text, assertion_status text)");
      batch = UUID.randomUUID();
      jdbc.update("INSERT INTO catalog_batch_current_revisions VALUES (?, 2)", batch);
      store = new PostgresCatalogReadStore(new NamedParameterJdbcTemplate(jdbc));
   }

   @AfterEach void cleanup() {
      if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
   }

   private static UUID id(int n) { return new UUID(0, n); }

   private void painting(int n, String field, String value, String status, String image) {
      jdbc.update("INSERT INTO catalog_paintings VALUES (?, ?, 2, ?, ?, '山水', ?, ?, NULL)",
         id(n), batch, "title".equals(field) ? value : "其他标题",
         "author".equals(field) ? value : "其他作者", status, image);
      jdbc.update("INSERT INTO catalog_source_records VALUES (?, 2, ?, ?, ?, ?, 'VALID')",
         batch, "file-" + n, n, id(n), "source-" + n);
      String subject = "subject".equals(field) ? value : "其他题材";
      jdbc.update("INSERT INTO catalog_annotation_revisions VALUES (?, 2, ?, ?, 'legacy.subject', ?, ?, 'PRESENT', 'UNVERIFIED')",
         batch, "file-" + n, n, subject, subject);
   }

   private CatalogReadStore.CatalogPage page(String keyword, String sort, int page, int size) {
      return store.list(new CatalogReadStore.CatalogQuery(keyword, null, null, null, null,
         null, null, null, null, null, null, page, size, sort, "asc",
         "daily".equals(sort) ? LocalDate.of(2026, 10, 19) : null));
   }

   private static List<String> ids(CatalogReadStore.CatalogPage page) {
      return page.items().stream().map(CatalogReadStore.CatalogPainting::publicId).toList();
   }

   static Stream<Arguments> literalKeywords() {
      // Quotes and mixed escape characters also prove the query remains parameter-bound.
      return Stream.of("daily", "source").flatMap(sort -> Stream.of("title", "author", "subject")
         .flatMap(field -> Stream.of("江南", "%", "_", "\\", "' OR TRUE --", "江\\%_南")
            .map(keyword -> Arguments.of(sort, field, keyword))));
   }

   @ParameterizedTest(name = "{0} sort, {1}, literal keyword [{2}]")
   @MethodSource("literalKeywords")
   void literalMatchesCountAndPagesUseTheSameCurrentEligibleSet(String sort, String field, String keyword) {
      for (int n : List.of(1, 3, 5)) painting(n, field, "前" + keyword + "后", "ACTIVE", "READY");
      painting(2, field, "前中后", "ACTIVE", "READY");
      painting(4, field, "前后", "ACTIVE", "READY");
      painting(6, field, keyword, "INACTIVE", "READY");
      painting(7, field, keyword, "ACTIVE", "MISSING");
      // An obsolete subject annotation must not turn a currently nonmatching record into a match.
      jdbc.update("INSERT INTO catalog_annotation_revisions VALUES (?, 1, 'file-2', 2, 'legacy.subject', ?, ?, 'PRESENT', 'UNVERIFIED')",
         batch, keyword, keyword);
      // Retained membership in another published batch must not duplicate the canonical painting.
      UUID otherBatch = UUID.randomUUID();
      jdbc.update("INSERT INTO catalog_batch_current_revisions VALUES (?, 1)", otherBatch);
      jdbc.update("INSERT INTO catalog_source_records VALUES (?, 1, 'other-file', 99, ?, 'retained-source', 'VALID')", otherBatch, id(1));

      var whole = page(keyword, sort, 0, 10);
      assertEquals(3, whole.totalElements());
      assertEquals(Set.of(id(1).toString(), id(3).toString(), id(5).toString()), Set.copyOf(ids(whole)));
      if ("source".equals(sort)) assertEquals(List.of(id(1).toString(), id(3).toString(), id(5).toString()), ids(whole));

      var first = page(keyword, sort, 0, 2);
      var second = page(keyword, sort, 1, 2);
      var beyond = page(keyword, sort, 2, 2);
      assertEquals(3, first.totalElements());
      assertEquals(3, second.totalElements());
      assertEquals(3, beyond.totalElements());
      assertEquals(2, first.items().size());
      assertEquals(1, second.items().size());
      assertTrue(beyond.items().isEmpty());
      var joined = new ArrayList<>(ids(first));
      joined.addAll(ids(second));
      assertEquals(ids(whole), joined);
      assertEquals(3, joined.stream().distinct().count());

      var missing = page(keyword + "绝无命中", sort, 0, 2);
      assertEquals(0, missing.totalElements());
      assertTrue(missing.items().isEmpty());
   }
}
