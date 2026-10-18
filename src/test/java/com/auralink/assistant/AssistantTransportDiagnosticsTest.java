package com.auralink.assistant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback transport; diagnostics must not alter retry, timeout or SSE behavior. */
class AssistantTransportDiagnosticsTest {
 @TempDir Path root;
 HttpServer server;
 ExecutorService executor;
 Logger logger;
 Level previous;
 ListAppender<ILoggingEvent> events;
 final String secret="synthetic-private-token-DO-NOT-LOG";

 @BeforeEach void capture()throws Exception{
  logger=(Logger)LoggerFactory.getLogger(AssistantAiClient.class);previous=logger.getLevel();
  events=new ListAppender<>();events.start();logger.addAppender(events);logger.setLevel(Level.DEBUG);
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);server.start();
 }
 @AfterEach void stop(){server.stop(0);executor.shutdownNow();logger.detachAppender(events);logger.setLevel(previous);events.stop();}
 AssistantAiClient client(Duration idle)throws Exception{
  Path token=root.resolve("token");Files.writeString(token,secret);Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));
  var p=new AssistantExecutionProperties();p.setEnabled(true);p.setAllowLoopbackHttp(true);p.setAiBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());p.setInternalTokenFile(token.toString());p.setExpectedConfigSha256("a".repeat(64));p.setIdleTimeout(idle);p.setTotalTimeout(Duration.ofSeconds(3));
  return new AssistantAiClient(p,new ObjectMapper());
 }
 String logs(){return String.join("\n",events.list.stream().map(ILoggingEvent::getFormattedMessage).toList());}
 void noSensitiveLogs(){String text=logs();for(String value:List.of(secret,"private-request-body","private-answer-body","private-exception-message","private-header-value","http://127.0.0.1",root.toString()))assertFalse(text.contains(value),value);assertTrue(events.list.stream().allMatch(e->e.getThrowableProxy()==null));}

 @Test void activeCommentsWithoutEventsIdentifyIdleTimerAndOnePost()throws Exception{
  var count=new AtomicInteger();server.createContext("/internal/v1/assistant/executions",exchange->{count.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){for(int i=0;i<30;i++){out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));out.flush();Thread.sleep(25);}}catch(IOException ignored){}catch(InterruptedException e){Thread.currentThread().interrupt();}});
  try(var client=client(Duration.ofMillis(200))){assertThrows(IOException.class,()->client.execute(UUID.randomUUID(),Map.of("text","private-request-body"),(e,d)->fail("comments are not data")));}
  assertEquals(1,count.get());assertTrue(logs().contains("phase=RESPONSE_HEADERS"));assertTrue(logs().contains("phase=CANCEL_TIMER"));assertTrue(logs().contains("cancellation=IDLE_TIMER"));assertTrue(logs().contains("phase=EXECUTION_EXCEPTION"));noSensitiveLogs();
 }

 @Test void silentBodyReportsActualExceptionClassWithoutPretendingWhichTimerWon()throws Exception{
  var count=new AtomicInteger();server.createContext("/internal/v1/assistant/executions",exchange->{count.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){out.write(": ready\n\n".getBytes(StandardCharsets.UTF_8));out.flush();Thread.sleep(800);}catch(IOException ignored){}catch(InterruptedException e){Thread.currentThread().interrupt();}});
  IOException failure;try(var client=client(Duration.ofMillis(200))){failure=assertThrows(IOException.class,()->client.execute(UUID.randomUUID(),Map.of(),(e,d)->{}));}
  assertEquals(1,count.get());assertTrue(logs().contains("phase=EXECUTION_EXCEPTION"));assertTrue(logs().contains("detail="+failure.getClass().getName()));assertFalse(logs().contains("detail=done"));noSensitiveLogs();
 }

 @Test void eofIsStillFailureAndNoBodyOrRawHeadersAreLogged()throws Exception{
  server.createContext("/internal/v1/assistant/executions",exchange->{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream; x=private-header-value");exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){out.write("event: delta\ndata: {\"text\":\"private-answer-body\"}\n\n".getBytes(StandardCharsets.UTF_8));}});
  var seen=new ArrayList<String>();try(var client=client(Duration.ofSeconds(1))){assertThrows(EOFException.class,()->client.execute(UUID.randomUUID(),Map.of("text","private-request-body"),(e,d)->seen.add(e)));}
  assertEquals(List.of("delta"),seen);assertTrue(logs().contains("detail=java.io.EOFException"));assertTrue(logs().contains("detail=text/event-stream"));assertTrue(logs().contains("detail=delta"));noSensitiveLogs();
 }

 @Test void successKeepsTerminalBehaviorAndCancellationReportsStartEnd()throws Exception{
  UUID id=UUID.randomUUID();var count=new AtomicInteger();
  server.createContext("/internal/v1/assistant/executions",exchange->{count.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){byte[] bytes="event: delta\ndata: {\"text\":\"山水private-answer-body\"}\n\nevent: done\ndata: {\"finishReason\":\"stop\"}\n\n".getBytes(StandardCharsets.UTF_8);for(byte b:bytes){out.write(b);out.flush();}}});
  server.createContext("/internal/v1/assistant/executions/"+id+"/cancel",exchange->{exchange.getRequestBody().readAllBytes();byte[] bytes="{\"cancelRequested\":true}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});
  var seen=new ArrayList<String>();try(var client=client(Duration.ofSeconds(1))){client.execute(id,Map.of("text","private-request-body"),(e,d)->seen.add(e));client.cancel(id);}
  assertEquals(List.of("delta","done"),seen);assertEquals(1,count.get());assertTrue(logs().contains("phase=CANCEL_REQUEST_START"));assertTrue(logs().contains("phase=CANCEL_REQUEST_END"));assertFalse(logs().contains("phase=EXECUTION_EXCEPTION"));noSensitiveLogs();
 }

 @Test void unknownEventsAndExceptionMessagesAreNeverLogged()throws Exception{
  server.createContext("/internal/v1/assistant/executions",exchange->{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){out.write("event: private-answer-body\ndata: {\"text\":\"private-answer-body\"}\n\n".getBytes(StandardCharsets.UTF_8));}});
  try(var client=client(Duration.ofSeconds(1))){assertThrows(IllegalStateException.class,()->client.execute(UUID.randomUUID(),Map.of(),(e,d)->{throw new IllegalStateException("private-exception-message");}));}
  assertTrue(logs().contains("detail=UNKNOWN"));assertTrue(logs().contains("detail=java.lang.IllegalStateException"));noSensitiveLogs();
 }

 @Test void diagnosticsRemainSilentWithoutDebugOptIn()throws Exception{
  logger.setLevel(Level.INFO);
  server.createContext("/internal/v1/assistant/executions",exchange->{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){out.write("event: done\ndata: {\"finishReason\":\"stop\"}\n\n".getBytes(StandardCharsets.UTF_8));}});
  try(var client=client(Duration.ofSeconds(1))){client.execute(UUID.randomUUID(),Map.of("text","private-request-body"),(e,d)->assertEquals("done",e));}
  assertTrue(events.list.isEmpty());
 }
}
