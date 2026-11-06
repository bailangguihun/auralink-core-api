package com.auralink.assistant;

import static com.auralink.assistant.AssistantExecutionRecords.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Exercises the real preparation/dispatch path against the installed AI v1 limits. */
class AssistantLegacyAiCompatibilityTest {
 private static final String CONFIG="a".repeat(64);
 private record Captured(Map<String,Object> request,Map<String,Object> publicStates,List<Citation> citations){}
 @SuppressWarnings("unchecked")
 private Captured execute(String kind,boolean requestedWeb,boolean rag)throws Exception{
  var store=mock(AssistantExecutionStore.class);var foundation=mock(AssistantStore.class);var ai=mock(AssistantAiClient.class);
  var properties=new AssistantExecutionProperties();properties.setExpectedConfigSha256(CONFIG);
  UUID id=UUID.randomUUID(),session=UUID.randomUUID(),branch=UUID.randomUUID(),input=UUID.randomUUID();
  var options=Map.<String,Object>of("configSha256",CONFIG,"retrieval",Map.of("artworks",false,"ragflow",rag,"web",requestedWeb));
  var run=new Run(id,session,branch,kind,"EXECUTING",input,1,1,"","mock-text",options,"fixture",Instant.now().plusSeconds(30),null,null,null,1,Map.of(),Map.of(),Map.of(),null,Instant.EPOCH,Instant.EPOCH);
  var message=new AssistantRecords.Message(input,session,null,input,null,"USER","question","ACCEPTED",null,Instant.EPOCH,List.of());
  when(store.context(eq(1L),eq(id),anyInt())).thenReturn(List.of(message));
  when(store.get(1,id)).thenReturn(run);
  when(store.savePreparation(eq(1L),eq(id),eq(1L),anyString(),anyMap(),anyList(),anyMap())).thenReturn(run);
  when(store.markIntent(eq(1L),eq(id),eq(1L),anyString(),anyString())).thenReturn(run);
  when(store.confirmModelSources(eq(1L),eq(id),eq(1L),anyString(),anyList())).thenReturn(run);
  when(ai.models()).thenReturn(Map.of("models",List.of(Map.of("id","mock-text","enabled",true,"configured",true,"capabilities",Map.of("images",false)))));
  var raw=IntStream.range(0,20).mapToObj(i->Map.of("id","source-"+i,"text","reference-"+i,"title","title-"+i)).toList();
  when(ai.retrieval(anyString(),eq(true))).thenReturn(Map.of("state","AVAILABLE","citations",raw));
  List<Map<String,Object>> sent=new ArrayList<>();
  doAnswer(call->{
   Map<String,Object> request=call.getArgument(1);sent.add(request);
   assertEquals(Set.of("artworks","ragflow"),((Map<?,?>)request.get("retrievalStates")).keySet());
   assertTrue(((List<?>)request.get("citations")).size()<=16);
   BiConsumer<String,Map<String,Object>> callback=call.getArgument(2);callback.accept("done",Map.of());return null;
  }).when(ai).execute(eq(id),anyMap(),any());
  try(var service=new AssistantExecutionService(store,foundation,mock(AssistantAttachmentService.class),ai,null,null,properties,new ObjectMapper(),Clock.systemUTC())){
   var method=AssistantExecutionService.class.getDeclaredMethod("execute",long.class,Run.class);method.setAccessible(true);method.invoke(service,1L,run);
  }
  assertEquals(1,sent.size());verify(ai,never()).cancel(any());
  var states=ArgumentCaptor.forClass(Map.class);var citations=ArgumentCaptor.forClass(List.class);
  verify(store).savePreparation(eq(1L),eq(id),eq(1L),anyString(),anyMap(),anyList(),states.capture());
  verify(store).complete(eq(1L),eq(id),eq(1L),anyString(),eq("COMPLETED"),eq(""),citations.capture(),anyMap(),anyList());
  return new Captured(sent.get(0),states.getValue(),citations.getValue());
 }
 @Test void ordinaryChatAndSummaryStillUseTheInstalledTwoStateWireContract()throws Exception{
  assertEquals("DISABLED",execute("CHAT",false,false).publicStates().get("web"));
  assertEquals("DISABLED",execute("SUMMARY",true,true).publicStates().get("web"));
 }
 @Test void unavailableWebRemainsVisibleWithoutBreakingLegacyModelExecution()throws Exception{
  var result=execute("CHAT",true,false);
  assertEquals("NOT_CONFIGURED",result.publicStates().get("web"));assertTrue(result.citations().isEmpty());
 }
 @Test void retrievedSourcesAreBoundedBeforeDispatchAndTheSameSourcesArePersisted()throws Exception{
  var result=execute("CHAT",false,true);
  assertEquals(16,((List<?>)result.request().get("citations")).size());assertEquals(16,result.citations().size());
  assertEquals("source-15",result.citations().get(15).sourceId());
 }
 @Test void oversizedCitationEnvelopeIsRejectedBeforeItCanReachAi(){
  var citation=new Citation(UUID.randomUUID(),1,"RAGFLOW_DOCUMENT","source","v1","fixture",Map.of(),"text",AssistantArtworkContext.sha("text"));
  var context=new AssistantArtworkContext(new ObjectMapper());
  assertEquals(16,context.wire(Collections.nCopies(16,citation)).size());
  assertThrows(AssistantFailure.class,()->context.wire(Collections.nCopies(17,citation)));
 }
}
