package com.auralink.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderNoPersistenceIntegrationTest {

    private static final List<String> PROTECTED_TABLES = List.of(
            "generation_logs",
            "media_assets",
            "paintings",
            "painting_guides",
            "painting_favorites",
            "user_workflows",
            "creations",
            "creation_steps",
            "creation_favorites");

    @TempDir
    Path temporaryDirectory;

    @Test
    void directFiveFlowContractChangesNoFlywayMigratedTable() throws Exception {
        Path database = temporaryDirectory.resolve("provider-contract.db");
        String jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .load()
                .migrate();

        Map<String, String> before = tableDigests(jdbcUrl);
        PackagedProviderContractHarness.run(temporaryDirectory.resolve("contract"));
        Map<String, String> after = tableDigests(jdbcUrl);

        assertThat(after).containsExactlyEntriesOf(before);
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
                Statement statement = connection.createStatement()) {
            try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
                assertThat(integrity.next()).isTrue();
                assertThat(integrity.getString(1)).isEqualTo("ok");
            }
            try (ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(violations.next()).isFalse();
            }
        }
    }

    private Map<String, String> tableDigests(String jdbcUrl) throws Exception {
        LinkedHashMap<String, String> digests = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            for (String table : PROTECTED_TABLES) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY rowid")) {
                    ResultSetMetaData metadata = rows.getMetaData();
                    while (rows.next()) {
                        for (int column = 1; column <= metadata.getColumnCount(); column++) {
                            Object value = rows.getObject(column);
                            digest.update((metadata.getColumnName(column) + "=" + String.valueOf(value) + "\n")
                                    .getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
                digests.put(table, HexFormat.of().formatHex(digest.digest()));
            }
        }
        return Map.copyOf(digests);
    }
}
