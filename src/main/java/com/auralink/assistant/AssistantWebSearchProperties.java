package com.auralink.assistant;

import java.time.Duration;

/** Optional server-only Brave Search configuration. Disabled installations never read a key file. */
public final class AssistantWebSearchProperties {
 private boolean enabled;
 private String tokenFile="";
 private int resultCount=8;
 private Duration timeout=Duration.ofSeconds(8);
 public boolean isEnabled(){return enabled;} public void setEnabled(boolean value){enabled=value;}
 public String getTokenFile(){return tokenFile;} public void setTokenFile(String value){tokenFile=value;}
 public int getResultCount(){return resultCount;} public void setResultCount(int value){resultCount=value;}
 public Duration getTimeout(){return timeout;} public void setTimeout(Duration value){timeout=value;}
 public void validate(){
  if(!enabled)return;
  if(tokenFile==null||tokenFile.isBlank())throw new IllegalArgumentException("Assistant web search requires a token file");
  if(resultCount<1||resultCount>10||timeout==null||timeout.toMillis()<250||timeout.toMillis()>15000)throw new IllegalArgumentException("Assistant web search limits are invalid");
 }
}
