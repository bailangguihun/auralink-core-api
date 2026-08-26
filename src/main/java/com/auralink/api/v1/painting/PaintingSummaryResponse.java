package com.auralink.api.v1.painting;

/** Compact gallery representation of an official catalog painting. */
public record PaintingSummaryResponse(
        String paintingId,
        String title,
        String authorName,
        String creationDynastyRaw,
        String creationDynastyNormalized,
        String category,
        String subject,
        String paintingSchool,
        String style,
        String artisticConception,
        boolean imageAvailable,
        PaintingImageResponse image,
        boolean favorited) {
}
