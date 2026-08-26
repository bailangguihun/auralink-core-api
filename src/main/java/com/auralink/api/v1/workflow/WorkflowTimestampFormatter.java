package com.auralink.api.v1.workflow;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/** Narrow persisted-SQLite timestamp formatter for public workflow DTOs. */
public final class WorkflowTimestampFormatter {

    private static final ZoneId PERSISTED_TIMESTAMP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter PUBLIC_INSTANT_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private WorkflowTimestampFormatter() {
    }

    public static String format(LocalDateTime timestamp) {
        if (timestamp == null) {
            return null;
        }
        return PUBLIC_INSTANT_FORMATTER.format(
                timestamp.atZone(PERSISTED_TIMESTAMP_ZONE).toInstant());
    }
}
