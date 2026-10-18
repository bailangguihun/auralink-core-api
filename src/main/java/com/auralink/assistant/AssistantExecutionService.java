package com.auralink.assistant;

import com.auralink.catalogread.AssistantArtworkRead;
import com.auralink.catalogread.CatalogMediaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.auralink.assistant.AssistantExecutionRecords.*;

/** Bounded dispatcher. HTTP subscriptions read durable events; they never own or resubmit execution. */
public final class AssistantExecutionService implements AutoCloseable {
 private final AssistantExecutionStore store;private final AssistantStore foundation;private final AssistantAttachmentService attachments;
 private final AssistantAiClient ai;private final AssistantArtworkRead artworks;private final CatalogMediaService media;private final AssistantExecutionProperties p;private final ObjectMapper json;private final Clock clock;
 private volatile Map<String,Object> registry;
 private final java.util.function.Supplier<String> generationControl;
 private final String worker=UUID.randomUUID().toString();
 private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2,thread("assistant-scheduler"));
 private final ExecutorService workers;private final Semaphore capacity;private final ConcurrentHashMap<UUID,Long> active=new ConcurrentHashMap<>();private volatile boolean closed;
 public AssistantExecutionService(AssistantExecutionStore store,AssistantStore foundation,AssistantAttachmentService attachments,AssistantAiClient ai,AssistantArtworkRead artworks,CatalogMediaService media,AssistantExecutionProperties p,ObjectMapper json,Clock clock){
  this(store,foundation,attachments,ai,artworks,media,p,json,clock,()->"AVAILABLE");
 }
 public AssistantExecutionService(AssistantExecutionStore store,AssistantStore foundation,AssistantAttachmentService attachments,AssistantAiClient ai,AssistantArtworkRead artworks,CatalogMediaService media,AssistantExecutionProperties p,ObjectMapper json,Clock clock,java.util.function.Supplier<String> generationControl){
  this.generationControl=Objects.requireNonNull(generationControl);
  this.store=store;this.foundation=foundation;this.attachments=attachments;this.ai=ai;this.artworks=artworks;this.media=media;this.p=p;this.json=json;this.clock=clock;
  workers=Executors.newFixedThreadPool(p.getConcurrency(),thread("assistant-execution"));capacity=new Semaphore(p.getConcurrency());
 }
 private static java.util.concurrent.ThreadFactory thread(String name){return r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;};}
 public String generationState(){return generationControl.get();}
 public boolean generationAvailable(){return "AVAILABLE".equals(generationState());}
 private void requireGeneration(){String state=generationState();if(!"AVAILABLE".equals(state))throw new AssistantFailure(503,state,"Assistant generation is suspended; existing history and attachments remain available");}
 public void start(){scheduler.scheduleWithFixedDelay(this::tick,50,p.getPollInterval().toMillis(),TimeUnit.MILLISECONDS);}
 public Map<String,Object> models(){Map<String,Object> result=ai.models();registry=result;return result;}
 @SuppressWarnings("unchecked") private Map<String,Object> model(String id){
  if(id==null||!id.matches("[A-Za-z0-9_.:-]{1,100}"))throw AssistantFailure.invalid("An explicit model selection is required");
  Map<String,Object> value=registry;if(value==null){synchronized(this){if(registry==null)models();value=registry;}}
  return ((List<Map<String,Object>>)value.getOrDefault("models",List.of())).stream().filter(m->id.equals(m.get("id"))&&Boolean.TRUE.equals(m.get("enabled"))&&Boolean.TRUE.equals(m.get("configured"))).findFirst().orElseThrow(()->new AssistantFailure(422,"ASSISTANT_MODEL_UNAVAILABLE","Selected model is unavailable; choose explicitly"));
 }
 public record ArtworkRef(UUID artworkId,UUID expectedBatchId,int expectedRevision,boolean includeImage){}
 public record Command(UUID branchId,long expectedVersion,String requestKey,String text,List<UUID> attachmentIds,String modelKey,Map<String,Boolean> retrieval,List<ArtworkRef> artworkRefs){}
 private Admission command(long owner,UUID session,Command c){
  foundation.requireOwnedSessionForRequest(owner,session);
  if(c==null||c.branchId()==null||c.expectedVersion()<0||c.text()==null||c.text().length()>32000||(c.text().isBlank()&&(c.attachmentIds()==null||c.attachmentIds().isEmpty())))throw AssistantFailure.invalid("A branch, version and bounded message are required");
  if(c.attachmentIds()!=null&&c.attachmentIds().stream().anyMatch(Objects::isNull)||c.artworkRefs()!=null&&c.artworkRefs().stream().anyMatch(Objects::isNull))throw AssistantFailure.invalid("Null attachment or artwork references are invalid");
  List<UUID> files=c.attachmentIds()==null?List.of():List.copyOf(c.attachmentIds());List<ArtworkRef> refs=c.artworkRefs()==null?List.of():List.copyOf(c.artworkRefs());
  if(files.size()>4||refs.size()>5||new HashSet<>(files).size()!=files.size()||refs.stream().map(ArtworkRef::artworkId).distinct().count()!=refs.size())throw AssistantFailure.invalid("Image/artwork input limit exceeded");
  Map<String,Boolean> retrieval=c.retrieval()==null?Map.of():c.retrieval();if(!Set.of("artworks","ragflow").containsAll(retrieval.keySet()))throw AssistantFailure.invalid("Unknown retrieval source");
  for(var ref:refs)if(ref.artworkId()==null||ref.expectedBatchId()==null||ref.expectedRevision()<1)throw AssistantFailure.invalid("Artwork identity and revision are required");
  if(!refs.isEmpty()&&!Boolean.TRUE.equals(retrieval.get("artworks")))throw AssistantFailure.invalid("Artwork references require explicit platform retrieval");
  var options=new LinkedHashMap<String,Object>();options.put("configSha256",p.getExpectedConfigSha256());options.put("retrieval",Map.of("artworks",Boolean.TRUE.equals(retrieval.get("artworks")),"ragflow",Boolean.TRUE.equals(retrieval.get("ragflow"))));options.put("artworkRefs",refs.stream().map(r->Map.of("artworkId",r.artworkId().toString(),"expectedBatchId",r.expectedBatchId().toString(),"expectedRevision",r.expectedRevision(),"includeImage",r.includeImage())).toList());options.put("promptVersion","assistant-chat-v1");
  return new Admission(session,c.branchId(),c.expectedVersion(),c.requestKey(),c.text(),files,c.modelKey(),options);
 }
 private void validateInput(long owner,Admission c){
  var selected=model(c.modelId());int images=c.attachmentIds().size();
  for(UUID id:c.attachmentIds()){var a=attachments.metadata(owner,id);if(!a.sessionId().equals(c.sessionId()))throw AssistantFailure.notFound();}
  // Existing branch images are included in capability checks; changing model never silently drops them.
  for(var m:foundation.history(owner,c.sessionId(),c.branchId(),false,p.getMaxContextMessages()))if(eligibleContextMessage(m,null))images+=m.attachmentIds().size();
  selectedArtworks(refs(c.options()));for(var ref:refs(c.options()))if(ref.includeImage())images++;
  if(images>4)throw new AssistantFailure(422,"ASSISTANT_IMAGE_LIMIT","The selected context exceeds four images");
  if(images>0&&!Boolean.TRUE.equals(map(selected.get("capabilities")).get("images")))throw new AssistantFailure(422,"ASSISTANT_IMAGES_UNSUPPORTED","The selected model does not accept images");
 }
 public Accepted admit(long owner,UUID session,Command c){var command=command(owner,session,c);var replay=store.replayAdmission(owner,command);if(replay.isPresent())return replay.get();requireGeneration();validateInput(owner,command);return store.admit(owner,command);}
 public Accepted retry(long owner,UUID id,String key,long version,boolean acknowledge){var replay=store.replayRetry(owner,id,key,version,acknowledge);if(replay.isPresent())return replay.get();requireGeneration();Run previous=store.get(owner,id);if(!previous.kind().equals("RETRIEVAL"))model(previous.modelId());if(!p.getExpectedConfigSha256().equals(previous.options().get("configSha256")))throw AssistantFailure.conflict("Retry requires the original model configuration");return store.retry(owner,id,key,version,acknowledge);}
 public AssistantRecords.Branch revise(long owner,UUID session,UUID message,UUID branch,long version,String key,String text){return foundation.reviseMessage(owner,session,branch,message,version,key,text);}
 public Accepted executeEdited(long owner,UUID session,Command c){
  Admission command=command(owner,session,c);if(!command.attachmentIds().isEmpty())throw AssistantFailure.invalid("Edited execution reuses its persisted image references");
  var replay=store.replayEditedInput(owner,command);if(replay.isPresent())return replay.get();requireGeneration();
  var branch=foundation.branches(owner,session).stream().filter(b->b.id().equals(c.branchId())).findFirst().orElseThrow(AssistantFailure::notFound);
  var head=foundation.history(owner,session,c.branchId(),false,1).stream().filter(m->m.id().equals(branch.headMessageId())).findFirst().orElseThrow(AssistantFailure::notFound);
  validateInput(owner,command);return store.admitEdited(owner,new Admission(session,c.branchId(),c.expectedVersion(),c.requestKey(),c.text(),head.attachmentIds(),c.modelKey(),command.options()));
 }
 public Accepted choice(long owner,UUID id,String card,String option,Command c){UUID session=store.ownedSessionForRequest(owner,id);Admission replayCommand=command(owner,session,c);var replay=store.replayChoice(owner,id,card,option,replayCommand);if(replay.isPresent())return replay.get();requireGeneration();Run source=store.get(owner,id);var selected=store.choices(owner,id).stream().filter(x->x.id().equals(card)).findFirst().orElseThrow(AssistantFailure::notFound);var selection=selected.options().stream().filter(x->x.id().equals(option)).findFirst().orElseThrow(AssistantFailure::notFound);Command normalized=new Command(c.branchId(),c.expectedVersion(),c.requestKey(),selection.text(),c.attachmentIds(),c.modelKey(),c.retrieval(),c.artworkRefs());Admission command=command(owner,source.sessionId(),normalized);validateInput(owner,command);return store.choice(owner,id,card,option,command);}
 public Run summary(long owner,UUID session,UUID branch,String key){foundation.session(owner,session);return store.summary(owner,session,branch,key,"",Map.of("promptVersion","assistant-summary-v1"),p.getMaxSummaryPerHour(),this::requireGeneration);}
 public Accepted retrieval(long owner,UUID session,Command c){Admission command=command(owner,session,c);return store.retrieval(owner,command);}
 public Run cancel(long owner,UUID id){var result=store.cancel(owner,id);ai.cancel(id);return result;}
 public List<AssistantArtworkRead.Artwork> search(long owner,String query){if(artworks==null)throw unavailable();return artworks.search(query,List.of(),10);}
 private AssistantArtworkRead.Artwork artwork(ArtworkRef ref){if(artworks==null)throw unavailable();return artworks.require(ref.artworkId(),ref.expectedBatchId(),ref.expectedRevision());}
 private static AssistantFailure unavailable(){return new AssistantFailure(503,"ASSISTANT_ARTWORK_UNAVAILABLE","Platform artwork retrieval is unavailable");}
 public AssistantExecutionStore store(){return store;}public AssistantExecutionProperties properties(){return p;}
 void tick(){
  if(closed)return;
  try{
   store.recoverExpired(50);
   if(!generationAvailable())return;
   if(p.isAutomaticSummaries())for(var candidate:store.automaticSummaryCandidates(p.getSummaryTurnThreshold(),p.getSummaryIdle(),10))try{summary(candidate.owner(),candidate.sessionId(),candidate.branchId(),"auto-summary:"+candidate.headMessageId());}catch(AssistantFailure ignored){}
   for(var pending:store.pendingSessions(50)){
    if(!generationAvailable())break;
    if(!capacity.tryAcquire())break;
    try{Optional<Run> run=store.claim(pending.owner(),pending.sessionId(),worker,p.getLease());if(run.isEmpty()){capacity.release();continue;}active.put(run.get().id(),pending.owner());workers.execute(()->execute(pending.owner(),run.get()));}catch(RuntimeException failure){capacity.release();throw failure;}
   }
  }catch(RuntimeException ignored){/* Database outage stops dispatch. Durable leases recover after connectivity returns. */}
 }
 private void execute(long owner,Run claimed){
  AtomicReference<Run> run=new AtomicReference<>(claimed);AtomicReference<String> text=new AtomicReference<>("");AtomicBoolean terminal=new AtomicBoolean();AtomicReference<Map<String,Object>> usage=new AtomicReference<>(Map.of("available",false));AtomicReference<List<ChoiceCard>> choices=new AtomicReference<>(List.of());List<Citation> citations=new ArrayList<>();AtomicBoolean sourcesObserved=new AtomicBoolean();
  var heartbeat=scheduler.scheduleWithFixedDelay(()->{try{store.heartbeat(owner,claimed.id(),claimed.fence(),worker,p.getLease());}catch(RuntimeException revoked){ai.closeStream(claimed.id());}},Math.max(100,p.getLease().toMillis()/3),Math.max(100,p.getLease().toMillis()/3),TimeUnit.MILLISECONDS);
  try{
   requireGeneration();
   if(!p.getExpectedConfigSha256().equals(claimed.options().get("configSha256")))throw new AssistantFailure(409,"ASSISTANT_CONFIG_CHANGED","Saved configuration is no longer available");
   var selected=claimed.kind().equals("RETRIEVAL")?Map.<String,Object>of():model(claimed.modelId());var preparation=new AssistantArtworkDeadline();var context=buildContext(owner,claimed,selected,preparation);Map<String,Object> retrieval=new LinkedHashMap<>();Map<String,Object> requested=map(claimed.options().get("retrieval"));
   boolean platform=Boolean.TRUE.equals(requested.get("artworks"))&&!claimed.kind().equals("SUMMARY");boolean rag=Boolean.TRUE.equals(requested.get("ragflow"))&&!claimed.kind().equals("SUMMARY");
   retrieval.put("artworks",platform?"EMPTY":"DISABLED");retrieval.put("ragflow",rag?"NOT_CONFIGURED":"DISABLED");
   if(platform){
    try{
     var references=refs(claimed.options());var found=selectedArtworks(references,preparation);
     var bounded=new AssistantArtworkContext(json);
     for(int i=0;i<found.size();i++)citations.add(bounded.snapshot(found.get(i),i+1,references.get(i).includeImage(),clock.instant()));
     retrieval.put("artworks",found.isEmpty()?"EMPTY":"AVAILABLE");
    }catch(AssistantFailure e){
     retrieval.put("artworks",e.status()==504?"TIMEOUT":"UNAVAILABLE");
     run.set(store.savePreparation(owner,claimed.id(),claimed.fence(),worker,context.manifest(),List.of(),retrieval));
     throw e; // Explicit selected-source failure never silently falls back to a charged general answer.
    }
   }
   if(rag){
    requireGeneration();
    run.set(store.markIntent(owner,claimed.id(),claimed.fence(),worker,"RETRIEVAL"));
    try{var result=ai.retrieval(context.query().substring(0,Math.min(2000,context.query().length())),true);String state=Objects.toString(result.get("state"),"UNAVAILABLE");if(!Set.of("DISABLED","NOT_CONFIGURED","AVAILABLE","EMPTY","FORBIDDEN","TIMEOUT","UNAVAILABLE").contains(state))state="UNAVAILABLE";retrieval.put("ragflow",state);
     if(state.equals("AVAILABLE"))for(Object item:list(result.get("citations"))){Map<String,Object> c=map(item);String excerpt=Objects.toString(c.get("text"),"");var provenance=new LinkedHashMap<String,Object>();for(String key:List.of("datasetId","documentId","chunkId","title","version","contentSha256"))provenance.put(key,c.get(key));provenance.put("configSha256",p.getExpectedConfigSha256());provenance.put("retrievedAt",clock.instant().toString());String upstreamVersion=c.get("version") instanceof String v&&!v.isBlank()?v:null;provenance.put("upstreamVersionAbsent",upstreamVersion==null);String sourceVersion=upstreamVersion==null?"received-content-sha256:"+digest(excerpt):upstreamVersion;citations.add(new Citation(UUID.randomUUID(),citations.size()+1,"RAGFLOW_DOCUMENT",Objects.toString(c.get("id"),""),sourceVersion,"ragflow-public-chunk-v1",provenance,excerpt,digest(excerpt)));}
    }catch(AssistantFailure e){retrieval.put("ragflow",e.code().equals("ASSISTANT_RETRIEVAL_TIMEOUT")?"TIMEOUT":"UNAVAILABLE");}
   }
   // Revalidate the currently public projection and owned attachment lifecycle
   // after potentially slow retrieval, before persistence/chargeable dispatch.
   revalidateCitations(citations,preparation);
   Context currentContext=buildContext(owner,claimed,selected,preparation);
   if(!context.manifest().get("contextSha256").equals(currentContext.manifest().get("contextSha256")))throw AssistantFailure.conflict("Assistant context changed before dispatch");
   run.set(store.savePreparation(owner,claimed.id(),claimed.fence(),worker,context.manifest(),List.of(),retrieval));
   if(claimed.kind().equals("RETRIEVAL")){
    var lookupOnly=citations.stream().map(c->{var v=new LinkedHashMap<>(c.provenance());v.put("deliveryEvidence","LOOKUP_ONLY");return new Citation(c.id(),c.ordinal(),c.sourceType(),c.sourceId(),c.sourceVersion(),c.projectionVersion(),v,c.excerpt(),c.excerptSha256());}).toList();
    store.complete(owner,claimed.id(),claimed.fence(),worker,"COMPLETED","",lookupOnly,Map.of("available",false,"purpose","RETRIEVAL"),List.of());terminal.set(true);return;
   }
   String notice=notice(retrieval);if(!notice.isEmpty()){text.set(notice);run.set(store.checkpoint(owner,claimed.id(),claimed.fence(),worker,run.get().sequence(),notice,Map.of("text",notice)));}
   // Re-check ownership/lifecycle and durable intent immediately before the potentially chargeable send.
   var boundedContext=new AssistantArtworkContext(json);var wireCitations=boundedContext.wire(citations);boundedContext.validateTextBudget(context.messages(),wireCitations);
   Map<String,Object> request=new LinkedHashMap<>();request.put("contractVersion",1);request.put("runId",claimed.id().toString());request.put("attemptId",claimed.id().toString());request.put("fence",claimed.fence());request.put("configSha256",p.getExpectedConfigSha256());request.put("purpose",claimed.kind());request.put("modelKey",claimed.modelId());request.put("messages",context.messages());request.put("citations",wireCitations);request.put("retrievalStates",retrieval);request.put("budgets",Map.of("totalMs",Math.min(180000,p.getTotalTimeout().toMillis()),"maxOutputTokens",4096));
   if(encode(request).getBytes(StandardCharsets.UTF_8).length>14000000)throw new AssistantFailure(422,"ASSISTANT_INPUT_LIMIT","The selected context exceeds the private execution request limit");
   requireGeneration();
   preparation.check();
   run.set(store.markIntent(owner,claimed.id(),claimed.fence(),worker,"MODEL"));
   Runnable confirmSources=()->{if(!sourcesObserved.get()){run.set(store.confirmModelSources(owner,claimed.id(),claimed.fence(),worker,citations));sourcesObserved.set(true);}};
   ai.execute(claimed.id(),request,(type,value)->{
    switch(type){
     case "delta" -> {Object delta=value.get("text");if(!(delta instanceof String))throw protocol();confirmSources.run();String next=text.get()+delta;if(next.length()>p.getMaxOutputChars())throw new AssistantFailure(502,"ASSISTANT_OUTPUT_LIMIT","Assistant output exceeds limit");run.set(store.checkpoint(owner,claimed.id(),claimed.fence(),worker,run.get().sequence(),next,Map.of("text",delta)));text.set(next);}
     case "usage" -> {confirmSources.run();usage.set(Map.of("available",true,"purpose",claimed.kind(),"providerReported",map(value.get("providerReported")),"verifiedBilling",false));}
     case "choices" -> choices.set(parseChoices(value));
     case "done" -> {confirmSources.run();run.set(store.complete(owner,claimed.id(),claimed.fence(),worker,"COMPLETED",text.get(),citations,usage.get(),choices.get()));terminal.set(true);}
     case "error" -> throw new AssistantFailure(502,"ASSISTANT_UPSTREAM_UNCERTAIN","Assistant upstream execution did not finish reliably");
     default -> throw protocol();
    }
   });
   if(!terminal.get())throw protocol();
  }catch(Exception error){
   if(error instanceof InterruptedException)Thread.currentThread().interrupt();
   try{Run latest=store.get(owner,claimed.id());if(!latest.terminal()){String state=latest.dispatchedAt()!=null||latest.retrievalDispatchedAt()!=null?"INTERRUPTED":"FAILED";String code=error instanceof AssistantFailure f?f.code():"ASSISTANT_EXECUTION_INTERRUPTED";store.complete(owner,claimed.id(),claimed.fence(),worker,state,latest.checkpoint(),sourcesObserved.get()?citations:List.of(),usage.get(),List.of(),code);}}catch(RuntimeException ignored){/* On unavailable persistence the durable lease expires; never resend. */}
   ai.cancel(claimed.id());
  }finally{heartbeat.cancel(false);active.remove(claimed.id());capacity.release();}
 }
 private record Context(List<Map<String,Object>> messages,Map<String,Object> manifest,String query){}
 private Context buildContext(long owner,Run run,Map<String,Object> selected,AssistantArtworkDeadline preparation){
  preparation.check();
  if(run.kind().equals("RETRIEVAL")){
   var input=store.context(owner,run.id(),1).stream().filter(m->m.id().equals(run.inputMessageId())&&m.role().equals("USER")).findFirst().orElseThrow(AssistantFailure::notFound);
   return new Context(List.of(),Map.of("version",1,"messageIds",List.of(input.id().toString()),"contextSha256",digest(input.body()),"images",List.of(),"characterCount",input.body().length(),"configSha256",p.getExpectedConfigSha256()),input.body());
  }
  List<AssistantRecords.Message> window=store.context(owner,run.id(),Math.min(100,p.getMaxContextMessages()+1));boolean truncated=window.size()>p.getMaxContextMessages()||window.size()==100;List<AssistantRecords.Message> history=window.subList(Math.max(0,window.size()-p.getMaxContextMessages()),window.size());List<Map<String,Object>> messages=new ArrayList<>();List<Object> manifests=new ArrayList<>();List<String> ids=new ArrayList<>();List<String> omitted=new ArrayList<>();int chars=0,images=0;String query="";
  for(var message:history){preparation.check();if(!eligibleContextMessage(message,run.inputMessageId()))continue;if(!Set.of("USER","ASSISTANT").contains(message.role()))continue;if(message.role().equals("ASSISTANT")&&!referencesStillPublic(owner,message.runId(),preparation)){omitted.add(message.id().toString());continue;}chars+=message.body().length();if(chars>p.getMaxContextChars())throw new AssistantFailure(422,"ASSISTANT_CONTEXT_LIMIT","The selected branch exceeds the context limit");
   List<Map<String,Object>> imageInputs=new ArrayList<>();for(UUID id:message.attachmentIds()){var content=attachments.content(owner,id);var image=AssistantImages.prepare(content.bytes(),content.metadata().mimeType(),id.toString());imageInputs.add(image.wire());manifests.add(image.manifest());images++;}
   var item=new LinkedHashMap<String,Object>();item.put("role",message.role().toLowerCase(Locale.ROOT));item.put("content",message.body());item.put("images",imageInputs);messages.add(item);ids.add(message.id().toString());if(message.role().equals("USER"))query=message.body();
  }
  if(!run.kind().equals("SUMMARY"))for(var ref:refs(run.options()))if(ref.includeImage()){
   var art=preparation.lookup(()->artwork(ref));if(media==null||art.mediaAssetId()==null)throw unavailable();var content=media.find(art.mediaAssetId().toString()).orElseThrow(AssistantExecutionService::unavailable);if(content.size()>10*1024*1024)throw AssistantFailure.invalid("Artwork image exceeds input limit");
   try(var stream=content.resource().getInputStream()){byte[] bytes=stream.readNBytes(10*1024*1024+1);var image=AssistantImages.prepare(bytes,content.mimeType(),art.artworkId().toString());Map<String,Object> last=messages.get(messages.size()-1);list(last.get("images")).add(image.wire());manifests.add(image.manifest());images++;}catch(java.io.IOException e){throw unavailable();}
  }
  if(images>4||images>0&&!Boolean.TRUE.equals(map(selected.get("capabilities")).get("images")))throw new AssistantFailure(422,"ASSISTANT_IMAGES_UNSUPPORTED","The selected context is incompatible with this model");
  if(messages.isEmpty())throw AssistantFailure.invalid("No eligible branch context");
  long imageBytes=0;
  for(var message:messages)for(Object input:list(message.get("images"))){String data=(String)map(input).get("dataBase64");imageBytes+=data.length()*3L/4;}
  if(imageBytes>8000000)throw new AssistantFailure(422,"ASSISTANT_IMAGE_LIMIT","The selected image context exceeds the aggregate execution limit");
  return new Context(messages,Map.of("version",1,"messageIds",ids,"omittedReferenceMessages",omitted,"historyTruncated",truncated,"contextSha256",digest(encode(messages)),"images",manifests,"messageLimit",p.getMaxContextMessages(),"characterCount",chars,"configSha256",p.getExpectedConfigSha256()),query);
 }
 static boolean eligibleContextMessage(AssistantRecords.Message message,UUID input){
  return message.id().equals(input)||message.role().equals("USER")&&Set.of("ACCEPTED","COMPLETED").contains(message.state())||message.role().equals("ASSISTANT")&&message.state().equals("COMPLETED");
 }
 private void revalidateCitations(List<Citation> citations,AssistantArtworkDeadline preparation){
  preparation.check();
  var platform=citations.stream().filter(c->c.sourceType().equals("PLATFORM_ARTWORK")).toList();
  var refs=platform.stream().map(c->{var v=c.provenance();return new ArtworkRef(UUID.fromString(c.sourceId()),UUID.fromString((String)v.get("batchId")),((Number)v.get("revision")).intValue(),false);}).toList();
  var current=selectedArtworks(refs,preparation);
  for(int i=0;i<platform.size();i++)if(!digest(encode(current.get(i).fields())).equals(AssistantArtworkContext.projectionHash(platform.get(i))))throw AssistantFailure.conflict("Public artwork projection changed before dispatch");
 }
 private List<AssistantArtworkRead.Artwork> selectedArtworks(List<ArtworkRef> refs){return selectedArtworks(refs,null);}
 private List<AssistantArtworkRead.Artwork> selectedArtworks(List<ArtworkRef> refs,AssistantArtworkDeadline preparation){
  if(preparation!=null)preparation.check();
  if(refs.isEmpty())return List.of();if(artworks==null)throw unavailable();
  try{
   var found=preparation==null?artworks.search("",refs.stream().map(ArtworkRef::artworkId).toList(),5):preparation.lookup(()->artworks.search("",refs.stream().map(ArtworkRef::artworkId).toList(),5));var result=new ArrayList<AssistantArtworkRead.Artwork>();
   for(var ref:refs){var value=found.stream().filter(a->a.artworkId().equals(ref.artworkId())).findFirst().orElseThrow(()->new AssistantFailure(404,"ASSISTANT_ARTWORK_NOT_FOUND","Selected artwork is no longer accessible; remove it and select again"));
    if(!value.batchId().equals(ref.expectedBatchId())||value.revision()!=ref.expectedRevision())throw new AssistantFailure(409,"ASSISTANT_ARTWORK_REVISION_CHANGED","Artwork revision changed; select the current public version");
    result.add(value);
   }return result;
  }catch(org.springframework.dao.QueryTimeoutException|org.springframework.transaction.TransactionTimedOutException timeout){throw new AssistantFailure(504,"ASSISTANT_ARTWORK_TIMEOUT","Platform artwork lookup timed out; no model was dispatched");}
  catch(org.springframework.dao.DataAccessException failure){throw unavailable();}
 }
 private boolean referencesStillPublic(long owner,UUID sourceRun,AssistantArtworkDeadline preparation){
  preparation.check();
  if(sourceRun==null)return true;
  for(var citation:store.citations(owner,sourceRun)){
   if(citation.sourceType().equals("PLATFORM_ARTWORK")){
    try{var v=citation.provenance();var current=preparation.lookup(()->artwork(new ArtworkRef(UUID.fromString(citation.sourceId()),UUID.fromString((String)v.get("batchId")),((Number)v.get("revision")).intValue(),false)));if(!digest(encode(current.fields())).equals(AssistantArtworkContext.projectionHash(citation)))return false;}catch(AssistantFailure failure){if(failure.code().equals("ASSISTANT_ARTWORK_TIMEOUT"))throw failure;return false;}catch(RuntimeException unavailable){preparation.check();return false;}
   }else if(citation.sourceType().equals("RAGFLOW_DOCUMENT")&&!p.getExpectedConfigSha256().equals(citation.provenance().get("configSha256")))return false;
  }
  return true;
 }
 private static List<ArtworkRef> refs(Map<String,Object> options){return list(options.get("artworkRefs")).stream().map(item->{Map<String,Object> r=map(item);return new ArtworkRef(UUID.fromString((String)r.get("artworkId")),UUID.fromString((String)r.get("expectedBatchId")),((Number)r.get("expectedRevision")).intValue(),Boolean.TRUE.equals(r.get("includeImage")));}).toList();}
 static String notice(Map<String,Object> states){var missing=states.entrySet().stream().filter(e->!Set.of("AVAILABLE","DISABLED").contains(e.getValue())).map(e->e.getKey()+"="+e.getValue()).toList();return missing.isEmpty()?"":"【资料检索状态："+String.join("，",missing)+"；以下回答不代表已获得该来源的证据。】\n";}
 static List<ChoiceCard> parseChoices(Map<String,Object> value){try{List<ChoiceCard> cards=new ArrayList<>();for(Object item:list(value.get("cards"))){var card=map(item);List<ChoiceOption> options=list(card.get("options")).stream().map(v->{var o=map(v);String label=(String)o.get("label");return new ChoiceOption((String)o.get("id"),label,label);}).toList();cards.add(new ChoiceCard((String)card.get("id"),(String)card.get("title"),options));}return AssistantExecutionStore.validChoices(cards)?cards:List.of();}catch(RuntimeException e){return List.of();}}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw protocol();}}
 private static String digest(String value){return AssistantAttachmentStorage.sha256(value.getBytes(StandardCharsets.UTF_8));}
 @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();}
 @SuppressWarnings("unchecked") private static List<Object> list(Object value){return value instanceof List<?>?(List<Object>)value:List.of();}
 private static AssistantFailure protocol(){return new AssistantFailure(502,"ASSISTANT_PROTOCOL_ERROR","Assistant stream contract was not satisfied");}
 @Override public void close(){closed=true;scheduler.shutdownNow();active.keySet().forEach(ai::closeStream);workers.shutdownNow();/* Leave unresolved intent durable for lease-based recovery; no automatic resubmission. */}
}
