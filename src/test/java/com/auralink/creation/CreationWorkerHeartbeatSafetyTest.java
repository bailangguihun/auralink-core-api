package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.TaskScheduler;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterRegistry;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderTextInput;
import com.auralink.provider.ProviderTestFixtures;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import com.auralink.workflow.capability.WorkflowParameterSchema;
import com.auralink.workflow.capability.WorkflowProviderCapability;
import com.auralink.workflow.graph.CanonicalWorkflowGraph;
import com.auralink.workflow.graph.CanonicalWorkflowNode;
import com.auralink.workflow.snapshot.WorkflowSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

class CreationWorkerHeartbeatSafetyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void lostOwnershipAfterMaterializationPreventsTheNextProviderCall() throws Exception {
        Fixture fixture = fixture();
        when(fixture.materializer.materialize(fixture.creation, fixture.step)).thenAnswer(ignored -> {
            fixture.heartbeatTask.get().run();
            return textInput();
        });

        fixture.worker.execute(new CreationExecutionTransactionService.ClaimedCreation(11L, "claim"));

        verify(fixture.adapters, never()).require(any(), any());
        verify(fixture.transactions, never()).markSendStarted(any(), any(), any());
    }

    @Test
    void lostOwnershipAfterProviderReturnDiscardsAndClosesTheArtifact() throws Exception {
        Fixture fixture = fixture();
        when(fixture.materializer.materialize(fixture.creation, fixture.step)).thenReturn(textInput());
        when(fixture.transactions.markSendStarted(11L, "claim", 12L)).thenReturn(Optional.of("request"));
        CreationProviderAdapter adapter = mock(CreationProviderAdapter.class);
        when(fixture.adapters.require(WorkflowOperation.TEXT_TO_PAINTING, "seedream-5")).thenReturn(adapter);
        ProviderArtifact artifact = ProviderTestFixtures.staging(
                        ProviderTestFixtures.properties(temporaryDirectory.resolve("returned-artifact")))
                .stageOutputImage(new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png");
        doAnswer(invocation -> {
            fixture.heartbeatTask.get().run();
            ProviderExecutionRequest request = invocation.getArgument(0);
            return new ProviderExecutionResult(request.requestId(), request.operation(), request.providerCode(),
                    WorkflowModality.PAINTING, new ProviderBinaryOutput(artifact));
        }).when(adapter).execute(any());

        fixture.worker.execute(new CreationExecutionTransactionService.ClaimedCreation(11L, "claim"));

        verify(adapter).execute(any());
        verify(fixture.persistence, never()).persistPainting(any(), any(), any(), any(Boolean.class));
        assertThat(artifact.isAvailable()).isFalse();
    }

    private Fixture fixture() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CreationExecutionTransactionService transactions = mock(CreationExecutionTransactionService.class);
        CreationInputMaterializer materializer = mock(CreationInputMaterializer.class);
        CreationResultPersistenceService persistence = mock(CreationResultPersistenceService.class);
        CreationFeatureGuard guard = mock(CreationFeatureGuard.class);
        CreationExecutionCapabilityService executionCapabilities = mock(CreationExecutionCapabilityService.class);
        WorkflowCapabilityRegistry workflowCapabilities = mock(WorkflowCapabilityRegistry.class);
        ProviderAdapterRegistry adapters = mock(ProviderAdapterRegistry.class);
        CreationLeaseHeartbeatTransactionService leaseTransactions =
                mock(CreationLeaseHeartbeatTransactionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        AtomicReference<Runnable> heartbeatTask = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> heartbeatFuture = mock(ScheduledFuture.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class))).thenAnswer(invocation -> {
            heartbeatTask.set(invocation.getArgument(0));
            return heartbeatFuture;
        });
        when(leaseTransactions.refresh(eq(11L), eq("claim"), any(), any())).thenReturn(0);
        CreationLeaseHeartbeatService heartbeats = new CreationLeaseHeartbeatService(
                leaseTransactions, new CreationExecutionProperties(), Clock.systemUTC(), scheduler);
        CreationRecoveryGate gate = new CreationRecoveryGate();
        gate.openAfterRecovery();

        String snapshot = mapper.writeValueAsString(new WorkflowSnapshot(1, "workflow", "Mock", 1,
                new CanonicalWorkflowGraph(1, List.of(CanonicalWorkflowNode.transform(
                        "painting", WorkflowOperation.TEXT_TO_PAINTING, "seedream-5",
                        WorkflowModality.TEXT_DESCRIPTION, WorkflowModality.PAINTING)), List.of())));
        var creation = new CreationExecutionTransactionService.ClaimedCreationData(
                11L, "claim", 5L, snapshot, "TEXT_DESCRIPTION", "safe test input", null, null);
        var step = new CreationExecutionTransactionService.StepData(
                12L, 0, "painting", "TEXT_TO_PAINTING", "seedream-5", "TEXT_DESCRIPTION", "PAINTING",
                "PENDING", 0, "NOT_SENT");
        when(transactions.loadClaimed(11L, "claim")).thenReturn(Optional.of(creation));
        when(transactions.loadSteps(11L)).thenReturn(List.of(step));
        when(transactions.startPendingStep(11L, "claim", 12L)).thenReturn(true);
        when(workflowCapabilities.require(WorkflowOperation.TEXT_TO_PAINTING)).thenReturn(paintingCapability());

        CreationWorker worker = new CreationWorker(
                transactions, materializer, persistence, guard, executionCapabilities, workflowCapabilities,
                adapters, new PaintingPoemResultValidator(mapper, new CreationProviderProperties()), mapper,
                heartbeats, gate);
        return new Fixture(worker, transactions, materializer, persistence, adapters, creation, step, heartbeatTask);
    }

    private static CreationInputMaterializer.MaterializedInput textInput() {
        return new CreationInputMaterializer.MaterializedInput(
                new ProviderTextInput("safe test input", WorkflowModality.TEXT_DESCRIPTION), null);
    }

    private static WorkflowOperationCapability paintingCapability() {
        return new WorkflowOperationCapability(
                WorkflowOperation.TEXT_TO_PAINTING, "Painting", WorkflowModality.TEXT_DESCRIPTION,
                WorkflowModality.PAINTING, true, false, true, "unused", List.of(new WorkflowProviderCapability(
                        "seedream-5", "Mock", true, false, WorkflowParameterSchema.emptyStrictObject())));
    }

    private record Fixture(
            CreationWorker worker,
            CreationExecutionTransactionService transactions,
            CreationInputMaterializer materializer,
            CreationResultPersistenceService persistence,
            ProviderAdapterRegistry adapters,
            CreationExecutionTransactionService.ClaimedCreationData creation,
            CreationExecutionTransactionService.StepData step,
            AtomicReference<Runnable> heartbeatTask) {
    }
}
