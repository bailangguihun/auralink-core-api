package com.auralink.creation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.auralink.api.v1.creation.*;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.*;
import com.auralink.creation.provider.*;
import com.auralink.entity.*;
import com.auralink.provider.vmm.VmmManagedAdmission;
import com.auralink.repository.*;
import com.auralink.service.CurrentUserService;
import com.auralink.workflow.*;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.service.WorkflowExecutionPreparer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/** Actual ORM, source resolver, capability gate and submission persistence; no provider calls. */
class OwnedUploadMusicSourceTest {
   @TempDir Path temp;
   @Test void ownedUploadPersistsRealAssetAndCrossUserAndChainsRemainRejected() throws Exception {
      var config = new Configuration();
      for (var type : List.of(User.class, MediaAsset.class, Painting.class, UserWorkflow.class, Creation.class,
                             CreationStep.class, CreationExecutionAttempt.class, CreationStepDispatchAttempt.class)) config.addAnnotatedClass(type);
      String postgres = System.getProperty("owned.upload.jdbc");
      if (postgres != null) {
         assertThat(postgres).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/owned_upload_test");
         config.setProperty("hibernate.connection.url", postgres).setProperty("hibernate.connection.username", System.getProperty("user.name"));
      } else {
         config.setProperty("hibernate.connection.url", "jdbc:sqlite:" + temp.resolve("isolated.db"))
            .setProperty("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
      }
      config
         .setProperty("hibernate.hbm2ddl.auto", "create-drop")
         .setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
      try (var factory = config.buildSessionFactory(); var em = factory.createEntityManager()) {
         em.getTransaction().begin();
         var owner = User.builder().username("upload-test").email("test@example.invalid").password("synthetic").fullName("Synthetic").build();
         var other = User.builder().username("other-test").email("other@example.invalid").password("synthetic").fullName("Other").build();
         em.persist(owner); em.persist(other);
         var asset = MediaAsset.builder().ownerUser(owner).storageKey("managed/test.png").originalFilename("test.png")
            .assetType("IMAGE").semanticType("PAINTING").sourceType("USER_UPLOAD").visibility("PRIVATE").status("ACTIVE")
            .sha256("a".repeat(64)).fileSize(123L).mimeType("image/png").width(2).height(2).build();
         var wf = UserWorkflow.builder().user(owner).name("Upload music").graphJson("{}").schemaVersion(2).status("ACTIVE").build();
         em.persist(asset); em.persist(wf); em.flush();
         var repositories = new JpaRepositoryFactory(em);
         var assets = repositories.getRepository(MediaAssetRepository.class);
         var paintings = repositories.getRepository(PaintingRepository.class);
         var creations = repositories.getRepository(CreationRepository.class);
         var steps = repositories.getRepository(CreationStepRepository.class);
         var attempts = repositories.getRepository(CreationExecutionAttemptRepository.class);
         var resolver = new CreationSourceResolver(assets, paintings, new CreationProviderProperties());
         var mapper = new ObjectMapper();
         var request = mapper.readValue("{\"modality\":\"PAINTING\",\"assetId\":\"" + asset.getPublicId() + "\"}", CreationSourceRequest.class);
         var resolved = resolver.resolve(request, WorkflowModality.PAINTING, owner);
         assertThat(resolved.sourcePainting()).isNull(); assertThat(resolved.sourceAsset()).isSameAs(asset);
         assertThatThrownBy(() -> resolver.resolve(request, WorkflowModality.PAINTING, other)).isInstanceOf(ApiV1Exception.class);
         for (String extra : List.of(",\"paintingId\":null", ",\"sourceKind\":\"CATALOG_PAINTING\"")) {
            var forged = mapper.readValue("{\"modality\":\"PAINTING\",\"assetId\":\"" + asset.getPublicId() + "\"" + extra + "}", CreationSourceRequest.class);
            assertThatThrownBy(() -> resolver.resolve(forged, WorkflowModality.PAINTING, owner)).isInstanceOf(ApiV1Exception.class);
         }
         var registry = new WorkflowCapabilityRegistry();
         var adapter = mock(CreationProviderAdapter.class);
         when(adapter.bindings()).thenReturn(List.of(new ProviderAdapterBinding(WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm", WorkflowModality.PAINTING, WorkflowModality.AUDIO)));
         when(adapter.readiness()).thenReturn(new ProviderReadiness(ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION, "SYNTHETIC"));
         var adapters = mock(ProviderAdapterRegistry.class);
         when(adapters.find(WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm")).thenReturn(java.util.Optional.of(adapter));
         when(adapters.readiness(WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm")).thenReturn(new ProviderReadiness(ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION, "SYNTHETIC"));
         var capability = new CreationExecutionCapabilityService(registry, adapters);
         var transform = new WorkflowExecutionPreparer.PreparedTransform("step1", WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm", WorkflowModality.PAINTING, WorkflowModality.AUDIO, "{\"durationSeconds\":3}");
         var prepared = new WorkflowExecutionPreparer.PreparedWorkflow(wf, null, "{}", WorkflowModality.PAINTING, WorkflowModality.AUDIO, List.of(transform));
         capability.requireExecutionAvailable(prepared, resolved);
         asset.setSourceType("GENERATED");
         assertThatThrownBy(() -> capability.requireExecutionAvailable(prepared, resolved)).isInstanceOf(ApiV1Exception.class);
         asset.setSourceType("USER_UPLOAD");
         assertThatThrownBy(() -> capability.requireExecutionAvailable(List.of(WorkflowOperation.TEXT_TO_PAINTING, WorkflowOperation.PAINTING_TO_MUSIC), "TEXT_DESCRIPTION", null, asset.getId())).isInstanceOf(ApiV1Exception.class);
         var current = mock(CurrentUserService.class); when(current.requireCurrentUser()).thenReturn(owner);when(current.findCurrentUser()).thenReturn(java.util.Optional.of(owner));
         var preparer = mock(WorkflowExecutionPreparer.class); when(preparer.prepare(wf.getPublicId(), owner)).thenReturn(prepared);
         var beans = new DefaultListableBeanFactory();
         var service = new CreationSubmissionService(mock(CreationFeatureGuard.class), new CreationExecutionProperties(), current, preparer,
            capability, resolver, new CreationStateMachine(), beans.getBeanProvider(CreationCatalogInputSnapshotWriter.class), creations, attempts, steps,
            mock(CreationResponseMapper.class), Clock.systemUTC(), mock(VmmManagedAdmission.class));
         var submission = mapper.readValue("{\"workflowId\":\"" + wf.getPublicId() + "\",\"source\":{\"modality\":\"PAINTING\",\"assetId\":\"" + asset.getPublicId() + "\"}}", CreationSubmissionRequest.class);
         String creationId = service.submit(submission).creationId();
         Long ownerId=owner.getId(), otherId=other.getId(); String assetId=asset.getPublicId();
         em.getTransaction().commit(); em.clear(); em.getTransaction().begin();
         var saved = creations.findByPublicIdAndUserId(creationId, ownerId).orElseThrow();
         assertThat(saved.getSourcePainting()).isNull(); assertThat(saved.getSourceAsset().getPublicId()).isEqualTo(assetId);
         assertThat(saved.getSourceAsset().getVisibility()).isEqualTo("PRIVATE"); assertThat(saved.getSourceModality()).isEqualTo("PAINTING");
         assertThat(creations.findByPublicIdAndUserId(creationId, otherId)).isEmpty();
         assertThat(steps.findByCreationIdOrderByStepIndexAsc(saved.getId())).hasSize(1);
         assertThat(creations.count()).isEqualTo(1); assertThat(paintings.count()).isZero();
         verify(adapter, never()).execute(any());
         // Real unchanged result persistence must also work with a null catalog source.
         saved.setStatus("RUNNING"); saved.setClaimToken("isolated-claim");
         var musicStep=steps.findByCreationIdOrderByStepIndexAsc(saved.getId()).get(0);
         musicStep.setStatus("RUNNING");musicStep.setProviderDispatchState("SEND_STARTED");musicStep.setProviderRequestKey("isolated-dispatch");
         var attempt=attempts.findByCreationIdAndFinishedAtIsNull(saved.getId()).orElseThrow();
         var dispatch=CreationStepDispatchAttempt.builder().creationStep(musicStep).creationExecutionAttempt(attempt)
            .providerRequestKey("isolated-dispatch").dispatchState("SEND_STARTED").build();
         em.persist(dispatch);em.flush();
         Long cid=saved.getId(),sid=musicStep.getId(),inputId=saved.getSourceAsset().getId();
         var mediaProperties=new MediaAssetProperties();mediaProperties.setManagedDir(temp.resolve("managed").toString());
         var storage=new com.auralink.service.media.MediaAssetStorageService(mediaProperties,
            new com.auralink.service.media.MediaAssetStorageResolver(mediaProperties,new PaintingProperties()));
         var images=new com.auralink.service.media.ImageContentValidator(mediaProperties);
         var mediaService=new com.auralink.service.media.MediaAssetService(assets,repositories.getRepository(UserRepository.class),current,
            new com.auralink.security.access.MediaAssetAccessPolicy(),storage,images,mediaProperties);
         var audio=new com.auralink.provider.artifact.AudioOutputValidator();
         var providerProperties=new CreationProviderProperties();providerProperties.setStagingDir(temp.resolve("staging"));
         var staging=new com.auralink.provider.artifact.ProviderArtifactStagingService(providerProperties,images,audio);
         var persistence=new CreationResultPersistenceService(creations,steps,attempts,repositories.getRepository(CreationStepDispatchAttemptRepository.class),
            mediaService,audio,mock(com.auralink.provider.qwen.PaintingPoemResultValidator.class),mapper,new CreationExecutionProperties(),Clock.systemUTC(),boundary -> {});
         byte[] wave=wave();
         try(var artifact=staging.stageOutputWave(new java.io.ByteArrayInputStream(wave))) {
            persistence.persistMusic(new CreationExecutionTransactionService.ClaimedCreationData(cid,"isolated-claim",ownerId,"{}","PAINTING",null,inputId,null,0),
               new CreationExecutionTransactionService.StepData(sid,0,"step1","PAINTING_TO_MUSIC","auralink-vmm","PAINTING","AUDIO","RUNNING",1,"SEND_STARTED"),
               new ProviderBinaryOutput(artifact),3,true);
         }
         em.getTransaction().commit();em.clear();em.getTransaction().begin();
         var complete=creations.findByPublicIdAndUserId(creationId,ownerId).orElseThrow();
         assertThat(complete.getStatus()).isEqualTo("SUCCEEDED");assertThat(complete.getSourceAsset().getPublicId()).isEqualTo(assetId);
         assertThat(complete.getSourcePainting()).isNull();assertThat(complete.getFinalAsset().getOwnerUser().getId()).isEqualTo(ownerId);
         assertThat(complete.getFinalAsset().getVisibility()).isEqualTo("PRIVATE");assertThat(complete.getFinalAsset().getDurationSeconds()).isEqualTo(3.0);
         String resultId=complete.getFinalAsset().getPublicId();
         assertThat(mediaService.getAccessibleContent(resultId).resource().getContentAsByteArray()).isEqualTo(wave);
         when(current.requireCurrentUser()).thenReturn(other);
         when(current.findCurrentUser()).thenReturn(java.util.Optional.of(other));
         assertThatThrownBy(() -> mediaService.getAccessibleContent(resultId)).isInstanceOf(ApiV1Exception.class);
         assertThat(creations.count()).isEqualTo(1);assertThat(assets.count()).isEqualTo(2);assertThat(paintings.count()).isZero();
         em.getTransaction().commit();
      }
   }
   private static byte[] wave() {
      int size=3*8000*2;var b=java.nio.ByteBuffer.allocate(44+size).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      b.put("RIFF".getBytes()).putInt(36+size).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1);
      b.putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(size);return b.array();
   }
}
