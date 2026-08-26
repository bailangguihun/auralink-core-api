package com.auralink.ops.round51;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import com.auralink.catalog.CatalogImportResult;
import com.auralink.catalog.CatalogImportStatus;
import com.auralink.catalog.CatalogSourceSnapshot;

/** Read-only, exact-state validation used before and after every activation phase. */
final class Round51DatabaseVerifier {

    static final Set<String> FOUNDATION_TABLES = Set.of(
            "media_assets",
            "paintings",
            "catalog_import_runs",
            "painting_guides",
            "painting_favorites",
            "user_workflows",
            "creations",
            "creation_steps",
            "creation_favorites");

    private static final Set<String> LEGACY_TABLES = Set.of("users", "generation_logs");
    private static final List<LegacyColumn> EXPECTED_USER_COLUMNS = List.of(
            column("id", "INTEGER", false, 1),
            column("account_non_expired", "BOOLEAN", true, 0),
            column("account_non_locked", "BOOLEAN", true, 0),
            column("created_at", "TIMESTAMP", false, 0),
            column("credentials_non_expired", "BOOLEAN", true, 0),
            column("email", "VARCHAR(255)", true, 0),
            column("enabled", "BOOLEAN", true, 0),
            column("full_name", "VARCHAR(255)", true, 0),
            column("password", "VARCHAR(255)", true, 0),
            column("role", "VARCHAR(255)", true, 0),
            column("updated_at", "TIMESTAMP", false, 0),
            column("username", "VARCHAR(255)", true, 0));
    private static final List<LegacyColumn> EXPECTED_GENERATION_LOG_COLUMNS = List.of(
            column("id", "INTEGER", false, 1),
            column("api_provider", "VARCHAR(255)", false, 0),
            column("api_source", "VARCHAR(255)", true, 0),
            column("created_at", "TIMESTAMP", true, 0),
            column("description", "VARCHAR(1024)", false, 0),
            column("duration", "INTEGER", false, 0),
            column("error_message", "VARCHAR(1024)", false, 0),
            column("image_url", "VARCHAR(1024)", false, 0),
            column("input_data", "TEXT", false, 0),
            column("metadata", "TEXT", false, 0),
            column("model_size", "VARCHAR(255)", true, 0),
            column("output_data", "TEXT", false, 0),
            column("processing_time_ms", "BIGINT", false, 0),
            column("result_url", "VARCHAR(1024)", false, 0),
            column("success", "BOOLEAN", true, 0),
            column("task_type", "VARCHAR(255)", true, 0),
            column("use_fast_generate", "BOOLEAN", true, 0),
            column("user_id", "BIGINT", true, 0));
    private static final Set<String> ACTIVATED_TABLES;

    static {
        Set<String> activated = new LinkedHashSet<>(LEGACY_TABLES);
        activated.add("flyway_schema_history");
        activated.addAll(FOUNDATION_TABLES);
        ACTIVATED_TABLES = Set.copyOf(activated);
    }

    private final JdbcTemplate jdbc;

    Round51DatabaseVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Round51ActivationState classify(CatalogSourceSnapshot snapshot, Expectations expected) {
        Set<String> tables = applicationTables();
        if (!tables.containsAll(LEGACY_TABLES)) {
            return Round51ActivationState.PARTIALLY_ACTIVATED_UNKNOWN;
        }
        verifyLegacy(expected);

        boolean hasHistory = tables.contains("flyway_schema_history");
        Set<String> presentFoundation = new LinkedHashSet<>(tables);
        presentFoundation.retainAll(FOUNDATION_TABLES);
        if (!hasHistory && presentFoundation.isEmpty() && tables.equals(LEGACY_TABLES)) {
            return Round51ActivationState.INHERITED_READY;
        }
        if (hasHistory && tables.equals(ACTIVATED_TABLES) && presentFoundation.equals(FOUNDATION_TABLES)
                && historyIsActivated()
                && catalogIsHealthy(snapshot, expected)) {
            return Round51ActivationState.ALREADY_ACTIVATED_HEALTHY;
        }
        return Round51ActivationState.PARTIALLY_ACTIVATED_UNKNOWN;
    }

    void verifySnapshot(CatalogSourceSnapshot snapshot, Expectations expected) {
        requireEquals("CATALOG_ROW_COUNT_MISMATCH", expected.paintings(), snapshot.totalRows());
        requireEquals("CATALOG_MATCH_COUNT_MISMATCH", expected.catalogMediaAssets(), snapshot.matchedImages());
        requireEquals("CATALOG_MISSING_COUNT_MISMATCH", expected.missingImages(), snapshot.missingImages());
        requireEquals("CATALOG_ORPHAN_COUNT_MISMATCH", expected.orphanImages(), snapshot.orphanImages());
        if (expected.catalogFingerprint() != null) {
            require(expected.catalogFingerprint().equals(snapshot.fingerprint()),
                    "CATALOG_REVIEWED_FINGERPRINT_MISMATCH");
        }
    }

    void verifyLegacy(Expectations expected) {
        requireEquals("LEGACY_USER_COUNT_MISMATCH", expected.users(), count("users"));
        requireEquals("LEGACY_LOG_COUNT_MISMATCH", expected.generationLogs(), count("generation_logs"));
        verifyLegacyStructure();
        verifyIntegrityAndForeignKeys(false);
    }

    private void verifyLegacyStructure() {
        require(EXPECTED_USER_COLUMNS.equals(legacyColumns("users")), "LEGACY_USERS_SCHEMA_MISMATCH");
        require(EXPECTED_GENERATION_LOG_COLUMNS.equals(legacyColumns("generation_logs")),
                "LEGACY_GENERATION_LOGS_SCHEMA_MISMATCH");
        require(uniqueColumnSets("users").equals(Set.of(List.of("email"), List.of("username"))),
                "LEGACY_USERS_UNIQUE_CONSTRAINT_MISMATCH");
        require(uniqueColumnSets("generation_logs").isEmpty(),
                "LEGACY_GENERATION_LOGS_UNIQUE_CONSTRAINT_MISMATCH");
        require(jdbc.queryForList("PRAGMA foreign_key_list('users')").isEmpty(),
                "LEGACY_USERS_FOREIGN_KEY_MISMATCH");
        require(jdbc.queryForList("PRAGMA foreign_key_list('generation_logs')").isEmpty(),
                "LEGACY_GENERATION_LOGS_FOREIGN_KEY_MISMATCH");
    }

    private List<LegacyColumn> legacyColumns(String table) {
        return jdbc.query("PRAGMA table_info('" + table + "')", (resultSet, rowNumber) -> new LegacyColumn(
                resultSet.getString("name"),
                resultSet.getString("type").trim().toUpperCase(Locale.ROOT),
                resultSet.getBoolean("notnull"),
                resultSet.getString("dflt_value"),
                resultSet.getInt("pk")));
    }

    private Set<List<String>> uniqueColumnSets(String table) {
        Set<List<String>> result = new LinkedHashSet<>();
        for (Map<String, Object> index : jdbc.queryForList("PRAGMA index_list('" + table + "')")) {
            Object unique = index.get("unique");
            if (!(unique instanceof Number number) || number.intValue() != 1) {
                continue;
            }
            String indexName = String.valueOf(index.get("name")).replace("'", "''");
            result.add(jdbc.query(
                    "PRAGMA index_info('" + indexName + "')",
                    (resultSet, rowNumber) -> resultSet.getString("name")));
        }
        return Set.copyOf(result);
    }

    private static LegacyColumn column(String name, String type, boolean notNull, int primaryKey) {
        return new LegacyColumn(name, type, notNull, null, primaryKey);
    }

    void verifyBaseline() {
        require(historyRowCount("1", "BASELINE") == 1, "FLYWAY_BASELINE_INVALID");
        require(historySuccess("1", "BASELINE") == 1, "FLYWAY_BASELINE_INVALID");
    }

    void verifyMigratedEmpty(Expectations expected) {
        verifyLegacy(expected);
        require(applicationTables().containsAll(FOUNDATION_TABLES), "FOUNDATION_TABLES_MISSING");
        require(historyRowCount("2", "SQL") == 1, "FLYWAY_V2_INVALID");
        require(historySuccess("2", "SQL") == 1, "FLYWAY_V2_INVALID");
        for (String table : FOUNDATION_TABLES) {
            requireEquals("FOUNDATION_TABLE_NOT_EMPTY", 0, count(table));
        }
        verifyIntegrityAndForeignKeys(true);
    }

    void verifyFirstImport(
            CatalogImportResult result,
            CatalogSourceSnapshot snapshot,
            Expectations expected) {
        require(CatalogImportStatus.SUCCESS.equals(result.status()), "CATALOG_FIRST_IMPORT_NOT_SUCCESS");
        require(snapshot.fingerprint().equals(result.sourceFingerprint()), "CATALOG_FINGERPRINT_MISMATCH");
        requireEquals("CATALOG_AUDIT_TOTAL_MISMATCH", expected.paintings(), result.totalRows());
        requireEquals("CATALOG_AUDIT_INSERT_MISMATCH", expected.paintings(), result.insertedRows());
        requireEquals("CATALOG_AUDIT_UPDATE_MISMATCH", 0, result.updatedRows());
        requireEquals("CATALOG_AUDIT_UNCHANGED_MISMATCH", 0, result.unchangedRows());
        requireEquals("CATALOG_AUDIT_MATCH_MISMATCH", expected.catalogMediaAssets(), result.matchedImages());
        requireEquals("CATALOG_AUDIT_MISSING_MISMATCH", expected.missingImages(), result.missingImages());
        requireEquals("CATALOG_AUDIT_ORPHAN_MISMATCH", expected.orphanImages(), result.orphanImages());
        verifyCatalog(snapshot, expected, CatalogImportStatus.SUCCESS);
    }

    void verifySecondImport(
            CatalogImportResult result,
            CatalogSourceSnapshot snapshot,
            Expectations expected,
            String identityDigestBefore) {
        require(CatalogImportStatus.SKIPPED.equals(result.status()), "CATALOG_REIMPORT_NOT_SKIPPED");
        require(snapshot.fingerprint().equals(result.sourceFingerprint()), "CATALOG_REIMPORT_FINGERPRINT_MISMATCH");
        requireEquals("CATALOG_REIMPORT_TOTAL_MISMATCH", expected.paintings(), result.totalRows());
        requireEquals("CATALOG_REIMPORT_CHANGED_ROWS", expected.paintings(), result.unchangedRows());
        verifyCatalog(snapshot, expected, CatalogImportStatus.SKIPPED);
        require(identityDigestBefore.equals(identityDigest()), "CATALOG_PUBLIC_IDS_CHANGED");
    }

    String identityDigest() {
        MessageDigest digest = sha256();
        updateRows(digest, "SELECT source_key, public_id FROM paintings ORDER BY source_key");
        updateRows(digest, "SELECT storage_key, public_id FROM media_assets "
                + "WHERE source_type = 'CATALOG_REFERENCE' ORDER BY storage_key");
        return HexFormat.of().formatHex(digest.digest());
    }

    String legacyDigest() {
        MessageDigest digest = sha256();
        updateRows(digest, "SELECT type, name, tbl_name, COALESCE(sql, '') FROM sqlite_master "
                + "WHERE (tbl_name IN ('users', 'generation_logs') OR name IN ('users', 'generation_logs')) "
                + "ORDER BY type, name");
        updateRows(digest, "SELECT * FROM users ORDER BY id");
        updateRows(digest, "SELECT * FROM generation_logs ORDER BY id");
        return HexFormat.of().formatHex(digest.digest());
    }

    void verifyLegacyDigest(String expectedDigest) {
        require(expectedDigest.equals(legacyDigest()), "LEGACY_ROWS_OR_SCHEMA_CHANGED");
    }

    void verifyIntegrityAndForeignKeys(boolean requireForeignKeysClean) {
        List<String> integrity = jdbc.queryForList("PRAGMA integrity_check", String.class);
        require(integrity.size() == 1 && "ok".equalsIgnoreCase(integrity.get(0)), "SQLITE_INTEGRITY_FAILED");
        if (requireForeignKeysClean) {
            List<?> violations = jdbc.queryForList("PRAGMA foreign_key_check");
            require(violations.isEmpty(), "SQLITE_FOREIGN_KEY_CHECK_FAILED");
        }
    }

    private boolean catalogIsHealthy(CatalogSourceSnapshot snapshot, Expectations expected) {
        try {
            verifyCatalog(snapshot, expected, CatalogImportStatus.SKIPPED);
            return true;
        } catch (Round51ActivationException exception) {
            return false;
        }
    }

    private void verifyCatalog(
            CatalogSourceSnapshot snapshot,
            Expectations expected,
            String expectedLatestStatus) {
        verifyLegacy(expected);
        requireEquals("PAINTING_COUNT_MISMATCH", expected.paintings(), count("paintings"));
        requireEquals("CATALOG_MEDIA_COUNT_MISMATCH", expected.catalogMediaAssets(), scalarInt(
                "SELECT COUNT(*) FROM media_assets WHERE source_type = 'CATALOG_REFERENCE'"));
        requireEquals("IMAGE_AVAILABLE_COUNT_MISMATCH", expected.catalogMediaAssets(), scalarInt(
                "SELECT COUNT(*) FROM paintings WHERE image_available = 1"));
        requireEquals("GALLERY_VISIBLE_COUNT_MISMATCH", expected.visibleInGallery(), scalarInt(
                "SELECT COUNT(*) FROM paintings WHERE visible_in_gallery = 1"));
        requireEquals("MISSING_IMAGE_COUNT_MISMATCH", expected.missingImages(), scalarInt(
                "SELECT COUNT(*) FROM paintings WHERE image_available = 0"));
        requireEquals("GENERATED_TEXT_COUNT_MISMATCH", expected.generatedTextPopulated(), scalarInt(
                "SELECT COUNT(*) FROM paintings WHERE NULLIF(TRIM(generated_text), '') IS NOT NULL"));
        requireEquals("MUSIC_SCENE_COUNT_MISMATCH", expected.musicScenePopulated(), scalarInt(
                "SELECT COUNT(*) FROM paintings WHERE NULLIF(TRIM(music_scene_description), '') IS NOT NULL"));
        requireEquals("PAINTING_SOURCE_KEY_DUPLICATE", expected.paintings(), scalarInt(
                "SELECT COUNT(DISTINCT source_key) FROM paintings"));
        requireEquals("PAINTING_PUBLIC_ID_DUPLICATE", expected.paintings(), scalarInt(
                "SELECT COUNT(DISTINCT public_id) FROM paintings"));
        requireEquals("CATALOG_MEDIA_PUBLIC_ID_DUPLICATE", expected.catalogMediaAssets(), scalarInt(
                "SELECT COUNT(DISTINCT public_id) FROM media_assets WHERE source_type = 'CATALOG_REFERENCE'"));
        requireEquals("CATALOG_MEDIA_POLICY_INVALID", 0, scalarInt(
                "SELECT COUNT(*) FROM media_assets WHERE source_type = 'CATALOG_REFERENCE' "
                        + "AND (owner_user_id IS NOT NULL OR asset_type <> 'IMAGE' "
                        + "OR semantic_type <> 'PAINTING' OR visibility <> 'PUBLIC' "
                        + "OR status <> 'ACTIVE' OR storage_key NOT LIKE 'catalog/%')"));
        requireEquals("PAINTING_POLICY_INVALID", 0, scalarInt(
                "SELECT COUNT(*) FROM paintings WHERE status <> 'ACTIVE' "
                        + "OR (image_available = 1 AND (image_asset_id IS NULL OR visible_in_gallery <> 1)) "
                        + "OR (image_available = 0 AND (image_asset_id IS NOT NULL OR visible_in_gallery <> 0))"));
        requireEquals("CATALOG_LINK_COUNT_MISMATCH", expected.catalogMediaAssets(), scalarInt(
                "SELECT COUNT(DISTINCT image_asset_id) FROM paintings WHERE image_asset_id IS NOT NULL"));
        requireEquals("CATALOG_LINK_POLICY_INVALID", 0, scalarInt(
                "SELECT COUNT(*) FROM paintings p JOIN media_assets m ON m.id = p.image_asset_id "
                        + "WHERE m.source_type <> 'CATALOG_REFERENCE' OR m.owner_user_id IS NOT NULL "
                        + "OR m.asset_type <> 'IMAGE' OR m.semantic_type <> 'PAINTING' "
                        + "OR m.visibility <> 'PUBLIC' OR m.status <> 'ACTIVE'"));
        requireEquals("UNLINKED_CATALOG_MEDIA_ASSET", 0, scalarInt(
                "SELECT COUNT(*) FROM media_assets m WHERE m.source_type = 'CATALOG_REFERENCE' "
                        + "AND NOT EXISTS (SELECT 1 FROM paintings p WHERE p.image_asset_id = m.id)"));
        requireEquals("CATALOG_AUDIT_SUCCESS_MISSING", 1, scalarInt(
                "SELECT CASE WHEN EXISTS (SELECT 1 FROM catalog_import_runs "
                        + "WHERE status = 'SUCCESS' AND source_sha256 = ? "
                        + "AND total_rows = ? AND inserted_rows = ? AND updated_rows = 0 "
                        + "AND unchanged_rows = 0 AND matched_images = ? AND missing_images = ? "
                        + "AND orphan_images = ? AND finished_at IS NOT NULL) THEN 1 ELSE 0 END",
                snapshot.fingerprint(),
                expected.paintings(),
                expected.paintings(),
                expected.catalogMediaAssets(),
                expected.missingImages(),
                expected.orphanImages()));
        String latestStatus = jdbc.queryForObject(
                "SELECT status FROM catalog_import_runs ORDER BY id DESC LIMIT 1", String.class);
        require(expectedLatestStatus.equals(latestStatus), "CATALOG_AUDIT_LATEST_STATUS_INVALID");
        if (CatalogImportStatus.SKIPPED.equals(expectedLatestStatus)) {
            requireEquals("CATALOG_SKIP_AUDIT_INVALID", 1, scalarInt(
                    "SELECT CASE WHEN EXISTS (SELECT 1 FROM catalog_import_runs "
                            + "WHERE id = (SELECT MAX(id) FROM catalog_import_runs) AND status = 'SKIPPED' "
                            + "AND source_sha256 = ? AND total_rows = ? AND inserted_rows = 0 "
                            + "AND updated_rows = 0 AND unchanged_rows = ? AND matched_images = ? "
                            + "AND missing_images = ? AND orphan_images = ? AND finished_at IS NOT NULL) "
                            + "THEN 1 ELSE 0 END",
                    snapshot.fingerprint(),
                    expected.paintings(),
                    expected.paintings(),
                    expected.catalogMediaAssets(),
                    expected.missingImages(),
                    expected.orphanImages()));
        }
        validateCanonicalUuids("SELECT public_id FROM paintings");
        validateCanonicalUuids("SELECT public_id FROM media_assets WHERE source_type = 'CATALOG_REFERENCE'");
        verifyIntegrityAndForeignKeys(true);
    }

    private boolean historyIsActivated() {
        return historySuccess("1", "BASELINE") == 1
                && historySuccess("2", "SQL") == 1
                && scalarInt("SELECT COUNT(*) FROM flyway_schema_history") == 2
                && scalarInt("SELECT COUNT(*) FROM flyway_schema_history WHERE success <> 1") == 0;
    }

    private int historyRowCount(String version, String type) {
        return scalarInt("SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND type = ?", version, type);
    }

    private int historySuccess(String version, String type) {
        return scalarInt(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND type = ? AND success = 1",
                version,
                type);
    }

    private Set<String> applicationTables() {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' "
                        + "AND name NOT LIKE 'sqlite_%' ORDER BY name",
                String.class));
    }

    private int count(String table) {
        if (!LEGACY_TABLES.contains(table) && !FOUNDATION_TABLES.contains(table)) {
            throw new IllegalArgumentException("Unexpected activation table");
        }
        return scalarInt("SELECT COUNT(*) FROM " + table);
    }

    private int scalarInt(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        if (value == null) {
            throw failure("DATABASE_VERIFICATION_FAILED");
        }
        return value;
    }

    private void validateCanonicalUuids(String sql) {
        List<String> publicIds = jdbc.queryForList(sql, String.class);
        for (String publicId : publicIds) {
            try {
                require(UUID.fromString(publicId).toString().equals(publicId), "INVALID_PUBLIC_UUID");
            } catch (IllegalArgumentException exception) {
                throw failure("INVALID_PUBLIC_UUID");
            }
        }
    }

    private void updateRows(MessageDigest digest, String sql) {
        jdbc.query(sql, (RowCallbackHandler) resultSet -> updateDigest(digest, resultSet));
    }

    private void updateDigest(MessageDigest digest, ResultSet resultSet) throws SQLException {
        for (int index = 1; index <= resultSet.getMetaData().getColumnCount(); index++) {
            String value = resultSet.getString(index);
            if (value == null) {
                digest.update((byte) 0xff);
            } else {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) 0);
        }
        digest.update((byte) '\n');
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireEquals(String code, int expected, int actual) {
        require(expected == actual, code);
    }

    private void require(boolean condition, String code) {
        if (!condition) {
            throw failure(code);
        }
    }

    private Round51ActivationException failure(String code) {
        return new Round51ActivationException(code, "Activation database verification failed");
    }

    record Expectations(
            int users,
            int generationLogs,
            int paintings,
            int catalogMediaAssets,
            int missingImages,
            int orphanImages,
            int generatedTextPopulated,
            int musicScenePopulated,
            int visibleInGallery,
            String catalogFingerprint) {

        static Expectations production() {
            return new Expectations(
                    7,
                    118,
                    11_067,
                    9_067,
                    2_000,
                    2,
                    8_915,
                    9_068,
                    9_067,
                    "a9cf4b05e374ecaa975c51c59eda6e2a6b1adf1e02badcb69994189c7554aff6");
        }
    }

    private record LegacyColumn(String name, String type, boolean notNull, String defaultValue, int primaryKey) {
    }
}
