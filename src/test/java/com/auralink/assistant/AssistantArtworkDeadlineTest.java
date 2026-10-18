package com.auralink.assistant;

import com.auralink.catalogread.AssistantArtworkRead;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantArtworkDeadlineTest {
 @Test void oneDeadlineIsSharedAcrossManyLookupsWithoutRestartingItsWindow(){
  AtomicLong now=new AtomicLong();AtomicInteger queries=new AtomicInteger();var deadline=new AssistantArtworkDeadline(now::get,Duration.ofSeconds(15));
  for(int i=0;i<3;i++)assertEquals("ok",deadline.lookup(()->{queries.incrementAndGet();now.addAndGet(Duration.ofSeconds(4).toNanos());return "ok";}));
  now.set(Duration.ofSeconds(15).toNanos());var failure=assertThrows(AssistantFailure.class,()->deadline.lookup(()->{queries.incrementAndGet();return "not dispatched";}));
  assertEquals(504,failure.status());assertEquals("ASSISTANT_ARTWORK_TIMEOUT",failure.code());assertEquals(3,queries.get());
 }
 @Test void anInFlightLookupCannotTurnLateCompletionIntoSuccessfulPreparation(){
  AtomicLong now=new AtomicLong();AtomicInteger queries=new AtomicInteger();var deadline=new AssistantArtworkDeadline(now::get,AssistantArtworkDeadline.BUDGET);
  now.set(Duration.ofSeconds(14).toNanos());assertThrows(AssistantFailure.class,()->deadline.lookup(()->{queries.incrementAndGet();now.addAndGet(Duration.ofSeconds(5).toNanos());return "late";}));
  assertThrows(AssistantFailure.class,()->deadline.lookup(()->{queries.incrementAndGet();return "second";}));assertEquals(1,queries.get());
 }
 @Test void expiryDominatesAnUnavailableLookupInsteadOfAllowingHistoryFallback(){
  AtomicLong now=new AtomicLong();var deadline=new AssistantArtworkDeadline(now::get,AssistantArtworkDeadline.BUDGET);
  var failure=assertThrows(AssistantFailure.class,()->deadline.lookup(()->{now.set(Duration.ofSeconds(16).toNanos());throw AssistantFailure.notFound();}));assertEquals("ASSISTANT_ARTWORK_TIMEOUT",failure.code());
 }
 @Test void historyAccessRevalidationPropagatesDeadlineInsteadOfSilentlyOmittingAndDispatching(){
  AtomicLong now=new AtomicLong();var deadline=new AssistantArtworkDeadline(now::get,AssistantArtworkDeadline.BUDGET);
  var store=mock(AssistantExecutionStore.class);var reader=mock(AssistantArtworkRead.class);var ai=mock(AssistantAiClient.class);UUID artwork=UUID.randomUUID(),batch=UUID.randomUUID(),run=UUID.randomUUID();
  var citation=new AssistantExecutionRecords.Citation(UUID.randomUUID(),1,"PLATFORM_ARTWORK",artwork.toString(),batch+":1","assistant-public-artwork-v1",Map.of("batchId",batch.toString(),"revision",1),"[]","a".repeat(64));
  when(store.citations(71,run)).thenReturn(List.of(citation));when(reader.require(artwork,batch,1)).thenAnswer(call->{now.set(Duration.ofSeconds(16).toNanos());throw AssistantFailure.notFound();});
  try(var service=new AssistantExecutionService(store,mock(AssistantStore.class),mock(AssistantAttachmentService.class),ai,reader,null,new AssistantExecutionProperties(),new ObjectMapper(),Clock.systemUTC())){
   var failure=assertThrows(AssistantFailure.class,()->ReflectionTestUtils.invokeMethod(service,"referencesStillPublic",71L,run,deadline));assertEquals(504,failure.status());assertEquals("ASSISTANT_ARTWORK_TIMEOUT",failure.code());verifyNoInteractions(ai);
  }
 }
}
