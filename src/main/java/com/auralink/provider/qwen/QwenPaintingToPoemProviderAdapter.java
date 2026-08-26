package com.auralink.provider.qwen;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterBinding;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderImageInput;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderTextOutput;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.validation.ProviderDataUrlEncoder;
import com.auralink.provider.validation.ProviderInputValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

import lombok.RequiredArgsConstructor;

/** Validated image-to-four-line-poem Qwen creation adapter. */
@Component
@ConditionalOnProperty(
        prefix = "auralink.creation-providers",
        name = "mock-adapters-enabled",
        havingValue = "false",
        matchIfMissing = true)
@RequiredArgsConstructor
public class QwenPaintingToPoemProviderAdapter implements CreationProviderAdapter {

    public static final String PROVIDER_CODE = "qwen3-vl-plus";
    private static final ProviderAdapterBinding BINDING = new ProviderAdapterBinding(
            WorkflowOperation.PAINTING_TO_POEM,
            PROVIDER_CODE,
            WorkflowModality.PAINTING,
            WorkflowModality.POEM);

    private final ProviderInputValidator inputValidator;
    private final ProviderDataUrlEncoder dataUrlEncoder;
    private final CreationProviderProperties properties;
    private final QwenCreationHttpClient client;
    private final QwenEndpointPolicy endpointPolicy;
    private final PaintingToPoemPromptBuilder promptBuilder;
    private final PaintingPoemResultValidator resultValidator;

    @Override
    public List<ProviderAdapterBinding> bindings() {
        return List.of(BINDING);
    }

    @Override
    public ProviderReadiness readiness() {
        return endpointPolicy.readiness();
    }

    @Override
    public ProviderExecutionResult execute(ProviderExecutionRequest request) {
        if (request == null
                || request.operation() != WorkflowOperation.PAINTING_TO_POEM
                || !PROVIDER_CODE.equals(request.providerCode())
                || !(request.input() instanceof ProviderImageInput imageInput)
                || imageInput.modality() != WorkflowModality.PAINTING) {
            throw rejected("PAINTING_TO_POEM provider input is invalid");
        }
        ProviderArtifact source = inputValidator.validateImage(imageInput);
        String dataUrl = dataUrlEncoder.encodeImage(source, properties.getMaxImageInputBytes());
        QwenResponseContent response = client.completeImageJsonWithShape(
                request.requestId(),
                promptBuilder.systemInstruction(),
                dataUrl,
                promptBuilder.userInstruction(imageInput.paintingMetadata()));
        PaintingPoemResult poem = resultValidator.validate(response);
        return new ProviderExecutionResult(
                request.requestId(),
                request.operation(),
                PROVIDER_CODE,
                WorkflowModality.POEM,
                new ProviderTextOutput(
                        poem.schemaVersion(), poem.title(), poem.lines(), poem.text()));
    }

    private ProviderExecutionException rejected(String message) {
        return new ProviderExecutionException(ProviderErrorCategory.PROVIDER_REJECTED, message);
    }
}
