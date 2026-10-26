package com.auralink.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class CreationClockConfiguration {
   @Bean
   @Primary
   @ConditionalOnMissingBean(Clock.class)
   public Clock creationClock() {
      return Clock.systemUTC();
   }
}
