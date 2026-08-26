package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.auralink.api.v1.creation.CreationRetryRequest;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.CreationStep;
import com.auralink.entity.User;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.service.CurrentUserService;

class CreationRetryServiceTest {

    private static final String CREATION_ID = "00000000-0000-0000-0000-000000000321";
    private static final String IDEMPOTENCY_KEY = "retry-key-0000000000000001";

    @Test
    void admitsSafeRetryWithoutInvokingAProviderAndCreatesAttemptTwo() {
        CurrentUserService users = mock(CurrentUserService.class);
        CreationRepository creations = mock(CreationRepository.class);
        CreationStepRepository steps = mock(CreationStepRepository.class);
        CreationExecutionAttemptRepository attempts = mock(CreationExecutionAttemptRepository.class);
        CreationRetryEligibilityService eligibility = mock(CreationRetryEligibilityService.class);
        CreationFeatureGuard guard = mock(CreationFeatureGuard.class);
        CreationStateMachine stateMachine = mock(CreationStateMachine.class);
        User owner = owner();
        Creation creation = creation(owner);
        CreationStep failed = CreationStep.builder().stepIndex(0).status("FAILED").build();

        when(users.requireCurrentUser()).thenReturn(owner);
        when(creations.findByPublicIdAndUserId(CREATION_ID, owner.getId())).thenReturn(Optional.of(creation));
        when(attempts.findByCreationIdAndRetryIdempotencyKeyDigest(
                eq(creation.getId()), any(String.class))).thenReturn(Optional.empty());
        when(steps.findByCreationIdOrderByStepIndexAsc(creation.getId())).thenReturn(List.of(failed));
        when(eligibility.assess(creation, List.of(failed)))
                .thenReturn(CreationRetryEligibilityService.RetryAssessment.available(0));
        when(creations.retrySafely(
                eq(creation.getId()), eq(owner.getId()), eq(0), any(java.time.LocalDateTime.class)))
                .thenReturn(1);
        when(steps.resetForSafeRetry(creation.getId(), 0)).thenReturn(1);
        Creation updated = creation(owner);
        updated.setRetryVersion(1);
        when(creations.findById(creation.getId())).thenReturn(Optional.of(updated));
        when(attempts.save(any(CreationExecutionAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreationRetryService service = new CreationRetryService(
                guard, users, creations, steps, attempts, eligibility, stateMachine);
        CreationRetryService.RetryAdmission admission = service.retry(CREATION_ID, IDEMPOTENCY_KEY, request(0));

        assertThat(admission.idempotentReplay()).isFalse();
        assertThat(admission.response().executionAttemptNumber()).isEqualTo(2);
        assertThat(admission.response().retryVersion()).isEqualTo(1);
        verify(attempts).save(any(CreationExecutionAttempt.class));
        verify(creations).retrySafely(
                eq(creation.getId()), eq(owner.getId()), eq(0), any(java.time.LocalDateTime.class));
    }

    @Test
    void returnsOriginalResponseForExactDuplicateWithoutMutatingState() throws Exception {
        CurrentUserService users = mock(CurrentUserService.class);
        CreationRepository creations = mock(CreationRepository.class);
        CreationStepRepository steps = mock(CreationStepRepository.class);
        CreationExecutionAttemptRepository attempts = mock(CreationExecutionAttemptRepository.class);
        CreationRetryEligibilityService eligibility = mock(CreationRetryEligibilityService.class);
        CreationFeatureGuard guard = mock(CreationFeatureGuard.class);
        CreationStateMachine stateMachine = mock(CreationStateMachine.class);
        User owner = owner();
        Creation creation = creation(owner);
        CreationExecutionAttempt existing = CreationExecutionAttempt.builder()
                .attemptNumber(2).admittedAt(java.time.LocalDateTime.of(2026, 8, 22, 8, 0)).build();

        when(users.requireCurrentUser()).thenReturn(owner);
        when(creations.findByPublicIdAndUserId(CREATION_ID, owner.getId())).thenReturn(Optional.of(creation));
        when(attempts.findByCreationIdAndRetryIdempotencyKeyDigest(creation.getId(), digest(IDEMPOTENCY_KEY)))
                .thenReturn(Optional.of(existing));

        CreationRetryService.RetryAdmission admission = new CreationRetryService(
                guard, users, creations, steps, attempts, eligibility, stateMachine)
                .retry(CREATION_ID, IDEMPOTENCY_KEY, request(0));

        assertThat(admission.idempotentReplay()).isTrue();
        assertThat(admission.response().executionAttemptNumber()).isEqualTo(2);
        verify(creations, never()).retrySafely(anyLong(), anyLong(), anyInt(), any());
        verify(attempts, never()).save(any());
    }

    @Test
    void blocksAmbiguousDispatchWithoutResettingSteps() {
        CurrentUserService users = mock(CurrentUserService.class);
        CreationRepository creations = mock(CreationRepository.class);
        CreationStepRepository steps = mock(CreationStepRepository.class);
        CreationExecutionAttemptRepository attempts = mock(CreationExecutionAttemptRepository.class);
        CreationRetryEligibilityService eligibility = mock(CreationRetryEligibilityService.class);
        CreationFeatureGuard guard = mock(CreationFeatureGuard.class);
        CreationStateMachine stateMachine = mock(CreationStateMachine.class);
        User owner = owner();
        Creation creation = creation(owner);
        CreationStep failed = CreationStep.builder().stepIndex(0).status("FAILED").build();

        when(users.requireCurrentUser()).thenReturn(owner);
        when(creations.findByPublicIdAndUserId(CREATION_ID, owner.getId())).thenReturn(Optional.of(creation));
        when(attempts.findByCreationIdAndRetryIdempotencyKeyDigest(anyLong(), any())).thenReturn(Optional.empty());
        when(steps.findByCreationIdOrderByStepIndexAsc(creation.getId())).thenReturn(List.of(failed));
        when(eligibility.assess(creation, List.of(failed))).thenReturn(
                CreationRetryEligibilityService.RetryAssessment.blocked(
                        CreationRetryEligibilityService.AMBIGUOUS));

        ApiV1Exception error = org.junit.jupiter.api.Assertions.assertThrows(ApiV1Exception.class,
                () -> new CreationRetryService(
                        guard, users, creations, steps, attempts, eligibility, stateMachine)
                        .retry(CREATION_ID, IDEMPOTENCY_KEY, request(0)));

        assertThat(error.getCode().name()).isEqualTo("CREATION_RETRY_DISPATCH_AMBIGUOUS");
        verify(steps, never()).resetForSafeRetry(anyLong(), anyInt());
        verify(creations, never()).retrySafely(anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    void rejectsUnsafeIdempotencyKeysBeforeAnyRetryStateIsReadOrMutated() {
        CurrentUserService users = mock(CurrentUserService.class);
        CreationRepository creations = mock(CreationRepository.class);
        CreationStepRepository steps = mock(CreationStepRepository.class);
        CreationExecutionAttemptRepository attempts = mock(CreationExecutionAttemptRepository.class);
        CreationRetryEligibilityService eligibility = mock(CreationRetryEligibilityService.class);
        CreationFeatureGuard guard = mock(CreationFeatureGuard.class);
        CreationStateMachine stateMachine = mock(CreationStateMachine.class);

        ApiV1Exception error = org.junit.jupiter.api.Assertions.assertThrows(ApiV1Exception.class,
                () -> new CreationRetryService(
                        guard, users, creations, steps, attempts, eligibility, stateMachine)
                        .retry(CREATION_ID, "unsafe/key", request(0)));

        assertThat(error.getCode().name()).isEqualTo("BAD_REQUEST");
        verify(users, never()).requireCurrentUser();
        verify(creations, never()).retrySafely(anyLong(), anyLong(), anyInt(), any());
        verify(attempts, never()).save(any());
    }

    private static CreationRetryRequest request(int version) {
        CreationRetryRequest request = new CreationRetryRequest();
        request.setExpectedRetryVersion(version);
        return request;
    }

    private static User owner() {
        User user = User.builder().username("retry-owner").password("x").fullName("Retry Owner")
                .email("retry@example.invalid").role("ROLE_USER").build();
        user.setId(12L);
        return user;
    }

    private static Creation creation(User owner) {
        Creation creation = Creation.builder().user(owner).status("FAILED").retryVersion(0).build();
        creation.setId(32L);
        creation.setPublicId(CREATION_ID);
        return creation;
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
