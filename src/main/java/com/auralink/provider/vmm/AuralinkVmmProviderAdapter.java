package com.auralink.provider.vmm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

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
import com.auralink.provider.ProviderBulkheadKind;
import com.auralink.provider.ProviderBulkheads;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.provider.validation.ProviderDataUrlEncoder;
import com.auralink.provider.validation.ProviderInputValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

import lombok.RequiredArgsConstructor;

/** Safe HTTP/filesystem boundary around the unchanged inherited VMM service. */
@Component
@RequiredArgsConstructor
public class AuralinkVmmProviderAdapter implements CreationProviderAdapter {

    public static final String PROVIDER_CODE = "auralink-vmm";
    private static final Pattern SAFE_WAVE_NAME = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,126}\\.wav");
    private static final ProviderAdapterBinding BINDING = new ProviderAdapterBinding(
            WorkflowOperation.PAINTING_TO_MUSIC,
            PROVIDER_CODE,
            WorkflowModality.PAINTING,
            WorkflowModality.AUDIO);

    private final ProviderInputValidator inputValidator;
    private final ProviderDataUrlEncoder dataUrlEncoder;
    private final ProviderArtifactStagingService stagingService;
    private final CreationProviderProperties properties;
    private final VmmHttpClient client;
    private final VmmEndpointPolicy endpointPolicy;
    private final ProviderBulkheads bulkheads;

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
                || request.operation() != WorkflowOperation.PAINTING_TO_MUSIC
                || !PROVIDER_CODE.equals(request.providerCode())
                || !(request.input() instanceof ProviderImageInput imageInput)
                || imageInput.modality() != WorkflowModality.PAINTING) {
            throw rejected("PAINTING_TO_MUSIC provider input is invalid");
        }
        ProviderArtifact source = inputValidator.validateImage(imageInput);
        stagingService.prepare();
        Path outputRoot = endpointPolicy.resolveOutputRoot();
        String dataUrl = dataUrlEncoder.encodeImage(source, properties.getMaxImageInputBytes());
        String fileName = bulkheads.execute(
                ProviderBulkheadKind.VMM,
                () -> client.generate(request.requestId(), dataUrl));
        ProviderArtifact output = stageContainedWave(outputRoot, fileName);
        return new ProviderExecutionResult(
                request.requestId(),
                request.operation(),
                PROVIDER_CODE,
                WorkflowModality.AUDIO,
                new ProviderBinaryOutput(output));
    }

    private ProviderArtifact stageContainedWave(Path configuredRoot, String fileName) {
        if (fileName == null || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..") || !SAFE_WAVE_NAME.matcher(fileName).matches()) {
            throw outputInvalid("VMM returned an unsafe file name", null);
        }

        final Path realRoot;
        final Path candidate;
        try {
            if (Files.isSymbolicLink(configuredRoot)
                    || !Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw outputInvalid("VMM output root is unavailable", null);
            }
            realRoot = configuredRoot.toRealPath();
            if (!realRoot.equals(configuredRoot.toAbsolutePath().normalize())) {
                throw outputInvalid("VMM output root is unsafe", null);
            }
            candidate = realRoot.resolve(fileName).normalize();
            if (Files.isSymbolicLink(candidate)) {
                Files.deleteIfExists(candidate);
                throw outputInvalid("VMM output file failed containment validation", null);
            }
            if (!candidate.startsWith(realRoot)
                    || candidate.getParent() == null || !candidate.getParent().equals(realRoot)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw outputInvalid("VMM output file failed containment validation", null);
            }
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)
                    || !realCandidate.getParent().equals(realRoot)) {
                throw outputInvalid("VMM output file failed containment validation", null);
            }
            if (Files.size(candidate) > properties.getMaxAudioOutputBytes()) {
                Files.deleteIfExists(candidate);
                throw outputInvalid("VMM output exceeds the configured byte limit", null);
            }
        } catch (ProviderExecutionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw outputInvalid("VMM output could not be inspected", exception);
        }

        ProviderArtifact staged = null;
        ProviderExecutionException primaryFailure = null;
        try (InputStream input = Files.newInputStream(candidate)) {
            staged = stagingService.stageOutputWave(input);
            return staged;
        } catch (ProviderExecutionException exception) {
            primaryFailure = exception;
            throw exception;
        } catch (IOException exception) {
            primaryFailure = outputInvalid("VMM output could not be staged", exception);
            throw primaryFailure;
        } finally {
            try {
                Files.deleteIfExists(candidate);
            } catch (IOException cleanupFailure) {
                if (staged != null) {
                    staged.close();
                }
                if (primaryFailure == null) {
                    throw outputInvalid("VMM transient output cleanup failed", cleanupFailure);
                }
            }
        }
    }

    private ProviderExecutionException rejected(String message) {
        return new ProviderExecutionException(ProviderErrorCategory.PROVIDER_REJECTED, message);
    }

    private ProviderExecutionException outputInvalid(String message, Throwable cause) {
        return cause == null
                ? new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_OUTPUT_INVALID, message)
                : new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_OUTPUT_INVALID, message, cause);
    }
}
