package com.auralink.assistant;

import com.auralink.catalogread.AssistantArtworkRead;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.auralink.assistant.AssistantExecutionRecords.Citation;

/** Bounded, explicit selection snapshots. No SQL, model planning or image fetching. */
final class AssistantArtworkContext {
 static final int MAX_SOURCES=5, SOURCE_BYTES=6000, TOTAL_BYTES=30000, VALUE_BYTES=512;
 static final int REFERENCE_WIRE_BYTES=48000, AGGREGATE_TEXT_BYTES=131072;
 private final ObjectMapper json;
 AssistantArtworkContext(ObjectMapper json){this.json=json;}

 Citation snapshot(AssistantArtworkRead.Artwork art,int ordinal,boolean imageSupplied,Instant at){
  if(ordinal<1||ordinal>MAX_SOURCES)throw limit();
  List<AssistantArtworkRead.Field> selected=new ArrayList<>();boolean truncated=false;
  List<AssistantArtworkRead.Field> ordered=new ArrayList<>(art.fields());
  // Identity fields survive the budget ahead of long interpretation annotations.
  ordered.sort(Comparator.comparingInt(f->priority(f.key())));
  for(var field:ordered){
   String value=clip(field.value(),VALUE_BYTES),raw=clip(field.rawValue(),VALUE_BYTES);
   truncated|=!Objects.equals(value,field.value())||!Objects.equals(raw,field.rawValue());
   var bounded=new AssistantArtworkRead.Field(field.key(),value,raw,field.valueStatus(),field.assertionStatus());
   selected.add(bounded);
   if(bytes(encode(selected))>SOURCE_BYTES){selected.remove(selected.size()-1);truncated=true;}
  }
  String excerpt=encode(selected);var provenance=new LinkedHashMap<String,Object>();
  provenance.put("artworkId",art.artworkId().toString());provenance.put("batchId",art.batchId().toString());
  provenance.put("revision",art.revision());provenance.put("projectionVersion",art.projectionVersion());
  provenance.put("sourceRecordId",art.sourceRecordId());provenance.put("recordNumber",art.recordNumber());
  provenance.put("retrievedAt",at.toString());provenance.put("detailUrl","/gallery/"+art.artworkId());
  provenance.put("sourceProjectionSha256",sha(encode(art.fields())));
  provenance.put("selectionMethod","EXPLICIT_USER_SELECTION");provenance.put("contextTruncated",truncated);
  provenance.put("sourceTextPreviewLimit",2000); // The public reader already bounds each value.
  provenance.put("excerptBytes",bytes(excerpt));provenance.put("imageSupplied",imageSupplied);
  provenance.put("deliveryEvidence","MODEL_STREAM_OBSERVED");
  return new Citation(UUID.randomUUID(),ordinal,"PLATFORM_ARTWORK",art.artworkId().toString(),art.batchId()+":"+art.revision(),art.projectionVersion(),provenance,excerpt,sha(excerpt));
 }
 List<Map<String,Object>> wire(List<Citation> citations){
  if(citations.stream().filter(c->c.sourceType().equals("PLATFORM_ARTWORK")).count()>MAX_SOURCES)throw limit();
  if(citations.stream().filter(c->c.sourceType().equals("PLATFORM_ARTWORK")).mapToInt(c->bytes(c.excerpt())).sum()>TOTAL_BYTES)throw limit();
  var result=citations.stream().map(c->{var item=new LinkedHashMap<String,Object>();item.put("id",c.id().toString());item.put("type",c.sourceType());item.put("text",c.excerpt());
   if(c.sourceType().equals("PLATFORM_ARTWORK")){item.put("artworkId",c.sourceId());item.put("batchId",c.provenance().get("batchId"));item.put("version",c.sourceVersion());item.put("sourceRecordId",c.provenance().get("sourceRecordId"));
    item.put("source",Map.of("kind","USER_SELECTED_PLATFORM_RECORD","imageSupplied",Boolean.TRUE.equals(c.provenance().get("imageSupplied")),"contextTruncated",Boolean.TRUE.equals(c.provenance().get("contextTruncated")),"missingFieldsAreUnknown",true));}
   return (Map<String,Object>)item;
  }).toList();
  if(bytes(encode(result))>REFERENCE_WIRE_BYTES)throw limit();return result;
 }
 void validateTextBudget(List<Map<String,Object>> messages,List<Map<String,Object>> citations){
  long total=bytes(encode(citations));
  for(var message:messages)total+=bytes(Objects.toString(message.get("content"),""));
  // Reserve 8 KiB for the fixed system instruction and retrieval-state envelope.
  if(total+8192>AGGREGATE_TEXT_BYTES)throw limit();
 }
 static String projectionHash(Citation c){Object hash=c.provenance().get("sourceProjectionSha256");return hash instanceof String s?s:c.excerptSha256();}
 static String clip(String value,int bytes){
  if(value==null)return null;int used=0,end=0;
  while(end<value.length()){int cp=value.codePointAt(end);int size=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;if(used+size>bytes)break;used+=size;end+=Character.charCount(cp);}
  return value.substring(0,end);
 }
 private static int priority(String key){return switch(key){case "core.title"->0;case "legacy.title","legacy.painting_name"->1;case "core.author_name","legacy.author_name"->2;case "core.category","legacy.category"->3;case "legacy.creation_dynasty_raw"->4;default->5;};}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw limit();}}
 static String sha(String value){return AssistantAttachmentStorage.sha256(value.getBytes(StandardCharsets.UTF_8));}
 static int bytes(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
 private static AssistantFailure limit(){return new AssistantFailure(422,"ASSISTANT_ARTWORK_CONTEXT_LIMIT","Selected artwork context exceeds its bounded input budget");}
}
