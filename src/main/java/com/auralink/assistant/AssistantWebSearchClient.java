package com.auralink.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.*;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.core5.util.Timeout;

/** Bounded Brave Search adapter. Search snippets are untrusted reference text, never instructions. */
public final class AssistantWebSearchClient implements AutoCloseable {
 static final URI BRAVE_ENDPOINT=URI.create("https://api.search.brave.com/res/v1/web/search");
 public record Result(String title,String url,String description,String age){}
 public static final class Failure extends RuntimeException {private final String state;Failure(String state){super(state);this.state=state;}public String state(){return state;}}
 private final URI endpoint;private final String token;private final int count;private final Duration timeout;private final ObjectMapper json;private final CloseableHttpClient http;
 public AssistantWebSearchClient(AssistantWebSearchProperties properties,ObjectMapper json){
  this(BRAVE_ENDPOINT,readToken(properties),properties.getResultCount(),properties.getTimeout(),json);
 }
 AssistantWebSearchClient(URI endpoint,String token,int count,Duration timeout,ObjectMapper json){
  if(endpoint==null||token==null||token.length()<16||count<1||count>10||timeout==null)throw new IllegalArgumentException("Invalid web search configuration");
  this.endpoint=endpoint;this.token=token;this.count=count;this.timeout=timeout;this.json=json;
  http=HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression().build();
 }
 private static String readToken(AssistantWebSearchProperties properties){
  properties.validate();
  try{
   Path path=Path.of(properties.getTokenFile());
   if(!path.isAbsolute()||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>4096)throw new IllegalArgumentException();
   try{Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);if(permissions.stream().anyMatch(value->value.name().startsWith("GROUP_")||value.name().startsWith("OTHERS_")))throw new IllegalArgumentException();}catch(UnsupportedOperationException ignored){/* Windows development hosts use ACLs instead of POSIX modes. */}
   String value=Files.readString(path).strip();if(value.length()<16||value.length()>2048||value.chars().anyMatch(c->c<=32||c>=127))throw new IllegalArgumentException();return value;
  }catch(IOException|InvalidPathException failure){throw new IllegalArgumentException("Assistant web search token file is invalid");}
 }
 public List<Result> search(String rawQuery){
  String query=rawQuery==null?"":rawQuery.strip();if(query.isEmpty())return List.of();if(query.length()>600)query=query.substring(0,600);
  URI uri=URI.create(endpoint+"?q="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&count="+count+"&search_lang=zh-hans&ui_lang=zh-CN");
  HttpGet request=new HttpGet(uri);request.setHeader("Accept","application/json");request.setHeader("X-Subscription-Token",token);request.setConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(3)).setConnectionRequestTimeout(Timeout.ofSeconds(3)).setResponseTimeout(Timeout.ofMilliseconds(timeout.toMillis())).build());
  try(CloseableHttpResponse response=http.execute(request)){
   int status=response.getCode();if(status==401||status==403)throw new Failure("FORBIDDEN");if(status!=200||response.getEntity()==null)throw new Failure("UNAVAILABLE");
   try(InputStream input=response.getEntity().getContent()){
    byte[] bytes=input.readNBytes(524289);if(bytes.length>524288)throw new Failure("UNAVAILABLE");
    Map<String,Object> root=json.readValue(bytes,new TypeReference<Map<String,Object>>(){});Object web=root.get("web");if(!(web instanceof Map<?,?> map)||!(map.get("results") instanceof List<?> rows))return List.of();
    List<Result> results=new ArrayList<>();
    for(Object value:rows){if(results.size()>=count)break;if(!(value instanceof Map<?,?> row))continue;String title=text(row.get("title"),300),url=text(row.get("url"),2048),description=text(row.get("description"),2000),age=text(row.get("age"),100);if(title.isBlank()||description.isBlank()||!safePublicUrl(url))continue;results.add(new Result(title,url,description,age));}
    return List.copyOf(results);
   }
  }catch(Failure failure){throw failure;}
  catch(java.net.SocketTimeoutException failure){throw new Failure("TIMEOUT");}
  catch(IOException|RuntimeException failure){throw new Failure("UNAVAILABLE");}
 }
 private static String text(Object value,int max){String text=value instanceof String s?s.strip():"";return text.length()>max?text.substring(0,max):text;}
 static boolean safePublicUrl(String value){
  try{URI uri=URI.create(value);if(!Set.of("http","https").contains(uri.getScheme())||uri.getUserInfo()!=null||uri.getHost()==null)return false;String host=uri.getHost().toLowerCase(Locale.ROOT);if(host.equals("localhost")||host.endsWith(".localhost")||host.equals("0.0.0.0")||host.equals("::1"))return false;if(host.matches("^(10|127)\\..*")||host.matches("^192\\.168\\..*")||host.matches("^169\\.254\\..*")||host.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*"))return false;return true;}catch(RuntimeException invalid){return false;}
 }
 @Override public void close(){try{http.close();}catch(IOException ignored){}}
}
