package com.auralink.catalogread;

import com.auralink.assistant.AssistantFailure;
import com.auralink.catalog.DynastyNormalizer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Assistant public projection. Gallery eligibility and canonical current-revision membership are shared. */
public final class AssistantArtworkRead {
 public static final String PROJECTION="assistant-public-artwork-v1";
 private static final int MAX_PAGE=100_000;
 private static final List<String> FIELDS=List.of("core.title","core.author_name","core.category","legacy.title","legacy.painting_name","legacy.author_name","legacy.category","legacy.creation_dynasty_raw","legacy.subject","legacy.painting_school","legacy.style","legacy.color","legacy.composition","legacy.artistic_conception","legacy.brushwork","legacy.ink_method","legacy.painting_material","legacy.cultural_symbol","annotation.style","annotation.subject_secondary");
 private static final List<String> KEYWORD_FIELDS=List.of("core.title","legacy.title","legacy.painting_name","core.author_name","legacy.author_name","legacy.subject");
 private static final Map<String,List<String>> FILTER_FIELDS=Map.ofEntries(
  Map.entry("dynasty",List.of("legacy.creation_dynasty_raw")),Map.entry("category",List.of("core.category","legacy.category")),
  Map.entry("author",List.of("core.author_name","legacy.author_name")),Map.entry("subject",List.of("legacy.subject")),
  Map.entry("paintingSchool",List.of("legacy.painting_school")),Map.entry("style",List.of("legacy.style")),
  Map.entry("artisticConception",List.of("legacy.artistic_conception")),Map.entry("paintingMaterial",List.of("legacy.painting_material")),
  Map.entry("collectionInstitution",List.of("legacy.collection_institution")),Map.entry("collectionPlatform",List.of("legacy.collection_platform")));
 private static final String PUBLIC_JOIN=" FROM catalog_annotation_revisions ar JOIN catalog_annotation_field_definitions fd ON fd.batch_id=ar.batch_id AND fd.revision=ar.revision AND fd.annotation_file_id=ar.annotation_file_id AND fd.field_key=ar.field_key WHERE ar.batch_id=sr.batch_id AND ar.revision=sr.revision AND ar.annotation_file_id=sr.annotation_file_id AND ar.record_number=sr.record_number AND fd.visibility='public'";
 private final NamedParameterJdbcTemplate jdbc;
 private final org.springframework.transaction.support.TransactionTemplate snapshot;
 public AssistantArtworkRead(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;this.snapshot=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));snapshot.setReadOnly(true);snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setTimeout(5);}
 public record Artwork(UUID artworkId,UUID batchId,int revision,UUID mediaAssetId,@com.fasterxml.jackson.annotation.JsonIgnore String annotationFileId,int recordNumber,String projectionVersion,List<Field> fields){
  @com.fasterxml.jackson.annotation.JsonProperty public int fieldTextLimit(){return 2000;}
  @com.fasterxml.jackson.annotation.JsonProperty public String sourceRecordId(){
   String identity=batchId+"\n"+revision+"\n"+annotationFileId.length()+":"+annotationFileId+"\n"+recordNumber;
   try{return "sha256:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
 }
 public record Field(String key,String value,String rawValue,String valueStatus,String assertionStatus){}
 public record Page(List<Artwork> items,long totalElements,long totalPages,int page,int size){}
 /** Bounded public filters only. Unknown filter names must not silently broaden a query. */
 public record Query(String keyword,Map<String,String> filters,int page,int size,String sort,String direction){
  public Query{
   keyword=normalized(keyword,200);filters=filters==null?Map.of():Map.copyOf(filters);
   var safe=new LinkedHashMap<String,String>();
   for(var entry:filters.entrySet()){
    if(!FILTER_FIELDS.containsKey(entry.getKey()))throw AssistantFailure.invalid("Unsupported artwork filter");
    String value=normalized(entry.getValue(),512);
    if(value!=null){if(entry.getKey().equals("dynasty"))value=new DynastyNormalizer().normalize(value);safe.put(entry.getKey(),value);}
   }
   filters=Collections.unmodifiableMap(safe);sort=sort==null||sort.isBlank()?"source":sort.strip().toLowerCase(Locale.ROOT);direction=direction==null||direction.isBlank()?"asc":direction.strip().toLowerCase(Locale.ROOT);
   if(page<0||page>MAX_PAGE||size<1||size>10||!Set.of("source","title","author","dynasty").contains(sort)||!Set.of("asc","desc").contains(direction))throw AssistantFailure.invalid("Artwork pagination or sort is invalid");
  }
 }
 public Page page(Query query){
  Objects.requireNonNull(query);return readSnapshot(()->{
   var params=new MapSqlParameterSource("limit",query.size()).addValue("offset",(long)query.page()*query.size());
   StringBuilder where=new StringBuilder(PostgresCatalogReadStore.GALLERY_WHERE);
   if(query.keyword()!=null){params.addValue("keyword",query.keyword()).addValue("keywordFields",KEYWORD_FIELDS);where.append(" AND EXISTS(SELECT 1").append(PUBLIC_JOIN).append(" AND ar.field_key IN (:keywordFields) AND (strpos(lower(coalesce(ar.normalized_value,'')),lower(:keyword))>0 OR strpos(lower(coalesce(ar.raw_value,'')),lower(:keyword))>0))");}
   for(var filter:query.filters().entrySet()){
    String key=filter.getKey();params.addValue(key,filter.getValue()).addValue(key+"Fields",FILTER_FIELDS.get(key));
    where.append(" AND EXISTS(SELECT 1").append(PUBLIC_JOIN).append(" AND ar.field_key IN (:").append(key).append("Fields) AND lower(ar.normalized_value)=lower(:").append(key).append("))");
   }
   String from=PostgresCatalogReadStore.CURRENT_SOURCE+where;
   Long count=jdbc.queryForObject("SELECT count(*) "+from,params,Long.class);long total=count==null?0:count;
   String order=sortExpression(query.sort(),params)+" "+query.direction().toUpperCase(Locale.ROOT)+" NULLS LAST, p.public_id ASC";
   var rows=jdbc.query(select()+from+" ORDER BY "+order+" LIMIT :limit OFFSET :offset",params,this::mapArtwork);
   return new Page(List.copyOf(rows),total,(total+query.size()-1)/query.size(),query.page(),query.size());
  });
 }
 public Artwork get(UUID id){if(id==null)throw AssistantFailure.invalid("Artwork ID is required");return search("",List.of(id),1).stream().findFirst().orElseThrow(AssistantFailure::notFound);}
 public List<Artwork> search(String query,List<UUID> ids,int limit){return readSnapshot(()->read(query,ids,limit));}
 private List<Artwork> read(String query,List<UUID> ids,int limit){
  if(query==null)query="";if(query.length()>200||ids==null||ids.size()>5||ids.stream().anyMatch(Objects::isNull)||limit<1||limit>10)throw AssistantFailure.invalid("Artwork query is invalid");
  if(query.isBlank()&&ids.isEmpty())return List.of();
  var params=new MapSqlParameterSource("query",query.strip()).addValue("fields",FIELDS).addValue("limit",limit);
  String sql=select()+PostgresCatalogReadStore.CURRENT_SOURCE+PostgresCatalogReadStore.GALLERY_WHERE;
  if(!ids.isEmpty()){params.addValue("ids",ids);sql+=" AND p.public_id IN (:ids)";}
  else sql+=" AND EXISTS(SELECT 1"+PUBLIC_JOIN+" AND ar.field_key IN (:fields) AND (strpos(lower(coalesce(ar.normalized_value,'')),lower(:query))>0 OR strpos(lower(coalesce(ar.raw_value,'')),lower(:query))>0))";
  return jdbc.query(sql+" ORDER BY p.public_id LIMIT :limit",params,this::mapArtwork);
 }
 private static String select(){return "SELECT p.public_id,sr.batch_id,sr.revision,sr.annotation_file_id,sr.record_number,p.image_asset_id ";}
 private Artwork mapArtwork(ResultSet rs,int row)throws SQLException{
  UUID batch=rs.getObject("batch_id",UUID.class);int revision=rs.getInt("revision");
  var fp=new MapSqlParameterSource("batch",batch).addValue("revision",revision).addValue("file",rs.getObject("annotation_file_id")).addValue("record",rs.getInt("record_number")).addValue("fields",FIELDS);
  var fields=jdbc.query("SELECT ar.field_key,left(ar.normalized_value,2000),left(ar.raw_value,2000),ar.value_status,ar.assertion_status FROM catalog_annotation_revisions ar JOIN catalog_annotation_field_definitions fd ON fd.batch_id=ar.batch_id AND fd.revision=ar.revision AND fd.annotation_file_id=ar.annotation_file_id AND fd.field_key=ar.field_key WHERE ar.batch_id=:batch AND ar.revision=:revision AND ar.annotation_file_id=:file AND ar.record_number=:record AND fd.visibility='public' AND ar.field_key IN (:fields) ORDER BY ar.field_key LIMIT 50",fp,(f,i)->new Field(f.getString(1),bounded(f.getString(2)),bounded(f.getString(3)),f.getString(4),f.getString(5)));
  return new Artwork(rs.getObject("public_id",UUID.class),batch,revision,rs.getObject("image_asset_id",UUID.class),rs.getString("annotation_file_id"),rs.getInt("record_number"),PROJECTION,fields);
 }
 private static String sortExpression(String sort,MapSqlParameterSource params){
  if(sort.equals("source"))return "sr.source_record_key";
  List<String> fields=switch(sort){case "title"->List.of("core.title","legacy.title","legacy.painting_name");case "author"->List.of("core.author_name","legacy.author_name");case "dynasty"->List.of("legacy.creation_dynasty_raw");default->throw AssistantFailure.invalid("Unsupported artwork sort");};
  params.addValue("sortFields",fields);return "(SELECT min(ar.normalized_value)"+PUBLIC_JOIN+" AND ar.field_key IN (:sortFields))";
 }
 private <T>T readSnapshot(Supplier<T> read){try{return snapshot.execute(status->read.get());}catch(QueryTimeoutException | org.springframework.transaction.TransactionTimedOutException timeout){throw new AssistantFailure(504,"ASSISTANT_ARTWORK_TIMEOUT","画作查询超时，请缩小筛选范围后重试");}catch(DataAccessException unavailable){for(Throwable cause=unavailable;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&"57014".equals(sql.getSQLState()))throw new AssistantFailure(504,"ASSISTANT_ARTWORK_TIMEOUT","画作查询超时，请缩小筛选范围后重试");throw new AssistantFailure(503,"ASSISTANT_ARTWORK_UNAVAILABLE","画作资料暂时不可用，请稍后重试");}}
 public Artwork require(UUID id,UUID batch,int revision){var value=get(id);if(!value.batchId().equals(batch)||value.revision()!=revision)throw new AssistantFailure(409,"ASSISTANT_ARTWORK_REVISION_CHANGED","Artwork revision changed; select the current public version");return value;}
 private static String normalized(String value,int max){if(value==null)return null;if(value.length()>max)throw AssistantFailure.invalid("Artwork filter is too long");String result=value.strip();return result.isEmpty()?null:result;}
 private static String bounded(String value){if(value==null)return null;int end=Math.min(2000,value.length());if(end<value.length()&&end>0&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;return value.substring(0,end);}
}
