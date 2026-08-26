package com.auralink.creation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import com.auralink.api.v1.creation.CreationRetryRequest;
import com.auralink.api.v1.creation.CreationRetryResponse;
import com.auralink.api.v1.creation.CreationTimestampFormatter;
import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.CreationStep;
import com.auralink.entity.User;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.service.CurrentUserService;

import lombok.RequiredArgsConstructor;

/** Owner-only, provider-free retry admission for proven-safe terminal Creation work. */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CreationRetryService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");

    private final CreationFeatureGuard featureGuard;
    private final CurrentUserService currentUserService;
    private final CreationRepository creations;
    private final CreationStepRepository steps;
    private final CreationExecutionAttemptRepository executionAttempts;
    private final CreationRetryEligibilityService eligibility;
    private final CreationStateMachine stateMachine;
    private final Clock clock;

    /** Compatibility constructor for existing unit tests; Spring injects the shared UTC clock. */
    public CreationRetryService(
            CreationFeatureGuard featureGuard,
            CurrentUserService currentUserService,
            CreationRepository creations,
            CreationStepRepository steps,
            CreationExecutionAttemptRepository executionAttempts,
            CreationRetryEligibilityService eligibility,
            CreationStateMachine stateMachine) {
        this(featureGuard, currentUserService, creations, steps, executionAttempts, eligibility, stateMachine,
                Clock.systemUTC());
    }

    @Transactional
    public RetryAdmission retry(String creationId, String idempotencyKey, CreationRetryRequest request) {
        int expectedVersion = requireExpectedVersion(request);
        String digest = digestIdempotencyKey(idempotencyKey);
        User owner = currentUserService.requireCurrentUser();
        Creation creation = findOwned(creationId, owner);

        Optional<CreationExecutionAttempt> existing = executionAttempts
                .findByCreationIdAndRetryIdempotencyKeyDigest(creation.getId(), digest);
        if (existing.isPresent()) {
            CreationExecutionAttempt attempt = existing.get();
            if (attempt.getAttemptNumber() != expectedVersion + 2) {
                throw conflict(ApiErrorCode.CREATION_RETRY_IDEMPOTENCY_CONFLICT, "创作重试幂等请求不一致");
            }
            return new RetryAdmission(response(creation.getPublicId(), expectedVersion + 1, attempt, true), true);
        }
        // A different key must not turn a stale optimistic-concurrency request
        // into a generic state error after another retry already won.
        if (creation.getRetryVersion() != expectedVersion) {
            throw conflict(ApiErrorCode.CREATION_RETRY_VERSION_CONFLICT, "创作重试版本已变化");
        }

        List<CreationStep> creationSteps = steps.findByCreationIdOrderByStepIndexAsc(creation.getId());
        CreationRetryEligibilityService.RetryAssessment assessment = eligibility.assess(creation, creationSteps);
        if (!assessment.available()) {
            throw blocked(assessment.blockedReason());
        }
        stateMachine.requireRetryTransition(CreationStatus.valueOf(creation.getStatus()), CreationStatus.QUEUED);

        LocalDateTime now = LocalDateTime.now(clock);
        if (creations.retrySafely(creation.getId(), owner.getId(), expectedVersion, now) != 1) {
            Optional<CreationExecutionAttempt> raced = executionAttempts
                    .findByCreationIdAndRetryIdempotencyKeyDigest(creation.getId(), digest);
            if (raced.isPresent() && raced.get().getAttemptNumber() == expectedVersion + 2) {
                return new RetryAdmission(response(creation.getPublicId(), expectedVersion + 1, raced.get(), true), true);
            }
            throw conflict(ApiErrorCode.CREATION_RETRY_VERSION_CONFLICT, "创作重试版本已变化");
        }
        int reset = steps.resetForSafeRetry(creation.getId(), assessment.boundaryIndex());
        if (reset != creationSteps.size() - assessment.boundaryIndex()) {
            throw new IllegalStateException("Creation retry state changed during admission");
        }

        Creation updated = creations.findById(creation.getId()).orElseThrow(CreationRetryService::notFound);
        CreationExecutionAttempt attempt = executionAttempts.save(CreationExecutionAttempt.builder()
                .creation(updated)
                .attemptNumber(expectedVersion + 2)
                .retryIdempotencyKeyDigest(digest)
                .admittedAt(now)
                .build());
        return new RetryAdmission(response(updated.getPublicId(), updated.getRetryVersion(), attempt, false), false);
    }

    private int requireExpectedVersion(CreationRetryRequest request) {
        if (request == null || request.getExpectedRetryVersion() == null
                || request.getExpectedRetryVersion() < 0
                || !request.unknownFields().isEmpty()
                || request.getExpectedRetryVersion() > Integer.MAX_VALUE - 2) {
            throw new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "创作重试请求无效");
        }
        return request.getExpectedRetryVersion();
    }

    private String digestIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "幂等请求键无效");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ApiV1Exception blocked(String reason) {
        if (CreationRetryEligibilityService.FEATURE_DISABLED.equals(reason)) {
            featureGuard.requireEnabled();
        }
        return switch (reason) {
            case CreationRetryEligibilityService.AMBIGUOUS -> conflict(
                    ApiErrorCode.CREATION_RETRY_DISPATCH_AMBIGUOUS, "创作请求状态存在外部执行歧义");
            case CreationRetryEligibilityService.INCONSISTENT -> conflict(
                    ApiErrorCode.CREATION_DATA_INCONSISTENT, "创作状态不一致，无法安全重试");
            case CreationRetryEligibilityService.NOT_AVAILABLE -> conflict(
                    ApiErrorCode.CREATION_RETRY_NOT_AVAILABLE, "创作当前不可重试");
            default -> conflict(ApiErrorCode.CREATION_RETRY_NOT_AVAILABLE, "创作当前不可重试");
        };
    }

    private Creation findOwned(String creationId, User owner) {
        String canonicalId;
        try {
            canonicalId = UUID.fromString(creationId).toString();
            if (!canonicalId.equals(creationId)) {
                throw notFound();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw notFound();
        }
        return creations.findByPublicIdAndUserId(canonicalId, owner.getId())
                .orElseThrow(CreationRetryService::notFound);
    }

    private CreationRetryResponse response(
            String creationId,
            int retryVersion,
            CreationExecutionAttempt attempt,
            boolean idempotentReplay) {
        return new CreationRetryResponse(
                creationId,
                CreationStatus.QUEUED,
                retryVersion,
                attempt.getAttemptNumber(),
                CreationTimestampFormatter.format(attempt.getAdmittedAt()),
                idempotentReplay);
    }

    private static ApiV1Exception conflict(ApiErrorCode code, String message) {
        return new ApiV1Exception(HttpStatus.CONFLICT, code, message);
    }

    private static ApiV1Exception notFound() {
        return new ApiV1Exception(HttpStatus.NOT_FOUND, ApiErrorCode.CREATION_NOT_FOUND, "创作不存在");
    }

    public record RetryAdmission(CreationRetryResponse response, boolean idempotentReplay) {
    }
}
