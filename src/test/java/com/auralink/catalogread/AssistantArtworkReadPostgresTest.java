package com.auralink.catalogread;

import com.auralink.assistant.AssistantFailure;
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

/** Real PostgreSQL fixture preserving V001's TEXT annotation-file identities. */
@EnabledIfEnvironmentVariable(named="ASSISTANT_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/assistant_foundation_test")
class AssistantArtworkReadPostgresTest {
 private JdbcTemplate admin,jdbc;private String schema;private UUID batch;private AssistantArtworkRead assistant;private PostgresCatalogReadStore gallery;
 @BeforeEach void setup(){
  String url=System.getenv("ASSISTANT_TEST_JDBC_URL"),user=System.getenv("ASSISTANT_TEST_JDBC_USER"),password=System.getenv("ASSISTANT_TEST_JDBC_PASSWORD");admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="assistant_artwork_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema="+schema,user,password));
  jdbc.execute("CREATE TABLE catalog_batch_current_revisions(batch_id uuid,revision int)");
  jdbc.execute("CREATE TABLE catalog_source_records(batch_id uuid,revision int,annotation_file_id text,record_number int,painting_id uuid,source_record_key text,record_state text)");
  jdbc.execute("CREATE TABLE catalog_paintings(public_id uuid PRIMARY KEY,current_batch_id uuid,current_revision int,title text,author_name text,category text,catalog_status text,image_display_status text,image_asset_id uuid)");
  jdbc.execute("CREATE TABLE catalog_media_assets(public_id uuid PRIMARY KEY,source_batch_id uuid,source_revision int,relative_path text,storage_uri text,content_sha256 text,media_status text)");
  jdbc.execute("CREATE TABLE catalog_annotation_field_definitions(batch_id uuid,revision int,annotation_file_id text,field_key text,visibility text)");
  jdbc.execute("CREATE TABLE catalog_annotation_revisions(batch_id uuid,revision int,annotation_file_id text,record_number int,field_key text,raw_value text,normalized_value text,value_status text,assertion_status text)");
  var named=new NamedParameterJdbcTemplate(jdbc);assistant=new AssistantArtworkRead(named);gallery=new PostgresCatalogReadStore(named);batch=UUID.randomUUID();jdbc.update("INSERT INTO catalog_batch_current_revisions VALUES(?,2)",batch);
 }
 @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
 private UUID painting(int n,String status,String image,boolean published){UUID id=UUID.randomUUID(),source=published?batch:UUID.randomUUID();jdbc.update("INSERT INTO catalog_paintings VALUES(?,?,2,'PRIVATE_DENORMALIZED_TITLE','PRIVATE_AUTHOR','PRIVATE_CATEGORY',?,?,NULL)",id,source,status,image);jdbc.update("INSERT INTO catalog_source_records VALUES(?,2,?,?,?,?,'VALID')",source,"fixture-file-"+n,n,id,"fixture-source-"+n);return id;}
 private void value(int n,String field,String visibility,String raw,String normalized,String valueState,String assertion){jdbc.update("INSERT INTO catalog_annotation_field_definitions VALUES(?,2,?,?,?)",batch,"fixture-file-"+n,field,visibility);jdbc.update("INSERT INTO catalog_annotation_revisions VALUES(?,2,?,?,?,?,?,?,?)",batch,"fixture-file-"+n,n,field,raw,normalized,valueState,assertion);}
 private AssistantFailure fails(int code,Runnable action){var e=assertThrows(AssistantFailure.class,action::run);assertEquals(code,e.status());return e;}
 @Test void canonicalCurrentMembershipMatchesGalleryAndDeduplicatesPublishedBatchHeads(){
  UUID first=painting(1,"ACTIVE","READY",true);painting(2,"ACTIVE","READY",true);painting(3,"ACTIVE","MISSING",true);painting(4,"INACTIVE","READY",true);painting(5,"ACTIVE","READY",false);
  value(1,"core.title","public","当前山水","当前山水","PRESENT","UNSPECIFIED");value(2,"core.title","public","当前花鸟","当前花鸟","PRESENT","UNSPECIFIED");value(3,"core.title","public","当前缺图","当前缺图","PRESENT","UNSPECIFIED");value(4,"core.title","public","当前禁用","当前禁用","PRESENT","UNSPECIFIED");
  UUID other=UUID.randomUUID();jdbc.update("INSERT INTO catalog_batch_current_revisions VALUES(?,1)",other);jdbc.update("INSERT INTO catalog_source_records VALUES(?,1,'other-published-file',1,?,'retained-membership','VALID')",other,first);jdbc.update("INSERT INTO catalog_source_records VALUES(?,1,'old-source-file',1,?,'old-revision','VALID')",batch,first);
  var results=assistant.search("当前",List.of(),10);var query=new CatalogReadStore.CatalogQuery(null,null,null,null,null,null,null,null,null,null,null,0,24,"source","asc");assertEquals(2,gallery.list(query).totalElements());assertEquals(gallery.list(query).totalElements(),results.size());assertEquals(1,results.stream().filter(r->r.artworkId().equals(first)).count());var row=results.stream().filter(r->r.artworkId().equals(first)).findFirst().orElseThrow();assertEquals(batch,row.batchId());assertEquals(2,row.revision());assertEquals("fixture-file-1",row.annotationFileId());
 }
 @Test void hiddenKeywordsAndDenormalizedFallbackNeverBecomeRetrievalEvidence(){UUID id=painting(1,"ACTIVE","READY",true);value(1,"core.title","hidden","PRIVATE_KEYWORD","PRIVATE_KEYWORD","PRESENT","UNSPECIFIED");value(1,"core.author_name","internal","INTERNAL_AUTHOR","INTERNAL_AUTHOR","PRESENT","UNSPECIFIED");value(1,"legacy.image_storage_name","public","/private/root/image.jpg","/private/root/image.jpg","PRESENT","UNSPECIFIED");value(1,"source.relative_image","public","private-relative.jpg","private-relative.jpg","PRESENT","UNSPECIFIED");assertTrue(assistant.search("PRIVATE_KEYWORD",List.of(),10).isEmpty());assertTrue(assistant.search("PRIVATE_DENORMALIZED",List.of(),10).isEmpty());assertTrue(assistant.search("INTERNAL_AUTHOR",List.of(),10).isEmpty());assertTrue(assistant.search("private",List.of(),10).isEmpty());var row=assistant.require(id,batch,2);assertTrue(row.fields().isEmpty());assertFalse(row.toString().contains("PRIVATE"));assertFalse(row.toString().contains("/private/root"));}
 @Test void uncertaintyAndLiteralZeroRemainRawEvidenceRatherThanInventedFacts(){UUID id=painting(1,"ACTIVE","READY",true);value(1,"core.author_name","public","0","0","PRESENT","UNRESOLVED");value(1,"legacy.creation_dynasty_raw","public","不详","未知","PRESENT","UNKNOWN");value(1,"annotation.style","public"," ",null,"MISSING","UNSPECIFIED");var row=assistant.require(id,batch,2);var author=row.fields().stream().filter(f->f.key().equals("core.author_name")).findFirst().orElseThrow();assertEquals("0",author.rawValue());assertEquals("0",author.value());assertEquals("UNRESOLVED",author.assertionStatus());var dynasty=row.fields().stream().filter(f->f.key().equals("legacy.creation_dynasty_raw")).findFirst().orElseThrow();assertEquals("不详",dynasty.rawValue());assertEquals("UNKNOWN",dynasty.assertionStatus());assertEquals(3,row.fields().size());assertFalse(row.toString().contains("PRIVATE_AUTHOR"));}
 @Test void uuidAndExpectedRevisionAreCheckedWithoutReturningObsoleteMembership(){UUID id=painting(1,"ACTIVE","READY",true);value(1,"core.title","public","old public","old public","PRESENT","UNSPECIFIED");assertEquals(id,assistant.require(id,batch,2).artworkId());fails(404,()->assistant.require(UUID.randomUUID(),batch,2));fails(409,()->assistant.require(id,UUID.randomUUID(),2));jdbc.update("UPDATE catalog_batch_current_revisions SET revision=3 WHERE batch_id=?",batch);jdbc.update("UPDATE catalog_paintings SET current_revision=3 WHERE public_id=?",id);jdbc.update("INSERT INTO catalog_source_records VALUES(?,3,'new-file',1,?,'new-source','VALID')",batch,id);jdbc.update("INSERT INTO catalog_annotation_field_definitions VALUES(?,3,'new-file','core.title','public')",batch);jdbc.update("INSERT INTO catalog_annotation_revisions VALUES(?,3,'new-file',1,'core.title','new public','new public','PRESENT','UNSPECIFIED')",batch);fails(409,()->assistant.require(id,batch,2));assertEquals(3,assistant.require(id,batch,3).revision());assertTrue(assistant.search("old public",List.of(),10).isEmpty());}
 @Test void permissionRevocationRemovesSearchAndProjectionWithoutErasingSource(){UUID id=painting(1,"ACTIVE","READY",true);value(1,"core.title","public","revocable title","revocable title","PRESENT","UNSPECIFIED");assertEquals(1,assistant.search("revocable",List.of(),10).size());jdbc.update("UPDATE catalog_annotation_field_definitions SET visibility='hidden' WHERE field_key='core.title'");assertTrue(assistant.search("revocable",List.of(),10).isEmpty());assertTrue(assistant.require(id,batch,2).fields().isEmpty());assertEquals("revocable title",jdbc.queryForObject("SELECT raw_value FROM catalog_annotation_revisions",String.class));}
 @Test void retrievalIsBoundedAndPlainPercentIsNotASqlWildcard(){for(int n=1;n<=4;n++){painting(n,"ACTIVE","READY",true);value(n,"core.title","public","山水 "+n,"山水 "+n,"PRESENT","UNSPECIFIED");}assertEquals(2,assistant.search("山水",List.of(),2).size());assertTrue(assistant.search("%",List.of(),10).isEmpty());assertTrue(assistant.search("",List.of(),10).isEmpty());fails(400,()->assistant.search("q".repeat(201),List.of(),1));fails(400,()->assistant.search("山水",List.of(),11));}
 @Test void publicSourceIdentityIsStableWithoutSerializingAnnotationPaths()throws Exception{
  UUID id=painting(1,"ACTIVE","READY",true);value(1,"core.title","public","公开标题","公开标题","PRESENT","UNSPECIFIED");String privateIdentity="/isolated-private/source/annotations.csv";
  for(String table:List.of("catalog_source_records","catalog_annotation_field_definitions","catalog_annotation_revisions"))jdbc.update("UPDATE "+table+" SET annotation_file_id=?",privateIdentity);
  var artwork=assistant.require(id,batch,2);assertEquals(privateIdentity,artwork.annotationFileId());var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var wire=mapper.readTree(mapper.writeValueAsString(artwork));assertFalse(wire.has("annotationFileId"));assertTrue(wire.get("sourceRecordId").asText().matches("sha256:[0-9a-f]{64}"));assertFalse(wire.toString().contains(privateIdentity));assertEquals(artwork.sourceRecordId(),assistant.require(id,batch,2).sourceRecordId());
  var different=new AssistantArtworkRead.Artwork(id,batch,2,null,privateIdentity,2,artwork.projectionVersion(),artwork.fields());assertNotEquals(artwork.sourceRecordId(),different.sourceRecordId());
 }
 private AssistantArtworkRead.Query query(String keyword,java.util.Map<String,String> filters,int page,int size,String sort,String direction){return new AssistantArtworkRead.Query(keyword,filters,page,size,sort,direction);}
 @Test void pagesKeepTotalSeparateFromSubsetAndAllowAmbiguousTitlesWithoutGuessing(){
  for(int n=1;n<=13;n++){painting(n,"ACTIVE","READY",true);value(n,"core.title","public","同名山水","同名山水","PRESENT","UNSPECIFIED");}
  var first=assistant.page(query("同名",java.util.Map.of(),0,10,"source","asc"));var second=assistant.page(query("同名",java.util.Map.of(),1,10,"source","asc"));
  assertEquals(13,first.totalElements());assertEquals(2,first.totalPages());assertEquals(10,first.items().size());assertEquals(3,second.items().size());
  assertTrue(first.items().stream().noneMatch(a->second.items().stream().anyMatch(b->b.artworkId().equals(a.artworkId()))));
  assertEquals(13,assistant.page(query(null,java.util.Map.of(),0,10,"title","asc")).totalElements());
  assertEquals(0,assistant.page(query("没有命中",java.util.Map.of(),0,10,"source","asc")).totalElements());
  assertTrue(assistant.page(query("同名",java.util.Map.of(),9,10,"source","asc")).items().isEmpty());
 }
 @Test void allGalleryFiltersRequirePublicDefinitionsAndNeverUsePrivateDenormalizedValues(){
  UUID match=painting(1,"ACTIVE","READY",true);painting(2,"ACTIVE","READY",true);
  String[][] mapping={{"dynasty","legacy.creation_dynasty_raw","宋代"},{"category","core.category","山水"},{"author","core.author_name","测试作者"},{"subject","legacy.subject","远山"},{"paintingSchool","legacy.painting_school","测试画派"},{"style","legacy.style","写意"},{"artisticConception","legacy.artistic_conception","澄明"},{"paintingMaterial","legacy.painting_material","纸本"},{"collectionInstitution","legacy.collection_institution","测试馆"},{"collectionPlatform","legacy.collection_platform","测试平台"}};
  var filters=new java.util.LinkedHashMap<String,String>();
  for(var entry:mapping){value(1,entry[1],"public",entry[2],entry[2],"PRESENT","UNVERIFIED");value(2,entry[1],"hidden",entry[2],entry[2],"PRESENT","UNVERIFIED");filters.put(entry[0],entry[2]);var page=assistant.page(query(null,java.util.Map.of(entry[0],entry[2]),0,10,"source","asc"));assertEquals(List.of(match),page.items().stream().map(AssistantArtworkRead.Artwork::artworkId).toList(),entry[0]);}
  assertEquals(1,assistant.page(query(null,filters,0,10,"author","desc")).totalElements());
  assertEquals(1,assistant.page(query(null,java.util.Map.of("dynasty","宋"),0,10,"dynasty","asc")).totalElements());
  assertEquals(0,assistant.page(query(null,java.util.Map.of("author","PRIVATE_AUTHOR"),0,10,"source","asc")).totalElements());
  assertTrue(assistant.get(match).fields().stream().noneMatch(f->f.key().startsWith("legacy.collection_")),"Filter-only fields must not expand the historically stable model projection");
 }
 @Test void pageKeywordAndSortNeverReadHiddenTitlesOrAuthors(){
  UUID first=painting(1,"ACTIVE","READY",true),second=painting(2,"ACTIVE","READY",true);
  value(1,"core.title","hidden","AAA_PRIVATE","AAA_PRIVATE","PRESENT","UNSPECIFIED");value(2,"core.title","internal","ZZZ_PRIVATE","ZZZ_PRIVATE","PRESENT","UNSPECIFIED");
  assertEquals(0,assistant.page(query("PRIVATE",java.util.Map.of(),0,10,"source","asc")).totalElements());
  var ascending=assistant.page(query(null,java.util.Map.of(),0,10,"title","asc"));var descending=assistant.page(query(null,java.util.Map.of(),0,10,"title","desc"));
  var expected=List.of(first,second).stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();assertEquals(expected,ascending.items().stream().map(AssistantArtworkRead.Artwork::artworkId).toList());assertEquals(expected,descending.items().stream().map(AssistantArtworkRead.Artwork::artworkId).toList());
 }
 @Test void pageAndDetailUseCurrentEligibleMembershipAndPreserveUnknownAnnotations(){
  UUID valid=painting(1,"ACTIVE","READY",true),hidden=painting(2,"INACTIVE","READY",true),missing=painting(3,"ACTIVE","MISSING",true),unpublished=painting(4,"ACTIVE","READY",false);
  value(1,"core.title","public","不详",null,"MISSING","UNVERIFIED");value(1,"annotation.style","public",null,null,"MISSING","UNKNOWN");
  var page=assistant.page(query(null,java.util.Map.of(),0,10,"source","asc"));assertEquals(1,page.totalElements());assertEquals(valid,page.items().get(0).artworkId());
  assertNull(assistant.get(valid).fields().get(0).value());assertTrue(assistant.get(valid).fields().stream().anyMatch(f->"UNVERIFIED".equals(f.assertionStatus())));
  for(UUID id:List.of(hidden,missing,unpublished,UUID.randomUUID()))fails(404,()->assistant.get(id));
 }
 @Test void invalidParametersCannotBroadenThePublicQuery(){
  fails(400,()->query(null,java.util.Map.of("sql","anything"),0,10,"source","asc"));fails(400,()->query("q".repeat(201),java.util.Map.of(),0,10,"source","asc"));
  fails(400,()->query(null,java.util.Map.of("author","字".repeat(513)),0,10,"source","asc"));
  fails(400,()->query(null,java.util.Map.of(),-1,10,"source","asc"));fails(400,()->query(null,java.util.Map.of(),100001,10,"source","asc"));fails(400,()->query(null,java.util.Map.of(),0,11,"source","asc"));fails(400,()->query(null,java.util.Map.of(),0,0,"source","asc"));fails(400,()->query(null,java.util.Map.of(),0,10,"random()","asc"));fails(400,()->query(null,java.util.Map.of(),0,10,"source","invalid"));
 }
 @Test void publicAnnotationTextIsBoundedWithoutSplittingUnicodeCodepoints(){
  UUID id=painting(1,"ACTIVE","READY",true);String text="山".repeat(1999)+"😀"+"水".repeat(4000);value(1,"annotation.style","public",text,text,"PRESENT","UNVERIFIED");var field=assistant.get(id).fields().get(0);assertEquals(1999,field.rawValue().length());assertFalse(Character.isSurrogate(field.rawValue().charAt(field.rawValue().length()-1)));assertEquals(2000,assistant.get(id).fieldTextLimit());
 }
 @Test void blockedCatalogQueryTimesOutAndDoesNotLeakSql()throws Exception{
  painting(1,"ACTIVE","READY",true);value(1,"core.title","public","山水","山水","PRESENT","UNSPECIFIED");
  try(var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
   connection.setAutoCommit(false);try(var statement=connection.createStatement()){statement.execute("LOCK TABLE catalog_annotation_revisions IN ACCESS EXCLUSIVE MODE");}
   long start=System.nanoTime();var failure=fails(504,()->assistant.page(query("山水",java.util.Map.of(),0,10,"source","asc")));long millis=(System.nanoTime()-start)/1_000_000;
   assertEquals("ASSISTANT_ARTWORK_TIMEOUT",failure.code());assertTrue(millis<7500,"bounded timeout elapsed="+millis);assertFalse(failure.getMessage().contains("catalog_annotation"));connection.rollback();
  }
  assertEquals(1,assistant.page(query("山水",java.util.Map.of(),0,10,"source","asc")).totalElements());
 }

}
