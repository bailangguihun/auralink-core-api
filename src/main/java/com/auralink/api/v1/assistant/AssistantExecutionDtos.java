package com.auralink.api.v1.assistant;

import com.auralink.assistant.AssistantExecutionRecords;
import com.auralink.assistant.AssistantExecutionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class AssistantExecutionDtos {
 private AssistantExecutionDtos(){}
 public record Chat(@NotNull UUID branchId,@NotNull @PositiveOrZero Long expectedVersion,
  @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,128}") String requestKey,@NotNull @Size(max=32000) String text,
  @Size(max=4) List<UUID> attachmentIds,@NotBlank @Size(max=100) String modelKey,Map<String,Boolean> retrieval,@Size(max=5) List<AssistantExecutionService.ArtworkRef> artworkRefs,Boolean voiceInput){
  public AssistantExecutionService.Command command(){return new AssistantExecutionService.Command(branchId,expectedVersion,requestKey,text,attachmentIds,modelKey,retrieval,artworkRefs,voiceInput);}
 }
 public record Retry(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,128}") String requestKey,@NotNull @PositiveOrZero Long expectedVersion,boolean acknowledgePossiblePriorCharge){}
 public record Summary(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,128}") String requestKey,@NotNull UUID branchId){}
 public record Revision(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,128}") String requestKey,@NotNull UUID branchId,@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Size(max=32000) String text){}
 public record Choice(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,128}") String requestKey,@NotBlank String cardId,@NotBlank String optionId,@NotNull UUID branchId,@NotNull @PositiveOrZero Long expectedVersion,@NotBlank String modelKey){public AssistantExecutionService.Command command(){return new AssistantExecutionService.Command(branchId,expectedVersion,requestKey,"selected choice",List.of(),modelKey,Map.of(),List.of());}}
 public record Classification(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,128}") String requestKey,@PositiveOrZero int expectedVersion,@NotBlank String label){}
 public record Run(UUID id,UUID sessionId,UUID branchId,String kind,String status,UUID inputMessageId,String modelKey,
  long sequence,String checkpoint,UUID retryOf,int attempt,boolean executionUncertain,Map<String,Object> retrieval,Map<String,Object> usage,String errorCode,Instant createdAt,Instant updatedAt){}
 public record Accepted(Run run,boolean replay){}
 public record Timeline(UUID messageId,String role,String state,String body,String classification,String classificationSource,String classifierVersion,int classificationVersion,List<Run> runs,Instant createdAt,String inputMode){}
 public static Run run(AssistantExecutionRecords.Run r){return new Run(r.id(),r.sessionId(),r.branchId(),r.kind(),r.status(),r.inputMessageId(),r.modelId(),r.sequence(),r.checkpoint(),r.retryOf(),r.attempt(),r.executionUncertain(),r.retrieval(),r.usage(),r.lastError(),r.createdAt(),r.updatedAt());}
 public static Accepted accepted(AssistantExecutionRecords.Accepted a){return new Accepted(run(a.run()),a.replay());}
 public static Timeline timeline(AssistantExecutionRecords.Timeline t){return new Timeline(t.messageId(),t.role(),t.state(),t.body(),t.classification(),t.classificationSource(),t.classifierVersion(),t.classificationVersion(),t.runs().stream().map(AssistantExecutionDtos::run).toList(),t.createdAt(),t.inputMode());}
}
