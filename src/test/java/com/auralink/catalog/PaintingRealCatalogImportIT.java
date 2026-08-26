package com.auralink.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.auralink.api.v1.painting.PaintingDetailResponse;
import com.auralink.api.v1.painting.PaintingPageResponse;
import com.auralink.entity.Painting;
import com.auralink.entity.User;
import com.auralink.repository.PaintingRepository;
import com.auralink.repository.UserRepository;
import com.auralink.service.painting.PaintingQueryService;

/**
 * Explicit, server-local validation of the inherited real catalog.
 *
 * <p>The class name intentionally ends in {@code IT}, so normal Surefire test
 * discovery excludes it. It is additionally disabled unless the caller opts in
 * and supplies both source paths as JVM system properties:</p>
 *
 * <pre>
 * -Dauralink.realCatalog.enabled=true
 * -Dauralink.realCatalog.csv=/server-local/path/to/paintings.csv
 * -Dauralink.realCatalog.pictures=/server-local/path/to/picture
 * </pre>
 *
 * <p>Only a random database and managed-asset directory under {@code /tmp} are
 * writable. The source CSV and picture corpus are read through the importer and
 * never copied into managed storage.</p>
 */
@EnabledIfSystemProperty(named = "auralink.realCatalog.enabled", matches = "(?i)true")
@SpringBootTest(properties = {
        "spring.config.import=optional:file:/tmp/auralink-round5-real-catalog-no-env.properties",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON",
        "auralink.jwt.secret=round5-real-catalog-it-only-secret",
        "auralink.paintings.import-enabled=false",
        "auralink.paintings.import-batch-size=500"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaintingRealCatalogImportIT {

    private static final int EXPECTED_PAINTINGS = 11_067;
    private static final int EXPECTED_MATCHED_IMAGES = 9_067;
    private static final int EXPECTED_MISSING_IMAGES = 2_000;
    private static final int EXPECTED_ORPHAN_IMAGES = 2;

    private static final Path RUNTIME_ROOT = Path.of(
            "/tmp", "auralink-round5-real-catalog-" + UUID.randomUUID());
    private static final Path DATABASE = RUNTIME_ROOT.resolve("catalog.db");
    private static final Path MANAGED_ASSETS = RUNTIME_ROOT.resolve("managed-assets");

    @DynamicPropertySource
    static void isolatedRuntime(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(RUNTIME_ROOT);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create isolated real-catalog test runtime", exception);
        }

        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("auralink.media-assets.managed-dir", MANAGED_ASSETS::toString);
        registry.add("auralink.paintings.metadata-csv-path", () -> requiredProperty("auralink.realCatalog.csv"));
        registry.add("auralink.paintings.picture-dir", () -> requiredProperty("auralink.realCatalog.pictures"));
        registry.add("auralink.storage.upload-dir", () -> RUNTIME_ROOT.resolve("legacy-upload").toString());
        registry.add("auralink.storage.audio-dir", () -> RUNTIME_ROOT.resolve("legacy-audio").toString());
        registry.add(
                "auralink.storage.legacy-frontend-audio-dir",
                () -> RUNTIME_ROOT.resolve("legacy-frontend-audio").toString());
    }

    @Autowired
    private PaintingCatalogImporter importer;

    @Autowired
    private PaintingQueryService queryService;

    @Autowired
    private PaintingRepository paintingRepository;

    @Autowired
    private CatalogSourceSnapshotFactory snapshotFactory;

    @Autowired
    private DynastyNormalizer dynastyNormalizer;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void importsTheRealCatalogOnceThenSkipsTheUnchangedSnapshot() throws IOException {
        Path csv = Path.of(requiredProperty("auralink.realCatalog.csv")).toAbsolutePath().normalize();
        Path pictures = Path.of(requiredProperty("auralink.realCatalog.pictures")).toAbsolutePath().normalize();
        assertThat(csv).isRegularFile();
        assertThat(pictures).isDirectory();
        assertThat(DATABASE.toString()).startsWith("/tmp/");
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);

        long managedFilesBefore = regularFileCount(MANAGED_ASSETS);
        Instant firstStarted = Instant.now();
        CatalogImportResult first = importer.importCatalog();
        Duration firstDuration = Duration.between(firstStarted, Instant.now());

        assertThat(first.status()).isEqualTo(CatalogImportStatus.SUCCESS);
        assertThat(first.sourceFingerprint()).matches("[0-9a-f]{64}");
        assertThat(first.totalRows()).isEqualTo(EXPECTED_PAINTINGS);
        assertThat(first.insertedRows()).isEqualTo(EXPECTED_PAINTINGS);
        assertThat(first.updatedRows()).isZero();
        assertThat(first.unchangedRows()).isZero();
        assertThat(first.matchedImages()).isEqualTo(EXPECTED_MATCHED_IMAGES);
        assertThat(first.missingImages()).isEqualTo(EXPECTED_MISSING_IMAGES);
        assertThat(first.orphanImages()).isEqualTo(EXPECTED_ORPHAN_IMAGES);

        assertDatabaseCountsAndCatalogAssetPolicy();
        assertStablePaintingIdsAndSourceKeys();
        assertMissingImageDetailAndGalleryBoundary();
        CatalogSourceSnapshot sourceSnapshot = snapshotFactory.create(csv, pictures);
        assertRepresentativeAllColumnMapping(sourceSnapshot);
        assertOrphansWereNotRegistered(sourceSnapshot);
        CatalogIdentitySnapshot firstIdentities = identitySnapshot();
        assertThat(regularFileCount(MANAGED_ASSETS)).isEqualTo(managedFilesBefore);

        Instant secondStarted = Instant.now();
        CatalogImportResult second = importer.importCatalog();
        Duration secondDuration = Duration.between(secondStarted, Instant.now());

        assertThat(second.status()).isEqualTo(CatalogImportStatus.SKIPPED);
        assertThat(second.sourceFingerprint()).isEqualTo(first.sourceFingerprint());
        assertThat(second.totalRows()).isEqualTo(EXPECTED_PAINTINGS);
        assertThat(second.insertedRows()).isZero();
        assertThat(second.updatedRows()).isZero();
        assertThat(second.unchangedRows()).isEqualTo(EXPECTED_PAINTINGS);
        assertThat(second.matchedImages()).isEqualTo(EXPECTED_MATCHED_IMAGES);
        assertThat(second.missingImages()).isEqualTo(EXPECTED_MISSING_IMAGES);
        assertThat(second.orphanImages()).isEqualTo(EXPECTED_ORPHAN_IMAGES);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_import_runs", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM catalog_import_runs WHERE status = 'SUCCESS'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM catalog_import_runs WHERE status = 'SKIPPED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        assertThat(identitySnapshot()).isEqualTo(firstIdentities);
        assertThat(regularFileCount(MANAGED_ASSETS)).isEqualTo(managedFilesBefore);

        int generatedTextRows = nonBlankCount("generated_text");
        int musicSceneRows = nonBlankCount("music_scene_description");
        assertThat(generatedTextRows).isGreaterThan(EXPECTED_PAINTINGS / 2);
        assertThat(musicSceneRows).isGreaterThan(EXPECTED_PAINTINGS / 2);

        System.out.printf(
                Locale.ROOT,
                "ROUND5_REAL_CATALOG_METRICS paintings=%d matched=%d missing=%d orphan=%d generated_text=%d music_scene=%d first_ms=%d second_ms=%d%n",
                first.totalRows(),
                first.matchedImages(),
                first.missingImages(),
                first.orphanImages(),
                generatedTextRows,
                musicSceneRows,
                firstDuration.toMillis(),
                secondDuration.toMillis());
    }

    private void assertDatabaseCountsAndCatalogAssetPolicy() {
        assertThat(count("paintings")).isEqualTo(EXPECTED_PAINTINGS);
        assertThat(count("media_assets")).isEqualTo(EXPECTED_MATCHED_IMAGES);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paintings WHERE image_available = 1", Integer.class))
                .isEqualTo(EXPECTED_MATCHED_IMAGES);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paintings WHERE visible_in_gallery = 1", Integer.class))
                .isEqualTo(EXPECTED_MATCHED_IMAGES);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paintings WHERE image_available = 0 AND image_asset_id IS NULL",
                Integer.class)).isEqualTo(EXPECTED_MISSING_IMAGES);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paintings WHERE visible_in_gallery = 0",
                Integer.class)).isEqualTo(EXPECTED_MISSING_IMAGES);

        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM media_assets
                WHERE owner_user_id IS NULL
                  AND asset_type = 'IMAGE'
                  AND semantic_type = 'PAINTING'
                  AND source_type = 'CATALOG_REFERENCE'
                  AND visibility = 'PUBLIC'
                  AND status = 'ACTIVE'
                  AND storage_key LIKE 'catalog/%'
                  AND storage_key NOT LIKE '/%'
                """,
                Integer.class)).isEqualTo(EXPECTED_MATCHED_IMAGES);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM paintings p
                JOIN media_assets m ON m.id = p.image_asset_id
                WHERE p.image_available = 1
                  AND m.source_type = 'CATALOG_REFERENCE'
                  AND m.visibility = 'PUBLIC'
                  AND m.status = 'ACTIVE'
                """,
                Integer.class)).isEqualTo(EXPECTED_MATCHED_IMAGES);
    }

    private void assertStablePaintingIdsAndSourceKeys() {
        List<IdAndSourceKey> rows = jdbc.query(
                "SELECT public_id, source_key FROM paintings",
                (resultSet, rowNumber) -> new IdAndSourceKey(
                        resultSet.getString("public_id"), resultSet.getString("source_key")));
        assertThat(rows).hasSize(EXPECTED_PAINTINGS);

        Set<String> publicIds = new HashSet<>();
        Set<String> sourceKeys = new HashSet<>();
        for (IdAndSourceKey row : rows) {
            String canonical = UUID.fromString(row.publicId()).toString();
            assertThat(row.publicId()).isEqualTo(canonical);
            assertThat(row.sourceKey()).startsWith(OfficialPaintingRecord.SOURCE_KEY_PREFIX);
            assertThat(row.sourceKey()).doesNotContain("/../", "\\");
            publicIds.add(row.publicId());
            sourceKeys.add(row.sourceKey());
        }
        assertThat(publicIds).hasSize(EXPECTED_PAINTINGS);
        assertThat(sourceKeys).hasSize(EXPECTED_PAINTINGS);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT source_key) FROM paintings", Integer.class))
                .isEqualTo(EXPECTED_PAINTINGS);

        List<AssetIdentity> assets = jdbc.query(
                "SELECT public_id, storage_key FROM media_assets",
                (resultSet, rowNumber) -> new AssetIdentity(
                        resultSet.getString("public_id"), resultSet.getString("storage_key")));
        assertThat(assets).hasSize(EXPECTED_MATCHED_IMAGES);
        assertThat(assets).allSatisfy(asset -> {
            assertThat(UUID.fromString(asset.publicId()).toString()).isEqualTo(asset.publicId());
            assertThat(asset.storageKey()).startsWith("catalog/");
            assertThat(Path.of(asset.storageKey()).isAbsolute()).isFalse();
            assertThat(asset.storageKey()).doesNotContain("\\", "/../", "/./");
        });
        assertThat(assets.stream().map(AssetIdentity::publicId).collect(java.util.stream.Collectors.toSet()))
                .hasSize(EXPECTED_MATCHED_IMAGES);
    }

    private void assertRepresentativeAllColumnMapping(CatalogSourceSnapshot snapshot) {
        assertThat(snapshot.totalRows()).isEqualTo(EXPECTED_PAINTINGS);
        assertThat(snapshot.matchedImages()).isEqualTo(EXPECTED_MATCHED_IMAGES);
        assertThat(snapshot.missingImages()).isEqualTo(EXPECTED_MISSING_IMAGES);
        assertThat(snapshot.orphanImages()).isEqualTo(EXPECTED_ORPHAN_IMAGES);

        OfficialPaintingRecord source = snapshot.rows().get(0).record();
        Painting stored = paintingRepository.findBySourceKey(source.sourceKey()).orElseThrow();
        assertThat(stored.getSourceKey()).isEqualTo(source.sourceKey());
        assertThat(stored.getSourceSequence()).isEqualTo(source.sourceSequence());
        assertThat(stored.getImageStorageName()).isEqualTo(source.imageStorageName());
        assertThat(stored.getTitle()).isEqualTo(source.title());
        assertThat(stored.getAuthorName()).isEqualTo(source.authorName());
        assertThat(stored.getAuthorBirthYear()).isEqualTo(source.authorBirthYear());
        assertThat(stored.getAuthorBirthPlace()).isEqualTo(source.authorBirthPlace());
        assertThat(stored.getAuthorSchool()).isEqualTo(source.authorSchool());
        assertThat(stored.getCreationYear()).isEqualTo(source.creationYear());
        assertThat(stored.getCreationDynastyRaw()).isEqualTo(source.creationDynastyRaw());
        assertThat(stored.getCreationDynastyNormalized())
                .isEqualTo(dynastyNormalizer.normalize(source.creationDynastyRaw()));
        assertThat(stored.getActualSize()).isEqualTo(source.actualSize());
        assertThat(stored.getCollectionInstitution()).isEqualTo(source.collectionInstitution());
        assertThat(stored.getCategory()).isEqualTo(source.category());
        assertThat(stored.getSubject()).isEqualTo(source.subject());
        assertThat(stored.getPaintingSchool()).isEqualTo(source.paintingSchool());
        assertThat(stored.getStyle()).isEqualTo(source.style());
        assertThat(stored.getColor()).isEqualTo(source.color());
        assertThat(stored.getComposition()).isEqualTo(source.composition());
        assertThat(stored.getArtisticConception()).isEqualTo(source.artisticConception());
        assertThat(stored.getBrushwork()).isEqualTo(source.brushwork());
        assertThat(stored.getInkMethod()).isEqualTo(source.inkMethod());
        assertThat(stored.getPaintingMaterial()).isEqualTo(source.paintingMaterial());
        assertThat(stored.getPigment()).isEqualTo(source.pigment());
        assertThat(stored.getSeal()).isEqualTo(source.seal());
        assertThat(stored.getCulturalSymbol()).isEqualTo(source.culturalSymbol());
        assertThat(stored.getGeneratedText()).isEqualTo(source.generatedText());
        assertThat(stored.getMusicSceneDescription()).isEqualTo(source.musicSceneDescription());
        assertThat(stored.getCollectionPlatform()).isEqualTo(source.collectionPlatform());
    }

    private void assertOrphansWereNotRegistered(CatalogSourceSnapshot snapshot) {
        assertThat(snapshot.orphanImageFileNames()).hasSize(EXPECTED_ORPHAN_IMAGES);
        assertThat(snapshot.orphanImageFileNames()).allSatisfy(fileName -> {
            Integer assetRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM media_assets WHERE storage_key = ?",
                    Integer.class,
                    "catalog/" + fileName);
            assertThat(assetRows).isZero();
        });
    }

    private void assertMissingImageDetailAndGalleryBoundary() {
        PaintingPageResponse gallery = queryService.listPaintings(
                null, null, null, null, null, null, null, null, null, null, null,
                0, 24, "source", "asc");
        assertThat(gallery.totalElements()).isEqualTo(EXPECTED_MATCHED_IMAGES);
        assertThat(gallery.items()).hasSize(24).allSatisfy(item -> {
            assertThat(item.imageAvailable()).isTrue();
            assertThat(item.image()).isNotNull();
        });

        String missingPaintingId = jdbc.queryForObject(
                """
                SELECT public_id
                FROM paintings
                WHERE image_available = 0
                ORDER BY source_key
                LIMIT 1
                """,
                String.class);
        assertThat(missingPaintingId).isNotBlank();

        User viewer = userRepository.saveAndFlush(User.builder()
                .username("round5-real-catalog-viewer")
                .password("not-a-real-credential")
                .fullName("Round 5 catalog viewer")
                .email("round5-real-catalog-viewer@example.invalid")
                .role("ROLE_USER")
                .build());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(viewer, null, viewer.getAuthorities()));

        PaintingDetailResponse detail = queryService.getPainting(missingPaintingId);
        assertThat(detail.paintingId()).isEqualTo(missingPaintingId);
        assertThat(detail.status()).isEqualTo("ACTIVE");
        assertThat(detail.imageAvailable()).isFalse();
        assertThat(detail.visibleInGallery()).isFalse();
        assertThat(detail.image()).isNull();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int nonBlankCount(String column) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM paintings WHERE " + column + " IS NOT NULL AND trim(" + column + ") <> ''",
                Integer.class);
    }

    private CatalogIdentitySnapshot identitySnapshot() {
        Map<String, String> paintings = jdbc.query(
                        "SELECT source_key, public_id FROM paintings",
                        (resultSet, rowNumber) -> new IdAndSourceKey(
                                resultSet.getString("public_id"), resultSet.getString("source_key")))
                .stream()
                .collect(Collectors.toUnmodifiableMap(IdAndSourceKey::sourceKey, IdAndSourceKey::publicId));
        Map<String, String> assets = jdbc.query(
                        "SELECT storage_key, public_id FROM media_assets",
                        (resultSet, rowNumber) -> new AssetIdentity(
                                resultSet.getString("public_id"), resultSet.getString("storage_key")))
                .stream()
                .collect(Collectors.toUnmodifiableMap(AssetIdentity::storageKey, AssetIdentity::publicId));
        return new CatalogIdentitySnapshot(paintings, assets);
    }

    private long regularFileCount(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0L;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required real-catalog test system property is missing: " + name);
        }
        return value;
    }

    private record IdAndSourceKey(String publicId, String sourceKey) {
    }

    private record AssetIdentity(String publicId, String storageKey) {
    }

    private record CatalogIdentitySnapshot(
            Map<String, String> paintingIdsBySourceKey,
            Map<String, String> assetIdsByStorageKey) {
    }
}
