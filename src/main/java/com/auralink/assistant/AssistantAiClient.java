package com.auralink.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

/** Private fixed-origin client. HTTP retries, redirects, cookies and proxy-env inheritance are disabled. */
public final class AssistantAiClient implements AutoCloseable {
 private static final Logger DIAGNOSTICS=LoggerFactory.getLogger(AssistantAiClient.class);
 private static final Set<String> KNOWN_EVENTS=Set.of("delta","usage","choices","done","error","heartbeat");
 private final AssistantExecutionProperties properties;
 private final ObjectMapper json;
 private final URI base;
 private final String token;
 private final CloseableHttpClient http;
 private final ConcurrentHashMap<UUID,InputStream> streams=new ConcurrentHashMap<>();
 private final ConcurrentHashMap<UUID,HttpUriRequestBase> requests=new ConcurrentHashMap<>();
 private final ScheduledExecutorService deadlines=Executors.newScheduledThreadPool(1,r->{Thread t=new Thread(r,"assistant-http-deadline");t.setDaemon(true);return t;});
 public AssistantAiClient(AssistantExecutionProperties p,ObjectMapper json){
  this.properties=p;this.json=json;p.validate();
  try{
   base=URI.create(p.getAiBaseUrl());String host=base.getHost();
   boolean loopback=Set.of("127.0.0.1","[::1]","::1").contains(host);
   if(host==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!(base.getPath().isEmpty()||base.getPath().equals("/"))||!(base.getScheme().equals("https")||(p.isAllowLoopbackHttp()&&loopback&&base.getScheme().equals("http"))))throw new IllegalArgumentException();
   Path path=Path.of(p.getInternalTokenFile());
   if(!path.isAbsolute()||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>4096)throw new IllegalArgumentException();
   var perms=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
   if(perms.stream().anyMatch(x->x.name().startsWith("GROUP_")||x.name().startsWith("OTHERS_")))throw new IllegalArgumentException();
   token=Files.readString(path).strip();if(token.length()<16||token.length()>2048||token.chars().anyMatch(c->c<=32||c>=127))throw new IllegalArgumentException();
  }catch(Exception e){throw new IllegalArgumentException("Assistant private origin or token file is invalid");}
  // Use the already-declared Apache client so disabling retries is per-client,
  // without mutating JVM-wide JDK HttpClient retry properties for other modules.
  http=HttpClients.custom().disableAutomaticRetries().disableRedirectHandling()
      .disableCookieManagement().disableContentCompression().build();
 }
 public Map<String,Object> models(){var value=request("GET","/models",null);requireConfig(value);return value;}
 public Map<String,Object> retrieval(String query,boolean enabled){return request("POST","/retrieval",Map.of("contractVersion",1,"configSha256",properties.getExpectedConfigSha256(),"query",query,"enabled",enabled),properties.getRetrievalTimeout().toMillis());}
 public void execute(UUID id,Map<String,Object> payload,BiConsumer<String,Map<String,Object>> consumer)throws IOException,InterruptedException{
  var request=builder("POST","/executions",properties.getIdleTimeout().toMillis());
  request.setEntity(new ByteArrayEntity(json.writeValueAsBytes(payload),ContentType.APPLICATION_JSON));
  requests.put(id,request);
  long start=System.nanoTime();
  AtomicReference<String> cancellation=new AtomicReference<>("NONE");
  trace(id,"EXECUTION_START",start,"-","-","NONE");
  var total=deadlines.schedule(()->{cancellation.compareAndSet("NONE","TOTAL_TIMER");trace(id,"CANCEL_TIMER",start,"-","-","TOTAL_TIMER");request.cancel();},properties.getTotalTimeout().toMillis(),TimeUnit.MILLISECONDS);
  try(CloseableHttpResponse response=http.execute(request)){
   String type=response.getFirstHeader("Content-Type")==null?"MISSING":response.getFirstHeader("Content-Type").getValue().startsWith("text/event-stream")?"text/event-stream":"OTHER";
   trace(id,"RESPONSE_HEADERS",start,Integer.toString(response.getCode()),type,cancellation.get());
   if(response.getCode()!=200)throw new IOException("Assistant execution refused");
   if(response.getFirstHeader("Content-Type")==null||!response.getFirstHeader("Content-Type").getValue().startsWith("text/event-stream")||response.getEntity()==null)throw new IOException("Assistant execution type invalid");
   try(InputStream body=response.getEntity().getContent()){
    streams.put(id,body);AtomicLong last=new AtomicLong(System.nanoTime());
    var deadline=deadlines.scheduleAtFixedRate(()->{long now=System.nanoTime();if(now-start>properties.getTotalTimeout().toNanos()||now-last.get()>properties.getIdleTimeout().toNanos()){String reason=now-start>properties.getTotalTimeout().toNanos()?"TOTAL_TIMER":"IDLE_TIMER";cancellation.compareAndSet("NONE",reason);trace(id,"CANCEL_TIMER",start,"-","-",reason);request.cancel();}},50,50,TimeUnit.MILLISECONDS);
    try{AssistantSseReader.read(body,65536,Math.max(262144,properties.getMaxOutputChars()*12),(event,data)->{
     try{
      Map<String,Object> value=json.readValue(data,new TypeReference<Map<String,Object>>(){});
      if(event.equals("heartbeat")&&!Map.of("kind","provider_activity").equals(value))throw new AssistantFailure(502,"ASSISTANT_PROTOCOL_ERROR","Invalid Assistant activity event");
      last.set(System.nanoTime());trace(id,"SSE_EVENT",start,"-",KNOWN_EVENTS.contains(event)?event:"UNKNOWN",cancellation.get());
      // Transport liveness only: never send reasoning/activity to persistence,
      // answer/citation callbacks, public SSE, or terminal-state processing.
      if(!event.equals("heartbeat"))consumer.accept(event,value);
     }catch(IOException e){throw new AssistantFailure(502,"ASSISTANT_PROTOCOL_ERROR","Invalid Assistant stream");}
    });}finally{deadline.cancel(false);streams.remove(id,body);}
   }
  }catch(IOException|RuntimeException error){trace(id,"EXECUTION_EXCEPTION",start,"-",error.getClass().getName(),cancellation.get());throw error;}
  finally{total.cancel(false);requests.remove(id,request);trace(id,"EXECUTION_END",start,"-","-",cancellation.get());}
 }
 public void cancel(UUID id){long start=System.nanoTime();trace(id,"CANCEL_REQUEST_START",start,"-","-","EXPLICIT_OR_RECOVERY");closeStream(id);try{request("POST","/executions/"+id+"/cancel",Map.of());trace(id,"CANCEL_REQUEST_END",start,"-","-","BEST_EFFORT_ACCEPTED");}catch(RuntimeException ignored){trace(id,"CANCEL_REQUEST_EXCEPTION",start,"-",ignored.getClass().getName(),"BEST_EFFORT_FAILED");/* Core durable fencing is authoritative; network cancellation is best effort. */}}
 /** Opt-in metadata only: never messages, exception text/stack, endpoints, tokens or stream content. */
 private static void trace(UUID id,String phase,long start,String status,String detail,String cancellation){
  if(DIAGNOSTICS.isDebugEnabled())DIAGNOSTICS.debug("assistant.transport runId={} phase={} elapsedMs={} status={} detail={} cancellation={}",id,phase,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),status,detail,cancellation);
 }
 public void closeStream(UUID id){HttpUriRequestBase request=requests.remove(id);if(request!=null)request.cancel();InputStream body=streams.remove(id);if(body!=null)try{body.close();}catch(IOException ignored){}}
 private Map<String,Object> request(String method,String path,Map<String,Object> body){
  return request(method,path,body,8000);
 }
 private Map<String,Object> request(String method,String path,Map<String,Object> body,long timeoutMs){
  var request=builder(method,path,timeoutMs);
  AtomicBoolean timedOut=new AtomicBoolean();
  var deadline=deadlines.schedule(()->{timedOut.set(true);request.cancel();},timeoutMs,TimeUnit.MILLISECONDS);
  try{
   if(body!=null)request.setEntity(new ByteArrayEntity(json.writeValueAsBytes(body),ContentType.APPLICATION_JSON));
   try(CloseableHttpResponse response=http.execute(request)){
    if(response.getCode()!=200||response.getEntity()==null)throw new IOException();
    try(InputStream stream=response.getEntity().getContent()){
     byte[] bytes=stream.readNBytes(262145);if(bytes.length>262144)throw new IOException();
     return json.readValue(bytes,new TypeReference<Map<String,Object>>(){});
    }
   }
  }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();if(path.equals("/retrieval")&&(timedOut.get()||e instanceof java.net.SocketTimeoutException))throw new AssistantFailure(504,"ASSISTANT_RETRIEVAL_TIMEOUT","Assistant retrieval timed out");throw new AssistantFailure(503,"ASSISTANT_EXECUTION_UNAVAILABLE","Assistant execution service is unavailable");}
  finally{deadline.cancel(false);}
 }
 private HttpUriRequestBase builder(String method,String path,long timeoutMs){
  var request=new HttpUriRequestBase(method,base.resolve("/internal/v1/assistant"+path));
  request.setConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(3)).setConnectionRequestTimeout(Timeout.ofSeconds(3)).setResponseTimeout(Timeout.ofMilliseconds(timeoutMs)).build());
  request.setHeader("Authorization","Bearer "+token);request.setHeader("Content-Type","application/json");request.setHeader("Accept","application/json, text/event-stream");return request;
 }
 private void requireConfig(Map<String,Object> value){if(!properties.getExpectedConfigSha256().equals(value.get("configSha256"))||!Objects.equals(value.get("contractVersion"),1))throw new AssistantFailure(503,"ASSISTANT_CONFIG_CHANGED","Assistant configuration binding does not match");}
 @Override public void close(){requests.keySet().forEach(this::closeStream);streams.keySet().forEach(this::closeStream);deadlines.shutdownNow();try{http.close();}catch(IOException ignored){}}
}
