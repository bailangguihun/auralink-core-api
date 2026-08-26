package com.auralink.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.auralink.entity.MediaAsset;

@Repository
public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    Optional<MediaAsset> findByPublicId(String publicId);

    boolean existsByPublicId(String publicId);

    Optional<MediaAsset> findByStorageKey(String storageKey);

    Optional<MediaAsset> findByPublicIdAndOwnerUser_IdAndStatusAndAssetType(
            String publicId,
            Long ownerUserId,
            String status,
            String assetType);
}
