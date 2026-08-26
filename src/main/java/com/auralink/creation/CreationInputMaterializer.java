package com.auralink.creation;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.PaintingMetadataContext;
import com.auralink.creation.provider.ProviderImageInput;
import com.auralink.creation.provider.ProviderInput;
import com.auralink.creation.provider.ProviderTextInput;
import com.auralink.entity.CreationStep;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.media.MediaAssetValues;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.provider.qwen.PaintingPoemResult;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.service.media.MediaAssetStorageService;
import com.auralink.workflow.WorkflowModality;

import lombok.RequiredArgsConstructor;

/**
 * Converts persisted source references and prior durable outputs to the narrow
 * provider-neutral input contracts.  It never accepts URL/path input and never
 * reads mutable UserWorkflow data.
 */
@Service
@RequiredArgsConstructor
public class CreationInputMaterializer {

    private final MediaAssetRepository mediaAssets;
    private final PaintingRepository paintings;
    private final CreationStepRepository steps;
    private final MediaAssetStorageService mediaStorage;
    private final ProviderArtifactStagingService artifactStaging;
    private final PaintingPoemResultValidator poemValidator;
    private final CreationProviderProperties providerProperties;

    @Transactional(readOnly = true)
    public MaterializedInput materialize(
            CreationExecutionTransactionService.ClaimedCreationData creation,
            CreationExecutionTransactionService.StepData step) {
        WorkflowModality expected = parseModality(step.inputModality());
        if (step.stepIndex() == 0) {
            return sourceInput(creation, expected);
        }
        return intermediateInput(creation, step, expected);
    }

    private MaterializedInput sourceInput(
            CreationExecutionTransactionService.ClaimedCreationData creation,
            WorkflowModality expected) {
        WorkflowModality source = parseModality(creation.sourceModality());
        if (source != expected) {
            throw invalidInput();
        }
        return switch (source) {
            case TEXT_DESCRIPTION, POEM -> new MaterializedInput(
                    new ProviderTextInput(requireSafeText(creation.sourceText()), source), null);
            case IMAGE -> imageInput(requireOwnedActiveImage(creation.sourceAssetId(), creation.ownerId()),
                    WorkflowModality.IMAGE, null);
            case PAINTING -> paintingInput(requireOfficialPainting(creation.sourcePaintingId()));
            default -> throw invalidInput();
        };
    }

    private MaterializedInput intermediateInput(
            CreationExecutionTransactionService.ClaimedCreationData creation,
            CreationExecutionTransactionService.StepData step,
            WorkflowModality expected) {
        CreationStep previous = steps.findByCreationIdAndStepIndex(
                        creation.creationId(), step.stepIndex() - 1)
                .orElseThrow(CreationInputMaterializer::invalidInput);
        if (!CreationStepStatus.SUCCEEDED.name().equals(previous.getStatus())) {
            throw invalidInput();
        }
        WorkflowModality previousOutput = parseModality(previous.getOutputModality());
        if (previousOutput != expected) {
            throw invalidInput();
        }
        if (expected == WorkflowModality.PAINTING) {
            MediaAsset asset = previous.getOutputAsset();
            requireGeneratedPainting(asset, creation.ownerId());
            return imageInput(asset, WorkflowModality.PAINTING, null);
        }
        if (expected == WorkflowModality.POEM) {
            PaintingPoemResult poem = poemValidator.validate(previous.getOutputJson());
            return new MaterializedInput(new ProviderTextInput(poem.text(), WorkflowModality.POEM), null);
        }
        throw invalidInput();
    }

    private MaterializedInput paintingInput(Painting painting) {
        MediaAsset image = painting.getImageAsset();
        requireOfficialCatalogImage(image);
        return imageInput(image, WorkflowModality.PAINTING, new PaintingMetadataContext(
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
                painting.getMusicSceneDescription()));
    }

    private MaterializedInput imageInput(
            MediaAsset asset,
            WorkflowModality modality,
            PaintingMetadataContext metadata) {
        ProviderArtifact artifact = null;
        try (InputStream input = mediaStorage.resolve(asset).resource().getInputStream()) {
            artifact = artifactStaging.stageInputImage(input, asset.getMimeType());
            return new MaterializedInput(new ProviderImageInput(artifact, modality, metadata), artifact);
        } catch (IOException | RuntimeException exception) {
            closeQuietly(artifact);
            throw invalidInput();
        }
    }

    private MediaAsset requireOwnedActiveImage(Long assetId, Long ownerId) {
        if (assetId == null || ownerId == null) {
            throw invalidInput();
        }
        MediaAsset asset = mediaAssets.findById(assetId).orElseThrow(CreationInputMaterializer::invalidInput);
        if (asset.getOwnerUser() == null || !ownerId.equals(asset.getOwnerUser().getId())
                || !MediaAssetValues.Status.ACTIVE.equals(asset.getStatus())
                || !MediaAssetValues.AssetType.IMAGE.equals(asset.getAssetType())
                || !MediaAssetValues.Visibility.PRIVATE.equals(asset.getVisibility())) {
            throw invalidInput();
        }
        return asset;
    }

    private Painting requireOfficialPainting(Long paintingId) {
        if (paintingId == null) {
            throw invalidInput();
        }
        Painting painting = paintings.findById(paintingId).orElseThrow(CreationInputMaterializer::invalidInput);
        if (!"ACTIVE".equals(painting.getStatus()) || !painting.isImageAvailable()) {
            throw invalidInput();
        }
        return painting;
    }

    private void requireOfficialCatalogImage(MediaAsset asset) {
        if (asset == null || asset.getOwnerUser() != null
                || !MediaAssetValues.Status.ACTIVE.equals(asset.getStatus())
                || !MediaAssetValues.AssetType.IMAGE.equals(asset.getAssetType())
                || !MediaAssetValues.Visibility.PUBLIC.equals(asset.getVisibility())
                || !MediaAssetValues.SourceType.CATALOG_REFERENCE.equals(asset.getSourceType())) {
            throw invalidInput();
        }
    }

    private void requireGeneratedPainting(MediaAsset asset, Long ownerId) {
        if (asset == null || ownerId == null || asset.getOwnerUser() == null
                || !ownerId.equals(asset.getOwnerUser().getId())
                || !MediaAssetValues.Status.ACTIVE.equals(asset.getStatus())
                || !MediaAssetValues.AssetType.IMAGE.equals(asset.getAssetType())
                || !MediaAssetValues.SemanticType.GENERATED_PAINTING.equals(asset.getSemanticType())
                || !MediaAssetValues.SourceType.GENERATED.equals(asset.getSourceType())
                || !MediaAssetValues.Visibility.PRIVATE.equals(asset.getVisibility())) {
            throw invalidInput();
        }
    }

    private String requireSafeText(String text) {
        if (text == null || text.isBlank() || text.length() > providerProperties.getMaxTextChars()
                || text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                        && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
            throw invalidInput();
        }
        return text;
    }

    private WorkflowModality parseModality(String value) {
        try {
            return WorkflowModality.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidInput();
        }
    }

    private void closeQuietly(ProviderArtifact artifact) {
        if (artifact == null) {
            return;
        }
        try {
            artifact.close();
        } catch (RuntimeException ignored) {
            // The artifact implementation only permits its own contained staging root.
        }
    }

    private static IllegalArgumentException invalidInput() {
        return new IllegalArgumentException("Creation input is unavailable or invalid");
    }

    /** Caller owns this object and always closes it after the adapter attempt. */
    public record MaterializedInput(ProviderInput input, ProviderArtifact inputArtifact) implements AutoCloseable {
        @Override
        public void close() {
            if (inputArtifact != null) {
                inputArtifact.close();
            }
        }
    }
}
