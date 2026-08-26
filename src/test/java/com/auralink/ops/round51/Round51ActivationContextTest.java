package com.auralink.ops.round51;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.repository.Repository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.auralink.Application;
import com.auralink.catalog.PaintingCatalogImportRunner;
import com.auralink.catalog.PaintingCatalogImporter;
import com.auralink.repository.CatalogImportRunRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletContext;

class Round51ActivationContextTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void originalFullApplicationSourceReproducesNoServletContextFailure() {
        Throwable failure = null;
        Path marker = temporaryDirectory.resolve("full-context-maintenance-marker");
        Path gate = temporaryDirectory.resolve("full-context-startup-gate");
        Path offServerRoot = temporaryDirectory.resolve("full-context-off-server-root");
        try (var ignoredPaths = Round51MaintenanceEnvironmentPostProcessor
                .isolatePathsForCurrentThread(marker, gate, offServerRoot)) {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(Application.class)
                    .web(WebApplicationType.NONE)
                    .registerShutdownHook(false)
                    .run(fullApplicationArguments())) {
                // The old bootstrap must fail before reaching this point.
            } catch (Throwable exception) {
                failure = exception;
            }
        }

        assertThat(marker).doesNotExist();
        assertThat(gate).doesNotExist();
        assertThat(failure).isNotNull();
        List<String> failureChain = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            failureChain.add(current.getClass().getName() + ": " + current.getMessage());
        }
        assertThat(failureChain).anyMatch(message -> message.contains("resourceHandlerMapping"));
        assertThat(failureChain).anyMatch(message -> message.contains("No ServletContext set"));
    }

    @Test
    void dedicatedContextLoadsOnlyTheNonWebActivationGraph() throws Exception {
        Path marker = temporaryDirectory.resolve("activation-context-maintenance-marker");
        Path gate = temporaryDirectory.resolve("activation-context-startup-gate");
        Path offServerRoot = temporaryDirectory.resolve("activation-context-off-server-root");
        try (var ignoredPaths = Round51MaintenanceEnvironmentPostProcessor
                .isolatePathsForCurrentThread(marker, gate, offServerRoot)) {
            try (ConfigurableApplicationContext context = Round51ActivationCommand.startActivationContext(
                    activationArguments())) {
                assertThat(context).isNotInstanceOf(WebApplicationContext.class);
                assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
                assertThat(context.getBeansOfType(ServletContext.class)).isEmpty();
                assertThat(context.getBeansOfType(ServletWebServerFactory.class)).isEmpty();
                assertThat(context.getBeansOfType(DispatcherServlet.class)).isEmpty();
                assertThat(context.getBeansOfType(HandlerMapping.class)).isEmpty();
                assertThat(context.getBeansOfType(WebMvcConfigurer.class)).isEmpty();
                assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
                assertThat(context.getBeansOfType(Filter.class)).isEmpty();
                assertThat(context.getBeansWithAnnotation(Controller.class)).isEmpty();
                assertThat(context.getBeansWithAnnotation(RestController.class)).isEmpty();
                assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty();
                assertThat(context.getBeansOfType(CommandLineRunner.class)).isEmpty();
                assertThat(context.getBeansOfType(PaintingCatalogImportRunner.class)).isEmpty();
                assertThat(context.getBeansOfType(Flyway.class)).isEmpty();

                assertThat(context.getBean(Round51ActivationCoordinator.class)).isNotNull();
                assertThat(context.getBean(PaintingCatalogImporter.class)).isNotNull();
                assertThat(context.getBean(DataSource.class)).isNotNull();
                assertThat(context.getBean(EntityManagerFactory.class)).isNotNull();
                assertThat(context.getBean(PlatformTransactionManager.class)).isNotNull();
                assertThat(context.getBean(UserRepository.class)).isNotNull();
                assertThat(context.getBean(MediaAssetRepository.class)).isNotNull();
                assertThat(context.getBean(PaintingRepository.class)).isNotNull();
                assertThat(context.getBean(CatalogImportRunRepository.class)).isNotNull();
                assertThat(context.getBeansOfType(CreationRepository.class)).isEmpty();
                assertThat(context.getBeansOfType(Repository.class)).hasSize(4);

                Set<String> managedEntities = context.getBean(EntityManagerFactory.class)
                        .getMetamodel()
                        .getEntities()
                        .stream()
                        .map(entity -> entity.getJavaType().getSimpleName())
                        .collect(Collectors.toSet());
                assertThat(managedEntities).containsExactlyInAnyOrder(
                        "User", "MediaAsset", "Painting", "CatalogImportRun");

                try (Connection connection = context.getBean(DataSource.class).getConnection();
                        Statement statement = connection.createStatement();
                        ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
        }

        assertThat(marker).doesNotExist();
        assertThat(gate).doesNotExist();

        assertThat(AnnotatedElementUtils.hasAnnotation(
                Round51ActivationContextConfiguration.class, Component.class)).isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(
                Round51ActivationCoordinator.class, Component.class)).isFalse();
    }

    private String[] activationArguments() {
        return new String[] {
                "--spring.config.import=optional:file:"
                        + temporaryDirectory.resolve("missing-activation.env") + "[.properties]",
                "--spring.datasource.url=jdbc:sqlite:" + temporaryDirectory.resolve("activation-context.db"),
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.enabled=false",
                "--spring.sql.init.mode=never",
                "--spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON",
                "--auralink.paintings.import-enabled=false",
                "--auralink.paintings.metadata-csv-path=" + temporaryDirectory.resolve("paintings.csv"),
                "--auralink.paintings.picture-dir=" + temporaryDirectory.resolve("pictures"),
                "--auralink.media-assets.managed-dir=" + temporaryDirectory.resolve("managed")
        };
    }

    private String[] fullApplicationArguments() {
        return new String[] {
                "--spring.config.import=optional:file:"
                        + temporaryDirectory.resolve("missing-full-application.env") + "[.properties]",
                "--spring.datasource.url=jdbc:sqlite:" + temporaryDirectory.resolve("old-context.db"),
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.enabled=false",
                "--spring.sql.init.mode=never",
                "--auralink.jwt.secret=round51-context-regression-safe-placeholder",
                "--auralink.paintings.import-enabled=false",
                "--auralink.storage.upload-dir=" + temporaryDirectory.resolve("legacy-uploads"),
                "--auralink.storage.audio-dir=" + temporaryDirectory.resolve("legacy-audio"),
                "--auralink.storage.legacy-frontend-audio-dir="
                        + temporaryDirectory.resolve("legacy-frontend-audio"),
                "--auralink.media-assets.managed-dir=" + temporaryDirectory.resolve("full-app-managed")
        };
    }
}
