package com.auralink.ops.round9cc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.auralink.creation.CreationExecutionBoundary;

class Round9CcFixtureAndJournalTest {

    private Path root;

    @AfterEach
    void removeOwnedFixture() throws Exception {
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
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
    void validatesPrivateFixtureAndKeepsJournalAcrossNewInstances() throws Exception {
        Round9CcFixture fixture = fixture();
        Round9CcMockJournal first = new Round9CcMockJournal(fixture, "NORMAL_COMPLETION", "workerA");
        first.entry("MOCK_STEP");
        first.returned("MOCK_STEP");
        Round9CcMockJournal second = new Round9CcMockJournal(fixture, "NORMAL_COMPLETION", "workerA");
        second.closed("RESULT_ARTIFACT");

        List<Round9CcMockJournal.Record> records = Round9CcMockJournal.read(fixture.journalFile("workerA"));

        assertThat(records).extracting(Round9CcMockJournal.Record::sequence).containsExactly(1L, 2L, 3L);
        assertThat(records).extracting(Round9CcMockJournal.Record::event).containsExactly(
                Round9CcMockJournal.Event.ENTRY,
                Round9CcMockJournal.Event.RETURN,
                Round9CcMockJournal.Event.CLOSE);
        assertThat(Files.getPosixFilePermissions(fixture.journalFile("workerA")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void createsAnEmptyPrivateJournalForAnActiveHarnessRole() throws Exception {
        Round9CcFixture fixture = fixture();

        new Round9CcMockJournal(fixture, "TERM_BEFORE_CLAIM", "workerA");

        Path journal = fixture.journalFile("workerA");
        assertThat(Files.isRegularFile(journal, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(journal)).isFalse();
        assertThat(Files.getPosixFilePermissions(journal))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Round9CcMockJournal.read(journal)).isEmpty();
    }

    @Test
    void selectedBarrierWritesOnlyTheSafeBoundaryAndReleasesBoundedly() throws Exception {
        Round9CcFixture fixture = fixture();
        Round9CcMockJournal journal = new Round9CcMockJournal(fixture, "TERM_AFTER_CLAIM", "workerA");
        Round9CcBarrierExecutionBoundaryHook hook = new Round9CcBarrierExecutionBoundaryHook(
                fixture, "workerA", Set.of(CreationExecutionBoundary.CLAIM_COMMITTED_BEFORE_SUBMIT),
                Duration.ofSeconds(2), journal);
        CountDownLatch entered = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                entered.countDown();
                hook.reached(CreationExecutionBoundary.CLAIM_COMMITTED_BEFORE_SUBMIT);
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            Path reached = fixture.controlDirectory("workerA")
                    .resolve("CLAIM_COMMITTED_BEFORE_SUBMIT.reached");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!Files.exists(reached) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(Files.readString(reached, StandardCharsets.UTF_8)).isEqualTo("CLAIM_COMMITTED_BEFORE_SUBMIT\n");
            Path release = fixture.controlDirectory("workerA")
                    .resolve("CLAIM_COMMITTED_BEFORE_SUBMIT.release");
            Files.writeString(release, "RELEASE\n", StandardCharsets.UTF_8);
            Round9CcFixture.setPrivateFile(release);
            future.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void scenariosCoverEveryRequiredC2NameAndCarryProviderFreeRecoveryExpectations() {
        assertThat(Round9CcScenario.values()).hasSize(22);
        assertThat(Round9CcScenario.MISSING_MANAGED_FILE.definition().safeCode())
                .isEqualTo("CREATION_RESULT_PERSISTENCE_INCONSISTENT");
        assertThat(Round9CcScenario.KILL_DURING_MOCK.definition().recoveryCalls()).isEqualTo("ZERO");
        assertThat(Round9CcScenario.NORMAL_COMPLETION.manifestValues()).containsEntry("scenario", "NORMAL_COMPLETION");
        assertThat(Round9CcScenario.NORMAL_COMPLETION.definition().recoveryCalls()).isEqualTo("ZERO");
        for (Round9CcScenario scenario : List.of(
                Round9CcScenario.TERM_BEFORE_CLAIM,
                Round9CcScenario.TERM_AFTER_CLAIM,
                Round9CcScenario.TERM_DURING_NOT_SENT,
                Round9CcScenario.INT_AFTER_SEND_STARTED)) {
            assertThat(scenario.definition().recoveryCalls()).isEqualTo("ZERO");
            assertThat(scenario.manifestValues()).containsEntry("recoveryProviderCalls", "ZERO");
        }
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
}
