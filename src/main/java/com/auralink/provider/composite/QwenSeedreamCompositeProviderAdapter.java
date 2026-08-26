package com.auralink.provider.composite;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterBinding;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.creation.provider.ProviderTextInput;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.qwen.PaintingPromptPlan;
import com.auralink.provider.qwen.QwenEndpointPolicy;
import com.auralink.provider.qwen.QwenPaintingPromptPlanner;
import com.auralink.provider.seedream.PoemPlanSeedreamPromptBuilder;
import com.auralink.provider.seedream.SeedreamEndpointPolicy;
import com.auralink.provider.seedream.SeedreamImageGenerator;
import com.auralink.provider.validation.ProviderInputValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

import lombok.RequiredArgsConstructor;

/** One workflow transform implemented as a validated Qwen stage then one Seedream stage. */
@Component
@ConditionalOnProperty(
        prefix = "auralink.creation-providers",
        name = "mock-adapters-enabled",
        havingValue = "false",
        matchIfMissing = true)
@RequiredArgsConstructor
public class QwenSeedreamCompositeProviderAdapter implements CreationProviderAdapter {

    public static final String PROVIDER_CODE = "qwen3vl-seedream5";
    private static final ProviderAdapterBinding BINDING = new ProviderAdapterBinding(
            WorkflowOperation.POEM_TO_PAINTING,
            PROVIDER_CODE,
            WorkflowModality.POEM,
            WorkflowModality.PAINTING);

    private final ProviderInputValidator inputValidator;
    private final QwenPaintingPromptPlanner planner;
    private final PoemPlanSeedreamPromptBuilder promptBuilder;
    private final SeedreamImageGenerator seedreamGenerator;
    private final QwenEndpointPolicy qwenEndpointPolicy;
    private final SeedreamEndpointPolicy seedreamEndpointPolicy;

    @Override
    public List<ProviderAdapterBinding> bindings() {
        return List.of(BINDING);
    }

    @Override
    public ProviderReadiness readiness() {
        ProviderReadiness qwen = qwenEndpointPolicy.readiness();
        if (qwen.state() != ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION) {
            return qwen;
        }
        return seedreamEndpointPolicy.readiness();
    }

    @Override
    public ProviderExecutionResult execute(ProviderExecutionRequest request) {
        if (request == null
                || request.operation() != WorkflowOperation.POEM_TO_PAINTING
                || !PROVIDER_CODE.equals(request.providerCode())
                || !(request.input() instanceof ProviderTextInput textInput)
                || textInput.modality() != WorkflowModality.POEM) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_REJECTED,
                    "POEM_TO_PAINTING provider input is invalid");
        }
        String poem = inputValidator.validateText(textInput);
        seedreamGenerator.prepare();
        // Validate both paid stages before submitting either request. The
        // clients resolve again at execution time, preserving their own
        // boundary checks without risking a wasted first-stage call here.
        qwenEndpointPolicy.resolveChatCompletionsEndpoint();
        seedreamEndpointPolicy.resolveGenerationEndpoint();
        PaintingPromptPlan plan = planner.create(request.requestId(), poem);
        ProviderArtifact artifact = seedreamGenerator.generate(
                request.requestId(), promptBuilder.build(plan), null);
        return new ProviderExecutionResult(
                request.requestId(),
                request.operation(),
                PROVIDER_CODE,
                WorkflowModality.PAINTING,
                new ProviderBinaryOutput(artifact));
    }
}
