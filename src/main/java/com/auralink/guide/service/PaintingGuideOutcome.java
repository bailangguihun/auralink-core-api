package com.auralink.guide.service;

import java.time.LocalDateTime;

import com.auralink.guide.model.GuideResult;

/** Internal service result used to build the explicit v1 response DTO. */
public record PaintingGuideOutcome(
        String paintingId,
        GuideResult result,
        GuideCacheStatus cacheStatus,
        LocalDateTime generatedAt,
        LocalDateTime updatedAt) {
}
