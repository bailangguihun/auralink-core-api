package com.auralink.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.auralink.entity.CreationStepDispatchAttempt;

@Repository
public interface CreationStepDispatchAttemptRepository extends JpaRepository<CreationStepDispatchAttempt, Long> {

    List<CreationStepDispatchAttempt> findByCreationStepIdOrderByIdAsc(Long creationStepId);

    Optional<CreationStepDispatchAttempt> findByCreationStepIdAndCreationExecutionAttemptId(
            Long creationStepId, Long creationExecutionAttemptId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creation_step_dispatch_attempts
            SET finished_at = :now, resolution_code = 'RECOVERY_REQUEUED_NOT_SENT'
            WHERE creation_step_id = :stepId
              AND creation_execution_attempt_id = :executionAttemptId
              AND dispatch_state = 'NOT_SENT'
              AND provider_request_key IS NULL
              AND EXISTS (SELECT 1 FROM creations c WHERE c.id = :creationId
                          AND c.status = 'RUNNING' AND c.claim_token = :recoveryToken)
            """, nativeQuery = true)
    int markRecoveryRequeuedNotSent(@Param("stepId") Long stepId,
                                    @Param("executionAttemptId") Long executionAttemptId,
                                    @Param("creationId") Long creationId,
                                    @Param("recoveryToken") String recoveryToken,
                                    @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creation_step_dispatch_attempts
            SET provider_request_key = :requestKey, dispatch_state = 'SEND_STARTED',
                dispatch_started_at = :now, finished_at = NULL,
                resolution_code = CASE WHEN resolution_code = 'RECOVERY_REQUEUED_NOT_SENT'
                    THEN 'RECOVERY_REQUEUED_NOT_SENT' ELSE NULL END
            WHERE creation_step_id = :stepId
              AND creation_execution_attempt_id = :executionAttemptId
              AND dispatch_state = 'NOT_SENT'
              AND EXISTS (
                  SELECT 1 FROM creations c WHERE c.id = :creationId
                    AND c.status = 'RUNNING' AND c.claim_token = :claimToken
              )
            """, nativeQuery = true)
    int markSendStarted(@Param("stepId") Long stepId,
                        @Param("executionAttemptId") Long executionAttemptId,
                        @Param("creationId") Long creationId,
                        @Param("claimToken") String claimToken,
                        @Param("requestKey") String requestKey,
                        @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creation_step_dispatch_attempts
            SET dispatch_state = 'RESULT_PERSISTED', finished_at = :now,
                resolution_code = CASE WHEN resolution_code = 'RECOVERY_REQUEUED_NOT_SENT'
                    THEN 'RECOVERY_REQUEUED_NOT_SENT;RESULT_PERSISTED' ELSE 'RESULT_PERSISTED' END,
                result_asset_id = :assetId,
                result_digest = :resultDigest, canonical_poem_digest = NULL
            WHERE creation_step_id = :stepId
              AND creation_execution_attempt_id = :executionAttemptId
              AND dispatch_state = 'SEND_STARTED'
              AND EXISTS (
                  SELECT 1 FROM creations c WHERE c.id = :creationId
                    AND c.status = 'RUNNING' AND c.claim_token = :claimToken
              )
            """, nativeQuery = true)
    int persistPaintingResult(@Param("stepId") Long stepId,
                              @Param("executionAttemptId") Long executionAttemptId,
                              @Param("creationId") Long creationId,
                              @Param("claimToken") String claimToken,
                              @Param("assetId") Long assetId,
                              @Param("resultDigest") String resultDigest,
                              @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creation_step_dispatch_attempts
            SET dispatch_state = 'RESULT_PERSISTED', finished_at = :now,
                resolution_code = CASE WHEN resolution_code = 'RECOVERY_REQUEUED_NOT_SENT'
                    THEN 'RECOVERY_REQUEUED_NOT_SENT;RESULT_PERSISTED' ELSE 'RESULT_PERSISTED' END,
                result_asset_id = NULL,
                result_digest = NULL, canonical_poem_digest = :poemDigest
            WHERE creation_step_id = :stepId
              AND creation_execution_attempt_id = :executionAttemptId
              AND dispatch_state = 'SEND_STARTED'
              AND EXISTS (
                  SELECT 1 FROM creations c WHERE c.id = :creationId
                    AND c.status = 'RUNNING' AND c.claim_token = :claimToken
              )
            """, nativeQuery = true)
    int persistPoemResult(@Param("stepId") Long stepId,
                           @Param("executionAttemptId") Long executionAttemptId,
                           @Param("creationId") Long creationId,
                           @Param("claimToken") String claimToken,
                           @Param("poemDigest") String poemDigest,
                           @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creation_step_dispatch_attempts
            SET finished_at = :now, resolution_code = :resolutionCode
            WHERE creation_step_id = :stepId
              AND creation_execution_attempt_id = :executionAttemptId
              AND finished_at IS NULL
              AND EXISTS (
                  SELECT 1 FROM creations c WHERE c.id = :creationId
                    AND c.status = 'RUNNING' AND c.claim_token = :claimToken
              )
            """, nativeQuery = true)
    int finishFailure(@Param("stepId") Long stepId,
                      @Param("executionAttemptId") Long executionAttemptId,
                      @Param("creationId") Long creationId,
                      @Param("claimToken") String claimToken,
                      @Param("resolutionCode") String resolutionCode,
                      @Param("now") LocalDateTime now);
}
