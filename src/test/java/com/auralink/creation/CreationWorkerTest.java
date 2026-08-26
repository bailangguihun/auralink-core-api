package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterRegistry;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderImageInput;
import com.auralink.creation.provider.ProviderTextOutput;
import com.auralink.provider.ProviderTestFixtures;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowNodeKind;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import com.auralink.workflow.capability.WorkflowParameterSchema;
import com.auralink.workflow.capability.WorkflowProviderCapability;
import com.auralink.workflow.graph.CanonicalWorkflowGraph;
import com.auralink.workflow.graph.CanonicalWorkflowNode;
import com.auralink.workflow.snapshot.WorkflowSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

class CreationWorkerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void invokesAdapterOutsideTransactionAndPersistsOneTerminalPoemStep() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CreationExecutionTransactionService transactions = mock(CreationExecutionTransactionService.class);
        CreationInputMaterializer materializer = mock(CreationInputMaterializer.class);
        CreationResultPersistenceService persistence = mock(CreationResultPersistenceService.class);
        CreationFeatureGuard guard = mock(CreationFeatureGuard.class);
        CreationExecutionCapabilityService executionCapabilities = mock(CreationExecutionCapabilityService.class);
        WorkflowCapabilityRegistry workflowCapabilities = mock(WorkflowCapabilityRegistry.class);
        ProviderAdapterRegistry adapters = mock(ProviderAdapterRegistry.class);
        CreationProviderAdapter adapter = mock(CreationProviderAdapter.class);

        String snapshot = mapper.writeValueAsString(new WorkflowSnapshot(1, "workflow", "Mock", 1,
                new CanonicalWorkflowGraph(1, List.of(CanonicalWorkflowNode.transform(
                        "poem", WorkflowOperation.PAINTING_TO_POEM, "qwen3-vl-plus",
                        WorkflowModality.PAINTING, WorkflowModality.POEM)), List.of())));
        var creation = new CreationExecutionTransactionService.ClaimedCreationData(
                11L, "claim-token", 5L, snapshot, "PAINTING", null, null, 8L);
        var step = new CreationExecutionTransactionService.StepData(
                12L, 0, "poem", "PAINTING_TO_POEM", "qwen3-vl-plus", "PAINTING", "POEM",
                "PENDING", 0, "NOT_SENT");
        when(transactions.loadClaimed(11L, "claim-token")).thenReturn(Optional.of(creation));
        when(transactions.loadSteps(11L)).thenReturn(List.of(step));
        when(transactions.startPendingStep(11L, "claim-token", 12L)).thenReturn(true);
        when(transactions.markSendStarted(11L, "claim-token", 12L)).thenReturn(Optional.of("request-key"));
        ProviderArtifact inputArtifact = ProviderTestFixtures.staging(
                        ProviderTestFixtures.properties(temporaryDirectory.resolve("provider-staging")))
                .stageInputImage(new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png");
        when(materializer.materialize(creation, step)).thenReturn(new CreationInputMaterializer.MaterializedInput(
                new ProviderImageInput(inputArtifact, WorkflowModality.PAINTING, null), inputArtifact));
        when(adapters.require(WorkflowOperation.PAINTING_TO_POEM, "qwen3-vl-plus")).thenReturn(adapter);
        when(workflowCapabilities.require(WorkflowOperation.PAINTING_TO_POEM)).thenReturn(capability());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ProviderExecutionRequest request = invocation.getArgument(0);
            return new ProviderExecutionResult(request.requestId(), request.operation(), request.providerCode(),
                    WorkflowModality.POEM, new ProviderTextOutput("1", null,
                            List.of("远岫含烟入暮云", "孤舟一叶过江津", "疏林淡墨留清韵", "月照寒波不染尘"),
                            "远岫含烟入暮云\n孤舟一叶过江津\n疏林淡墨留清韵\n月照寒波不染尘"));
        }).when(adapter).execute(any());

        CreationWorker worker = new CreationWorker(
                transactions, materializer, persistence, guard, executionCapabilities, workflowCapabilities,
                adapters, new PaintingPoemResultValidator(mapper, new CreationProviderProperties()), mapper);

        worker.execute(new CreationExecutionTransactionService.ClaimedCreation(11L, "claim-token"));

        verify(adapter).execute(any());
        verify(persistence).persistPoem(eq(creation), eq(step), any(ProviderTextOutput.class), eq(true));
        verify(executionCapabilities).requireExecutionAvailable(
                WorkflowOperation.PAINTING_TO_POEM, "qwen3-vl-plus");
        assertThat(inputArtifact.isAvailable()).isFalse();
    }

    private static WorkflowOperationCapability capability() {
        return new WorkflowOperationCapability(
                WorkflowOperation.PAINTING_TO_POEM, "Poem", WorkflowModality.PAINTING, WorkflowModality.POEM,
                true, false, true, "unused", List.of(new WorkflowProviderCapability(
                        "qwen3-vl-plus", "Qwen", true, false, WorkflowParameterSchema.emptyStrictObject())));
    }
}
