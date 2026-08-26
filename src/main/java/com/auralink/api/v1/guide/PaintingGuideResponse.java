package com.auralink.api.v1.guide;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;

import com.auralink.guide.model.GuideKnowledgeReference;
import com.auralink.guide.model.GuideSections;
import com.auralink.guide.service.PaintingGuideOutcome;

/** Safe public representation of one validated standard Painting guide. */
public record PaintingGuideResponse(
        String paintingId,
        String schemaVersion,
        String summary,
        GuideSections sections,
        List<String> highlights,
        List<GuideKnowledgeReference> knowledgeReferences,
        String cacheStatus,
        String generatedAt,
        String updatedAt) {

    private static final ZoneId PERSISTED_TIMESTAMP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter PUBLIC_INSTANT_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    public static PaintingGuideResponse from(PaintingGuideOutcome outcome) {
        return new PaintingGuideResponse(
                outcome.paintingId(),
                outcome.result().schemaVersion(),
                outcome.result().summary(),
                outcome.result().sections(),
                List.copyOf(outcome.result().highlights()),
                List.copyOf(outcome.result().knowledgeReferences()),
                outcome.cacheStatus().name(),
                formatPersistedTimestamp(outcome.generatedAt()),
                formatPersistedTimestamp(outcome.updatedAt()));
    }

    private static String formatPersistedTimestamp(LocalDateTime timestamp) {
        // SQLite stores these legacy LocalDateTime values as epoch milliseconds
        // using the application's Asia/Shanghai default zone. Convert with that
        // same zone so the public UTC instant is identical to the stored epoch.
        return PUBLIC_INSTANT_FORMATTER.format(
                timestamp.atZone(PERSISTED_TIMESTAMP_ZONE).toInstant());
    }
}
