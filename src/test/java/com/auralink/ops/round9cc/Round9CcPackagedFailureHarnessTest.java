package com.auralink.ops.round9cc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.creation.CreationExecutionBoundary;
import com.auralink.creation.CreationExecutionBoundaryHook;
import com.auralink.creation.CreationRecoveryGate;
import com.auralink.creation.CreationStatus;
import com.auralink.creation.CreationStepStatus;
import com.auralink.creation.ProviderDispatchState;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationStep;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.GenerationLogRepository;
import com.auralink.repository.PaintingRepository;
class Round9CcPackagedFailureHarnessTest {

    private Path root;

    @AfterEach
    void deleteExactOwnedFixture() throws Exception {
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    @Test
    void dedicatedHarnessLaunchRequiresValidatedFixtureExactManifestAndBoundPhase() throws Exception {
        Round9CcFixture fixture = fixture();
        Files.writeString(fixture.propertiesFile(), "fixture.only=true\n", StandardCharsets.UTF_8);
        Round9CcFixture.setPrivateFile(fixture.propertiesFile());
        Round9CcFixtureManifest.write(fixture, Round9CcScenario.NORMAL_COMPLETION);

        var launch = Round9CcPackagedFailureHarness.Launch.parse(new String[] {
                "--fixture-root=" + fixture.root(),
                "--instance=workerA",
                "--scenario=NORMAL_COMPLETION",
                "--phase=INITIAL",
                "--failpoint-timeout-seconds=3"
        });

        assertThat(launch.scenario()).isEqualTo(Round9CcScenario.NORMAL_COMPLETION);
        assertThat(launch.phase()).isEqualTo(Round9CcRunPhase.INITIAL);
        assertThat(launch.failpoint()).isNull();
        assertThat(launch.role()).isEqualTo("DISPATCHER_WORKER");
        assertThat(launch.timeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void arbitraryFailpointIsRejectedBeforeSpringStartupOrFixtureDatabaseMutation() throws Exception {
        Round9CcFixture fixture = fixture();
        Files.writeString(fixture.propertiesFile(), "fixture.only=true\n", StandardCharsets.UTF_8);
        Round9CcFixture.setPrivateFile(fixture.propertiesFile());
        Round9CcFixtureManifest.write(fixture, Round9CcScenario.TERM_AFTER_CLAIM);

        assertThatThrownBy(() -> Round9CcPackagedFailureHarness.Launch.parse(new String[] {
                "--fixture-root=" + fixture.root(),
                "--instance=workerA",
                "--scenario=TERM_AFTER_CLAIM",
                "--failpoint=HARD_KILL_WINDOW"
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ROUND9CC_ERROR:MANIFEST_LAUNCH_MISMATCH");
        assertThat(Files.exists(fixture.root().resolve("db/fixture.db"))).isFalse();
    }

    @Test
    void batch1SeedAndRecoveryPhasesAreManifestBoundAndDoNotSelectAFailpoint() throws Exception {
        Round9CcFixture fixture = fixture();
        Files.writeString(fixture.propertiesFile(), "fixture.only=true\n", StandardCharsets.UTF_8);
        Round9CcFixture.setPrivateFile(fixture.propertiesFile());
        Round9CcFixtureManifest.write(fixture, Round9CcScenario.INT_AFTER_SEND_STARTED);

        var seed = Round9CcPackagedFailureHarness.Launch.parse(new String[] {
                "--fixture-root=" + fixture.root(), "--instance=seedA",
                "--scenario=INT_AFTER_SEND_STARTED", "--phase=SEED"
        });
        var recovery = Round9CcPackagedFailureHarness.Launch.parse(new String[] {
                "--fixture-root=" + fixture.root(), "--instance=recoveryA",
                "--scenario=INT_AFTER_SEND_STARTED", "--phase=RECOVERY"
        });

        assertThat(seed.failpoint()).isNull();
        assertThat(seed.role()).isEqualTo("SEEDER");
        assertThat(recovery.failpoint()).isNull();
        assertThat(recovery.role()).isEqualTo("RECOVERY");
        assertThat(Files.exists(fixture.root().resolve("db/fixture.db"))).isFalse();
    }

    @Test
    void batch1ManifestBindsOnlyTheFourApprovedInitialBoundariesAndValidFixtureTimings() throws Exception {
        List<Round9CcScenario> batch1 = List.of(
                Round9CcScenario.TERM_BEFORE_CLAIM,
                Round9CcScenario.TERM_AFTER_CLAIM,
                Round9CcScenario.TERM_DURING_NOT_SENT,
                Round9CcScenario.INT_AFTER_SEND_STARTED);
        List<CreationExecutionBoundary> boundaries = List.of(
                CreationExecutionBoundary.STARTUP_RECOVERY_GATE_CLOSED,
                CreationExecutionBoundary.CLAIM_COMMITTED_BEFORE_SUBMIT,
                CreationExecutionBoundary.STEP_RUNNING_BEFORE_SEND_STARTED,
                CreationExecutionBoundary.SEND_STARTED_COMMITTED);
        for (int index = 0; index < batch1.size(); index++) {
            Round9CcScenario scenario = batch1.get(index);
            assertThat(scenario.supports(Round9CcRunPhase.SEED)).isTrue();
            assertThat(scenario.supports(Round9CcRunPhase.RECOVERY)).isTrue();
            assertThat(scenario.failpointFor(Round9CcRunPhase.INITIAL)).isEqualTo(boundaries.get(index));
            assertThat(scenario.failpointFor(Round9CcRunPhase.SEED)).isNull();
            assertThat(scenario.failpointFor(Round9CcRunPhase.RECOVERY)).isNull();
            assertThat(scenario.definition().recoveryCalls()).isEqualTo("ZERO");
            assertThat(scenario.manifestValues()).containsEntry("recoveryProviderCalls", "ZERO");
        }
        assertThat(Round9CcScenario.KILL_DURING_MOCK.supports(Round9CcRunPhase.SEED)).isFalse();

        Round9CcFixture fixture = fixture();
        prepare(fixture, Round9CcScenario.TERM_AFTER_CLAIM);
        var batchLaunch = Round9CcPackagedFailureHarness.Launch.parse(new String[] {
                "--fixture-root=" + fixture.root(), "--instance=seedA",
                "--scenario=TERM_AFTER_CLAIM", "--phase=SEED"
        });
        List<String> batchArguments = Round9CcPackagedFailureHarness.startupArguments(batchLaunch);
        assertThat(batchArguments).contains(
                "--auralink.creations.lease-duration=2s",
                "--auralink.creations.heartbeat-interval=1s",
                "--auralink.creations.recovery-grace=1s",
                "--auralink.creations.recovery-fence-lease=300s",
                "--auralink.creations.startup-max-batches=2");
        assertThat(batchArguments).doesNotContain("--auralink.creations.startup-max-batches=1");
        assertThat(batchArguments.stream()
                .filter(argument -> argument.startsWith("--auralink.creations.startup-max-batches="))
                .toList()).containsExactly("--auralink.creations.startup-max-batches=2");
        assertThat(new CreationExecutionProperties().getStartupMaxBatches()).isEqualTo(20);
    }

    @Test
    void termBeforeClaimProducerWritesTheCanonicalRecoveryCallVocabulary() throws Exception {
        Round9CcFixture fixture = fixture();
        prepare(fixture, Round9CcScenario.TERM_BEFORE_CLAIM);

        assertThat(Round9CcScenario.TERM_BEFORE_CLAIM.definition().recoveryCalls()).isEqualTo("ZERO");
        assertThat(Round9CcScenario.TERM_BEFORE_CLAIM.manifestValues())
                .containsEntry("recoveryProviderCalls", "ZERO");
        assertThat(Files.readString(fixture.manifestFile(), StandardCharsets.UTF_8))
                .contains("recoveryProviderCalls=ZERO");
    }

    @Test
    void dedicatedHarnessLaunchRejectsAChangedScenarioManifest() throws Exception {
        Round9CcFixture fixture = fixture();
        Files.writeString(fixture.propertiesFile(), "fixture.only=true\n", StandardCharsets.UTF_8);
        Round9CcFixture.setPrivateFile(fixture.propertiesFile());
        Round9CcFixtureManifest.write(fixture, Round9CcScenario.NORMAL_COMPLETION);
        Files.writeString(fixture.manifestFile(), "scenario=TERM_AFTER_CLAIM\n", StandardCharsets.UTF_8);
        Round9CcFixture.setPrivateFile(fixture.manifestFile());

        assertThatThrownBy(() -> Round9CcPackagedFailureHarness.Launch.parse(new String[] {
                "--fixture-root=" + fixture.root(),
                "--instance=workerA",
                "--scenario=NORMAL_COMPLETION"
        })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dedicatedHarnessLaunchRejectsAnInvalidFixtureRoot() {
        assertThatThrownBy(() -> Round9CcPackagedFailureHarness.Launch.parse(new String[] {
                "--fixture-root=/tmp/auralink-round9cc-invalid",
                "--instance=workerA",
                "--scenario=NORMAL_COMPLETION"
        })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dedicatedHarnessExplicitlyRegistersItsStateAndOnlyHarnessBeans() throws Exception {
        Round9CcFixture fixture = fixture();
        Files.writeString(fixture.propertiesFile(), "fixture.only=true\n", StandardCharsets.UTF_8);
        Round9CcFixture.setPrivateFile(fixture.propertiesFile());
        Round9CcFixtureManifest.write(fixture, Round9CcScenario.NORMAL_COMPLETION);
        var launch = Round9CcPackagedFailureHarness.Launch.parse(new String[] {
                "--fixture-root=" + fixture.root(),
                "--instance=workerA",
                "--scenario=NORMAL_COMPLETION"
        });

        try (ConfigurableApplicationContext context = Round9CcPackagedFailureHarness.start(launch)) {
            assertThat(context.getBeansOfType(Round9CcPackagedFailureHarness.HarnessState.class)).hasSize(1);
            assertThat(context.getBeansOfType(Round9CcBarrierExecutionBoundaryHook.class)).hasSize(1);
            assertThat(context.getBeansOfType(Round9CcMockCreationProviderAdapter.class)).hasSize(1);
            assertThat(context.containsBeanDefinition("round9CcBatch1SeedCoordinator")).isFalse();
            assertThat(context.getBean(CreationExecutionBoundaryHook.class))
                    .isSameAs(context.getBean(Round9CcBarrierExecutionBoundaryHook.class));
        }
    }

    @Test
    void normalCompletionSeedsThroughDispatcherAndClosesOnlyAfterTerminalEvidence() throws Exception {
        Round9CcFixture fixture = fixture();
        prepare(fixture, Round9CcScenario.NORMAL_COMPLETION);
        var launch = launch(fixture, Round9CcScenario.NORMAL_COMPLETION, "5");
        ConfigurableApplicationContext context = Round9CcPackagedFailureHarness.start(launch);
        try {
            assertThat(context.getBean(CreationRepository.class).count()).isZero();

            Round9CcNormalCompletionCoordinator.Completion completion =
                    Round9CcPackagedFailureHarness.completeNormalCompletionAndClose(context, launch);

            assertThat(context.isActive()).isFalse();
            assertThat(completion.creationStatus()).isEqualTo(CreationStatus.SUCCEEDED.name());
            assertThat(completion.stepStatus()).isEqualTo(CreationStepStatus.SUCCEEDED.name());
            assertThat(completion.dispatchState()).isEqualTo(ProviderDispatchState.RESULT_PERSISTED.name());
            assertThat(completion.executionFinished()).isTrue();
            assertThat(completion.claimAndLeaseClear()).isTrue();
            assertThat(completion.retryVersion()).isZero();
            assertThat(completion.entries()).isEqualTo(1);
            assertThat(completion.returns()).isEqualTo(1);
            assertThat(completion.closes()).isEqualTo(1);
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }
    }

    @Test
    void nonNormalScenarioIsNotAutoSeededAndCannotRunNormalCoordinator() throws Exception {
        Round9CcFixture fixture = fixture();
        prepare(fixture, Round9CcScenario.ALL_SUCCEEDED_FINALIZATION);
        var launch = launch(fixture, Round9CcScenario.ALL_SUCCEEDED_FINALIZATION, "5");
        try (ConfigurableApplicationContext context = Round9CcPackagedFailureHarness.start(launch)) {
            assertThat(context.getBean(CreationRepository.class).count()).isZero();
            assertThatThrownBy(() -> Round9CcPackagedFailureHarness.completeNormalCompletion(context, launch))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(context.getBean(CreationRepository.class).count()).isZero();
        }
    }

    @Test
    void everyBatch1SeedPhaseUsesNormalAdmissionForExactlyOneSyntheticCreation() throws Exception {
        for (Round9CcScenario scenario : List.of(
                Round9CcScenario.TERM_BEFORE_CLAIM,
                Round9CcScenario.TERM_AFTER_CLAIM,
                Round9CcScenario.TERM_DURING_NOT_SENT,
                Round9CcScenario.INT_AFTER_SEND_STARTED)) {
            Round9CcFixture fixture = fixture();
            prepare(fixture, scenario);
            var launch = Round9CcPackagedFailureHarness.Launch.parse(new String[] {
                    "--fixture-root=" + fixture.root(),
                    "--instance=seedA",
                    "--scenario=" + scenario.name(),
                    "--phase=SEED"
            });
            try (ConfigurableApplicationContext context = Round9CcPackagedFailureHarness.start(launch)) {
                assertThat(context.getBeansOfType(CreationRecoveryGate.class)).hasSize(1);
                assertThat(context.getBean(CreationRecoveryGate.class).isOpen()).isTrue();
                context.getBean(Round9CcBatch1SeedCoordinator.class).seed(launch);

                CreationRepository creations = context.getBean(CreationRepository.class);
                Creation creation = creations.findAll().get(0);
                List<CreationStep> persistedSteps = context.getBean(CreationStepRepository.class)
                        .findByCreationIdOrderByStepIndexAsc(creation.getId());
                assertThat(creations.count()).isEqualTo(1);
                assertThat(creation.getStatus()).isEqualTo(CreationStatus.QUEUED.name());
                assertThat(persistedSteps).hasSize(1);
                assertThat(persistedSteps.get(0).getStatus()).isEqualTo(CreationStepStatus.PENDING.name());
                assertThat(persistedSteps.get(0).getProviderDispatchState())
                        .isEqualTo(ProviderDispatchState.NOT_SENT.name());
                assertThat(context.getBean(CreationExecutionAttemptRepository.class)
                        .findByCreationIdAndFinishedAtIsNull(creation.getId())).isPresent();
                assertThat(context.getBean(GenerationLogRepository.class).count()).isZero();
                assertThat(context.getBean(PaintingRepository.class).count()).isZero();
                assertThat(Round9CcMockJournal.read(fixture.journalFile("seedA"))).isEmpty();
            }
            deleteFixture(fixture.root());
            root = null;
        }
    }

    @Test
    void normalCompletionRejectsNonPositiveTimeoutBeforeSpringStartupOrSeeding() throws Exception {
        Round9CcFixture fixture = fixture();
        prepare(fixture, Round9CcScenario.NORMAL_COMPLETION);
        var launch = launch(fixture, Round9CcScenario.NORMAL_COMPLETION, "0");

        assertRejectedBeforeContextOrSeed(fixture, launch);
    }

    @Test
    void normalCompletionRejectsOverMaximumTimeoutBeforeSpringStartupOrSeeding() throws Exception {
        Round9CcFixture fixture = fixture();
        prepare(fixture, Round9CcScenario.NORMAL_COMPLETION);
        var launch = launch(fixture, Round9CcScenario.NORMAL_COMPLETION, "301");

        assertRejectedBeforeContextOrSeed(fixture, launch);
    }

    @Test
    void normalCompletionCoordinatorIsNotAComponent() {
        assertThat(Round9CcNormalCompletionCoordinator.class.getAnnotations()).isEmpty();
        assertThat(Round9CcBatch1SeedCoordinator.class.getAnnotations()).isEmpty();
        assertThat(Round9CcPackagedFailureHarness.RuntimeEvidenceListener.class.getAnnotations()).isEmpty();
    }

    private static void prepare(Round9CcFixture fixture, Round9CcScenario scenario) throws Exception {
        Files.writeString(fixture.propertiesFile(), "fixture.only=true\n", StandardCharsets.UTF_8);
        Round9CcFixture.setPrivateFile(fixture.propertiesFile());
        Round9CcFixtureManifest.write(fixture, scenario);
    }

    private static Round9CcPackagedFailureHarness.Launch launch(
            Round9CcFixture fixture, Round9CcScenario scenario, String timeoutSeconds) {
        return Round9CcPackagedFailureHarness.Launch.parse(new String[] {
                "--fixture-root=" + fixture.root(),
                "--instance=workerA",
                "--scenario=" + scenario.name(),
                "--failpoint-timeout-seconds=" + timeoutSeconds
        });
    }

    private static void assertRejectedBeforeContextOrSeed(
            Round9CcFixture fixture, Round9CcPackagedFailureHarness.Launch launch) throws Exception {
        assertThatThrownBy(() -> Round9CcPackagedFailureHarness.start(launch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ROUND 9C-C failpoint timeout is invalid");
        assertThat(Files.exists(fixture.root().resolve("db/fixture.db"))).isFalse();
        try (var runtimeFiles = Files.list(fixture.requireDirectory("runtime"))) {
            assertThat(runtimeFiles.toList()).isEmpty();
        }
        assertThat(Round9CcMockJournal.read(fixture.journalFile("workerA"))).isEmpty();
    }

    private Round9CcFixture fixture() throws Exception {
        root = Files.createTempDirectory(Path.of("/tmp"), "auralink-round9cc.");
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        for (String name : List.of("db", "managed", "provider-staging", "env", "control", "counters", "logs", "runtime", "manifest")) {
            Path directory = root.resolve(name);
            Files.createDirectory(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        Path marker = root.resolve(".round9cc-fixture");
        Files.writeString(marker, "ROUND9CC_FIXTURE\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-------"));
        return Round9CcFixture.validate(root);
    }

    private static void deleteFixture(Path fixtureRoot) throws Exception {
        try (var paths = Files.walk(fixtureRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }
}
