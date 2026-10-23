package com.auralink.creation;

import com.auralink.api.v1.creation.CreationSourceRequest;
import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.entity.User;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.workflow.WorkflowModality;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline observed-row-shape reproduction. No Spring application or provider is started. */
class CatalogProjectionSourceContractTest {
   private static final String ID = "23c2e7c7-3898-5ecd-9ca0-dc47fc7b2d39";
   private final PaintingRepository paintings = mock(PaintingRepository.class);
   private final MediaAssetRepository media = mock(MediaAssetRepository.class);
   private final CreationSourceResolver resolver = new CreationSourceResolver(media, paintings, new CreationProviderProperties());

   private CreationSourceRequest request(String fields) throws Exception {
      return new ObjectMapper().readValue("{\"modality\":\"PAINTING\",\"paintingId\":\"" + ID + "\"" + fields + "}", CreationSourceRequest.class);
   }
   private void rejected(CreationSourceRequest request) {
      assertThatThrownBy(() -> resolver.resolve(request, WorkflowModality.PAINTING, new User()))
         .isInstanceOfSatisfying(ApiV1Exception.class, error -> {
            assertThat(error.getStatus().value()).isEqualTo(422);
            assertThat(error.getCode()).isEqualTo(ApiErrorCode.CREATION_SOURCE_INVALID);
            assertThat(error.getMessage()).isEqualTo("创作源无效或不可访问");
         });
      verifyNoInteractions(media);
   }
   @Test void galleryUuidWithoutJpaProjectionReproducesExactResolverRejection() throws Exception {
      when(paintings.findByPublicIdAndStatus(ID, "ACTIVE")).thenReturn(Optional.empty());
      rejected(request(""));
      verify(paintings).findByPublicIdAndStatus(ID, "ACTIVE");
   }
   private Painting projected() {
      var asset = MediaAsset.builder().id(901L).publicId("43dce8c3-bd8d-5837-893c-735b2d379987")
         .status("ACTIVE").assetType("IMAGE").sourceType("CATALOG_REFERENCE").visibility("PUBLIC").build();
      var painting = Painting.builder().id(902L).publicId(ID).status("ACTIVE").imageAvailable(true).imageAsset(asset).build();
      when(paintings.findByPublicIdAndStatus(ID, "ACTIVE")).thenReturn(Optional.of(painting));
      return painting;
   }
   @Test void completeProjectionPassesUnchangedConsumerWithSamePublicIdentities() throws Exception {
      var painting = projected();
      var source = resolver.resolve(request(""), WorkflowModality.PAINTING, new User());
      assertThat(source.sourcePainting()).isSameAs(painting);
      assertThat(source.sourcePainting().getPublicId()).isEqualTo(ID);
      assertThat(source.sourceAsset()).isNull();
      verifyNoInteractions(media);
   }
   @ParameterizedTest @ValueSource(strings={"available", "link", "status", "type", "source", "visibility"})
   void incompleteOrIneligibleProjectionRemainsRejected(String field) throws Exception {
      var p = projected();
      switch (field) {
         case "available" -> p.setImageAvailable(false);
         case "link" -> p.setImageAsset(null);
         case "status" -> p.getImageAsset().setStatus("INACTIVE");
         case "type" -> p.getImageAsset().setAssetType("AUDIO");
         case "source" -> p.getImageAsset().setSourceType("UPLOAD");
         case "visibility" -> p.getImageAsset().setVisibility("PRIVATE");
      }
      rejected(request(""));
   }
   @Test void staleUploadFieldCannotBeSilentlyIgnored() throws Exception {
      rejected(request(",\"assetId\":\"43dce8c3-bd8d-5837-893c-735b2d379987\""));
      verifyNoInteractions(paintings);
   }
}
