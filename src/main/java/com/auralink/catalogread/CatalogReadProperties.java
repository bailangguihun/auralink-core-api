package com.auralink.catalogread;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auralink.catalog-read")
public class CatalogReadProperties {
   private boolean enabled;
   private String thumbnailCacheDirectory;
   private CatalogReadProperties.Datasource datasource = new CatalogReadProperties.Datasource();
   private List<CatalogReadProperties.BatchMediaRoot> batchMediaRoots = new ArrayList<>();

   public boolean isEnabled() {
      return this.enabled;
   }

   public String getThumbnailCacheDirectory() {
      return this.thumbnailCacheDirectory;
   }

   public void setThumbnailCacheDirectory(String thumbnailCacheDirectory) {
      this.thumbnailCacheDirectory = thumbnailCacheDirectory;
   }

   public CatalogReadProperties.Datasource getDatasource() {
      return this.datasource;
   }

   public List<CatalogReadProperties.BatchMediaRoot> getBatchMediaRoots() {
      return this.batchMediaRoots;
   }

   public void setEnabled(final boolean enabled) {
      this.enabled = enabled;
   }

   public void setDatasource(final CatalogReadProperties.Datasource datasource) {
      this.datasource = datasource;
   }

   public void setBatchMediaRoots(final List<CatalogReadProperties.BatchMediaRoot> batchMediaRoots) {
      this.batchMediaRoots = batchMediaRoots;
   }

   public static class BatchMediaRoot {
      private String batchId;
      private int revision;
      private String root;

      public String getBatchId() {
         return this.batchId;
      }

      public int getRevision() {
         return this.revision;
      }

      public String getRoot() {
         return this.root;
      }

      public void setBatchId(final String batchId) {
         this.batchId = batchId;
      }

      public void setRevision(final int revision) {
         this.revision = revision;
      }

      public void setRoot(final String root) {
         this.root = root;
      }
   }

   public static class Datasource {
      private String jdbcUrl;
      private String username;
      private String password;
      private int maximumPoolSize = 2;
      private int minimumIdle = 0;
      private long connectionTimeoutMs = 2000L;

      public String getJdbcUrl() {
         return this.jdbcUrl;
      }

      public String getUsername() {
         return this.username;
      }

      public String getPassword() {
         return this.password;
      }

      public int getMaximumPoolSize() {
         return this.maximumPoolSize;
      }

      public int getMinimumIdle() {
         return this.minimumIdle;
      }

      public long getConnectionTimeoutMs() {
         return this.connectionTimeoutMs;
      }

      public void setJdbcUrl(final String jdbcUrl) {
         this.jdbcUrl = jdbcUrl;
      }

      public void setUsername(final String username) {
         this.username = username;
      }

      public void setPassword(final String password) {
         this.password = password;
      }

      public void setMaximumPoolSize(final int maximumPoolSize) {
         this.maximumPoolSize = maximumPoolSize;
      }

      public void setMinimumIdle(final int minimumIdle) {
         this.minimumIdle = minimumIdle;
      }

      public void setConnectionTimeoutMs(final long connectionTimeoutMs) {
         this.connectionTimeoutMs = connectionTimeoutMs;
      }
   }
}
