package com.auralink.api.v1.assistant;

import com.auralink.api.v1.error.ApiV1ErrorResponse;
import com.auralink.assistant.AssistantFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import com.auralink.assistant.AssistantStore;
import com.auralink.catalogread.AssistantArtworkRead;
import com.auralink.service.CurrentUserService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Authenticated public-artwork selection, independent of generation availability. */
@RestController
@RequestMapping("/api/v1/assistant/artworks")
public final class AssistantArtworkController {
 private static final Set<String> FILTERS=Set.of("dynasty","category","author","subject","paintingSchool","style","artisticConception","paintingMaterial","collectionInstitution","collectionPlatform");
 private static final Set<String> PARAMETERS=Set.of("query","keyword","page","size","sort","direction","dynasty","category","author","subject","paintingSchool","style","artisticConception","paintingMaterial","collectionInstitution","collectionPlatform");
 private final CurrentUserService users;
 private final ObjectProvider<AssistantStore> stores;
 private final ObjectProvider<NamedParameterJdbcTemplate> reads;
 public AssistantArtworkController(CurrentUserService users,ObjectProvider<AssistantStore> stores,@Qualifier("catalogReadJdbcTemplate")ObjectProvider<NamedParameterJdbcTemplate> reads){this.users=users;this.stores=stores;this.reads=reads;}
 @GetMapping("/page") public ResponseEntity<AssistantArtworkRead.Page> page(@RequestParam org.springframework.util.MultiValueMap<String,String> rawParameters){
  var service=service();
  if(rawParameters.values().stream().anyMatch(values->values.size()!=1))throw AssistantFailure.invalid("Repeated artwork query parameters are not supported");
  Map<String,String> parameters=rawParameters.toSingleValueMap();
  if(!PARAMETERS.containsAll(parameters.keySet()))throw AssistantFailure.invalid("Unsupported artwork query parameter");
  String query=parameters.get("query"),keyword=parameters.get("keyword");
  if(query!=null&&keyword!=null&&!query.strip().equals(keyword.strip()))throw AssistantFailure.invalid("Conflicting artwork query aliases");
  var filters=new LinkedHashMap<String,String>();for(String key:FILTERS)if(parameters.containsKey(key))filters.put(key,parameters.get(key));
  var request=new AssistantArtworkRead.Query(keyword==null?query:keyword,filters,integer(parameters,"page",0),integer(parameters,"size",10),parameters.get("sort"),parameters.get("direction"));
  return ok(service.page(request));
 }
 @GetMapping("/{id}") public ResponseEntity<AssistantArtworkRead.Artwork> detail(@PathVariable String id){
  var service=service();UUID uuid;try{uuid=UUID.fromString(id);if(!uuid.toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException invalid){throw AssistantFailure.invalid("Artwork ID must be a canonical UUID");}
  return ok(service.get(uuid));
 }
 private AssistantArtworkRead service(){
  users.requireCurrentUser();
  if(stores.getIfAvailable()==null)throw new AssistantFailure(503,"ASSISTANT_DISABLED","Assistant is unavailable");
  var jdbc=reads.getIfAvailable();if(jdbc==null)throw new AssistantFailure(503,"ASSISTANT_ARTWORK_UNAVAILABLE","画作资料暂时不可用，请稍后重试");
  return new AssistantArtworkRead(jdbc);
 }
 @ExceptionHandler(AssistantFailure.class) public ResponseEntity<ApiV1ErrorResponse> failure(AssistantFailure failure,HttpServletRequest request){return error(failure.status(),failure.code(),failure.getMessage(),request);}
 @ExceptionHandler(DataAccessException.class) public ResponseEntity<ApiV1ErrorResponse> unavailable(DataAccessException failure,HttpServletRequest request){return error(503,"ASSISTANT_ARTWORK_UNAVAILABLE","画作资料暂时不可用，请稍后重试",request);}
 private ResponseEntity<ApiV1ErrorResponse> error(int status,String code,String message,HttpServletRequest request){String correlation=UUID.randomUUID().toString();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).header("X-Correlation-ID",correlation).body(new ApiV1ErrorResponse(Instant.now(),status,code,message,request.getRequestURI(),correlation,Map.of(),List.of()));}
 private static int integer(Map<String,String> parameters,String key,int fallback){try{return parameters.containsKey(key)?Integer.parseInt(parameters.get(key)):fallback;}catch(NumberFormatException invalid){throw AssistantFailure.invalid("Artwork pagination is invalid");}}
 private static <T>ResponseEntity<T> ok(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
