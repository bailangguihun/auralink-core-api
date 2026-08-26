package com.auralink.ops.round9cc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Exercises the shared supervisor helper without starting a Spring Harness. */
@EnabledOnOs(OS.LINUX)
class Round9CcStartInstanceSupervisorScriptTest {

    private static final String SCENARIO = "TERM_BEFORE_CLAIM";

    private Path root;
    private Process ownedProcess;
    private SyntheticProcessIdentity syntheticSignalChild;
    private SyntheticProcessIdentity syntheticSignalMonitor;

    @AfterEach
    void tearDown() throws Exception {
        destroyExactSyntheticProcess(syntheticSignalChild);
        destroyExactSyntheticProcess(syntheticSignalMonitor);
        if (ownedProcess != null && ownedProcess.isAlive()) {
            ownedProcess.destroyForcibly();
            ownedProcess.waitFor(5, TimeUnit.SECONDS);
        }
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    @Test
    @Timeout(30)
    void seedAcceptsPortEvidenceAfterFifteenSecondsWithinTheSixtySecondDeadline() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("17");
        writePidAndStart("seedA", ownedProcess.pid(), processStart(ownedProcess.pid()));
        writeRuntime("seedA", "phase", "SEED\n");

        CompletableFuture<CommandResult> readiness = CompletableFuture.supplyAsync(
                () -> awaitReadiness("seedA", "SEED", "SEEDER", 60));

        int port = unusedPort();
        Thread.sleep(16_000);
        writeRuntime("seedA", "port", port + "\n");
        writeRuntime("seedA", "role", "SEEDER\n");
        writeRuntime("seedA", "boundary", "NO_BACKEND_ENV\nMOCK_ONLY_NO_REAL_PROVIDER\n");
        Thread.sleep(200);
        try (ServerSocket listener = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
            assertThat(ownedProcess.waitFor()).isZero();
        }
        writeRuntime("seedA", "exit", "0\n");
        writeSeedCompletion("seedA");

        CommandResult result = readiness.get(10, TimeUnit.SECONDS);

        assertThat(result.exitCode()).as("supervisor output: %s", result.output()).isZero();
        assertThat(result.output()).isEmpty();
    }

    @Test
    void seedTransitioningFromAliveToStoppedBeforeListenerReadinessIsAcceptedWithCompleteEvidence() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("2");
        writePidAndStart("seedA", ownedProcess.pid(), processStart(ownedProcess.pid()));
        writeRuntime("seedA", "phase", "SEED\n");
        writeCommonRuntimeEvidence("seedA", "SEEDER", unusedPort());

        CompletableFuture<CommandResult> readiness = CompletableFuture.supplyAsync(
                () -> awaitReadiness("seedA", "SEED", "SEEDER", 5));
        Thread.sleep(200);
        assertThat(ownedProcess.waitFor()).isZero();
        writeRuntime("seedA", "exit", "0\n");
        writeSeedCompletion("seedA");

        CommandResult result = readiness.get(5, TimeUnit.SECONDS);

        assertThat(result.exitCode()).as("supervisor output: %s", result.output()).isZero();
        assertThat(result.output()).isEmpty();
    }

    @Test
    void completedSeedIsAcceptedOnlyWithAllCompletionArtifacts() throws Exception {
        root = fixture();
        writeStoppedSeed("seedA", "0\n", true, true, unusedPort() + "\n");

        CommandResult result = awaitReadiness("seedA", "SEED", "SEEDER", 2);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEmpty();
    }

    @Test
    void completedSeedWithoutSeedEvidenceFailsClosed() throws Exception {
        root = fixture();
        writeStoppedSeed("seedA", "0\n", false, true, unusedPort() + "\n");

        assertSafeFailure(awaitReadiness("seedA", "SEED", "SEEDER", 2), "SEED_COMPLETION_EVIDENCE_INVALID");
    }

    @Test
    void completedSeedWithoutBoundaryEvidenceFailsClosed() throws Exception {
        root = fixture();
        writeStoppedSeed("seedA", "0\n", true, false, unusedPort() + "\n");

        assertSafeFailure(awaitReadiness("seedA", "SEED", "SEEDER", 2), "INSTANCE_RUNTIME_EVIDENCE_INVALID");
    }

    @Test
    void completedSeedWithMalformedPortEvidenceFailsClosed() throws Exception {
        root = fixture();
        writeStoppedSeed("seedA", "0\n", true, true, "not-a-port\n");

        assertSafeFailure(awaitReadiness("seedA", "SEED", "SEEDER", 2), "INSTANCE_PORT_INVALID");
    }

    @Test
    void nonzeroCompletedSeedExitFailsWithTheExitMismatchCode() throws Exception {
        root = fixture();
        writeStoppedSeed("seedA", "1\n", false, false, "not-a-port\n");

        assertSafeFailure(awaitReadiness("seedA", "SEED", "SEEDER", 2), "PROCESS_EXIT_MISMATCH");
    }

    @Test
    void initialNeverReportsAStoppedInstanceAsReady() throws Exception {
        root = fixture();
        Process completed = harnessShapedShell("1");
        String start = processStart(completed.pid());
        assertThat(completed.waitFor()).isZero();
        writePidAndStart("workerA", completed.pid(), start);
        writeRuntime("workerA", "phase", "INITIAL\n");
        writeRuntime("workerA", "role", "DISPATCHER_WORKER\n");
        writeRuntime("workerA", "port", unusedPort() + "\n");
        writeRuntime("workerA", "boundary", "NO_BACKEND_ENV\nMOCK_ONLY_NO_REAL_PROVIDER\n");
        writeRuntime("workerA", "exit", "0\n");

        assertSafeFailure(awaitReadiness("workerA", "INITIAL", "DISPATCHER_WORKER", 2), "INITIAL_LIVE_REQUIRED");
    }

    @Test
    void hungStartupStillEndsAtTheBoundedDeadline() throws Exception {
        root = fixture();

        assertSafeFailure(awaitReadiness("workerA", "INITIAL", "DISPATCHER_WORKER", 1), "INSTANCE_START_TIMEOUT");
    }

    @Test
    void exactOwnedAliveSeedWhoseListenerNeverAppearsFailsAtTheBoundedDeadline() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5");
        writePidAndStart("seedA", ownedProcess.pid(), processStart(ownedProcess.pid()));
        writeRuntime("seedA", "phase", "SEED\n");
        writeCommonRuntimeEvidence("seedA", "SEEDER", unusedPort());

        assertSafeFailure(awaitReadiness("seedA", "SEED", "SEEDER", 1), "LISTENER_NOT_READY");
    }

    @Test
    // The explicit EVIDENCE_PENDING prefix adds one valid bounded probe before
    // exact ownership; the second STOPPED probe is the completion-boundary
    // termination recheck already required for one-shot acceptance.
    void seedToleratesOneTransientOwnershipRejectionOnlyAfterExactOwnerWasObserved() throws Exception {
        root = fixture();
        writeRuntime("seedA", "phase", "SEED\n");
        writeCommonRuntimeEvidence("seedA", "SEEDER", unusedPort());
        writeSeedCompletion("seedA");

        CommandResult result = awaitReadinessWithProbeSequence(
                "seedA", "SEED", "SEEDER", 2, 3,
                "EVIDENCE_PENDING", "ALIVE", "OWNERSHIP_REJECTED", "STOPPED");

        assertThat(result.exitCode()).as("supervisor output: %s", result.output()).isZero();
        assertThat(result.output()).isEmpty();
        assertOneShotProbeTrace();
        assertThat(probeSequenceCalls()).isEqualTo(5);
    }

    @Test
    void recoveryToleratesOneTransientOwnershipRejectionOnlyAfterExactOwnerWasObserved() throws Exception {
        root = fixture();
        writeRuntime("recoveryA", "phase", "RECOVERY\n");
        writeCommonRuntimeEvidence("recoveryA", "RECOVERY", unusedPort());
        writeRecoveryCompletion("recoveryA");

        CommandResult result = awaitReadinessWithProbeSequence(
                "recoveryA", "RECOVERY", "RECOVERY", 2, 3,
                "EVIDENCE_PENDING", "ALIVE", "OWNERSHIP_REJECTED", "STOPPED");

        assertThat(result.exitCode()).as("supervisor output: %s", result.output()).isZero();
        assertThat(result.output()).isEmpty();
        assertOneShotProbeTrace();
        assertThat(probeSequenceCalls()).isEqualTo(5);
    }

    @Test
    void oneShotOwnershipRejectionBeforeAnyExactOwnerObservationFailsImmediately() throws Exception {
        root = fixture();

        CommandResult result = awaitReadinessWithProbeSequence(
                "seedA", "SEED", "SEEDER", 2, 0, "OWNERSHIP_REJECTED");

        assertSafeFailure(result, "PID_OWNERSHIP_REJECTED");
        assertThat(probeSequenceCalls()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void persistentOneShotOwnershipRejectionAfterExactOwnerObservationFailsClosedAtDeadline() throws Exception {
        root = fixture();
        writeRuntime("seedA", "phase", "SEED\n");
        writeCommonRuntimeEvidence("seedA", "SEEDER", unusedPort());

        CommandResult result = awaitReadinessWithProbeSequence(
                "seedA", "SEED", "SEEDER", 1, 0,
                "ALIVE", "OWNERSHIP_REJECTED");

        assertSafeFailure(result, "PID_OWNERSHIP_REJECTED");
        assertThat(probeSequenceCalls()).isGreaterThan(1);
    }

    @Test
    void initialOwnershipRejectionRemainsImmediateEvenAfterExactOwnerWasObserved() throws Exception {
        root = fixture();
        writeRuntime("workerA", "phase", "INITIAL\n");
        writeCommonRuntimeEvidence("workerA", "DISPATCHER_WORKER", unusedPort());
        writeRuntime("workerA", "exit", "0\n");

        CommandResult result = awaitReadinessWithProbeSequence(
                "workerA", "INITIAL", "DISPATCHER_WORKER", 2, 0,
                "ALIVE", "OWNERSHIP_REJECTED");

        assertSafeFailure(result, "PID_OWNERSHIP_REJECTED");
        assertThat(probeSequenceCalls()).isEqualTo(2);
    }

    @Test
    void initialRequiresAnExactOwnedLiveHarnessAndListeningRecordedPort() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5");
        writePidAndStart("workerA", ownedProcess.pid(), processStart(ownedProcess.pid()));
        writeRuntime("workerA", "phase", "INITIAL\n");
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            writeCommonRuntimeEvidence("workerA", "DISPATCHER_WORKER", listener.getLocalPort());

            CommandResult result = awaitReadiness("workerA", "INITIAL", "DISPATCHER_WORKER", 2);

            assertThat(result.exitCode()).as("supervisor output: %s", result.output()).isZero();
            assertThat(ownedProcess.isAlive()).isTrue();
        }
    }

    @Test
    @Timeout(10)
    void atomicPublisherKeepsFinalIdentityPathHiddenUntilThePrivateValueIsComplete() throws Exception {
        root = fixture();
        Path finalPid = root.resolve("runtime/seedA.pid");
        Path ready = root.resolve("control/pid-publish-ready");
        Path release = root.resolve("control/pid-publish-release");

        Process publisher = startPausedAtomicPublisher(finalPid, "12345", ready, release);
        Path temporary = awaitPublishedTemporary(ready);

        assertThat(Files.exists(finalPid)).isFalse();
        assertThat(temporary.getParent()).isEqualTo(root.resolve("runtime"));
        assertPrivateScalar(temporary, "12345");

        Files.createFile(release);
        assertSuccessfulPublisher(publisher);

        assertPrivateScalar(finalPid, "12345");
        assertThat(Files.exists(temporary)).isFalse();
        assertThat(runtimePublicationTemporaries()).isEmpty();
    }

    @Test
    @Timeout(10)
    void atomicPidThenStartPublicationKeepsInitialReadinessPendingUntilThePairIsComplete() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5");
        writeRuntime("workerA", "phase", "INITIAL\n");
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            writeCommonRuntimeEvidence("workerA", "DISPATCHER_WORKER", listener.getLocalPort());
            assertSuccessfulPublisher(publishAtomicRuntimeValue(
                    root.resolve("runtime/workerA.pid"), Long.toString(ownedProcess.pid())));

            Path probeObservation = root.resolve("control/initial-pair-probe");
            CompletableFuture<CommandResult> readiness = CompletableFuture.supplyAsync(
                    () -> awaitReadinessRecordingProbe(
                            "workerA", "INITIAL", "DISPATCHER_WORKER", 3, probeObservation));
            Path ready = root.resolve("control/start-publish-ready");
            Path release = root.resolve("control/start-publish-release");
            Path finalStart = root.resolve("runtime/workerA.start");
            Process publisher = startPausedAtomicPublisher(finalStart, processStart(ownedProcess.pid()), ready, release);
            Path temporary = awaitPublishedTemporary(ready);

            assertPrivateScalar(root.resolve("runtime/workerA.pid"), Long.toString(ownedProcess.pid()));
            assertThat(Files.exists(finalStart)).isFalse();
            assertPrivateScalar(temporary, processStart(ownedProcess.pid()));
            assertEventuallyObservedProbe(probeObservation, "EVIDENCE_PENDING", readiness);
            assertThat(readiness.isDone()).as("supervisor completed before identity pair publication").isFalse();

            Files.createFile(release);
            assertSuccessfulPublisher(publisher);
            CommandResult result = readiness.get(5, TimeUnit.SECONDS);

            assertThat(result.exitCode()).as("supervisor output: %s", result.output()).isZero();
            assertThat(result.output()).isEmpty();
            assertPrivateScalar(finalStart, processStart(ownedProcess.pid()));
        }
    }

    @Test
    @Timeout(10)
    void atomicIdentityPublicationSupportsSeedOneShotCompletionAfterTheExistingExitRace() throws Exception {
        assertAtomicOneShotPublicationAndCompletion("seedA", "SEED", "SEEDER", "seed");
    }

    @Test
    @Timeout(10)
    void atomicIdentityPublicationSupportsRecoveryOneShotCompletion() throws Exception {
        assertAtomicOneShotPublicationAndCompletion("recoveryA", "RECOVERY", "RECOVERY", "recovery");
    }

    @Test
    void malformedFinalIdentityPairRemainsImmediatelyFailClosedAfterAtomicPublication() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5");
        writeRuntime("workerA", "phase", "INITIAL\n");
        writeCommonRuntimeEvidence("workerA", "DISPATCHER_WORKER", unusedPort());
        assertSuccessfulPublisher(publishAtomicRuntimeValue(
                root.resolve("runtime/workerA.pid"), Long.toString(ownedProcess.pid())));
        assertSuccessfulPublisher(publishAtomicRuntimeValue(
                root.resolve("runtime/workerA.start"), processStart(ownedProcess.pid())));
        privateFile(root.resolve("runtime/workerA.start"), "not-a-start-time\n");

        assertSafeFailure(awaitReadiness("workerA", "INITIAL", "DISPATCHER_WORKER", 2),
                "INSTANCE_RUNTIME_EVIDENCE_INVALID");
    }

    @Test
    void probeTreatsAbsentAndIndividuallyValidIdentityEvidenceAsPending() throws Exception {
        root = fixture();

        assertProbeState(probeInstanceState("seedA"), "EVIDENCE_PENDING");

        writeRuntime("seedA", "pid", "12345\n");
        assertProbeState(probeInstanceState("seedA"), "EVIDENCE_PENDING");

        Files.delete(root.resolve("runtime/seedA.pid"));
        writeRuntime("seedA", "start", "67890\n");
        assertProbeState(probeInstanceState("seedA"), "EVIDENCE_PENDING");
    }

    @Test
    void probeRejectsUnsafeOrMalformedVisiblePartialIdentityEvidence() throws Exception {
        root = fixture();
        Path pid = root.resolve("runtime/seedA.pid");

        Files.createSymbolicLink(pid, root.resolve("control/missing-pid"));
        assertProbeState(probeInstanceState("seedA"), "EVIDENCE_INVALID");
        Files.delete(pid);

        privateFile(pid, "12345\n");
        Files.setPosixFilePermissions(pid, PosixFilePermissions.fromString("rw-r--r--"));
        assertProbeState(probeInstanceState("seedA"), "EVIDENCE_INVALID");
        Files.delete(pid);

        privateFile(pid, "12345\n");
        Path hardLink = root.resolve("control/seedA.pid.link");
        Files.createLink(hardLink, pid);
        assertProbeState(probeInstanceState("seedA"), "EVIDENCE_INVALID");
        Files.delete(hardLink);
        Files.delete(pid);

        for (String malformed : List.of("", "not-a-pid\n", "12345\n67890\n")) {
            privateFile(pid, malformed);
            assertProbeState(probeInstanceState("seedA"), "EVIDENCE_INVALID");
            Files.delete(pid);
        }
    }

    @Test
    void pendingProbeResultIsNotReinterpretedWhenStartPublishesBeforeReadinessHandlesIt() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5");
        writeRuntime("workerA", "phase", "INITIAL\n");
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            writeCommonRuntimeEvidence("workerA", "DISPATCHER_WORKER", listener.getLocalPort());
            writeRuntime("workerA", "pid", ownedProcess.pid() + "\n");
            Path finalStart = root.resolve("runtime/workerA.start");

            CommandResult result = awaitReadinessWithFirstProbeAction(
                    "workerA", "INITIAL", "DISPATCHER_WORKER", 2,
                    "EVIDENCE_PENDING", finalStart, processStart(ownedProcess.pid()));

            assertThat(result.exitCode()).as("supervisor output: %s", result.output()).isZero();
            assertThat(result.output()).isEmpty();
            assertPrivateScalar(finalStart, processStart(ownedProcess.pid()));
            assertThat(firstProbeActionCalls()).isEqualTo(2);
        }
    }

    @Test
    @Timeout(5)
    void finalPendingProbeResultRemainsATimeoutWhenStartPublishesBeforeItIsHandled() throws Exception {
        root = fixture();
        writeRuntime("seedA", "pid", "12345\n");
        Path finalStart = root.resolve("runtime/seedA.start");

        CommandResult result = awaitReadinessWithFinalPendingPublication(
                "seedA", "SEED", "SEEDER", 1, finalStart, "67890");

        assertThat(result.exitCode()).as("supervisor output: %s", result.output()).isEqualTo(124);
        assertThat(result.output()).as("supervisor output: %s", result.output())
                .isEqualTo("ROUND9CC_ERROR:INSTANCE_START_TIMEOUT");
        assertPrivateScalar(finalStart, "67890");
    }

    @Test
    void invalidProbeResultIsNotReinterpretedWhenEvidenceIsRepairedBeforeItIsHandled() throws Exception {
        root = fixture();
        writeRuntime("seedA", "pid", "not-a-pid\n");
        writeRuntime("seedA", "start", "67890\n");

        CommandResult result = awaitReadinessWithFirstProbeAction(
                "seedA", "SEED", "SEEDER", 2,
                "EVIDENCE_INVALID", root.resolve("runtime/seedA.pid"), "12345");

        assertSafeFailure(result, "INSTANCE_RUNTIME_EVIDENCE_INVALID");
        assertPrivateScalar(root.resolve("runtime/seedA.pid"), "12345");
        assertThat(firstProbeActionCalls()).isEqualTo(1);
    }

    @Test
    void terminatedInstanceRejectsPendingAndInvalidIdentityEvidence() throws Exception {
        root = fixture();

        assertSafeFailure(assertTerminatedInstance("seedA"), "INSTANCE_RUNTIME_EVIDENCE_INVALID");

        writeRuntime("seedA", "pid", "not-a-pid\n");
        assertSafeFailure(assertTerminatedInstance("seedA"), "INSTANCE_RUNTIME_EVIDENCE_INVALID");
    }

    @Test
    void atomicPublisherRejectsAnExistingFinalDestinationWithoutOverwritingIt() throws Exception {
        root = fixture();
        Path finalPid = root.resolve("runtime/seedA.pid");
        privateFile(finalPid, "777\n");

        assertSafeFailure(publishAtomicRuntimeValue(finalPid, "12345"), "RUNTIME_FILE_EXISTS");
        assertPrivateScalar(finalPid, "777");
        assertThat(runtimePublicationTemporaries()).isEmpty();
    }

    @Test
    void atomicPublisherRejectsAPathOutsideTheValidatedPrivateRuntimeDirectory() throws Exception {
        root = fixture();
        Path outsideRuntime = root.resolve("logs/seedA.pid");

        assertSafeFailure(publishAtomicRuntimeValue(outsideRuntime, "12345"), "INSTANCE_RUNTIME_EVIDENCE_INVALID");
        assertThat(Files.exists(outsideRuntime)).isFalse();
        assertThat(runtimePublicationTemporaries()).isEmpty();
    }

    @Test
    @Timeout(10)
    void atomicPublisherCleansOnlyItsTemporaryFileWhenPublicationFindsANewDestination() throws Exception {
        root = fixture();
        Path finalStart = root.resolve("runtime/seedA.start");
        Path ready = root.resolve("control/start-collision-ready");
        Path release = root.resolve("control/start-collision-release");
        Process publisher = startPausedAtomicPublisher(finalStart, "54321", ready, release);
        Path temporary = awaitPublishedTemporary(ready);

        privateFile(finalStart, "preexisting\n");
        Files.createFile(release);
        assertSafeFailure(awaitPublisher(publisher), "RUNTIME_FILE_EXISTS");

        assertPrivateScalar(finalStart, "preexisting");
        assertThat(Files.exists(temporary)).isFalse();
        assertThat(runtimePublicationTemporaries()).isEmpty();
    }

    @Test
    void reusedPidAndOwnershipMismatchRemainRejectedBeforeReadiness() throws Exception {
        root = fixture();
        ownedProcess = new ProcessBuilder("bash", "-c", "while :; do sleep 1; done").start();
        writePidAndStart("workerA", ownedProcess.pid(), Long.toString(Long.parseLong(processStart(ownedProcess.pid())) + 1));

        assertSafeFailure(awaitReadiness("workerA", "INITIAL", "DISPATCHER_WORKER", 2), "PID_REUSE_REJECTED");

        Files.delete(root.resolve("runtime/workerA.start"));
        writeRuntime("workerA", "start", processStart(ownedProcess.pid()) + "\n");
        assertSafeFailure(awaitReadiness("workerA", "INITIAL", "DISPATCHER_WORKER", 2), "PID_OWNERSHIP_REJECTED");
    }

    @Test
    void exactOwnedLiveHarnessProcessIsClassifiedAlive() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5");
        writePidAndStart("seedA", ownedProcess.pid(), processStart(ownedProcess.pid()));

        assertProbeState(probeInstanceState("seedA"), "ALIVE");
    }

    @Test
    void rawLiveSnapshotEmitsOneParsableStateAndStartLine() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5");
        String expectedStart = processStart(ownedProcess.pid());

        CommandResult result = rawProcessSnapshot(ownedProcess.pid());
        String[] fields = result.output().split("\\|", -1);

        assertThat(result.exitCode()).as("snapshot output: %s", result.output()).isZero();
        assertThat(fields).as("snapshot output: %s", result.output()).hasSize(3);
        assertThat(fields[0]).as("snapshot output: %s", result.output()).isEqualTo("LIVE");
        assertThat(fields[1]).as("snapshot output: %s", result.output()).matches("[A-Za-z]");
        assertThat(fields[2]).as("snapshot output: %s", result.output()).isEqualTo(expectedStart);
    }

    @Test
    void rawSnapshotForAnInitiallyAbsentProcessIsStopped() {
        CommandResult result = rawProcessSnapshot(999_999_999L);

        assertThat(result.exitCode()).as("snapshot output: %s", result.output()).isZero();
        assertThat(result.output()).as("snapshot output: %s", result.output()).isEqualTo("STOPPED||");
    }

    @Test
    void exactLiveHarnessWithDifferentFixtureRootIsOwnershipRejected() throws Exception {
        root = fixture();
        String differentRoot = root.resolveSibling("auralink-round9cc.other-root").toString();
        ownedProcess = harnessShapedShell(
                "5", "com.auralink.ops.round9cc.Round9CcPackagedFailureHarness", differentRoot);
        writePidAndStart("seedA", ownedProcess.pid(), processStart(ownedProcess.pid()));

        assertProbeState(probeInstanceState("seedA"), "OWNERSHIP_REJECTED");
    }

    @Test
    void exactLiveHarnessWithDifferentLoaderMainIsOwnershipRejected() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5", "com.example.UnrelatedMain", root.toString());
        writePidAndStart("seedA", ownedProcess.pid(), processStart(ownedProcess.pid()));

        assertProbeState(probeInstanceState("seedA"), "OWNERSHIP_REJECTED");
    }

    @Test
    void matchingPidWithDifferentStartTimeIsClassifiedAsPidReused() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5");
        long staleStart = Long.parseLong(processStart(ownedProcess.pid())) + 1;
        writePidAndStart("seedA", ownedProcess.pid(), Long.toString(staleStart));

        assertProbeState(probeInstanceState("seedA"), "PID_REUSED");
    }

    @Test
    void changedStartTimeOnTheFinalSnapshotIsClassifiedAsPidReused() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5", "com.example.UnrelatedMain", root.toString());
        String recordedStart = processStart(ownedProcess.pid());
        writePidAndStart("seedA", ownedProcess.pid(), recordedStart);
        Path counter = root.resolve("control/final-snapshot-counter");

        CommandResult result = probeInstanceStateWithChangedFinalSnapshot(
                "seedA", recordedStart, Long.toString(Long.parseLong(recordedStart) + 1), counter);

        assertProbeState(result, "PID_REUSED");
        assertThat(Files.readString(counter, StandardCharsets.UTF_8).trim())
                .as("probe output: %s", result.output())
                .isEqualTo("2");
    }

    @Test
    void stoppedProcessIsClassifiedStopped() throws Exception {
        root = fixture();
        Process completed = harnessShapedShell("1");
        String start = processStart(completed.pid());
        assertThat(completed.waitFor()).isZero();
        writePidAndStart("seedA", completed.pid(), start);

        assertProbeState(probeInstanceState("seedA"), "STOPPED");
    }

    @Test
    void zombieAndExitKernelStatesAreClassifiedStoppedBeforeCommandLineOwnership() {
        CommandResult result = stoppedKernelStateClassifier();

        assertThat(result.exitCode()).as("state-classifier output: %s", result.output()).isZero();
        assertThat(result.output()).as("state-classifier output: %s", result.output())
                .isEqualTo("STOPPED_STATE_CLASSIFIER_OK");
    }

    @Test
    void processExitingBetweenFirstSnapshotAndCommandLineReadIsClassifiedStopped() throws Exception {
        root = fixture();
        Path exitTrigger = root.resolve("control/exit-after-first-snapshot");
        ownedProcess = harnessShapedShellThatExitsWhen(exitTrigger);
        writePidAndStart("seedA", ownedProcess.pid(), processStart(ownedProcess.pid()));

        CommandResult result = probeInstanceStateAfterFirstSnapshotExit("seedA", exitTrigger);

        assertProbeState(result, "STOPPED");
        assertThat(ownedProcess.waitFor(2, TimeUnit.SECONDS))
                .as("probe output: %s", result.output())
                .isTrue();
        assertThat(ownedProcess.exitValue()).as("probe output: %s", result.output()).isZero();
    }

    @Test
    void processProbeRunsUnderSetUWithoutAnUndefinedVariableFailure() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("5");
        writePidAndStart("seedA", ownedProcess.pid(), processStart(ownedProcess.pid()));

        CommandResult result = probeInstanceState("seedA");

        assertThat(result.exitCode()).as("probe output: %s", result.output()).isZero();
        assertThat(result.output()).as("probe output: %s", result.output()).isEqualTo("ALIVE");
    }

    @Test
    @Timeout(10)
    void asynchronousMonitorExecBoundaryResetsInheritedSigintAndPublishesExit130() throws Exception {
        root = fixture();
        SignalMonitorLaunch launch = startSignalDispositionMonitor();
        ownedProcess = launch.supervisor();
        long monitorPid = awaitPositivePrivateScalar(launch.monitorPid());
        syntheticSignalMonitor = new SyntheticProcessIdentity(monitorPid, processStart(monitorPid));
        long childPid = awaitPositivePrivateScalar(launch.childPid());
        String recordedStart = Long.toString(awaitPositivePrivateScalar(launch.childStart()));
        syntheticSignalChild = new SyntheticProcessIdentity(childPid, recordedStart);

        awaitProcessCommand(childPid, "sleep");
        assertThat(processStart(childPid)).isEqualTo(recordedStart);
        assertThat(processStatusMask(monitorPid, "SigIgn").testBit(1))
                .as("synthetic asynchronous monitor must reproduce inherited SIGINT ignore")
                .isTrue();
        assertThat(processStatusMask(childPid, "SigIgn").testBit(1))
                .as("child SigIgn must not include SIGINT")
                .isFalse();
        assertThat(processStatusMask(childPid, "SigIgn").testBit(14))
                .as("child SigIgn must not include SIGTERM")
                .isFalse();
        assertThat(processStatusMask(childPid, "SigBlk").testBit(1))
                .as("child SigBlk must not include SIGINT")
                .isFalse();

        assertSuccessfulExactSignal(sendExactSignal("INT", childPid));
        assertPublishedSignalExit(launch, 130);
    }

    @Test
    @Timeout(10)
    void asynchronousMonitorPreservesExactTermExit143() throws Exception {
        assertSyntheticMonitorSignalExit("TERM", 143);
    }

    @Test
    @Timeout(10)
    void asynchronousMonitorPreservesExactKillExit137() throws Exception {
        assertSyntheticMonitorSignalExit("KILL", 137);
    }

    @Test
    void startScriptDoesNotContainAnySignalOperation() throws Exception {
        String source = Files.readString(
                Path.of("src/test/scripts/round9cc/round9cc-start-instance.sh"), StandardCharsets.UTF_8);

        assertThat(source).contains("ROUND9CC_STARTUP_DEADLINE_SECONDS", "round9cc_wait_for_phase_readiness")
                .doesNotContain("kill ", "round9cc-signal-instance.sh");
    }

    private void assertSyntheticMonitorSignalExit(String signal, int expectedExit) throws Exception {
        root = fixture();
        SignalMonitorLaunch launch = startSignalDispositionMonitor();
        ownedProcess = launch.supervisor();
        long monitorPid = awaitPositivePrivateScalar(launch.monitorPid());
        syntheticSignalMonitor = new SyntheticProcessIdentity(monitorPid, processStart(monitorPid));
        long childPid = awaitPositivePrivateScalar(launch.childPid());
        String recordedStart = Long.toString(awaitPositivePrivateScalar(launch.childStart()));
        syntheticSignalChild = new SyntheticProcessIdentity(childPid, recordedStart);

        awaitProcessCommand(childPid, "sleep");
        assertThat(processStart(childPid)).isEqualTo(recordedStart);
        assertSuccessfulExactSignal(sendExactSignal(signal, childPid));
        assertPublishedSignalExit(launch, expectedExit);
    }

    private SignalMonitorLaunch startSignalDispositionMonitor() throws IOException {
        Path childPid = root.resolve("runtime/signal-child.pid");
        Path childStart = root.resolve("runtime/signal-child.start");
        Path monitorPid = root.resolve("runtime/signal-monitor.pid");
        Path exit = root.resolve("runtime/signal-child.exit");
        ProcessBuilder processBuilder = new ProcessBuilder(
                "env",
                "--ignore-signal=INT",
                "--",
                "bash",
                "-c",
                """
                        umask 077
                        (
                          set +e
                          env --default-signal=INT -- sleep 30 &
                          child_pid=$!
                          child_start="$(awk '{print $22}' "/proc/${child_pid}/stat" 2>/dev/null)"
                          [[ "${child_pid}" =~ ^[1-9][0-9]*$ \
                              && "${child_start}" =~ ^[1-9][0-9]*$ ]] || exit 2
                          printf '%s\\n' "${child_pid}" >"$1"
                          chmod 600 -- "$1"
                          printf '%s\\n' "${child_start}" >"$2"
                          chmod 600 -- "$2"
                          wait "${child_pid}"
                          status=$?
                          printf '%s\\n' "${status}" >"$4"
                          chmod 600 -- "$4"
                          exit "${status}"
                        ) &
                        monitor_pid=$!
                        printf '%s\\n' "${monitor_pid}" >"$3"
                        chmod 600 -- "$3"
                        wait "${monitor_pid}"
                        exit $?
                        """,
                "round9cc-signal-disposition-test",
                childPid.toString(),
                childStart.toString(),
                monitorPid.toString(),
                exit.toString());
        processBuilder.redirectErrorStream(true);
        return new SignalMonitorLaunch(processBuilder.start(), childPid, childStart, monitorPid, exit);
    }

    private long awaitPositivePrivateScalar(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file) && !Files.isSymbolicLink(file)
                    && Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))) {
                String value = Files.readString(file, StandardCharsets.UTF_8);
                if (value.matches("[1-9][0-9]*\\n")) {
                    return Long.parseLong(value.substring(0, value.length() - 1));
                }
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("private positive scalar was not published: " + file.getFileName());
    }

    private static void awaitProcessCommand(long pid, String expectedCommand) throws Exception {
        Path command = Path.of("/proc", Long.toString(pid), "comm");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(command)
                    && Files.readString(command, StandardCharsets.UTF_8).equals(expectedCommand + "\n")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("process did not exec the expected bounded test child: " + expectedCommand);
    }

    private static BigInteger processStatusMask(long pid, String field) throws IOException {
        String prefix = field + ":";
        String value = Files.readAllLines(Path.of("/proc", Long.toString(pid), "status"), StandardCharsets.UTF_8)
                .stream()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing process status field: " + field));
        return new BigInteger(value, 16);
    }

    private static CommandResult sendExactSignal(String signal, long pid) throws Exception {
        Process process = new ProcessBuilder("kill", "-" + signal, Long.toString(pid))
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(3, TimeUnit.SECONDS)).isTrue();
        return new CommandResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
    }

    private static void assertSuccessfulExactSignal(CommandResult result) {
        assertThat(result.exitCode()).as("exact signal output: %s", result.output()).isZero();
        assertThat(result.output()).as("exact signal output: %s", result.output()).isEmpty();
    }

    private void assertPublishedSignalExit(SignalMonitorLaunch launch, int expectedExit) throws Exception {
        boolean completed = ownedProcess.waitFor(5, TimeUnit.SECONDS);
        assertThat(completed).as("synthetic monitor did not terminate within its bounded wait").isTrue();
        String output = processOutput(ownedProcess);
        assertThat(ownedProcess.exitValue())
                .as("monitor output: %s", output)
                .isEqualTo(expectedExit);
        assertPrivateScalar(launch.exit(), Integer.toString(expectedExit));
        syntheticSignalChild = null;
        syntheticSignalMonitor = null;
    }

    private static String processOutput(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    private static void destroyExactSyntheticProcess(SyntheticProcessIdentity identity) throws InterruptedException {
        if (identity == null) {
            return;
        }
        try {
            if (!processStart(identity.pid()).equals(identity.start())) {
                return;
            }
        } catch (IOException ignored) {
            return;
        }
        ProcessHandle.of(identity.pid()).ifPresent(handle -> {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        ProcessHandle.of(identity.pid()).ifPresent(handle -> {
            try {
                handle.onExit().get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Best-effort exact-PID cleanup for a failed synthetic test only.
            }
        });
    }

    private Path fixture() throws Exception {
        Path fixtureRoot = Files.createTempDirectory(Path.of("/tmp"), "auralink-round9cc.");
        Files.setPosixFilePermissions(fixtureRoot, PosixFilePermissions.fromString("rwx------"));
        for (String directory : List.of("db", "managed", "provider-staging", "env", "control", "counters", "logs", "runtime", "manifest")) {
            Path path = fixtureRoot.resolve(directory);
            Files.createDirectory(path);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
        privateFile(fixtureRoot.resolve(".round9cc-fixture"), "ROUND9CC_FIXTURE\n");
        var manifest = Round9CcScenario.TERM_BEFORE_CLAIM.manifestValues();
        privateFile(fixtureRoot.resolve("manifest/scenario.properties"), """
                scenario=%s
                initialExpectedExit=%s
                seedExpectedExit=%s
                recoveryExpectedExit=%s
                recoveryProviderCalls=%s
                ordinaryDispatchResumes=%s
                """.formatted(
                manifest.get("scenario"),
                manifest.get("initialExpectedExit"),
                manifest.get("seedExpectedExit"),
                manifest.get("recoveryExpectedExit"),
                manifest.get("recoveryProviderCalls"),
                manifest.get("ordinaryDispatchResumes")));
        return fixtureRoot;
    }

    private Process harnessShapedShell(String seconds) throws IOException {
        return harnessShapedShell(
                seconds, "com.auralink.ops.round9cc.Round9CcPackagedFailureHarness", root.toString());
    }

    private Process harnessShapedShell(String seconds, String loaderMain, String fixtureRoot) throws IOException {
        return new ProcessBuilder(
                "bash",
                "-c",
                "deadline=$((SECONDS + $1)); while (( SECONDS < deadline )); do sleep 0.05; done",
                "-Dloader.main=" + loaderMain,
                seconds,
                "--fixture-root=" + fixtureRoot)
                .start();
    }

    private Process harnessShapedShellThatExitsWhen(Path exitTrigger) throws IOException {
        return new ProcessBuilder(
                "bash",
                "-c",
                "while [[ ! -f \"$2\" ]]; do sleep 0.01; done",
                "-Dloader.main=com.auralink.ops.round9cc.Round9CcPackagedFailureHarness",
                "--fixture-root=" + root,
                exitTrigger.toString())
                .start();
    }

    private void writeStoppedSeed(
            String instance, String exit, boolean seed, boolean boundary, String port) throws Exception {
        Process completed = harnessShapedShell("1");
        String start = processStart(completed.pid());
        assertThat(completed.waitFor()).isZero();
        writePidAndStart(instance, completed.pid(), start);
        writeRuntime(instance, "phase", "SEED\n");
        writeRuntime(instance, "role", "SEEDER\n");
        writeRuntime(instance, "port", port);
        if (boundary) {
            writeRuntime(instance, "boundary", "NO_BACKEND_ENV\nMOCK_ONLY_NO_REAL_PROVIDER\n");
        }
        writeRuntime(instance, "exit", exit);
        if (seed) {
            writeSeedCompletion(instance);
        }
    }

    private void writeSeedCompletion(String instance) throws IOException {
        writeRuntime(instance, "seed", """
                SCENARIO=TERM_BEFORE_CLAIM
                ROLE=SEEDER
                CREATIONS=1
                EXECUTION_ATTEMPTS=1
                MOCK_PROVIDER_CALLS=0
                """);
    }

    private void writeRecoveryCompletion(String instance) throws IOException {
        writeRuntime(instance, "recovery", """
                SCENARIO=TERM_BEFORE_CLAIM
                ROLE=RECOVERY
                RECOVERY_GATE_OPEN
                RECOVERY_PROVIDER_CALLS=ZERO
                ORDINARY_DISPATCH_RESUMES=true
                """);
    }

    private void writeCommonRuntimeEvidence(String instance, String role, int port) throws IOException {
        writeRuntime(instance, "port", port + "\n");
        writeRuntime(instance, "role", role + "\n");
        writeRuntime(instance, "boundary", "NO_BACKEND_ENV\nMOCK_ONLY_NO_REAL_PROVIDER\n");
    }

    private void writePidAndStart(String instance, long pid, String start) throws IOException {
        writeRuntime(instance, "pid", pid + "\n");
        writeRuntime(instance, "start", start + "\n");
    }

    private void writeRuntime(String instance, String suffix, String value) throws IOException {
        privateFile(root.resolve("runtime/" + instance + "." + suffix), value);
    }

    private CommandResult awaitReadiness(String instance, String phase, String role, int timeout) {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                "source \"$1\"; round9cc_wait_for_phase_readiness \"$2\" \"$3\" \"$4\" \"$5\" \"$6\" \"$7\"",
                "round9cc-supervisor-test",
                library,
                root.toString(),
                instance,
                SCENARIO,
                phase,
                role,
                Integer.toString(timeout));
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(timeout + 12L, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CommandResult awaitReadinessWithFirstProbeAction(
            String instance,
            String phase,
            String role,
            int timeout,
            String firstState,
            Path actionFile,
            String actionValue) throws IOException {
        Path counter = root.resolve("control/first-probe-action-counter");
        privateFile(counter, "0\n");
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                """
                        source "$1"
                        eval "$(declare -f round9cc_probe_instance_state \
                          | sed '1s/round9cc_probe_instance_state/round9cc_real_probe_instance_state/')"
                        round9cc_probe_instance_state() {
                          local calls state
                          calls="$(<"${ROUND9CC_TEST_PROBE_COUNTER}")"
                          [[ "${calls}" =~ ^[0-9]+$ ]] || return 1
                          calls=$((calls + 1))
                          printf '%s\\n' "${calls}" >"${ROUND9CC_TEST_PROBE_COUNTER}"
                          if (( calls == 1 )); then
                            state="$(round9cc_real_probe_instance_state "$@")"
                            [[ "${state}" == "${ROUND9CC_TEST_FIRST_STATE}" ]] || {
                              printf '%s\\n' "${state}"
                              return 0
                            }
                            printf '%s\\n' "${ROUND9CC_TEST_ACTION_VALUE}" >"${ROUND9CC_TEST_ACTION_FILE}"
                            chmod 600 -- "${ROUND9CC_TEST_ACTION_FILE}"
                            printf '%s\\n' "${state}"
                            return 0
                          fi
                          round9cc_real_probe_instance_state "$@"
                        }
                        round9cc_wait_for_phase_readiness "$2" "$3" "$4" "$5" "$6" "$7"
                        """,
                "round9cc-first-probe-action-test",
                library,
                root.toString(),
                instance,
                SCENARIO,
                phase,
                role,
                Integer.toString(timeout));
        processBuilder.environment().put("ROUND9CC_TEST_PROBE_COUNTER", counter.toString());
        processBuilder.environment().put("ROUND9CC_TEST_FIRST_STATE", firstState);
        processBuilder.environment().put("ROUND9CC_TEST_ACTION_FILE", actionFile.toString());
        processBuilder.environment().put("ROUND9CC_TEST_ACTION_VALUE", actionValue);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(timeout + 5L, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int firstProbeActionCalls() throws IOException {
        return Integer.parseInt(Files.readString(
                root.resolve("control/first-probe-action-counter"), StandardCharsets.UTF_8).trim());
    }

    private CommandResult awaitReadinessWithFinalPendingPublication(
            String instance, String phase, String role, int timeout, Path actionFile, String actionValue) throws IOException {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                """
                        source "$1"
                        eval "$(declare -f round9cc_probe_instance_state \
                          | sed '1s/round9cc_probe_instance_state/round9cc_real_probe_instance_state/')"
                        round9cc_probe_instance_state() {
                          round9cc_real_probe_instance_state "$@"
                        }
                        round9cc_before_final_phase_readiness_state_handling() {
                          [[ "$1" == 'EVIDENCE_PENDING' ]] || return 1
                          printf '%s\\n' "${ROUND9CC_TEST_ACTION_VALUE}" >"${ROUND9CC_TEST_ACTION_FILE}"
                          chmod 600 -- "${ROUND9CC_TEST_ACTION_FILE}"
                        }
                        round9cc_wait_for_phase_readiness "$2" "$3" "$4" "$5" "$6" "$7"
                        """,
                "round9cc-final-pending-publication-test",
                library,
                root.toString(),
                instance,
                SCENARIO,
                phase,
                role,
                Integer.toString(timeout));
        processBuilder.environment().put("ROUND9CC_TEST_ACTION_FILE", actionFile.toString());
        processBuilder.environment().put("ROUND9CC_TEST_ACTION_VALUE", actionValue);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(timeout + 5L, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CommandResult assertTerminatedInstance(String instance) {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                "source \"$1\"; round9cc_assert_terminated_instance \"$2\" \"$3\"",
                "round9cc-terminated-instance-test",
                library,
                root.toString(),
                instance);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CommandResult awaitReadinessRecordingProbe(
            String instance, String phase, String role, int timeout, Path probeObservation) {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                """
                        source "$1"
                        eval "$(declare -f round9cc_probe_instance_state \
                          | sed '1s/round9cc_probe_instance_state/round9cc_real_probe_instance_state/')"
                        probe_observation="$8"
                        round9cc_probe_instance_state() {
                          local state
                          state="$(round9cc_real_probe_instance_state "$@")"
                          printf '%s\\n' "${state}" >"${probe_observation}"
                          printf '%s\\n' "${state}"
                        }
                        round9cc_wait_for_phase_readiness "$2" "$3" "$4" "$5" "$6" "$7"
                        """,
                "round9cc-pair-observation-test",
                library,
                root.toString(),
                instance,
                SCENARIO,
                phase,
                role,
                Integer.toString(timeout),
                probeObservation.toString());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(timeout + 12L, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertAtomicOneShotPublicationAndCompletion(
            String instance, String phase, String role, String completionSuffix) throws Exception {
        root = fixture();
        ownedProcess = harnessShapedShell("1");
        String expectedStart = processStart(ownedProcess.pid());
        writeRuntime(instance, "phase", phase + "\n");
        writeCommonRuntimeEvidence(instance, role, unusedPort());
        assertSuccessfulPublisher(publishAtomicRuntimeValue(
                root.resolve("runtime/" + instance + ".pid"), Long.toString(ownedProcess.pid())));

        Path probeObservation = root.resolve("control/" + instance + "-pair-probe");
        CompletableFuture<CommandResult> readiness = CompletableFuture.supplyAsync(
                () -> awaitReadinessRecordingProbe(instance, phase, role, 5, probeObservation));
        Path ready = root.resolve("control/" + instance + "-start-ready");
        Path release = root.resolve("control/" + instance + "-start-release");
        Path finalStart = root.resolve("runtime/" + instance + ".start");
        Process publisher = startPausedAtomicPublisher(finalStart, expectedStart, ready, release);
        Path temporary = awaitPublishedTemporary(ready);

        assertThat(Files.exists(finalStart)).isFalse();
        assertPrivateScalar(temporary, expectedStart);
        assertEventuallyObservedProbe(probeObservation, "EVIDENCE_PENDING", readiness);
        assertThat(readiness.isDone()).as("supervisor completed before identity pair publication").isFalse();

        Files.createFile(release);
        assertSuccessfulPublisher(publisher);
        assertThat(ownedProcess.waitFor(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ownedProcess.exitValue()).isZero();
        writeRuntime(instance, "exit", "0\n");
        if ("seed".equals(completionSuffix)) {
            writeSeedCompletion(instance);
        } else {
            writeRecoveryCompletion(instance);
        }

        CommandResult result = readiness.get(5, TimeUnit.SECONDS);
        assertThat(result.exitCode()).as("supervisor output: %s", result.output()).isZero();
        assertThat(result.output()).isEmpty();
        assertPrivateScalar(finalStart, expectedStart);
    }

    private CommandResult publishAtomicRuntimeValue(Path finalFile, String value) {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-u",
                "-c",
                "source \"$1\"; round9cc_atomic_private_publish \"$2\" \"$3\" \"$4\"",
                "round9cc-atomic-publisher-test",
                library,
                root.toString(),
                finalFile.toString(),
                value);
        processBuilder.redirectErrorStream(true);
        try {
            return awaitPublisher(processBuilder.start());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Process startPausedAtomicPublisher(Path finalFile, String value, Path ready, Path release) throws IOException {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-u",
                "-c",
                """
                        source "$1"
                        ready_file="$5"
                        release_file="$6"
                        round9cc_before_atomic_runtime_publish() {
                          printf '%s\\n' "$1" >"${ready_file}"
                          local deadline=$((SECONDS + 5))
                          while (( SECONDS < deadline )); do
                            [[ -f "${release_file}" ]] && return 0
                            sleep 0.01
                          done
                          return 1
                        }
                        round9cc_atomic_private_publish "$2" "$3" "$4"
                        """,
                "round9cc-paused-atomic-publisher-test",
                library,
                root.toString(),
                finalFile.toString(),
                value,
                ready.toString(),
                release.toString());
        processBuilder.redirectErrorStream(true);
        return processBuilder.start();
    }

    private CommandResult awaitPublisher(Process process) {
        try {
            assertThat(process.waitFor(6, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (InterruptedException | IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertSuccessfulPublisher(Process process) {
        assertSuccessfulPublisher(awaitPublisher(process));
    }

    private static void assertSuccessfulPublisher(CommandResult result) {
        assertThat(result.exitCode()).as("publisher output: %s", result.output()).isZero();
        assertThat(result.output()).isEmpty();
    }

    private Path awaitPublishedTemporary(Path ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(ready)) {
                String rawPath = Files.readString(ready, StandardCharsets.UTF_8).trim();
                if (!rawPath.isEmpty()) {
                    return Path.of(rawPath);
                }
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("atomic publisher did not reach its test-only publication seam");
    }

    private void assertEventuallyObservedProbe(
            Path observation, String expectedState, CompletableFuture<CommandResult> readiness) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(observation)
                    && expectedState.equals(Files.readString(observation, StandardCharsets.UTF_8).trim())) {
                return;
            }
            Thread.sleep(10);
        }
        CommandResult result = readiness.get(5, TimeUnit.SECONDS);
        throw new IllegalStateException(
                "supervisor never observed " + expectedState + " while the identity pair was incomplete; output: "
                        + result.output());
    }

    private List<Path> runtimePublicationTemporaries() throws IOException {
        try (var paths = Files.list(root.resolve("runtime"))) {
            return paths.filter(path -> path.getFileName().toString().startsWith(".round9cc-runtime.")).toList();
        }
    }

    private static void assertPrivateScalar(Path file, String value) throws IOException {
        assertThat(Files.isRegularFile(file)).isTrue();
        assertThat(Files.isSymbolicLink(file)).isFalse();
        assertThat(Files.getOwner(file)).isEqualTo(Files.getOwner(file.getParent()));
        assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(((Number) Files.getAttribute(file, "unix:nlink")).longValue()).isEqualTo(1L);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(value + "\n");
    }

    private CommandResult awaitReadinessWithProbeSequence(
            String instance, String phase, String role, int timeout, int publishExitOnCall, String... states) throws IOException {
        Path counter = root.resolve("control/probe-sequence-counter");
        Path trace = root.resolve("control/probe-sequence-trace");
        privateFile(counter, "0\n");
        privateFile(trace, "");
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                """
                        source "$1"
                        round9cc_probe_instance_state() {
                          local calls index state
                          calls="$(<"${ROUND9CC_TEST_PROBE_COUNTER}")"
                          [[ "${calls}" =~ ^[0-9]+$ ]] || return 1
                          calls=$((calls + 1))
                          printf '%s\\n' "${calls}" > "${ROUND9CC_TEST_PROBE_COUNTER}"
                          local IFS=','
                          local -a scripted_states=()
                          read -r -a scripted_states <<<"${ROUND9CC_TEST_PROBE_STATES}"
                          index=$((calls - 1))
                          if (( index >= ${#scripted_states[@]} )); then
                            index=$((${#scripted_states[@]} - 1))
                          fi
                          state="${scripted_states[${index}]}"
                          if [[ "${calls}" == "${ROUND9CC_TEST_PUBLISH_EXIT_CALL}" ]]; then
                            printf '0\\n' > "${ROUND9CC_TEST_EXIT_FILE}"
                          fi
                          printf '%s\\n' "${state}" >> "${ROUND9CC_TEST_PROBE_TRACE}"
                          printf '%s\\n' "${state}"
                        }
                        round9cc_wait_for_phase_readiness "$2" "$3" "$4" "$5" "$6" "$7"
                        """,
                "round9cc-publication-race-test",
                library,
                root.toString(),
                instance,
                SCENARIO,
                phase,
                role,
                Integer.toString(timeout));
        processBuilder.environment().put("ROUND9CC_TEST_PROBE_COUNTER", counter.toString());
        processBuilder.environment().put("ROUND9CC_TEST_PROBE_STATES", String.join(",", states));
        processBuilder.environment().put("ROUND9CC_TEST_PROBE_TRACE", trace.toString());
        processBuilder.environment().put("ROUND9CC_TEST_PUBLISH_EXIT_CALL", Integer.toString(publishExitOnCall));
        processBuilder.environment().put("ROUND9CC_TEST_EXIT_FILE", root.resolve("runtime/" + instance + ".exit").toString());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(timeout + 5L, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int probeSequenceCalls() throws IOException {
        return Integer.parseInt(Files.readString(root.resolve("control/probe-sequence-counter"), StandardCharsets.UTF_8).trim());
    }

    private void assertOneShotProbeTrace() throws IOException {
        Path trace = root.resolve("control/probe-sequence-trace");
        String contents = Files.readString(trace, StandardCharsets.UTF_8);
        List<String> states = Files.readAllLines(trace, StandardCharsets.UTF_8);

        assertThat(states).as("probe trace: %s", contents)
                .hasSize(5)
                .doesNotContain("")
                .containsExactly(
                        "EVIDENCE_PENDING",
                        "ALIVE",
                        "OWNERSHIP_REJECTED",
                        "STOPPED",
                        "STOPPED");
        assertThat(contents).as("probe trace: %s", contents).isEqualTo("""
                EVIDENCE_PENDING
                ALIVE
                OWNERSHIP_REJECTED
                STOPPED
                STOPPED
                """);
    }

    private CommandResult probeInstanceState(String instance) {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-u",
                "-c",
                "source \"$1\"; round9cc_probe_instance_state \"$2\" \"$3\"",
                "round9cc-probe-test",
                library,
                root.toString(),
                instance);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CommandResult rawProcessSnapshot(long pid) {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-u",
                "-c",
                "source \"$1\"; round9cc_read_process_snapshot \"$2\"",
                "round9cc-raw-snapshot-test",
                library,
                Long.toString(pid));
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CommandResult probeInstanceStateAfterFirstSnapshotExit(String instance, Path exitTrigger) {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-u",
                "-c",
                """
                        source "$1"
                        round9cc_probe_after_first_snapshot() {
                          : > "${ROUND9CC_TEST_EXIT_TRIGGER}"
                          local deadline=$((SECONDS + 2)) state
                          while (( SECONDS < deadline )); do
                            if [[ ! -d "/proc/${1}" ]]; then
                              return 0
                            fi
                            state="$(awk '{print $3}' "/proc/${1}/stat" 2>/dev/null || true)"
                            case "${state}" in
                              Z|X|x) return 0 ;;
                            esac
                            sleep 0.01
                          done
                          return 1
                        }
                        round9cc_probe_instance_state "$2" "$3"
                        """,
                "round9cc-probe-exit-race-test",
                library,
                root.toString(),
                instance);
        processBuilder.environment().put("ROUND9CC_TEST_EXIT_TRIGGER", exitTrigger.toString());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CommandResult probeInstanceStateWithChangedFinalSnapshot(
            String instance, String firstStart, String finalStart, Path counter) {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-u",
                "-c",
                """
                        source "$1"
                        round9cc_read_process_snapshot() {
                          local snapshot_calls=0
                          if [[ -e "${ROUND9CC_TEST_SNAPSHOT_COUNTER}" ]]; then
                            snapshot_calls="$(<"${ROUND9CC_TEST_SNAPSHOT_COUNTER}")"
                          fi
                          [[ "${snapshot_calls}" =~ ^[0-9]+$ ]] || return 1
                          snapshot_calls=$((snapshot_calls + 1))
                          printf '%s\n' "${snapshot_calls}" > "${ROUND9CC_TEST_SNAPSHOT_COUNTER}"
                          if (( snapshot_calls == 1 )); then
                            printf 'LIVE|S|%s\\n' "${ROUND9CC_TEST_FIRST_START}"
                          else
                            printf 'LIVE|S|%s\\n' "${ROUND9CC_TEST_FINAL_START}"
                          fi
                        }
                        round9cc_probe_instance_state "$2" "$3"
                        """,
                "round9cc-final-snapshot-reuse-test",
                library,
                root.toString(),
                instance);
        processBuilder.environment().put("ROUND9CC_TEST_FIRST_START", firstStart);
        processBuilder.environment().put("ROUND9CC_TEST_FINAL_START", finalStart);
        processBuilder.environment().put("ROUND9CC_TEST_SNAPSHOT_COUNTER", counter.toString());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CommandResult stoppedKernelStateClassifier() {
        String library = Path.of("src/test/scripts/round9cc/round9cc-lib.sh").toAbsolutePath().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-u",
                "-c",
                """
                        source "$1"
                        for state in Z X x; do
                          round9cc_process_state_is_stopped "${state}" || exit 1
                        done
                        if round9cc_process_state_is_stopped S; then
                          exit 1
                        fi
                        printf '%s\\n' 'STOPPED_STATE_CLASSIFIER_OK'
                        """,
                "round9cc-state-classifier-test",
                library);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            return new CommandResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String processStart(long pid) throws IOException {
        String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"), StandardCharsets.UTF_8);
        String afterCommand = stat.substring(stat.lastIndexOf(')') + 2);
        return afterCommand.split("\\s+")[19];
    }

    private static void privateFile(Path file, String value) throws IOException {
        Files.writeString(file, value, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void assertSafeFailure(CommandResult result, String code) {
        assertThat(result.exitCode()).as("supervisor output: %s", result.output()).isNotZero();
        assertThat(result.output()).as("supervisor output: %s", result.output())
                .isEqualTo("ROUND9CC_ERROR:" + code);
    }

    private static void assertProbeState(CommandResult result, String state) {
        assertThat(result.exitCode()).as("probe output: %s", result.output()).isZero();
        assertThat(result.output()).as("probe output: %s", result.output()).isEqualTo(state);
    }

    private record SignalMonitorLaunch(
            Process supervisor, Path childPid, Path childStart, Path monitorPid, Path exit) {
    }

    private record SyntheticProcessIdentity(long pid, String start) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
