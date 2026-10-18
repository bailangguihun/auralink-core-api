package com.auralink.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AssistantProviderActivityTest {
 @TempDir Path root;
 HttpServer server;
 ExecutorService executor;
 static final String ACTIVITY="event: heartbeat\ndata: {\"kind\":\"provider_activity\"}\n\n";
 @AfterEach void stop(){if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();}
 AssistantAiClient client()throws Exception{
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);server.start();
  Path token=root.resolve("token");Files.writeString(token,"synthetic-local-test-token-only");Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));
  var p=new AssistantExecutionProperties();p.setEnabled(true);p.setAllowLoopbackHttp(true);p.setAiBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());p.setInternalTokenFile(token.toString());p.setExpectedConfigSha256("a".repeat(64));p.setIdleTimeout(Duration.ofMillis(300));p.setTotalTimeout(Duration.ofMillis(1400));
  return new AssistantAiClient(p,new ObjectMapper());
 }
 interface Writer {void write(OutputStream stream)throws Exception;}
 void serve(Writer writer){server.createContext("/internal/v1/assistant/executions",exchange->{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){writer.write(out);}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception ignored){/* Expected client timeout/cancellation closes the fixture stream. */}});}
 static void emit(OutputStream out,String value)throws IOException{out.write(value.getBytes(StandardCharsets.UTF_8));out.flush();}
 @Test void validActivityKeepsTransportAliveWithoutCallingAnswerOrSourceConsumer()throws Exception{
  try(var client=client()){
   serve(out->{for(int i=0;i<6;i++){emit(out,ACTIVITY);Thread.sleep(100);}emit(out,"event: delta\ndata: {\"text\":\"山水\"}\n\nevent: done\ndata: {\"finishReason\":\"stop\"}\n\n");});
   var events=new ArrayList<String>();client.execute(UUID.randomUUID(),Map.of(),(type,value)->events.add(type));
   assertEquals(List.of("delta","done"),events);
  }
 }
 @Test void malformedActivityIsRejectedBeforeBusinessCallback()throws Exception{
  for(String value:List.of("{}","{\"kind\":\"timer\"}","{\"kind\":\"provider_activity\",\"text\":\"private\"}","null")){
   try(var client=client()){
    serve(out->emit(out,"event: heartbeat\ndata: "+value+"\n\n"));var events=new ArrayList<String>();
    var error=assertThrows(AssistantFailure.class,()->client.execute(UUID.randomUUID(),Map.of(),(type,data)->events.add(type)));
    assertEquals("ASSISTANT_PROTOCOL_ERROR",error.code());assertTrue(events.isEmpty());
   }finally{stop();}
  }
 }
 @Test void commentsDoNotKeepIdleTransportAlive()throws Exception{
  try(var client=client()){
   serve(out->{for(int i=0;i<30;i++){emit(out,": comment\n\n");Thread.sleep(50);}});
   long start=System.nanoTime();assertThrows(IOException.class,()->client.execute(UUID.randomUUID(),Map.of(),(type,data)->fail("Unexpected business event")));
   assertTrue(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(1100));
  }
 }
 @Test void sustainedActivityCannotExtendTotalDeadline()throws Exception{
  try(var client=client()){
   serve(out->{for(int i=0;i<60;i++){emit(out,ACTIVITY);Thread.sleep(50);}});
   long start=System.nanoTime();assertThrows(IOException.class,()->client.execute(UUID.randomUUID(),Map.of(),(type,data)->fail("Unexpected business event")));
   long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);assertTrue(elapsed>=1000&&elapsed<2400,"elapsed="+elapsed);
  }
 }
 @Test void activityAndPartialAnswerWithoutDoneRemainIncomplete()throws Exception{
  try(var client=client()){
   serve(out->{emit(out,ACTIVITY);emit(out,"event: delta\ndata: {\"text\":\"部分\"}\n\n");});var events=new ArrayList<String>();
   assertThrows(EOFException.class,()->client.execute(UUID.randomUUID(),Map.of(),(type,data)->events.add(type)));
   assertEquals(List.of("delta"),events);
  }
 }
 @Test void explicitCancellationStillStopsActiveProviderTransport()throws Exception{
  try(var client=client()){
   CountDownLatch sent=new CountDownLatch(1);serve(out->{for(int i=0;i<30;i++){emit(out,ACTIVITY);sent.countDown();Thread.sleep(50);}});
   UUID id=UUID.randomUUID();Future<?> active=executor.submit(()->assertThrows(IOException.class,()->client.execute(id,Map.of(),(type,data)->fail("Unexpected business event"))));
   assertTrue(sent.await(1,TimeUnit.SECONDS));client.closeStream(id);active.get(1,TimeUnit.SECONDS);
  }
 }
}
