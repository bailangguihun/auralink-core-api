package com.auralink.ops.round9b2;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import com.auralink.creation.CreationExecutionBoundaryHook;
import com.auralink.ops.round9cc.Round9CcBarrierExecutionBoundaryHook;
import com.auralink.ops.round9cc.Round9CcMockCreationProviderAdapter;

class Round9B2PackagedMockHarnessIsolationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void normalB2ContextCannotActivateRound9CcBeansByPropertyOrProfile() throws Exception {
        Path root = temporaryDirectory.resolve("normal-b2-context");
        Files.createDirectory(root);
        assertThat(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(root)).isFalse();

        try (ConfigurableApplicationContext context = Round9B2PackagedMockHarness.startContext(root, new String[] {
                "--round9cc.harness.enabled=true",
                "--spring.profiles.active=round9cc"
        })) {
            assertThat(context.containsBean("round9CcHarnessState")).isFalse();
            assertThat(context.containsBean("round9CcNormalCompletionCoordinator")).isFalse();
            assertThat(context.containsBeanDefinition("round9CcBatch1SeedCoordinator")).isFalse();
            assertThat(context.getBeansOfType(Round9CcBarrierExecutionBoundaryHook.class)).isEmpty();
            assertThat(context.getBeansOfType(Round9CcMockCreationProviderAdapter.class)).isEmpty();
            assertThat(context.getBean(CreationExecutionBoundaryHook.class).getClass().getName())
                    .isEqualTo("com.auralink.creation.NoOpCreationExecutionBoundaryHook");
        }
    }

    @Test
    void dedicatedHarnessConfigurationIsNotComponentScanned() throws Exception {
        Class<?> configuration = Class.forName(
                "com.auralink.ops.round9cc.Round9CcPackagedFailureHarness$HarnessConfiguration");
        assertThat(configuration.isAnnotationPresent(Configuration.class)).isFalse();
        assertThat(configuration.getAnnotations()).isEmpty();
        Class<?> coordinator = Class.forName("com.auralink.ops.round9cc.Round9CcNormalCompletionCoordinator");
        assertThat(coordinator.getAnnotations()).isEmpty();
    }
}
