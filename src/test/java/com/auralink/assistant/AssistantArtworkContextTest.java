package com.auralink.assistant;

import com.auralink.catalogread.AssistantArtworkRead;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssistantArtworkContextTest {
 private final ObjectMapper json=new ObjectMapper();
 private final AssistantArtworkContext context=new AssistantArtworkContext(json);
 private AssistantArtworkRead.Artwork art(List<AssistantArtworkRead.Field> fields){return new AssistantArtworkRead.Artwork(UUID.randomUUID(),UUID.randomUUID(),2,null,"private/source.csv",1,"assistant-public-artwork-v1",fields);}
 @Test void preservesUncertaintyAndIdsWithoutInternalSourcePaths()throws Exception{
  var source=art(List.of(new AssistantArtworkRead.Field("core.author_name","0","0","PRESENT","UNRESOLVED"),new AssistantArtworkRead.Field("annotation.style",null,"未知","MISSING","UNKNOWN")));
  var c=context.snapshot(source,1,false,Instant.EPOCH);assertEquals(source.artworkId().toString(),c.sourceId());assertEquals(source.batchId()+":2",c.sourceVersion());
  assertEquals("MODEL_STREAM_OBSERVED",c.provenance().get("deliveryEvidence"));assertFalse((Boolean)c.provenance().get("imageSupplied"));
  assertTrue(c.excerpt().contains("UNRESOLVED"));assertTrue(c.excerpt().contains("MISSING"));assertTrue(c.excerpt().contains("未知"));assertFalse(json.writeValueAsString(c).contains("private/source"));
  assertEquals(AssistantArtworkContext.sha(json.writeValueAsString(source.fields())),AssistantArtworkContext.projectionHash(c));
 }
 @Test void longChineseIsBoundedOnUtf8BytesWithoutBreakingSurrogatePairs()throws Exception{
  var fields=new ArrayList<AssistantArtworkRead.Field>();for(int i=0;i<45;i++)fields.add(new AssistantArtworkRead.Field("annotation."+i,"花鸟😀".repeat(2000),"云山".repeat(2000),"PRESENT","UNVERIFIED"));
  fields.add(new AssistantArtworkRead.Field("core.title","同名长作品","同名长作品","PRESENT","UNSPECIFIED"));
  var c=context.snapshot(art(fields),1,false,Instant.EPOCH);assertTrue(AssistantArtworkContext.bytes(c.excerpt())<=6000);assertEquals(true,c.provenance().get("contextTruncated"));
  assertEquals("core.title",json.readTree(c.excerpt()).get(0).get("key").asText());
  for(var field:json.readTree(c.excerpt()))assertTrue(AssistantArtworkContext.bytes(field.get("value").asText())<=512);
  assertEquals("中😀",AssistantArtworkContext.clip("中😀文",7));assertEquals("中",AssistantArtworkContext.clip("中😀文",6));
  assertEquals(AssistantArtworkContext.sha(c.excerpt()),c.excerptSha256());assertNotEquals(c.excerptSha256(),AssistantArtworkContext.projectionHash(c));
 }
 @Test void wireContainsOnlyBoundSourcesAndBoundsWholeTextContext(){
  var c=context.snapshot(art(List.of()),1,false,Instant.EPOCH);var wire=context.wire(List.of(c));assertEquals(c.sourceId(),wire.get(0).get("artworkId"));
  assertEquals(false,((Map<?,?>)wire.get(0).get("source")).get("imageSupplied"));
  context.validateTextBudget(List.of(Map.of("content","正常问题")),wire);
  assertThrows(AssistantFailure.class,()->context.validateTextBudget(List.of(Map.of("content","中".repeat(45000))),wire));
  assertThrows(AssistantFailure.class,()->context.wire(Collections.nCopies(6,c)));
 }
 @Test void legacyCitationHashFallbackRetainsOldHistoryCompatibility(){
  var c=new AssistantExecutionRecords.Citation(UUID.randomUUID(),1,"PLATFORM_ARTWORK","id","version","projection",Map.of(),"[]","a".repeat(64));assertEquals(c.excerptSha256(),AssistantArtworkContext.projectionHash(c));
 }
}
