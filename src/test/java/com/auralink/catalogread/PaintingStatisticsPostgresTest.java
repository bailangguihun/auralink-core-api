package com.auralink.catalogread;

import com.auralink.api.v1.knowledge.PaintingStatisticsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL; opt in with a loopback-only disposable test database. Never uses application config. */
@EnabledIfEnvironmentVariable(named = "ARTLIVE_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/artlive_knowledge_fixture")
class PaintingStatisticsPostgresTest {
   private JdbcTemplate admin;
   private JdbcTemplate jdbc;
   private PostgresCatalogReadStore store;
   private String schema;
   private String batch;
   private boolean keepScaleSchema;

   @BeforeEach void createIsolatedSchema() {
      String url = System.getenv("ARTLIVE_TEST_JDBC_URL");
      String user = System.getenv("ARTLIVE_TEST_DB_USER");
      String password = System.getenv("ARTLIVE_TEST_DB_PASSWORD");
      admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
      schema = "knowledge_test_" + UUID.randomUUID().toString().replace("-", "");
      admin.execute("CREATE SCHEMA " + schema);
      jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password));
      jdbc.execute("CREATE TABLE catalog_batch_current_revisions(batch_id uuid,revision int)");
      jdbc.execute("CREATE TABLE catalog_source_records(batch_id uuid,revision int,annotation_file_id text,record_number int,painting_id uuid,source_record_key text,record_state text)");
      jdbc.execute("CREATE TABLE catalog_paintings(public_id uuid PRIMARY KEY,current_batch_id uuid,current_revision int,title text,author_name text,category text,catalog_status text,image_display_status text,image_asset_id uuid)");
      jdbc.execute("CREATE TABLE catalog_media_assets(public_id uuid PRIMARY KEY,source_batch_id uuid,source_revision int,relative_path text,storage_uri text,content_sha256 text,media_status text)");
      jdbc.execute("CREATE TABLE catalog_annotation_field_definitions(batch_id uuid,revision int,annotation_file_id text,field_key text,visibility text)");
      jdbc.execute("CREATE TABLE catalog_annotation_revisions(batch_id uuid,revision int,annotation_file_id text,record_number int,field_key text,raw_value text,normalized_value text,value_status text DEFAULT 'PRESENT',assertion_status text DEFAULT 'UNSPECIFIED')");
      store = new PostgresCatalogReadStore(new NamedParameterJdbcTemplate(jdbc));
      batch = UUID.randomUUID().toString();
      jdbc.update("INSERT INTO catalog_batch_current_revisions VALUES (?::uuid,2)", batch);
   }

   @AfterEach void removeOnlyOwnSchema() { if (admin != null && schema != null && !keepScaleSchema) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

   private String painting(int number, String status, String media, boolean published) {
      String id = UUID.randomUUID().toString();
      String rowBatch = published ? batch : UUID.randomUUID().toString();
      jdbc.update("INSERT INTO catalog_paintings VALUES (?::uuid,?::uuid,2,'fixture','PRIVATE_CORE_AUTHOR','PRIVATE_CORE_CATEGORY',?,?,NULL)", id, rowBatch, status, media);
      jdbc.update("INSERT INTO catalog_source_records VALUES (?::uuid,2,?, ?,?::uuid,?,'VALID')", rowBatch, "file-" + number, number, id, "fixture-" + number);
      return id;
   }

   private void value(int number, String key, String visibility, String value) {
      jdbc.update("INSERT INTO catalog_annotation_field_definitions VALUES (?::uuid,2,?,?,?)", batch, "file-" + number, key, visibility);
      jdbc.update("INSERT INTO catalog_annotation_revisions(batch_id,revision,annotation_file_id,record_number,field_key,raw_value,normalized_value) VALUES (?::uuid,2,?,?,?,?,?)", batch, "file-" + number, number, key, value, value);
   }

   private void seed() {
      String first = painting(1,"ACTIVE","READY",true);
      painting(2,"ACTIVE","READY",true);
      painting(3,"ACTIVE","READY",true);
      painting(4,"ACTIVE","READY",true);
      painting(5,"ACTIVE","READY",true);
      painting(6,"ACTIVE","READY",true);
      painting(7,"ACTIVE","MISSING",true);
      painting(8,"INACTIVE","READY",true);
      painting(9,"ACTIVE","READY",false);
      // The same stable painting remains in another published batch head and an old revision.
      String other = UUID.randomUUID().toString();
      jdbc.update("INSERT INTO catalog_batch_current_revisions VALUES (?::uuid,1)",other);
      jdbc.update("INSERT INTO catalog_source_records VALUES (?::uuid,1,'old-head',1,?::uuid,'duplicate','VALID')",other,first);
      jdbc.update("INSERT INTO catalog_source_records VALUES (?::uuid,1,'old-revision',1,?::uuid,'stale','VALID')",batch,first);
      for (int n : List.of(1,2)) {
         value(n,"core.author_name","public",n==1?" 张   三 ":"张 三");
         value(n,"core.category","public","山水");
         value(n,"legacy.creation_dynasty_raw","public",n==1?"唐":"唐朝");
         value(n,"legacy.author_birth_place","public","江苏苏州");
         value(n,"legacy.style","public","工笔、工笔；写意");
         value(n,"legacy.artistic_conception","public","宁静");
         value(n,"legacy.music_scene_description","public","欢快喜悦");
      }
      // Exact public huaniao schema; no dynasty/year inference.
      value(3,"core.author_name","public","陈乙"); value(3,"core.category","public","花鸟");
      value(3,"annotation.style","public","写意"); value(3,"annotation.subject_secondary","public","花鸟、春日");
      value(3,"annotation.creation_year","public","2020");
      for (String key : List.of("core.author_name","core.category","legacy.style","legacy.creation_dynasty_raw","legacy.artistic_conception","legacy.author_birth_place","legacy.music_scene_description")) {
         value(4,key,"public","0"); value(5,key,key.equals("core.author_name")?"internal":"hidden","SECRET_"+key);
      }
      value(6,"core.author_name","public","王丙"); value(6,"core.category","public","人物");
      value(6,"legacy.creation_dynasty_raw","public","元代"); value(6,"legacy.author_birth_place","public","未映射地点");
      value(6,"legacy.music_scene_description","public","没有规则命中的描述");
   }

   @Test void matchesGalleryExactlyAndDeduplicatesCurrentRevisions() {
      seed();
      var response = store.paintingStatistics();
      var query = new CatalogReadStore.CatalogQuery(null,null,null,null,null,null,null,null,null,null,null,0,24,"source","asc");
      assertEquals(6,store.list(query).totalElements());
      assertEquals(store.list(query).totalElements(),response.population().count());
      assertEquals(3,response.authorNames().count());
      assertEquals(4,response.authorNames().eligibleRecords());
      assertEquals(2,item(response,"dynasty","唐代"));
      assertEquals(3,metric(response,"dynasty").eligibleRecords());
      assertEquals(1,item(response,"geography","江苏省"));
      assertEquals(1,metric(response,"geography").unmatchedRecords());
      assertEquals(3,item(response,"style","写意"));
      assertEquals(2,item(response,"style","工笔"));
      assertEquals(3,metric(response,"imagery").eligibleRecords());
      assertEquals(2,item(response,"sentiment","欢快"));
      assertEquals(1,metric(response,"sentiment").unmatchedRecords());
      assertEquals(61,item(response,"sentimentByDynasty","唐代"));
   }

   @Test void neverAggregatesHiddenFieldsOrLiteralZeroAndTracksCoverage() {
      seed();
      var response = store.paintingStatistics();
      assertEquals(2,metric(response,"category").missingOrRestrictedRecords());
      assertEquals(3,metric(response,"style").missingOrRestrictedRecords());
      assertFalse(response.toString().contains("SECRET"));
      assertFalse(response.toString().contains("PRIVATE_CORE"));
      assertTrue(response.metrics().values().stream().flatMap(m->m.items().stream()).noneMatch(i->i.name().equals("0")));
      // Changing a hidden value cannot change any public aggregate.
      jdbc.update("UPDATE catalog_annotation_revisions SET normalized_value='宁静 工笔 唐代',raw_value='宁静 工笔 唐代' WHERE record_number=5");
      assertEquals(response,store.paintingStatistics());
   }

   @Test void explicitUnknownsAreUnavailableAndUnmatchedSamplesStayVisible() {
      painting(1,"ACTIVE","READY",true);
      value(1,"core.author_name","public","不详"); value(1,"core.category","public","  未知  ");
      value(1,"legacy.style","public","0");
      var response=store.paintingStatistics();
      assertEquals(0,response.authorNames().count());
      assertEquals("unavailable",metric(response,"style").status());
      assertTrue(metric(response,"style").reason().contains("待核实"));
      assertEquals(1,metric(response,"category").missingOrRestrictedRecords());
   }

   @Test void missingPendingAndConflictingAliasesAreNotCounted() {
      painting(1,"ACTIVE","READY",true); painting(2,"ACTIVE","READY",true); painting(3,"ACTIVE","READY",true);
      value(1,"core.author_name","public","甲"); value(1,"legacy.author_name","public","乙");
      value(1,"legacy.style","public","有文本但缺失");
      value(2,"core.category","public","有文本但待核"); value(3,"core.category","public","有文本但不明");
      jdbc.update("UPDATE catalog_annotation_revisions SET value_status='MISSING' WHERE field_key='legacy.style'");
      jdbc.update("UPDATE catalog_annotation_revisions SET assertion_status='UNRESOLVED' WHERE record_number=2");
      jdbc.update("UPDATE catalog_annotation_revisions SET assertion_status='UNKNOWN' WHERE record_number=3");
      var response=store.paintingStatistics();
      assertEquals(0,response.authorNames().count());
      assertEquals("unavailable",metric(response,"style").status());
      assertEquals("unavailable",metric(response,"category").status());
   }

   @Test void outputIsBoundedAndReportsTruncationWithoutChangingDenominator() {
      for(int n=1;n<=65;n++) {
         painting(n,"ACTIVE","READY",true);value(n,"core.category","public","synthetic-category-"+n);
      }
      var response=store.paintingStatistics();
      assertEquals(65,response.population().count());
      assertEquals(50,metric(response,"category").items().size());
      assertTrue(metric(response,"category").truncated());
      assertEquals(65,metric(response,"category").eligibleRecords());
   }

   @Test void geographyPreservesProvincePriorityConflictsAndNameBirthplaceUnits() throws Exception {
      String[] authors={"张三","张三","张三","张三","乙","丙","丁","戊","0","己","庚","辛"};
      String[] places={"江苏苏州","江苏苏州","苏州南京","江苏杭州","江苏浙江","南京杭州",
         "未映射地点","江苏苏州","江苏苏州","未知","江苏苏州","苏州南京"};
      for(int n=1;n<=authors.length;n++) {
         painting(n,"ACTIVE","READY",true);
         value(n,"core.author_name","public",authors[n-1]);
         value(n,"legacy.author_birth_place",n==8?"hidden":"public",places[n-1]);
         value(n,"core.category","public","山水"); value(n,"legacy.creation_dynasty_raw","public","宋");
         value(n,"legacy.style","public","工笔、写意"); value(n,"legacy.artistic_conception","public","宁静");
         value(n,"legacy.music_scene_description","public","欢快");
      }
      assertSameRowsAsPreviousQuery();
      var response=store.paintingStatistics();
      assertEquals(12,response.population().count());
      assertEquals(8,response.authorNames().count());
      assertEquals(11,response.authorNames().eligibleRecords());
      assertEquals(Map.of("江苏省",5.0),items(response,"geography"));
      assertEquals(9,metric(response,"geography").eligibleRecords());
      assertEquals(3,metric(response,"geography").missingOrRestrictedRecords());
      assertEquals(3,metric(response,"geography").unmatchedRecords());
      assertEquals(12,item(response,"dynasty","宋代"));
      assertEquals(12,item(response,"sentiment","欢快"));
   }

   @Test void denseGeographicCoverageMatchesEveryPreviousAggregateRow() throws Exception {
      String[] places={"江苏苏州","苏州南京","江苏杭州","江苏浙江","南京杭州","未映射地点"};
      // All 13 names occur with each of six birthplace strings five times.
      // Multiple candidates per painting expose the former correlated rescan;
      // this remains a bounded semantic fixture, not the prior 10,000-row run.
      for(int n=0;n<390;n++) {
         painting(n+1,"ACTIVE","READY",true);
         value(n+1,"core.author_name","public","合成作者"+(n%13));
         value(n+1,"legacy.author_birth_place","public",places[n%places.length]);
         value(n+1,"core.category","public",n%2==0?"山水":"花鸟");
         value(n+1,n%2==0?"legacy.style":"annotation.style","public","工笔、写意");
      }
      jdbc.execute("ANALYZE catalog_paintings");jdbc.execute("ANALYZE catalog_source_records");
      jdbc.execute("ANALYZE catalog_annotation_field_definitions");jdbc.execute("ANALYZE catalog_annotation_revisions");
      assertSameRowsAsPreviousQuery();
      var response=store.paintingStatistics();
      assertEquals(390,response.population().count());
      assertEquals(13,response.authorNames().count());
      assertEquals(Map.of("江苏省",39.0),items(response,"geography"));
      assertEquals(390,metric(response,"geography").eligibleRecords());
      assertEquals(195,metric(response,"geography").unmatchedRecords());
      assertEquals(0,metric(response,"geography").missingOrRestrictedRecords());
   }

   @Test void sharedFileDefinitionsDoNotMultiplyAnnotationScansOrExposePrivateFields() throws Exception {
      // Three shared source files, including a huaniao-shaped file and a file
      // with restricted author/geography/style. Keep planner statistics absent,
      // as in the installation which exposed repeated per-field record scans.
      jdbc.execute("""
         CREATE TABLE shared_records AS
         SELECT n, n %% 3 AS cohort, md5('shared-annotation-' || n)::uuid AS id
         FROM generate_series(1,384) n
         """.replace("%%","%"));
      jdbc.execute("""
         INSERT INTO catalog_paintings
         SELECT id,'%s'::uuid,2,'synthetic shared file','PRIVATE_CORE_AUTHOR','PRIVATE_CORE_CATEGORY',
           'ACTIVE','READY',NULL FROM shared_records
         """.formatted(batch));
      jdbc.execute("""
         INSERT INTO catalog_source_records
         SELECT '%s'::uuid,2,'shared-' || cohort,n,id,'shared:' || n,'VALID' FROM shared_records
         """.formatted(batch));
      jdbc.execute("""
         CREATE TABLE shared_values AS
         SELECT r.*,v.field_key,v.value FROM shared_records r CROSS JOIN LATERAL (VALUES
           ('core.author_name','合成作者' || (n % 16)),
           ('core.category',CASE WHEN cohort=1 THEN '花鸟' ELSE '山水' END),
           ('legacy.author_birth_place','江苏苏州'),
           ('legacy.creation_dynasty_raw','宋'),
           (CASE WHEN cohort=1 THEN 'annotation.style' ELSE 'legacy.style' END,'工笔、写意'),
           ('legacy.music_scene_description','欢快'),
           ('legacy.artistic_conception','宁静'),
           ('annotation.subject_secondary','花鸟、春日')
         ) v(field_key,value)
         UNION ALL
         SELECT r.*,'annotation.extra_' || x,'UNMAPPED_SECRET_' || x
         FROM shared_records r CROSS JOIN generate_series(1,20) x
         """);
      jdbc.execute("""
         INSERT INTO catalog_annotation_field_definitions
         SELECT DISTINCT '%s'::uuid,2,'shared-' || cohort,field_key,
           CASE WHEN cohort=2 AND field_key IN ('core.author_name','legacy.author_birth_place','legacy.style')
             THEN 'hidden' ELSE 'public' END FROM shared_values
         """.formatted(batch));
      jdbc.execute("""
         INSERT INTO catalog_annotation_revisions
         SELECT '%s'::uuid,2,'shared-' || cohort,n,field_key,value,value,'PRESENT','UNSPECIFIED'
         FROM shared_values
         """.formatted(batch));
      jdbc.execute("CREATE UNIQUE INDEX shared_source_key ON catalog_source_records(batch_id,revision,annotation_file_id,record_number)");
      jdbc.execute("CREATE INDEX shared_source_painting ON catalog_source_records(painting_id)");
      jdbc.execute("CREATE UNIQUE INDEX shared_field_key ON catalog_annotation_field_definitions(batch_id,revision,annotation_file_id,field_key)");
      jdbc.execute("CREATE UNIQUE INDEX shared_annotation_key ON catalog_annotation_revisions(batch_id,revision,annotation_file_id,record_number,field_key)");
      assertSameRowsAsPreviousQuery();
      var response=store.paintingStatistics();
      assertEquals(384,response.population().count());
      assertEquals(16,response.authorNames().count());
      assertEquals(256,response.authorNames().eligibleRecords());
      assertEquals(256,metric(response,"geography").eligibleRecords());
      assertEquals(128,metric(response,"geography").missingOrRestrictedRecords());
      assertEquals(Map.of("江苏省",16.0),items(response,"geography"));
      assertFalse(response.toString().contains("PRIVATE_CORE"));
      assertFalse(response.toString().contains("UNMAPPED_SECRET"));
      jdbc.update("""
         UPDATE catalog_annotation_revisions SET normalized_value='PRIVATE_CHANGED',raw_value='PRIVATE_CHANGED'
         WHERE annotation_file_id='shared-2' AND field_key IN ('core.author_name','legacy.author_birth_place','legacy.style')
         """);
      assertEquals(response,store.paintingStatistics());

      // Inspect actual isolated execution rather than assert an exact planner
      // tree or elapsed time: annotations may be scanned once or once per
      // painting, but must not be rescanned once per field definition.
      var plan=new ObjectMapper().readTree(jdbc.queryForObject(
         "EXPLAIN (ANALYZE,FORMAT JSON,TIMING OFF) "+statisticsSql(),String.class)).get(0).get("Plan");
      var scans=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
      annotationScans(plan,scans);
      assertFalse(scans.isEmpty());
      assertTrue(scans.stream().allMatch(node->node.path("Actual Loops").asLong()<=384),
         "Annotation access must not repeat for each source-file field definition");
   }

   private void annotationScans(com.fasterxml.jackson.databind.JsonNode node,List<com.fasterxml.jackson.databind.JsonNode> scans) {
      if("catalog_annotation_revisions".equals(node.path("Relation Name").asText())) scans.add(node);
      for(var child:node.path("Plans")) annotationScans(child,scans);
   }

   private String statisticsSql() throws Exception {
      String sql;
      try(var stream=new ClassPathResource("knowledge/painting-statistics.sql").getInputStream()) {
         sql=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
            .replace("/* GALLERY_SOURCE */",PostgresCatalogReadStore.CURRENT_SOURCE)
            .replace("/* GALLERY_WHERE */",PostgresCatalogReadStore.GALLERY_WHERE);
      }
      return sql;
   }

   private void assertSameRowsAsPreviousQuery() throws Exception {
      String sql=statisticsSql();
      // Independent before-change oracle: keep all other aggregation logic,
      // including public projection, coverage, ordering and bounded outputs.
      String previous=sql.replaceFirst("(?s)candidate_values AS MATERIALIZED \\(.*?\\), usable_values AS", """
         public_values AS (
          SELECT p.public_id, ar.field_key,
                 btrim(regexp_replace(ar.normalized_value, '[[:space:]　]+', ' ', 'g')) AS value,
                 btrim(regexp_replace(ar.raw_value, '[[:space:]　]+', ' ', 'g')) AS raw_value
          FROM population p
          JOIN catalog_annotation_revisions ar ON ar.batch_id=p.batch_id AND ar.revision=p.revision
            AND ar.annotation_file_id=p.annotation_file_id AND ar.record_number=p.record_number
          JOIN catalog_annotation_field_definitions fd ON fd.batch_id=ar.batch_id AND fd.revision=ar.revision
            AND fd.annotation_file_id=ar.annotation_file_id AND fd.field_key=ar.field_key
          WHERE fd.visibility='public' AND ar.value_status='PRESENT'
           AND ar.assertion_status NOT IN ('UNRESOLVED','UNKNOWN') AND ar.field_key IN ('annotation.style','annotation.subject_secondary','core.author_name','core.category','legacy.artistic_conception','legacy.author_birth_place','legacy.author_name','legacy.brushwork','legacy.category','legacy.color','legacy.composition','legacy.creation_dynasty_raw','legacy.ink_method','legacy.music_scene_description','legacy.painting_school','legacy.style','legacy.subject')
         ), usable_values AS""");
      assertNotEquals(sql,previous,"Reference query must use the prior inline public annotation join");
      String previousJoin=previous;
      previous=previous.replaceFirst("(?s)best_region_candidates AS \\(.*?\\), region_matches AS", """
         best_region_candidates AS (
          SELECT c.* FROM region_candidates c WHERE c.priority=(SELECT min(c2.priority)
            FROM region_candidates c2 WHERE c2.public_id=c.public_id)
         ), region_matches AS""");
      assertNotEquals(previousJoin,previous,"Reference query must use the prior correlated selection");
      assertEquals(jdbc.queryForList(previous),jdbc.queryForList(sql),
         "All ordered metric rows, denominators, values and truncation metadata must remain identical");
   }

   @Test void tenThousandSyntheticRecordsKeepExactCountsAndBoundedPublicProjection() throws Exception {
      // Minimal extracted PostgreSQL runtimes may omit LLVM. This explicit,
      // connection-only opt-in leaves the server and other sessions unchanged.
      if ("true".equals(System.getenv("ARTLIVE_TEST_PG_DISABLE_JIT"))) {
         var source=new DriverManagerDataSource(System.getenv("ARTLIVE_TEST_JDBC_URL")+"?currentSchema="+schema,
            System.getenv("ARTLIVE_TEST_DB_USER"),System.getenv("ARTLIVE_TEST_DB_PASSWORD"));
         var properties=new java.util.Properties(); properties.setProperty("options","-c jit=off");
         source.setConnectionProperties(properties);
         jdbc=new JdbcTemplate(source);
         store=new PostgresCatalogReadStore(new NamedParameterJdbcTemplate(jdbc));
      }
      // Ten equally sized cohorts. IDs, categories and names are deterministic;
      // only the private test schema name is random. No application/public schema is used.
      jdbc.execute("""
         CREATE TABLE scale_records AS
         SELECT n, n %% 10 AS cohort, (n / 10) %% 50 AS name_index,
           md5('knowledge-scale-painting-' || n)::uuid AS id,
           CASE WHEN n %% 10=9 THEN md5('unselected-scale-batch')::uuid ELSE '%s'::uuid END AS batch_id
         FROM generate_series(0,9999) n
         """.formatted(batch));
      jdbc.execute("""
         INSERT INTO catalog_paintings
         SELECT id,batch_id,2,'synthetic scale','PRIVATE_CORE_AUTHOR','PRIVATE_CORE_CATEGORY',
           CASE WHEN cohort=8 THEN 'INCOMPLETE' ELSE 'ACTIVE' END,
           CASE WHEN cohort=7 THEN 'MISSING' ELSE 'READY' END,NULL
         FROM scale_records
         """);
      jdbc.execute("""
         INSERT INTO catalog_source_records
         SELECT batch_id,2,'scale-' || cohort,n+1,id,'synthetic-scale:' || n,'VALID' FROM scale_records
         """);
      String other = UUID.randomUUID().toString();
      jdbc.update("INSERT INTO catalog_batch_current_revisions VALUES (?::uuid,1)",other);
      jdbc.update("""
         INSERT INTO catalog_source_records
         SELECT ?::uuid,1,'cross-batch',n+1,id,'cross-batch:' || n,'VALID' FROM scale_records WHERE n % 100=0
         """,other);
      jdbc.update("""
         INSERT INTO catalog_source_records
         SELECT ?::uuid,1,'old-revision',n+1,id,'old-revision:' || n,'VALID' FROM scale_records WHERE n % 100=0
         """,batch);
      // 66,000 annotation rows across ten shared source-file definitions. Exact
      // huaniao keys supply style/subject but no invented dynasty or birthplace.
      jdbc.execute("""
         CREATE TABLE scale_values AS
         SELECT r.*,v.field_key,
           CASE WHEN cohort=3 THEN '0'
                WHEN cohort=4 THEN 'SECRET_' || field_key
                WHEN cohort=5 THEN CASE WHEN field_key='core.author_name' THEN '不详'
                                       WHEN field_key='core.category' THEN '未知' ELSE '有文本但缺失' END
                ELSE v.value END AS value
         FROM scale_records r CROSS JOIN LATERAL (
           VALUES
           ('core.author_name',CASE WHEN cohort % 2=0 THEN '  画家   ' || name_index || '　' ELSE '画家 ' || name_index END),
           ('core.category',CASE WHEN cohort=2 THEN '花鸟' WHEN cohort=6 THEN '人物' ELSE '山水' END),
           (CASE WHEN cohort=2 THEN 'annotation.style' ELSE 'legacy.style' END,
              CASE WHEN cohort=2 THEN '写意' WHEN cohort=6 THEN NULL ELSE '工笔、工笔；写意' END),
           ('legacy.creation_dynasty_raw',CASE WHEN cohort=2 THEN NULL WHEN cohort=0 THEN '唐' WHEN cohort=6 THEN '元代' ELSE '唐朝' END),
           ('legacy.author_birth_place',CASE WHEN cohort=2 THEN NULL WHEN cohort=6 THEN '未映射地点' ELSE '江苏苏州' END),
           ('legacy.artistic_conception',CASE WHEN cohort IN (2,6) THEN NULL ELSE '宁静' END),
           ('legacy.music_scene_description',CASE WHEN cohort=2 THEN NULL WHEN cohort=6 THEN '没有规则命中的描述' ELSE '欢快喜悦' END),
           ('annotation.subject_secondary',CASE WHEN cohort=2 THEN '花鸟、春日' ELSE NULL END),
           ('annotation.creation_year',CASE WHEN cohort=2 THEN '2024' ELSE NULL END)
         ) v(field_key,value) WHERE v.value IS NOT NULL
         """);
      jdbc.execute("""
         INSERT INTO catalog_annotation_field_definitions
         SELECT DISTINCT batch_id,2,'scale-' || cohort,field_key,
           CASE WHEN cohort=4 THEN CASE WHEN field_key='core.author_name' THEN 'internal' ELSE 'hidden' END ELSE 'public' END
         FROM scale_values
         """);
      jdbc.execute("""
         INSERT INTO catalog_annotation_revisions
         SELECT batch_id,2,'scale-' || cohort,n+1,field_key,value,value,
           CASE WHEN cohort=5 AND field_key NOT IN ('core.author_name','core.category') THEN 'MISSING' ELSE 'PRESENT' END,
           CASE WHEN cohort=3 THEN 'UNRESOLVED' ELSE 'UNSPECIFIED' END FROM scale_values
         """);
      // Twenty additional deterministic source columns per painting model a
      // wider source schema. Half are public but unsupported, half hidden: neither
      // may influence metric labels, denominators or counts.
      jdbc.execute("""
         INSERT INTO catalog_annotation_field_definitions
         SELECT DISTINCT r.batch_id,2,'scale-' || cohort,'annotation.extra_' || x,
           CASE WHEN x<=10 THEN 'public' ELSE 'hidden' END
         FROM scale_records r CROSS JOIN generate_series(1,20) x
         """);
      jdbc.execute("""
         INSERT INTO catalog_annotation_revisions
         SELECT batch_id,2,'scale-' || cohort,n+1,'annotation.extra_' || x,
           'UNMAPPED_SECRET_' || x,'UNMAPPED_SECRET_' || x,'PRESENT','UNSPECIFIED'
         FROM scale_records CROSS JOIN generate_series(1,20) x
         """);
      // Match the catalogue's join index shapes, then collect planner statistics.
      jdbc.execute("CREATE UNIQUE INDEX scale_source_key ON catalog_source_records(batch_id,revision,annotation_file_id,record_number)");
      jdbc.execute("CREATE INDEX scale_source_painting ON catalog_source_records(painting_id)");
      jdbc.execute("CREATE UNIQUE INDEX scale_field_key ON catalog_annotation_field_definitions(batch_id,revision,annotation_file_id,field_key)");
      jdbc.execute("CREATE UNIQUE INDEX scale_annotation_key ON catalog_annotation_revisions(batch_id,revision,annotation_file_id,record_number,field_key)");
      for(String table:List.of("catalog_paintings","catalog_source_records","catalog_annotation_revisions","catalog_annotation_field_definitions")) jdbc.execute("ANALYZE "+table);

      var millis=new ArrayList<Double>();
      PaintingStatisticsResponse response=null;
      for(int sample=0;sample<5;sample++) {
         long start=System.nanoTime();
         var current=store.paintingStatistics();
         millis.add((System.nanoTime()-start)/1_000_000.0);
         if(response!=null) assertEquals(response,current,"Repeated reads must be deterministic");
         response=current;
      }
      assertNotNull(response);
      assertEquals(10000,jdbc.queryForObject("SELECT count(*) FROM catalog_paintings",Integer.class));
      assertEquals(10200,jdbc.queryForObject("SELECT count(*) FROM catalog_source_records",Integer.class));
      assertEquals(266000,jdbc.queryForObject("SELECT count(*) FROM catalog_annotation_revisions",Integer.class));
      assertEquals(7000,response.population().count());
      var query = new CatalogReadStore.CatalogQuery(null,null,null,null,null,null,null,null,null,null,null,0,1,"source","asc");
      assertEquals(7000,store.list(query).totalElements());
      assertEquals(50,response.authorNames().count());
      assertEquals(4000,response.authorNames().eligibleRecords());
      assertEquals(Map.of("山水",2000.0,"花鸟",1000.0,"人物",1000.0),items(response,"category"));
      assertEquals(Map.of("唐代",2000.0,"元代",1000.0),items(response,"dynasty"));
      assertEquals(Map.of("写意",3000.0,"工笔",2000.0),items(response,"style"));
      assertEquals(Map.of("江苏省",50.0),items(response,"geography"));
      assertEquals(Map.of("欢快",2000.0),items(response,"sentiment"));
      assertEquals(Map.of("唐代",61.0),items(response,"sentimentByDynasty"));
      assertEquals(Map.of("写意",3000.0,"工笔",2000.0,"宁静",2000.0,"花鸟",1000.0,"春日",1000.0),items(response,"imagery"));
      Map<String,Long> eligible=Map.of("dynasty",3000L,"category",4000L,"geography",3000L,"style",3000L,"imagery",3000L,"sentiment",3000L,"sentimentByDynasty",2000L);
      for(var entry:eligible.entrySet()) {
         var metric=metric(response,entry.getKey());
         assertEquals(entry.getValue(),metric.eligibleRecords());
         assertEquals(7000-entry.getValue(),metric.missingOrRestrictedRecords());
         assertEquals(List.of("geography","sentiment").contains(entry.getKey())?1000:0,metric.unmatchedRecords());
         assertFalse(metric.truncated());
      }
      byte[] json=new ObjectMapper().writeValueAsBytes(response);
      assertTrue(json.length<16000,"Aggregated payload remains bounded at 10,000 records");
      assertFalse(new String(json,java.nio.charset.StandardCharsets.UTF_8).contains("SECRET"));
      jdbc.update("UPDATE catalog_annotation_revisions SET raw_value='宁静 唐代 工笔',normalized_value='宁静 唐代 工笔' WHERE annotation_file_id='scale-4'");
      assertEquals(response,store.paintingStatistics(),"Changing hidden and unsupported values cannot change any public statistic");
      keepScaleSchema="true".equals(System.getenv("ARTLIVE_TEST_KEEP_SCALE_SCHEMA"));
      System.out.println("KNOWLEDGE_SCALE_EVIDENCE="+new ObjectMapper().writeValueAsString(Map.ofEntries(
         Map.entry("paintings",10000),Map.entry("sourceMemberships",10200),Map.entry("annotationRows",jdbc.queryForObject("SELECT count(*) FROM catalog_annotation_revisions",Integer.class)),
         Map.entry("galleryPopulation",7000),Map.entry("normalizedAuthorNames",50),Map.entry("jdbcMilliseconds",millis),Map.entry("jsonBytes",json.length),
         Map.entry("jit",jdbc.queryForObject("SHOW jit",String.class)),Map.entry("schema",schema),Map.entry("retainedForLocalHttp",keepScaleSchema),
         Map.entry("method","Five sequential store calls after synthetic seed and ANALYZE; first cold JVM call included; no production data or load guarantee"))));
   }

   private static Map<String,Double> items(PaintingStatisticsResponse response,String key) {
      return metric(response,key).items().stream().collect(java.util.stream.Collectors.toMap(PaintingStatisticsResponse.Item::name,PaintingStatisticsResponse.Item::value));
   }

   @Test void emptyCatalogStillReturnsEveryMetric() {
      var response=store.paintingStatistics();
      assertEquals(0,response.population().count());
      assertEquals(7,response.metrics().size());
      assertTrue(response.metrics().values().stream().allMatch(m->m.status().equals("unavailable")&&m.items().isEmpty()));
   }

   @Test void fullMetricPagesReachLowFrequencyTailBeyondAllLegacyLimits() {
      for (int i = 0; i < 231; i++) {
         painting(i, "ACTIVE", "READY", true);
         value(i, "legacy.style", "public", String.format("词%03d", i));
      }
      painting(1001, "ACTIVE", "READY", true);
      value(1001, "legacy.style", "private", "SECRET_STYLE");
      var overview = store.paintingStatistics();
      assertEquals(12, overview.metrics().get("style").items().size());
      assertTrue(overview.metrics().get("style").truncated());
      var first = store.paintingMetric(new com.auralink.api.v1.knowledge.PaintingMetricQuery("style", 0, 100, "", "name", null));
      assertEquals(231, first.totalItems());
      assertEquals(3, first.totalPages());
      var names = new ArrayList<String>();
      for (int page = 0; page < first.totalPages(); page++) {
         var result = store.paintingMetric(new com.auralink.api.v1.knowledge.PaintingMetricQuery("style", page, 100, "", "name", first.revision()));
         names.addAll(result.items().stream().map(PaintingStatisticsResponse.Item::name).toList());
         assertEquals(page < 2, result.hasMore());
      }
      assertEquals(231, names.size());
      assertEquals(231, names.stream().distinct().count());
      assertEquals("词230", names.get(names.size() - 1));
      var tail = store.paintingMetric(new com.auralink.api.v1.knowledge.PaintingMetricQuery("style", 0, 100, "词230", "value", first.revision()));
      assertEquals(List.of(new PaintingStatisticsResponse.Item("词230", 1)), tail.items());
      var hidden = store.paintingMetric(new com.auralink.api.v1.knowledge.PaintingMetricQuery("style", 0, 100, "SECRET", "value", first.revision()));
      assertTrue(hidden.items().isEmpty());
   }

   private static PaintingStatisticsResponse.Metric metric(PaintingStatisticsResponse response,String key) {return response.metrics().get(key);}
   private static double item(PaintingStatisticsResponse response,String metric,String name) {
      return metric(response,metric).items().stream().filter(i->i.name().equals(name)).findFirst().orElseThrow().value();
   }
}
