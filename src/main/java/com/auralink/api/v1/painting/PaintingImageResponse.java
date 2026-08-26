package com.auralink.api.v1.painting;

/** Safe public reference to the MediaAsset that contains a painting image. */
public record PaintingImageResponse(
        String assetId,
        String mimeType,
        Long fileSize,
        Integer width,
        Integer height,
        String contentUrl,
        String downloadUrl) {
}
