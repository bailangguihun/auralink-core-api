package com.auralink.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.auralink.entity.CatalogImportRun;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.repository.CatalogImportRunRepository;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;

/**
 * Exercises the full importer only against a unique Flyway-migrated SQLite file
 * under /tmp. No test path can resolve to the inherited live database or catalog.
 */
@SpringBootTest(properties = {
        "spring.config.import=optional:file:/tmp/auralink-round5-importer-no-env.properties",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON",
        "auralink.jwt.secret=round5-importer-test-only-jwt-secret",
        "auralink.paintings.import-enabled=false",
        "auralink.paintings.import-fail-on-error=true",
        "auralink.paintings.import-batch-size=2"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaintingCatalogImporterIntegrationTest {

    private static final Path ROOT = Path.of(
            "/tmp", "auralink-round5-importer-" + UUID.randomUUID());
    private static final Path DATABASE = ROOT.resolve("round5.db");
    private static final Path CSV = ROOT.resolve("paintings.csv");
    private static final Path PICTURES = ROOT.resolve("pictures");
    private static final Path MANAGED = ROOT.resolve("managed-assets");

    @DynamicPropertySource
    static void isolatedRuntime(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(ROOT);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to create isolated Round 5 test directory", exception);
        }
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("auralink.paintings.metadata-csv-path", CSV::toString);
        registry.add("auralink.paintings.picture-dir", PICTURES::toString);
        registry.add("auralink.media-assets.managed-dir", MANAGED::toString);
        registry.add("auralink.storage.upload-dir", () -> ROOT.resolve("legacy-uploads").toString());
        registry.add("auralink.storage.audio-dir", () -> ROOT.resolve("legacy-audio").toString());
        registry.add("auralink.storage.legacy-frontend-audio-dir",
                () -> ROOT.resolve("legacy-frontend-audio").toString());
    }

    @Autowired private PaintingCatalogImporter importer;
    @Autowired private PaintingRepository paintings;
    @Autowired private MediaAssetRepository mediaAssets;
    @Autowired private CatalogImportRunRepository importRuns;

    @BeforeEach
    void resetIsolatedCatalogState() throws IOException {
        paintings.deleteAllInBatch();
        mediaAssets.deleteAllInBatch();
        importRuns.deleteAllInBatch();
        Files.deleteIfExists(CSV);
        if (Files.exists(PICTURES)) {
            try (var paths = Files.walk(PICTURES)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(PICTURES);
    }

    @Test
    void importsUpdatesSkipsAndRetainsAbsentRowsWithStablePublicIds() throws Exception {
        writeJpeg(PICTURES.resolve("1 (1).jpg"));
        writeJpeg(PICTURES.resolve("existing.jpeg"));
        writeJpeg(PICTURES.resolve("absent.jpg"));
        writeJpeg(PICTURES.resolve("orphan.jpg"));

        List<String> official = completeOfficialRow();
        List<String> initiallyMissing = minimalRow("2", "missing", "起初缺图");
        List<String> laterMissing = minimalRow("3", "existing.jpeg", "随后缺图");
        List<String> laterAbsent = minimalRow("4", "absent", "后续源中缺席");
        writeCsv(List.of(official, initiallyMissing, laterMissing, laterAbsent));

        CatalogImportResult initial = importer.importCatalog();

        assertThat(initial.status()).isEqualTo(CatalogImportStatus.SUCCESS);
        assertThat(initial.totalRows()).isEqualTo(4);
        assertThat(initial.insertedRows()).isEqualTo(4);
        assertThat(initial.updatedRows()).isZero();
        assertThat(initial.unchangedRows()).isZero();
        assertThat(initial.matchedImages()).isEqualTo(3);
        assertThat(initial.missingImages()).isEqualTo(1);
        assertThat(initial.orphanImages()).isEqualTo(1);
        assertThat(paintings.count()).isEqualTo(4);
        assertThat(mediaAssets.count()).isEqualTo(3);

        Painting first = requirePainting("1（1）");
        Painting missing = requirePainting("missing");
        Painting disappearing = requirePainting("existing.jpeg");
        Painting absentLater = requirePainting("absent");
        assertCompleteOfficialMapping(first);
        assertThat(first.isImageAvailable()).isTrue();
        assertThat(first.isVisibleInGallery()).isTrue();
        assertThat(first.getImageAsset()).isNotNull();
        assertThat(missing.getImageAsset()).isNull();
        assertThat(missing.isImageAvailable()).isFalse();
        assertThat(missing.isVisibleInGallery()).isFalse();

        String firstPaintingId = first.getPublicId();
        String firstMediaId = mediaAssets.findByStorageKey("catalog/1 (1).jpg")
                .orElseThrow()
                .getPublicId();
        String missingPaintingId = missing.getPublicId();
        String disappearingPaintingId = disappearing.getPublicId();
        String absentPaintingId = absentLater.getPublicId();

        CatalogImportResult skipped = importer.importCatalog();
        assertThat(skipped.status()).isEqualTo(CatalogImportStatus.SKIPPED);
        assertThat(skipped.sourceFingerprint()).isEqualTo(initial.sourceFingerprint());
        assertThat(skipped.totalRows()).isEqualTo(4);
        assertThat(skipped.unchangedRows()).isEqualTo(4);
        assertThat(paintings.count()).isEqualTo(4);
        assertThat(mediaAssets.count()).isEqualTo(3);

        official.set(2, "更新后的官方题名");
        official.set(24, "更新后的官方文本");
        Files.delete(PICTURES.resolve("existing.jpeg"));
        writeJpeg(PICTURES.resolve("missing.jpeg"));
        writeCsv(List.of(official, initiallyMissing, laterMissing));

        CatalogImportResult changed = importer.importCatalog();

        assertThat(changed.status()).isEqualTo(CatalogImportStatus.SUCCESS);
        assertThat(changed.sourceFingerprint()).isNotEqualTo(initial.sourceFingerprint());
        assertThat(changed.totalRows()).isEqualTo(3);
        assertThat(changed.insertedRows()).isZero();
        assertThat(changed.updatedRows()).isEqualTo(3);
        assertThat(changed.unchangedRows()).isZero();
        assertThat(changed.matchedImages()).isEqualTo(2);
        assertThat(changed.missingImages()).isEqualTo(1);
        assertThat(changed.orphanImages()).isEqualTo(2);

        Painting updatedFirst = requirePainting("1（1）");
        Painting updatedMissing = requirePainting("missing");
        Painting updatedDisappearing = requirePainting("existing.jpeg");
        Painting retainedAbsent = requirePainting("absent");
        assertThat(updatedFirst.getPublicId()).isEqualTo(firstPaintingId);
        assertThat(updatedFirst.getTitle()).isEqualTo("更新后的官方题名");
        assertThat(updatedFirst.getGeneratedText()).isEqualTo("更新后的官方文本");
        assertThat(updatedFirst.getImageAsset()).isNotNull();
        assertThat(mediaAssets.findByStorageKey("catalog/1 (1).jpg")
                .orElseThrow()
                .getPublicId()).isEqualTo(firstMediaId);
        assertThat(updatedMissing.getPublicId()).isEqualTo(missingPaintingId);
        assertThat(updatedMissing.isImageAvailable()).isTrue();
        assertThat(updatedMissing.isVisibleInGallery()).isTrue();
        assertThat(updatedMissing.getImageAsset()).isNotNull();
        assertThat(updatedDisappearing.getPublicId()).isEqualTo(disappearingPaintingId);
        assertThat(updatedDisappearing.getImageAsset()).isNull();
        assertThat(updatedDisappearing.isImageAvailable()).isFalse();
        assertThat(updatedDisappearing.isVisibleInGallery()).isFalse();
        assertThat(retainedAbsent.getPublicId()).isEqualTo(absentPaintingId);
        assertThat(paintings.count()).isEqualTo(4);
        assertThat(mediaAssets.count()).isEqualTo(4);

        List<CatalogImportRun> audits = importRuns.findAll().stream()
                .sorted(Comparator.comparing(CatalogImportRun::getId))
                .toList();
        assertThat(audits).extracting(CatalogImportRun::getStatus)
                .containsExactly(
                        CatalogImportStatus.SUCCESS,
                        CatalogImportStatus.SKIPPED,
                        CatalogImportStatus.SUCCESS);
        assertThat(audits.get(0).getInsertedRows()).isEqualTo(4);
        assertThat(audits.get(0).getMatchedImages()).isEqualTo(3);
        assertThat(audits.get(0).getMissingImages()).isEqualTo(1);
        assertThat(audits.get(0).getOrphanImages()).isEqualTo(1);
        assertThat(audits.get(1).getUnchangedRows()).isEqualTo(4);
        assertThat(audits.get(2).getUpdatedRows()).isEqualTo(3);
        assertThat(audits.get(2).getMatchedImages()).isEqualTo(2);
        assertThat(audits.get(2).getMissingImages()).isEqualTo(1);
        assertThat(audits.get(2).getOrphanImages()).isEqualTo(2);
        assertThat(audits).allSatisfy(run -> {
            assertThat(run.getSourceName()).isEqualTo("paintings.csv");
            assertThat(run.getFinishedAt()).isNotNull();
            assertThat(run.getErrorMessage()).isNull();
        });
    }

    @Test
    void recordsSafeFailedAuditAndResumesAfterLaterBatchFailure() throws Exception {
        writeJpeg(PICTURES.resolve("restart-first.jpg"));
        writeJpeg(PICTURES.resolve("restart-second.jpg"));
        Files.writeString(
                PICTURES.resolve("restart-failure.jpg"),
                "not an image",
                StandardCharsets.UTF_8);
        writeJpeg(PICTURES.resolve("restart-fourth.jpg"));
        writeCsv(List.of(
                minimalRow("1", "restart-first.jpg", "首批一"),
                minimalRow("2", "restart-second.jpg", "首批二"),
                minimalRow("3", "restart-failure.jpg", "失败批次"),
                minimalRow("4", "restart-fourth.jpg", "失败批次后续")));

        assertThatThrownBy(importer::importCatalog)
                .isInstanceOf(CatalogImportException.class)
                .hasMessage("Official painting catalog import failed");

        assertThat(paintings.count()).isEqualTo(2);
        assertThat(mediaAssets.count()).isEqualTo(2);
        Painting committedFirst = requirePainting("restart-first.jpg");
        Painting committedSecond = requirePainting("restart-second.jpg");
        String firstPaintingId = committedFirst.getPublicId();
        String secondPaintingId = committedSecond.getPublicId();
        String firstAssetId = requireCatalogAsset("restart-first.jpg").getPublicId();
        String secondAssetId = requireCatalogAsset("restart-second.jpg").getPublicId();
        assertThat(paintings.findBySourceKey(
                OfficialPaintingRecord.SOURCE_KEY_PREFIX + "restart-failure.jpg")).isEmpty();
        assertThat(paintings.findBySourceKey(
                OfficialPaintingRecord.SOURCE_KEY_PREFIX + "restart-fourth.jpg")).isEmpty();

        CatalogImportRun failed = importRuns.findAll().stream()
                .filter(run -> CatalogImportStatus.FAILED.equals(run.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(failed.getTotalRows()).isEqualTo(4);
        assertThat(failed.getInsertedRows()).isEqualTo(2);
        assertThat(failed.getUpdatedRows()).isZero();
        assertThat(failed.getUnchangedRows()).isZero();
        assertThat(failed.getFinishedAt()).isNotNull();
        assertThat(failed.getErrorMessage())
                .startsWith("Catalog synchronization failed (")
                .doesNotContain(ROOT.toString())
                .doesNotContain(CSV.toString())
                .doesNotContain(PICTURES.toString())
                .doesNotContain("restart-failure.jpg");

        writeJpeg(PICTURES.resolve("restart-failure.jpg"));
        CatalogImportResult resumed = importer.importCatalog();

        assertThat(resumed.status()).isEqualTo(CatalogImportStatus.SUCCESS);
        assertThat(resumed.totalRows()).isEqualTo(4);
        assertThat(resumed.insertedRows()).isEqualTo(2);
        assertThat(resumed.updatedRows()).isZero();
        assertThat(resumed.unchangedRows()).isEqualTo(2);
        assertThat(paintings.count()).isEqualTo(4);
        assertThat(mediaAssets.count()).isEqualTo(4);
        assertThat(requirePainting("restart-first.jpg").getPublicId())
                .isEqualTo(firstPaintingId);
        assertThat(requirePainting("restart-second.jpg").getPublicId())
                .isEqualTo(secondPaintingId);
        assertThat(requireCatalogAsset("restart-first.jpg").getPublicId())
                .isEqualTo(firstAssetId);
        assertThat(requireCatalogAsset("restart-second.jpg").getPublicId())
                .isEqualTo(secondAssetId);
        assertThat(paintings.findAll())
                .extracting(Painting::getSourceKey)
                .doesNotHaveDuplicates();
        assertThat(mediaAssets.findAll())
                .extracting(MediaAsset::getStorageKey)
                .doesNotHaveDuplicates();

        List<CatalogImportRun> audits = importRuns.findAll().stream()
                .sorted(Comparator.comparing(CatalogImportRun::getId))
                .toList();
        assertThat(audits).extracting(CatalogImportRun::getStatus)
                .containsExactly(CatalogImportStatus.FAILED, CatalogImportStatus.SUCCESS);
        assertThat(audits.get(1).getFinishedAt()).isNotNull();
        assertThat(audits.get(1).getErrorMessage()).isNull();
    }

    private Painting requirePainting(String imageStorageName) {
        return paintings.findBySourceKey(OfficialPaintingRecord.SOURCE_KEY_PREFIX + imageStorageName)
                .orElseThrow();
    }

    private MediaAsset requireCatalogAsset(String fileName) {
        return mediaAssets.findByStorageKey("catalog/" + fileName).orElseThrow();
    }

    private void assertCompleteOfficialMapping(Painting painting) {
        assertThat(painting.getSourceSequence()).isEqualTo("1");
        assertThat(painting.getImageStorageName()).isEqualTo("1（1）");
        assertThat(painting.getTitle()).isEqualTo("官方画作");
        assertThat(painting.getAuthorName()).isEqualTo("作者");
        assertThat(painting.getAuthorBirthYear()).isEqualTo("0");
        assertThat(painting.getAuthorBirthPlace()).isEqualTo("出生地");
        assertThat(painting.getAuthorSchool()).isEqualTo("作者流派");
        assertThat(painting.getCreationYear()).isEqualTo("创作年代");
        assertThat(painting.getCreationDynastyRaw()).isEqualTo("清朝");
        assertThat(painting.getCreationDynastyNormalized()).isEqualTo("清代");
        assertThat(painting.getActualSize()).isEqualTo("尺寸");
        assertThat(painting.getCollectionInstitution()).isEqualTo("收藏机构");
        assertThat(painting.getCategory()).isEqualTo("分类");
        assertThat(painting.getSubject()).isEqualTo("题材");
        assertThat(painting.getPaintingSchool()).isEqualTo("画作流派");
        assertThat(painting.getStyle()).isEqualTo("风格");
        assertThat(painting.getColor()).isEqualTo("色彩");
        assertThat(painting.getComposition()).isEqualTo("构图");
        assertThat(painting.getArtisticConception()).isEqualTo("意境");
        assertThat(painting.getBrushwork()).isEqualTo("笔法");
        assertThat(painting.getInkMethod()).isEqualTo("墨法");
        assertThat(painting.getPaintingMaterial()).isEqualTo("绘画材料");
        assertThat(painting.getPigment()).isEqualTo("颜料");
        assertThat(painting.getSeal()).isEqualTo("印章");
        assertThat(painting.getCulturalSymbol()).isEqualTo("文化符号");
        assertThat(painting.getGeneratedText()).isEqualTo("0");
        assertThat(painting.getMusicSceneDescription()).isEqualTo("音乐情境");
        assertThat(painting.getCollectionPlatform()).isEqualTo("收集平台");
        assertThat(painting.getStatus()).isEqualTo("ACTIVE");
    }

    private List<String> completeOfficialRow() {
        return new ArrayList<>(List.of(
                "1",
                "1（1）",
                "官方画作",
                "作者",
                "0",
                "出生地",
                "作者流派",
                "创作年代",
                "清朝",
                "尺寸",
                "收藏机构",
                "分类",
                "题材",
                "画作流派",
                "风格",
                "色彩",
                "构图",
                "意境",
                "笔法",
                "墨法",
                "绘画材料",
                "颜料",
                "印章",
                "文化符号",
                "0",
                "音乐情境",
                "收集平台"));
    }

    private List<String> minimalRow(String sequence, String imageName, String title) {
        List<String> values = new ArrayList<>(
                Collections.nCopies(OfficialPaintingRecord.CSV_HEADERS.size(), ""));
        values.set(0, sequence);
        values.set(1, imageName);
        values.set(2, title);
        values.set(8, "明代");
        values.set(11, "分类");
        return values;
    }

    private void writeCsv(List<List<String>> rows) throws IOException {
        StringBuilder content = new StringBuilder("\uFEFF");
        content.append(csvLine(OfficialPaintingRecord.CSV_HEADERS)).append('\n');
        for (List<String> row : rows) {
            content.append(csvLine(row)).append('\n');
        }
        Files.writeString(CSV, content, StandardCharsets.UTF_8);
    }

    private String csvLine(List<String> values) {
        return values.stream().map(this::csvCell).collect(Collectors.joining(","));
    }

    private String csvCell(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private void writeJpeg(Path path) throws IOException {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLACK.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "JPEG", output)) {
            throw new IOException("JPEG writer unavailable in test runtime");
        }
        Files.write(path, output.toByteArray());
    }
}
