package com.auralink.ops.round81;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

import com.auralink.creation.provider.ProviderAdapterRegistry;
import com.auralink.workflow.WorkflowOperation;

import jakarta.persistence.EntityManagerFactory;

class Round81ProviderValidationContextTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsMinimalNonWebProviderGraphWithoutPersistenceOrSecurity() throws Exception {
        Path outputRoot = Files.createDirectory(temporaryDirectory.resolve("vmm-output"));
        Path stagingRoot = temporaryDirectory.resolve("staging");
        String mockBase = "http://127.0.0.1:48991";

        try (ConfigurableApplicationContext context =
                Round81ProviderValidationCommand.startValidationContext(
                        "--spring.config.location=optional:classpath:/round81-provider-validation-mock.properties",
                        "--spring.main.web-application-type=none",
                        "--spring.main.banner-mode=off",
                        "--spring.jmx.enabled=false",
                        "--logging.level.root=OFF",
                        "--auralink.creation-providers.enabled=true",
                        "--auralink.creation-providers.staging-dir=" + stagingRoot,
                        "--auralink.round81.mock-mode=LOCAL_LOOPBACK_ONLY",
                        "--auralink.round81.mock-base-url=" + mockBase,
                        "--auralink.providers.seedream.api-key=mock-only",
                        "--auralink.providers.seedream.base-url=https://ark.cn-beijing.volces.com/api/v3",
                        "--auralink.providers.seedream.model=mock-model",
                        "--auralink.providers.qwen.api-key=mock-only",
                        "--auralink.providers.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "--auralink.providers.qwen.model=qwen3-vl-plus",
                        "--auralink.providers.painting-music.base-url=" + mockBase,
                        "--auralink.providers.painting-music.output-root=" + outputRoot)) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
            assertThat(context.getBeanNamesForAnnotation(org.springframework.stereotype.Controller.class))
                    .isEmpty();
            assertThat(context.getBeanNamesForAnnotation(org.springframework.web.bind.annotation.RestController.class))
                    .isEmpty();
            assertThat(context.getBeanDefinitionNames())
                    .noneMatch(name -> name.toLowerCase().contains("repository"))
                    .noneMatch(name -> name.toLowerCase().contains("catalogstartup"))
                    .noneMatch(name -> name.toLowerCase().contains("guideclient"));

            ProviderAdapterRegistry registry = context.getBean(ProviderAdapterRegistry.class);
            assertThat(registry.bindings()).hasSize(5);
            assertThat(registry.find(WorkflowOperation.TEXT_TO_PAINTING, "seedream-5")).isPresent();
            assertThat(registry.find(WorkflowOperation.IMAGE_TO_PAINTING, "seedream-5")).isPresent();
            assertThat(registry.find(WorkflowOperation.POEM_TO_PAINTING, "qwen3vl-seedream5")).isPresent();
            assertThat(registry.find(WorkflowOperation.PAINTING_TO_POEM, "qwen3-vl-plus")).isPresent();
            assertThat(registry.find(WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm")).isPresent();
            assertThat(registry.find(WorkflowOperation.PAINTING_TO_VIDEO, "reserved-video")).isEmpty();

            context.getBean(Round81ProviderValidationCoordinator.class)
                    .dryRun(Round81ValidationOperation.TEXT_TO_PAINTING);
            assertThat(Files.exists(stagingRoot)).isFalse();
        }
    }
}
