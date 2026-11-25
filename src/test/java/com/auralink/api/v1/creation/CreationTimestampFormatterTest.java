package com.auralink.api.v1.creation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CreationTimestampFormatterTest {
   @Test
   void preservesTheInstantOfAnExistingUtcTimestamp() {
      LocalDateTime stored = LocalDateTime.parse("2026-11-24T17:01:00.123");

      String timestamp = CreationTimestampFormatter.format(stored);

      assertThat(timestamp).isEqualTo("2026-11-24T17:01:00.123Z");
      assertThat(Instant.parse(timestamp).atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime())
         .isEqualTo(LocalDateTime.parse("2026-11-25T01:01:00.123"));
   }

   @Test
   void keepsAnUnsetTimestampNull() {
      assertThat(CreationTimestampFormatter.format(null)).isNull();
   }
}
