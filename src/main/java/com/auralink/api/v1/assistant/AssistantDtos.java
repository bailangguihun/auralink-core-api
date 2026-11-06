package com.auralink.api.v1.assistant;

import com.auralink.assistant.AssistantRecords;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public contracts: never serialize storage keys, owner IDs, database records or provider configuration. */
public final class AssistantDtos {
   private AssistantDtos() {}
   public record Status(boolean enabled, String stage, boolean executionAvailable,boolean generationAvailable,String generationState,boolean automaticSummaryAvailable,Integer summaryTurnThreshold,Long summaryIdleSeconds,Integer maxSummaryPerHour) {}
   public record ResolveRequest(@NotBlank @Size(max=40) String operation,@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,128}") String requestKey,UUID sessionId,boolean closeIfMissing) {}
   public record CreateSession(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,128}") String requestKey, @NotBlank @Size(max=160) String title) {}
   public record UpdateSession(@NotNull @PositiveOrZero Long expectedVersion, @Size(min=1,max=160) String title,
      UUID activeBranchId, Boolean summaryAutoEnabled) {}
   public record Session(UUID id,String title,UUID activeBranchId,long version,boolean summaryAutoEnabled,String status,Instant createdAt,Instant updatedAt) {}
   public record Branch(UUID id,UUID sessionId,UUID parentBranchId,UUID forkMessageId,UUID headMessageId,long version,Instant createdAt) {}
   public record Attachment(UUID id,UUID sessionId,String filename,String mimeType,long byteSize,String sha256,int width,int height,
      String status,String contentUrl,Instant createdAt,Instant updatedAt,Instant draftExpiresAt) {}
   public record Message(UUID id,UUID sessionId,UUID parentMessageId,UUID versionGroupId,UUID supersedesId,String role,
      String body,String state,UUID runId,Instant createdAt,List<UUID> attachmentIds) {}
   public record Run(UUID id,UUID sessionId,UUID branchId,String kind,String status,UUID inputMessageId,Instant createdAt,Instant updatedAt) {}
   public record History(UUID sessionId,UUID branchId,boolean includeHistory,List<Message> messages) {}
   public record Export(int schemaVersion,Instant exportedAt,boolean includeHistory,Session session,List<Branch> branches,
      List<Message> messages,List<Run> runs,List<Attachment> attachments) {}
   public static Session session(AssistantRecords.Session s) { return new Session(s.id(),s.title(),s.activeBranchId(),s.version(),s.summaryAutoEnabled(),s.status(),s.createdAt(),s.updatedAt()); }
   public static Branch branch(AssistantRecords.Branch b) { return new Branch(b.id(),b.sessionId(),b.parentBranchId(),b.forkMessageId(),b.headMessageId(),b.version(),b.createdAt()); }
   public static Message message(AssistantRecords.Message m) { return new Message(m.id(),m.sessionId(),m.parentMessageId(),m.versionGroupId(),m.supersedesId(),m.role(),m.body(),m.state(),m.runId(),m.createdAt(),m.attachmentIds()); }
   public static Run run(AssistantRecords.Run r) { return new Run(r.id(),r.sessionId(),r.branchId(),r.kind(),r.status(),r.inputMessageId(),r.createdAt(),r.updatedAt()); }
   public static Attachment attachment(AssistantRecords.Attachment a) { return new Attachment(a.id(),a.sessionId(),a.filename(),a.mimeType(),a.byteSize(),a.sha256(),a.width(),a.height(),a.status(),
      "/api/v1/assistant/attachments/"+a.id()+"/content",a.createdAt(),a.updatedAt(),null); }
   public static Attachment draftAttachment(AssistantRecords.DraftAttachment draft) { var a=draft.attachment();return new Attachment(a.id(),a.sessionId(),a.filename(),a.mimeType(),a.byteSize(),a.sha256(),a.width(),a.height(),a.status(),
      "/api/v1/assistant/attachments/"+a.id()+"/content",a.createdAt(),a.updatedAt(),draft.expiresAt()); }
   public static Export export(AssistantRecords.ExportSnapshot e,boolean history) { return new Export(1,Instant.now(),history,session(e.session()),
      e.branches().stream().map(AssistantDtos::branch).toList(),e.messages().stream().map(AssistantDtos::message).toList(),e.runs().stream().map(AssistantDtos::run).toList(),e.attachments().stream().map(AssistantDtos::attachment).toList()); }
}
