package com.auralink.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in only. The legacy VMM configuration keeps its previous behavior. */
@ConfigurationProperties(prefix = "auralink.vmm-managed")
public class VmmManagedProperties {
   private boolean enabled;
   private String tokenFile = "";
   private Long acceptanceOwnerId;
   private Long acceptanceSourcePaintingId;
   private Integer acceptanceDurationSeconds;

   public boolean isEnabled() { return enabled; }
   public void setEnabled(boolean value) { enabled = value; }
   public String getTokenFile() { return tokenFile; }
   public void setTokenFile(String value) { tokenFile = value; }
   public Long getAcceptanceOwnerId() { return acceptanceOwnerId; }
   public void setAcceptanceOwnerId(Long value) { acceptanceOwnerId = value; }
   public Long getAcceptanceSourcePaintingId() { return acceptanceSourcePaintingId; }
   public void setAcceptanceSourcePaintingId(Long value) { acceptanceSourcePaintingId = value; }
   public Integer getAcceptanceDurationSeconds() { return acceptanceDurationSeconds; }
   public void setAcceptanceDurationSeconds(Integer value) { acceptanceDurationSeconds = value; }
}
