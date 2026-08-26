package com.auralink.creation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Always installs the no-op boundary hook for ordinary application wiring. */
@Configuration(proxyBeanMethods = false)
public class CreationExecutionBoundaryConfiguration {

    @Bean
    CreationExecutionBoundaryHook creationExecutionBoundaryHook() {
        return NoOpCreationExecutionBoundaryHook.INSTANCE;
    }
}
