package com.auralink.ops.round51;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import com.auralink.catalog.CatalogSourceSnapshotFactory;
import com.auralink.catalog.CatalogSourceSnapshot;
import com.auralink.catalog.PaintingCatalogImporter;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.ops.round51.Round51DatabaseVerifier.Expectations;

/** Actual Flyway + importer activation against one isolated inherited SQLite fixture. */
@SpringBootTest(properties = {
        "spring.config.import=optional:file:/tmp/auralink-round51-no-env.properties",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON",
        "auralink.jwt.secret=round51-test-only-safe-placeholder",
        "auralink.paintings.import-enabled=false",
        "auralink.paintings.import-batch-size=2"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Round51ActivationCoordinatorIntegrationTest {

    private static final Path ROOT = Path.of("/tmp", "auralink-round51-java-" + UUID.randomUUID());
    private static final Path DATABASE = ROOT.resolve("inherited.db");
    private static final Path CSV = ROOT.resolve("paintings.csv");
    private static final Path PICTURES = ROOT.resolve("pictures");
    private static final Path MANAGED = ROOT.resolve("managed");

    @DynamicPropertySource
    static void isolatedRuntime(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("auralink.paintings.metadata-csv-path", CSV::toString);
        registry.add("auralink.paintings.picture-dir", PICTURES::toString);
        registry.add("auralink.media-assets.managed-dir", MANAGED::toString);
        registry.add("auralink.storage.upload-dir", () -> ROOT.resolve("uploads").toString());
        registry.add("auralink.storage.audio-dir", () -> ROOT.resolve("audio").toString());
        registry.add("auralink.storage.legacy-frontend-audio-dir", () -> ROOT.resolve("legacy-audio").toString());
    }

    @BeforeAll
    static void prepareInheritedDatabaseAndCatalog() throws Exception {
        Files.createDirectories(PICTURES);
        createInheritedDatabase();
        writeJpeg(PICTURES.resolve("matched.jpg"));
        writeJpeg(PICTURES.resolve("orphan.jpg"));
        writeCatalog();
    }

    @Autowired private DataSource dataSource;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private PaintingCatalogImporter importer;
    @Autowired private CatalogSourceSnapshotFactory snapshotFactory;
    @Autowired private PaintingProperties paintingProperties;

    @Test
    @Order(0)
    void normalApplicationRemainsServletBasedAndDoesNotDiscoverActivationOnlyTypes() {
        assertThat(applicationContext).isInstanceOf(WebApplicationContext.class);
        assertThat(applicationContext.getBeansWithAnnotation(Controller.class)).isNotEmpty();
        assertThat(applicationContext.getBeansWithAnnotation(RestController.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(SecurityFilterChain.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(Round51ActivationCoordinator.class)).isEmpty();
        assertThat(applicationContext.containsBean(
                Round51ActivationContextConfiguration.class.getName())).isFalse();
    }

    @Test
    @Order(1)
    void explicitlyActivatesInheritedDatabaseAndPreservesLegacyRows() {
        Round51ActivationResult result = coordinator().activate();

        assertThat(result.state()).isEqualTo(Round51ActivationState.ACTIVATED_NOW);
        assertThat(result.paintings()).isEqualTo(2);
        assertThat(result.catalogMediaAssets()).isEqualTo(1);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
             Statement statement = connection.createStatement()) {
            assertThat(scalar(statement, "SELECT COUNT(*) FROM users")).isEqualTo(2);
            assertThat(scalar(statement, "SELECT COUNT(*) FROM generation_logs")).isEqualTo(3);
            assertThat(scalar(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND type='BASELINE' AND success=1"))
                    .isEqualTo(1);
            assertThat(scalar(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND type='SQL' AND success=1"))
                    .isEqualTo(1);
            assertThat(scalar(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='3'"))
                    .isZero();
            assertThat(scalar(statement, "SELECT COUNT(*) FROM flyway_schema_history")).isEqualTo(2);
            assertThat(scalar(statement, "SELECT COUNT(*) FROM paintings")).isEqualTo(2);
            assertThat(scalar(statement,
                    "SELECT COUNT(*) FROM catalog_import_runs WHERE status='SUCCESS'"))
                    .isEqualTo(1);
            assertThat(scalar(statement,
                    "SELECT COUNT(*) FROM catalog_import_runs WHERE status='SKIPPED'"))
                    .isEqualTo(1);
            assertThat(queryString(statement, "PRAGMA integrity_check")).isEqualTo("ok");
            assertThat(statement.executeQuery("PRAGMA foreign_key_check").next()).isFalse();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    @Order(2)
    void rerunDetectsAlreadyActivatedHealthyStateWithoutAddingRows() throws Exception {
        Round51ActivationResult result = coordinator().activate();

        assertThat(result.state()).isEqualTo(Round51ActivationState.ALREADY_ACTIVATED_HEALTHY);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
             Statement statement = connection.createStatement()) {
            assertThat(scalar(statement, "SELECT COUNT(*) FROM paintings")).isEqualTo(2);
            assertThat(scalar(statement, "SELECT COUNT(*) FROM media_assets")).isEqualTo(1);
            assertThat(scalar(statement, "SELECT COUNT(*) FROM catalog_import_runs")).isEqualTo(2);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        Path pythonHealthy = ROOT.resolve("python-healthy.db");
        Files.copy(DATABASE, pythonHealthy);
        expandLegacyFixtureToProductionCounts(pythonHealthy);
        HelperResult helper = verifyWithPythonHelper(pythonHealthy);
        assertThat(helper.exitCode()).isZero();
        assertThat(helper.output()).contains("\"state\": \"ACTIVATED_CANDIDATE\"");
    }

    @Test
    @Order(3)
    void pythonPreflightRejectsSuccessfulImportWithoutRequiredSkippedReimport() throws Exception {
        Path incomplete = ROOT.resolve("successful-only.db");
        Files.copy(DATABASE, incomplete);
        expandLegacyFixtureToProductionCounts(incomplete);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + incomplete);
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM catalog_import_runs WHERE status='SKIPPED'");
        }

        HelperResult helper = verifyWithPythonHelper(incomplete);

        assertThat(helper.exitCode()).isNotZero();
        assertThat(helper.output()).contains("required unchanged SKIPPED run");
    }

    @Test
    @Order(4)
    void refusesUnexpectedPartialOrUnknownSchemaWithoutMutatingIt() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unexpected_partial_state (id INTEGER PRIMARY KEY)");
        }

        assertThatThrownBy(() -> coordinator().activate())
                .isInstanceOf(Round51ActivationException.class)
                .satisfies(exception -> assertThat(((Round51ActivationException) exception).getCode())
                        .isEqualTo("PARTIALLY_ACTIVATED_STATE_REFUSED"));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
             Statement statement = connection.createStatement()) {
            assertThat(scalar(statement, "SELECT COUNT(*) FROM paintings")).isEqualTo(2);
            assertThat(scalar(statement, "SELECT COUNT(*) FROM unexpected_partial_state")).isZero();
            statement.execute("DROP TABLE unexpected_partial_state");
        }
    }

    private Round51ActivationCoordinator coordinator() {
        String reviewedFingerprint = snapshotFactory.create(CSV, PICTURES).fingerprint();
        return new Round51ActivationCoordinator(
                dataSource,
                importer,
                snapshotFactory,
                paintingProperties,
                new Expectations(2, 3, 2, 1, 1, 1, 1, 1, 1, reviewedFingerprint),
                checkpoint -> { });
    }

    private HelperResult verifyWithPythonHelper(Path database) {
        CatalogSourceSnapshot snapshot = snapshotFactory.create(CSV, PICTURES);
        ProcessBuilder builder = new ProcessBuilder(
                "python3",
                Path.of(System.getProperty("user.dir"), "scripts", "round51_state.py").toString(),
                "verify-preflight",
                "--database", database.toString(),
                "--csv", CSV.toString(),
                "--pictures", PICTURES.toString(),
                "--expected-legacy-data-sha256", legacyDataHashWithPython(database),
                "--expected-catalog-fingerprint", snapshot.fingerprint(),
                "--expected-paintings", "2",
                "--expected-image-files", "2",
                "--expected-catalog-assets", "1",
                "--expected-missing-images", "1",
                "--expected-orphan-images", "1",
                "--expected-generated-text", "1",
                "--expected-music-scene", "1",
                "--expected-gallery-visible", "1")
                .redirectErrorStream(true);
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        try {
            Process process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Python activation verifier timed out");
            }
            return new HelperResult(
                    process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private String legacyDataHashWithPython(Path database) {
        ProcessBuilder builder = new ProcessBuilder(
                "python3",
                Path.of(System.getProperty("user.dir"), "scripts", "round51_state.py").toString(),
                "inspect",
                "--database", database.toString())
                .redirectErrorStream(true);
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        try {
            Process process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Python database inspection timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AssertionError("Python database inspection failed: " + output);
            }
            Matcher matcher = Pattern.compile(
                    "\\\"legacyDataSha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"")
                    .matcher(output);
            if (!matcher.find()) {
                throw new AssertionError("Python database inspection omitted the legacy data digest");
            }
            return matcher.group(1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void expandLegacyFixtureToProductionCounts(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);
            try (PreparedStatement users = connection.prepareStatement("""
                    INSERT INTO users (
                        id, account_non_expired, account_non_locked, created_at,
                        credentials_non_expired, email, enabled, full_name, password,
                        role, updated_at, username
                    ) VALUES (?, 1, 1, '2026-01-04 00:00:00', 1, ?, 1, ?,
                              'safe-test-hash', 'ROLE_USER', '2026-01-04 00:00:00', ?)
                    """)) {
                for (long id = 3; id <= 7; id++) {
                    users.setLong(1, id);
                    users.setString(2, "round51-" + id + "@example.invalid");
                    users.setString(3, "Round 5.1 User " + id);
                    users.setString(4, "round51-user-" + id);
                    users.addBatch();
                }
                users.executeBatch();
            }
            try (PreparedStatement logs = connection.prepareStatement("""
                    INSERT INTO generation_logs (
                        id, api_provider, api_source, created_at, description, duration,
                        error_message, image_url, input_data, metadata, model_size,
                        output_data, processing_time_ms, result_url, success, task_type,
                        use_fast_generate, user_id
                    ) VALUES (?, 'fixture', 'LOCAL', '2026-01-04 01:00:00',
                              'production-count fixture', 1, NULL, NULL, NULL, NULL,
                              'test', NULL, 1, NULL, 1, 'TEXT', 0, 1)
                    """)) {
                for (long id = 4; id <= 118; id++) {
                    logs.setLong(1, id);
                    logs.addBatch();
                }
                logs.executeBatch();
            }
            connection.commit();
        }
    }

    private static void createInheritedDatabase() throws Exception {
        String script;
        try (var input = Round51ActivationCoordinatorIntegrationTest.class
                .getResourceAsStream("/db/legacy/inherited_schema_fixture.sql")) {
            if (input == null) {
                throw new IllegalStateException("Missing inherited schema fixture");
            }
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
             Statement statement = connection.createStatement()) {
            for (String command : script.split(";")) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        }
    }

    private static void writeCatalog() throws IOException {
        String header = String.join(",", List.of(
                "序号", "图像存储名称", "画作名称", "作者姓名", "作者出生年份", "作者出生地", "作者流派",
                "创作年代", "创作朝代", "实际尺寸", "收藏机构", "分类", "题材", "画作流派", "风格", "色彩",
                "构图", "意境", "笔法", "墨法", "绘画材料", "颜料", "印章", "文化符号", "文本生成",
                "音乐情境生成", "收集平台"));
        String matched = String.join(",", List.of(
                "1", "matched", "匹配画作", "作者", "", "", "", "", "清", "", "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "官方文本", "官方音乐", ""));
        String missing = String.join(",", List.of(
                "1", "missing", "缺图画作", "作者", "", "", "", "", "宋", "", "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "", "", ""));
        Files.writeString(CSV, header + "\n" + matched + "\n" + missing + "\n", StandardCharsets.UTF_8);
    }

    private static void writeJpeg(Path path) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        if (!ImageIO.write(image, "jpg", path.toFile())) {
            throw new IOException("JPEG writer unavailable");
        }
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (var result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private static String queryString(Statement statement, String sql) throws Exception {
        try (var result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private record HelperResult(int exitCode, String output) {
    }
}
