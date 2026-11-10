package com.auralink.creation;

import com.auralink.api.v1.creation.CreationController;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.api.v1.error.ApiV1ExceptionHandler;
import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.entity.*;
import com.auralink.repository.*;
import com.auralink.service.CurrentUserService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual repository/service/HTTP contract, synthetic owner and records; no providers. */
class CreationHistoryDeletionTest {
   @TempDir Path temp;

   @Test void deletionIsOwnedPersistentIdempotentAndPreservesRelatedRecords() throws Exception {
      var config = new Configuration();
      for (var type : List.of(User.class, MediaAsset.class, Painting.class, UserWorkflow.class, Creation.class,
         CreationStep.class, CreationExecutionAttempt.class, CreationStepDispatchAttempt.class, CreationFavorite.class)) config.addAnnotatedClass(type);
      String postgres = System.getProperty("studio.history.jdbc");
      if (postgres != null) {
         assertThat(postgres).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/studio_history_test");
         config.setProperty("hibernate.connection.url", postgres).setProperty("hibernate.connection.username", System.getProperty("user.name"));
      } else {
         config.setProperty("hibernate.connection.url", "jdbc:sqlite:" + temp.resolve("history.db"))
            .setProperty("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
      }
      config.setProperty("hibernate.hbm2ddl.auto", "create-drop")
         .setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
      try (var factory = config.buildSessionFactory(); var em = factory.createEntityManager()) {
         em.getTransaction().begin();
         var owner = User.builder().username("owner").email("owner@example.invalid").password("synthetic").fullName("Synthetic user").build();
         var other = User.builder().username("other").email("other@example.invalid").password("synthetic").fullName("Synthetic user").build();
         em.persist(owner); em.persist(other);
         var media = MediaAsset.builder().ownerUser(owner).storageKey("synthetic/result.png").originalFilename("result.png")
            .assetType("IMAGE").semanticType("PAINTING").sourceType("GENERATED").visibility("PRIVATE").status("ACTIVE")
            .sha256("a".repeat(64)).fileSize(123L).mimeType("image/png").build();
         em.persist(media);
         var done = Creation.builder().user(owner).workflowSnapshot("{}").sourceModality("TEXT_DESCRIPTION")
            .sourceText("synthetic").finalAsset(media).status("SUCCEEDED").build();
         var queued = Creation.builder().user(owner).workflowSnapshot("{}").sourceModality("TEXT_DESCRIPTION").status("QUEUED").build();
         var running = Creation.builder().user(owner).workflowSnapshot("{}").sourceModality("TEXT_DESCRIPTION").status("RUNNING").claimToken("live-claim").build();
         var failed = Creation.builder().user(owner).workflowSnapshot("{}").sourceModality("TEXT_DESCRIPTION").status("FAILED").build();
         var retryFirst = Creation.builder().user(owner).workflowSnapshot("{}").sourceModality("TEXT_DESCRIPTION").status("FAILED").build();
         for (var c : List.of(done, queued, running, failed, retryFirst)) em.persist(c);
         em.persist(CreationFavorite.builder().user(other).creation(done).build());
         em.persist(CreationExecutionAttempt.builder().creation(done).attemptNumber(1).admittedAt(LocalDateTime.now()).build());
         em.persist(CreationStep.builder().creation(done).stepIndex(0).nodeId("step1").operationCode("TEXT_TO_PAINTING")
            .providerCode("synthetic").inputModality("TEXT_DESCRIPTION").outputModality("PAINTING").status("SUCCEEDED")
            .attemptCount(1).outputAsset(media).providerDispatchState("RESULT_PERSISTED").build());
         em.flush();
         String doneId = done.getPublicId(), queuedId = queued.getPublicId(), runningId = running.getPublicId();
         Long ownerId = owner.getId(), failedId = failed.getId(), retryFirstId = retryFirst.getId();
         String failedPublicId = failed.getPublicId(), retryFirstPublicId = retryFirst.getPublicId();
         var repositories = new JpaRepositoryFactory(em);
         var creations = repositories.getRepository(CreationRepository.class);
         var current = mock(CurrentUserService.class);
         var service = new CreationSubmissionService(null, new CreationExecutionProperties(), current, null, null, null,
            null, new DefaultListableBeanFactory().getBeanProvider(CreationCatalogInputSnapshotWriter.class), creations,
            null, null, null, Clock.systemUTC(), null);
         var http = MockMvcBuilders.standaloneSetup(new CreationController(service, mock(CreationRetryService.class)))
            .setControllerAdvice(new ApiV1ExceptionHandler()).build();
         when(current.requireCurrentUser()).thenReturn(other);
         http.perform(delete("/api/v1/me/creations/" + doneId)).andExpect(status().isNotFound());
         when(current.requireCurrentUser()).thenReturn(owner);
         http.perform(delete("/api/v1/me/creations/not-a-uuid")).andExpect(status().isNotFound());
         http.perform(delete("/api/v1/me/creations/" + queuedId)).andExpect(status().isConflict());
         http.perform(delete("/api/v1/me/creations/" + runningId)).andExpect(status().isConflict());
         http.perform(delete("/api/v1/me/creations/" + doneId)).andExpect(status().isNoContent());
         http.perform(delete("/api/v1/me/creations/" + doneId)).andExpect(status().isNoContent());
         service.deleteHistory(failedPublicId);
         assertThat(creations.retrySafely(failedId, ownerId, 0, LocalDateTime.now())).isZero();
         assertThat(creations.retrySafely(retryFirstId, ownerId, 0, LocalDateTime.now())).isEqualTo(1);
         http.perform(delete("/api/v1/me/creations/" + retryFirstPublicId)).andExpect(status().isConflict());
         em.getTransaction().commit(); em.clear();
         // Read in a fresh transaction, as a reload would: rows and related assets still exist.
         em.getTransaction().begin();
         assertThat(creations.findAllByUser_IdAndHistoryDeletedAtIsNull(ownerId, PageRequest.of(0, 6)).getTotalElements()).isEqualTo(3);
         assertThat(creations.findByPublicId(doneId).orElseThrow().getHistoryDeletedAt()).isNotNull();
         assertThat(creations.count()).isEqualTo(5);
         assertThat(em.createQuery("select count(f) from CreationFavorite f", Long.class).getSingleResult()).isEqualTo(1);
         assertThat(em.createQuery("select count(a) from CreationExecutionAttempt a", Long.class).getSingleResult()).isEqualTo(1);
         assertThat(em.createQuery("select count(s) from CreationStep s", Long.class).getSingleResult()).isEqualTo(1);
         assertThat(em.createQuery("select count(m) from MediaAsset m", Long.class).getSingleResult()).isEqualTo(1);
         assertThatThrownBy(() -> service.get(doneId)).isInstanceOfSatisfying(ApiV1Exception.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
         em.getTransaction().commit();
      }
   }
}
