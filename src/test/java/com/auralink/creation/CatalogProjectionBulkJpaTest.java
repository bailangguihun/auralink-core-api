package com.auralink.creation;

import com.auralink.api.v1.creation.CreationSourceRequest;
import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.entity.User;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.workflow.WorkflowModality;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import static org.assertj.core.api.Assertions.*;

/** Invoked only against the explicitly isolated, loopback-only disposable DB. */
@EnabledIfEnvironmentVariable(named="CATALOG_REPAIR_TEST_JDBC_URL",
   matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/catalog_projection_test")
class CatalogProjectionBulkJpaTest {
   @Test void everyRepairedCatalogIdentityPassesTheUnchangedConsumer() throws Exception {
      var config = new Configuration().addAnnotatedClass(Painting.class)
         .addAnnotatedClass(MediaAsset.class).addAnnotatedClass(User.class)
         .setProperty("hibernate.connection.url", System.getenv("CATALOG_REPAIR_TEST_JDBC_URL"))
         .setProperty("hibernate.connection.username", System.getProperty("user.name"))
         .setProperty("hibernate.hbm2ddl.auto", "none")
         .setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
      try (var factory = config.buildSessionFactory(); var em = factory.createEntityManager()) {
         var repositories = new JpaRepositoryFactory(em);
         var resolver = new CreationSourceResolver(repositories.getRepository(MediaAssetRepository.class),
            repositories.getRepository(PaintingRepository.class), new CreationProviderProperties());
         var ids = em.createNativeQuery("SELECT CAST(catalog_painting_id AS text), CAST(catalog_asset_id AS text) FROM catalog_creation_painting_projection ORDER BY catalog_painting_id").getResultList();
         assertThat(ids.size()).isEqualTo(Integer.parseInt(System.getenv("CATALOG_REPAIR_TEST_EXPECTED_COUNT")));
         for (var entry : ids) {
            var identity = (Object[]) entry;
            var request = new ObjectMapper().readValue("{\"modality\":\"PAINTING\",\"paintingId\":\"" + identity[0] + "\"}", CreationSourceRequest.class);
            var source = resolver.resolve(request, WorkflowModality.PAINTING, new User());
            assertThat(source.sourcePainting().getPublicId()).isEqualTo(identity[0]);
            assertThat(source.sourcePainting().getImageAsset().getPublicId()).isEqualTo(identity[1]);
            assertThat(source.sourcePainting().getImageAsset().getSourceType()).isEqualTo("CATALOG_REFERENCE");
         }
      }
   }
}
