package com.auralink.catalogread;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Uses only disposable schemas in the explicit loopback test database. */
@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/assistant_foundation_test")
class GalleryDailyOrderPostgresTest {
   private JdbcTemplate admin;
   private JdbcTemplate jdbc;
   private String schema;
   private PostgresCatalogReadStore store;
   private UUID firstBatch;
   private UUID secondBatch;
   private static final LocalDate DAY = LocalDate.of(2026, 10, 19);

   @BeforeEach void setup() {
      String url = System.getenv("ASSISTANT_TEST_JDBC_URL");
      String user = System.getenv("ASSISTANT_TEST_JDBC_USER");
      String password = System.getenv("ASSISTANT_TEST_JDBC_PASSWORD");
      admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
      schema = "gallery_daily_" + UUID.randomUUID().toString().replace("-", "");
      admin.execute("CREATE SCHEMA " + schema);
      jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password));
      jdbc.execute("CREATE TABLE catalog_batch_current_revisions(batch_id uuid, revision int)");
      jdbc.execute("CREATE TABLE catalog_source_records(batch_id uuid, revision int, annotation_file_id text, record_number int, painting_id uuid, source_record_key text, record_state text)");
      jdbc.execute("CREATE TABLE catalog_paintings(public_id uuid PRIMARY KEY, current_batch_id uuid, current_revision int, title text, author_name text, category text, catalog_status text, image_display_status text, image_asset_id uuid)");
      jdbc.execute("CREATE TABLE catalog_media_assets(public_id uuid PRIMARY KEY, source_batch_id uuid, source_revision int, relative_path text, storage_uri text, content_sha256 text, media_status text)");
      jdbc.execute("CREATE TABLE catalog_annotation_revisions(batch_id uuid, revision int, annotation_file_id text, record_number int, field_key text, raw_value text, normalized_value text, value_status text, assertion_status text)");
      firstBatch = UUID.randomUUID();
      secondBatch = UUID.randomUUID();
      jdbc.update("INSERT INTO catalog_batch_current_revisions VALUES (?, 1), (?, 1)", firstBatch, secondBatch);
      for (int n = 1; n <= 24; n++) addPainting(n, n <= 12 ? firstBatch : secondBatch, "ACTIVE", "READY");
      addPainting(25, firstBatch, "INACTIVE", "READY");
      addPainting(26, firstBatch, "ACTIVE", "MISSING");
      addPainting(27, UUID.randomUUID(), "ACTIVE", "READY");
      jdbc.update("INSERT INTO catalog_source_records VALUES (?,1,'retained-member',99,?,'duplicate-key','VALID')", secondBatch, id(1));
      jdbc.update("INSERT INTO catalog_source_records VALUES (?,0,'old-revision',98,?,'obsolete-key','VALID')", firstBatch, id(1));
      store = new PostgresCatalogReadStore(new NamedParameterJdbcTemplate(jdbc));
   }

   @AfterEach void cleanup() {
      if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
   }

   private static UUID id(int n) { return new UUID(0, n); }

   private void addPainting(int n, UUID batch, String status, String image) {
      jdbc.update("INSERT INTO catalog_paintings VALUES (?, ?, 1, ?, ?, ?, ?, ?, NULL)",
         id(n), batch, "同名山水", n <= 12 ? "作者甲" : "作者乙", n % 2 == 0 ? "山水" : "花鸟", status, image);
      jdbc.update("INSERT INTO catalog_source_records VALUES (?,1,?,?,?,?,'VALID')",
         batch, "file-" + n, n, id(n), "source-" + String.format("%03d", n));
      jdbc.update("INSERT INTO catalog_annotation_revisions VALUES (?,1,?,?,'legacy.subject','远山','远山','PRESENT','UNSPECIFIED')",
         batch, "file-" + n, n);
   }

   private CatalogReadStore.CatalogQuery query(int page, int size, String sort, String direction, LocalDate date, String category, String author) {
      return new CatalogReadStore.CatalogQuery(null, null, category, author, null, null, null, null, null, null, null,
         page, size, sort, direction, date);
   }

   private List<String> ids(CatalogReadStore.CatalogPage page) {
      return page.items().stream().map(CatalogReadStore.CatalogPainting::publicId).toList();
   }

   private List<String> expected(LocalDate date) {
      return java.util.stream.IntStream.rangeClosed(1, 24).mapToObj(n -> id(n).toString())
         .sorted(Comparator.comparing((String value) -> digest(date + ":" + value)).thenComparing(Comparator.naturalOrder()))
         .toList();
   }

   private static String digest(String value) {
      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
      } catch (java.security.NoSuchAlgorithmException exception) {
         throw new AssertionError(exception);
      }
   }

   @Test void wholeFilteredSetIsHashedBeforePagingAcrossBothBatches() {
      var whole = store.list(query(0, 100, "daily", "asc", DAY, null, null));
      assertEquals(24, whole.totalElements());
      assertEquals(expected(DAY), ids(whole));
      for (int size : List.of(5, 7)) {
         var joined = new ArrayList<String>();
         for (int page = 0; page < (24 + size - 1) / size; page++) {
            var result = store.list(query(page, size, "daily", "asc", DAY, null, null));
            assertEquals(24, result.totalElements());
            joined.addAll(ids(result));
         }
         assertEquals(ids(whole), joined);
         assertEquals(24, joined.stream().distinct().count());
      }
   }

   @Test void repeatDayIsStableAndNextDayReordersTheSameMembership() {
      var first = ids(store.list(query(0, 100, "daily", "asc", DAY, null, null)));
      assertEquals(first, ids(store.list(query(0, 100, "daily", "asc", DAY, null, null))));
      var tomorrow = ids(store.list(query(0, 100, "daily", "asc", DAY.plusDays(1), null, null)));
      assertEquals(expected(DAY.plusDays(1)), tomorrow);
      assertNotEquals(first, tomorrow);
      assertEquals(new java.util.HashSet<>(first), new java.util.HashSet<>(tomorrow));
   }

   @Test void filtersRetainDailyRelativeOrderAndCanonicalMembership() {
      var result = store.list(query(0, 100, "daily", "asc", DAY, "山水", "作者甲"));
      var expected = expected(DAY).stream().filter(value -> {
         long n = UUID.fromString(value).getLeastSignificantBits();
         return n <= 12 && n % 2 == 0;
      }).toList();
      assertEquals(6, result.totalElements());
      assertEquals(expected, ids(result));
      assertEquals(0, store.list(query(0, 100, "daily", "asc", DAY, "不存在", null)).totalElements());
      assertTrue(store.list(query(9, 5, "daily", "asc", DAY, null, null)).items().isEmpty());
   }

   @Test void descendingDailyAndExistingSourceOrdersRemainDeterministic() {
      var reversed = new ArrayList<>(expected(DAY));
      java.util.Collections.reverse(reversed);
      assertEquals(reversed, ids(store.list(query(0, 100, "daily", "desc", DAY, null, null))));
      var source = java.util.stream.IntStream.rangeClosed(1, 24).mapToObj(n -> id(n).toString()).toList();
      assertEquals(source, ids(store.list(query(0, 100, "source", "asc", null, null, null))));
   }

   @Test void missingDailySeedCannotSilentlyUseUnstableOrSourceOrder() {
      assertThrows(IllegalArgumentException.class,
         () -> store.list(query(0, 24, "daily", "asc", null, null, null)));
   }
}
