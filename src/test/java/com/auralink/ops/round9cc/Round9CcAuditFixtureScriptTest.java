package com.auralink.ops.round9cc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class Round9CcAuditFixtureScriptTest {

    private Path root;
    private Process ownedProcess;

    @AfterEach
    void tearDown() throws Exception {
        if (ownedProcess != null && ownedProcess.isAlive()) {
            ownedProcess.destroy();
            ownedProcess.waitFor(5, TimeUnit.SECONDS);
            if (ownedProcess.isAlive()) {
                ownedProcess.destroyForcibly();
                ownedProcess.waitFor(5, TimeUnit.SECONDS);
            }
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
    void auditAcceptsHistoricalNormalExitAndReachesSemanticAssertions() throws Exception {
        root = fixture();
        Process completed = new ProcessBuilder("bash", "-c", "sleep 1").start();
        String start = processStart(completed.pid());
        assertThat(completed.waitFor()).isZero();
        writeRuntime(root, "workerA", completed.pid(), start);

        CommandResult result = audit(root);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).containsExactly("ROUND9CC_FIXTURE_AUDIT_OK scenario=NORMAL_COMPLETION");
    }

    @Test
    void auditConsumesTheRealTermBeforeClaimManifestWithCanonicalRecoveryCallEvidence() throws Exception {
        root = batch1Fixture(false);

        assertThat(root).isNotNull();
        assertThat(root.resolve("runtime/seedA.phase")).isRegularFile();
        assertThat(root.resolve("runtime/recoveryA.recovery")).isRegularFile();
        CommandResult result = audit(root);

        assertThat(result.exitCode()).as("audit output: %s", result.output()).isZero();
        assertThat(result.output()).as("audit output: %s", result.output())
                .containsExactly("ROUND9CC_FIXTURE_AUDIT_OK scenario=TERM_BEFORE_CLAIM");
    }

    @Test
    void auditRejectsAMutatedTermBeforeClaimNoneRecoveryCallVocabulary() throws Exception {
        root = batch1Fixture(true);

        assertThat(root).isNotNull();
        assertThat(root.resolve("runtime/recoveryA.recovery")).isRegularFile();
        CommandResult result = audit(root);

        assertThat(result.exitCode()).as("audit output: %s", result.output()).isEqualTo(2);
        assertThat(result.output()).as("audit output: %s", result.output())
                .containsExactly("ROUND9CC_ERROR:SCENARIO_MANIFEST_MISMATCH");
    }

    @Test
    void auditRejectsAnExactLiveHarnessInstanceBeforeDatabaseInspection() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedProcess(root);
        writeRuntime(root, "workerA", ownedProcess.pid(), processStart(ownedProcess.pid()));

        CommandResult result = audit(root);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).containsExactly("ROUND9CC_ERROR:INSTANCE_STILL_RUNNING");
    }

    @Test
    void reusedPidEvidenceDoesNotSignalTheUnrelatedLiveProcess() throws Exception {
        root = fixture();
        ownedProcess = new ProcessBuilder("bash", "-c", "while :; do sleep 1; done").start();
        long staleStart = Long.parseLong(processStart(ownedProcess.pid())) + 1;
        writeRuntime(root, "workerA", ownedProcess.pid(), Long.toString(staleStart));

        CommandResult result = audit(root);

        assertThat(result.exitCode()).as("audit output: %s", result.output()).isEqualTo(2);
        assertThat(result.output()).as("audit output: %s", result.output())
                .containsExactly("ROUND9CC_ERROR:PID_REUSE_REJECTED");
        assertThat(ownedProcess.isAlive()).as("audit output: %s", result.output()).isTrue();
    }

    @Test
    void malformedRuntimeEvidenceFailsWithOneSafeError() throws Exception {
        root = fixture();
        writeRuntime(root, "workerA", "not-a-pid", "not-a-start-time");

        CommandResult result = audit(root);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).containsExactly("ROUND9CC_ERROR:INSTANCE_RUNTIME_EVIDENCE_INVALID");
    }

    @Test
    void auditRejectsIncompleteIdentityPublicationBeforeAnySemanticAuditSuccess() throws Exception {
        root = fixture();
        privateFile(root.resolve("runtime/workerA.pid"), "12345\n");

        CommandResult result = audit(root);

        assertThat(result.exitCode()).as("audit output: %s", result.output()).isEqualTo(2);
        assertThat(result.output()).as("audit output: %s", result.output())
                .containsExactly("ROUND9CC_ERROR:INSTANCE_RUNTIME_EVIDENCE_INVALID");
    }

    @Test
    void matchingStartWithoutHarnessFixtureOwnershipFailsClosed() throws Exception {
        root = fixture();
        ownedProcess = new ProcessBuilder("bash", "-c", "while :; do sleep 1; done").start();
        writeRuntime(root, "workerA", ownedProcess.pid(), processStart(ownedProcess.pid()));

        CommandResult result = audit(root);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).containsExactly("ROUND9CC_ERROR:PID_OWNERSHIP_REJECTED");
    }

    @Test
    void releaseRejectsStoppedReusedAndOwnershipRejectedInstancesWithoutWritingAMarker() throws Exception {
        root = fixture();
        Process completed = new ProcessBuilder("bash", "-c", "sleep 1").start();
        String start = processStart(completed.pid());
        assertThat(completed.waitFor()).isZero();
        writeRuntime(root, "workerA", completed.pid(), start);
        Path release = prepareReached(root, "workerA", "BEFORE_MOCK_ENTRY");

        CommandResult stopped = release(root, "workerA", "BEFORE_MOCK_ENTRY");

        assertThat(stopped.exitCode()).isEqualTo(2);
        assertThat(stopped.output()).containsExactly("ROUND9CC_ERROR:INSTANCE_NOT_RUNNING");
        assertThat(Files.exists(release)).isFalse();

        Files.delete(root.resolve("runtime/workerA.pid"));
        Files.delete(root.resolve("runtime/workerA.start"));
        ownedProcess = new ProcessBuilder("bash", "-c", "while :; do sleep 1; done").start();
        writeRuntime(root, "workerA", ownedProcess.pid(), Long.toString(Long.parseLong(processStart(ownedProcess.pid())) + 1));

        CommandResult reused = release(root, "workerA", "BEFORE_MOCK_ENTRY");

        assertThat(reused.exitCode()).isEqualTo(2);
        assertThat(reused.output()).containsExactly("ROUND9CC_ERROR:PID_REUSE_REJECTED");
        assertThat(Files.exists(release)).isFalse();

        Files.delete(root.resolve("runtime/workerA.pid"));
        Files.delete(root.resolve("runtime/workerA.start"));
        writeRuntime(root, "workerA", ownedProcess.pid(), processStart(ownedProcess.pid()));

        CommandResult ownershipRejected = release(root, "workerA", "BEFORE_MOCK_ENTRY");

        assertThat(ownershipRejected.exitCode()).isEqualTo(2);
        assertThat(ownershipRejected.output()).containsExactly("ROUND9CC_ERROR:PID_OWNERSHIP_REJECTED");
        assertThat(Files.exists(release)).isFalse();
    }

    @Test
    void releaseWritesMarkerOnlyForALiveExactFixtureHarness() throws Exception {
        root = fixture();
        ownedProcess = harnessShapedProcess(root);
        writeRuntime(root, "workerA", ownedProcess.pid(), processStart(ownedProcess.pid()));
        Path release = prepareReached(root, "workerA", "BEFORE_MOCK_ENTRY");

        CommandResult result = release(root, "workerA", "BEFORE_MOCK_ENTRY");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).containsExactly("ROUND9CC_FAILPOINT_RELEASED failpoint=BEFORE_MOCK_ENTRY");
        assertThat(Files.readString(release, StandardCharsets.UTF_8)).isEqualTo("RELEASE\n");
    }

    private Path fixture() throws Exception {
        Path fixtureRoot = Files.createTempDirectory(Path.of("/tmp"), "auralink-round9cc.");
        root = fixtureRoot;
        Files.setPosixFilePermissions(fixtureRoot, PosixFilePermissions.fromString("rwx------"));
        for (String directory : List.of("db", "managed", "provider-staging", "env", "control", "counters", "logs", "runtime", "manifest")) {
            Path path = fixtureRoot.resolve(directory);
            Files.createDirectory(path);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
        privateFile(fixtureRoot.resolve(".round9cc-fixture"), "ROUND9CC_FIXTURE\n");
        Files.createFile(fixtureRoot.resolve("db/fixture.db"));
        privateFile(fixtureRoot.resolve("manifest/scenario.properties"), """
                scenario=NORMAL_COMPLETION
                expectedCreationStatus=SUCCEEDED
                expectedMockEntry=0
                expectedMockReturn=0
                expectedMockClose=0
                """);
        privateFile(fixtureRoot.resolve("manifest/expected-counts.properties"), """
                generation_logs=0
                paintings=0
                catalog_import_runs=0
                """);
        privateFile(fixtureRoot.resolve("counters/workerA.journal"), "");
        privateFile(fixtureRoot.resolve("runtime/workerA.boundary"), """
                NO_BACKEND_ENV
                MOCK_ONLY_NO_REAL_PROVIDER
                """);
        return fixtureRoot;
    }

    private Path batch1Fixture(boolean mutateRecoveryCallsToNone) throws Exception {
        Path fixtureRoot = fixture();
        Path manifest = fixtureRoot.resolve("manifest/scenario.properties");
        Files.delete(manifest);
        Round9CcFixture scenarioFixture = Round9CcFixture.validate(fixtureRoot);
        Round9CcFixtureManifest.write(scenarioFixture, Round9CcScenario.TERM_BEFORE_CLAIM);

        String manifestContents = Files.readString(manifest, StandardCharsets.UTF_8);
        assertThat(manifestContents).contains("recoveryProviderCalls=ZERO");
        if (mutateRecoveryCallsToNone) {
            privateFile(manifest, manifestContents.replace("recoveryProviderCalls=ZERO", "recoveryProviderCalls=NONE"));
        }

        for (String instance : List.of("seedA", "workerA", "recoveryA")) {
            Path control = fixtureRoot.resolve("control").resolve(instance);
            Files.createDirectory(control);
            Files.setPosixFilePermissions(control, PosixFilePermissions.fromString("rwx------"));
        }
        privateFile(fixtureRoot.resolve("control/workerA/STARTUP_RECOVERY_GATE_CLOSED.reached"),
                "STARTUP_RECOVERY_GATE_CLOSED\n");
        writeBatch1Runtime(fixtureRoot, "seedA", "SEED", "SEEDER", "0", "41001");
        writeBatch1Runtime(fixtureRoot, "workerA", "INITIAL", "DISPATCHER_WORKER", "143", "41002");
        writeBatch1Runtime(fixtureRoot, "recoveryA", "RECOVERY", "RECOVERY", "0", "41003");
        privateFile(fixtureRoot.resolve("runtime/seedA.seed"), """
                SCENARIO=TERM_BEFORE_CLAIM
                ROLE=SEEDER
                CREATIONS=1
                EXECUTION_ATTEMPTS=1
                MOCK_PROVIDER_CALLS=0
                """);
        privateFile(fixtureRoot.resolve("runtime/recoveryA.recovery"), """
                SCENARIO=TERM_BEFORE_CLAIM
                ROLE=RECOVERY
                RECOVERY_GATE_OPEN
                RECOVERY_PROVIDER_CALLS=%s
                ORDINARY_DISPATCH_RESUMES=true
                """.formatted(manifestValue(manifest, "recoveryProviderCalls")));
        return fixtureRoot;
    }

    private static void writeBatch1Runtime(Path fixtureRoot, String instance, String phase, String role, String exit, String port)
            throws Exception {
        writeRuntimeValue(fixtureRoot, instance, "phase", phase + "\n");
        writeRuntimeValue(fixtureRoot, instance, "role", role + "\n");
        writeRuntimeValue(fixtureRoot, instance, "exit", exit + "\n");
        writeRuntimeValue(fixtureRoot, instance, "port", port + "\n");
        writeRuntimeValue(fixtureRoot, instance, "boundary", "NO_BACKEND_ENV\nMOCK_ONLY_NO_REAL_PROVIDER\n");
    }

    private static void writeRuntimeValue(Path fixtureRoot, String instance, String suffix, String value) throws IOException {
        privateFile(fixtureRoot.resolve("runtime/" + instance + "." + suffix), value);
    }

    private static String manifestValue(Path manifest, String key) throws IOException {
        return Files.readAllLines(manifest, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1))
                .findFirst()
                .orElseThrow();
    }

    private static void writeRuntime(Path fixtureRoot, String instance, long pid, String start) throws Exception {
        writeRuntime(fixtureRoot, instance, Long.toString(pid), start);
    }

    private static void writeRuntime(Path fixtureRoot, String instance, String pid, String start) throws Exception {
        privateFile(fixtureRoot.resolve("runtime/" + instance + ".pid"), pid + "\n");
        privateFile(fixtureRoot.resolve("runtime/" + instance + ".start"), start + "\n");
    }

    private static Process harnessShapedProcess(Path fixture) throws IOException {
        return new ProcessBuilder(
                "bash",
                "-c",
                "while :; do sleep 1; done",
                "-Dloader.main=com.auralink.ops.round9cc.Round9CcPackagedFailureHarness",
                "--fixture-root=" + fixture)
                .start();
    }

    private static CommandResult audit(Path fixtureRoot) throws Exception {
        Path fakeBin = Files.createDirectory(fixtureRoot.resolve("fake-bin"));
        Files.setPosixFilePermissions(fakeBin, PosixFilePermissions.fromString("rwx------"));
        Path sqlite = fakeBin.resolve("sqlite3");
        privateFile(sqlite, """
                #!/usr/bin/env bash
                set -euo pipefail
                query="${!#}"
                case "${query}" in
                  'PRAGMA integrity_check;') printf 'ok\\n' ;;
                  'PRAGMA foreign_key_check;') ;;
                  'SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank;') printf '1\\n2\\n3\\n4\\n' ;;
                  'SELECT COUNT(*) FROM creations;') printf '1\\n' ;;
                  'SELECT status FROM creations;') printf 'QUEUED\\n' ;;
                  "SELECT COUNT(*) FROM creations WHERE status <> 'SUCCEEDED';"|"SELECT COUNT(*) FROM creations WHERE status <> 'QUEUED';") printf '0\\n' ;;
                  'SELECT COUNT(*) FROM creation_steps;'|'SELECT COUNT(*) FROM creation_execution_attempts;'|'SELECT COUNT(*) FROM creation_execution_attempts WHERE finished_at IS NULL;') printf '1\\n' ;;
                  'SELECT status FROM creation_steps;') printf 'PENDING\\n' ;;
                  'SELECT provider_dispatch_state FROM creation_steps;') printf 'NOT_SENT\\n' ;;
                  "SELECT COUNT(*) FROM creation_steps WHERE status <> 'PENDING';"|"SELECT COUNT(*) FROM creation_steps WHERE provider_dispatch_state <> 'NOT_SENT';"|"SELECT COUNT(*) FROM creations WHERE claim_token IS NULL AND lease_expires_at IS NULL;"|"SELECT COUNT(*) FROM creations WHERE error_code IS NULL AND error_message IS NULL;"|"SELECT COUNT(*) FROM creation_steps WHERE error_code IS NULL AND error_message IS NULL;") printf '1\\n' ;;
                  'SELECT COUNT(*) FROM generation_logs;'|'SELECT COUNT(*) FROM paintings;'|'SELECT COUNT(*) FROM catalog_import_runs;') printf '0\\n' ;;
                  *) ;;
                esac
                """);
        sqlite.toFile().setExecutable(true, true);
        Path ss = fakeBin.resolve("ss");
        privateFile(ss, "#!/usr/bin/env bash\nset -euo pipefail\n");
        ss.toFile().setExecutable(true, true);
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                Path.of("src/test/scripts/round9cc/round9cc-audit-fixture.sh").toAbsolutePath().toString(),
                fixtureRoot.toString());
        processBuilder.environment().put("PATH", fakeBin + ":" + processBuilder.environment().get("PATH"));
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        return new CommandResult(process.exitValue(),
                List.of(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()));
    }

    private static Path prepareReached(Path fixtureRoot, String instance, String failpoint) throws Exception {
        Path instanceControl = fixtureRoot.resolve("control").resolve(instance);
        Files.createDirectory(instanceControl);
        Files.setPosixFilePermissions(instanceControl, PosixFilePermissions.fromString("rwx------"));
        privateFile(instanceControl.resolve(failpoint + ".reached"), failpoint + "\n");
        return instanceControl.resolve(failpoint + ".release");
    }

    private static CommandResult release(Path fixtureRoot, String instance, String failpoint) throws Exception {
        return runScript("round9cc-release-failpoint.sh", fixtureRoot.toString(), instance, failpoint);
    }

    private static CommandResult runScript(String script, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 2];
        command[0] = "bash";
        command[1] = Path.of("src/test/scripts/round9cc").resolve(script).toAbsolutePath().toString();
        System.arraycopy(arguments, 0, command, 2, arguments.length);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        return new CommandResult(process.exitValue(),
                List.of(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()));
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

    private record CommandResult(int exitCode, List<String> output) {
    }
}
