package com.auralink.provider.vmm;

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
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "auralink.creation-providers", name = "mock-adapters-enabled", havingValue = "false", matchIfMissing = true)
public class AuralinkVmmProviderAdapter implements CreationProviderAdapter {
   public static final String PROVIDER_CODE = "auralink-vmm";
   public static final int DEFAULT_DURATION_SECONDS = 10;
   private static final Pattern SAFE_WAVE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,126}\\.wav");
   private static final ProviderAdapterBinding BINDING = new ProviderAdapterBinding(
      WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm", WorkflowModality.PAINTING, WorkflowModality.AUDIO
   );
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
      return this.endpointPolicy.readiness();
   }

   @Override
   public ProviderExecutionResult execute(ProviderExecutionRequest request) {
      if (request != null
         && request.operation() == WorkflowOperation.PAINTING_TO_MUSIC
         && "auralink-vmm".equals(request.providerCode())
         && request.input() instanceof ProviderImageInput imageInput
         && imageInput.modality() == WorkflowModality.PAINTING) {
         ProviderArtifact source = this.inputValidator.validateImage(imageInput);
         this.stagingService.prepare();
         Path outputRoot = this.endpointPolicy.resolveOutputRoot();
         String dataUrl = this.dataUrlEncoder.encodeImage(source, this.properties.getMaxImageInputBytes());
         int durationSeconds = this.requireDuration(request.parameters());
         String fileName = this.bulkheads.execute(ProviderBulkheadKind.VMM, () -> this.client.generate(request.requestId(), dataUrl, durationSeconds, request.vmmContext()));
         ProviderArtifact output = this.stageContainedWave(outputRoot, fileName);
         return new ProviderExecutionResult(request.requestId(), request.operation(), "auralink-vmm", WorkflowModality.AUDIO, new ProviderBinaryOutput(output));
      } else {
         throw this.rejected("PAINTING_TO_MUSIC provider input is invalid");
      }
   }

   private int requireDuration(Map<String, JsonNode> parameters) {
      JsonNode duration = parameters == null ? null : parameters.get("durationSeconds");
      if (duration != null && !duration.isNull()) {
         if (duration.isIntegralNumber() && duration.canConvertToInt() && duration.intValue() >= 3 && duration.intValue() <= 30) {
            return duration.intValue();
         } else {
            throw this.rejected("PAINTING_TO_MUSIC durationSeconds must be an integer from 3 through 30");
         }
      } else {
         return 10;
      }
   }

   private ProviderArtifact stageContainedWave(Path configuredRoot, String fileName) {
      if (fileName != null && !fileName.contains("/") && !fileName.contains("\\") && !fileName.contains("..") && SAFE_WAVE_NAME.matcher(fileName).matches()) {
         Path candidate;
         try {
            if (Files.isSymbolicLink(configuredRoot) || !Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
               throw this.outputInvalid("VMM output root is unavailable", null);
            }

            Path realRoot = configuredRoot.toRealPath();
            if (!realRoot.equals(configuredRoot.toAbsolutePath().normalize())) {
               throw this.outputInvalid("VMM output root is unsafe", null);
            }

            candidate = realRoot.resolve(fileName).normalize();
            if (Files.isSymbolicLink(candidate)) {
               Files.deleteIfExists(candidate);
               throw this.outputInvalid("VMM output file failed containment validation", null);
            }

            if (!candidate.startsWith(realRoot)
               || candidate.getParent() == null
               || !candidate.getParent().equals(realRoot)
               || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
               throw this.outputInvalid("VMM output file failed containment validation", null);
            }

            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot) || !realCandidate.getParent().equals(realRoot)) {
               throw this.outputInvalid("VMM output file failed containment validation", null);
            }

            if (Files.size(candidate) > this.properties.getMaxAudioOutputBytes()) {
               Files.deleteIfExists(candidate);
               throw this.outputInvalid("VMM output exceeds the configured byte limit", null);
            }
         } catch (ProviderExecutionException exception) {
            throw exception;
         } catch (IOException exception) {
            throw this.outputInvalid("VMM output could not be inspected", exception);
         }

         ProviderArtifact staged = null;
         ProviderExecutionException primaryFailure = null;

         try (InputStream input = Files.newInputStream(candidate)) {
            return this.stagingService.stageOutputWave(input);
         } catch (ProviderExecutionException exception) {
            primaryFailure = exception;
            throw exception;
         } catch (IOException exception) {
            primaryFailure = this.outputInvalid("VMM output could not be staged", exception);
            throw primaryFailure;
         } finally {
            try {
               Files.deleteIfExists(candidate);
            } catch (IOException cleanupFailure) {
               if (staged != null) {
                  staged.close();
               }

               if (primaryFailure == null) {
                  throw this.outputInvalid("VMM transient output cleanup failed", cleanupFailure);
               }
            }
         }
      } else {
         throw this.outputInvalid("VMM returned an unsafe file name", null);
      }
   }

   private ProviderExecutionException rejected(String message) {
      return new ProviderExecutionException(ProviderErrorCategory.PROVIDER_REJECTED, message);
   }

   private ProviderExecutionException outputInvalid(String message, Throwable cause) {
      return cause == null
         ? new ProviderExecutionException(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID, message)
         : new ProviderExecutionException(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID, message, cause);
   }

   public AuralinkVmmProviderAdapter(
      final ProviderInputValidator inputValidator,
      final ProviderDataUrlEncoder dataUrlEncoder,
      final ProviderArtifactStagingService stagingService,
      final CreationProviderProperties properties,
      final VmmHttpClient client,
      final VmmEndpointPolicy endpointPolicy,
      final ProviderBulkheads bulkheads
   ) {
      this.inputValidator = inputValidator;
      this.dataUrlEncoder = dataUrlEncoder;
      this.stagingService = stagingService;
      this.properties = properties;
      this.client = client;
      this.endpointPolicy = endpointPolicy;
      this.bulkheads = bulkheads;
   }
}
