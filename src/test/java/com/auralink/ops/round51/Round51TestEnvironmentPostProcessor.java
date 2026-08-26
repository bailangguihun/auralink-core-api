package com.auralink.ops.round51;

import java.nio.file.Path;
import java.util.UUID;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Test-classpath-only ordering bridge for Spring contexts created outside an
 * individual Round 5.1 test method. The root is intentionally nonexistent:
 * the production guard classifies it as off-server before opening a marker or
 * startup gate.
 */
public final class Round51TestEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application) {
        Path root = Path.of(System.getProperty("java.io.tmpdir"),
                "auralink-round51-test-" + UUID.randomUUID()).toAbsolutePath().normalize();
        Round51MaintenanceEnvironmentPostProcessor.installAutomaticPathsForCurrentThread(
                root.resolve(".round51-maintenance"),
                root.resolve(".round51-startup-gate"),
                root);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
