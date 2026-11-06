package com.auralink.assistant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import static com.auralink.assistant.AssistantRecords.*;

/** All owner IDs are resolved by Core authentication, never accepted as browser authorization. */
public interface AssistantStore {
   AssistantStoragePolicy storagePolicy();
   record StorageAccounting(long readyBytes,long reservedBytes,long cleanupReservedBytes,List<Attachment> tracked) {}
   StorageAccounting storageAccounting(long owner);
   List<Attachment> retentionCandidates(int limit);
   boolean retentionAttachment(UUID id,boolean apply,Function<Attachment,Boolean> action);
   Session createSession(long owner,String requestKey,String title);
   List<Session> sessions(long owner,int limit,int offset);
   AssistantPage<Session> sessionPage(long owner,int limit,String cursor);
   AssistantPage<Branch> branchPage(long owner,UUID sessionId,int limit,String cursor);
   AssistantPage<Message> historyPage(long owner,UUID sessionId,UUID branchId,boolean includeHistory,int limit,String cursor);
   Session session(long owner,UUID id);
   /** Authorization-only check for request replay: permits an owned tombstone, returns no content. */
   void requireOwnedSessionForRequest(long owner,UUID id);
   Session updateSession(long owner,UUID id,long expectedVersion,String title,UUID activeBranchId,Boolean summaryAutoEnabled);
   void deleteSession(long owner,UUID id);
   List<Branch> branches(long owner,UUID sessionId);
   Branch branch(long owner,UUID sessionId,UUID branchId);
   RequestResolution resolveRequest(long owner,String operation,String requestKey,UUID sessionId);
   RequestResolution closeUnconfirmedChatRequest(long owner,String operation,String requestKey,UUID sessionId);
   record RequestResolution(String operation,String status,UUID sessionId,String resultType,UUID resultId,
      String resultStatus,Instant expiresAt,Boolean attachmentLinked) {}
   List<Message> history(long owner,UUID sessionId,UUID branchId,boolean includeHistory,int limit);
   ExportSnapshot exportSnapshot(long owner,UUID sessionId,boolean includeHistory);
   <T>T withExportSnapshot(long owner,UUID sessionId,boolean includeHistory,Function<ExportSnapshot,T> action);
   Run run(long owner,UUID id);
   /** Internal persistence primitive only: no HTTP chat endpoint and no provider execution in this stage. */
   AcceptedTurn acceptTurn(long owner,UUID sessionId,UUID branchId,long expectedVersion,String requestKey,String body,List<UUID> attachments);
   Branch reviseMessage(long owner,UUID sessionId,UUID branchId,UUID messageId,long expectedVersion,String requestKey,String body);
   Run enqueueSummary(long owner,UUID sessionId,UUID branchId,String requestKey);
   Optional<Run> claimNext(long owner,UUID sessionId);
   Run checkpoint(long owner,UUID runId,long fence,long expectedSequence,String text);
   Run finish(long owner,UUID runId,long fence,String terminalState,String text);
   void interruptExecuting(long owner,UUID sessionId);
   Attachment reserveAttachment(long owner,UUID sessionId,String requestKey,String payloadHash,UUID attachmentId,String storageKey,String filename,String mimeType,long byteSize,String sha256,int width,int height);
   Attachment readyAttachment(long owner,UUID id,int width,int height);
   Attachment attachment(long owner,UUID id);
   /** Session-wide drafts only: acknowledged, unlinked uploads within their original request lifetime. */
   AssistantPage<DraftAttachment> draftAttachmentPage(long owner,UUID sessionId,int limit,String cursor);
   <T> T withAttachment(long owner,UUID id,Function<Attachment,T> action);
   <T> T withUploadingAttachment(long owner,UUID id,Function<Attachment,T> action);
   void removeAttachment(long owner,UUID id);
   void failAttachment(long owner,UUID id);
   List<Attachment> cleanupCandidates(Instant staleBefore,int limit);
   <T> T cleanupAttachment(UUID id,Instant staleBefore,Function<Attachment,T> action);
}
