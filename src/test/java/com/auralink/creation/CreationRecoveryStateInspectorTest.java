package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.CreationStep;
import com.auralink.entity.CreationStepDispatchAttempt;
import com.auralink.entity.MediaAsset;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.service.media.MediaAssetStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;

class CreationRecoveryStateInspectorTest {

    @TempDir
    Path temporaryDirectory;

    private final MediaAssetStorageService mediaStorage = mock(MediaAssetStorageService.class);
    private final CreationRecoveryStateInspector inspector = new CreationRecoveryStateInspector(
            new PaintingPoemResultValidator(new ObjectMapper(), new CreationProviderProperties()), mediaStorage);

    @Test
    void classifiesRunningNotSentAsSafeRequeueWithoutChangingAttemptNumber() {
        CreationStep step = step(1, "RUNNING", "NOT_SENT");
        CreationStepDispatchAttempt dispatch = CreationStepDispatchAttempt.builder()
                .dispatchState("NOT_SENT").build();

        var decision = inspector.inspect(List.of(step), List.of(activeAttempt()), Map.of(step.getId(), dispatch));

        assertThat(decision.kind()).isEqualTo(CreationRecoveryStateInspector.Kind.REQUEUE_NOT_SENT);
        assertThat(decision.boundary()).isSameAs(step);
    }

    @Test
    void classifiesSendStartedAsAmbiguousAndNeverAsReplayable() {
        CreationStep step = step(1, "RUNNING", "SEND_STARTED");
        step.setProviderRequestKey("request-key");
        CreationStepDispatchAttempt dispatch = CreationStepDispatchAttempt.builder()
                .dispatchState("SEND_STARTED").providerRequestKey("request-key").build();

        var decision = inspector.inspect(List.of(step), List.of(activeAttempt()), Map.of(step.getId(), dispatch));

        assertThat(decision.kind()).isEqualTo(CreationRecoveryStateInspector.Kind.AMBIGUOUS);
        assertThat(decision.errorCode()).isEqualTo("PROVIDER_DISPATCH_AMBIGUOUS");
    }

    @Test
    void finalizesDurablePaintingWithoutAProvider() {
        MediaAsset asset = availableAsset("a");
        asset.setId(9L);
        CreationStep step = step(1, "SUCCEEDED", "RESULT_PERSISTED");
        step.setOutputAsset(asset);

        var decision = inspector.inspect(List.of(step), List.of(activeAttempt()), Map.of());

        assertThat(decision.kind()).isEqualTo(CreationRecoveryStateInspector.Kind.FINALIZE_SUCCESS);
        assertThat(decision.finalAssetId()).isEqualTo(9L);
    }

    @Test
    void quarantinesResultPersistedRunningProjection() {
        CreationStep step = step(1, "RUNNING", "RESULT_PERSISTED");
        var decision = inspector.inspect(List.of(step), List.of(activeAttempt()), Map.of());
        assertThat(decision.kind()).isEqualTo(CreationRecoveryStateInspector.Kind.INCONSISTENT);
        assertThat(decision.errorCode()).isEqualTo("CREATION_RESULT_PERSISTENCE_INCONSISTENT");
    }

    @Test
    void preservesSuccessfulPrefixAndQueuesOnlyPendingWork() {
        MediaAsset asset = availableAsset("b");
        asset.setId(9L);
        CreationStep first = step(1, "SUCCEEDED", "RESULT_PERSISTED");
        first.setOutputAsset(asset);
        CreationStep second = step(2, "PENDING", "NOT_SENT");

        var decision = inspector.inspect(List.of(first, second), List.of(activeAttempt()), Map.of());

        assertThat(decision.kind()).isEqualTo(CreationRecoveryStateInspector.Kind.REQUEUE);
    }

    @Test
    void terminalizesFailedBoundaryAsPartialWhenDurablePrefixExists() {
        MediaAsset asset = availableAsset("c");
        asset.setId(9L);
        CreationStep first = step(1, "SUCCEEDED", "RESULT_PERSISTED");
        first.setOutputAsset(asset);
        CreationStep second = step(2, "FAILED", "SEND_STARTED");

        var decision = inspector.inspect(List.of(first, second), List.of(activeAttempt()), Map.of());

        assertThat(decision.kind()).isEqualTo(CreationRecoveryStateInspector.Kind.FINALIZE_FAILED);
        assertThat(decision.priorSuccess()).isTrue();
        assertThat(decision.errorCode()).isEqualTo("PROVIDER_DISPATCH_AMBIGUOUS");
    }

    @Test
    void quarantinesSucceededStepWithoutItsRequiredDurableOutput() {
        CreationStep step = step(1, "SUCCEEDED", "RESULT_PERSISTED");

        var decision = inspector.inspect(List.of(step), List.of(activeAttempt()), Map.of());

        assertThat(decision.kind()).isEqualTo(CreationRecoveryStateInspector.Kind.INCONSISTENT);
        assertThat(decision.errorCode()).isEqualTo("CREATION_RESULT_PERSISTENCE_INCONSISTENT");
    }

    @Test
    void quarantinesOutOfOrderSuccessWithoutResettingAnyStep() {
        CreationStep first = step(1, "PENDING", "NOT_SENT");
        MediaAsset asset = availableAsset("d");
        asset.setId(10L);
        CreationStep second = step(2, "SUCCEEDED", "RESULT_PERSISTED");
        second.setOutputAsset(asset);

        var decision = inspector.inspect(List.of(first, second), List.of(activeAttempt()), Map.of());

        assertThat(decision.kind()).isEqualTo(CreationRecoveryStateInspector.Kind.INCONSISTENT);
        assertThat(decision.errorCode()).isEqualTo("CREATION_STATE_INCONSISTENT");
    }

    @Test
    void quarantinesDurablePaintingWhenItsOnlyReferencedManagedFileIsUnavailable() {
        MediaAsset asset = MediaAsset.builder().mimeType("image/png").sha256("e".repeat(64)).fileSize(12L).build();
        asset.setId(11L);
        CreationStep step = step(1, "SUCCEEDED", "RESULT_PERSISTED");
        step.setOutputAsset(asset);
        when(mediaStorage.resolve(asset)).thenThrow(new IllegalStateException("missing fixture file"));

        var decision = inspector.inspect(List.of(step), List.of(activeAttempt()), Map.of());

        assertThat(decision.kind()).isEqualTo(CreationRecoveryStateInspector.Kind.INCONSISTENT);
        assertThat(decision.errorCode()).isEqualTo("CREATION_RESULT_PERSISTENCE_INCONSISTENT");
    }

    private MediaAsset availableAsset(String digestCharacter) {
        MediaAsset asset = MediaAsset.builder()
                .mimeType("image/png")
                .sha256(digestCharacter.repeat(64))
                .fileSize(12L)
                .build();
        try {
            Path file = Files.createTempFile(temporaryDirectory, "available-", ".png");
            Files.writeString(file, "123456789012");
            FileSystemResource resource = new FileSystemResource(file);
            when(mediaStorage.resolve(any(MediaAsset.class))).thenReturn(
                    new MediaAssetStorageService.MediaAssetStoredResource(resource, 12L));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        return asset;
    }

    private static CreationExecutionAttempt activeAttempt() {
        CreationExecutionAttempt attempt = CreationExecutionAttempt.builder().attemptNumber(1).build();
        attempt.setId(7L);
        return attempt;
    }

    private static CreationStep step(int index, String status, String dispatchState) {
        CreationStep step = CreationStep.builder()
                .stepIndex(index - 1)
                .status(status)
                .providerDispatchState(dispatchState)
                .outputModality("PAINTING")
                .build();
        step.setId((long) index);
        return step;
    }
}
