package com.auralink.ops.round51;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Round51ActivationScriptContractTest {

    private static final Path BACKEND_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path SCRIPT = BACKEND_ROOT.resolve("scripts/activate-round5-catalog.sh");
    private static final Path RECOVERY_SCRIPT = BACKEND_ROOT.resolve(
            "scripts/recover-round5-catalog-activation.sh");
    private static final Path HELPER = BACKEND_ROOT.resolve("scripts/round51_state.py");
    private static final List<String> FOUNDATION_TABLES = List.of(
            "media_assets",
            "paintings",
            "catalog_import_runs",
            "painting_guides",
            "painting_favorites",
            "user_workflows",
            "creations",
            "creation_steps",
            "creation_favorites");
    private static final List<String> CATALOG_HEADERS = List.of(
            "序号", "图像存储名称", "画作名称", "作者姓名", "作者出生年份", "作者出生地", "作者流派",
            "创作年代", "创作朝代", "实际尺寸", "收藏机构", "分类", "题材", "画作流派", "风格", "色彩",
            "构图", "意境", "笔法", "墨法", "绘画材料", "颜料", "印章", "文化符号", "文本生成",
            "音乐情境生成", "收集平台");

    @TempDir
    Path temporaryDirectory;

    private Path testPython3;

    @BeforeEach
    void resolveTestPython3() {
        testPython3 = Round51TestPython.resolve();
    }

    @Test
    void acceptsExplicitExecutablePythonOutsideUsrBin() throws Exception {
        Path outsideUsrBin = temporaryDirectory.resolve("portable-python3").toAbsolutePath();
        Files.createSymbolicLink(outsideUsrBin, testPython3);

        Path resolved = Round51TestPython.resolve(
                outsideUsrBin.toString(), null, System.getenv("PATH"));

        assertEquals(outsideUsrBin, resolved);
        assertTrue(Files.isExecutable(resolved));
        assertFalse(resolved.startsWith("/usr/bin"));
    }

    @Test
    void productionScriptRefusesTheSshfsDevelopmentRoot() throws Exception {
        ScriptFixture fixture = createScriptFixture("sshfs-development-root");
        Path fakeBin = fixture.root().resolve(".round51-test-bin");
        Path findmntInvocation = temporaryDirectory.resolve("sshfs-findmnt-invocation");
        Path fakeFindmnt = createFakeSshfsFindmnt(fakeBin, fixture.root(), findmntInvocation);

        ProcessResult result = fixture.run("--dry-run");

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains(
                "server-local project root is on a FUSE/SSHFS filesystem"));
        assertFalse(result.output().contains("SERVER_LOCAL_ROOT_VERIFIED"));
        assertEquals(fakeBin, fakeFindmnt.getParent());

        List<String> invocation = Files.readAllLines(findmntInvocation, StandardCharsets.UTF_8);
        assertTrue(invocation.get(0).startsWith("PATH=" + fakeBin + ":"));
        assertEquals(List.of("-n", "-o", "FSTYPE", "-T", fixture.root().toString()),
                invocation.subList(1, invocation.size()));
    }

    @Test
    void optionalSmokeBackendIsBoundToLoopbackOnly() throws Exception {
        String script = Files.readString(SCRIPT);

        assertTrue(script.contains("--server.address=127.0.0.1"));
        assertFalse(script.contains("--server.address=0.0.0.0"));
        assertTrue(script.contains("verify_filesystem_space \"$backup_probe\" \"$backup_minimum\" \"BACKUP\""));
        assertTrue(script.contains(
                "verify_filesystem_space \"$(dirname -- \"$LIVE_DATABASE\")\" \"$live_minimum\" \"LIVE_DATABASE\""));
        assertTrue(script.contains("start_service_exclusion_monitor"));
        assertTrue(script.contains("hold-startup-gate"));
        assertTrue(script.contains("BACKEND_STARTUP_KERNEL_GATE_ACQUIRED"));
        assertTrue(script.contains("BACKEND_STARTUP_KERNEL_GATE_RETAINED_FOR_OPERATOR_RECOVERY"));
        assertTrue(script.contains("--orphan-fence"));
        assertTrue(script.contains("set_service_allowance \"$ACTIVATION_CHILD\" 0"));
        assertTrue(script.contains("set_service_allowance \"$SMOKE_CHILD\" 1"));
        assertTrue(script.contains(
                "readonly JAVA_MAIN=\"com.auralink.ops.round51.Round51ActivationCommand\""));
        assertTrue(script.contains("java -Dloader.main=\"$JAVA_MAIN\""));
        assertTrue(script.contains("org.springframework.boot.loader.launch.PropertiesLauncher"));
        assertTrue(script.contains("if wait \"$ACTIVATION_CHILD\"; then"));
        assertTrue(script.contains("ROUND51_ACTIVATION_ERROR_CLASS=$activation_error_class"));
        assertTrue(script.contains("ROUND51_ACTIVATION_ERROR_SUMMARY=$activation_error_summary"));
        assertTrue(script.contains("process_identity_matches"));
        assertTrue(script.contains("SERVICE_MONITOR_CHILD_START"));
    }

    @Test
    void recoveryToolIsServerLocalNamedBackupOnlyAndNeverPerformsActivation() throws Exception {
        String script = Files.readString(RECOVERY_SCRIPT);

        assertTrue(script.contains("readonly SERVER_LOCAL_ROOT=\"/root/autodl-tmp/auralink\""));
        assertTrue(script.contains("RESTORE_AURALINK_ROUND51_PRE_ACTIVATION_BACKUP"));
        assertTrue(script.contains("remove-stale-maintenance-marker"));
        assertTrue(script.contains("verify-inherited"));
        assertTrue(script.contains("preserve-failed"));
        assertTrue(script.contains("process_identity_matches"));
        assertTrue(script.contains("SERVICE_MONITOR_CHILD_START"));
        assertFalse(script.contains("Round51ActivationCommand"));

        ProcessResult syntax = run(BACKEND_ROOT, Map.of(), Duration.ofSeconds(15),
                "bash", "-n", RECOVERY_SCRIPT.toString());
        assertEquals(0, syntax.exitCode(), syntax.output());

        ProcessResult wrongRoot = run(
                BACKEND_ROOT,
                Map.of(
                        "AURALINK_ROUND51_RECOVERY_CONFIRM",
                                "RESTORE_AURALINK_ROUND51_PRE_ACTIVATION_BACKUP",
                        "AURALINK_ROUND51_EXPECTED_COMMIT", "0".repeat(40)),
                Duration.ofSeconds(15),
                "bash", RECOVERY_SCRIPT.toString(), "--backup-dir",
                "/root/auralink_activation_backups/not-selected");
        assertNotEquals(0, wrongRoot.exitCode());
        assertTrue(wrongRoot.output().contains("exact server-local project root"));
    }

    @Test
    void staleRecoveryRestoresNamedInheritedBackupAndPreservesInterruptedState() throws Exception {
        ScriptFixture fixture = createScriptFixture("stale-recovery-restore");
        RecoveryBackup backup = prepareRecoveryBackup(fixture, "20260812T120000Z-4101");
        String inheritedDataHash = legacyDataHash(fixture.database());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER)");
            statement.execute("CREATE TABLE media_assets (id INTEGER PRIMARY KEY)");
        }
        String interruptedEnvironment = "SERVER_PORT=45991\nINTERRUPTED_SETTING=must-be-preserved\n";
        Files.writeString(fixture.environment(), interruptedEnvironment);

        ProcessResult result = fixture.runRecovery(backup.directory());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("STALE_MAINTENANCE_RECOVERY_COMPLETED"), result.output());
        assertFalse(Files.exists(backup.marker()));
        assertEquals(inheritedDataHash, legacyDataHash(fixture.database()));
        assertEquals(7, count(fixture.database(), "users"));
        assertEquals(118, count(fixture.database(), "generation_logs"));
        assertFalse(tableExists(fixture.database(), "flyway_schema_history"));
        assertFalse(tableExists(fixture.database(), "media_assets"));
        assertEquals(backup.originalEnvironment(), Files.readString(fixture.environment()));

        Path partialDatabase;
        Path interruptedEnvironmentSnapshot;
        try (var files = Files.list(backup.directory())) {
            partialDatabase = files
                    .filter(path -> path.getFileName().toString()
                            .matches("crash-recovery-partial-.*\\.db"))
                    .findFirst()
                    .orElseThrow();
        }
        try (var files = Files.list(backup.directory())) {
            interruptedEnvironmentSnapshot = files
                    .filter(path -> path.getFileName().toString()
                            .matches("crash-recovery-current-.*\\.env"))
                    .findFirst()
                    .orElseThrow();
        }
        assertTrue(tableExists(partialDatabase, "flyway_schema_history"));
        assertTrue(tableExists(partialDatabase, "media_assets"));
        assertEquals(interruptedEnvironment, Files.readString(interruptedEnvironmentSnapshot));
        assertTrue(Files.isRegularFile(backup.database()));
        assertTrue(Files.isRegularFile(backup.environment()));
    }

    @Test
    void staleRecoveryLeavesVerifiedActivatedDatabaseUntouchedAndOnlyReleasesMarker() throws Exception {
        ScriptFixture fixture = createScriptFixture("stale-recovery-activated");
        RecoveryBackup backup = prepareRecoveryBackup(fixture, "20260812T120100Z-4102");
        createFullyVerifiedActivatedCandidate(fixture);
        assertEquals(0, helper("update-env", "--env-file", fixture.environment().toString()).exitCode());
        String activatedDatabaseHash = sha256(fixture.database());
        String activatedEnvironment = Files.readString(fixture.environment());

        ProcessResult result = fixture.runRecovery(backup.directory());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("ALREADY_ACTIVATED_AND_HEALTHY"), result.output());
        assertFalse(Files.exists(backup.marker()));
        assertEquals(activatedDatabaseHash, sha256(fixture.database()),
                "healthy activated database must not be restored to its inherited backup");
        assertEquals(activatedEnvironment, Files.readString(fixture.environment()));
        try (var files = Files.list(backup.directory())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("crash-recovery-")));
        }
    }

    @Test
    void staleRecoveryRestoresEnvironmentWhenDatabaseWasAlreadyRolledBack() throws Exception {
        ScriptFixture fixture = createScriptFixture("stale-recovery-db-restored-env-pending");
        RecoveryBackup backup = prepareRecoveryBackup(fixture, "20260812T120050Z-4103");
        assertEquals(0, helper("update-env", "--env-file", fixture.environment().toString()).exitCode());
        String inheritedDatabaseHash = legacyDataHash(fixture.database());

        ProcessResult result = fixture.runRecovery(backup.directory());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("STALE_MAINTENANCE_RECOVERY_COMPLETED"), result.output());
        assertEquals(inheritedDatabaseHash, legacyDataHash(fixture.database()));
        assertEquals(backup.originalEnvironment(), Files.readString(fixture.environment()));
        assertFalse(Files.exists(backup.marker()));
    }

    @Test
    void staleRecoveryReestablishesFenceIfMarkerIsRemovedDuringRestore() throws Exception {
        ScriptFixture fixture = createScriptFixture("stale-recovery-marker-loss");
        RecoveryBackup backup = prepareRecoveryBackup(fixture, "20260812T120055Z-4104");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database())) {
            connection.createStatement().execute(
                    "CREATE TABLE flyway_schema_history (installed_rank INTEGER)");
            connection.createStatement().execute("CREATE TABLE media_assets (id INTEGER PRIMARY KEY)");
        }
        String interruptedEnvironment = "SERVER_PORT=45991\nINTERRUPTED=true\n";
        Files.writeString(fixture.environment(), interruptedEnvironment);
        Path fakeBin = createRecoveryMarkerLossTools(backup.marker());

        ProcessResult result = fixture.runRecovery(
                backup.directory(),
                Map.of("PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45));

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(
                "RECOVERY_MAINTENANCE_MARKER_LOST_WITHOUT_RELEASE_INTENT"), result.output());
        assertTrue(result.output().contains(
                "RECOVERY_MAINTENANCE_FENCE_REESTABLISHED"), result.output());
        assertTrue(Files.isRegularFile(backup.marker()),
                "failed recovery must leave ordinary backend startup fenced");
        assertEquals(7, count(fixture.database(), "users"));
        assertEquals(118, count(fixture.database(), "generation_logs"));
        assertFalse(tableExists(fixture.database(), "flyway_schema_history"));
        assertFalse(tableExists(fixture.database(), "media_assets"));
        assertEquals(interruptedEnvironment, Files.readString(fixture.environment()),
                "interrupted environment remains recoverable behind the restored fence");
    }

    @Test
    void markerlessRecoveryAuthenticatesActivationAndRecoveryReleaseEvidenceAcrossTwoCycles()
            throws Exception {
        ScriptFixture fixture = createScriptFixture("markerless-release-recovery");
        RecoveryBackup backup = prepareRecoveryBackup(fixture, "20260812T120057Z-4105");
        String inheritedDatabaseHash = legacyDataHash(fixture.database());
        String inheritedEnvironment = Files.readString(fixture.environment());

        Path activationEvidence = backup.directory()
                .resolve(".round51-released-activation-marker-simulated");
        Files.createLink(activationEvidence, backup.marker());
        Path activationIntent = backup.directory().resolve(".round51-service-release-intent");
        writePrivateFile(activationIntent, "verified-release\n");
        Files.delete(backup.marker());
        Path activationOrphan = fixture.backupRoot()
                .resolve(".round51-activation-startup-gate-orphan-fence-simulated");
        createBoundOrphanFence(fixture, backup, activationOrphan);

        ProcessResult firstRecovery = fixture.runRecovery(backup.directory());

        assertEquals(0, firstRecovery.exitCode(), firstRecovery.output());
        assertTrue(firstRecovery.output().contains("STALE_MAINTENANCE_RECOVERY_COMPLETED"),
                firstRecovery.output());
        assertFalse(Files.exists(backup.marker()));
        assertFalse(Files.exists(activationOrphan));
        assertEquals(inheritedDatabaseHash, legacyDataHash(fixture.database()));
        assertEquals(inheritedEnvironment, Files.readString(fixture.environment()));

        List<Path> releasedAfterFirstRecovery = filesWithPrefix(
                backup.directory(), ".round51-released-");
        assertTrue(releasedAfterFirstRecovery.size() >= 2,
                "activation and recovery releases must both remain as durable evidence");
        assertReleasedMarkersAuthenticate(fixture, backup, releasedAfterFirstRecovery);

        // Model a host reset after that recovery's verified marker unlink but before
        // its startup-gate holder can retire the durable orphan fence.
        Path recoveryOrphan = fixture.backupRoot()
                .resolve(".round51-recovery-startup-gate-orphan-fence-simulated");
        createBoundOrphanFence(fixture, backup, recoveryOrphan);
        ProcessResult secondRecovery = fixture.runRecovery(backup.directory());

        assertEquals(0, secondRecovery.exitCode(), secondRecovery.output());
        assertTrue(secondRecovery.output().contains("STALE_MAINTENANCE_RECOVERY_COMPLETED"),
                secondRecovery.output());
        assertFalse(Files.exists(backup.marker()));
        assertFalse(Files.exists(recoveryOrphan));
        assertTrue(filesWithPrefix(fixture.backupRoot(),
                ".round51-activation-startup-gate-orphan-fence-").isEmpty());
        assertTrue(filesWithPrefix(fixture.backupRoot(),
                ".round51-recovery-startup-gate-orphan-fence-").isEmpty());
        assertEquals(inheritedDatabaseHash, legacyDataHash(fixture.database()));
        assertEquals(inheritedEnvironment, Files.readString(fixture.environment()));

        List<Path> releasedAfterSecondRecovery = filesWithPrefix(
                backup.directory(), ".round51-released-");
        assertTrue(releasedAfterSecondRecovery.size() > releasedAfterFirstRecovery.size());
        assertReleasedMarkersAuthenticate(fixture, backup, releasedAfterSecondRecovery);
        for (Path releasedMarker : releasedAfterSecondRecovery) {
            assertEquals(1, ((Number) Files.getAttribute(releasedMarker, "unix:nlink")).intValue(),
                    "a released marker must be the sole durable link after global unlink");
        }
    }

    @Test
    void markerlessRecoveryRefusesMultipleUnauthenticatedReleasedMarkersWithoutMutation()
            throws Exception {
        ScriptFixture fixture = createScriptFixture("markerless-invalid-evidence");
        RecoveryBackup backup = prepareRecoveryBackup(fixture, "20260812T120058Z-4106");
        String databaseBefore = sha256(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        writePrivateFile(backup.directory().resolve(".round51-service-release-intent"),
                "verified-release\n");
        writePrivateFile(backup.directory().resolve(
                ".round51-released-activation-marker-invalid-a"), "a".repeat(64) + "\n");
        writePrivateFile(backup.directory().resolve(
                ".round51-released-recovery-marker-invalid-b"), "b".repeat(64) + "\n");
        Files.delete(backup.marker());
        Path orphan = fixture.backupRoot()
                .resolve(".round51-activation-startup-gate-orphan-fence-invalid-evidence");
        createBoundOrphanFence(fixture, backup, orphan);

        ProcessResult result = fixture.runRecovery(backup.directory());

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(
                "no released-marker evidence authenticates the selected recovery binding"),
                result.output());
        assertFalse(Files.exists(backup.marker()));
        assertTrue(Files.isRegularFile(orphan),
                "refused recovery must retain the durable startup fence");
        assertEquals(databaseBefore, sha256(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
    }

    @Test
    void recoveryRefusesOrphanFenceBoundToAnotherBackupRunWithoutMutation() throws Exception {
        ScriptFixture fixture = createScriptFixture("cross-backup-orphan-replay");
        RecoveryBackup selected = prepareRecoveryBackup(fixture, "20260812T120059Z-4107");
        Path selectedRelease = selected.directory()
                .resolve(".round51-released-activation-marker-simulated");
        Files.createLink(selectedRelease, selected.marker());
        writePrivateFile(selected.directory().resolve(".round51-service-release-intent"),
                "verified-release\n");
        Files.delete(selected.marker());

        RecoveryBackup unrelated = prepareRecoveryBackup(fixture, "20260812T120100Z-4108");
        Path unrelatedOrphan = fixture.backupRoot()
                .resolve(".round51-activation-startup-gate-orphan-fence-unrelated-run");
        createBoundOrphanFence(fixture, unrelated, unrelatedOrphan);
        Path unrelatedDetachedMarker = unrelated.directory().resolve("detached-unrelated-marker");
        Files.move(unrelated.marker(), unrelatedDetachedMarker);
        String databaseBefore = sha256(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        ProcessResult result = fixture.runRecovery(selected.directory());

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(
                "an unrelated startup orphan fence belongs to a different activation run"),
                result.output());
        assertFalse(Files.exists(selected.marker()),
                "unbound orphan evidence must not authorize recreating the global marker");
        assertTrue(Files.isRegularFile(unrelatedDetachedMarker));
        assertTrue(Files.isRegularFile(unrelatedOrphan),
                "foreign evidence must never be retired by the selected recovery run");
        assertEquals(databaseBefore, sha256(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
    }

    @Test
    void staleRecoveryRefusesAValidButDifferentUnboundBackupDirectory() throws Exception {
        ScriptFixture fixture = createScriptFixture("stale-recovery-wrong-binding");
        RecoveryBackup wrong = prepareRecoveryBackup(fixture, "20260812T115900Z-4099");
        Path detachedWrongMarker = wrong.directory().resolve("detached-maintenance-marker");
        Files.move(wrong.marker(), detachedWrongMarker);
        RecoveryBackup bound = prepareRecoveryBackup(fixture, "20260812T120000Z-4100");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database())) {
            connection.createStatement().execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER)");
        }
        Files.writeString(fixture.environment(), "SERVER_PORT=45991\nINTERRUPTED=true\n");
        String databaseBefore = sha256(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        ProcessResult result = fixture.runRecovery(wrong.directory());

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("not bound to the maintenance marker"), result.output());
        assertEquals(databaseBefore, sha256(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
        assertTrue(Files.isRegularFile(bound.marker()));
        try (var files = Files.list(wrong.directory())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("crash-recovery-partial-")));
        }
    }

    @Test
    void scriptRefusesAnUnreviewedCommitAndDirtyTreeBeforeDatabaseInspection() throws Exception {
        ScriptFixture fixture = createScriptFixture("commit-guard");
        String databaseBefore = sha256(fixture.database());

        ProcessResult wrongCommit = fixture.run(
                Map.of("AURALINK_ROUND51_EXPECTED_COMMIT", "0".repeat(40)),
                Duration.ofSeconds(30),
                "--dry-run");
        assertNotEquals(0, wrongCommit.exitCode());
        assertTrue(wrongCommit.output().contains("current commit differs"));
        assertEquals(databaseBefore, sha256(fixture.database()));

        Files.writeString(fixture.root().resolve("tracked-drift.txt"), "drift\n");
        ProcessResult dirty = fixture.run("--dry-run");
        assertNotEquals(0, dirty.exitCode());
        assertTrue(dirty.output().contains("tracked checkout is not clean"));
        assertEquals(databaseBefore, sha256(fixture.database()));
    }

    @Test
    void dryRunInspectsACompleteFixtureWithoutMutatingDatabaseOrEnvironment() throws Exception {
        ScriptFixture fixture = createScriptFixture("dry-run");
        String databaseBefore = sha256(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        ProcessResult result = fixture.run("--dry-run");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("SERVER_LOCAL_ROOT_VERIFIED"));
        assertTrue(result.output().contains("DATABASE_PREFLIGHT_STATE=INHERITED_READY"));
        assertTrue(result.output().contains("DRY_RUN_ZERO_MUTATION"));
        assertTrue(result.output().contains("DRY_RUN_OK"));
        assertEquals(databaseBefore, sha256(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
        assertEquals("", gitStatus(fixture.root()));
        assertFalse(tableExists(fixture.database(), "flyway_schema_history"));
        assertFalse(Files.exists(fixture.backupRoot()));
    }

    @Test
    void scriptRefusesMissingEnvironmentAndMigrationBeforeAnyMutation() throws Exception {
        ScriptFixture missingEnvironment = createScriptFixture("missing-env");
        String envDatabaseBefore = sha256(missingEnvironment.database());
        Files.delete(missingEnvironment.environment());

        ProcessResult envResult = missingEnvironment.run("--dry-run");

        assertNotEquals(0, envResult.exitCode());
        assertTrue(envResult.output().contains("backend/.env must be a regular non-symlink file"));
        assertEquals(envDatabaseBefore, sha256(missingEnvironment.database()));

        ScriptFixture missingMigration = createScriptFixture("missing-migration");
        String migrationDatabaseBefore = sha256(missingMigration.database());
        Files.delete(missingMigration.root().resolve(
                "backend/src/main/resources/db/migration/V2__create_auralink_2_0_foundation.sql"));

        ProcessResult migrationResult = missingMigration.run("--dry-run");

        assertNotEquals(0, migrationResult.exitCode());
        assertTrue(migrationResult.output().contains("V2 migration must be a regular non-symlink file"));
        assertEquals(migrationDatabaseBefore, sha256(missingMigration.database()));
    }

    @Test
    void scriptRefusesAnOccupiedConfiguredBackendPort() throws Exception {
        ScriptFixture fixture = createScriptFixture("occupied-port");
        String databaseBefore = sha256(fixture.database());

        ProcessResult result = fixture.run(
                Map.of("ROUND51_TEST_SS_OUTPUT",
                        "LISTEN 0 50 127.0.0.1:45991 0.0.0.0:* users:((java,pid=123,fd=4))"),
                Duration.ofSeconds(30),
                "--dry-run");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("BACKEND_PORT_OCCUPIED"));
        assertTrue(result.output().contains("BACKEND_SERVICE_MUST_BE_STOPPED"));
        assertEquals(databaseBefore, sha256(fixture.database()));
    }

    @Test
    void scriptFailsClosedWhenListenerInspectionFails() throws Exception {
        ScriptFixture fixture = createScriptFixture("listener-inspection-failure");
        String databaseBefore = sha256(fixture.database());

        ProcessResult result = fixture.run(
                Map.of("ROUND51_TEST_SS_FAIL", "1"),
                Duration.ofSeconds(30),
                "--dry-run");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("BACKEND_SERVICE_STATE_UNKNOWN"));
        assertTrue(result.output().contains("BACKEND_SERVICE_MUST_BE_STOPPED"));
        assertEquals(databaseBefore, sha256(fixture.database()));
        assertFalse(Files.exists(fixture.backupRoot()));
    }

    @Test
    void continuousMonitorFencesAnUnexpectedServiceAttemptDuringActivation() throws Exception {
        ScriptFixture fixture = createScriptFixture("mid-activation-service-attempt");
        Path fakeBin = createFakeActivationTools("mid-activation-service-attempt");
        Path javaStarted = temporaryDirectory.resolve("activation-java-started.flag");
        Path marker = fixture.backupRoot().resolve(".round51-maintenance");
        String databaseBefore = sha256(fixture.database());

        ProcessResult result = fixture.run(
                Map.of(
                        "AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG",
                        "ROUND51_FAKE_FAILURE_STAGE", "after-baseline",
                        "ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER", marker.toString(),
                        "ROUND51_TEST_JAVA_HOLD_FILE", javaStarted.toString(),
                        "ROUND51_TEST_SS_TRIGGER_FILE", javaStarted.toString(),
                        "ROUND51_TEST_SS_TRIGGER_OUTPUT",
                                "LISTEN 0 50 127.0.0.1:45991 0.0.0.0:* "
                                        + "users:((java,pid=987654,fd=4))",
                        "PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45),
                "--activate");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains(
                "SERVICE_EXCLUSION_MONITOR_DETECTED_UNAUTHORIZED_DATABASE_USER"), result.output());
        assertTrue(result.output().contains("ROLLBACK_BLOCKED_SERVICE_RUNNING"), result.output());
        assertTrue(Files.isRegularFile(marker), "startup fence must remain after blocked rollback");
        assertEquals(databaseBefore, sha256(fixture.database()));
    }

    @Test
    void externallyRemovedFenceAfterMutationIsRecreatedBeforeVerifiedRollback() throws Exception {
        ScriptFixture fixture = createScriptFixture("marker-loss-after-mutation");
        Path fakeBin = createFakeActivationTools("marker-loss-after-mutation");
        Path marker = fixture.backupRoot().resolve(".round51-maintenance");
        String legacyDataBefore = legacyDataHash(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        ProcessResult result = fixture.run(
                Map.of(
                        "AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG",
                        "ROUND51_FAKE_FAILURE_STAGE", "after-v2",
                        "ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER", marker.toString(),
                        "ROUND51_TEST_REMOVE_MAINTENANCE_MARKER", marker.toString(),
                        "PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45),
                "--activate");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(
                "SERVICE_EXCLUSION_MONITOR_MARKER_LOST_WITHOUT_RELEASE_INTENT"), result.output());
        assertTrue(result.output().contains(
                "ROLLBACK_MAINTENANCE_FENCE_REESTABLISHED"), result.output());
        assertTrue(result.output().contains("ROLLBACK_COMPLETED"), result.output());
        assertEquals(legacyDataBefore, legacyDataHash(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
        assertFalse(tableExists(fixture.database(), "flyway_schema_history"));
        assertFalse(tableExists(fixture.database(), "media_assets"));
        assertFalse(Files.exists(marker));
    }

    @Test
    void fenceRemovedDuringRollbackRestoreIsRecreatedAndRollbackCompletes() throws Exception {
        ScriptFixture fixture = createScriptFixture("marker-loss-during-rollback");
        Path fakeBin = createFakeActivationTools("marker-loss-during-rollback");
        Path marker = fixture.backupRoot().resolve(".round51-maintenance");
        String legacyDataBefore = legacyDataHash(fixture.database());

        ProcessResult result = fixture.run(
                Map.of(
                        "AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG",
                        "ROUND51_FAKE_FAILURE_STAGE", "after-v2",
                        "ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER", marker.toString(),
                        "ROUND51_TEST_REMOVE_MARKER_DURING_RESTORE", marker.toString(),
                        "PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45),
                "--activate");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(
                "SERVICE_EXCLUSION_MONITOR_MARKER_LOST_WITHOUT_RELEASE_INTENT"), result.output());
        assertTrue(result.output().contains(
                "SERVICE_EXCLUSION_MONITOR_FENCE_REESTABLISHED"), result.output());
        assertTrue(result.output().contains("ROLLBACK_COMPLETED"), result.output());
        assertEquals(legacyDataBefore, legacyDataHash(fixture.database()));
        assertFalse(tableExists(fixture.database(), "flyway_schema_history"));
        assertFalse(Files.exists(marker));
    }

    @Test
    void postMarkerPreMonitorFailureReleasesNoMutationLease() throws Exception {
        ScriptFixture fixture = createScriptFixture("post-marker-pre-monitor-failure");
        Path fakeBin = createFakeActivationTools("post-marker-pre-monitor-failure");
        Path marker = fixture.backupRoot().resolve(".round51-maintenance");
        String databaseBefore = sha256(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        ProcessResult result = fixture.run(
                Map.of(
                        "AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG",
                        "ROUND51_FAKE_FAILURE_STAGE", "after-baseline",
                        "ROUND51_TEST_SS_TRIGGER_FILE", marker.toString(),
                        "ROUND51_TEST_SS_TRIGGER_OUTPUT",
                                "LISTEN 0 50 127.0.0.1:45991 0.0.0.0:* "
                                        + "users:((java,pid=987654,fd=4))",
                        "PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45),
                "--activate");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("BACKEND_SERVICE_MUST_BE_STOPPED"), result.output());
        assertTrue(result.output().contains(
                "BACKEND_STARTUP_MAINTENANCE_LEASE_RELEASED"), result.output());
        assertEquals(databaseBefore, sha256(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
        assertFalse(Files.exists(marker));
    }

    @Test
    void nonWebActivationProcessIsNeverAllowedToListenOnTheBackendPort() throws Exception {
        ScriptFixture fixture = createScriptFixture("activation-listener-refused");
        Path fakeBin = createFakeActivationTools("activation-listener-refused");
        Path javaPid = temporaryDirectory.resolve("activation-listener.pid");
        Path javaStarted = temporaryDirectory.resolve("activation-listener.started");

        ProcessResult result = fixture.run(
                Map.of(
                        "AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG",
                        "ROUND51_FAKE_FAILURE_STAGE", "after-baseline",
                        "ROUND51_TEST_JAVA_PID_FILE", javaPid.toString(),
                        "ROUND51_TEST_JAVA_HOLD_FILE", javaStarted.toString(),
                        "ROUND51_TEST_SS_PID_FILE", javaPid.toString(),
                        "PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45),
                "--activate");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("SERVICE_EXCLUSION_MONITOR_DETECTED"), result.output());
        assertTrue(result.output().contains("BACKEND_PORT_OCCUPIED"), result.output());
    }

    @Test
    void scriptRefusesCheckoutDriftIntroducedDuringPackagingBeforeBackup() throws Exception {
        ScriptFixture fixture = createScriptFixture("package-checkout-drift");
        Path fakeBin = createFakeActivationTools("package-checkout-drift");
        String databaseBefore = sha256(fixture.database());

        ProcessResult result = fixture.run(
                Map.of(
                        "AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG",
                        "ROUND51_FAKE_FAILURE_STAGE", "after-baseline",
                        "ROUND51_TEST_MUTATE_PROJECT_ROOT", fixture.root().toString(),
                        "PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45),
                "--activate");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("tracked checkout is not clean"), result.output());
        assertEquals(databaseBefore, sha256(fixture.database()));
        try (var contents = Files.list(fixture.backupRoot())) {
            assertFalse(contents.anyMatch(Files::isDirectory));
        }
    }

    @Test
    void activationLockNeverFollowsOrTruncatesAChildSymlink() throws Exception {
        ScriptFixture fixture = createScriptFixture("lock-symlink");
        Files.createDirectories(fixture.backupRoot());
        Path hostileLock = fixture.backupRoot().resolve(".round51-activation.lock");
        Files.createSymbolicLink(hostileLock, fixture.environment());
        String environmentBefore = Files.readString(fixture.environment());
        Path fakeBin = createFakeActivationTools("lock-symlink");

        ProcessResult result = fixture.run(
                Map.of(
                        "AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG",
                        "ROUND51_FAKE_FAILURE_STAGE", "after-baseline",
                        "PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45),
                "--activate");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("ROLLBACK_COMPLETED"), result.output());
        assertTrue(Files.isSymbolicLink(hostileLock));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
    }

    @Test
    void activationRefusesAStaleOrphanFenceUnderTheRootLockWithoutMutation()
            throws Exception {
        ScriptFixture fixture = createScriptFixture("stale-orphan-activation-refusal");
        Files.createDirectories(fixture.backupRoot());
        Files.setPosixFilePermissions(fixture.backupRoot(), java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        Path staleOrphan = fixture.backupRoot()
                .resolve(".round51-recovery-startup-gate-orphan-fence-stale");
        writePrivateFile(staleOrphan, "stale evidence must be recovered, not overwritten\n");
        String databaseBefore = sha256(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        ProcessResult result = fixture.run(
                Map.of("AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG"),
                Duration.ofSeconds(30),
                "--activate");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(
                "stale Round 5.1 startup fence requires named recovery before activation"),
                result.output());
        assertTrue(Files.isRegularFile(staleOrphan));
        assertFalse(Files.exists(fixture.backupRoot().resolve(".round51-maintenance")));
        assertEquals(databaseBefore, sha256(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
        try (var entries = Files.list(fixture.backupRoot())) {
            assertEquals(1, entries.count(),
                    "refusal must happen before a backup run directory is created");
        }
    }

    @Test
    void alreadyActivatedVerificationRefusesAStaleOrphanFenceWithoutMutation()
            throws Exception {
        ScriptFixture fixture = createScriptFixture("stale-orphan-activated-refusal");
        createFullyVerifiedActivatedCandidate(fixture);
        assertEquals(0, helper("update-env", "--env-file",
                fixture.environment().toString()).exitCode());
        String activatedCommit = commitFixtureChange(
                fixture.root(), "Create activated stale-fence fixture");
        fixture = fixture.withCommit(activatedCommit);
        Files.createDirectories(fixture.backupRoot());
        Files.setPosixFilePermissions(fixture.backupRoot(), java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        Path staleOrphan = fixture.backupRoot()
                .resolve(".round51-activation-startup-gate-orphan-fence-stale");
        writePrivateFile(staleOrphan, "stale activation evidence\n");
        String databaseBefore = sha256(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        ProcessResult result = fixture.run("--dry-run");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(
                "stale Round 5.1 startup fence requires named recovery before activation"),
                result.output());
        assertEquals(databaseBefore, sha256(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
        assertTrue(Files.isRegularFile(staleOrphan));
    }

    @Test
    void activationRequiresTheExplicitHumanConfirmationBeforeBackupOrMutation() throws Exception {
        ScriptFixture fixture = createScriptFixture("missing-confirmation");
        String databaseBefore = sha256(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        ProcessResult result = fixture.run("--activate");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("AURALINK_ROUND51_CONFIRM confirmation token is required"));
        assertEquals(databaseBefore, sha256(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
        assertFalse(Files.exists(fixture.backupRoot()));
    }

    @Test
    void scriptRefusesPartiallyActivatedDatabaseWithoutMutation() throws Exception {
        ScriptFixture fixture = createScriptFixture("partial-state");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database())) {
            connection.createStatement().execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER)");
        }
        String partialHash = sha256(fixture.database());
        String commit = commitFixtureChange(fixture.root(), "Create partial activation fixture");
        fixture = fixture.withCommit(commit);

        ProcessResult result = fixture.run("--dry-run");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("database state is not safe for activation"), result.output());
        assertEquals(partialHash, sha256(fixture.database()));
        assertFalse(Files.exists(fixture.backupRoot()));
    }

    @Test
    void successfulShellOrchestrationBacksUpUpdatesEnvironmentAndWritesFinalEvidence() throws Exception {
        ScriptFixture fixture = createScriptFixture("successful-orchestration");
        Path fakeBin = createFakeSuccessfulActivationTools();
        String databaseBefore = sha256(fixture.database());

        ProcessResult result = fixture.run(
                Map.of(
                        "AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG",
                        "ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER",
                                fixture.backupRoot().resolve(".round51-maintenance").toString(),
                        "PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45),
                "--activate");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("ROUND51_ACTIVATION_COMPLETED"));
        assertTrue(result.output().contains("FINAL_ACTIVATED_STATE_VERIFIED"));
        assertFalse(Files.exists(fixture.backupRoot().resolve(".round51-maintenance")));
        assertEquals(databaseBefore, sha256(fixture.database()));
        String environment = Files.readString(fixture.environment());
        assertTrue(environment.contains("AURALINK_FLYWAY_ENABLED=false"));
        assertTrue(environment.contains("AURALINK_JPA_DDL_AUTO=none"));
        assertTrue(environment.contains("AURALINK_PAINTING_CATALOG_IMPORT_ENABLED=true"));
        assertTrue(environment.contains("AURALINK_PAINTING_CATALOG_IMPORT_FAIL_ON_ERROR=true"));

        Path activationDirectory;
        try (var directories = Files.list(fixture.backupRoot())) {
            activationDirectory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        assertTrue(Files.isRegularFile(activationDirectory.resolve("auralink.pre-activation.db")));
        assertTrue(Files.isRegularFile(activationDirectory.resolve("backend.env.pre-activation")));
        assertTrue(Files.isRegularFile(activationDirectory.resolve("database-backup-verification.json")));
        assertTrue(Files.isRegularFile(activationDirectory.resolve("final-activation-verification.json")));
        assertTrue(Files.isRegularFile(activationDirectory.resolve("post-activation-manifest.json")));
        assertTrue(Files.isRegularFile(activationDirectory.resolve(".round51-service-release-intent")));
        assertEquals("verified-release", Files.readString(
                activationDirectory.resolve(".round51-service-release-intent")).trim());
        assertEquals(1, filesWithPrefix(activationDirectory,
                ".round51-released-activated-marker-").size());
        assertTrue(filesWithPrefix(fixture.backupRoot(),
                ".round51-activation-startup-gate-orphan-fence-").isEmpty());
        assertEquals(java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(activationDirectory));
    }

    @Test
    void smokeResponseValidatorAcceptsSafeAssetUrlsAndRejectsAbsolutePaths() throws Exception {
        Path health = temporaryDirectory.resolve("health.json");
        Path gallery = temporaryDirectory.resolve("gallery.json");
        Path daily = temporaryDirectory.resolve("daily.json");
        Files.writeString(health, "{\"status\":\"UP\"}");
        Files.writeString(gallery,
                "{\"items\":[{\"image\":{\"contentUrl\":\"/api/v1/assets/"
                        + "00000000-0000-0000-0000-000000000001/content\"}}]}");
        Files.writeString(daily,
                "{\"image\":{\"contentUrl\":\"/api/v1/assets/"
                        + "00000000-0000-0000-0000-000000000001/content\"}}");

        assertEquals(0, helper("validate-smoke", "--health", health.toString(),
                "--gallery", gallery.toString(), "--daily", daily.toString()).exitCode());

        Files.writeString(daily, "{\"path\":\"/root/private/catalog.jpg\"}");
        ProcessResult unsafe = helper("validate-smoke", "--health", health.toString(),
                "--gallery", gallery.toString(), "--daily", daily.toString());
        assertNotEquals(0, unsafe.exitCode());
        assertTrue(unsafe.output().contains("local filesystem path"));
    }

    @Test
    void failureAfterBaselineTriggersAutomaticRollback() throws Exception {
        assertControlledActivationFailureRollsBack("after-baseline", false);
    }

    @Test
    void failureAfterV2TriggersAutomaticRollback() throws Exception {
        assertControlledActivationFailureRollsBack("after-v2", true);
    }

    @Test
    void failureDuringCatalogImportTriggersAutomaticRollback() throws Exception {
        assertControlledActivationFailureRollsBack("during-import", true);
    }

    @Test
    void contextInitializationFailureIsReportedSafelyAndRollsBack() throws Exception {
        ScriptFixture fixture = createScriptFixture("context-initialization-failure");
        Path fakeBin = createFakeActivationTools("context-init");
        String legacyDataBefore = legacyDataHash(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        ProcessResult result = fixture.run(
                Map.of(
                        "AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG",
                        "ROUND51_FAKE_FAILURE_STAGE", "context-init",
                        "ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER",
                                fixture.backupRoot().resolve(".round51-maintenance").toString(),
                        "PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45),
                "--activate");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains(
                "ROUND51_ACTIVATION_ERROR_CLASS=ACTIVATION_CONTEXT_INITIALIZATION_FAILED"),
                result.output());
        assertTrue(result.output().contains(
                "ROUND51_ACTIVATION_ERROR_SUMMARY="
                        + "Dedicated non-web activation context could not be initialized"),
                result.output());
        assertFalse(result.output().contains("No ServletContext set"), result.output());
        assertTrue(result.output().contains("ROLLBACK_COMPLETED"), result.output());
        assertEquals(legacyDataBefore, legacyDataHash(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
        assertFalse(tableExists(fixture.database(), "flyway_schema_history"));
        assertFalse(tableExists(fixture.database(), "media_assets"));

        Path activationDirectory;
        try (var directories = Files.list(fixture.backupRoot())) {
            activationDirectory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        Path privateJavaLog = activationDirectory.resolve("java-activation.log");
        assertTrue(Files.readString(privateJavaLog).contains("No ServletContext set"));
        assertEquals(java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(privateJavaLog));
        assertTrue(Files.isRegularFile(activationDirectory.resolve("failed-partial.db")));
    }

    @Test
    void helperBackupFailureSnapshotAndRestoreRecoverExactInheritedState() throws Exception {
        Path database = temporaryDirectory.resolve("rollback-live.db");
        Path backup = temporaryDirectory.resolve("private-backup.db");
        Path failed = temporaryDirectory.resolve("failed-partial.db");
        createInheritedDatabase(database);
        String inheritedHash = sha256(database);

        assertEquals(0, helper("backup-db", "--source", database.toString(),
                "--destination", backup.toString()).exitCode());
        assertEquals(0, helper("verify-backup", "--source", database.toString(),
                "--database", backup.toString()).exitCode());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER)");
            connection.createStatement().execute("CREATE TABLE media_assets (id INTEGER PRIMARY KEY)");
        }
        Path staleJournal = Path.of(database + "-journal");
        Files.write(staleJournal, new byte[] {0x41, 0x55, 0x52, 0x41});
        assertNotEquals(inheritedHash, sha256(database));
        assertEquals(0, helper("preserve-failed", "--database", database.toString(),
                "--destination-prefix", failed.toString()).exitCode());
        assertTrue(Files.isRegularFile(failed));
        assertTrue(Files.isRegularFile(Path.of(failed + "-journal")));

        ProcessResult restore = helper("restore-db", "--backup", backup.toString(),
                "--database", database.toString());

        assertEquals(0, restore.exitCode(), restore.output());
        assertTrue(restore.output().contains("\"state\": \"INHERITED_READY\""));
        assertFalse(tableExists(database, "flyway_schema_history"));
        assertFalse(tableExists(database, "media_assets"));
        assertFalse(Files.exists(staleJournal));
        assertEquals(7, count(database, "users"));
        assertEquals(118, count(database, "generation_logs"));
        assertTrue(tableExists(failed, "flyway_schema_history"));
    }

    @Test
    void helperClassifiesActivatedCandidateAndRejectablePartialStateReadOnly() throws Exception {
        Path activated = temporaryDirectory.resolve("activated.db");
        createInheritedDatabase(activated);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + activated);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER)");
            for (String table : FOUNDATION_TABLES) {
                statement.execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY)");
            }
        }
        String activatedHash = sha256(activated);

        ProcessResult activatedInspection = helper("inspect", "--database", activated.toString());

        assertEquals(0, activatedInspection.exitCode(), activatedInspection.output());
        assertTrue(activatedInspection.output().contains("\"state\": \"ACTIVATED_CANDIDATE\""));
        assertEquals(activatedHash, sha256(activated));

        Path partial = temporaryDirectory.resolve("partial.db");
        createInheritedDatabase(partial);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + partial)) {
            connection.createStatement().execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER)");
        }
        String partialHash = sha256(partial);

        ProcessResult partialInspection = helper("inspect", "--database", partial.toString());

        assertEquals(0, partialInspection.exitCode(), partialInspection.output());
        assertTrue(partialInspection.output().contains("\"state\": \"PARTIALLY_ACTIVATED_UNKNOWN\""));
        assertEquals(partialHash, sha256(partial));
    }

    @Test
    void environmentBackupUpdateAndRestorePreserveUnrelatedValuesAndRejectSymlinks() throws Exception {
        Path environment = temporaryDirectory.resolve("backend.env");
        Path backup = temporaryDirectory.resolve("backend.env.backup");
        String original = "UNRELATED_SETTING=preserve-me\n"
                + "AURALINK_FLYWAY_ENABLED=true\n"
                + "AURALINK_JPA_DDL_AUTO=update\n";
        Files.writeString(environment, original);

        assertEquals(0, helper("backup-env", "--source", environment.toString(),
                "--destination", backup.toString()).exitCode());
        assertEquals(original, Files.readString(backup));
        assertEquals(0, helper("update-env", "--env-file", environment.toString()).exitCode());

        String updated = Files.readString(environment);
        assertTrue(updated.contains("UNRELATED_SETTING=preserve-me"));
        assertTrue(updated.contains("AURALINK_FLYWAY_ENABLED=false"));
        assertTrue(updated.contains("AURALINK_JPA_DDL_AUTO=none"));
        assertTrue(updated.contains("AURALINK_PAINTING_CATALOG_IMPORT_ENABLED=true"));
        assertTrue(updated.contains("AURALINK_PAINTING_CATALOG_IMPORT_FAIL_ON_ERROR=true"));

        assertEquals(0, helper("restore-env", "--backup", backup.toString(),
                "--env-file", environment.toString()).exitCode());
        assertEquals(original, Files.readString(environment));

        Path symlink = temporaryDirectory.resolve("backend.env.link");
        Path rejectedBackup = temporaryDirectory.resolve("should-not-exist.env");
        Files.createSymbolicLink(symlink, environment.getFileName());
        ProcessResult symlinkResult = helper("backup-env", "--source", symlink.toString(),
                "--destination", rejectedBackup.toString());
        assertNotEquals(0, symlinkResult.exitCode());
        assertTrue(symlinkResult.output().contains("non-symlink"));
        assertFalse(Files.exists(rejectedBackup));
    }

    @Test
    void maintenanceMarkerRequiresItsNonceForRemovalAndIsDurablyReleased() throws Exception {
        Path parent = temporaryDirectory.resolve("maintenance-parent");
        Path marker = parent.resolve(".round51-maintenance");
        Files.createDirectory(parent);

        ProcessResult created = helper("create-maintenance-marker", "--marker", marker.toString());
        assertEquals(0, created.exitCode(), created.output());
        String token = created.output().trim();
        assertTrue(token.matches("[0-9a-f]{64}"));
        assertTrue(Files.isRegularFile(marker));

        ProcessResult wrongOwner = run(
                BACKEND_ROOT,
                Map.of("AURALINK_ROUND51_MAINTENANCE_TOKEN", "b".repeat(64)),
                Duration.ofSeconds(30),
                "python3", HELPER.toString(), "remove-maintenance-marker",
                "--marker", marker.toString());
        assertNotEquals(0, wrongOwner.exitCode());
        assertTrue(Files.isRegularFile(marker));

        ProcessResult released = run(
                BACKEND_ROOT,
                Map.of("AURALINK_ROUND51_MAINTENANCE_TOKEN", token),
                Duration.ofSeconds(30),
                "python3", HELPER.toString(), "remove-maintenance-marker",
                "--marker", marker.toString());
        assertEquals(0, released.exitCode(), released.output());
        assertFalse(Files.exists(marker));

    }

    @Test
    void privateEvidencePublicationIsAtomicAndLeavesNoPartialFinalMarker() throws Exception {
        String helperSource = Files.readString(HELPER);

        assertTrue(helperSource.contains("tempfile.mkstemp("));
        assertTrue(helperSource.contains("os.link(temporary, destination, follow_symlinks=False)"));
        assertFalse(helperSource.contains(
                "os.open(destination, flags, 0o600)\n    try:\n        with os.fdopen"));
    }

    @Test
    void finalFenceReleasePinsStateAndExcludesSqliteWritersThroughUnlink() throws Exception {
        String helperSource = Files.readString(HELPER);
        int releaseFunction = helperSource.indexOf("def remove_stale_maintenance_marker(");
        int nextFunction = helperSource.indexOf("\ndef _atomic_copy(", releaseFunction);
        String releaseSource = helperSource.substring(releaseFunction, nextFunction);

        int databaseLock = releaseSource.indexOf("lock_connection.execute(\"BEGIN IMMEDIATE\")");
        int currentValidation = releaseSource.indexOf("if allow_activated_current:");
        int releaseIntent = releaseSource.indexOf(
                "_write_private_exclusive(release_intent, b\"verified-release\\n\")");
        int releasedMarkerLink = releaseSource.indexOf("os.link(");
        int releasedMarkerFsync = releaseSource.indexOf("os.fsync(released_parent_descriptor)");
        int markerUnlink = releaseSource.indexOf(
                "os.unlink(marker_file.path.name, dir_fd=marker_file.parent_descriptor)");
        int postUnlinkDatabaseCheck = releaseSource.indexOf(
                "database_file.assert_current(\"current database\")", markerUnlink);
        int postUnlinkEnvironmentCheck = releaseSource.indexOf(
                "env_file_handle.assert_current(\"current environment\")", markerUnlink);
        int postUnlinkCatalogCheck = releaseSource.indexOf(
                "current_catalog_fingerprint = catalog_fingerprint", markerUnlink);
        int rollback = releaseSource.indexOf("lock_connection.execute(\"ROLLBACK\")");

        assertTrue(releaseSource.contains("database_file = _open_pinned_regular_file(\n"
                + "            current_database, \"current database\""));
        assertTrue(releaseSource.contains("verify_activation_env_text(env_content.decode(\"utf-8\"))"));
        assertTrue(releaseSource.contains("database_file.assert_current(\"current database\")"));
        assertTrue(releaseSource.contains(
                "os.unlink(marker_file.path.name, dir_fd=marker_file.parent_descriptor)"));
        assertTrue(releaseSource.contains("marker_file.descriptor).st_nlink != 1"));
        assertTrue(databaseLock >= 0 && databaseLock < currentValidation);
        assertTrue(currentValidation < releasedMarkerLink);
        assertTrue(releasedMarkerLink < releasedMarkerFsync);
        assertTrue(releasedMarkerFsync < releaseIntent);
        assertTrue(releaseIntent < markerUnlink);
        assertTrue(markerUnlink < postUnlinkDatabaseCheck);
        assertTrue(markerUnlink < postUnlinkEnvironmentCheck);
        assertTrue(markerUnlink < postUnlinkCatalogCheck);
        assertTrue(postUnlinkDatabaseCheck < rollback);
        assertTrue(postUnlinkEnvironmentCheck < rollback);
        assertTrue(postUnlinkCatalogCheck < rollback);
        assertTrue(markerUnlink < rollback,
                "SQLite writer exclusion must remain held through durable marker release");

        String activationSource = Files.readString(SCRIPT);
        int activatedRelease = activationSource.lastIndexOf(
                "release_maintenance_marker activated");
        int startupGateRetirement = activationSource.indexOf(
                "stop_startup_gate", activatedRelease);
        assertTrue(activatedRelease >= 0 && activatedRelease < startupGateRetirement,
                "the exclusive startup gate must remain held until marker release succeeds");

        int activationStopFunction = activationSource.indexOf("stop_startup_gate() {");
        int activationNextFunction = activationSource.indexOf(
                "\nrefuse_preexisting_orphan_fences()", activationStopFunction);
        String activationStopSource = activationSource.substring(
                activationStopFunction, activationNextFunction);
        int activationOrphanUnlink = activationStopSource.indexOf(
                "rm -f -- \"$STARTUP_GATE_ORPHAN_FENCE\"");
        int activationDirectoryFsync = activationStopSource.indexOf("fsync-dir");
        int activationHolderStop = activationStopSource.indexOf("stop_owned_process");
        assertTrue(activationOrphanUnlink >= 0
                        && activationOrphanUnlink < activationDirectoryFsync
                        && activationDirectoryFsync < activationHolderStop,
                "activation must durably retire orphan evidence before releasing the kernel gate");

        int activationRootLock = activationSource.indexOf("flock -n 9");
        int staleFenceRefusal = activationSource.indexOf(
                "refuse_preexisting_orphan_fences", activationRootLock);
        int activationGateStart = activationSource.indexOf("start_startup_gate", staleFenceRefusal);
        assertTrue(activationRootLock >= 0
                        && activationRootLock < staleFenceRefusal
                        && staleFenceRefusal < activationGateStart,
                "stale-fence refusal must run under the root lock before acquiring a new gate");

        String recoverySource = Files.readString(RECOVERY_SCRIPT);
        int recoveryStopFunction = recoverySource.indexOf("stop_startup_gate() {");
        int recoveryNextFunction = recoverySource.indexOf(
                "\nstart_recovery_service_monitor()", recoveryStopFunction);
        String recoveryStopSource = recoverySource.substring(
                recoveryStopFunction, recoveryNextFunction);
        int recoveryOrphanUnlink = recoveryStopSource.indexOf(
                "rm -f -- \"$STARTUP_GATE_ORPHAN_FENCE\"");
        int recoveryDirectoryFsync = recoveryStopSource.indexOf("fsync-dir");
        int recoveryHolderStop = recoveryStopSource.indexOf("stop_owned_process");
        assertTrue(recoveryOrphanUnlink >= 0
                        && recoveryOrphanUnlink < recoveryDirectoryFsync
                        && recoveryDirectoryFsync < recoveryHolderStop,
                "recovery must durably retire orphan evidence before releasing the kernel gate");
    }

    private void assertControlledActivationFailureRollsBack(
            String failureStage,
            boolean expectFoundationInSnapshot) throws Exception {
        ScriptFixture fixture = createScriptFixture("rollback-" + failureStage);
        Path fakeBin = createFakeActivationTools(failureStage);
        String legacyDataBefore = legacyDataHash(fixture.database());
        String environmentBefore = Files.readString(fixture.environment());

        ProcessResult result = fixture.run(
                Map.of(
                        "AURALINK_ROUND51_CONFIRM", "ACTIVATE_AURALINK_2_0_CATALOG",
                        "ROUND51_FAKE_FAILURE_STAGE", failureStage,
                        "ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER",
                                fixture.backupRoot().resolve(".round51-maintenance").toString(),
                        "PATH", fakeBin + ":" + System.getenv("PATH")),
                Duration.ofSeconds(45),
                "--activate");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("ROLLBACK_COMPLETED"), result.output());
        assertEquals(legacyDataBefore, legacyDataHash(fixture.database()));
        assertEquals(environmentBefore, Files.readString(fixture.environment()));
        assertEquals(7, count(fixture.database(), "users"));
        assertEquals(118, count(fixture.database(), "generation_logs"));
        assertFalse(tableExists(fixture.database(), "flyway_schema_history"));
        assertFalse(tableExists(fixture.database(), "media_assets"));
        assertFalse(Files.exists(fixture.backupRoot().resolve(".round51-maintenance")));

        Path activationDirectory;
        try (var directories = Files.list(fixture.backupRoot())) {
            activationDirectory = directories
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElseThrow();
        }
        Path failedSnapshot = activationDirectory.resolve("failed-partial.db");
        assertTrue(Files.isRegularFile(failedSnapshot));
        assertTrue(tableExists(failedSnapshot, "flyway_schema_history"));
        assertEquals(expectFoundationInSnapshot, tableExists(failedSnapshot, "media_assets"));
        assertTrue(Files.isRegularFile(activationDirectory.resolve("auralink.pre-activation.db")));
        assertTrue(Files.isRegularFile(activationDirectory.resolve("backend.env.pre-activation")));
    }

    private Path createFakeActivationTools(String name) throws IOException {
        Path fakeBin = temporaryDirectory.resolve("fake-bin-" + name);
        Files.createDirectories(fakeBin);

        Path maven = fakeBin.resolve("mvn");
        Files.writeString(maven, "#!/usr/bin/env bash\n"
                + "set -eu\n"
                + "if [[ -n ${ROUND51_TEST_MUTATE_PROJECT_ROOT:-} ]]; then\n"
                + "  printf '%s\\n' drift > \"$ROUND51_TEST_MUTATE_PROJECT_ROOT/tracked-drift.txt\"\n"
                + "fi\n"
                + "mkdir -p target\n"
                + ": > target/auralink-backend-0.0.1-SNAPSHOT.jar\n");
        maven.toFile().setExecutable(true);

        Path java = fakeBin.resolve("java");
        Files.writeString(java, "#!/usr/bin/env bash\n"
                + "set -eu\n"
                + "if [[ -n ${ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER:-} ]]; then\n"
                + "  test -f \"$ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER\"\n"
                + "  test \"$(tr -d '\\n' < \"$ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER\")\" "
                + "= \"${AURALINK_ROUND51_MAINTENANCE_TOKEN:-}\"\n"
                + "fi\n"
                + "if [[ -n ${ROUND51_TEST_JAVA_HOLD_FILE:-} ]]; then\n"
                + "  if [[ -n ${ROUND51_TEST_JAVA_PID_FILE:-} ]]; then "
                + "printf '%s\\n' $$ > \"$ROUND51_TEST_JAVA_PID_FILE\"; fi\n"
                + "  : > \"$ROUND51_TEST_JAVA_HOLD_FILE\"\n"
                + "  /bin/sleep 10\n"
                + "fi\n"
                + "if [[ ${ROUND51_FAKE_FAILURE_STAGE:?} == context-init ]]; then\n"
                + "  printf '%s\\n' "
                + "ROUND51_ACTIVATION_ERROR_CLASS=ACTIVATION_CONTEXT_INITIALIZATION_FAILED\n"
                + "  printf '%s\\n' 'ROUND51_ACTIVATION_ERROR_SUMMARY="
                + "Dedicated non-web activation context could not be initialized'\n"
                + "  printf '%s\\n' 'BeanCreationException: No ServletContext set "
                + "[private diagnostics only]'\n"
                + "  exit 42\n"
                + "fi\n"
                + "database=${AURALINK_DATABASE_URL#jdbc:sqlite:}\n"
                + "python3 - \"$database\" \"${ROUND51_FAKE_FAILURE_STAGE:?}\" <<'PY'\n"
                + "import sqlite3, sys\n"
                + "database, stage = sys.argv[1:]\n"
                + "tables = " + pythonListLiteral(FOUNDATION_TABLES) + "\n"
                + "with sqlite3.connect(database) as connection:\n"
                + "    connection.execute('CREATE TABLE flyway_schema_history (installed_rank INTEGER)')\n"
                + "    if stage in {'after-v2', 'during-import'}:\n"
                + "        for table in tables:\n"
                + "            connection.execute(f'CREATE TABLE {table} (id INTEGER PRIMARY KEY)')\n"
                + "    if stage == 'during-import':\n"
                + "        connection.execute('INSERT INTO paintings(id) VALUES (1)')\n"
                + "PY\n"
                + "if [[ -n ${ROUND51_TEST_REMOVE_MAINTENANCE_MARKER:-} ]]; then\n"
                + "  rm -f -- \"$ROUND51_TEST_REMOVE_MAINTENANCE_MARKER\"\n"
                + "  /bin/sleep 10\n"
                + "fi\n"
                + "exit 42\n");
        java.toFile().setExecutable(true);

        Path python = fakeBin.resolve("python3");
        Files.writeString(python, "#!/usr/bin/env bash\n"
                + "set -eu\n"
                + "if [[ ${2:-} == restore-db "
                + "&& -n ${ROUND51_TEST_REMOVE_MARKER_DURING_RESTORE:-} ]]; then\n"
                + "  " + testPythonInvocation() + "\n"
                + "  status=$?\n"
                + "  rm -f -- \"$ROUND51_TEST_REMOVE_MARKER_DURING_RESTORE\"\n"
                + "  /bin/sleep 10\n"
                + "  exit $status\n"
                + "fi\n"
                + "exec " + testPythonInvocation() + "\n");
        python.toFile().setExecutable(true);

        Path sleep = fakeBin.resolve("sleep");
        Files.writeString(sleep, "#!/usr/bin/env bash\n/bin/sleep 0.01\n");
        sleep.toFile().setExecutable(true);
        return fakeBin;
    }

    private Path createFakeSuccessfulActivationTools() throws IOException {
        Path fakeBin = temporaryDirectory.resolve("fake-bin-success");
        Files.createDirectories(fakeBin);

        Path maven = fakeBin.resolve("mvn");
        Files.writeString(maven, "#!/usr/bin/env bash\n"
                + "set -eu\n"
                + "if [[ -n ${ROUND51_TEST_MUTATE_PROJECT_ROOT:-} ]]; then\n"
                + "  printf '%s\\n' drift > \"$ROUND51_TEST_MUTATE_PROJECT_ROOT/tracked-drift.txt\"\n"
                + "fi\n"
                + "mkdir -p target\n"
                + ": > target/auralink-backend-0.0.1-SNAPSHOT.jar\n");
        maven.toFile().setExecutable(true);

        Path java = fakeBin.resolve("java");
        Files.writeString(java, "#!/usr/bin/env bash\n"
                + "set -eu\n"
                + "if [[ -n ${ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER:-} ]]; then\n"
                + "  test -f \"$ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER\"\n"
                + "  test \"$(tr -d '\\n' < \"$ROUND51_TEST_REQUIRE_MAINTENANCE_MARKER\")\" "
                + "= \"${AURALINK_ROUND51_MAINTENANCE_TOKEN:-}\"\n"
                + "fi\n"
                + "printf '%s\\n' ROUND51_ACTIVATION_COMPLETED\n");
        java.toFile().setExecutable(true);

        Path python = fakeBin.resolve("python3");
        Files.writeString(python, "#!/usr/bin/env bash\n"
                + "if [[ ${2:-} == verify-activated ]]; then\n"
                + "  printf '%s\\n' '{\"state\":\"ACTIVATED_CANDIDATE\"}'\n"
                + "  exit 0\n"
                + "fi\n"
                + "if [[ ${2:-} == remove-stale-maintenance-marker ]]; then\n"
                + "  marker=\n"
                + "  released_marker=\n"
                + "  release_intent=\n"
                + "  previous=\n"
                + "  for argument in \"$@\"; do\n"
                + "    if [[ $previous == --marker ]]; then marker=$argument; fi\n"
                + "    if [[ $previous == --released-marker ]]; then released_marker=$argument; fi\n"
                + "    if [[ $previous == --release-intent ]]; then release_intent=$argument; fi\n"
                + "    previous=$argument\n"
                + "  done\n"
                + "  test -n \"$marker\" -a -n \"$released_marker\" -a -n \"$release_intent\"\n"
                + "  test \"$*\" != \"${*#*--allow-activated-current}\"\n"
                + "  test \"$*\" != \"${*#*--expected-catalog-fingerprint}\"\n"
                + "  ln -- \"$marker\" \"$released_marker\"\n"
                + "  printf '%s\\n' verified-release > \"$release_intent\"\n"
                + "  rm -f -- \"$marker\"\n"
                + "  exit 0\n"
                + "fi\n"
                + "exec " + testPythonInvocation() + "\n");
        python.toFile().setExecutable(true);

        Path sleep = fakeBin.resolve("sleep");
        Files.writeString(sleep, "#!/usr/bin/env bash\n/bin/sleep 0.01\n");
        sleep.toFile().setExecutable(true);
        return fakeBin;
    }

    private Path createVerifiedActivatedRecoveryTools() throws IOException {
        Path fakeBin = temporaryDirectory.resolve("fake-bin-recovery-activated");
        Files.createDirectories(fakeBin);
        Path python = fakeBin.resolve("python3");
        Files.writeString(python, "#!/usr/bin/env bash\n"
                + "set -eu\n"
                + "if [[ ${2:-} == verify-preflight ]]; then\n"
                + "  printf '%s\\n' '{\"state\":\"ACTIVATED_CANDIDATE\"}'\n"
                + "  exit 0\n"
                + "fi\n"
                + "exec " + testPythonInvocation() + "\n");
        python.toFile().setExecutable(true);
        return fakeBin;
    }

    private Path createRecoveryMarkerLossTools(Path marker) throws IOException {
        Path fakeBin = temporaryDirectory.resolve("fake-bin-recovery-marker-loss");
        Files.createDirectories(fakeBin);
        Path python = fakeBin.resolve("python3");
        Files.writeString(python, "#!/usr/bin/env bash\n"
                + "set -eu\n"
                + "if [[ ${2:-} == restore-db ]]; then\n"
                + "  " + testPythonInvocation() + "\n"
                + "  status=$?\n"
                + "  rm -f -- " + shellQuote(marker.toString()) + "\n"
                + "  /bin/sleep 10\n"
                + "  exit $status\n"
                + "fi\n"
                + "exec " + testPythonInvocation() + "\n");
        python.toFile().setExecutable(true);
        return fakeBin;
    }

    private RecoveryBackup prepareRecoveryBackup(ScriptFixture fixture, String directoryName)
            throws Exception {
        runRequired(fixture.root(), "git", "update-index", "--assume-unchanged",
                "backend/auralink.db", "backend/.env");
        Path directory = fixture.backupRoot().resolve(directoryName);
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(fixture.backupRoot(), java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(directory, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));

        Path databaseBackup = directory.resolve("auralink.pre-activation.db");
        Path environmentBackup = directory.resolve("backend.env.pre-activation");
        Path manifest = directory.resolve("pre-activation-manifest.json");
        Path verification = directory.resolve("database-backup-verification.json");
        String originalEnvironment = Files.readString(fixture.environment());

        assertEquals(0, helper("manifest", "--database", fixture.database().toString(),
                "--destination", manifest.toString(), "--phase", "pre-activation").exitCode());
        ProcessResult databaseBackupResult = helper(
                "backup-db", "--source", fixture.database().toString(),
                "--destination", databaseBackup.toString());
        assertEquals(0, databaseBackupResult.exitCode(), databaseBackupResult.output());
        ProcessResult environmentBackupResult = helper(
                "backup-env", "--source", fixture.environment().toString(),
                "--destination", environmentBackup.toString());
        assertEquals(0, environmentBackupResult.exitCode(), environmentBackupResult.output());
        ProcessResult verificationResult = helper(
                "verify-backup", "--source", fixture.database().toString(),
                "--database", databaseBackup.toString());
        assertEquals(0, verificationResult.exitCode(), verificationResult.output());
        Files.writeString(verification, verificationResult.output());
        Files.setPosixFilePermissions(verification, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));

        Path marker = fixture.backupRoot().resolve(".round51-maintenance");
        Path binding = directory.resolve("round51-recovery-binding.json");
        ProcessResult markerResult = helper(
                "create-recovery-binding",
                "--marker", marker.toString(),
                "--binding", binding.toString(),
                "--database-backup", databaseBackup.toString(),
                "--environment-backup", environmentBackup.toString(),
                "--pre-activation-manifest", manifest.toString(),
                "--database-verification", verification.toString(),
                "--reviewed-commit", fixture.commit());
        assertEquals(0, markerResult.exitCode(), markerResult.output());
        assertTrue(markerResult.output().trim().matches("[0-9a-f]{64}"));
        return new RecoveryBackup(
                directory, databaseBackup, environmentBackup, binding, marker, originalEnvironment);
    }

    private void assertReleasedMarkersAuthenticate(
            ScriptFixture fixture,
            RecoveryBackup backup,
            List<Path> releasedMarkers) throws Exception {
        String retainedToken = null;
        for (Path releasedMarker : releasedMarkers) {
            ProcessResult retained = helper(
                    "retain-verified-recovery-token",
                    "--marker", releasedMarker.toString(),
                    "--binding", backup.binding().toString(),
                    "--database-backup", backup.database().toString(),
                    "--environment-backup", backup.environment().toString(),
                    "--pre-activation-manifest",
                    backup.directory().resolve("pre-activation-manifest.json").toString(),
                    "--database-verification",
                    backup.directory().resolve("database-backup-verification.json").toString(),
                    "--reviewed-commit", fixture.commit());
            assertEquals(0, retained.exitCode(), retained.output());
            String currentToken = retained.output().trim();
            assertTrue(currentToken.matches("[0-9a-f]{64}"));
            if (retainedToken == null) {
                retainedToken = currentToken;
            } else {
                assertEquals(retainedToken, currentToken,
                        "all durable release records for one binding must retain one nonce");
            }
        }
    }

    private void createBoundOrphanFence(
            ScriptFixture fixture,
            RecoveryBackup backup,
            Path orphanFence) throws Exception {
        ProcessResult created = helper(
                "create-bound-orphan-fence",
                "--fence", orphanFence.toString(),
                "--binding", backup.binding().toString(),
                "--database-backup", backup.database().toString(),
                "--environment-backup", backup.environment().toString(),
                "--pre-activation-manifest",
                backup.directory().resolve("pre-activation-manifest.json").toString(),
                "--database-verification",
                backup.directory().resolve("database-backup-verification.json").toString(),
                "--reviewed-commit", fixture.commit());
        assertEquals(0, created.exitCode(), created.output());
        assertTrue(Files.isRegularFile(orphanFence));
    }

    private List<Path> filesWithPrefix(Path directory, String prefix) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .toList();
        }
    }

    private void writePrivateFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
    }

    private void createMinimalActivatedCandidate(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER)");
            for (String table : FOUNDATION_TABLES) {
                statement.execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY)");
            }
        }
    }

    private void createFullyVerifiedActivatedCandidate(ScriptFixture fixture) throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:sqlite:" + fixture.database(), null, null)
                .locations("filesystem:" + BACKEND_ROOT.resolve(
                        "src/main/resources/db/migration").toAbsolutePath())
                .baselineVersion("1")
                .baselineOnMigrate(false)
                .target("2")
                .load();
        flyway.baseline();
        flyway.migrate();
        String fingerprint = catalogFingerprint(
                fixture.root().resolve("frontend/public/data/paintings.csv"),
                fixture.root().resolve("backend/picture/fixture.jpg"));
        String now = "2026-08-12 12:00:00";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("INSERT INTO media_assets(public_id, storage_key, original_filename, "
                    + "mime_type, file_size, sha256, width, height, asset_type, semantic_type, "
                    + "source_type, visibility, status, created_at, updated_at) VALUES "
                    + "('00000000-0000-0000-0000-000000000001','catalog/fixture.jpg',"
                    + "'fixture.jpg','image/jpeg',3,'" + sha256(fixture.root().resolve(
                            "backend/picture/fixture.jpg")) + "',1,1,'IMAGE','PAINTING',"
                    + "'CATALOG_REFERENCE','PUBLIC','ACTIVE','" + now + "','" + now + "')");
            statement.execute("INSERT INTO paintings(public_id, source_key, source_sequence, "
                    + "image_storage_name, title, creation_dynasty_raw, "
                    + "creation_dynasty_normalized, generated_text, music_scene_description, "
                    + "image_asset_id, image_available, visible_in_gallery, status, created_at, "
                    + "updated_at) VALUES ('00000000-0000-0000-0000-000000000002',"
                    + "'painting-dataset:fixture','1','fixture','测试画作','清','清代',"
                    + "'官方文本','官方音乐',1,1,1,'ACTIVE','" + now + "','" + now + "')");
            statement.execute("INSERT INTO catalog_import_runs(public_id, source_name, "
                    + "source_sha256, total_rows, inserted_rows, updated_rows, unchanged_rows, "
                    + "matched_images, missing_images, orphan_images, status, started_at, "
                    + "finished_at) VALUES ('00000000-0000-0000-0000-000000000003',"
                    + "'fixture','" + fingerprint + "',1,1,0,0,1,0,0,'SUCCESS',"
                    + "'2026-08-12 12:00:00','2026-08-12 12:00:01')");
            statement.execute("INSERT INTO catalog_import_runs(public_id, source_name, "
                    + "source_sha256, total_rows, inserted_rows, updated_rows, unchanged_rows, "
                    + "matched_images, missing_images, orphan_images, status, started_at, "
                    + "finished_at) VALUES ('00000000-0000-0000-0000-000000000004',"
                    + "'fixture','" + fingerprint + "',1,0,0,1,1,0,0,'SKIPPED',"
                    + "'2026-08-12 12:01:00','2026-08-12 12:01:01')");
        }
    }

    private String pythonListLiteral(List<String> values) {
        return "[" + values.stream()
                .map(value -> "'" + value + "'")
                .collect(java.util.stream.Collectors.joining(", ")) + "]";
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String commitFixtureChange(Path root, String message) throws Exception {
        runRequired(root, "git", "add", ".");
        runRequired(root, "git", "-c", "user.name=Round51 Test", "-c",
                "user.email=round51-test@example.invalid", "commit", "-m", message);
        return runRequired(root, "git", "rev-parse", "HEAD").output().trim();
    }

    private ScriptFixture createScriptFixture(String name) throws Exception {
        Path root = temporaryDirectory.resolve(name).toAbsolutePath().normalize();
        Path backend = root.resolve("backend");
        Path scripts = backend.resolve("scripts");
        Path migrations = backend.resolve("src/main/resources/db/migration");
        Files.createDirectories(scripts);
        Files.createDirectories(migrations);
        Files.createDirectories(backend.resolve("picture"));
        Files.createDirectories(root.resolve("frontend/public/data"));

        Path backupRoot = temporaryDirectory.resolve(name + "-activation-backups").toAbsolutePath().normalize();
        Files.copy(HELPER, scripts.resolve("round51_state.py"));

        Path environment = backend.resolve(".env");
        Files.writeString(environment, "SERVER_PORT=45991\n");
        Files.setPosixFilePermissions(environment, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        Path database = backend.resolve("auralink.db");
        createInheritedDatabase(database);
        Files.writeString(migrations.resolve("V1__legacy_schema_baseline.sql"), "-- fixture V1\n");
        Files.writeString(migrations.resolve("V2__create_auralink_2_0_foundation.sql"), "-- fixture V2\n");
        String row = String.join(",", List.of(
                "1", "fixture", "测试画作", "测试作者", "", "", "", "", "清", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "", "", "官方文本", "官方音乐", ""));
        Files.writeString(root.resolve("frontend/public/data/paintings.csv"),
                String.join(",", CATALOG_HEADERS) + "\n" + row + "\n", StandardCharsets.UTF_8);
        Files.write(backend.resolve("picture/fixture.jpg"), new byte[] {1, 2, 3});

        Path fixtureTools = root.resolve(".round51-test-bin");
        Files.createDirectories(fixtureTools);
        Path ss = fixtureTools.resolve("ss");
        Files.writeString(ss, "#!/usr/bin/env bash\n"
                + "if [[ -n ${ROUND51_TEST_SS_FAIL:-} ]]; then exit 42; fi\n"
                + "if [[ -n ${ROUND51_TEST_SS_PID_FILE:-} "
                + "&& -s $ROUND51_TEST_SS_PID_FILE ]]; then\n"
                + "  pid=$(cat \"$ROUND51_TEST_SS_PID_FILE\")\n"
                + "  printf 'LISTEN 0 50 127.0.0.1:45991 0.0.0.0:* "
                + "users:((java,pid=%s,fd=4))\\n' \"$pid\"\n"
                + "  exit 0\n"
                + "fi\n"
                + "if [[ -n ${ROUND51_TEST_SS_TRIGGER_FILE:-} "
                + "&& -e $ROUND51_TEST_SS_TRIGGER_FILE ]]; then\n"
                + "  printf '%s\\n' \"${ROUND51_TEST_SS_TRIGGER_OUTPUT:-}\"\n"
                + "  exit 0\n"
                + "fi\n"
                + "if [[ -n ${ROUND51_TEST_SS_OUTPUT:-} ]]; then\n"
                + "  printf '%s\\n' \"$ROUND51_TEST_SS_OUTPUT\"\n"
                + "fi\n");
        ss.toFile().setExecutable(true);

        String fixtureLegacyDataHash = legacyDataHash(database);
        String fixtureFingerprint = catalogFingerprint(
                root.resolve("frontend/public/data/paintings.csv"),
                backend.resolve("picture/fixture.jpg"));
        String productionScript = Files.readString(SCRIPT);
        String productionRecoveryScript = Files.readString(RECOVERY_SCRIPT);
        String fixtureRootDeclaration = "readonly SERVER_LOCAL_ROOT=\"" + root + "\"";
        String fixtureBackupDeclaration = "readonly BACKUP_ROOT=\"" + backupRoot + "\"";
        String fixtureScript = productionScript
                .replace("readonly SERVER_LOCAL_ROOT=\"/root/autodl-tmp/auralink\"", fixtureRootDeclaration)
                .replace("readonly BACKUP_ROOT=\"/root/auralink_activation_backups\"", fixtureBackupDeclaration)
                .replace("readonly EXPECTED_LEGACY_DATA_SHA256=\""
                                + "1a0d0e7f41964ee77d4a78c9a86ec47d732f1d202400e180d41994046b941131\"",
                        "readonly EXPECTED_LEGACY_DATA_SHA256=\"" + fixtureLegacyDataHash + "\"")
                .replace("readonly EXPECTED_CATALOG_FINGERPRINT=\""
                                + "a9cf4b05e374ecaa975c51c59eda6e2a6b1adf1e02badcb69994189c7554aff6\"",
                        "readonly EXPECTED_CATALOG_FINGERPRINT=\"" + fixtureFingerprint + "\"")
                .replace("readonly EXPECTED_PAINTINGS=11067", "readonly EXPECTED_PAINTINGS=1")
                .replace("readonly EXPECTED_IMAGE_FILES=9069", "readonly EXPECTED_IMAGE_FILES=1")
                .replace("readonly EXPECTED_CATALOG_ASSETS=9067", "readonly EXPECTED_CATALOG_ASSETS=1")
                .replace("readonly EXPECTED_MISSING_IMAGES=2000", "readonly EXPECTED_MISSING_IMAGES=0")
                .replace("readonly EXPECTED_ORPHAN_IMAGES=2", "readonly EXPECTED_ORPHAN_IMAGES=0")
                .replace("readonly EXPECTED_GENERATED_TEXT=8915", "readonly EXPECTED_GENERATED_TEXT=1")
                .replace("readonly EXPECTED_MUSIC_SCENE=9068", "readonly EXPECTED_MUSIC_SCENE=1")
                .replace("readonly EXPECTED_GALLERY_VISIBLE=9067", "readonly EXPECTED_GALLERY_VISIBLE=1");
        assertTrue(fixtureScript.contains(fixtureRootDeclaration));
        assertTrue(fixtureScript.contains(fixtureBackupDeclaration));
        assertTrue(fixtureScript.contains(fixtureFingerprint));
        Files.writeString(scripts.resolve("activate-round5-catalog.sh"), fixtureScript);

        String fixtureRecoveryScript = productionRecoveryScript
                .replace("readonly SERVER_LOCAL_ROOT=\"/root/autodl-tmp/auralink\"", fixtureRootDeclaration)
                .replace("readonly BACKUP_ROOT=\"/root/auralink_activation_backups\"", fixtureBackupDeclaration)
                .replace("readonly EXPECTED_LEGACY_DATA_SHA256=\""
                                + "1a0d0e7f41964ee77d4a78c9a86ec47d732f1d202400e180d41994046b941131\"",
                        "readonly EXPECTED_LEGACY_DATA_SHA256=\"" + fixtureLegacyDataHash + "\"")
                .replace("a9cf4b05e374ecaa975c51c59eda6e2a6b1adf1e02badcb69994189c7554aff6",
                        fixtureFingerprint)
                .replace("--expected-paintings 11067 --expected-image-files 9069",
                        "--expected-paintings 1 --expected-image-files 1")
                .replace("--expected-catalog-assets 9067 --expected-missing-images 2000",
                        "--expected-catalog-assets 1 --expected-missing-images 0")
                .replace("--expected-orphan-images 2 --expected-generated-text 8915",
                        "--expected-orphan-images 0 --expected-generated-text 1")
                .replace("--expected-music-scene 9068 --expected-gallery-visible 9067",
                        "--expected-music-scene 1 --expected-gallery-visible 1");
        assertTrue(fixtureRecoveryScript.contains(fixtureRootDeclaration));
        assertTrue(fixtureRecoveryScript.contains(fixtureBackupDeclaration));
        assertTrue(fixtureRecoveryScript.contains(fixtureLegacyDataHash));
        Files.writeString(scripts.resolve("recover-round5-catalog-activation.sh"), fixtureRecoveryScript);

        runRequired(root, "git", "init", "-b", "main");
        runRequired(root, "git", "add", ".");
        runRequired(root, "git", "-c", "user.name=Round51 Test", "-c",
                "user.email=round51-test@example.invalid", "commit", "-m", "Fixture");
        String commit = runRequired(root, "git", "rev-parse", "HEAD").output().trim();
        return new ScriptFixture(root, database, environment, backupRoot, commit);
    }

    private String gitStatus(Path root) throws Exception {
        return runRequired(root, "git", "status", "--porcelain", "--untracked-files=all").output().trim();
    }

    private ProcessResult helper(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 2];
        command[0] = testPython3.toString();
        command[1] = HELPER.toString();
        System.arraycopy(arguments, 0, command, 2, arguments.length);
        return run(BACKEND_ROOT, Map.of("PYTHONDONTWRITEBYTECODE", "1"),
                Duration.ofSeconds(30), command);
    }

    private String testPythonInvocation() {
        return Round51TestPython.shellQuote(testPython3) + " \"$@\"";
    }

    private Path createFakeSshfsFindmnt(
            Path fakeBin,
            Path expectedProjectRoot,
            Path invocationRecord) throws IOException {
        Path findmnt = fakeBin.resolve("findmnt");
        Files.writeString(findmnt, "#!/usr/bin/env bash\n"
                + "set -eu\n"
                + "if [[ $# -ne 5 || $1 != -n || $2 != -o || $3 != FSTYPE "
                + "|| $4 != -T || $5 != " + shellQuote(expectedProjectRoot.toString()) + " ]]; then\n"
                + "  printf '%s\\n' 'unexpected findmnt invocation' >&2\n"
                + "  exit 64\n"
                + "fi\n"
                + "printf 'PATH=%s\\n' \"$PATH\" > " + shellQuote(invocationRecord.toString()) + "\n"
                + "printf '%s\\n' \"$@\" >> " + shellQuote(invocationRecord.toString()) + "\n"
                + "printf '%s\\n' fuse.sshfs\n");
        findmnt.toFile().setExecutable(true);
        return findmnt;
    }

    private void createInheritedDatabase(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                        id integer,
                        account_non_expired boolean NOT NULL,
                        account_non_locked boolean NOT NULL,
                        created_at timestamp,
                        credentials_non_expired boolean NOT NULL,
                        email varchar(255) NOT NULL UNIQUE,
                        enabled boolean NOT NULL,
                        full_name varchar(255) NOT NULL,
                        password varchar(255) NOT NULL,
                        role varchar(255) NOT NULL,
                        updated_at timestamp,
                        username varchar(255) NOT NULL UNIQUE,
                        PRIMARY KEY (id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE generation_logs (
                        id integer,
                        api_provider varchar(255),
                        api_source varchar(255) NOT NULL,
                        created_at timestamp NOT NULL,
                        description varchar(1024),
                        duration integer,
                        error_message varchar(1024),
                        image_url varchar(1024),
                        input_data TEXT,
                        metadata TEXT,
                        model_size varchar(255) NOT NULL,
                        output_data TEXT,
                        processing_time_ms bigint,
                        result_url varchar(1024),
                        success boolean NOT NULL,
                        task_type varchar(255) NOT NULL,
                        use_fast_generate boolean NOT NULL,
                        user_id bigint NOT NULL,
                        PRIMARY KEY (id)
                    )
                    """);
            for (int index = 1; index <= 7; index++) {
                statement.execute("INSERT INTO users VALUES ("
                        + index + ",1,1,NULL,1,'fixture-" + index + "@example.invalid',1,'Fixture User "
                        + index + "','safe-test-hash','ROLE_USER',NULL,'fixture-user-" + index + "')");
            }
            for (int index = 1; index <= 118; index++) {
                int user = ((index - 1) % 7) + 1;
                statement.execute("INSERT INTO generation_logs VALUES ("
                        + index + ",NULL,'FIXTURE','2026-01-01 00:00:00',NULL,NULL,NULL,NULL,NULL,NULL,"
                        + "'test',NULL,NULL,NULL,1,'TEST',0," + user + ")");
            }
        }
    }

    private String catalogFingerprint(Path csv, Path image) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("auralink-official-painting-snapshot-v1\n".getBytes(StandardCharsets.UTF_8));
        digest.update(sha256(csv).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(image.getFileName().toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(Files.size(image)).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(Files.getLastModifiedTime(image).toMillis()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        return HexFormat.of().formatHex(digest.digest());
    }

    private String legacyDataHash(Path database) throws Exception {
        ProcessResult inspection = helper("inspect", "--database", database.toString());
        assertEquals(0, inspection.exitCode(), inspection.output());
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\\"legacyDataSha256\\\": \\\"([0-9a-f]{64})\\\"")
                .matcher(inspection.output());
        assertTrue(matcher.find(), inspection.output());
        return matcher.group(1);
    }

    private boolean tableExists(Path database, String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private int count(Path database, String table) throws SQLException {
        if (!table.equals("users") && !table.equals("generation_logs")) {
            throw new IllegalArgumentException("Unexpected fixture table");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var source = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private ProcessResult runRequired(Path directory, String... command) throws Exception {
        ProcessResult result = run(directory, Map.of(), Duration.ofSeconds(30), command);
        assertEquals(0, result.exitCode(), result.output());
        return result;
    }

    private ProcessResult run(
            Path directory,
            Map<String, String> environment,
            Duration timeout,
            String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Process timed out: " + String.join(" ", command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record RecoveryBackup(
            Path directory,
            Path database,
            Path environment,
            Path binding,
            Path marker,
            String originalEnvironment) {
    }

    private record ScriptFixture(
            Path root,
            Path database,
            Path environment,
            Path backupRoot,
            String commit) {
        ScriptFixture withCommit(String updatedCommit) {
            return new ScriptFixture(root, database, environment, backupRoot, updatedCommit);
        }

        ProcessResult run(String... arguments) throws Exception {
            return run(Map.of(), Duration.ofSeconds(30), arguments);
        }

        ProcessResult run(
                Map<String, String> additionalEnvironment,
                Duration timeout,
                String... arguments) throws Exception {
            String[] command = new String[arguments.length + 2];
            command[0] = "bash";
            command[1] = root.resolve("backend/scripts/activate-round5-catalog.sh").toString();
            System.arraycopy(arguments, 0, command, 2, arguments.length);
            java.util.Map<String, String> environment = new java.util.HashMap<>(additionalEnvironment);
            environment.putIfAbsent("AURALINK_ROUND51_EXPECTED_COMMIT", commit);
            String requestedPath = environment.getOrDefault("PATH", System.getenv("PATH"));
            environment.put("PATH", root.resolve(".round51-test-bin") + ":" + requestedPath);
            return Round51ActivationScriptContractTest.runStatic(
                    root,
                    environment,
                    timeout,
                    command);
        }

        ProcessResult runRecovery(Path backupDirectory) throws Exception {
            return runRecovery(backupDirectory, Map.of(), Duration.ofSeconds(30));
        }

        ProcessResult runRecovery(
                Path backupDirectory,
                Map<String, String> additionalEnvironment,
                Duration timeout) throws Exception {
            java.util.Map<String, String> environment = new java.util.HashMap<>(additionalEnvironment);
            environment.putIfAbsent("AURALINK_ROUND51_EXPECTED_COMMIT", commit);
            environment.putIfAbsent(
                    "AURALINK_ROUND51_RECOVERY_CONFIRM",
                    "RESTORE_AURALINK_ROUND51_PRE_ACTIVATION_BACKUP");
            String requestedPath = environment.getOrDefault("PATH", System.getenv("PATH"));
            environment.put("PATH", root.resolve(".round51-test-bin") + ":" + requestedPath);
            return Round51ActivationScriptContractTest.runStatic(
                    root,
                    environment,
                    timeout,
                    "bash",
                    root.resolve("backend/scripts/recover-round5-catalog-activation.sh").toString(),
                    "--backup-dir",
                    backupDirectory.toString());
        }
    }

    private static ProcessResult runStatic(
            Path directory,
            Map<String, String> environment,
            Duration timeout,
            String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Process timed out: " + String.join(" ", command));
        }
        return new ProcessResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
