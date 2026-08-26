package com.auralink.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.BaselineResult;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlywayMigrationIntegrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final String EXTERNAL_LEGACY_DATABASE_PROPERTY = "auralink.test.legacy-db";
    private static final String LEGACY_FIXTURE_RESOURCE = "/db/legacy/inherited_schema_fixture.sql";
    private static final Set<String> FOUNDATION_TABLES = Set.of(
            "media_assets",
            "paintings",
            "catalog_import_runs",
            "painting_guides",
            "painting_favorites",
            "user_workflows",
            "creations",
            "creation_steps",
            "creation_favorites",
            "creation_execution_attempts",
            "creation_step_dispatch_attempts"
    );
    private static final Set<String> FOUNDATION_INDEXES = Set.of(
            "idx_media_assets_owner_created",
            "idx_media_assets_type_status",
            "idx_media_assets_sha256",
            "idx_paintings_image_storage_name",
            "idx_paintings_gallery_status",
            "idx_paintings_dynasty",
            "idx_paintings_category",
            "idx_paintings_author_name",
            "idx_paintings_image_asset",
            "idx_catalog_import_runs_status_started",
            "idx_catalog_import_runs_source_sha256",
            "idx_painting_guides_status",
            "idx_painting_guides_source_hash",
            "idx_painting_favorites_user_created",
            "idx_painting_favorites_painting_user",
            "idx_user_workflows_user_status_updated",
            "idx_creations_user_created",
            "idx_creations_user_status",
            "idx_creations_workflow",
            "idx_creations_source_painting",
            "idx_creations_source_asset",
            "idx_creations_final_asset",
            "idx_creations_status_created_id",
            "idx_creations_status_lease_id",
            "idx_creations_user_created_public",
            "idx_creation_steps_creation_status",
            "idx_creation_steps_input_asset",
            "idx_creation_steps_output_asset",
            "idx_creation_favorites_user_created",
            "idx_creation_favorites_user_created_public",
            "idx_creation_favorites_creation_user",
            "uq_creation_execution_attempts_one_active",
            "idx_creation_execution_attempts_creation_admitted",
            "idx_creation_step_dispatch_attempts_step",
            "idx_creation_step_dispatch_attempts_execution"
    );

    @TempDir
    Path tempDir;

    @Test
    void preservesHistoricalMigrationBytes() throws Exception {
        assertEquals("ef47e64423dec6952b6839e1cea64dac4416d2e7e438e6f3ad1bd167b46c29e0",
                fileSha256("src/main/resources/db/migration/V1__legacy_schema_baseline.sql"));
        assertEquals("d400ce503333262f9b18bb5f3539136269d7bdab84bfcf34d7f2e22ab7a758e6",
                fileSha256("src/main/resources/db/migration/V2__create_auralink_2_0_foundation.sql"));
        assertEquals("67b6229bd1b42a87cc494a40104c3c42f72fd5de6d2eac9d68e2ff49cdd41653",
                fileSha256("src/main/resources/db/migration/V3__add_creation_execution_recovery.sql"));
    }

    @Test
    void explicitlyBaselinesAndMigratesCopiedInheritedDatabase() throws Exception {
        Path database = prepareInheritedDatabase("existing.db");
        String jdbcUrl = jdbcUrl(database);

        LegacySnapshot before;
        int usersBefore;
        int logsBefore;
        try (Connection connection = openCheckedConnection(jdbcUrl)) {
            usersBefore = rowCount(connection, "users");
            logsBefore = rowCount(connection, "generation_logs");
            assertTrue(usersBefore > 0, "Inherited fixture must contain users");
            assertTrue(logsBefore > 0, "Inherited fixture must contain generation logs");
            assertFalse(tableExists(connection, "flyway_schema_history"));
            assertFoundationTablesAbsent(connection);
            assertIntegrityChecksPass(connection);
            assertEquals(authoritativeLegacyStructure(), legacyStructure(connection));
            before = legacySnapshot(connection);
        }

        Flyway flyway = configuredFlyway(jdbcUrl);
        BaselineResult baseline = flyway.baseline();
        assertTrue(baseline.successfullyBaselined);
        assertEquals("1", baseline.baselineVersion);

        try (Connection connection = openCheckedConnection(jdbcUrl)) {
            assertSchemaHistoryRow(connection, "1", "BASELINE", true);
        }

        MigrateResult firstMigrate = flyway.migrate();
        assertTrue(firstMigrate.success);
        assertEquals(3, firstMigrate.migrationsExecuted);

        ValidateResult validation = flyway.validateWithResult();
        assertTrue(validation.validationSuccessful, validation::getAllErrorMessages);

        MigrateResult secondMigrate = flyway.migrate();
        assertTrue(secondMigrate.success);
        assertEquals(0, secondMigrate.migrationsExecuted);
        assertEquals(0, flyway.info().pending().length);

        try (Connection connection = openCheckedConnection(jdbcUrl)) {
            assertSchemaHistoryRow(connection, "1", "BASELINE", true);
            assertSchemaHistoryRow(connection, "2", "SQL", true);
            assertSchemaHistoryRow(connection, "3", "SQL", true);
            assertSchemaHistoryRow(connection, "4", "SQL", true);
            assertFoundationSchema(connection);
            assertEquals(usersBefore, rowCount(connection, "users"));
            assertEquals(logsBefore, rowCount(connection, "generation_logs"));
            assertEquals(before, legacySnapshot(connection));
            assertIntegrityChecksPass(connection);
        }
    }

    @Test
    void migratesCleanDatabaseThroughV1V2V3AndV4() throws Exception {
        LegacyStructure authoritativeLegacyStructure = authoritativeLegacyStructure();

        Path cleanDatabase = tempDir.resolve("clean.db");
        String jdbcUrl = jdbcUrl(cleanDatabase);
        Flyway flyway = configuredFlyway(jdbcUrl);

        MigrateResult firstMigrate = flyway.migrate();
        assertTrue(firstMigrate.success);
        assertEquals(4, firstMigrate.migrationsExecuted);

        ValidateResult validation = flyway.validateWithResult();
        assertTrue(validation.validationSuccessful, validation::getAllErrorMessages);

        MigrateResult secondMigrate = flyway.migrate();
        assertTrue(secondMigrate.success);
        assertEquals(0, secondMigrate.migrationsExecuted);
        assertEquals(0, flyway.info().pending().length);

        try (Connection connection = openCheckedConnection(jdbcUrl)) {
            assertSchemaHistoryRow(connection, "1", "SQL", true);
            assertSchemaHistoryRow(connection, "2", "SQL", true);
            assertSchemaHistoryRow(connection, "3", "SQL", true);
            assertSchemaHistoryRow(connection, "4", "SQL", true);
            assertFoundationSchema(connection);
            assertEquals(0, rowCount(connection, "users"));
            assertEquals(0, rowCount(connection, "generation_logs"));
            assertEquals(authoritativeLegacyStructure, legacyStructure(connection));
            assertIntegrityChecksPass(connection);
        }
    }

    @Test
    void migratesPopulatedV2CreationsThroughV4WithDeterministicBackfill() throws Exception {
        Path database = createLegacyFixture("populated-v2.db");
        String jdbcUrl = jdbcUrl(database);
        Flyway v2 = configuredFlyway(jdbcUrl, "2");
        assertTrue(v2.baseline().successfullyBaselined);
        MigrateResult v2Migration = v2.migrate();
        assertTrue(v2Migration.success);
        assertEquals(1, v2Migration.migrationsExecuted);

        try (Connection connection = openCheckedConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            long userId = firstUserId(connection);
            statement.execute("INSERT INTO creations (public_id, user_id, workflow_snapshot, source_modality, "
                    + "status, created_at, started_at, finished_at) VALUES "
                    + "('00000000-0000-0000-0000-000000000101', " + userId + ", '{}', "
                    + "'TEXT_DESCRIPTION', 'SUCCEEDED', '2026-08-01 01:02:03', "
                    + "'2026-08-02 01:02:03', '2026-08-03 01:02:03'), "
                    + "('00000000-0000-0000-0000-000000000102', " + userId + ", '{}', "
                    + "'TEXT_DESCRIPTION', 'RUNNING', '2026-08-04 01:02:03', "
                    + "'2026-08-05 01:02:03', NULL), "
                    + "('00000000-0000-0000-0000-000000000103', " + userId + ", '{}', "
                    + "'TEXT_DESCRIPTION', 'QUEUED', '2026-08-06 01:02:03', NULL, NULL)");
            statement.execute("INSERT INTO creation_steps (public_id, creation_id, step_index, node_id, "
                    + "operation_code, input_modality, output_modality, status, attempt_count) "
                    + "SELECT '00000000-0000-0000-0000-000000000201', id, 0, 'step1', "
                    + "'TEXT_TO_PAINTING', 'TEXT_DESCRIPTION', 'PAINTING', 'SUCCEEDED', 1 "
                    + "FROM creations WHERE public_id='00000000-0000-0000-0000-000000000101'");
        }

        Flyway fullTarget = configuredFlyway(jdbcUrl);
        MigrateResult v3AndV4Migration = fullTarget.migrate();
        assertTrue(v3AndV4Migration.success);
        assertEquals(2, v3AndV4Migration.migrationsExecuted);
        assertEquals(0, fullTarget.migrate().migrationsExecuted);
        assertEquals(0, fullTarget.info().pending().length);

        try (Connection connection = openCheckedConnection(jdbcUrl)) {
            assertSchemaHistoryRow(connection, "1", "BASELINE", true);
            assertSchemaHistoryRow(connection, "2", "SQL", true);
            assertSchemaHistoryRow(connection, "3", "SQL", true);
            assertSchemaHistoryRow(connection, "4", "SQL", true);
            assertEquals("2026-08-03 01:02:03", queryString(connection,
                    "SELECT updated_at FROM creations WHERE public_id='00000000-0000-0000-0000-000000000101'"));
            assertEquals("2026-08-05 01:02:03", queryString(connection,
                    "SELECT updated_at FROM creations WHERE public_id='00000000-0000-0000-0000-000000000102'"));
            assertEquals("2026-08-06 01:02:03", queryString(connection,
                    "SELECT updated_at FROM creations WHERE public_id='00000000-0000-0000-0000-000000000103'"));
            assertEquals("NOT_SENT", queryString(connection,
                    "SELECT provider_dispatch_state FROM creation_steps "
                            + "WHERE public_id='00000000-0000-0000-0000-000000000201'"));
            assertEquals(3, rowCount(connection, "creations"));
            assertEquals(1, rowCount(connection, "creation_steps"));
            assertEquals(3, rowCount(connection, "creation_execution_attempts"));
            assertEquals(1, rowCount(connection, "creation_step_dispatch_attempts"));
            assertEquals("LEGACY_V3_STATE_SNAPSHOT", queryString(connection,
                    "SELECT resolution_code FROM creation_step_dispatch_attempts"));
            assertEquals("0", queryString(connection,
                    "SELECT retry_version FROM creations WHERE public_id='00000000-0000-0000-0000-000000000101'"));
            assertFoundationSchema(connection);
            assertIntegrityChecksPass(connection);
        }
    }

    @Test
    void backfillsOneHonestV3SnapshotForEachCreationAndStartedStep() throws Exception {
        Path database = createLegacyFixture("v3-retry-snapshots.db");
        String jdbcUrl = jdbcUrl(database);
        Flyway v3 = configuredFlyway(jdbcUrl, "3");
        assertTrue(v3.baseline().successfullyBaselined);
        assertEquals(2, v3.migrate().migrationsExecuted);

        try (Connection connection = openCheckedConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            long userId = firstUserId(connection);
            statement.execute("INSERT INTO creations (public_id, user_id, workflow_snapshot, source_modality, "
                    + "status, created_at, started_at, finished_at, updated_at) VALUES "
                    + "('00000000-0000-0000-0000-000000000301', " + userId + ", '{}', "
                    + "'TEXT_DESCRIPTION', 'QUEUED', '2026-08-01 00:00:00', NULL, NULL, '2026-08-01 00:00:00'), "
                    + "('00000000-0000-0000-0000-000000000302', " + userId + ", '{}', "
                    + "'TEXT_DESCRIPTION', 'RUNNING', '2026-08-01 00:00:00', '2026-08-01 00:01:00', NULL, '2026-08-01 00:01:00'), "
                    + "('00000000-0000-0000-0000-000000000303', " + userId + ", '{}', "
                    + "'TEXT_DESCRIPTION', 'RUNNING', '2026-08-01 00:00:00', '2026-08-01 00:01:00', NULL, '2026-08-01 00:01:00'), "
                    + "('00000000-0000-0000-0000-000000000304', " + userId + ", '{}', "
                    + "'TEXT_DESCRIPTION', 'SUCCEEDED', '2026-08-01 00:00:00', '2026-08-01 00:01:00', '2026-08-01 00:02:00', '2026-08-01 00:02:00'), "
                    + "('00000000-0000-0000-0000-000000000305', " + userId + ", '{}', "
                    + "'TEXT_DESCRIPTION', 'FAILED', '2026-08-01 00:00:00', '2026-08-01 00:01:00', '2026-08-01 00:02:00', '2026-08-01 00:02:00'), "
                    + "('00000000-0000-0000-0000-000000000306', " + userId + ", '{}', "
                    + "'TEXT_DESCRIPTION', 'PARTIAL_SUCCESS', '2026-08-01 00:00:00', '2026-08-01 00:01:00', '2026-08-01 00:02:00', '2026-08-01 00:02:00')");
            insertV3Step(statement, "00000000-0000-0000-0000-000000000401", "00000000-0000-0000-0000-000000000302",
                    0, "RUNNING", "NOT_SENT", null);
            insertV3Step(statement, "00000000-0000-0000-0000-000000000402", "00000000-0000-0000-0000-000000000303",
                    0, "RUNNING", "SEND_STARTED", "legacy-v3-send-started-key");
            insertV3Step(statement, "00000000-0000-0000-0000-000000000403", "00000000-0000-0000-0000-000000000304",
                    0, "SUCCEEDED", "RESULT_PERSISTED", "legacy-v3-success-key");
            insertV3Step(statement, "00000000-0000-0000-0000-000000000404", "00000000-0000-0000-0000-000000000305",
                    0, "FAILED", "SEND_STARTED", "legacy-v3-failed-key");
            insertV3Step(statement, "00000000-0000-0000-0000-000000000405", "00000000-0000-0000-0000-000000000306",
                    0, "SUCCEEDED", "RESULT_PERSISTED", "legacy-v3-partial-success-key");
            insertV3Step(statement, "00000000-0000-0000-0000-000000000406", "00000000-0000-0000-0000-000000000306",
                    1, "FAILED", "NOT_SENT", null);
        }

        Flyway v4 = configuredFlyway(jdbcUrl);
        assertEquals(1, v4.migrate().migrationsExecuted);
        assertEquals(0, v4.migrate().migrationsExecuted);

        try (Connection connection = openCheckedConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            assertEquals(6, rowCount(connection, "creation_execution_attempts"));
            assertEquals(6, rowCount(connection, "creation_step_dispatch_attempts"));
            assertEquals(3, queryInt(connection, "SELECT count(*) FROM creation_execution_attempts "
                    + "WHERE finished_at IS NULL"));
            int terminalFixtureCreations = queryInt(connection, "SELECT count(*) FROM creations "
                    + "WHERE status IN ('SUCCEEDED', 'FAILED', 'PARTIAL_SUCCESS')");
            assertEquals(3, terminalFixtureCreations,
                    "the fixture has one SUCCEEDED, one FAILED, and one PARTIAL_SUCCESS Creation");
            assertEquals(terminalFixtureCreations, queryInt(connection,
                    "SELECT count(*) FROM creation_execution_attempts "
                            + "WHERE resolution_code='LEGACY_V3_STATE_SNAPSHOT'"));
            assertEquals("SEND_STARTED", queryString(connection,
                    "SELECT dispatch_state FROM creation_step_dispatch_attempts d "
                            + "JOIN creation_steps s ON s.id=d.creation_step_id "
                            + "WHERE s.public_id='00000000-0000-0000-0000-000000000402'"));
            assertEquals("RESULT_PERSISTED", queryString(connection,
                    "SELECT dispatch_state FROM creation_step_dispatch_attempts d "
                            + "JOIN creation_steps s ON s.id=d.creation_step_id "
                            + "WHERE s.public_id='00000000-0000-0000-0000-000000000403'"));
            assertEquals("LEGACY_V3_STATE_SNAPSHOT", queryString(connection,
                    "SELECT resolution_code FROM creation_step_dispatch_attempts d "
                            + "JOIN creation_steps s ON s.id=d.creation_step_id "
                            + "WHERE s.public_id='00000000-0000-0000-0000-000000000406'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO creation_execution_attempts (creation_id, attempt_number, admitted_at) "
                            + "SELECT id, 2, '2026-08-03 00:00:00' FROM creations "
                            + "WHERE public_id='00000000-0000-0000-0000-000000000301'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO creation_step_dispatch_attempts (creation_step_id, creation_execution_attempt_id, dispatch_state) "
                            + "VALUES (1, 1, 'UNSAFE')"));
            assertIntegrityChecksPass(connection);
        }
    }

    private Path prepareInheritedDatabase(String filename) throws IOException, SQLException {
        Optional<Path> externalDatabase = resolveExternalLegacyDatabase();
        if (externalDatabase.isEmpty()) {
            return createLegacyFixture(filename);
        }

        Path source = externalDatabase.orElseThrow();
        Path destination = tempDir.resolve(filename);
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        assertTrue(Files.isRegularFile(destination));
        assertEquals(Files.size(source), Files.size(destination));
        return destination;
    }

    private Optional<Path> resolveExternalLegacyDatabase() throws IOException {
        String configured = System.getProperty(EXTERNAL_LEGACY_DATABASE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }

        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path source = Path.of(configured).toRealPath();
        if (!source.startsWith(temporaryRoot) || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException(
                    EXTERNAL_LEGACY_DATABASE_PROPERTY + " must identify a regular database backup under /tmp");
        }
        return Optional.of(source);
    }

    private Path createLegacyFixture(String filename) throws IOException, SQLException {
        Path database = tempDir.resolve(filename);
        String script;
        try (var input = FlywayMigrationIntegrationTest.class.getResourceAsStream(LEGACY_FIXTURE_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing legacy schema test fixture");
            }
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Connection connection = openCheckedConnection(jdbcUrl(database));
             Statement statement = connection.createStatement()) {
            for (String command : script.split(";")) {
                String sql = command.trim();
                if (!sql.isEmpty()) {
                    statement.execute(sql);
                }
            }
        }
        return database;
    }

    private LegacyStructure authoritativeLegacyStructure() throws IOException, SQLException {
        Path referenceDatabase = createLegacyFixture("legacy-reference-" + System.nanoTime() + ".db");
        try (Connection connection = openCheckedConnection(jdbcUrl(referenceDatabase))) {
            return legacyStructure(connection);
        }
    }

    private Flyway configuredFlyway(String jdbcUrl) {
        return configuredFlyway(jdbcUrl, null);
    }

    private Flyway configuredFlyway(String jdbcUrl, String targetVersion) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations(MIGRATION_LOCATION)
                .baselineOnMigrate(false)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .validateOnMigrate(true);
        if (targetVersion != null) {
            configuration.target(targetVersion);
        }
        return configuration.load();
    }

    private long firstUserId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT id FROM users ORDER BY id LIMIT 1")) {
            assertTrue(resultSet.next(), "Legacy fixture must contain a user");
            return resultSet.getLong(1);
        }
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            String value = resultSet.getString(1);
            assertFalse(resultSet.next());
            return value;
        }
    }

    private int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            int value = resultSet.getInt(1);
            assertFalse(resultSet.next());
            return value;
        }
    }

    private void insertV3Step(
            Statement statement,
            String stepPublicId,
            String creationPublicId,
            int stepIndex,
            String status,
            String dispatchState,
            String requestKey) throws SQLException {
        String request = requestKey == null ? "NULL" : "'" + requestKey + "'";
        statement.execute("INSERT INTO creation_steps (public_id, creation_id, step_index, node_id, "
                + "operation_code, provider_code, input_modality, output_modality, status, attempt_count, "
                + "started_at, finished_at, provider_dispatch_state, provider_request_key) "
                + "SELECT '" + stepPublicId + "', id, " + stepIndex + ", 'step-" + stepIndex + "', "
                + "'TEXT_TO_PAINTING', 'seedream-5', 'TEXT_DESCRIPTION', 'PAINTING', '" + status + "', 1, "
                + "'2026-08-01 00:01:00', "
                + ("RUNNING".equals(status) ? "NULL" : "'2026-08-01 00:02:00'") + ", '"
                + dispatchState + "', " + request + " FROM creations WHERE public_id='" + creationPublicId + "'");
    }

    private Connection openCheckedConnection(String jdbcUrl) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        assertEquals(1, pragmaInt(connection, "foreign_keys"));
        return connection;
    }

    private String jdbcUrl(Path database) {
        return "jdbc:sqlite:" + database.toAbsolutePath().normalize();
    }

    private void assertFoundationTablesAbsent(Connection connection) throws SQLException {
        for (String table : FOUNDATION_TABLES) {
            assertFalse(tableExists(connection, table), () -> table + " must not exist before migration");
        }
    }

    private void assertFoundationTablesPresent(Connection connection) throws SQLException {
        for (String table : FOUNDATION_TABLES) {
            assertTrue(tableExists(connection, table), () -> table + " must exist after migration");
        }
    }

    private void assertFoundationSchema(Connection connection) throws SQLException {
        assertFoundationTablesPresent(connection);
        assertFoundationIndexesPresent(connection);
        assertCreationV3Columns(connection);

        assertForeignKeys(connection, "media_assets", Set.of(
                new ForeignKeyPolicy("owner_user_id", "users", "id", "RESTRICT")));
        assertForeignKeys(connection, "paintings", Set.of(
                new ForeignKeyPolicy("image_asset_id", "media_assets", "id", "SET NULL")));
        assertForeignKeys(connection, "catalog_import_runs", Set.of());
        assertForeignKeys(connection, "painting_guides", Set.of(
                new ForeignKeyPolicy("painting_id", "paintings", "id", "RESTRICT")));
        assertForeignKeys(connection, "painting_favorites", Set.of(
                new ForeignKeyPolicy("user_id", "users", "id", "CASCADE"),
                new ForeignKeyPolicy("painting_id", "paintings", "id", "RESTRICT")));
        assertForeignKeys(connection, "user_workflows", Set.of(
                new ForeignKeyPolicy("user_id", "users", "id", "RESTRICT")));
        assertForeignKeys(connection, "creations", Set.of(
                new ForeignKeyPolicy("user_id", "users", "id", "RESTRICT"),
                new ForeignKeyPolicy("workflow_id", "user_workflows", "id", "SET NULL"),
                new ForeignKeyPolicy("source_painting_id", "paintings", "id", "RESTRICT"),
                new ForeignKeyPolicy("source_asset_id", "media_assets", "id", "RESTRICT"),
                new ForeignKeyPolicy("final_asset_id", "media_assets", "id", "RESTRICT")));
        assertForeignKeys(connection, "creation_steps", Set.of(
                new ForeignKeyPolicy("creation_id", "creations", "id", "CASCADE"),
                new ForeignKeyPolicy("input_asset_id", "media_assets", "id", "RESTRICT"),
                new ForeignKeyPolicy("output_asset_id", "media_assets", "id", "RESTRICT")));
        assertForeignKeys(connection, "creation_favorites", Set.of(
                new ForeignKeyPolicy("user_id", "users", "id", "CASCADE"),
                new ForeignKeyPolicy("creation_id", "creations", "id", "CASCADE")));
        assertForeignKeys(connection, "creation_execution_attempts", Set.of(
                new ForeignKeyPolicy("creation_id", "creations", "id", "CASCADE")));
        assertForeignKeys(connection, "creation_step_dispatch_attempts", Set.of(
                new ForeignKeyPolicy("creation_step_id", "creation_steps", "id", "CASCADE"),
                new ForeignKeyPolicy("creation_execution_attempt_id", "creation_execution_attempts", "id", "CASCADE"),
                new ForeignKeyPolicy("result_asset_id", "media_assets", "id", "RESTRICT")));
    }

    private void assertCreationV3Columns(Connection connection) throws SQLException {
        assertTrue(tableColumnNames(connection, "creations").containsAll(Set.of(
                "error_code", "error_message", "updated_at", "claim_token", "lease_expires_at")));
        assertTrue(tableColumnNames(connection, "creation_steps").containsAll(Set.of(
                "provider_dispatch_state", "provider_request_key")));
        assertTrue(tableColumnNames(connection, "creations").contains("retry_version"));
        Set<String> executionAttemptColumns = tableColumnNames(connection, "creation_execution_attempts");
        assertTrue(executionAttemptColumns.containsAll(Set.of(
                "creation_id", "attempt_number", "retry_idempotency_key_digest", "admitted_at", "finished_at",
                "resolution_code")));
        Set<String> dispatchAttemptColumns = tableColumnNames(connection, "creation_step_dispatch_attempts");
        assertTrue(dispatchAttemptColumns.containsAll(Set.of(
                "creation_step_id", "creation_execution_attempt_id", "provider_request_key", "dispatch_state",
                "dispatch_started_at", "finished_at", "resolution_code", "result_asset_id", "result_digest",
                "canonical_poem_digest")));
        Set<String> forbidden = Set.of("source_text", "workflow_snapshot", "prompt", "base64", "provider_body",
                "provider_endpoint", "provider_model", "api_key", "authorization", "signed_url", "stack_trace",
                "filesystem_path", "storage_key");
        assertTrue(java.util.Collections.disjoint(executionAttemptColumns, forbidden));
        assertTrue(java.util.Collections.disjoint(dispatchAttemptColumns, forbidden));
    }

    private Set<String> tableColumnNames(Connection connection, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (resultSet.next()) {
                names.add(resultSet.getString("name"));
            }
        }
        return names;
    }

    private void assertFoundationIndexesPresent(Connection connection) throws SQLException {
        Set<String> actual = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'index'")) {
            while (resultSet.next()) {
                actual.add(resultSet.getString("name"));
            }
        }

        Set<String> missing = new LinkedHashSet<>(FOUNDATION_INDEXES);
        missing.removeAll(actual);
        assertTrue(missing.isEmpty(), () -> "Missing foundation indexes: " + missing);
    }

    private void assertForeignKeys(
            Connection connection,
            String table,
            Set<ForeignKeyPolicy> expected) throws SQLException {
        Set<ForeignKeyPolicy> actual = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list('" + table + "')")) {
            while (resultSet.next()) {
                actual.add(new ForeignKeyPolicy(
                        resultSet.getString("from"),
                        resultSet.getString("table"),
                        resultSet.getString("to"),
                        resultSet.getString("on_delete")));
            }
        }
        assertEquals(expected, actual, () -> "Unexpected foreign-key policy for " + table);
    }

    private void assertSchemaHistoryRow(Connection connection, String version, String type, boolean successful)
            throws SQLException {
        String sql = "SELECT type, success FROM flyway_schema_history WHERE version = '" + version + "'";
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), () -> "Missing Flyway schema history version " + version);
            assertEquals(type, resultSet.getString("type"));
            assertEquals(successful, resultSet.getBoolean("success"));
            assertFalse(resultSet.next(), () -> "Duplicate Flyway schema history version " + version);
        }
    }

    private void assertIntegrityChecksPass(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check")) {
            assertTrue(resultSet.next());
            assertEquals("ok", resultSet.getString(1));
            assertFalse(resultSet.next());
        }

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check")) {
            assertFalse(resultSet.next(), "Foreign-key check must not report violations");
        }
    }

    private LegacySnapshot legacySnapshot(Connection connection) throws SQLException {
        return new LegacySnapshot(
                legacyStructure(connection),
                digestTable(connection, "users"),
                digestTable(connection, "generation_logs")
        );
    }

    private LegacyStructure legacyStructure(Connection connection) throws SQLException {
        return new LegacyStructure(
                tableStructure(connection, "users"),
                tableStructure(connection, "generation_logs")
        );
    }

    private TableStructure tableStructure(Connection connection, String table) throws SQLException {
        List<ColumnDefinition> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (resultSet.next()) {
                columns.add(new ColumnDefinition(
                        resultSet.getInt("cid"),
                        resultSet.getString("name"),
                        normalizeType(resultSet.getString("type")),
                        resultSet.getBoolean("notnull"),
                        resultSet.getString("dflt_value"),
                        resultSet.getInt("pk")
                ));
            }
        }
        assertFalse(columns.isEmpty(), () -> "Missing legacy table " + table);

        Set<List<String>> uniqueColumnSets = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet indexes = statement.executeQuery("PRAGMA index_list('" + table + "')")) {
            while (indexes.next()) {
                if (indexes.getBoolean("unique")) {
                    uniqueColumnSets.add(indexColumns(connection, indexes.getString("name")));
                }
            }
        }

        List<ForeignKeyDefinition> foreignKeys = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list('" + table + "')")) {
            while (resultSet.next()) {
                foreignKeys.add(new ForeignKeyDefinition(
                        resultSet.getString("table"),
                        resultSet.getString("from"),
                        resultSet.getString("to"),
                        resultSet.getString("on_update"),
                        resultSet.getString("on_delete")
                ));
            }
        }
        return new TableStructure(columns, Set.copyOf(uniqueColumnSets), foreignKeys);
    }

    private List<String> indexColumns(Connection connection, String indexName) throws SQLException {
        List<String> columns = new ArrayList<>();
        String escapedIndexName = indexName.replace("'", "''");
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA index_info('" + escapedIndexName + "')")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return List.copyOf(columns);
    }

    private String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    private byte[] digestTable(Connection connection, String table) throws SQLException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
            int columnCount = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                for (int column = 1; column <= columnCount; column++) {
                    byte[] value = resultSet.getBytes(column);
                    if (resultSet.wasNull()) {
                        digest.update((byte) 0);
                    } else {
                        digest.update((byte) 1);
                        digest.update(Integer.toString(value.length).getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) ':');
                        digest.update(value);
                    }
                }
            }
        }
        return digest.digest();
    }

    private String fileSha256(String relativePath) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of(relativePath))));
    }

    private int rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private int pragmaInt(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA " + pragma)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private record LegacySnapshot(LegacyStructure structure, byte[] usersDigest, byte[] logsDigest) {
        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) {
                return true;
            }
            if (!(candidate instanceof LegacySnapshot other)) {
                return false;
            }
            return structure.equals(other.structure)
                    && MessageDigest.isEqual(usersDigest, other.usersDigest)
                    && MessageDigest.isEqual(logsDigest, other.logsDigest);
        }

        @Override
        public int hashCode() {
            int result = structure.hashCode();
            result = 31 * result + Arrays.hashCode(usersDigest);
            result = 31 * result + Arrays.hashCode(logsDigest);
            return result;
        }
    }

    private record LegacyStructure(TableStructure users, TableStructure generationLogs) {
    }

    private record TableStructure(
            List<ColumnDefinition> columns,
            Set<List<String>> uniqueColumnSets,
            List<ForeignKeyDefinition> foreignKeys) {
    }

    private record ColumnDefinition(
            int ordinal,
            String name,
            String type,
            boolean notNull,
            String defaultValue,
            int primaryKeyPosition) {
    }

    private record ForeignKeyDefinition(
            String parentTable,
            String childColumn,
            String parentColumn,
            String onUpdate,
            String onDelete) {
    }

    private record ForeignKeyPolicy(
            String fromColumn,
            String targetTable,
            String targetColumn,
            String onDelete) {
    }
}
