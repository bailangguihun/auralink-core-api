package com.auralink.provider.seedream;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterBinding;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderImageInput;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderTextInput;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.validation.ProviderDataUrlEncoder;
import com.auralink.provider.validation.ProviderInputValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

import lombok.RequiredArgsConstructor;

/** Seedream adapter for direct text and validated reference-image Painting generation. */
@Component
@ConditionalOnProperty(
        prefix = "auralink.creation-providers",
        name = "mock-adapters-enabled",
        havingValue = "false",
        matchIfMissing = true)
@RequiredArgsConstructor
public class SeedreamProviderAdapter implements CreationProviderAdapter {

    public static final String PROVIDER_CODE = "seedream-5";
    private static final List<ProviderAdapterBinding> BINDINGS = List.of(
            new ProviderAdapterBinding(
                    WorkflowOperation.TEXT_TO_PAINTING,
                    PROVIDER_CODE,
                    WorkflowModality.TEXT_DESCRIPTION,
                    WorkflowModality.PAINTING),
            new ProviderAdapterBinding(
                    WorkflowOperation.IMAGE_TO_PAINTING,
                    PROVIDER_CODE,
                    WorkflowModality.IMAGE,
                    WorkflowModality.PAINTING));

    private final ProviderInputValidator inputValidator;
    private final ProviderDataUrlEncoder dataUrlEncoder;
    private final TextToPaintingPromptBuilder textPromptBuilder;
    private final ImageToPaintingPromptBuilder imagePromptBuilder;
    private final SeedreamImageGenerator generator;
    private final SeedreamEndpointPolicy endpointPolicy;
    private final CreationProviderProperties properties;

    @Override
    public List<ProviderAdapterBinding> bindings() {
        return BINDINGS;
    }

    @Override
    public ProviderReadiness readiness() {
        return endpointPolicy.readiness();
    }

    @Override
    public ProviderExecutionResult execute(ProviderExecutionRequest request) {
        requireBinding(request);
        final ProviderArtifact artifact;
        if (request.operation() == WorkflowOperation.TEXT_TO_PAINTING) {
            if (!(request.input() instanceof ProviderTextInput textInput)
                    || textInput.modality() != WorkflowModality.TEXT_DESCRIPTION) {
                throw rejected("TEXT_TO_PAINTING requires TEXT_DESCRIPTION input");
            }
            String source = inputValidator.validateText(textInput);
            artifact = generator.generate(request.requestId(), textPromptBuilder.build(source), null);
        } else if (request.operation() == WorkflowOperation.IMAGE_TO_PAINTING) {
            if (!(request.input() instanceof ProviderImageInput imageInput)
                    || imageInput.modality() != WorkflowModality.IMAGE) {
                throw rejected("IMAGE_TO_PAINTING requires IMAGE input");
            }
            ProviderArtifact source = inputValidator.validateImage(imageInput);
            String dataUrl = dataUrlEncoder.encodeImage(source, properties.getMaxImageInputBytes());
            artifact = generator.generate(request.requestId(), imagePromptBuilder.build(), dataUrl);
        } else {
            throw rejected("Seedream operation is not supported");
        }
        return new ProviderExecutionResult(
                request.requestId(),
                request.operation(),
                PROVIDER_CODE,
                WorkflowModality.PAINTING,
                new ProviderBinaryOutput(artifact));
    }

    private void requireBinding(ProviderExecutionRequest request) {
        if (request == null || !PROVIDER_CODE.equals(request.providerCode())
                || BINDINGS.stream().noneMatch(binding -> binding.operation() == request.operation())) {
            throw rejected("Provider operation mapping is invalid");
        }
    }

    private ProviderExecutionException rejected(String message) {
        return new ProviderExecutionException(ProviderErrorCategory.PROVIDER_REJECTED, message);
    }
}
