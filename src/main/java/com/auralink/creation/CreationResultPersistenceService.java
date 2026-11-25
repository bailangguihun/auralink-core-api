package com.auralink.creation;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderTextOutput;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.MediaAsset;
import com.auralink.provider.artifact.AudioOutputValidator;
import com.auralink.provider.qwen.PaintingPoemResult;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepDispatchAttemptRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.service.media.GeneratedAssetRequest;
import com.auralink.service.media.MediaAssetService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreationResultPersistenceService {
   static final double MUSIC_DURATION_TOLERANCE_SECONDS = 0.05;
   private final CreationRepository creations;
   private final CreationStepRepository steps;
   private final CreationExecutionAttemptRepository executionAttempts;
   private final CreationStepDispatchAttemptRepository dispatchAttempts;
   private final MediaAssetService mediaAssets;
   private final AudioOutputValidator audioOutputValidator;
   private final PaintingPoemResultValidator poemValidator;
   private final ObjectMapper objectMapper;
   private final CreationExecutionProperties properties;
   private final Clock clock;
   private final CreationExecutionBoundaryHook boundaryHook;

   @Transactional
   public void persistPainting(
      CreationExecutionTransactionService.ClaimedCreationData creationData,
      CreationExecutionTransactionService.StepData step,
      ProviderBinaryOutput output,
      boolean terminal
   ) {
      if (output != null && output.artifact() != null) {
         Creation creation = this.creations
            .findByIdAndStatusAndClaimToken(creationData.creationId(), CreationStatus.RUNNING.name(), creationData.claimToken())
            .orElseThrow(ClaimOwnershipLostException::new);
         CreationExecutionAttempt executionAttempt = this.requireActiveAttempt(creationData.creationId());
         MediaAsset asset = this.mediaAssets
            .storeGeneratedAsset(
               new GeneratedAssetRequest(
                  creation.getUser(),
                  output.artifact().openStream(),
                  "generated-painting." + output.artifact().fileExtension(),
                  output.mimeType(),
                  "IMAGE",
                  "GENERATED_PAINTING",
                  null
               )
            );
         this.boundaryHook.reached(CreationExecutionBoundary.MANAGED_FILE_BEFORE_DB_COMMIT);
         LocalDateTime now = LocalDateTime.now(this.clock);
         if (this.steps.persistImageSuccess(step.stepId(), creationData.creationId(), creationData.claimToken(), asset.getId(), now) != 1) {
            throw new ClaimOwnershipLostException();
         }

         if (this.dispatchAttempts
               .persistPaintingResult(
                  step.stepId(), executionAttempt.getId(), creationData.creationId(), creationData.claimToken(), asset.getId(), output.sha256(), now
               )
            != 1) {
            throw new ClaimOwnershipLostException();
         }

         if (terminal) {
            this.boundaryHook.reached(CreationExecutionBoundary.BEFORE_TERMINAL_CREATION_MUTATION);
            if (this.creations.completePainting(creationData.creationId(), creationData.claimToken(), asset.getId(), now) != 1) {
               throw new ClaimOwnershipLostException();
            }

            this.finishExecutionAttempt(executionAttempt, CreationStatus.SUCCEEDED.name(), now);
         } else if (this.creations.refreshLease(creationData.creationId(), creationData.claimToken(), now.plus(this.properties.getLeaseDuration()), now) != 1) {
            throw new ClaimOwnershipLostException();
         }
      } else {
         throw new IllegalArgumentException("Validated painting output is required");
      }
   }

   @Transactional
   public void persistPoem(
      CreationExecutionTransactionService.ClaimedCreationData creationData,
      CreationExecutionTransactionService.StepData step,
      ProviderTextOutput output,
      boolean terminal
   ) {
      String canonicalPoem = this.canonicalPoem(output);
      LocalDateTime now = LocalDateTime.now(this.clock);
      CreationExecutionAttempt executionAttempt = this.requireActiveAttempt(creationData.creationId());
      if (this.steps.persistPoemSuccess(step.stepId(), creationData.creationId(), creationData.claimToken(), canonicalPoem, now) != 1) {
         throw new ClaimOwnershipLostException();
      }

      if (this.dispatchAttempts
            .persistPoemResult(step.stepId(), executionAttempt.getId(), creationData.creationId(), creationData.claimToken(), this.sha256(canonicalPoem), now)
         != 1) {
         throw new ClaimOwnershipLostException();
      }

      if (terminal) {
         this.boundaryHook.reached(CreationExecutionBoundary.BEFORE_TERMINAL_CREATION_MUTATION);
         if (this.creations.completePoem(creationData.creationId(), creationData.claimToken(), canonicalPoem, now) != 1) {
            throw new ClaimOwnershipLostException();
         }

         this.finishExecutionAttempt(executionAttempt, CreationStatus.SUCCEEDED.name(), now);
      } else if (this.creations.refreshLease(creationData.creationId(), creationData.claimToken(), now.plus(this.properties.getLeaseDuration()), now) != 1) {
         throw new ClaimOwnershipLostException();
      }
   }

   @Transactional
   public void persistMusic(
      CreationExecutionTransactionService.ClaimedCreationData creationData,
      CreationExecutionTransactionService.StepData step,
      ProviderBinaryOutput output,
      int durationSeconds,
      boolean terminal
   ) {
      if (output != null && output.artifact() != null && durationSeconds >= 3 && durationSeconds <= 30) {
         double measuredDurationSeconds = this.requireMatchingMusicDuration(output, durationSeconds);
         Creation creation = this.creations
            .findByIdAndStatusAndClaimToken(creationData.creationId(), CreationStatus.RUNNING.name(), creationData.claimToken())
            .orElseThrow(ClaimOwnershipLostException::new);
         CreationExecutionAttempt executionAttempt = this.requireActiveAttempt(creationData.creationId());
         MediaAsset asset = this.mediaAssets
            .storeGeneratedAsset(
               new GeneratedAssetRequest(
                  creation.getUser(),
                  output.artifact().openStream(),
                  "generated-music." + output.artifact().fileExtension(),
                  output.mimeType(),
                  "AUDIO",
                  "MUSIC",
                  measuredDurationSeconds
               )
            );
         this.boundaryHook.reached(CreationExecutionBoundary.MANAGED_FILE_BEFORE_DB_COMMIT);
         LocalDateTime now = LocalDateTime.now(this.clock);
         if (this.steps.persistMusicSuccess(step.stepId(), creationData.creationId(), creationData.claimToken(), asset.getId(), now) != 1) {
            throw new ClaimOwnershipLostException();
         }

         if (this.dispatchAttempts
               .persistMusicResult(
                  step.stepId(), executionAttempt.getId(), creationData.creationId(), creationData.claimToken(), asset.getId(), output.sha256(), now
               )
            != 1) {
            throw new ClaimOwnershipLostException();
         }

         if (terminal) {
            this.boundaryHook.reached(CreationExecutionBoundary.BEFORE_TERMINAL_CREATION_MUTATION);
            if (this.creations.completeMusic(creationData.creationId(), creationData.claimToken(), asset.getId(), now) != 1) {
               throw new ClaimOwnershipLostException();
            }

            this.finishExecutionAttempt(executionAttempt, CreationStatus.SUCCEEDED.name(), now);
         } else if (this.creations.refreshLease(creationData.creationId(), creationData.claimToken(), now.plus(this.properties.getLeaseDuration()), now) != 1) {
            throw new ClaimOwnershipLostException();
         }
      } else {
         throw new IllegalArgumentException("Validated music output and duration are required");
      }
   }

   private double requireMatchingMusicDuration(ProviderBinaryOutput output, int requestedDurationSeconds) {
      AudioOutputValidator.WaveMetadata wave;
      try (InputStream input = output.artifact().openStream()) {
         wave = this.audioOutputValidator.measureWave(input);
      } catch (IOException exception) {
         throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID, "Generated music could not be measured", exception);
      }

      double actualDurationSeconds = wave.durationSeconds();
      if (Math.abs(actualDurationSeconds - requestedDurationSeconds) > 0.05) {
         throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID, "Generated music duration does not match the requested duration");
      } else {
         return actualDurationSeconds;
      }
   }

   private String canonicalPoem(ProviderTextOutput output) {
      if (output == null) {
         throw new IllegalArgumentException("Validated poem output is required");
      }

      try {
         LinkedHashMap<String, Object> source = new LinkedHashMap<>();
         source.put("schemaVersion", output.schemaVersion());
         source.put("title", output.title());
         source.put("lines", output.lines());
         source.put("text", output.text());
         PaintingPoemResult poem = this.poemValidator.validateGenerated(this.objectMapper.writeValueAsString(source));
         LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
         canonical.put("schemaVersion", poem.schemaVersion());
         canonical.put("title", poem.title());
         canonical.put("lines", poem.lines());
         canonical.put("text", poem.text());
         return this.objectMapper.writeValueAsString(canonical);
      } catch (JsonProcessingException exception) {
         throw new IllegalArgumentException("Poem output cannot be encoded safely", exception);
      }
   }

   private CreationExecutionAttempt requireActiveAttempt(Long creationId) {
      return this.executionAttempts.findByCreationIdAndFinishedAtIsNull(creationId).orElseThrow(ClaimOwnershipLostException::new);
   }

   private void finishExecutionAttempt(CreationExecutionAttempt executionAttempt, String resolutionCode, LocalDateTime now) {
      executionAttempt.setFinishedAt(now);
      executionAttempt.setResolutionCode(resolutionCode);
      this.executionAttempts.save(executionAttempt);
   }

   private String sha256(String value) {
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
         return HexFormat.of().formatHex(digest);
      } catch (NoSuchAlgorithmException exception) {
         throw new IllegalStateException("SHA-256 is unavailable", exception);
      }
   }

   public CreationResultPersistenceService(
      final CreationRepository creations,
      final CreationStepRepository steps,
      final CreationExecutionAttemptRepository executionAttempts,
      final CreationStepDispatchAttemptRepository dispatchAttempts,
      final MediaAssetService mediaAssets,
      final AudioOutputValidator audioOutputValidator,
      final PaintingPoemResultValidator poemValidator,
      final ObjectMapper objectMapper,
      final CreationExecutionProperties properties,
      final Clock clock,
      final CreationExecutionBoundaryHook boundaryHook
   ) {
      this.creations = creations;
      this.steps = steps;
      this.executionAttempts = executionAttempts;
      this.dispatchAttempts = dispatchAttempts;
      this.mediaAssets = mediaAssets;
      this.audioOutputValidator = audioOutputValidator;
      this.poemValidator = poemValidator;
      this.objectMapper = objectMapper;
      this.properties = properties;
      this.clock = clock;
      this.boundaryHook = boundaryHook;
   }
}
