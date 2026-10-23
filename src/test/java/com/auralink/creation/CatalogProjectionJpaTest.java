package com.auralink.creation;

import com.auralink.api.v1.creation.CreationSourceRequest;
import com.auralink.api.v1.error.ApiV1Exception;
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

/** Real PostgreSQL/JPA query and unchanged resolver. No app, scheduler or provider. */
@EnabledIfEnvironmentVariable(named="CATALOG_REPAIR_TEST_JDBC_URL",
   matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/catalog_projection_test")
class CatalogProjectionJpaTest {
   @Test void observedMissingProjectionAndRepairedProjectionUseActualJpaConsumer() throws Exception {
      var config = new Configuration().addAnnotatedClass(Painting.class)
         .addAnnotatedClass(MediaAsset.class).addAnnotatedClass(User.class)
         .setProperty("hibernate.connection.url", System.getenv("CATALOG_REPAIR_TEST_JDBC_URL"))
         .setProperty("hibernate.connection.username", System.getProperty("user.name"))
         .setProperty("hibernate.hbm2ddl.auto", "none")
         .setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
      try (var factory = config.buildSessionFactory(); var em = factory.createEntityManager()) {
         var repositories = new JpaRepositoryFactory(em);
         var paintings = repositories.getRepository(PaintingRepository.class);
         var media = repositories.getRepository(MediaAssetRepository.class);
         var resolver = new CreationSourceResolver(media, paintings, new CreationProviderProperties());
         var request = new ObjectMapper().readValue("{\"modality\":\"PAINTING\",\"paintingId\":\"23c2e7c7-3898-5ecd-9ca0-dc47fc7b2d39\"}", CreationSourceRequest.class);
         if ("missing".equals(System.getenv("CATALOG_REPAIR_TEST_EXPECTED"))) {
            assertThatThrownBy(() -> resolver.resolve(request, WorkflowModality.PAINTING, new User()))
               .isInstanceOfSatisfying(ApiV1Exception.class, e -> {
                  assertThat(e.getStatus().value()).isEqualTo(422);
                  assertThat(e.getCode().name()).isEqualTo("CREATION_SOURCE_INVALID");
                  assertThat(e.getMessage()).isEqualTo("创作源无效或不可访问");
               });
         } else {
            assertThat(System.getenv("CATALOG_REPAIR_TEST_EXPECTED")).isEqualTo("repaired");
            var source = resolver.resolve(request, WorkflowModality.PAINTING, new User());
            assertThat(source.sourcePainting().getPublicId()).isEqualTo(request.getPaintingId());
            assertThat(source.sourcePainting().getImageAsset().getPublicId()).isEqualTo("43dce8c3-bd8d-5837-893c-735b2d379987");
            assertThat(source.sourcePainting().getImageAsset().getSourceType()).isEqualTo("CATALOG_REFERENCE");
         }
      }
   }
}
