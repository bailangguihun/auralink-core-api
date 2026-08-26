package com.auralink.api.v1.creation;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/** Millisecond-safe public timestamp formatter for persisted SQLite Creation values. */
public final class CreationTimestampFormatter {

    private static final ZoneId PERSISTED_TIMESTAMP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private CreationTimestampFormatter() {
    }

    public static String format(LocalDateTime value) {
        return value == null ? null : FORMATTER.format(value.atZone(PERSISTED_TIMESTAMP_ZONE).toInstant());
    }
}
