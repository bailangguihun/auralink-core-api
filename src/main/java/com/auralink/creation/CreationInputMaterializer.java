package com.auralink.creation;

import com.auralink.catalogread.CatalogMediaService;
import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.PaintingMetadataContext;
import com.auralink.creation.provider.ProviderImageInput;
import com.auralink.creation.provider.ProviderInput;
import com.auralink.creation.provider.ProviderTextInput;
import com.auralink.entity.CreationStep;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.provider.qwen.PaintingPoemResult;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.service.media.MediaAssetStorageService;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreationInputMaterializer {
   private final MediaAssetRepository mediaAssets;
   private final PaintingRepository paintings;
   private final CreationStepRepository steps;
   private final MediaAssetStorageService mediaStorage;
   private final ProviderArtifactStagingService artifactStaging;
   private final PaintingPoemResultValidator poemValidator;
   private final CreationProviderProperties providerProperties;
   private final ObjectProvider<CatalogMediaService> catalogMedia;

   @Transactional(readOnly = true)
   public CreationInputMaterializer.MaterializedInput materialize(
      CreationExecutionTransactionService.ClaimedCreationData creation, CreationExecutionTransactionService.StepData step
   ) {
      WorkflowModality expected = this.parseModality(step.inputModality());
      boolean music = WorkflowOperation.PAINTING_TO_MUSIC.name().equals(step.operationCode());
      if (music && step.stepIndex() != 0) throw invalidInput();
      return step.stepIndex() == 0 ? this.sourceInput(creation, expected, music) : this.intermediateInput(creation, step, expected);
   }

   private CreationInputMaterializer.MaterializedInput sourceInput(CreationExecutionTransactionService.ClaimedCreationData creation, WorkflowModality expected, boolean music) {
      WorkflowModality source = this.parseModality(creation.sourceModality());
      if (source != expected) {
         throw invalidInput();
      }

      return switch (source) {
         case TEXT_DESCRIPTION, POEM -> new CreationInputMaterializer.MaterializedInput(
            new ProviderTextInput(this.requireSafeText(creation.sourceText()), source), null
         );
         case IMAGE -> this.imageInput(this.requireOwnedActiveImageSource(creation.sourceAssetId(), creation.ownerId()), WorkflowModality.IMAGE, null);
         case PAINTING -> this.paintingSourceInput(creation, music);
         default -> throw invalidInput();
      };
   }

   private CreationInputMaterializer.MaterializedInput intermediateInput(
      CreationExecutionTransactionService.ClaimedCreationData creation, CreationExecutionTransactionService.StepData step, WorkflowModality expected
   ) {
      CreationStep previous = this.steps
         .findByCreationIdAndStepIndex(creation.creationId(), step.stepIndex() - 1)
         .orElseThrow(CreationInputMaterializer::invalidInput);
      if (!CreationStepStatus.SUCCEEDED.name().equals(previous.getStatus())) {
         throw invalidInput();
      } else {
         WorkflowModality previousOutput = this.parseModality(previous.getOutputModality());
         if (previousOutput != expected) {
            throw invalidInput();
         } else if (expected == WorkflowModality.PAINTING) {
            MediaAsset asset = previous.getOutputAsset();
            this.requireGeneratedPainting(asset, creation.ownerId());
            return this.imageInput(asset, WorkflowModality.PAINTING, null);
         } else if (expected == WorkflowModality.POEM) {
            PaintingPoemResult poem = this.poemValidator.validate(previous.getOutputJson());
            return new CreationInputMaterializer.MaterializedInput(new ProviderTextInput(poem.text(), WorkflowModality.POEM), null);
         } else {
            throw invalidInput();
         }
      }
   }

   private CreationInputMaterializer.MaterializedInput paintingInput(Painting painting) {
      MediaAsset image = painting.getImageAsset();
      this.requireOfficialCatalogImage(image);
      return this.imageInput(
         image,
         WorkflowModality.PAINTING,
         new PaintingMetadataContext(
            painting.getPublicId(),
            painting.getTitle(),
            painting.getAuthorName(),
            painting.getCreationDynastyNormalized(),
            painting.getCategory(),
            painting.getSubject(),
            painting.getPaintingSchool(),
            painting.getStyle(),
            painting.getComposition(),
            painting.getArtisticConception(),
            painting.getGeneratedText(),
            painting.getMusicSceneDescription()
         )
      );
   }

   private CreationInputMaterializer.MaterializedInput paintingSourceInput(CreationExecutionTransactionService.ClaimedCreationData creation, boolean music) {
      if (creation.sourcePaintingId() != null && creation.sourceAssetId() == null) {
         return this.paintingInput(this.requireOfficialPainting(creation.sourcePaintingId()));
      } else if (creation.sourcePaintingId() == null && creation.sourceAssetId() != null) {
         MediaAsset asset = this.requireOwnedActivePainting(creation.sourceAssetId(), creation.ownerId());
         if (music && !"USER_UPLOAD".equals(asset.getSourceType())) throw invalidInput();
         return this.imageInput(asset, WorkflowModality.PAINTING, null, music);
      } else {
         throw invalidInput();
      }
   }

   private CreationInputMaterializer.MaterializedInput imageInput(MediaAsset asset, WorkflowModality modality, PaintingMetadataContext metadata) {
      return this.imageInput(asset, modality, metadata, false);
   }

   private CreationInputMaterializer.MaterializedInput imageInput(MediaAsset asset, WorkflowModality modality, PaintingMetadataContext metadata, boolean verifyUpload) {
      ProviderArtifact artifact = null;

      try (InputStream input = this.openImageInput(asset)) {
         artifact = this.artifactStaging.stageInputImage(input, asset.getMimeType());
         if (verifyUpload && (asset.getSha256() == null || !asset.getSha256().equals(artifact.sha256())
            || asset.getFileSize() == null || asset.getFileSize() != artifact.byteLength()
            || !java.util.Objects.equals(asset.getWidth(), artifact.width())
            || !java.util.Objects.equals(asset.getHeight(), artifact.height()))) throw invalidInput();
         return new CreationInputMaterializer.MaterializedInput(new ProviderImageInput(artifact, modality, metadata), artifact);
      } catch (IOException | RuntimeException exception) {
         this.closeQuietly(artifact);
         // Keep the internal cause for diagnosis; the worker still returns the
         // same safe CREATION_INPUT_INVALID response, without paths or content.
         throw new IllegalArgumentException("Creation input is unavailable or invalid", exception);
      }
   }

   private InputStream openImageInput(MediaAsset asset) throws IOException {
      if ("CATALOG_REFERENCE".equals(asset.getSourceType())) {
         CatalogMediaService catalog = this.catalogMedia.getIfAvailable();
         if (catalog != null) {
            // PostgreSQL catalog projections have logical keys, not files under
            // the legacy picture root. Reuse the API's batch/revision resolver.
            // An absent or invalid catalog object must not fall back to an old file.
            CatalogMediaService.CatalogMediaContent content = catalog.find(asset.getPublicId())
               .orElseThrow(CreationInputMaterializer::invalidInput);
            if (!asset.getPublicId().equals(content.asset().publicId())
               || asset.getSha256() == null || !asset.getSha256().equalsIgnoreCase(content.asset().contentSha256())
               || asset.getFileSize() == null || asset.getFileSize() != content.size()
               || asset.getMimeType() == null || !asset.getMimeType().equalsIgnoreCase(content.mimeType())) {
               throw invalidInput();
            }
            return content.resource().getInputStream();
         }
      }
      return this.mediaStorage.resolve(asset).resource().getInputStream();
   }

   private MediaAsset requireOwnedActiveImage(Long assetId, Long ownerId) {
      if (assetId != null && ownerId != null) {
         MediaAsset asset = (MediaAsset)this.mediaAssets.findById(assetId).orElseThrow(CreationInputMaterializer::invalidInput);
         if (asset.getOwnerUser() != null
            && ownerId.equals(asset.getOwnerUser().getId())
            && "ACTIVE".equals(asset.getStatus())
            && "IMAGE".equals(asset.getAssetType())
            && "PRIVATE".equals(asset.getVisibility())) {
            return asset;
         } else {
            throw invalidInput();
         }
      } else {
         throw invalidInput();
      }
   }

   private MediaAsset requireOwnedActiveImageSource(Long assetId, Long ownerId) {
      MediaAsset asset = this.requireOwnedActiveImage(assetId, ownerId);
      if (!"IMAGE".equals(asset.getSemanticType())) {
         throw invalidInput();
      } else {
         return asset;
      }
   }

   private MediaAsset requireOwnedActivePainting(Long assetId, Long ownerId) {
      MediaAsset asset = this.requireOwnedActiveImage(assetId, ownerId);
      if (!"PAINTING".equals(asset.getSemanticType())) {
         throw invalidInput();
      } else {
         return asset;
      }
   }

   private Painting requireOfficialPainting(Long paintingId) {
      if (paintingId == null) {
         throw invalidInput();
      } else {
         Painting painting = (Painting)this.paintings.findById(paintingId).orElseThrow(CreationInputMaterializer::invalidInput);
         if ("ACTIVE".equals(painting.getStatus()) && painting.isImageAvailable()) {
            return painting;
         } else {
            throw invalidInput();
         }
      }
   }

   private void requireOfficialCatalogImage(MediaAsset asset) {
      if (asset == null
         || asset.getOwnerUser() != null
         || !"ACTIVE".equals(asset.getStatus())
         || !"IMAGE".equals(asset.getAssetType())
         || !"PUBLIC".equals(asset.getVisibility())
         || !"CATALOG_REFERENCE".equals(asset.getSourceType())) {
         throw invalidInput();
      }
   }

   private void requireGeneratedPainting(MediaAsset asset, Long ownerId) {
      if (asset == null
         || ownerId == null
         || asset.getOwnerUser() == null
         || !ownerId.equals(asset.getOwnerUser().getId())
         || !"ACTIVE".equals(asset.getStatus())
         || !"IMAGE".equals(asset.getAssetType())
         || !"GENERATED_PAINTING".equals(asset.getSemanticType())
         || !"GENERATED".equals(asset.getSourceType())
         || !"PRIVATE".equals(asset.getVisibility())) {
         throw invalidInput();
      }
   }

   private String requireSafeText(String text) {
      if (text != null
         && !text.isBlank()
         && text.length() <= this.providerProperties.getMaxTextChars()
         && !text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint) && codePoint != 10 && codePoint != 13 && codePoint != 9)) {
         return text;
      } else {
         throw invalidInput();
      }
   }

   private WorkflowModality parseModality(String value) {
      try {
         return WorkflowModality.valueOf(value);
      } catch (IllegalArgumentException | NullPointerException exception) {
         throw invalidInput();
      }
   }

   private void closeQuietly(ProviderArtifact artifact) {
      if (artifact != null) {
         try {
            artifact.close();
         } catch (RuntimeException var3) {
         }
      }
   }

   private static IllegalArgumentException invalidInput() {
      return new IllegalArgumentException("Creation input is unavailable or invalid");
   }

   public CreationInputMaterializer(
      final MediaAssetRepository mediaAssets,
      final PaintingRepository paintings,
      final CreationStepRepository steps,
      final MediaAssetStorageService mediaStorage,
      final ProviderArtifactStagingService artifactStaging,
      final PaintingPoemResultValidator poemValidator,
      final CreationProviderProperties providerProperties,
      final ObjectProvider<CatalogMediaService> catalogMedia
   ) {
      this.mediaAssets = mediaAssets;
      this.paintings = paintings;
      this.steps = steps;
      this.mediaStorage = mediaStorage;
      this.artifactStaging = artifactStaging;
      this.poemValidator = poemValidator;
      this.providerProperties = providerProperties;
      this.catalogMedia = catalogMedia;
   }

   public record MaterializedInput(ProviderInput input, ProviderArtifact inputArtifact) implements AutoCloseable {
      @Override
      public void close() {
         if (this.inputArtifact != null) {
            this.inputArtifact.close();
         }
      }
   }
}
