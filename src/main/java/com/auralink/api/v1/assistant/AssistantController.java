package com.auralink.api.v1.assistant;

import com.auralink.api.v1.error.ApiV1ErrorResponse;
import com.auralink.assistant.*;
import com.auralink.exception.StorageException;
import com.auralink.service.CurrentUserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Account-owned foundation endpoints. Optional S2 projection never exposes worker identities or secrets. */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {
   private final CurrentUserService users;
   private final ObjectProvider<AssistantStore> stores;
   private final ObjectProvider<AssistantAttachmentService> attachments;
   private final ObjectMapper json;
   @org.springframework.beans.factory.annotation.Autowired(required=false) private AssistantExecutionRuntime execution;
   public AssistantController(CurrentUserService users,ObjectProvider<AssistantStore> stores,
      ObjectProvider<AssistantAttachmentService> attachments,ObjectMapper json) {
      this.users=users; this.stores=stores; this.attachments=attachments; this.json=json;
   }
   @GetMapping("/status") public ResponseEntity<AssistantDtos.Status> status() {
      owner();boolean available=execution!=null&&execution.available();var config=available?execution.require().properties():null;
      return ok(new AssistantDtos.Status(stores.getIfAvailable()!=null,available?"execution":"foundation",available,available&&execution.require().generationAvailable(),available?execution.require().generationState():"ASSISTANT_EXECUTION_DISABLED",available&&execution.require().generationAvailable()&&config.isAutomaticSummaries(),available?config.getSummaryTurnThreshold():null,available?config.getSummaryIdle().toSeconds():null,available?config.getMaxSummaryPerHour():null));
   }
   @GetMapping("/storage") public ResponseEntity<?> storageUsage(){long owner=owner();return ok(attachmentService().usage(owner));}
   @PostMapping("/requests/resolve") public ResponseEntity<?> resolve(@Valid @RequestBody AssistantDtos.ResolveRequest body){
      long owner=owner();
      return ok(body.closeIfMissing()
         ? store().closeUnconfirmedChatRequest(owner,body.operation(),body.requestKey(),body.sessionId())
         : store().resolveRequest(owner,body.operation(),body.requestKey(),body.sessionId()));
   }
   @GetMapping("/sessions/{id}/branches/{branchId}") public ResponseEntity<?> branch(@PathVariable UUID id,@PathVariable UUID branchId){
      long owner=owner();return ok(AssistantDtos.branch(store().branch(owner,id,branchId)));
   }
   @PostMapping("/sessions") public ResponseEntity<AssistantDtos.Session> create(@Valid @RequestBody AssistantDtos.CreateSession body) {
      long owner=owner(); return ok(AssistantDtos.session(store().createSession(owner,body.requestKey(),body.title())));
   }
   @GetMapping("/sessions") public ResponseEntity<List<AssistantDtos.Session>> sessions(@RequestParam(defaultValue="30") int limit,@RequestParam(defaultValue="0") int offset) {
      long owner=owner(); return ok(store().sessions(owner,limit,offset).stream().map(AssistantDtos::session).toList());
   }
   @GetMapping("/sessions/page") public ResponseEntity<?> sessionPage(@RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String cursor){
      long owner=owner();return ok(store().sessionPage(owner,limit,cursor).map(AssistantDtos::session));
   }
   @GetMapping("/sessions/{id}/branches/page") public ResponseEntity<?> branchPage(@PathVariable UUID id,@RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String cursor){
      long owner=owner();return ok(store().branchPage(owner,id,limit,cursor).map(AssistantDtos::branch));
   }
   @GetMapping("/sessions/{id}/messages/page") public ResponseEntity<?> historyPage(@PathVariable UUID id,@RequestParam(required=false) UUID branchId,
      @RequestParam(defaultValue="false") boolean includeHistory,@RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String cursor){
      long owner=owner();return ok(store().historyPage(owner,id,branchId,includeHistory,limit,cursor).map(AssistantDtos::message));
   }
   @GetMapping("/sessions/{id}") public ResponseEntity<AssistantDtos.Session> session(@PathVariable UUID id) {
      long owner=owner(); return ok(AssistantDtos.session(store().session(owner,id)));
   }
   @PatchMapping("/sessions/{id}") public ResponseEntity<AssistantDtos.Session> update(@PathVariable UUID id,@Valid @RequestBody AssistantDtos.UpdateSession body) {
      long owner=owner(); return ok(AssistantDtos.session(store().updateSession(owner,id,body.expectedVersion(),body.title(),body.activeBranchId(),body.summaryAutoEnabled())));
   }
   @DeleteMapping("/sessions/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) {
      long owner=owner(); store().deleteSession(owner,id); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
   }
   @GetMapping("/sessions/{id}/branches") public ResponseEntity<List<AssistantDtos.Branch>> branches(@PathVariable UUID id) {
      long owner=owner(); return ok(store().branches(owner,id).stream().map(AssistantDtos::branch).toList());
   }
   @GetMapping("/sessions/{id}/messages") public ResponseEntity<AssistantDtos.History> messages(@PathVariable UUID id,
      @RequestParam(required=false) UUID branchId,@RequestParam(defaultValue="false") boolean includeHistory,@RequestParam(defaultValue="200") int limit) {
      long owner=owner(); return ok(new AssistantDtos.History(id,branchId,includeHistory,store().history(owner,id,branchId,includeHistory,limit).stream().map(AssistantDtos::message).toList()));
   }
   @GetMapping("/sessions/{id}/export") public ResponseEntity<byte[]> export(@PathVariable UUID id,
      @RequestParam(defaultValue="json") String format,@RequestParam(defaultValue="false") boolean includeHistory) {
      long owner=owner();
      if (!format.equals("json") && !format.equals("csv")) throw AssistantFailure.invalid("Export format must be json or csv");
      byte[] bytes=store().withExportSnapshot(owner,id,includeHistory,snapshot -> {
         if(execution!=null&&execution.available()&&snapshot.messages().size()>1000)throw new AssistantFailure(422,"ASSISTANT_EXPORT_LIMIT","This Assistant export exceeds the current 1000-message bound; no partial export was produced");
         var dto=AssistantDtos.export(snapshot,includeHistory);
         if (format.equals("csv")) {
            if(execution!=null&&execution.available())return executionCsv(owner,id,includeHistory);
            return csv(dto);
         }
         try {
            if(execution!=null&&execution.available()){
               var store=execution.require().store();var timeline=store.timeline(owner,id,null,includeHistory);
               var ids=timeline.stream().flatMap(row->row.runs().stream()).map(com.auralink.assistant.AssistantExecutionRecords.Run::id).collect(java.util.stream.Collectors.toSet());
               // Summary/retrieval runs have no assistant output row; retain them only for selected branches.
               var branches=dto.branches().stream().map(AssistantDtos.Branch::id).collect(java.util.stream.Collectors.toSet());
               snapshot.runs().stream().filter(r->!r.kind().equals("CHAT")&&branches.contains(r.branchId())).forEach(r->ids.add(r.id()));
               var filtered=new AssistantDtos.Export(dto.schemaVersion(),dto.exportedAt(),dto.includeHistory(),dto.session(),dto.branches(),dto.messages(),dto.runs().stream().filter(r->ids.contains(r.id())).toList(),dto.attachments());
               var extension=new java.util.LinkedHashMap<String,Object>();extension.put("schemaVersion",2);extension.put("foundation",filtered);
               extension.put("timeline",timeline.stream().map(AssistantExecutionDtos::timeline).toList());
               extension.put("executions",snapshot.runs().stream().filter(r->ids.contains(r.id())).map(r->Map.of("run",AssistantExecutionDtos.run(store.get(owner,r.id())),"citations",store.citations(owner,r.id()),"choices",store.choices(owner,r.id()))).toList());
               return json.writeValueAsBytes(extension);
            }
            return json.writeValueAsBytes(dto); }
         catch (JsonProcessingException error) { throw new IllegalStateException("Assistant export serialization failed"); }
      });
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(format.equals("json") ? MediaType.APPLICATION_JSON : new MediaType("text","csv",StandardCharsets.UTF_8))
         .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=assistant-"+id+"."+format)
         .header("X-Content-Type-Options","nosniff").body(bytes);
   }
   @PostMapping(value="/sessions/{id}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
   public ResponseEntity<AssistantDtos.Attachment> upload(@PathVariable UUID id,@RequestParam String requestKey,@RequestPart("file") MultipartFile file) {
      long owner=owner(); return ok(AssistantDtos.attachment(attachmentService().upload(owner,id,requestKey,file)));
   }
   @GetMapping("/sessions/{id}/attachments/draft") public ResponseEntity<?> draftAttachments(@PathVariable UUID id,
      @RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String cursor) {
      long owner=owner();return ok(store().draftAttachmentPage(owner,id,limit,cursor).map(AssistantDtos::draftAttachment));
   }
   @GetMapping("/attachments/{id}") public ResponseEntity<AssistantDtos.Attachment> attachment(@PathVariable UUID id) {
      long owner=owner(); return ok(AssistantDtos.attachment(attachmentService().metadata(owner,id)));
   }
   @GetMapping("/attachments/{id}/content") public ResponseEntity<byte[]> content(@PathVariable UUID id) {
      long owner=owner(); var content=attachmentService().content(owner,id);
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(content.metadata().mimeType()))
         .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename="+id+(content.metadata().mimeType().equals("image/png") ? ".png" : ".jpg"))
         .body(content.bytes());
   }
   @DeleteMapping("/attachments/{id}") public ResponseEntity<Void> removeAttachment(@PathVariable UUID id) {
      long owner=owner(); attachmentService().remove(owner,id); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
   }
   @ExceptionHandler(AssistantFailure.class) public ResponseEntity<ApiV1ErrorResponse> failure(AssistantFailure failure,HttpServletRequest request) {
      return error(failure.status(),failure.code(),failure.getMessage(),request);
   }
   @ExceptionHandler({DataAccessException.class,StorageException.class}) public ResponseEntity<ApiV1ErrorResponse> unavailable(Exception failure,HttpServletRequest request) {
      return error(503,"ASSISTANT_STORAGE_UNAVAILABLE","Assistant storage is temporarily unavailable",request);
   }
   private ResponseEntity<ApiV1ErrorResponse> error(int status,String code,String message,HttpServletRequest request) {
      String correlation=UUID.randomUUID().toString();
      return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).header("X-Correlation-ID",correlation)
         .body(new ApiV1ErrorResponse(Instant.now(),status,code,message,request.getRequestURI(),correlation,Map.of(),List.of()));
   }
   private long owner() { return users.requireCurrentUser().getId(); }
   private AssistantStore store() { var store=stores.getIfAvailable(); if (store==null) throw disabled(); return store; }
   private AssistantAttachmentService attachmentService() { var service=attachments.getIfAvailable(); if (service==null) throw disabled(); return service; }
   private static AssistantFailure disabled() { return new AssistantFailure(503,"ASSISTANT_DISABLED","Assistant is disabled"); }
   private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
   private byte[] executionCsv(long owner,UUID session,boolean history) {
      var store=execution.require().store();StringBuilder csv=new StringBuilder("message_id,role,state,created_at,classification,classification_source,body,runs_json,citations_json,choices_json\r\n");
      for(var row:store.timeline(owner,session,null,history)){
         try {
            var runs=row.runs().stream().map(AssistantExecutionDtos::run).toList();
            var citations=row.runs().stream().flatMap(r->store.citations(owner,r.id()).stream()).toList();
            var choices=row.runs().stream().flatMap(r->store.choices(owner,r.id()).stream()).toList();
            var cells=java.util.Arrays.asList(row.messageId().toString(),row.role(),row.state(),row.createdAt().toString(),row.classification(),row.classificationSource(),row.body(),json.writeValueAsString(runs),json.writeValueAsString(citations),json.writeValueAsString(choices));
            csv.append(cells.stream().map(AssistantController::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
         }catch(JsonProcessingException failure){throw new IllegalStateException("Assistant export serialization failed");}
      }
      return csv.toString().getBytes(StandardCharsets.UTF_8);
   }
   static byte[] csv(AssistantDtos.Export export) {
      StringBuilder value=new StringBuilder("message_id,role,state,created_at,body\r\n");
      for (var message : export.messages()) value.append(csvCell(message.id().toString())).append(',').append(csvCell(message.role())).append(',')
         .append(csvCell(message.state())).append(',').append(csvCell(message.createdAt().toString())).append(',').append(csvCell(message.body())).append("\r\n");
      return value.toString().getBytes(StandardCharsets.UTF_8);
   }
   static String csvCell(String input) {
      String value=input==null ? "" : input;
      String stripped=value.stripLeading();
      if ((!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0) || value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) value="'"+value;
      return "\""+value.replace("\"","\"\"")+"\"";
   }
}
