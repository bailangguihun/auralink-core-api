package com.auralink.api.v1.media;

import java.time.LocalDateTime;

import com.auralink.entity.MediaAsset;

/**
 * Public representation of a MediaAsset. Internal database identifiers,
 * ownership rows and filesystem storage keys are deliberately omitted.
 */
public record MediaAssetResponse(
        String assetId,
        String originalFilename,
        String mimeType,
        Long fileSize,
        Integer width,
        Integer height,
        Double durationSeconds,
        String assetType,
        String semanticType,
        String sourceType,
        String visibility,
        String status,
        String contentUrl,
        String downloadUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static MediaAssetResponse from(MediaAsset asset) {
        String publicId = asset.getPublicId();
        String baseUrl = "/api/v1/assets/" + publicId;
        return new MediaAssetResponse(
                publicId,
                asset.getOriginalFilename(),
                asset.getMimeType(),
                asset.getFileSize(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getDurationSeconds(),
                asset.getAssetType(),
                asset.getSemanticType(),
                asset.getSourceType(),
                asset.getVisibility(),
                asset.getStatus(),
                baseUrl + "/content",
                baseUrl + "/download",
                asset.getCreatedAt(),
                asset.getUpdatedAt());
    }
}
