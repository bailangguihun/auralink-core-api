package com.auralink.ops.round9cc;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.auralink.creation.CreationExecutionBoundary;
import com.auralink.creation.CreationExecutionBoundaryHook;
import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.PackagedMockCreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterBinding;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.creation.provider.ProviderTextOutput;
import com.auralink.ops.round9b2.Round9B2MockCreationProviderAdapter;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

/** Dedicated in-process Mock used only by the ROUND 9C-C packaged harness. */
public final class Round9CcMockCreationProviderAdapter
        implements CreationProviderAdapter, PackagedMockCreationProviderAdapter {

    private static final String STEP = "MOCK_STEP";
    private static final List<String> POEM_LINES = List.of(
            "远岫含烟入暮云", "孤舟一叶过江津", "疏林淡墨留清韵", "月照寒波不染尘");
    private static final List<ProviderAdapterBinding> BINDINGS = List.of(
            new ProviderAdapterBinding(WorkflowOperation.TEXT_TO_PAINTING, "seedream-5",
                    WorkflowModality.TEXT_DESCRIPTION, WorkflowModality.PAINTING),
            new ProviderAdapterBinding(WorkflowOperation.IMAGE_TO_PAINTING, "seedream-5",
                    WorkflowModality.IMAGE, WorkflowModality.PAINTING),
            new ProviderAdapterBinding(WorkflowOperation.POEM_TO_PAINTING, "qwen3vl-seedream5",
                    WorkflowModality.POEM, WorkflowModality.PAINTING),
            new ProviderAdapterBinding(WorkflowOperation.PAINTING_TO_POEM, "qwen3-vl-plus",
                    WorkflowModality.PAINTING, WorkflowModality.POEM));

    private final ProviderArtifactStagingService staging;
    private final CreationExecutionBoundaryHook boundaryHook;
    private final Round9CcMockJournal journal;

    public Round9CcMockCreationProviderAdapter(
            ProviderArtifactStagingService staging,
            CreationExecutionBoundaryHook boundaryHook,
            Round9CcMockJournal journal) {
        this.staging = staging;
        this.boundaryHook = boundaryHook;
        this.journal = journal;
    }

    @Override
    public List<ProviderAdapterBinding> bindings() {
        return BINDINGS;
    }

    @Override
    public ProviderReadiness readiness() {
        return new ProviderReadiness(
                ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION,
                "READY_FOR_CONTROLLED_EXECUTION");
    }

    @Override
    public ProviderExecutionResult execute(ProviderExecutionRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_INTERNAL_CONTRACT_ERROR,
                    "ROUND 9C-C Mock cannot execute in a transaction");
        }
        journal.entry(STEP);
        boundaryHook.reached(CreationExecutionBoundary.MOCK_DURING_EXECUTION);
        ProviderExecutionResult result = switch (request.operation()) {
            case TEXT_TO_PAINTING, IMAGE_TO_PAINTING, POEM_TO_PAINTING -> painting(request);
            case PAINTING_TO_POEM -> poem(request);
            case PAINTING_TO_MUSIC, PAINTING_TO_VIDEO -> throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_INTERNAL_CONTRACT_ERROR,
                    "ROUND 9C-C Mock does not implement this operation");
        };
        journal.returned(STEP);
        return result;
    }

    private ProviderExecutionResult painting(ProviderExecutionRequest request) {
        ProviderArtifact artifact = staging.stageOutputImage(
                new ByteArrayInputStream(Round9B2MockCreationProviderAdapter.validPng()), "image/png");
        return new ProviderExecutionResult(
                request.requestId(), request.operation(), request.providerCode(), WorkflowModality.PAINTING,
                new ProviderBinaryOutput(artifact));
    }

    private ProviderExecutionResult poem(ProviderExecutionRequest request) {
        return new ProviderExecutionResult(
                request.requestId(), request.operation(), request.providerCode(), WorkflowModality.POEM,
                new ProviderTextOutput("1", null, POEM_LINES, String.join("\n", POEM_LINES)));
    }
}
