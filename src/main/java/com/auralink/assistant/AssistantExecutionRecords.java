package com.auralink.assistant;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Internal persistence records. Public HTTP projection must not expose worker/fence details. */
public final class AssistantExecutionRecords {
 private AssistantExecutionRecords() {}
 public record Admission(UUID sessionId,UUID branchId,long expectedVersion,String requestKey,String text,List<UUID> attachmentIds,String modelId,Map<String,Object> options) {}
 public record Run(UUID id,UUID sessionId,UUID branchId,String kind,String status,UUID inputMessageId,long fence,long sequence,String checkpoint,String modelId,Map<String,Object> options,String leaseOwner,Instant leaseUntil,Instant dispatchedAt,Instant retrievalDispatchedAt,UUID retryOf,int attempt,Map<String,Object> usage,Map<String,Object> retrieval,Map<String,Object> contextManifest,String lastError,Instant createdAt,Instant updatedAt) {
  public boolean terminal(){return List.of("COMPLETED","FAILED","CANCELLED","INTERRUPTED").contains(status);}
  public boolean executionUncertain(){return "INTERRUPTED".equals(status)&&(dispatchedAt!=null||retrievalDispatchedAt!=null);}
 }
 public record Accepted(Run run,boolean replay) {}
 public record Pending(long owner,UUID sessionId) {}
 public record AutoSummary(long owner,UUID sessionId,UUID branchId,UUID headMessageId,String modelId) {}
 public record Event(long sequence,String type,Map<String,Object> payload,Instant createdAt) {}
 public record EventPage(Run run,boolean snapshotRequired,List<Event> events) {}
 public record Citation(UUID id,int ordinal,String sourceType,String sourceId,String sourceVersion,String projectionVersion,Map<String,Object> provenance,String excerpt,String excerptSha256) {}
 public record ChoiceOption(String id,String label,String text) {}
 public record ChoiceCard(String id,String title,List<ChoiceOption> options) {}
 public record Choice(UUID runId,UUID sourceMessageId,String id,String title,List<ChoiceOption> options) {}
 public record Timeline(UUID messageId,String role,String state,String body,String classification,String classificationSource,String classifierVersion,int classificationVersion,List<Run> runs,Instant createdAt,String inputMode) {}
}
