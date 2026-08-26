package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.CreationStep;
import com.auralink.entity.MediaAsset;
import com.auralink.media.MediaAssetValues;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.service.media.MediaAssetStorageResolver;
import com.auralink.service.media.MediaAssetStorageService;
import com.auralink.workflow.WorkflowModality;
import com.fasterxml.jackson.databind.ObjectMapper;

class CreationRecoveryManagedFileAvailabilityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingReferencedManagedFileQuarantinesDurableSuccessWithoutProviderReplay() {
        MediaAssetProperties media = new MediaAssetProperties();
        media.setManagedDir(temporaryDirectory.resolve("managed").toString());
        PaintingProperties paintings = new PaintingProperties();
        paintings.setPictureDir(temporaryDirectory.resolve("catalog").toString());
        MediaAssetStorageService storage = new MediaAssetStorageService(
                media, new MediaAssetStorageResolver(media, paintings));
        CreationRecoveryStateInspector inspector = new CreationRecoveryStateInspector(
                new PaintingPoemResultValidator(new ObjectMapper(), new CreationProviderProperties()), storage);

        MediaAsset asset = MediaAsset.builder()
                .storageKey("managed/missing.png")
                .originalFilename("missing.png")
                .mimeType("image/png")
                .fileSize(12L)
                .sha256("a".repeat(64))
                .assetType(MediaAssetValues.AssetType.IMAGE)
                .semanticType(MediaAssetValues.SemanticType.GENERATED_PAINTING)
                .sourceType(MediaAssetValues.SourceType.GENERATED)
                .visibility(MediaAssetValues.Visibility.PRIVATE)
                .status(MediaAssetValues.Status.ACTIVE)
                .build();
        asset.setId(11L);
        CreationStep step = CreationStep.builder()
                .stepIndex(0)
                .status(CreationStepStatus.SUCCEEDED.name())
                .providerDispatchState(ProviderDispatchState.RESULT_PERSISTED.name())
                .outputModality(WorkflowModality.PAINTING.name())
                .outputAsset(asset)
                .build();
        step.setId(12L);
        CreationExecutionAttempt attempt = CreationExecutionAttempt.builder().attemptNumber(1).build();
        attempt.setId(13L);

        var decision = inspector.inspect(List.of(step), List.of(attempt), Map.of());

        assertThat(decision.kind()).isEqualTo(CreationRecoveryStateInspector.Kind.INCONSISTENT);
        assertThat(decision.errorCode()).isEqualTo("CREATION_RESULT_PERSISTENCE_INCONSISTENT");
    }
}
