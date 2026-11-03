package com.auralink.assistant;

/** Misconfiguration is isolated to Assistant execution, never the pre-existing application. */
public final class AssistantExecutionRuntime implements AutoCloseable {
 private final AssistantExecutionService service;private final AssistantAiClient client;private final AssistantWebSearchClient webSearch;private final String state;
 public AssistantExecutionRuntime(AssistantExecutionService service,AssistantAiClient client,String state){this(service,client,null,state);}
 public AssistantExecutionRuntime(AssistantExecutionService service,AssistantAiClient client,AssistantWebSearchClient webSearch,String state){this.service=service;this.client=client;this.webSearch=webSearch;this.state=state;}
 public boolean available(){return service!=null;}public String state(){return state;}
 public AssistantExecutionService require(){if(service==null)throw new AssistantFailure(503,state,"Assistant execution is unavailable");return service;}
 @Override public void close(){if(service!=null)service.close();if(client!=null)client.close();if(webSearch!=null)webSearch.close();}
}
