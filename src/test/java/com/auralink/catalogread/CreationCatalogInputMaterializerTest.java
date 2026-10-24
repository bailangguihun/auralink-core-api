package com.auralink.catalogread;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.creation.CreationExecutionTransactionService.ClaimedCreationData;
import com.auralink.creation.CreationExecutionTransactionService.StepData;
import com.auralink.creation.CreationInputMaterializer;
import com.auralink.creation.provider.ProviderImageInput;
import com.auralink.entity.CreationStep;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.entity.User;
import com.auralink.exception.InvalidStoragePathException;
import com.auralink.provider.artifact.AudioOutputValidator;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.service.media.ImageContentValidator;
import com.auralink.service.media.MediaAssetStorageResolver;
import com.auralink.service.media.MediaAssetStorageService;
import com.auralink.workflow.WorkflowModality;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real catalog/storage/image validation; only repository rows are synthetic.
 * No HTTP client, worker, scheduler, model, or generation bean is started.
 * JPA proxy/transaction behavior is deliberately outside this test's scope.
 */
class CreationCatalogInputMaterializerTest {
   private static final String PAINTING_ID = "00000000-0000-4000-8000-000000000101";
   private static final String ASSET_ID = "00000000-0000-4000-8000-000000000102";
   private static final String BATCH_ID = "00000000-0000-4000-8000-000000000103";
   @TempDir Path temporary;
   private Path legacyRoot, managedRoot, batchRoot, stagingRoot;
   private final PaintingRepository paintings = mock(PaintingRepository.class);
   private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
   private final CreationStepRepository steps = mock(CreationStepRepository.class);
   private final CatalogReadStore catalog = mock(CatalogReadStore.class);

   @BeforeEach void roots() throws Exception {
      legacyRoot = Files.createDirectories(temporary.resolve("legacy"));
      managedRoot = Files.createDirectories(temporary.resolve("managed"));
      batchRoot = Files.createDirectories(temporary.resolve("catalog-batch"));
      Files.createDirectories(batchRoot.resolve("images/original"));
      stagingRoot = temporary.resolve("provider-staging");
   }

   @ParameterizedTest @ValueSource(strings = {"jpeg", "png"})
   void projectedCatalogImageUsesAuthoritativeBatchWhenLegacyPathDoesNotExist(String format) throws Exception {
      byte[] bytes = image(format);
      MediaAsset asset = officialAsset(format);
      Painting painting = officialPainting(asset);
      when(paintings.findById(101L)).thenReturn(Optional.of(painting));
      catalogImage(format, bytes, sha(bytes));

      try (var context = context(true)) {
         assertThatThrownBy(() -> context.getBean(MediaAssetStorageService.class).resolve(asset))
            .isInstanceOf(InvalidStoragePathException.class)
            .hasMessage("MediaAsset resource is unavailable")
            .hasRootCauseInstanceOf(NoSuchFileException.class);
         var catalogContent = context.getBean(CatalogMediaService.class).find(ASSET_ID).orElseThrow();
         assertThat(catalogContent.resource().getContentAsByteArray()).isEqualTo(bytes);
         clearInvocations(catalog);

         ProviderArtifact artifact;
         try (var input = context.getBean(CreationInputMaterializer.class).materialize(source("PAINTING", null, 101L), step(0, "PAINTING"))) {
            var image = (ProviderImageInput) input.input();
            artifact = image.artifact();
            assertThat(image.modality()).isEqualTo(WorkflowModality.PAINTING);
            assertThat(image.paintingMetadata().paintingId()).isEqualTo(PAINTING_ID);
            assertThat(image.paintingMetadata().title()).isEqualTo("合成画作");
            assertArtifact(artifact, bytes, "image/" + format);
         }
         assertThat(artifact.isAvailable()).isFalse();
         verify(catalog).findMedia(ASSET_ID);
         assertThat(painting.getPublicId()).isEqualTo(PAINTING_ID);
         assertThat(asset.getPublicId()).isEqualTo(ASSET_ID);
         assertThat(Files.readAllBytes(batchRoot.resolve("images/original/synthetic." + format))).isEqualTo(bytes);
      }
   }

   @Test void catalogHashFailureDoesNotFallBackToReadableLegacyImage() throws Exception {
      byte[] bytes = image("jpeg");
      MediaAsset asset = officialAsset("jpeg");
      when(paintings.findById(101L)).thenReturn(Optional.of(officialPainting(asset)));
      legacyImage(asset, bytes);
      catalogImage("jpeg", bytes, "0".repeat(64));
      try (var context = context(true)) {
         assertThatThrownBy(() -> context.getBean(CreationInputMaterializer.class)
            .materialize(source("PAINTING", null, 101L), step(0, "PAINTING")))
            .isInstanceOf(IllegalArgumentException.class).hasCauseInstanceOf(com.auralink.api.v1.error.ApiV1Exception.class);
         verify(catalog).findMedia(ASSET_ID);
         assertNoStagedFiles();
      }
   }

   @Test void missingCatalogRecordDoesNotFallBackToReadableLegacyImage() throws Exception {
      MediaAsset asset = officialAsset("png");
      when(paintings.findById(101L)).thenReturn(Optional.of(officialPainting(asset)));
      legacyImage(asset, image("png"));
      when(catalog.findMedia(ASSET_ID)).thenReturn(Optional.empty());
      try (var context = context(true)) {
         assertThatThrownBy(() -> context.getBean(CreationInputMaterializer.class)
            .materialize(source("PAINTING", null, 101L), step(0, "PAINTING")))
            .isInstanceOf(IllegalArgumentException.class);
         verify(catalog).findMedia(ASSET_ID);
         assertNoStagedFiles();
      }
   }

   @ParameterizedTest @ValueSource(strings = {"hash", "size", "mime"})
   void catalogProjectionDriftIsRejectedWithoutLegacyFallback(String field) throws Exception {
      byte[] bytes = image("jpeg");
      MediaAsset asset = officialAsset("jpeg");
      switch (field) {
         case "hash" -> asset.setSha256("0".repeat(64));
         case "size" -> asset.setFileSize((long) bytes.length + 1);
         case "mime" -> asset.setMimeType("image/png");
         default -> throw new AssertionError("Unexpected synthetic field");
      }
      when(paintings.findById(101L)).thenReturn(Optional.of(officialPainting(asset)));
      legacyImage(asset, bytes);
      catalogImage("jpeg", bytes, sha(bytes));
      try (var context = context(true)) {
         assertThatThrownBy(() -> context.getBean(CreationInputMaterializer.class)
            .materialize(source("PAINTING", null, 101L), step(0, "PAINTING")))
            .isInstanceOf(IllegalArgumentException.class);
         verify(catalog).findMedia(ASSET_ID);
         assertNoStagedFiles();
      }
   }

   @Test void catalogTraversalIsRejectedEvenWhenLegacyImageIsReadable() throws Exception {
      byte[] bytes = image("png");
      MediaAsset asset = officialAsset("png");
      when(paintings.findById(101L)).thenReturn(Optional.of(officialPainting(asset)));
      legacyImage(asset, bytes);
      Files.write(batchRoot.resolve("outside.png"), bytes);
      when(catalog.findMedia(ASSET_ID)).thenReturn(Optional.of(new CatalogReadStore.CatalogMediaAsset(
         ASSET_ID, BATCH_ID, 1, "../../outside.png", "unused:synthetic", sha(bytes), "image/png")));
      try (var context = context(true)) {
         assertThatThrownBy(() -> context.getBean(CreationInputMaterializer.class)
            .materialize(source("PAINTING", null, 101L), step(0, "PAINTING")))
            .isInstanceOf(IllegalArgumentException.class).hasCauseInstanceOf(com.auralink.api.v1.error.ApiV1Exception.class);
         verify(catalog).findMedia(ASSET_ID);
         assertNoStagedFiles();
      }
   }

   @Test void installationWithoutCatalogServiceRetainsLegacyCatalogStorage() throws Exception {
      byte[] bytes = image("jpeg");
      MediaAsset asset = officialAsset("jpeg");
      when(paintings.findById(101L)).thenReturn(Optional.of(officialPainting(asset)));
      legacyImage(asset, bytes);
      try (var context = context(false);
           var input = context.getBean(CreationInputMaterializer.class).materialize(source("PAINTING", null, 101L), step(0, "PAINTING"))) {
         assertArtifact(((ProviderImageInput) input.input()).artifact(), bytes, "image/jpeg");
         verifyNoInteractions(catalog);
      }
      assertNoStagedFiles();
   }

   @Test void catalogIntermediateDirectorySymlinkCannotEscapeImageRoot() throws Exception {
      byte[] bytes = image("png");
      MediaAsset asset = officialAsset("png");
      when(paintings.findById(101L)).thenReturn(Optional.of(officialPainting(asset)));
      Path outside = Files.createDirectories(temporary.resolve("outside-intermediate"));
      Files.write(outside.resolve("synthetic.png"), bytes);
      Files.createSymbolicLink(batchRoot.resolve("images/original/linked"), outside);
      when(catalog.findMedia(ASSET_ID)).thenReturn(Optional.of(new CatalogReadStore.CatalogMediaAsset(
         ASSET_ID, BATCH_ID, 1, "linked/synthetic.png", "unused:synthetic", sha(bytes), "image/png")));
      legacyImage(asset, bytes);

      try (var context = context(true)) {
         assertThatThrownBy(() -> {
            try (var ignored = context.getBean(CreationInputMaterializer.class)
               .materialize(source("PAINTING", null, 101L), step(0, "PAINTING"))) {
               // Close a mistakenly accepted input so the failing regression leaves no staged artifact.
            }
         }).isInstanceOf(IllegalArgumentException.class)
            .hasCauseInstanceOf(com.auralink.api.v1.error.ApiV1Exception.class);
         verify(catalog).findMedia(ASSET_ID);
         assertNoStagedFiles();
         assertThat(Files.readAllBytes(outside.resolve("synthetic.png"))).isEqualTo(bytes);
      }
   }

   @Test void catalogImageRootSymlinkCannotRedirectOutsideConfiguredBatch() throws Exception {
      byte[] bytes = image("png");
      MediaAsset asset = officialAsset("png");
      when(paintings.findById(101L)).thenReturn(Optional.of(officialPainting(asset)));
      Path outside = Files.createDirectories(temporary.resolve("outside-image-root"));
      Files.write(outside.resolve("synthetic.png"), bytes);
      Path imageRoot = batchRoot.resolve("images/original");
      Files.delete(imageRoot);
      Files.createSymbolicLink(imageRoot, outside);
      when(catalog.findMedia(ASSET_ID)).thenReturn(Optional.of(new CatalogReadStore.CatalogMediaAsset(
         ASSET_ID, BATCH_ID, 1, "synthetic.png", "unused:synthetic", sha(bytes), "image/png")));
      legacyImage(asset, bytes);

      try (var context = context(true)) {
         assertThatThrownBy(() -> {
            try (var ignored = context.getBean(CreationInputMaterializer.class)
               .materialize(source("PAINTING", null, 101L), step(0, "PAINTING"))) {
               // Close a mistakenly accepted input so the failing regression leaves no staged artifact.
            }
         }).isInstanceOf(IllegalArgumentException.class)
            .hasCauseInstanceOf(com.auralink.api.v1.error.ApiV1Exception.class);
         verify(catalog).findMedia(ASSET_ID);
         assertNoStagedFiles();
         assertThat(Files.readAllBytes(outside.resolve("synthetic.png"))).isEqualTo(bytes);
      }
   }

   @Test void catalogNestedRegularImageRemainsReadableWithinConfiguredRoot() throws Exception {
      byte[] bytes = image("png");
      MediaAsset asset = officialAsset("png");
      when(paintings.findById(101L)).thenReturn(Optional.of(officialPainting(asset)));
      Path nested = Files.createDirectories(batchRoot.resolve("images/original/nested/collection"));
      Files.write(nested.resolve("synthetic.png"), bytes);
      when(catalog.findMedia(ASSET_ID)).thenReturn(Optional.of(new CatalogReadStore.CatalogMediaAsset(
         ASSET_ID, BATCH_ID, 1, "nested/collection/synthetic.png", "unused:synthetic", sha(bytes), "image/png")));

      try (var context = context(true);
           var input = context.getBean(CreationInputMaterializer.class)
              .materialize(source("PAINTING", null, 101L), step(0, "PAINTING"))) {
         assertArtifact(((ProviderImageInput) input.input()).artifact(), bytes, "image/png");
         verify(catalog).findMedia(ASSET_ID);
      }
      assertNoStagedFiles();
   }

   @Test void ownedUploadUsesManagedStorageWithoutCatalogLookup() throws Exception {
      byte[] bytes = image("png");
      MediaAsset asset = privateAsset("USER_UPLOAD", "IMAGE");
      Files.write(managedRoot.resolve("private.png"), bytes);
      when(assets.findById(102L)).thenReturn(Optional.of(asset));
      try (var context = context(true);
           var input = context.getBean(CreationInputMaterializer.class).materialize(source("IMAGE", 102L, null), step(0, "IMAGE"))) {
         var image = (ProviderImageInput) input.input();
         assertThat(image.modality()).isEqualTo(WorkflowModality.IMAGE);
         assertThat(image.paintingMetadata()).isNull();
         assertArtifact(image.artifact(), bytes, "image/png");
         verifyNoInteractions(catalog);
      }
      assertNoStagedFiles();
   }

   @Test void priorGeneratedPaintingUsesManagedStorageWithoutCatalogLookup() throws Exception {
      byte[] bytes = image("png");
      MediaAsset asset = privateAsset("GENERATED", "GENERATED_PAINTING");
      Files.write(managedRoot.resolve("private.png"), bytes);
      when(steps.findByCreationIdAndStepIndex(1L, 0)).thenReturn(Optional.of(CreationStep.builder()
         .status("SUCCEEDED").outputModality("PAINTING").outputAsset(asset).build()));
      try (var context = context(true);
           var input = context.getBean(CreationInputMaterializer.class).materialize(source("TEXT_DESCRIPTION", null, null), step(1, "PAINTING"))) {
         var image = (ProviderImageInput) input.input();
         assertThat(image.modality()).isEqualTo(WorkflowModality.PAINTING);
         assertThat(image.paintingMetadata()).isNull();
         assertArtifact(image.artifact(), bytes, "image/png");
         verifyNoInteractions(catalog);
      }
      assertNoStagedFiles();
   }

   @Test void ownedUploadedPaintingIsValidatedAndStagedWithoutCatalogIdentity() throws Exception {
      var asset = uploadedPainting();
      try (var context = context(true);
           var input = context.getBean(CreationInputMaterializer.class).materialize(source("PAINTING", 102L, null), step(0, "PAINTING"))) {
         var image = (ProviderImageInput) input.input();
         assertArtifact(image.artifact(), image("png"), "image/png");
         assertThat(image.paintingMetadata()).isNull();
         verifyNoInteractions(catalog, paintings);
      }
      assertThat(Files.readAllBytes(managedRoot.resolve("private.png"))).isEqualTo(image("png"));
      assertNoStagedFiles();
   }

   @ParameterizedTest @ValueSource(strings={"owner", "status", "visibility", "type", "semantic", "generated", "hash", "size", "width", "mime", "invalid", "symlink"})
   void invalidOwnedUploadIsRejectedBeforeAnyProviderCall(String fault) throws Exception {
      var asset = uploadedPainting();
      switch (fault) {
         case "owner" -> asset.setOwnerUser(User.builder().id(8L).build());
         case "status" -> asset.setStatus("DELETED");
         case "visibility" -> asset.setVisibility("PUBLIC");
         case "type" -> asset.setAssetType("AUDIO");
         case "semantic" -> asset.setSemanticType("GENERATED_PAINTING");
         case "generated" -> asset.setSourceType("GENERATED");
         case "hash" -> asset.setSha256("0".repeat(64));
         case "size" -> asset.setFileSize(1L);
         case "width" -> asset.setWidth(999);
         case "mime" -> asset.setMimeType("image/jpeg");
         case "invalid" -> Files.writeString(managedRoot.resolve("private.png"), "not an image");
         case "symlink" -> { Files.delete(managedRoot.resolve("private.png")); Files.createSymbolicLink(managedRoot.resolve("private.png"), Path.of("/etc/passwd")); }
      }
      try (var context = context(true)) {
         assertThatThrownBy(() -> context.getBean(CreationInputMaterializer.class).materialize(source("PAINTING", 102L, null), step(0, "PAINTING")))
            .isInstanceOf(RuntimeException.class);
      }
      verifyNoInteractions(catalog, paintings);
      assertNoStagedFiles();
   }

   private MediaAsset uploadedPainting() throws Exception {
      var asset = privateAsset("USER_UPLOAD", "PAINTING");
      byte[] bytes = image("png");
      asset.setSha256(sha(bytes)); asset.setFileSize((long)bytes.length); asset.setWidth(3); asset.setHeight(2);
      Files.write(managedRoot.resolve("private.png"), bytes);
      when(assets.findById(102L)).thenReturn(Optional.of(asset));
      return asset;
   }

   private AnnotationConfigApplicationContext context(boolean withCatalog) {
      var media = new MediaAssetProperties(); media.setManagedDir(managedRoot.toString());
      var painting = new PaintingProperties(); painting.setPictureDir(legacyRoot.toString());
      var provider = new CreationProviderProperties(); provider.setStagingDir(stagingRoot);
      var catalogProperties = new CatalogReadProperties();
      var batch = new CatalogReadProperties.BatchMediaRoot();
      batch.setBatchId(BATCH_ID); batch.setRevision(1); batch.setRoot(batchRoot.toString());
      catalogProperties.setBatchMediaRoots(List.of(batch));
      var context = new AnnotationConfigApplicationContext();
      context.registerBean(MediaAssetProperties.class, () -> media);
      context.registerBean(PaintingProperties.class, () -> painting);
      context.registerBean(CreationProviderProperties.class, () -> provider);
      context.registerBean(MediaAssetRepository.class, () -> assets);
      context.registerBean(PaintingRepository.class, () -> paintings);
      context.registerBean(CreationStepRepository.class, () -> steps);
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      if (withCatalog) context.registerBean(CatalogMediaService.class, () -> new CatalogMediaService(catalog, catalogProperties));
      context.register(MediaAssetStorageResolver.class, MediaAssetStorageService.class, ImageContentValidator.class,
         AudioOutputValidator.class, ProviderArtifactStagingService.class, PaintingPoemResultValidator.class, CreationInputMaterializer.class);
      context.refresh();
      return context;
   }

   private static MediaAsset officialAsset(String format) throws Exception {
      byte[] bytes = image(format);
      return MediaAsset.builder().id(102L).publicId(ASSET_ID).storageKey("catalog/" + ASSET_ID + "/synthetic." + format)
         .mimeType("image/" + format).sourceType("CATALOG_REFERENCE").assetType("IMAGE").semanticType("IMAGE")
         .fileSize((long) bytes.length).sha256(sha(bytes)).visibility("PUBLIC").status("ACTIVE").build();
   }

   private static Painting officialPainting(MediaAsset asset) {
      return Painting.builder().id(101L).publicId(PAINTING_ID).status("ACTIVE").imageAvailable(true)
         .imageAsset(asset).title("合成画作").authorName("合成作者").build();
   }

   private static MediaAsset privateAsset(String source, String semantic) {
      User owner = new User(); owner.setId(7L);
      return MediaAsset.builder().id(102L).publicId(ASSET_ID).ownerUser(owner).storageKey("managed/private.png")
         .mimeType("image/png").sourceType(source).assetType("IMAGE").semanticType(semantic).visibility("PRIVATE").status("ACTIVE").build();
   }

   private void catalogImage(String format, byte[] bytes, String expectedHash) throws Exception {
      Files.write(batchRoot.resolve("images/original/synthetic." + format), bytes);
      when(catalog.findMedia(ASSET_ID)).thenReturn(Optional.of(new CatalogReadStore.CatalogMediaAsset(
         ASSET_ID, BATCH_ID, 1, "synthetic." + format, "unused:synthetic", expectedHash, "image/" + format)));
   }

   private void legacyImage(MediaAsset asset, byte[] bytes) throws Exception {
      Path path = legacyRoot.resolve(asset.getStorageKey().substring("catalog/".length()));
      Files.createDirectories(path.getParent()); Files.write(path, bytes);
   }

   private static ClaimedCreationData source(String modality, Long asset, Long painting) {
      return new ClaimedCreationData(1L, "synthetic-claim", 7L, "{}", modality, "合成描述", asset, painting, 0);
   }

   private static StepData step(int index, String modality) {
      return new StepData(11L, index, "step" + (index + 1), index == 0 && "PAINTING".equals(modality) ? "PAINTING_TO_MUSIC" : "PAINTING_TO_POEM", "auralink-vmm", modality, "AUDIO", "RUNNING", 1, "NOT_SENT");
   }

   private static byte[] image(String format) throws Exception {
      var output = new ByteArrayOutputStream();
      assertThat(ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), format, output)).isTrue();
      return output.toByteArray();
   }

   private static String sha(byte[] bytes) throws Exception {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   }

   private static void assertArtifact(ProviderArtifact artifact, byte[] bytes, String mime) throws Exception {
      assertThat(artifact.isAvailable()).isTrue();
      assertThat(artifact.mimeType()).isEqualTo(mime);
      assertThat(artifact.sha256()).isEqualTo(sha(bytes));
      try (var stream = artifact.openStream()) { assertThat(stream.readAllBytes()).isEqualTo(bytes); }
   }

   private void assertNoStagedFiles() throws Exception {
      if (Files.exists(stagingRoot)) try (var files = Files.list(stagingRoot)) { assertThat(files.count()).isZero(); }
   }
}
