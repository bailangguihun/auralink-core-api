package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.CreationStep;
import com.auralink.entity.CreationStepDispatchAttempt;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.CreationStepDispatchAttemptRepository;

class CreationExecutionTransactionServiceTest {

    @Test
    void createsNotSentDispatchEvidenceWhenStepStartsAndKeepsItInTheSameExecutionAttempt() {
        CreationRepository creations = mock(CreationRepository.class);
        CreationStepRepository steps = mock(CreationStepRepository.class);
        CreationExecutionAttemptRepository attempts = mock(CreationExecutionAttemptRepository.class);
        CreationStepDispatchAttemptRepository dispatches = mock(CreationStepDispatchAttemptRepository.class);
        CreationExecutionAttempt executionAttempt = CreationExecutionAttempt.builder().attemptNumber(1).build();
        executionAttempt.setId(91L);
        CreationStep step = CreationStep.builder().status("RUNNING").build();
        step.setId(42L);
        when(steps.startPending(eq(42L), eq(7L), eq("claim"), any())).thenReturn(1);
        when(attempts.findByCreationIdAndFinishedAtIsNull(7L)).thenReturn(Optional.of(executionAttempt));
        when(steps.findById(42L)).thenReturn(Optional.of(step));
        when(creations.refreshLease(eq(7L), eq("claim"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);

        boolean started = new CreationExecutionTransactionService(
                creations, steps, attempts, dispatches, new CreationExecutionProperties())
                .startPendingStep(7L, "claim", 42L);

        assertThat(started).isTrue();
        verify(dispatches).save(org.mockito.ArgumentMatchers.argThat((CreationStepDispatchAttempt attempt) ->
                attempt.getCreationStep() == step
                        && attempt.getCreationExecutionAttempt() == executionAttempt
                        && "NOT_SENT".equals(attempt.getDispatchState())));
    }

    @Test
    void conditionalClaimUsesOldestQueuedCandidateAndDoesNotLoadThenSave() {
        CreationRepository creations = mock(CreationRepository.class);
        CreationStepRepository steps = mock(CreationStepRepository.class);
        CreationExecutionAttemptRepository attempts = mock(CreationExecutionAttemptRepository.class);
        CreationStepDispatchAttemptRepository dispatches = mock(CreationStepDispatchAttemptRepository.class);
        Creation candidate = mock(Creation.class);
        when(candidate.getId()).thenReturn(42L);
        when(creations.findFirstByStatusOrderByCreatedAtAscIdAsc("QUEUED"))
                .thenReturn(Optional.of(candidate));
        when(creations.claimQueued(eq(42L), any(), any(), any())).thenReturn(1);

        var service = new CreationExecutionTransactionService(
                creations, steps, attempts, dispatches, new CreationExecutionProperties());

        Optional<CreationExecutionTransactionService.ClaimedCreation> claimed = service.claimOldestQueued();

        assertThat(claimed).isPresent();
        assertThat(claimed.get().id()).isEqualTo(42L);
        assertThat(claimed.get().claimToken()).matches("[0-9a-f-]{36}");
        verify(creations).claimQueued(eq(42L), any(), any(), any());
        verify(creations, never()).save(any());
    }

    @Test
    void marksProjectionAndImmutableDispatchAttemptWithTheSameFreshRequestKey() {
        CreationRepository creations = mock(CreationRepository.class);
        CreationStepRepository steps = mock(CreationStepRepository.class);
        CreationExecutionAttemptRepository attempts = mock(CreationExecutionAttemptRepository.class);
        CreationStepDispatchAttemptRepository dispatches = mock(CreationStepDispatchAttemptRepository.class);
        CreationExecutionAttempt executionAttempt = CreationExecutionAttempt.builder().attemptNumber(1).build();
        executionAttempt.setId(91L);
        when(attempts.findByCreationIdAndFinishedAtIsNull(7L)).thenReturn(Optional.of(executionAttempt));
        when(steps.markSendStarted(eq(42L), eq(7L), eq("claim"), any())).thenReturn(1);
        when(dispatches.markSendStarted(eq(42L), eq(91L), eq(7L), eq("claim"), any(), any())).thenReturn(1);
        when(creations.refreshLease(eq(7L), eq("claim"), any(), any())).thenReturn(1);

        var requestKey = new CreationExecutionTransactionService(
                creations, steps, attempts, dispatches, new CreationExecutionProperties())
                .markSendStarted(7L, "claim", 42L);

        assertThat(requestKey).isPresent();
        ArgumentCaptor<String> projectionKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ledgerKey = ArgumentCaptor.forClass(String.class);
        verify(steps).markSendStarted(eq(42L), eq(7L), eq("claim"), projectionKey.capture());
        verify(dispatches).markSendStarted(
                eq(42L), eq(91L), eq(7L), eq("claim"), ledgerKey.capture(), any());
        assertThat(projectionKey.getValue()).isEqualTo(requestKey.get());
        assertThat(ledgerKey.getValue()).isEqualTo(requestKey.get());
    }

    @Test
    void failedConcurrentConditionalClaimDoesNotClaimAnotherCreationInTheSameCycle() {
        CreationRepository creations = mock(CreationRepository.class);
        CreationStepRepository steps = mock(CreationStepRepository.class);
        CreationExecutionAttemptRepository attempts = mock(CreationExecutionAttemptRepository.class);
        CreationStepDispatchAttemptRepository dispatches = mock(CreationStepDispatchAttemptRepository.class);
        Creation candidate = mock(Creation.class);
        when(candidate.getId()).thenReturn(42L);
        when(creations.findFirstByStatusOrderByCreatedAtAscIdAsc("QUEUED"))
                .thenReturn(Optional.of(candidate));
        when(creations.claimQueued(eq(42L), any(), any(), any())).thenReturn(0);

        Optional<CreationExecutionTransactionService.ClaimedCreation> claimed =
                new CreationExecutionTransactionService(
                        creations, steps, attempts, dispatches, new CreationExecutionProperties()).claimOldestQueued();

        assertThat(claimed).isEmpty();
        verify(creations).claimQueued(eq(42L), any(), any(), any());
    }
}
