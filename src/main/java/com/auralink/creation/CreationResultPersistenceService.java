package com.auralink.creation;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderTextOutput;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.MediaAsset;
import com.auralink.media.MediaAssetValues;
import com.auralink.provider.qwen.PaintingPoemResult;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.CreationStepDispatchAttemptRepository;
import com.auralink.service.media.GeneratedAssetRequest;
import com.auralink.service.media.MediaAssetService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Stores only validated terminal/intermediate provider outputs.  Image bytes,
 * MediaAsset registration, step state and Creation state share one transaction
 * so a failed terminal update rolls the managed file back through the existing
 * MediaAsset rollback hook.
 */
@Service
@RequiredArgsConstructor
public class CreationResultPersistenceService {

    private final CreationRepository creations;
    private final CreationStepRepository steps;
    private final CreationExecutionAttemptRepository executionAttempts;
    private final CreationStepDispatchAttemptRepository dispatchAttempts;
    private final MediaAssetService mediaAssets;
    private final PaintingPoemResultValidator poemValidator;
    private final ObjectMapper objectMapper;
    private final com.auralink.config.properties.CreationExecutionProperties properties;
    private final Clock clock;
    private final CreationExecutionBoundaryHook boundaryHook;

    @Transactional
    public void persistPainting(
            CreationExecutionTransactionService.ClaimedCreationData creationData,
            CreationExecutionTransactionService.StepData step,
            ProviderBinaryOutput output,
            boolean terminal) {
        if (output == null || output.artifact() == null) {
            throw new IllegalArgumentException("Validated painting output is required");
        }
        Creation creation = creations.findByIdAndStatusAndClaimToken(
                        creationData.creationId(), CreationStatus.RUNNING.name(), creationData.claimToken())
                .orElseThrow(ClaimOwnershipLostException::new);
        CreationExecutionAttempt executionAttempt = requireActiveAttempt(creationData.creationId());
        MediaAsset asset = mediaAssets.storeGeneratedAsset(new GeneratedAssetRequest(
                creation.getUser(),
                output.artifact().openStream(),
                "generated-painting." + output.artifact().fileExtension(),
                output.mimeType(),
                MediaAssetValues.AssetType.IMAGE,
                MediaAssetValues.SemanticType.GENERATED_PAINTING,
                null));
        // This is intentionally inside the short persistence transaction; the
        // harness timeout is bounded and a hard kill demonstrates rollback.
        boundaryHook.reached(CreationExecutionBoundary.MANAGED_FILE_BEFORE_DB_COMMIT);
        LocalDateTime now = LocalDateTime.now(clock);
        if (steps.persistImageSuccess(
                step.stepId(), creationData.creationId(), creationData.claimToken(), asset.getId(), now) != 1) {
            throw new ClaimOwnershipLostException();
        }
        if (dispatchAttempts.persistPaintingResult(
                step.stepId(), executionAttempt.getId(), creationData.creationId(), creationData.claimToken(),
                asset.getId(), output.sha256(), now) != 1) {
            throw new ClaimOwnershipLostException();
        }
        if (terminal) {
            boundaryHook.reached(CreationExecutionBoundary.BEFORE_TERMINAL_CREATION_MUTATION);
            if (creations.completePainting(
                    creationData.creationId(), creationData.claimToken(), asset.getId(), now) != 1) {
                throw new ClaimOwnershipLostException();
            }
            finishExecutionAttempt(executionAttempt, CreationStatus.SUCCEEDED.name(), now);
        } else if (creations.refreshLease(
                creationData.creationId(), creationData.claimToken(),
                now.plus(properties.getLeaseDuration()), now) != 1) {
            throw new ClaimOwnershipLostException();
        }
    }

    @Transactional
    public void persistPoem(
            CreationExecutionTransactionService.ClaimedCreationData creationData,
            CreationExecutionTransactionService.StepData step,
            ProviderTextOutput output,
            boolean terminal) {
        String canonicalPoem = canonicalPoem(output);
        LocalDateTime now = LocalDateTime.now(clock);
        CreationExecutionAttempt executionAttempt = requireActiveAttempt(creationData.creationId());
        if (steps.persistPoemSuccess(
                step.stepId(), creationData.creationId(), creationData.claimToken(), canonicalPoem, now) != 1) {
            throw new ClaimOwnershipLostException();
        }
        if (dispatchAttempts.persistPoemResult(
                step.stepId(), executionAttempt.getId(), creationData.creationId(), creationData.claimToken(),
                sha256(canonicalPoem), now) != 1) {
            throw new ClaimOwnershipLostException();
        }
        if (terminal) {
            boundaryHook.reached(CreationExecutionBoundary.BEFORE_TERMINAL_CREATION_MUTATION);
            if (creations.completePoem(
                    creationData.creationId(), creationData.claimToken(), canonicalPoem, now) != 1) {
                throw new ClaimOwnershipLostException();
            }
            finishExecutionAttempt(executionAttempt, CreationStatus.SUCCEEDED.name(), now);
        } else if (creations.refreshLease(
                creationData.creationId(), creationData.claimToken(),
                now.plus(properties.getLeaseDuration()), now) != 1) {
            throw new ClaimOwnershipLostException();
        }
    }

    /** Re-validates the exact four-field contract immediately before persistence. */
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
            PaintingPoemResult poem = poemValidator.validate(objectMapper.writeValueAsString(source));
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("schemaVersion", poem.schemaVersion());
            canonical.put("title", poem.title());
            canonical.put("lines", poem.lines());
            canonical.put("text", poem.text());
            return objectMapper.writeValueAsString(canonical);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Poem output cannot be encoded safely", exception);
        }
    }

    private CreationExecutionAttempt requireActiveAttempt(Long creationId) {
        return executionAttempts.findByCreationIdAndFinishedAtIsNull(creationId)
                .orElseThrow(ClaimOwnershipLostException::new);
    }

    private void finishExecutionAttempt(
            CreationExecutionAttempt executionAttempt,
            String resolutionCode,
            LocalDateTime now) {
        executionAttempt.setFinishedAt(now);
        executionAttempt.setResolutionCode(resolutionCode);
        executionAttempts.save(executionAttempt);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
