package com.auralink.service.media;

import java.io.InputStream;

import com.auralink.entity.User;

/**
 * Provider-independent input for registering a generated resource.
 *
 * <p>This is an internal service contract. Provider adapters supply a stream
 * and non-secret metadata; they never hand the resource layer an arbitrary
 * filesystem path.</p>
 */
public record GeneratedAssetRequest(
        User owner,
        InputStream content,
        String originalFilename,
        String mimeType,
        String assetType,
        String semanticType,
        Double durationSeconds) {

    public GeneratedAssetRequest {
        if (owner == null) {
            throw new IllegalArgumentException("Generated asset owner is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("Generated asset content is required");
        }
    }
}
