package com.auralink.ops.round51;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class Round51MaintenanceEnvironmentPostProcessorTest {

    private static final String TOKEN = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void releaseHeldStartupGates() {
        Round51MaintenanceEnvironmentPostProcessor.releaseHeldStartupGatesForTests();
    }

    @Test
    void offServerDevelopmentStartupDoesNotProbeThePrivateServerMarker() {
        Path marker = temporaryDirectory.resolve("isolated-maintenance-marker");
        Path gate = temporaryDirectory.resolve("isolated-startup-gate");
        Path offServerRoot = temporaryDirectory.resolve("explicit-off-server-root");

        try (var ignored = Round51MaintenanceEnvironmentPostProcessor
                .isolatePathsForCurrentThread(marker, gate, offServerRoot)) {
            assertThatCode(() -> new Round51MaintenanceEnvironmentPostProcessor()
                    .postProcessEnvironment(new MockEnvironment(), new SpringApplication()))
                    .doesNotThrowAnyException();
        }

        assertThat(marker).doesNotExist();
        assertThat(gate).doesNotExist();
    }

    @Test
    void allowsNormalStartupWhenNoMaintenanceMarkerExists() throws Exception {
        Path marker = temporaryDirectory.resolve("maintenance");
        Path gate = temporaryDirectory.resolve(".round51-startup-gate");
        var processor = new Round51MaintenanceEnvironmentPostProcessor(marker);

        assertThatCode(() -> processor.postProcessEnvironment(
                new MockEnvironment(), new SpringApplication()))
                .doesNotThrowAnyException();
        assertThat(gate).isRegularFile();
        assertThat(Files.getPosixFilePermissions(gate))
                .doesNotContain(
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                        java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
    }

    @Test
    void refusesNormalStartupWhileMaintenanceLeaseExists() throws Exception {
        Path marker = temporaryDirectory.resolve("maintenance");
        Files.writeString(marker, TOKEN + "\n");
        var processor = new Round51MaintenanceEnvironmentPostProcessor(marker);

        assertThatThrownBy(() -> processor.postProcessEnvironment(
                new MockEnvironment(), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AURALINK_ROUND51_MAINTENANCE_ACTIVE");
    }

    @Test
    void allowsOnlyTheOwnerTokenAndRejectsSymlinkMarkers() throws Exception {
        Path marker = temporaryDirectory.resolve("maintenance");
        Files.writeString(marker, TOKEN + "\n");
        var environment = new MockEnvironment()
                .withProperty(Round51MaintenanceEnvironmentPostProcessor.TOKEN_PROPERTY, TOKEN);
        var processor = new Round51MaintenanceEnvironmentPostProcessor(marker);

        assertThatCode(() -> processor.postProcessEnvironment(
                environment, new SpringApplication()))
                .doesNotThrowAnyException();

        Path target = temporaryDirectory.resolve("target");
        Path symlink = temporaryDirectory.resolve("maintenance-link");
        Files.writeString(target, TOKEN);
        Files.createSymbolicLink(symlink, target.getFileName());

        assertThatThrownBy(() -> new Round51MaintenanceEnvironmentPostProcessor(symlink)
                .postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AURALINK_ROUND51_MAINTENANCE_ACTIVE");
    }

    @Test
    void springFactoriesRegistersTheStartupGuard() throws Exception {
        String registration = Files.readString(Path.of(
                "src/main/resources/META-INF/spring.factories"));

        org.assertj.core.api.Assertions.assertThat(registration)
                .contains("org.springframework.boot.env.EnvironmentPostProcessor")
                .contains(Round51MaintenanceEnvironmentPostProcessor.class.getName());
    }

    @Test
    void normalBackendRetainsSharedStartupGateForJvmLifetime() throws Exception {
        Path marker = temporaryDirectory.resolve("maintenance-shared");
        Path gate = temporaryDirectory.resolve("startup-gate-shared");
        createPrivateFile(gate);
        var processor = new Round51MaintenanceEnvironmentPostProcessor(marker, gate);

        assertThatCode(() -> processor.postProcessEnvironment(
                new MockEnvironment(), new SpringApplication()))
                .doesNotThrowAnyException();

        try (FileChannel contender = FileChannel.open(
                gate, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertThatThrownBy(() -> contender.tryLock())
                    .isInstanceOf(java.nio.channels.OverlappingFileLockException.class);
        }
    }

    @Test
    void ordinaryBackendRefusesAnExclusivelyHeldActivationGate() throws Exception {
        Path marker = temporaryDirectory.resolve("maintenance-exclusive");
        Path gate = temporaryDirectory.resolve("startup-gate-exclusive");
        createPrivateFile(gate);
        try (FileChannel owner = FileChannel.open(
                    gate, StandardOpenOption.READ, StandardOpenOption.WRITE);
                var ignored = owner.lock()) {
            var processor = new Round51MaintenanceEnvironmentPostProcessor(marker, gate);

            assertThatThrownBy(() -> processor.postProcessEnvironment(
                    new MockEnvironment(), new SpringApplication()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AURALINK_ROUND51_MAINTENANCE_ACTIVE");
        }
    }

    @Test
    void reviewedOwnerTokenBypassesGateOnlyWhileMarkerMatches() throws Exception {
        Path marker = temporaryDirectory.resolve("maintenance-owner");
        Path gate = temporaryDirectory.resolve("startup-gate-owner");
        Files.writeString(marker, TOKEN + "\n");
        createPrivateFile(gate);
        Files.createFile(temporaryDirectory.resolve(
                ".round51-activation-startup-gate-orphan-fence-healthy"));
        try (FileChannel owner = FileChannel.open(
                    gate, StandardOpenOption.READ, StandardOpenOption.WRITE);
                var ignored = owner.lock()) {
            var processor = new Round51MaintenanceEnvironmentPostProcessor(marker, gate);
            var environment = new MockEnvironment().withProperty(
                    Round51MaintenanceEnvironmentPostProcessor.TOKEN_PROPERTY, TOKEN);

            assertThatCode(() -> processor.postProcessEnvironment(
                    environment, new SpringApplication()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void pythonActivationGateExcludesNormalSpringStartup() throws Exception {
        Path marker = temporaryDirectory.resolve("maintenance-python");
        Path gate = temporaryDirectory.resolve("startup-gate-python");
        Path ready = temporaryDirectory.resolve("startup-gate-ready");
        long pid = ProcessHandle.current().pid();
        String[] fields = Files.readString(Path.of("/proc/" + pid + "/stat")).split("\\s+");
        Process holder = new ProcessBuilder(
                Round51TestPython.resolve().toString(),
                "scripts/round51_state.py",
                "hold-startup-gate",
                "--gate", gate.toString(),
                "--ready", ready.toString(),
                "--parent-pid", Long.toString(pid),
                "--parent-start-time", fields[21])
                .redirectErrorStream(true)
                .start();
        try {
            for (int attempt = 0; attempt < 100 && Files.notExists(ready); attempt++) {
                if (!holder.isAlive()) {
                    throw new AssertionError(new String(holder.getInputStream().readAllBytes()));
                }
                Thread.sleep(25);
            }
            org.assertj.core.api.Assertions.assertThat(Files.isRegularFile(ready)).isTrue();
            var processor = new Round51MaintenanceEnvironmentPostProcessor(marker, gate);

            assertThatThrownBy(() -> processor.postProcessEnvironment(
                    new MockEnvironment(), new SpringApplication()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AURALINK_ROUND51_MAINTENANCE_ACTIVE");
        } finally {
            holder.destroy();
            if (!holder.waitFor(5, TimeUnit.SECONDS)) {
                holder.destroyForcibly();
                holder.waitFor(5, TimeUnit.SECONDS);
            }
            if (holder.isAlive()) {
                throw new AssertionError("Round 5.1 startup-gate holder did not terminate");
            }
        }
    }

    @Test
    void durableOrphanFenceBlocksStartupAfterGateHolderOrHostLoss() throws Exception {
        Path marker = temporaryDirectory.resolve("maintenance-orphan");
        Path gate = temporaryDirectory.resolve("startup-gate-orphan");
        createPrivateFile(gate);
        Files.createFile(temporaryDirectory.resolve(
                ".round51-activation-startup-gate-orphan-fence-123"));
        var processor = new Round51MaintenanceEnvironmentPostProcessor(marker, gate);

        assertThatThrownBy(() -> processor.postProcessEnvironment(
                new MockEnvironment(), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AURALINK_ROUND51_MAINTENANCE_ACTIVE");

        Files.delete(temporaryDirectory.resolve(
                ".round51-activation-startup-gate-orphan-fence-123"));
        Files.createFile(temporaryDirectory.resolve(
                ".round51-recovery-startup-gate-orphan-fence-456"));
        assertThatThrownBy(() -> processor.postProcessEnvironment(
                new MockEnvironment(), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AURALINK_ROUND51_MAINTENANCE_ACTIVE");
    }

    private static void createPrivateFile(Path path) throws Exception {
        Files.createFile(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
}
