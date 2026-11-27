package com.auralink.catalogread;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
@ConditionalOnProperty(prefix = "auralink.catalog-read", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CatalogReadProperties.class)
public class CatalogReadConfiguration {
   @Bean(name = "catalogReadDataSource", destroyMethod = "close")
   HikariDataSource catalogReadDataSource(CatalogReadProperties properties) {
      CatalogReadProperties.Datasource source = properties.getDatasource();
      if (source.getJdbcUrl() != null && source.getJdbcUrl().startsWith("jdbc:postgresql://")) {
         HikariConfig config = new HikariConfig();
         config.setPoolName("artlive-catalog-read-postgres");
         config.setJdbcUrl(source.getJdbcUrl());
         config.setUsername(source.getUsername());
         config.setPassword(source.getPassword());
         config.setDriverClassName("org.postgresql.Driver");
         config.setMaximumPoolSize(source.getMaximumPoolSize());
         config.setMinimumIdle(source.getMinimumIdle());
         config.setConnectionTimeout(source.getConnectionTimeoutMs());
         config.setAutoCommit(true);
         config.setReadOnly(true);
         return new HikariDataSource(config);
      } else {
         throw new IllegalArgumentException("catalog-read datasource must use an explicit PostgreSQL JDBC URL");
      }
   }

   @Bean
   NamedParameterJdbcTemplate catalogReadJdbcTemplate(@Qualifier("catalogReadDataSource") DataSource dataSource) {
      return new NamedParameterJdbcTemplate(dataSource);
   }

   @Bean
   CatalogReadStore catalogReadStore(NamedParameterJdbcTemplate catalogReadJdbcTemplate) {
      return new PostgresCatalogReadStore(catalogReadJdbcTemplate);
   }

   @Bean
   CatalogMediaService catalogMediaService(CatalogReadStore catalogReadStore, CatalogReadProperties properties, Environment environment) {
      if (properties.getThumbnailCacheDirectory() == null
          && environment.acceptsProfiles(Profiles.of("catalog-postgres-production & !catalog-postgres-cutover-readonly"))) {
         // The managed launcher supplies a private, persistent Java home.
         properties.setThumbnailCacheDirectory(Path.of(System.getProperty("user.home"), "catalog-thumbnail-cache").toString());
      }
      return new CatalogMediaService(catalogReadStore, properties);
   }
}
