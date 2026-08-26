package com.auralink.repository;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.auralink.entity.Creation;

@Repository
public interface CreationRepository extends JpaRepository<Creation, Long> {

    Optional<Creation> findByPublicId(String publicId);

    boolean existsByPublicId(String publicId);

    Optional<Creation> findByPublicIdAndUserId(String publicId, Long userId);

    Page<Creation> findAllByUser_Id(Long userId, Pageable pageable);

    long countByStatusIn(java.util.Collection<String> statuses);

    Optional<Creation> findFirstByStatusOrderByCreatedAtAscIdAsc(String status);

    @EntityGraph(attributePaths = {"user", "sourceAsset", "sourcePainting"})
    Optional<Creation> findByIdAndStatusAndClaimToken(Long id, String status, String claimToken);

    @Query(value = """
            SELECT * FROM creations
            WHERE status = 'RUNNING'
              AND claim_token IS NOT NULL
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at <= :cutoff
            ORDER BY lease_expires_at ASC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Creation> findExpiredRecoveryCandidates(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET status = 'RUNNING', claim_token = :claimToken, lease_expires_at = :leaseExpiresAt,
                started_at = COALESCE(started_at, :now), updated_at = :now,
                error_code = NULL, error_message = NULL
            WHERE id = :creationId AND status = 'QUEUED'
            """, nativeQuery = true)
    int claimQueued(@Param("creationId") Long creationId,
                    @Param("claimToken") String claimToken,
                    @Param("leaseExpiresAt") java.time.LocalDateTime leaseExpiresAt,
                    @Param("now") java.time.LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET lease_expires_at = :leaseExpiresAt, updated_at = :now
            WHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken
            """, nativeQuery = true)
    int refreshLease(@Param("creationId") Long creationId,
                     @Param("claimToken") String claimToken,
                     @Param("leaseExpiresAt") java.time.LocalDateTime leaseExpiresAt,
                     @Param("now") java.time.LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET claim_token = :recoveryToken, lease_expires_at = :fenceLeaseExpiresAt, updated_at = :now
            WHERE id = :creationId
              AND status = 'RUNNING'
              AND claim_token = :observedClaimToken
              AND lease_expires_at = :observedLeaseExpiresAt
              AND lease_expires_at <= :cutoff
            """, nativeQuery = true)
    int fenceExpiredClaim(@Param("creationId") Long creationId,
                          @Param("observedClaimToken") String observedClaimToken,
                          @Param("observedLeaseExpiresAt") LocalDateTime observedLeaseExpiresAt,
                          @Param("cutoff") LocalDateTime cutoff,
                          @Param("recoveryToken") String recoveryToken,
                          @Param("fenceLeaseExpiresAt") LocalDateTime fenceLeaseExpiresAt,
                          @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET status = 'QUEUED', claim_token = NULL, lease_expires_at = NULL,
                updated_at = :now, error_code = NULL, error_message = NULL
            WHERE id = :creationId AND status = 'RUNNING' AND claim_token = :recoveryToken
            """, nativeQuery = true)
    int requeueRecovered(@Param("creationId") Long creationId,
                         @Param("recoveryToken") String recoveryToken,
                         @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET status = :status, finished_at = :now, updated_at = :now,
                error_code = :errorCode, error_message = :errorMessage,
                claim_token = NULL, lease_expires_at = NULL
            WHERE id = :creationId AND status = 'RUNNING' AND claim_token = :recoveryToken
            """, nativeQuery = true)
    int terminalizeRecovered(@Param("creationId") Long creationId,
                             @Param("recoveryToken") String recoveryToken,
                             @Param("status") String status,
                             @Param("errorCode") String errorCode,
                             @Param("errorMessage") String errorMessage,
                             @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET status = 'SUCCEEDED', final_modality = :finalModality, final_asset_id = :assetId,
                final_output_json = :outputJson, finished_at = :now, updated_at = :now,
                error_code = NULL, error_message = NULL, claim_token = NULL, lease_expires_at = NULL
            WHERE id = :creationId AND status = 'RUNNING' AND claim_token = :recoveryToken
            """, nativeQuery = true)
    int finalizeRecoveredSuccess(@Param("creationId") Long creationId,
                                 @Param("recoveryToken") String recoveryToken,
                                 @Param("finalModality") String finalModality,
                                 @Param("assetId") Long assetId,
                                 @Param("outputJson") String outputJson,
                                 @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET status = 'QUEUED', claim_token = NULL, lease_expires_at = NULL,
                started_at = NULL, updated_at = :now, error_code = NULL, error_message = NULL
            WHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken
              AND NOT EXISTS (
                  SELECT 1 FROM creation_steps s
                  WHERE s.creation_id = :creationId
                    AND (s.status = 'RUNNING' OR s.provider_dispatch_state = 'SEND_STARTED')
              )
            """, nativeQuery = true)
    int releaseRejectedBeforeDispatch(@Param("creationId") Long creationId,
                                      @Param("claimToken") String claimToken,
                                      @Param("now") java.time.LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET status = :status, finished_at = :now, updated_at = :now,
                error_code = :errorCode, error_message = :errorMessage,
                claim_token = NULL, lease_expires_at = NULL
            WHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken
            """, nativeQuery = true)
    int failClaimed(@Param("creationId") Long creationId,
                    @Param("claimToken") String claimToken,
                    @Param("status") String status,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage,
                    @Param("now") java.time.LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET status = 'SUCCEEDED', final_modality = 'PAINTING', final_asset_id = :assetId,
                final_output_json = NULL, finished_at = :now, updated_at = :now,
                error_code = NULL, error_message = NULL, claim_token = NULL, lease_expires_at = NULL
            WHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken
            """, nativeQuery = true)
    int completePainting(@Param("creationId") Long creationId,
                         @Param("claimToken") String claimToken,
                         @Param("assetId") Long assetId,
                         @Param("now") java.time.LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET status = 'SUCCEEDED', final_modality = 'POEM', final_asset_id = NULL,
                final_output_json = :outputJson, finished_at = :now, updated_at = :now,
                error_code = NULL, error_message = NULL, claim_token = NULL, lease_expires_at = NULL
            WHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken
            """, nativeQuery = true)
    int completePoem(@Param("creationId") Long creationId,
                     @Param("claimToken") String claimToken,
                     @Param("outputJson") String outputJson,
                     @Param("now") java.time.LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE creations
            SET status = 'QUEUED', retry_version = retry_version + 1,
                final_modality = NULL, final_asset_id = NULL, final_output_json = NULL,
                started_at = NULL, finished_at = NULL, updated_at = :now,
                error_code = NULL, error_message = NULL,
                claim_token = NULL, lease_expires_at = NULL
            WHERE id = :creationId AND user_id = :userId
              AND status IN ('FAILED', 'PARTIAL_SUCCESS')
              AND retry_version = :expectedRetryVersion
              AND claim_token IS NULL AND lease_expires_at IS NULL
            """, nativeQuery = true)
    int retrySafely(@Param("creationId") Long creationId,
                    @Param("userId") Long userId,
                    @Param("expectedRetryVersion") int expectedRetryVersion,
                    @Param("now") java.time.LocalDateTime now);
}
