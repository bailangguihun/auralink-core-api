package com.auralink.api.v1.creation;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

public final class CreationTimestampFormatter {
   private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

   private CreationTimestampFormatter() {
   }

   public static String format(LocalDateTime value) {
      return value == null ? null : FORMATTER.format(value.toInstant(ZoneOffset.UTC));
   }
}
