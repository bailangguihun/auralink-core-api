package com.auralink.assistant;

import static com.auralink.assistant.AssistantRecords.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL authority. All object operations lock/check the owning session first.
 * No model dispatch, thread scheduler, migration, or ambient production configuration. */
public final class PostgresAssistantStore implements AssistantStore {
   private final JdbcTemplate jdbc;
   private final TransactionTemplate transaction;
   private final Clock clock;
   private final Duration requestWindow;
   private final int maxPending;
   private final AssistantStoragePolicy storagePolicy;
   public PostgresAssistantStore(DataSource source,Clock clock,Duration requestWindow,int maxPending) {
      this(source,clock,requestWindow,maxPending,AssistantStoragePolicy.missing());
   }
   public PostgresAssistantStore(DataSource source,Clock clock,Duration requestWindow,int maxPending,AssistantStoragePolicy storagePolicy) {
      this.storagePolicy=Objects.requireNonNull(storagePolicy);
      this.jdbc=new JdbcTemplate(source); this.transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
      this.clock=Objects.requireNonNull(clock); this.requestWindow=Objects.requireNonNull(requestWindow);
      if(requestWindow.isNegative()||requestWindow.isZero()||maxPending<1||maxPending>100) throw new IllegalArgumentException("Invalid Assistant limits");
      this.maxPending=maxPending;
   }
   /** Read-only startup contract for explicitly enabled deployments; never called in disabled mode. */
   public void verifySchema(){
      String[][] shapes={
         {"assistant_sessions","id,owner_id,title,active_branch_id,version,summary_auto_enabled,status,created_at,updated_at,deleted_at"},
         {"assistant_branches","id,session_id,parent_branch_id,fork_message_id,head_message_id,version,created_at"},
         {"assistant_messages","id,session_id,parent_message_id,version_group_id,supersedes_id,role,body,state,run_id,created_at"},
         {"assistant_requests","owner_id,operation,key_hash,payload_hash,session_id,result_id,status,created_at,expires_at"},
         {"assistant_runs","id,session_id,branch_id,input_message_id,kind,status,fence,sequence,checkpoint,context_version,created_at,updated_at"},
         {"assistant_attachments","id,session_id,storage_key,filename,mime_type,byte_size,sha256,width,height,status,created_at,updated_at,draft_expires_at"},
         {"assistant_message_attachments","session_id,message_id,attachment_id,position"},
         {"assistant_run_events","session_id,run_id,sequence,event_type,payload,created_at"},
         {"assistant_citations","id,session_id,run_id,ordinal,source_type,source_id,source_version,projection_version,provenance,excerpt,excerpt_sha256"},
         {"assistant_interaction_selections","id,session_id,source_message_id,target_message_id,card_id,option_id,created_at"},
         {"assistant_summaries","id,session_id,branch_id,head_message_id,context_hash,model_config_hash,prompt_version,current_run_id,body"},
         {"assistant_stage_classifications","session_id,message_id,version,label,source,classifier_version,created_at"}
      };
      try{
         for(String[] shape:shapes)jdbc.queryForList("SELECT "+shape[1]+" FROM "+shape[0]+" WHERE false");
         Integer indexes=jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN ('assistant_one_executing','assistant_one_queued_summary')",Integer.class);
         if(indexes==null||indexes!=2)throw new AssistantFailure(503,"ASSISTANT_SCHEMA_UNAVAILABLE","Assistant schema requires an explicitly applied compatible migration");
      }catch(org.springframework.dao.DataAccessException unavailable){throw new AssistantFailure(503,"ASSISTANT_SCHEMA_UNAVAILABLE","Assistant schema requires an explicitly applied compatible migration");}
   }
   private <T>T tx(Supplier<T> work) { return transaction.execute(status->work.get()); }
   private Timestamp now(){return Timestamp.from(clock.instant());}
   private static UUID uuid(ResultSet r,String key)throws SQLException{return r.getObject(key,UUID.class);}
   private static Instant time(ResultSet r,String key)throws SQLException{return r.getTimestamp(key).toInstant();}
   private static final RowMapper<Session> SESSION=(r,n)->new Session(uuid(r,"id"),r.getString("title"),uuid(r,"active_branch_id"),r.getLong("version"),r.getBoolean("summary_auto_enabled"),r.getString("status"),time(r,"created_at"),time(r,"updated_at"));
   private static final RowMapper<Branch> BRANCH=(r,n)->new Branch(uuid(r,"id"),uuid(r,"session_id"),uuid(r,"parent_branch_id"),uuid(r,"fork_message_id"),uuid(r,"head_message_id"),r.getLong("version"),time(r,"created_at"));
   private static final RowMapper<Message> MESSAGE=(r,n)->new Message(uuid(r,"id"),uuid(r,"session_id"),uuid(r,"parent_message_id"),uuid(r,"version_group_id"),uuid(r,"supersedes_id"),r.getString("role"),r.getString("body"),r.getString("state"),uuid(r,"run_id"),time(r,"created_at"),List.of());
   private static final RowMapper<Run> RUN=(r,n)->new Run(uuid(r,"id"),uuid(r,"session_id"),uuid(r,"branch_id"),r.getString("kind"),r.getString("status"),uuid(r,"input_message_id"),r.getLong("fence"),r.getLong("sequence"),r.getString("checkpoint"),time(r,"created_at"),time(r,"updated_at"));
   private static final RowMapper<Attachment> ATTACHMENT=(r,n)->new Attachment(uuid(r,"id"),uuid(r,"session_id"),r.getString("storage_key"),r.getString("filename"),r.getString("mime_type"),r.getLong("byte_size"),r.getString("sha256"),r.getInt("width"),r.getInt("height"),r.getString("status"),time(r,"created_at"),time(r,"updated_at"));
   private <T>T one(String sql,RowMapper<T> mapper,Object...args){var rows=jdbc.query(sql,mapper,args);if(rows.isEmpty())throw AssistantFailure.notFound();return rows.get(0);}
   private Session lock(long owner,UUID session,boolean deletedAllowed){
      Session s=one("SELECT * FROM assistant_sessions WHERE id=? AND owner_id=? FOR UPDATE",SESSION,session,owner);
      if(!deletedAllowed&&!s.status().equals("ACTIVE"))throw AssistantFailure.notFound();return s;
   }
   private Branch branch(UUID session,UUID id){return one("SELECT * FROM assistant_branches WHERE session_id=? AND id=?",BRANCH,session,id);}
   private Run rawRun(UUID id){return one("SELECT * FROM assistant_runs WHERE id=?",RUN,id);}
   private Attachment rawAttachment(UUID id){return one("SELECT * FROM assistant_attachments WHERE id=?",ATTACHMENT,id);}
   private static void bound(String value,int maximum,String label){if(value==null||value.getBytes(StandardCharsets.UTF_8).length>maximum)throw AssistantFailure.invalid("Invalid "+label);}
   private static String title(String text){String value=text==null?"新对话":text.strip();if(value.isBlank()||value.length()>160)throw AssistantFailure.invalid("Invalid session title");return value;}
   private static String key(String value){if(value==null||value.length()<8||value.length()>128||!value.matches("[A-Za-z0-9_.:-]+"))throw AssistantFailure.invalid("Invalid idempotency key");return digest(value);}
   public static String digest(String...values){try{var digest=MessageDigest.getInstance("SHA-256");for(String value:values){byte[] b=Objects.toString(value,"<null>").getBytes(StandardCharsets.UTF_8);digest.update((b.length+":").getBytes(StandardCharsets.US_ASCII));digest.update(b);}return HexFormat.of().formatHex(digest.digest());}catch(Exception e){throw new IllegalStateException(e);}}
   private void expire(long owner){jdbc.update("UPDATE assistant_requests SET status='EXPIRED',result_id=NULL WHERE owner_id=? AND status='ACTIVE' AND expires_at<=?",owner,now());}
   private void ownerLock(long owner){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"artlive-assistant-request-owner:"+owner);}
   private void checkKnownRequest(long owner,String operation,String requestKey,String hash){
      var rows=jdbc.queryForList("SELECT payload_hash,status FROM assistant_requests WHERE owner_id=? AND operation=? AND key_hash=?",owner,operation,key(requestKey));
      if(!rows.isEmpty()){var r=rows.get(0);if(!hash.equals(r.get("payload_hash")))throw AssistantFailure.conflict("Idempotency key was used with a different payload");if(!"ACTIVE".equals(r.get("status")))throw AssistantFailure.gone();}
   }
   private record Replay(UUID id,boolean replay){}
   private Replay request(long owner,String operation,String requestKey,String payloadHash,UUID session,Supplier<UUID> create){
      String hash=key(requestKey);
      ownerLock(owner);
      var rows=jdbc.queryForList("SELECT payload_hash,result_id,status FROM assistant_requests WHERE owner_id=? AND operation=? AND key_hash=? FOR UPDATE",owner,operation,hash);
      if(!rows.isEmpty()){
         var previous=rows.get(0);
         if(!payloadHash.equals(previous.get("payload_hash")))throw AssistantFailure.conflict("Idempotency key was used with a different payload");
         if(!"ACTIVE".equals(previous.get("status")))throw AssistantFailure.gone();
         return new Replay((UUID)previous.get("result_id"),true);
      }
      UUID id=create.get();
      jdbc.update("INSERT INTO assistant_requests(owner_id,operation,key_hash,payload_hash,session_id,result_id,status,created_at,expires_at) VALUES(?,?,?,?,?,?,'ACTIVE',?,?)",owner,operation,hash,payloadHash,session,id,now(),Timestamp.from(clock.instant().plus(requestWindow)));
      return new Replay(id,false);
   }
   @Override public Session createSession(long owner,String requestKey,String value){String title=title(value);expire(owner);return tx(()->{
      Replay result=request(owner,"CREATE_SESSION",requestKey,digest(title),null,()->{
         UUID id=UUID.randomUUID(),branch=UUID.randomUUID();
         jdbc.update("INSERT INTO assistant_sessions(id,owner_id,title,active_branch_id,created_at,updated_at) VALUES(?,?,?,?,?,?)",id,owner,title,branch,now(),now());
         jdbc.update("INSERT INTO assistant_branches(id,session_id,created_at) VALUES(?,?,?)",branch,id,now());return id;
      });
      Session session=lock(owner,result.id(),false);
      jdbc.update("UPDATE assistant_requests SET session_id=? WHERE owner_id=? AND operation='CREATE_SESSION' AND key_hash=?",session.id(),owner,key(requestKey));return session;
   });}
   @Override public List<Session> sessions(long owner,int limit,int offset){if(limit<1||limit>100||offset<0||offset>100000)throw AssistantFailure.invalid("Invalid pagination");return jdbc.query("SELECT * FROM assistant_sessions WHERE owner_id=? AND status='ACTIVE' ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",SESSION,owner,limit,offset);}
   @Override public AssistantPage<Session> sessionPage(long owner,int limit,String cursor){
      AssistantPage.limit(limit);String scope="sessions";var c=AssistantPage.cursor(cursor,scope);
      if(c!=null&&(c.at()==null||c.head()!=null))throw AssistantFailure.invalid("Invalid session cursor");
      // Immutable creation order avoids losing a session when a different device updates it.
      var rows=c==null?jdbc.query("SELECT * FROM assistant_sessions WHERE owner_id=? AND status='ACTIVE' ORDER BY created_at DESC,id DESC LIMIT ?",SESSION,owner,limit+1)
         :jdbc.query("SELECT * FROM assistant_sessions WHERE owner_id=? AND status='ACTIVE' AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",SESSION,owner,Timestamp.from(c.at()),c.id(),limit+1);
      return AssistantPage.of(rows,limit,row->AssistantPage.cursor(scope,row.createdAt(),row.id(),null));
   }
   @Override public AssistantPage<Branch> branchPage(long owner,UUID session,int limit,String cursor){
      AssistantPage.limit(limit);String scope="branches:"+session;var c=AssistantPage.cursor(cursor,scope);
      if(c!=null&&(c.at()==null||c.head()!=null))throw AssistantFailure.invalid("Invalid branch cursor");
      return tx(()->{lock(owner,session,false);var rows=c==null?jdbc.query("SELECT * FROM assistant_branches WHERE session_id=? ORDER BY created_at,id LIMIT ?",BRANCH,session,limit+1)
         :jdbc.query("SELECT * FROM assistant_branches WHERE session_id=? AND (created_at,id)>(?,?) ORDER BY created_at,id LIMIT ?",BRANCH,session,Timestamp.from(c.at()),c.id(),limit+1);
         return AssistantPage.of(rows,limit,row->AssistantPage.cursor(scope,row.createdAt(),row.id(),null));});
   }
   private static final String BRANCH_PATH="WITH RECURSIVE path AS (SELECT id,parent_message_id FROM assistant_messages WHERE session_id=? AND id=? UNION SELECT m.id,m.parent_message_id FROM assistant_messages m JOIN path p ON m.id=p.parent_message_id WHERE m.session_id=?) ";
   private boolean inPath(UUID session,UUID head,UUID message){return jdbc.queryForObject(BRANCH_PATH+"SELECT count(*) FROM path WHERE id=?",Integer.class,session,head,session,message)==1;}
   private static final String MESSAGE_PAGE="WITH RECURSIVE path AS (SELECT m.*,0 depth FROM assistant_messages m WHERE session_id=? AND id=? UNION ALL SELECT m.*,p.depth+1 FROM assistant_messages m JOIN path p ON m.id=p.parent_message_id AND m.session_id=p.session_id WHERE p.depth<?) CYCLE id SET cycle USING traversal ";
   private AssistantPage<Message> chronologicalPage(List<Message> newest,int limit,Function<Message,String> cursor){
      var page=AssistantPage.of(newest,limit,cursor);var ordered=new ArrayList<>(page.items());java.util.Collections.reverse(ordered);return new AssistantPage<>(List.copyOf(ordered),page.nextCursor(),page.hasMore());
   }
   @Override public AssistantPage<Message> historyPage(long owner,UUID session,UUID branchId,boolean all,int limit,String cursor){
      AssistantPage.limit(limit);return tx(()->{
         Session s=lock(owner,session,false);Branch b=branch(session,branchId==null?s.activeBranchId():branchId);
         String scope="messages:"+session+":"+b.id()+":"+all;var c=AssistantPage.cursor(cursor,scope);
         if(all){if(c!=null&&(c.at()==null||c.head()!=null))throw AssistantFailure.invalid("Invalid history cursor");
            var rows=c==null?jdbc.query("SELECT * FROM assistant_messages WHERE session_id=? ORDER BY created_at DESC,id DESC LIMIT ?",MESSAGE,session,limit+1)
               :jdbc.query("SELECT * FROM assistant_messages WHERE session_id=? AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",MESSAGE,session,Timestamp.from(c.at()),c.id(),limit+1);
            return chronologicalPage(rows.stream().map(this::withAttachments).toList(),limit,row->AssistantPage.cursor(scope,row.createdAt(),row.id(),null));
         }
         UUID head=c==null?b.headMessageId():c.head();if(head==null){if(c!=null)throw AssistantFailure.invalid("Invalid branch cursor");return new AssistantPage<>(List.of(),null,false);}
         UUID start=head;
         if(c!=null){
            if(c.at()!=null||(!head.equals(b.headMessageId())&&!inPath(session,b.headMessageId(),head))||!inPath(session,head,c.id()))throw AssistantFailure.invalid("Page snapshot is not in this branch");
            start=jdbc.queryForObject("SELECT parent_message_id FROM assistant_messages WHERE session_id=? AND id=?",UUID.class,session,c.id());
         }
         // Fetch at most one page plus the lookahead row, regardless of branch length.
         var rows=jdbc.query(MESSAGE_PAGE+"SELECT * FROM path WHERE NOT cycle ORDER BY depth LIMIT ?",MESSAGE,session,start,limit,limit+1).stream().map(this::withAttachments).toList();
         return chronologicalPage(rows,limit,row->AssistantPage.cursor(scope,null,row.id(),head));
      });
   }
   @Override public Session session(long owner,UUID id){return tx(()->lock(owner,id,false));}
   @Override public void requireOwnedSessionForRequest(long owner,UUID id){tx(()->{lock(owner,id,true);return null;});}
   @Override public Session updateSession(long owner,UUID id,long version,String value,UUID active,Boolean auto){return tx(()->{
      Session current=lock(owner,id,false);if(current.version()!=version)throw AssistantFailure.conflict("Session version changed");
      if(active!=null){branch(id,active);if(!active.equals(current.activeBranchId()))invalidateSummaries(id);}
      jdbc.update("UPDATE assistant_sessions SET title=?,active_branch_id=?,summary_auto_enabled=?,version=version+1,updated_at=? WHERE id=?",value==null?current.title():title(value),active==null?current.activeBranchId():active,auto==null?current.summaryAutoEnabled():auto,now(),id);
      return lock(owner,id,false);
   });}
   @Override public void deleteSession(long owner,UUID id){tx(()->{
      ownerLock(owner);Session s=lock(owner,id,true);if(s.status().equals("DELETED"))return null;
      jdbc.update("UPDATE assistant_sessions SET status='DELETED',title='',deleted_at=?,updated_at=?,version=version+1 WHERE id=?",now(),now(),id);
      jdbc.update("UPDATE assistant_requests SET status='DELETED',result_id=NULL WHERE session_id=?",id);
      jdbc.update("UPDATE assistant_runs SET status=CASE WHEN status IN ('QUEUED','EXECUTING') THEN 'CANCELLED' ELSE status END,fence=fence+1,checkpoint='',updated_at=? WHERE session_id=?",now(),id);
      jdbc.update("UPDATE assistant_messages SET body='' WHERE session_id=?",id);
      jdbc.update("UPDATE assistant_attachments SET status='DELETING',updated_at=? WHERE session_id=? AND status<>'DELETED'",now(),id);
      jdbc.update("DELETE FROM assistant_run_events WHERE session_id=?",id);
      jdbc.update("DELETE FROM assistant_citations WHERE session_id=?",id);
      jdbc.update("DELETE FROM assistant_interaction_selections WHERE session_id=?",id);
      jdbc.update("DELETE FROM assistant_summaries WHERE session_id=?",id);
      jdbc.update("DELETE FROM assistant_stage_classifications WHERE session_id=?",id);return null;
   });}
   @Override public List<Branch> branches(long owner,UUID id){return tx(()->{lock(owner,id,false);return jdbc.query("SELECT * FROM assistant_branches WHERE session_id=? ORDER BY created_at,id",BRANCH,id);});}
   @Override public Branch branch(long owner,UUID sessionId,UUID branchId){return tx(()->{lock(owner,sessionId,false);return branch(sessionId,branchId);});}
   @Override public RequestResolution resolveRequest(long owner,String operation,String requestKey,UUID sessionId){
      return resolveRequest(owner,operation,requestKey,sessionId,false);
   }
   @Override public RequestResolution closeUnconfirmedChatRequest(long owner,String operation,String requestKey,UUID sessionId){
      if(!"CHAT_EXECUTION".equals(operation)||sessionId==null)throw AssistantFailure.invalid("Closing a request requires a chat operation and session");
      return resolveRequest(owner,operation,requestKey,sessionId,true);
   }
   private RequestResolution resolveRequest(long owner,String operation,String requestKey,UUID sessionId,boolean closeIfMissing){
      String type=switch(operation==null?"":operation){case "CREATE_SESSION"->"SESSION";case "CHAT_EXECUTION","EDITED_EXECUTION","RETRY_EXECUTION","RETRIEVAL_EXECUTION","SUMMARY_EXECUTION"->"RUN";case "REVISE_MESSAGE"->"BRANCH";case "UPLOAD_ATTACHMENT"->"ATTACHMENT";case "CLASSIFY_MESSAGE"->"MESSAGE";default->throw AssistantFailure.invalid("Unsupported request operation");};
      String hash=key(requestKey);return tx(()->{
         // Admission uses the same owner lock. A tombstone prevents any late admission for this key.
         ownerLock(owner);if(sessionId!=null)lock(owner,sessionId,true);
         var rows=jdbc.queryForList("SELECT session_id,result_id,status,expires_at FROM assistant_requests WHERE owner_id=? AND operation=? AND key_hash=?",owner,operation,hash);
         if(rows.isEmpty()){
            if(sessionId!=null)lock(owner,sessionId,false);
            if(closeIfMissing){
               Instant created=clock.instant();
               jdbc.update("INSERT INTO assistant_requests(owner_id,operation,key_hash,payload_hash,session_id,status,created_at,expires_at) VALUES(?,?,?,?,?,'EXPIRED',?,?)",
                  owner,operation,hash,digest("unconfirmed-chat-closed"),sessionId,Timestamp.from(created),Timestamp.from(created.plus(requestWindow)));
            }
            return new RequestResolution(operation,closeIfMissing?"CLOSED":"NOT_FOUND",sessionId,type,null,null,null,null);
         }
         var row=rows.get(0);UUID actual=(UUID)row.get("session_id");if(sessionId!=null&&!sessionId.equals(actual))throw AssistantFailure.notFound();
         if(closeIfMissing&&("EXPIRED".equals(row.get("status"))||("ACTIVE".equals(row.get("status"))&&!((Timestamp)row.get("expires_at")).toInstant().isAfter(clock.instant())))){
            lock(owner,sessionId,false);
            return new RequestResolution(operation,"CLOSED",sessionId,type,null,null,null,null);
         }
         if(!"ACTIVE".equals(row.get("status"))||!((Timestamp)row.get("expires_at")).toInstant().isAfter(clock.instant()))throw AssistantFailure.gone();
         if(actual==null)throw AssistantFailure.notFound();lock(owner,actual,false);
         UUID result=(UUID)row.get("result_id");String resultStatus=null;Boolean linked=null;
         if(type.equals("ATTACHMENT")){
            Attachment attachment=rawAttachment(result);if(!actual.equals(attachment.sessionId()))throw AssistantFailure.notFound();
            resultStatus=attachment.status();linked=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM assistant_message_attachments WHERE session_id=? AND attachment_id=?)",Boolean.class,actual,result);
         }
         return new RequestResolution(operation,"FOUND",actual,type,result,resultStatus,((Timestamp)row.get("expires_at")).toInstant(),linked);
      });
   }
   private Message withAttachments(Message m){return new Message(m.id(),m.sessionId(),m.parentMessageId(),m.versionGroupId(),m.supersedesId(),m.role(),m.body(),m.state(),m.runId(),m.createdAt(),jdbc.queryForList("SELECT attachment_id FROM assistant_message_attachments WHERE message_id=? ORDER BY position",UUID.class,m.id()));}
   private List<Message> messages(UUID session,UUID branchId,boolean all,int limit){
      if(all)return jdbc.query("SELECT * FROM (SELECT * FROM assistant_messages WHERE session_id=? ORDER BY created_at DESC,id DESC LIMIT ?) recent ORDER BY created_at,id",MESSAGE,session,limit).stream().map(this::withAttachments).toList();
      Branch b=branch(session,branchId);
      return jdbc.query("WITH RECURSIVE path AS (SELECT m.*,0 depth FROM assistant_messages m WHERE session_id=? AND id=? UNION ALL SELECT m.*,p.depth+1 FROM assistant_messages m JOIN path p ON m.id=p.parent_message_id AND m.session_id=p.session_id WHERE p.depth<10000) SELECT * FROM (SELECT * FROM path ORDER BY depth ASC LIMIT ?) recent ORDER BY depth DESC",MESSAGE,session,b.headMessageId(),limit).stream().map(this::withAttachments).toList();
   }
   @Override public List<Message> history(long owner,UUID id,UUID branchId,boolean all,int limit){if(limit<1||limit>10000)throw AssistantFailure.invalid("Invalid history limit");return tx(()->{Session s=lock(owner,id,false);if(branchId!=null)branch(id,branchId);return messages(id,branchId==null?s.activeBranchId():branchId,all,limit);});}
   @Override public ExportSnapshot exportSnapshot(long owner,UUID id,boolean all){return tx(()->{
      Session s=lock(owner,id,false);List<Message> messages=messages(id,s.activeBranchId(),all,10001);if(messages.size()>10000)throw AssistantFailure.conflict("Export exceeds bounded history window");
      var ids=messages.stream().map(Message::id).toList();
      List<Run> runs=jdbc.query("SELECT * FROM assistant_runs WHERE session_id=? ORDER BY created_at,id",RUN,id).stream().filter(r->all||ids.contains(r.inputMessageId())).toList();
      List<Attachment> attachments=jdbc.query("SELECT DISTINCT a.* FROM assistant_attachments a JOIN assistant_message_attachments ma ON ma.attachment_id=a.id WHERE a.session_id=? AND a.status='READY' ORDER BY a.created_at,a.id",ATTACHMENT,id).stream().filter(a->all||jdbc.queryForList("SELECT message_id FROM assistant_message_attachments WHERE attachment_id=?",UUID.class,a.id()).stream().anyMatch(ids::contains)).toList();
      return new ExportSnapshot(s,all?jdbc.query("SELECT * FROM assistant_branches WHERE session_id=? ORDER BY created_at,id",BRANCH,id):List.of(branch(id,s.activeBranchId())),messages,runs,attachments);
   });}
   @Override public <T>T withExportSnapshot(long owner,UUID id,boolean all,Function<ExportSnapshot,T> action){return tx(()->{lock(owner,id,false);return action.apply(exportSnapshot(owner,id,all));});}
   @Override public Run run(long owner,UUID id){return tx(()->{Run r=rawRun(id);lock(owner,r.sessionId(),false);return rawRun(id);});}
   private void invalidateSummaries(UUID id){jdbc.update("UPDATE assistant_runs SET status='CANCELLED',updated_at=? WHERE session_id=? AND kind='SUMMARY' AND status='QUEUED'",now(),id);}
   private void pendingCapacity(UUID id){Integer count=jdbc.queryForObject("SELECT count(*) FROM assistant_runs WHERE session_id=? AND status='QUEUED'",Integer.class,id);if(count>=maxPending)throw AssistantFailure.conflict("Assistant pending capacity reached");}
   @Override public AcceptedTurn acceptTurn(long owner,UUID id,UUID branchId,long version,String requestKey,String body,List<UUID> attachmentIds){
      bound(body,65536,"message");if(body.isBlank()&&attachmentIds.isEmpty())throw AssistantFailure.invalid("Empty message");if(attachmentIds.size()>4||attachmentIds.stream().distinct().count()!=attachmentIds.size())throw AssistantFailure.invalid("Invalid attachments");
      String hash=digest(id.toString(),branchId.toString(),Long.toString(version),body,attachmentIds.toString());expire(owner);return tx(()->{
         ownerLock(owner);checkKnownRequest(owner,"ACCEPT_TURN",requestKey,hash);lock(owner,id,false);
         Replay accepted=request(owner,"ACCEPT_TURN",requestKey,hash,id,()->{
            Branch b=branch(id,branchId);if(b.version()!=version)throw AssistantFailure.conflict("Branch version changed");
            if(jdbc.queryForObject("SELECT count(*) FROM assistant_runs WHERE session_id=? AND kind='CHAT' AND status IN ('QUEUED','EXECUTING')",Integer.class,id)>0)throw AssistantFailure.conflict("A chat turn is still pending");
            invalidateSummaries(id);pendingCapacity(id);
            for(UUID attachmentId:attachmentIds){Attachment a=rawAttachment(attachmentId);if(!a.sessionId().equals(id)||!a.status().equals("READY"))throw AssistantFailure.notFound();requireSendableAttachment(a.id());}
            UUID message=UUID.randomUUID(),run=UUID.randomUUID();
            jdbc.update("INSERT INTO assistant_messages(id,session_id,parent_message_id,version_group_id,role,body,state,run_id,created_at) VALUES(?,?,?,?,'USER',?,'ACCEPTED',?,?)",message,id,b.headMessageId(),message,body,run,now());
            jdbc.update("INSERT INTO assistant_runs(id,session_id,branch_id,input_message_id,kind,status,context_version,created_at,updated_at) VALUES(?,?,?,?,'CHAT','QUEUED',?,?,?)",run,id,branchId,message,version+1,now(),now());
            for(int i=0;i<attachmentIds.size();i++)jdbc.update("INSERT INTO assistant_message_attachments VALUES(?,?,?,?)",id,message,attachmentIds.get(i),i);
            jdbc.update("UPDATE assistant_branches SET head_message_id=?,version=version+1 WHERE id=?",message,branchId);
            jdbc.update("UPDATE assistant_sessions SET active_branch_id=?,version=version+1,updated_at=? WHERE id=?",branchId,now(),id);return run;
         });Run r=rawRun(accepted.id());return new AcceptedTurn(withAttachments(one("SELECT * FROM assistant_messages WHERE id=?",MESSAGE,r.inputMessageId())),r,branch(id,r.branchId()),accepted.replay());
      });
   }
   @Override public Branch reviseMessage(long owner,UUID id,UUID branchId,UUID messageId,long version,String requestKey,String body){bound(body,65536,"message");expire(owner);return tx(()->{
      ownerLock(owner);String hash=digest(id.toString(),branchId.toString(),messageId.toString(),Long.toString(version),body);checkKnownRequest(owner,"REVISE_MESSAGE",requestKey,hash);lock(owner,id,false);Replay result=request(owner,"REVISE_MESSAGE",requestKey,hash,id,()->{
         Branch old=branch(id,branchId);if(old.version()!=version)throw AssistantFailure.conflict("Branch version changed");
         if(jdbc.queryForObject("SELECT count(*) FROM assistant_runs WHERE session_id=? AND status='EXECUTING'",Integer.class,id)>0)throw AssistantFailure.conflict("Execution in progress");
         Message original=messages(id,branchId,false,10000).stream().filter(m->m.id().equals(messageId)&&m.role().equals("USER")).findFirst().orElseThrow(AssistantFailure::notFound);
         invalidateSummaries(id);jdbc.update("UPDATE assistant_runs SET status='CANCELLED',updated_at=? WHERE session_id=? AND status='QUEUED'",now(),id);
         UUID message=UUID.randomUUID(),newBranch=UUID.randomUUID();
         jdbc.update("INSERT INTO assistant_messages(id,session_id,parent_message_id,version_group_id,supersedes_id,role,body,state,created_at) VALUES(?,?,?,?,?,'USER',?,'ACCEPTED',?)",message,id,original.parentMessageId(),original.versionGroupId(),original.id(),body,now());
         jdbc.update("INSERT INTO assistant_branches(id,session_id,parent_branch_id,fork_message_id,head_message_id,created_at) VALUES(?,?,?,?,?,?)",newBranch,id,branchId,original.parentMessageId(),message,now());
         jdbc.update("INSERT INTO assistant_message_attachments SELECT session_id,?,attachment_id,position FROM assistant_message_attachments WHERE message_id=?",message,original.id());
         jdbc.update("UPDATE assistant_sessions SET active_branch_id=?,version=version+1,updated_at=? WHERE id=?",newBranch,now(),id);return newBranch;
      });return branch(id,result.id());
   });}
   @Override public Run enqueueSummary(long owner,UUID id,UUID branchId,String requestKey){expire(owner);return tx(()->{
      ownerLock(owner);String hash=digest(id.toString(),branchId.toString());checkKnownRequest(owner,"ENQUEUE_SUMMARY",requestKey,hash);lock(owner,id,false);Branch b=branch(id,branchId);if(b.headMessageId()==null)throw AssistantFailure.invalid("No messages to summarize");
      Replay result=request(owner,"ENQUEUE_SUMMARY",requestKey,hash,id,()->{
         var existing=jdbc.query("SELECT * FROM assistant_runs WHERE session_id=? AND branch_id=? AND kind='SUMMARY' AND context_version=? AND status IN ('QUEUED','EXECUTING','COMPLETED') ORDER BY created_at DESC LIMIT 1",RUN,id,branchId,b.version());if(!existing.isEmpty())return existing.get(0).id();
         invalidateSummaries(id);pendingCapacity(id);UUID run=UUID.randomUUID();jdbc.update("INSERT INTO assistant_runs(id,session_id,branch_id,input_message_id,kind,status,context_version,created_at,updated_at) VALUES(?,?,?,?,'SUMMARY','QUEUED',?,?,?)",run,id,branchId,b.headMessageId(),b.version(),now(),now());return run;
      });return rawRun(result.id());
   });}
   @Override public Optional<Run> claimNext(long owner,UUID id){return tx(()->{
      lock(owner,id,false);if(jdbc.queryForObject("SELECT count(*) FROM assistant_runs WHERE session_id=? AND status='EXECUTING'",Integer.class,id)>0)return Optional.empty();
      jdbc.update("UPDATE assistant_runs r SET status='CANCELLED',updated_at=? FROM assistant_branches b WHERE r.session_id=? AND r.branch_id=b.id AND r.kind='SUMMARY' AND r.status='QUEUED' AND r.context_version<>b.version",now(),id);
      var queued=jdbc.query("SELECT * FROM assistant_runs WHERE session_id=? AND status='QUEUED' ORDER BY CASE kind WHEN 'CHAT' THEN 0 WHEN 'RETRIEVAL' THEN 1 ELSE 2 END,created_at,id LIMIT 1 FOR UPDATE",RUN,id);
      if(queued.isEmpty())return Optional.empty();Run r=queued.get(0);jdbc.update("UPDATE assistant_runs SET status='EXECUTING',fence=fence+1,updated_at=? WHERE id=?",now(),r.id());return Optional.of(rawRun(r.id()));
   });}
   @Override public Run checkpoint(long owner,UUID id,long fence,long sequence,String text){bound(text,262144,"checkpoint");return tx(()->{
      Run r=rawRun(id);lock(owner,r.sessionId(),false);r=rawRun(id);
      if(!r.status().equals("EXECUTING")||r.fence()!=fence||r.sequence()!=sequence)throw AssistantFailure.conflict("Execution fence or sequence changed");
      jdbc.update("UPDATE assistant_runs SET checkpoint=?,sequence=sequence+1,updated_at=? WHERE id=?",text,now(),id);return rawRun(id);
   });}
   @Override public Run finish(long owner,UUID id,long fence,String state,String text){bound(text,262144,"result");if(!List.of("COMPLETED","FAILED","CANCELLED","INTERRUPTED").contains(state))throw AssistantFailure.invalid("Invalid terminal state");return tx(()->{
      Run r=rawRun(id);lock(owner,r.sessionId(),false);r=rawRun(id);if(!r.status().equals("EXECUTING")||r.fence()!=fence)throw AssistantFailure.conflict("Execution fence changed");
      if(r.kind().equals("CHAT")){
         Branch b=branch(r.sessionId(),r.branchId());if(!Objects.equals(b.headMessageId(),r.inputMessageId()))throw AssistantFailure.conflict("Active branch moved");
         UUID message=UUID.randomUUID();jdbc.update("INSERT INTO assistant_messages(id,session_id,parent_message_id,version_group_id,role,body,state,run_id,created_at) VALUES(?,?,?,?,'ASSISTANT',?,?,?,?)",message,r.sessionId(),r.inputMessageId(),message,text,state,id,now());
         jdbc.update("UPDATE assistant_branches SET head_message_id=?,version=version+1 WHERE id=?",message,r.branchId());
      }
      jdbc.update("UPDATE assistant_runs SET status=?,checkpoint=?,sequence=sequence+1,updated_at=? WHERE id=?",state,text,now(),id);
      jdbc.update("UPDATE assistant_sessions SET version=version+1,updated_at=? WHERE id=?",now(),r.sessionId());return rawRun(id);
   });}
   @Override public void interruptExecuting(long owner,UUID id){tx(()->{lock(owner,id,false);jdbc.update("UPDATE assistant_runs SET status='INTERRUPTED',fence=fence+1,updated_at=? WHERE session_id=? AND status='EXECUTING'",now(),id);return null;});}
   @Override public Attachment reserveAttachment(long owner,UUID session,String requestKey,String payloadHash,UUID attachmentId,String storageKey,String filename,String mimeType,long byteSize,String sha256,int width,int height){
      if(!attachmentId.toString().equals(storageKey)||!sha256.matches("[0-9a-f]{64}")||byteSize<1||byteSize>10485760||filename==null||filename.length()>180||filename.contains("/")||filename.contains("\\")||!List.of("image/jpeg","image/png").contains(mimeType))throw AssistantFailure.invalid("Invalid attachment metadata");
      expire(owner);return tx(()->{ownerLock(owner);String hash=digest(session.toString(),payloadHash,filename,mimeType,Long.toString(byteSize),sha256);checkKnownRequest(owner,"UPLOAD_ATTACHMENT",requestKey,hash);lock(owner,session,false);
         Replay result=request(owner,"UPLOAD_ATTACHMENT",requestKey,hash,session,()->{
            storagePolicy.requireUploads();
            long used=jdbc.queryForObject("SELECT coalesce(sum(a.byte_size),0) FROM assistant_attachments a JOIN assistant_sessions s ON s.id=a.session_id WHERE s.owner_id=? AND a.status<>'DELETED'",Long.class,owner);
            if(byteSize>storagePolicy.quotaBytes()-used)throw new AssistantFailure(413,"ASSISTANT_QUOTA_EXCEEDED","附件容量不足；可读取、导出或删除既有内容后再上传");
            jdbc.update("INSERT INTO assistant_attachments(id,session_id,storage_key,filename,mime_type,byte_size,sha256,width,height,status,created_at,updated_at,draft_expires_at) VALUES(?,?,?,?,?,?,?,?,?,'UPLOADING',?,?,?)",attachmentId,session,storageKey,filename,mimeType,byteSize,sha256,width,height,now(),now(),Timestamp.from(clock.instant().plus(storagePolicy.draftLifetime().compareTo(requestWindow)>0?requestWindow:storagePolicy.draftLifetime())));return attachmentId;
         });Attachment a=rawAttachment(result.id());if(result.replay()&&!a.status().equals("READY"))throw AssistantFailure.conflict("Attachment upload is incomplete or removed; this request cannot be restarted");return a;
      });
   }
   @Override public Attachment readyAttachment(long owner,UUID id,int width,int height){if(width<1||height<1)throw AssistantFailure.invalid("Invalid image dimensions");return tx(()->{ownerLock(owner);Attachment a=rawAttachment(id);lock(owner,a.sessionId(),false);a=rawAttachment(id);if(!a.status().equals("UPLOADING"))throw AssistantFailure.conflict("Attachment is not uploading");jdbc.update("UPDATE assistant_attachments SET status='READY',width=?,height=?,updated_at=? WHERE id=?",width,height,now(),id);return rawAttachment(id);});}
   @Override public Attachment attachment(long owner,UUID id){return withAttachment(owner,id,Function.identity());}
   @Override public AssistantPage<DraftAttachment> draftAttachmentPage(long owner,UUID session,int limit,String cursor){
      AssistantPage.limit(limit);String scope="attachment-drafts:"+session;var c=AssistantPage.cursor(cursor,scope);
      if(c!=null&&(c.at()==null||c.head()!=null))throw AssistantFailure.invalid("Invalid attachment draft cursor");
      return tx(()->{
         lock(owner,session,false);
         // The original accepted upload request is the draft authority. Never sweep READY orphan rows,
         // restore removed uploads, or infer a branch that was not part of the original upload contract.
         String sql="SELECT a.*,a.draft_expires_at AS draft_expires_at FROM assistant_attachments a JOIN assistant_requests r ON r.session_id=a.session_id AND r.result_id=a.id AND r.operation='UPLOAD_ATTACHMENT' AND r.owner_id=? AND r.status='ACTIVE' AND r.expires_at>? WHERE a.session_id=? AND a.status='READY' AND a.draft_expires_at>r.created_at AND a.draft_expires_at>? AND NOT EXISTS(SELECT 1 FROM assistant_message_attachments m WHERE m.session_id=a.session_id AND m.attachment_id=a.id)";
         RowMapper<DraftAttachment> mapper=(r,n)->new DraftAttachment(ATTACHMENT.mapRow(r,n),time(r,"draft_expires_at"));
         List<DraftAttachment> rows=c==null?jdbc.query(sql+" ORDER BY a.created_at,a.id LIMIT ?",mapper,owner,now(),session,now(),limit+1)
            :jdbc.query(sql+" AND (a.created_at,a.id)>(?,?) ORDER BY a.created_at,a.id LIMIT ?",mapper,owner,now(),session,now(),Timestamp.from(c.at()),c.id(),limit+1);
         return AssistantPage.of(rows,limit,row->AssistantPage.cursor(scope,row.attachment().createdAt(),row.attachment().id(),null));
      });
   }
   @Override public <T>T withAttachment(long owner,UUID id,Function<Attachment,T> action){return attachmentAction(owner,id,"READY",action);}
   @Override public <T>T withUploadingAttachment(long owner,UUID id,Function<Attachment,T> action){return attachmentAction(owner,id,"UPLOADING",action);}
   private <T>T attachmentAction(long owner,UUID id,String status,Function<Attachment,T> action){return tx(()->{ownerLock(owner);Attachment a=rawAttachment(id);lock(owner,a.sessionId(),false);a=rawAttachment(id);if(!a.status().equals(status))throw AssistantFailure.notFound();return action.apply(a);});}
   @Override public void removeAttachment(long owner,UUID id){tx(()->{ownerLock(owner);Attachment a=rawAttachment(id);lock(owner,a.sessionId(),false);a=rawAttachment(id);if(jdbc.queryForObject("SELECT count(*) FROM assistant_message_attachments WHERE attachment_id=?",Integer.class,id)>0)throw AssistantFailure.conflict("Attachment belongs to retained history");jdbc.update("UPDATE assistant_attachments SET status='DELETING',updated_at=? WHERE id=? AND status<>'DELETED'",now(),id);jdbc.update("UPDATE assistant_requests SET status='DELETED',result_id=NULL WHERE result_id=? AND operation='UPLOAD_ATTACHMENT'",id);return null;});}
   @Override public void failAttachment(long owner,UUID id){tx(()->{ownerLock(owner);Attachment a=rawAttachment(id);lock(owner,a.sessionId(),true);jdbc.update("UPDATE assistant_attachments SET status='FAILED',updated_at=? WHERE id=? AND status='UPLOADING'",now(),id);return null;});}
   @Override public List<Attachment> cleanupCandidates(Instant staleBefore,int limit){if(limit<1||limit>100)throw AssistantFailure.invalid("Invalid cleanup limit");return jdbc.query("SELECT * FROM assistant_attachments WHERE status IN ('FAILED','DELETING') OR (status='UPLOADING' AND updated_at<?) ORDER BY updated_at,id LIMIT ?",ATTACHMENT,Timestamp.from(staleBefore),limit);}
   @Override public <T>T cleanupAttachment(UUID id,Instant staleBefore,Function<Attachment,T> action){return tx(()->{
      Attachment a=rawAttachment(id);Long owner=jdbc.queryForObject("SELECT owner_id FROM assistant_sessions WHERE id=?",Long.class,a.sessionId());ownerLock(owner);lock(owner,a.sessionId(),true);a=rawAttachment(id);
      if(!List.of("FAILED","DELETING").contains(a.status())&&!(a.status().equals("UPLOADING")&&a.updatedAt().isBefore(staleBefore)))throw AssistantFailure.conflict("Attachment cleanup eligibility changed");
      if(jdbc.queryForObject("SELECT count(*) FROM assistant_message_attachments ma JOIN assistant_sessions s ON s.id=ma.session_id WHERE ma.attachment_id=? AND s.status='ACTIVE'",Integer.class,id)>0)throw AssistantFailure.conflict("Attachment belongs to retained history");
      T result=action.apply(a);jdbc.update("UPDATE assistant_attachments SET status='DELETED',filename='',updated_at=? WHERE id=?",now(),id);jdbc.update("UPDATE assistant_requests SET status='DELETED',result_id=NULL WHERE result_id=? AND operation='UPLOAD_ATTACHMENT'",id);return result;
   });}
   @Override public AssistantStoragePolicy storagePolicy(){return storagePolicy;}
   @Override public StorageAccounting storageAccounting(long owner){return tx(()->{
      ownerLock(owner);
      List<Attachment> tracked=jdbc.query("SELECT a.* FROM assistant_attachments a JOIN assistant_sessions s ON s.id=a.session_id WHERE s.owner_id=? AND a.status<>'DELETED' ORDER BY a.id",ATTACHMENT,owner);
      return new StorageAccounting(tracked.stream().filter(a->a.status().equals("READY")).mapToLong(Attachment::byteSize).sum(),tracked.stream().filter(a->a.status().equals("UPLOADING")).mapToLong(Attachment::byteSize).sum(),tracked.stream().filter(a->List.of("FAILED","DELETING").contains(a.status())).mapToLong(Attachment::byteSize).sum(),tracked);
   });}
   private void requireSendableAttachment(UUID id){
      if(jdbc.queryForObject("SELECT count(*) FROM assistant_attachments a WHERE a.id=? AND (a.draft_expires_at>? OR EXISTS(SELECT 1 FROM assistant_message_attachments m WHERE m.attachment_id=a.id))",Integer.class,id,now())!=1)
         throw new AssistantFailure(410,"ASSISTANT_DRAFT_EXPIRED","待发送图片已超过服务器草稿保留期，请重新选择图片");
   }
   @Override public List<Attachment> retentionCandidates(int limit){
      storagePolicy.requireCleanup();if(limit<1||limit>100)throw AssistantFailure.invalid("Invalid cleanup limit");
      return jdbc.query("SELECT a.* FROM assistant_attachments a JOIN assistant_sessions s ON s.id=a.session_id WHERE ((a.status='UPLOADING' AND a.updated_at<?) OR (a.status='FAILED' AND a.updated_at<?) OR (a.status='DELETING' AND a.updated_at<?) OR (a.status='READY' AND a.draft_expires_at<=?)) AND NOT EXISTS(SELECT 1 FROM assistant_message_attachments m WHERE m.attachment_id=a.id AND s.status='ACTIVE') ORDER BY a.updated_at,a.id LIMIT ?",ATTACHMENT,Timestamp.from(clock.instant().minus(storagePolicy.staleUploadAge())),Timestamp.from(clock.instant().minus(storagePolicy.failedGrace())),Timestamp.from(clock.instant().minus(storagePolicy.deletedGrace())),now(),limit);
   }
   @Override public boolean retentionAttachment(UUID id,boolean apply,Function<Attachment,Boolean> action){return tx(()->{
      storagePolicy.requireCleanup();Attachment a=rawAttachment(id);Long owner=jdbc.queryForObject("SELECT owner_id FROM assistant_sessions WHERE id=?",Long.class,a.sessionId());ownerLock(owner);Session session=lock(owner,a.sessionId(),true);a=rawAttachment(id);
      if(session.status().equals("ACTIVE")&&jdbc.queryForObject("SELECT count(*) FROM assistant_message_attachments WHERE attachment_id=?",Integer.class,id)>0)return false;
      boolean eligible=switch(a.status()){
         case "UPLOADING"->a.updatedAt().isBefore(clock.instant().minus(storagePolicy.staleUploadAge()));
         case "FAILED"->a.updatedAt().isBefore(clock.instant().minus(storagePolicy.failedGrace()));
         case "DELETING"->a.updatedAt().isBefore(clock.instant().minus(storagePolicy.deletedGrace()));
         case "READY"->{var expiry=jdbc.queryForObject("SELECT draft_expires_at FROM assistant_attachments WHERE id=?",Timestamp.class,id);yield expiry!=null&&!expiry.toInstant().isAfter(clock.instant());}
         default->false;
      };
      if(!eligible)return false;
      if(!apply)return action.apply(a);
      // Session/owner locks serialize sends/uploads/deletion. Request tombstones remain permanently non-reusable.
      if(!action.apply(a))return false;
      jdbc.update("UPDATE assistant_attachments SET status='DELETED',filename='',updated_at=? WHERE id=?",now(),id);
      jdbc.update("UPDATE assistant_requests SET status='DELETED',result_id=NULL WHERE operation='UPLOAD_ATTACHMENT' AND result_id=?",id);
      return true;
   });}

}
