package com.auralink.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One UTC authority for Creation execution, heartbeat, and recovery decisions. */
@Configuration
public class CreationClockConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock creationClock() {
        return Clock.systemUTC();
    }
}
