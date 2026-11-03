package com.auralink.assistant;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssistantExecutionContractTest {
 private AssistantRecords.Message message(String role,String state){return new AssistantRecords.Message(UUID.randomUUID(),UUID.randomUUID(),null,UUID.randomUUID(),null,role,"fixture",state,null,Instant.EPOCH,List.of());}
 @Test void acceptedUserHistoryRemainsInContextAndFailedAnswerDoesNot(){
  assertTrue(AssistantExecutionService.eligibleContextMessage(message("USER","ACCEPTED"),null));
  assertTrue(AssistantExecutionService.eligibleContextMessage(message("USER","COMPLETED"),null));
  assertTrue(AssistantExecutionService.eligibleContextMessage(message("ASSISTANT","COMPLETED"),null));
  for(String state:List.of("FAILED","CANCELLED","INTERRUPTED"))assertFalse(AssistantExecutionService.eligibleContextMessage(message("ASSISTANT",state),null));
 }
 @Test void choicesKeepOnlyStrictSupportedShape(){
  var optionA=Map.of("id","a","label","构图");var optionB=Map.of("id","b","label","笔墨");
  assertEquals(1,AssistantExecutionService.parseChoices(Map.of("cards",List.of(Map.of("id","stage","title","阶段","options",List.of(optionA,optionB))))).size());
  assertTrue(AssistantExecutionService.parseChoices(Map.of("cards",List.of(Map.of("id","stage","title","阶段","options",List.of(optionA,optionA))))).isEmpty());
 }
}
