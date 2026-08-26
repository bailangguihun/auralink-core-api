package com.auralink.ops.round9b2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterBinding;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.creation.provider.ProviderTextOutput;
import com.auralink.creation.provider.PackagedMockCreationProviderAdapter;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

/**
 * Isolated packaged-harness adapter.  It has no endpoint, credential, real
 * provider implementation, or production activation path.  It exists solely
 * when the explicit test property is set on a disposable runtime.
 */
@Component
@ConditionalOnProperty(
        prefix = "auralink.creation-providers",
        name = "mock-adapters-enabled",
        havingValue = "true")
public class Round9B2MockCreationProviderAdapter
        implements CreationProviderAdapter, PackagedMockCreationProviderAdapter {

    private static final byte[] PNG = png();
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
    private final AtomicInteger seedreamCalls = new AtomicInteger();
    private final AtomicInteger qwenCalls = new AtomicInteger();
    private volatile boolean failNextCall;
    private volatile WorkflowOperation failNextOperation;
    private volatile boolean invalidNextOutput;

    public Round9B2MockCreationProviderAdapter(ProviderArtifactStagingService staging) {
        this.staging = staging;
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
                    "Mock adapter must not execute inside a transaction");
        }
        if (failNextCall || request.operation() == failNextOperation) {
            failNextCall = false;
            failNextOperation = null;
            throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_TIMEOUT, "Synthetic timeout");
        }
        return switch (request.operation()) {
            case TEXT_TO_PAINTING, IMAGE_TO_PAINTING -> painting(request, false);
            case POEM_TO_PAINTING -> painting(request, true);
            case PAINTING_TO_POEM -> poem(request);
            case PAINTING_TO_MUSIC, PAINTING_TO_VIDEO -> throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_INTERNAL_CONTRACT_ERROR,
                    "Unsupported mock operation");
        };
    }

    public void failNextCall() {
        failNextCall = true;
    }

    public void invalidateNextOutput() {
        invalidNextOutput = true;
    }

    public void failNextOperation(WorkflowOperation operation) {
        failNextOperation = operation;
    }

    public int seedreamCalls() {
        return seedreamCalls.get();
    }

    public int qwenCalls() {
        return qwenCalls.get();
    }

    public static byte[] validPng() {
        return PNG.clone();
    }

    private ProviderExecutionResult painting(ProviderExecutionRequest request, boolean composite) {
        if (composite) {
            qwenCalls.incrementAndGet();
        }
        seedreamCalls.incrementAndGet();
        ProviderArtifact artifact = staging.stageOutputImage(new ByteArrayInputStream(PNG), "image/png");
        if (invalidNextOutput) {
            invalidNextOutput = false;
            return new ProviderExecutionResult(request.requestId(), request.operation(), request.providerCode(),
                    WorkflowModality.PAINTING, new ProviderBinaryOutput(
                            artifact, "image/jpeg", artifact.byteLength(), artifact.sha256(),
                            artifact.width(), artifact.height()));
        }
        return new ProviderExecutionResult(request.requestId(), request.operation(), request.providerCode(),
                WorkflowModality.PAINTING, new ProviderBinaryOutput(artifact));
    }

    private ProviderExecutionResult poem(ProviderExecutionRequest request) {
        qwenCalls.incrementAndGet();
        if (invalidNextOutput) {
            invalidNextOutput = false;
            return new ProviderExecutionResult(request.requestId(), request.operation(), request.providerCode(),
                    WorkflowModality.POEM, new ProviderTextOutput("2", null, POEM_LINES,
                            String.join("\n", POEM_LINES)));
        }
        return new ProviderExecutionResult(request.requestId(), request.operation(), request.providerCode(),
                WorkflowModality.POEM, new ProviderTextOutput("1", null, POEM_LINES,
                        String.join("\n", POEM_LINES)));
    }

    private static byte[] png() {
        try {
            BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, Color.BLACK.getRGB());
            image.setRGB(1, 0, Color.WHITE.getRGB());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "PNG", output)) {
                throw new IllegalStateException("PNG encoder unavailable");
            }
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Synthetic PNG could not be created", exception);
        }
    }
}
