package com.auralink.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;

class AssistantExecutionConfigurationTest {
 private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(AssistantExecutionConfiguration.class).withBean(ObjectMapper.class,ObjectMapper::new);
 @Test void disabledNeedsNoTablesDirectoriesTokensOrAi(){runner.run(c->{assertNull(c.getStartupFailure());assertEquals("ASSISTANT_EXECUTION_DISABLED",c.getBean(AssistantExecutionRuntime.class).state());});}
 @Test void malformedExecutionSettingsDoNotFailExistingApplication(){runner.withPropertyValues("auralink.assistant.enabled=true","auralink.assistant.execution.enabled=true","auralink.assistant.execution.concurrency=not-a-number").run(c->{assertNull(c.getStartupFailure());assertEquals("ASSISTANT_EXECUTION_MISCONFIGURED",c.getBean(AssistantExecutionRuntime.class).state());});}
 @Test void missingExecutionDependenciesDoNotEnableExecution(){runner.withPropertyValues("auralink.assistant.enabled=true","auralink.assistant.execution.enabled=true").run(c->{assertNull(c.getStartupFailure());assertFalse(c.getBean(AssistantExecutionRuntime.class).available());});}
 @Test void historyOnlyDoesNotInitializeDispatcherEvenWhenExecutionFlagWasLeftTrue(){runner.withPropertyValues("auralink.assistant.enabled=true","auralink.assistant.execution.enabled=true","auralink.assistant.history-only=true").run(c->{assertNull(c.getStartupFailure());assertEquals("ASSISTANT_HISTORY_ONLY",c.getBean(AssistantExecutionRuntime.class).state());assertFalse(c.getBean(AssistantExecutionRuntime.class).available());});}
 @Test void malformedHistoryModeFailsAssistantClosedWithoutBreakingApplication(){runner.withPropertyValues("auralink.assistant.enabled=true","auralink.assistant.execution.enabled=true","auralink.assistant.history-only=perhaps").run(c->{assertNull(c.getStartupFailure());assertEquals("ASSISTANT_EXECUTION_MISCONFIGURED",c.getBean(AssistantExecutionRuntime.class).state());});}
 @Test void optionalWebNeedsBothAiCapabilityAndActualDatabaseConstraint(){
  var store=mock(AssistantExecutionStore.class);var ai=mock(AssistantAiClient.class);
  when(ai.models()).thenReturn(Map.of("contractVersion",1,"models",List.of()));
  assertFalse(AssistantExecutionConfiguration.webSearchReady(store,ai));
  verifyNoInteractions(store);
  when(ai.models()).thenReturn(Map.of("executionCapabilities",Map.of("citationTypes",List.of("PLATFORM_ARTWORK","RAGFLOW_DOCUMENT"))));
  assertFalse(AssistantExecutionConfiguration.webSearchReady(store,ai));verifyNoInteractions(store);
  when(ai.models()).thenReturn(Map.of("executionCapabilities",Map.of("citationTypes",List.of("PLATFORM_ARTWORK","RAGFLOW_DOCUMENT","WEB_RESULT"))));
  when(store.supportsWebCitations()).thenReturn(false);
  assertFalse(AssistantExecutionConfiguration.webSearchReady(store,ai));
  when(store.supportsWebCitations()).thenReturn(true);
  assertTrue(AssistantExecutionConfiguration.webSearchReady(store,ai));
  verify(ai,never()).retrieval(anyString(),anyBoolean());
 }
}
