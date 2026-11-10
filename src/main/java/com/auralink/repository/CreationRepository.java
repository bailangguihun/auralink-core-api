package com.auralink.repository;

import com.auralink.entity.Creation;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CreationRepository extends JpaRepository<Creation, Long> {
   Optional<Creation> findByPublicId(String publicId);

   boolean existsByPublicId(String publicId);

   Optional<Creation> findByPublicIdAndUserId(String publicId, Long userId);

   Page<Creation> findAllByUser_Id(Long userId, Pageable pageable);

   Page<Creation> findAllByUser_IdAndHistoryDeletedAtIsNull(Long userId, Pageable pageable);

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(value = "UPDATE creations SET history_deleted_at = :now "
      + "WHERE public_id = :publicId AND user_id = :userId AND history_deleted_at IS NULL "
      + "AND status IN ('SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED') "
      + "AND claim_token IS NULL AND lease_expires_at IS NULL", nativeQuery = true)
   int deleteHistorySafely(@Param("publicId") String publicId, @Param("userId") Long userId,
      @Param("now") LocalDateTime now);

   long countByStatusIn(Collection<String> statuses);

   Optional<Creation> findFirstByStatusOrderByCreatedAtAscIdAsc(String status);

   @EntityGraph(attributePaths = {"user", "sourceAsset", "sourcePainting"})
   Optional<Creation> findByIdAndStatusAndClaimToken(Long id, String status, String claimToken);

   @Query(
      value = "SELECT * FROM creations\nWHERE status = 'RUNNING'\n  AND claim_token IS NOT NULL\n  AND lease_expires_at IS NOT NULL\n  AND lease_expires_at <= :cutoff\nORDER BY lease_expires_at ASC, id ASC\nLIMIT :limit\n",
      nativeQuery = true
   )
   List<Creation> findExpiredRecoveryCandidates(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET status = 'RUNNING', claim_token = :claimToken, lease_expires_at = :leaseExpiresAt,\n    started_at = COALESCE(started_at, :now), updated_at = :now,\n    error_code = NULL, error_message = NULL\nWHERE id = :creationId AND status = 'QUEUED'\n",
      nativeQuery = true
   )
   int claimQueued(
      @Param("creationId") Long creationId,
      @Param("claimToken") String claimToken,
      @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
      @Param("now") LocalDateTime now
   );

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET lease_expires_at = :leaseExpiresAt, updated_at = :now\nWHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken\n",
      nativeQuery = true
   )
   int refreshLease(
      @Param("creationId") Long creationId,
      @Param("claimToken") String claimToken,
      @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
      @Param("now") LocalDateTime now
   );

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET claim_token = :recoveryToken, lease_expires_at = :fenceLeaseExpiresAt, updated_at = :now\nWHERE id = :creationId\n  AND status = 'RUNNING'\n  AND claim_token = :observedClaimToken\n  AND lease_expires_at = :observedLeaseExpiresAt\n  AND lease_expires_at <= :cutoff\n",
      nativeQuery = true
   )
   int fenceExpiredClaim(
      @Param("creationId") Long creationId,
      @Param("observedClaimToken") String observedClaimToken,
      @Param("observedLeaseExpiresAt") LocalDateTime observedLeaseExpiresAt,
      @Param("cutoff") LocalDateTime cutoff,
      @Param("recoveryToken") String recoveryToken,
      @Param("fenceLeaseExpiresAt") LocalDateTime fenceLeaseExpiresAt,
      @Param("now") LocalDateTime now
   );

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET status = 'QUEUED', claim_token = NULL, lease_expires_at = NULL,\n    updated_at = :now, error_code = NULL, error_message = NULL\nWHERE id = :creationId AND status = 'RUNNING' AND claim_token = :recoveryToken\n",
      nativeQuery = true
   )
   int requeueRecovered(@Param("creationId") Long creationId, @Param("recoveryToken") String recoveryToken, @Param("now") LocalDateTime now);

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET status = :status, finished_at = :now, updated_at = :now,\n    error_code = :errorCode, error_message = :errorMessage,\n    claim_token = NULL, lease_expires_at = NULL\nWHERE id = :creationId AND status = 'RUNNING' AND claim_token = :recoveryToken\n",
      nativeQuery = true
   )
   int terminalizeRecovered(
      @Param("creationId") Long creationId,
      @Param("recoveryToken") String recoveryToken,
      @Param("status") String status,
      @Param("errorCode") String errorCode,
      @Param("errorMessage") String errorMessage,
      @Param("now") LocalDateTime now
   );

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET status = 'SUCCEEDED', final_modality = :finalModality, final_asset_id = :assetId,\n    final_output_json = :outputJson, finished_at = :now, updated_at = :now,\n    error_code = NULL, error_message = NULL, claim_token = NULL, lease_expires_at = NULL\nWHERE id = :creationId AND status = 'RUNNING' AND claim_token = :recoveryToken\n",
      nativeQuery = true
   )
   int finalizeRecoveredSuccess(
      @Param("creationId") Long creationId,
      @Param("recoveryToken") String recoveryToken,
      @Param("finalModality") String finalModality,
      @Param("assetId") Long assetId,
      @Param("outputJson") String outputJson,
      @Param("now") LocalDateTime now
   );

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET status = 'QUEUED', claim_token = NULL, lease_expires_at = NULL,\n    started_at = NULL, updated_at = :now, error_code = NULL, error_message = NULL\nWHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken\n  AND NOT EXISTS (\n      SELECT 1 FROM creation_steps s\n      WHERE s.creation_id = :creationId\n        AND (s.status = 'RUNNING' OR s.provider_dispatch_state = 'SEND_STARTED')\n  )\n",
      nativeQuery = true
   )
   int releaseRejectedBeforeDispatch(@Param("creationId") Long creationId, @Param("claimToken") String claimToken, @Param("now") LocalDateTime now);

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET status = :status, finished_at = :now, updated_at = :now,\n    error_code = :errorCode, error_message = :errorMessage,\n    claim_token = NULL, lease_expires_at = NULL\nWHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken\n",
      nativeQuery = true
   )
   int failClaimed(
      @Param("creationId") Long creationId,
      @Param("claimToken") String claimToken,
      @Param("status") String status,
      @Param("errorCode") String errorCode,
      @Param("errorMessage") String errorMessage,
      @Param("now") LocalDateTime now
   );

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET status = 'SUCCEEDED', final_modality = 'PAINTING', final_asset_id = :assetId,\n    final_output_json = NULL, finished_at = :now, updated_at = :now,\n    error_code = NULL, error_message = NULL, claim_token = NULL, lease_expires_at = NULL\nWHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken\n",
      nativeQuery = true
   )
   int completePainting(
      @Param("creationId") Long creationId, @Param("claimToken") String claimToken, @Param("assetId") Long assetId, @Param("now") LocalDateTime now
   );

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET status = 'SUCCEEDED', final_modality = 'POEM', final_asset_id = NULL,\n    final_output_json = :outputJson, finished_at = :now, updated_at = :now,\n    error_code = NULL, error_message = NULL, claim_token = NULL, lease_expires_at = NULL\nWHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken\n",
      nativeQuery = true
   )
   int completePoem(
      @Param("creationId") Long creationId, @Param("claimToken") String claimToken, @Param("outputJson") String outputJson, @Param("now") LocalDateTime now
   );

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET status = 'SUCCEEDED', final_modality = 'AUDIO', final_asset_id = :assetId,\n    final_output_json = NULL, finished_at = :now, updated_at = :now,\n    error_code = NULL, error_message = NULL, claim_token = NULL, lease_expires_at = NULL\nWHERE id = :creationId AND status = 'RUNNING' AND claim_token = :claimToken\n",
      nativeQuery = true
   )
   int completeMusic(
      @Param("creationId") Long creationId, @Param("claimToken") String claimToken, @Param("assetId") Long assetId, @Param("now") LocalDateTime now
   );

   @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(
      value = "UPDATE creations\nSET status = 'QUEUED', retry_version = retry_version + 1,\n    final_modality = NULL, final_asset_id = NULL, final_output_json = NULL,\n    started_at = NULL, finished_at = NULL, updated_at = :now,\n    error_code = NULL, error_message = NULL,\n    claim_token = NULL, lease_expires_at = NULL\nWHERE id = :creationId AND user_id = :userId\n  AND history_deleted_at IS NULL\n  AND status IN ('FAILED', 'PARTIAL_SUCCESS')\n  AND retry_version = :expectedRetryVersion\n  AND claim_token IS NULL AND lease_expires_at IS NULL\n",
      nativeQuery = true
   )
   int retrySafely(
      @Param("creationId") Long creationId,
      @Param("userId") Long userId,
      @Param("expectedRetryVersion") int expectedRetryVersion,
      @Param("now") LocalDateTime now
   );
}
