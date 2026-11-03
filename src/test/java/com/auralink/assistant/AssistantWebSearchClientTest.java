package com.auralink.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantWebSearchClientTest {
 private HttpServer server;
 @AfterEach void close(){if(server!=null)server.stop(0);}
 private URI endpoint()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/search");}

 @Test void sendsBoundedChineseSearchAndKeepsOnlySafePublicResults()throws Exception{
  URI endpoint=endpoint();AtomicReference<String> query=new AtomicReference<>(),token=new AtomicReference<>();
  server.createContext("/search",exchange->{query.set(exchange.getRequestURI().getRawQuery());token.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));byte[] body="""
   {"web":{"results":[
    {"title":"故宫博物院","url":"https://www.dpm.org.cn/","description":"清明上河图公开资料","age":"2 days ago"},
    {"title":"内网","url":"http://127.0.0.1/private","description":"不得显示"},
    {"title":"无摘要","url":"https://example.org/","description":""}
   ]}}
   """.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});
  try(var client=new AssistantWebSearchClient(endpoint,"test-only-token-123456",8,Duration.ofSeconds(2),new ObjectMapper())){
   var results=client.search("清明上河图 研究");assertEquals(1,results.size());assertEquals("故宫博物院",results.get(0).title());assertTrue(query.get().contains("count=8"));assertTrue(query.get().contains("search_lang=zh-hans"));assertEquals("test-only-token-123456",token.get());
  }
 }

 @Test void mapsAuthorizationAndTimeoutWithoutRetry()throws Exception{
  URI endpoint=endpoint();var calls=new java.util.concurrent.atomic.AtomicInteger();server.createContext("/search",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(403,-1);exchange.close();});
  try(var client=new AssistantWebSearchClient(endpoint,"test-only-token-123456",3,Duration.ofSeconds(1),new ObjectMapper())){var failure=assertThrows(AssistantWebSearchClient.Failure.class,()->client.search("test"));assertEquals("FORBIDDEN",failure.state());assertEquals(1,calls.get());}
 }

 @Test void rejectsCredentialAndPrivateCitationLinks(){
  for(String value:new String[]{"http://localhost/x","http://10.0.0.1/x","http://172.16.0.1/x","http://192.168.1.1/x","https://user:pass@example.org/x","javascript:alert(1)"})assertFalse(AssistantWebSearchClient.safePublicUrl(value),value);
  assertTrue(AssistantWebSearchClient.safePublicUrl("https://www.dpm.org.cn/explore"));
 }
}
