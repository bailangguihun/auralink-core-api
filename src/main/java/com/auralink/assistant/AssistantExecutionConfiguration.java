package com.auralink.assistant;

import com.auralink.catalogread.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration(proxyBeanMethods=false)
public class AssistantExecutionConfiguration {
 @Bean(destroyMethod="close") AssistantExecutionRuntime assistantExecutionRuntime(Environment environment,
  ObjectProvider<AssistantStore> foundations,ObjectProvider<AssistantAttachmentService> attachments,
  @Qualifier("catalogAuthDataSource") ObjectProvider<DataSource> sources,
  @Qualifier("catalogReadJdbcTemplate") ObjectProvider<NamedParameterJdbcTemplate> reads,
  ObjectProvider<CatalogMediaService> media,ObjectMapper json){
  if(!"true".equalsIgnoreCase(environment.getProperty("auralink.assistant.enabled","false"))||!"true".equalsIgnoreCase(environment.getProperty("auralink.assistant.execution.enabled","false")))return new AssistantExecutionRuntime(null,null,"ASSISTANT_EXECUTION_DISABLED");
  String historyOnly=environment.getProperty("auralink.assistant.history-only","false");
  if(!historyOnly.equals("false"))return new AssistantExecutionRuntime(null,null,historyOnly.equals("true")?"ASSISTANT_HISTORY_ONLY":"ASSISTANT_EXECUTION_MISCONFIGURED");
  AssistantAiClient client=null;AssistantWebSearchClient webSearch=null;
  try{
   AssistantExecutionProperties p=Binder.get(environment).bind("auralink.assistant.execution",Bindable.of(AssistantExecutionProperties.class)).orElseThrow(()->new IllegalArgumentException("Missing Assistant execution settings"));p.validate();
   AssistantProperties base=Binder.get(environment).bind("auralink.assistant",Bindable.of(AssistantProperties.class)).orElseThrow(()->new IllegalArgumentException("Missing Assistant foundation settings"));
   var foundation=foundations.getIfAvailable();var source=sources.getIfAvailable();var attachment=attachments.getIfAvailable();if(foundation==null||source==null||attachment==null)throw new IllegalArgumentException("Missing Assistant storage");
   var store=new AssistantExecutionStore(source,foundation,Clock.systemUTC(),base.getRequestReplayWindow(),base.getMaxPendingPerSession(),p.getMaxEvents(),p.getConcurrency(),p.getOwnerConcurrency(),p.getGlobalPending(),p.getOwnerPending());store.verifySchema();
   client=new AssistantAiClient(p,json);
   AssistantWebSearchProperties webProperties=Binder.get(environment).bind("auralink.assistant.web-search",Bindable.of(AssistantWebSearchProperties.class)).orElseGet(AssistantWebSearchProperties::new);
   if(webProperties.isEnabled())try{webSearch=new AssistantWebSearchClient(webProperties,json);}catch(RuntimeException unavailable){webSearch=null;/* Optional search misconfiguration must not erase model/history availability. */}
   var jdbc=reads.getIfAvailable();var service=new AssistantExecutionService(store,foundation,attachment,client,jdbc==null?null:new AssistantArtworkRead(jdbc),media.getIfAvailable(),p,json,Clock.systemUTC(),webSearch,new AssistantGenerationControl(java.nio.file.Path.of(base.getAttachmentRoot()))::state);service.start();return new AssistantExecutionRuntime(service,client,webSearch,"READY");
  }catch(RuntimeException misconfigured){if(client!=null)client.close();if(webSearch!=null)webSearch.close();return new AssistantExecutionRuntime(null,null,"ASSISTANT_EXECUTION_MISCONFIGURED");}
 }
}
