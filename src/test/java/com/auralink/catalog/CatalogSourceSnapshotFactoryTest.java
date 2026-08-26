package com.auralink.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogSourceSnapshotFactoryTest {

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");

    @TempDir
    Path temporaryDirectory;

    private final CatalogSourceSnapshotFactory factory = new CatalogSourceSnapshotFactory(
            new OfficialPaintingCsvReader(), new PaintingImageMatcher());

    @Test
    void createsDeterministicMatchedMissingAndOrphanSnapshot() throws IOException {
        Path images = Files.createDirectories(temporaryDirectory.resolve("images"));
        writeImage(images.resolve("ONE.jpg"), "one", 1_000L);
        writeImage(images.resolve("orphan.jpeg"), "orphan", 2_000L);
        writeImage(images.resolve("ignored.png"), "not-in-official-corpus", 3_000L);

        List<String> matched = row("1", "one", "匹配画作");
        List<String> missing = row("2", "missing", "缺图画作");
        Path csv = writeCsv(temporaryDirectory.resolve("paintings.csv"), List.of(matched, missing));

        CatalogSourceSnapshot first = factory.create(csv, images);
        CatalogSourceSnapshot second = factory.create(csv, images);

        assertThat(first.sourceName()).isEqualTo("paintings.csv");
        assertThat(first.csvSha256()).matches(LOWERCASE_SHA256);
        assertThat(first.fingerprint()).matches(LOWERCASE_SHA256);
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.csvSha256()).isEqualTo(second.csvSha256());
        assertThat(first.totalRows()).isEqualTo(2);
        assertThat(first.matchedImages()).isEqualTo(1);
        assertThat(first.missingImages()).isEqualTo(1);
        assertThat(first.orphanImages()).isEqualTo(1);
        assertThat(first.orphanImageFileNames()).containsExactly("orphan.jpeg");
        assertThat(first.rows()).extracting(row -> row.record().sourceKey())
                .containsExactly("painting-dataset:one", "painting-dataset:missing");
        assertThat(first.rows().get(0).imageFileName()).isEqualTo("ONE.jpg");
        assertThat(first.rows().get(0).imageAvailable()).isTrue();
        assertThat(first.rows().get(1).imageFileName()).isNull();
        assertThat(first.rows().get(1).imageAvailable()).isFalse();
    }

    @Test
    void fingerprintChangesForCsvBytesOrImageManifestMetadata() throws IOException {
        Path images = Files.createDirectories(temporaryDirectory.resolve("fingerprint-images"));
        Path officialImage = images.resolve("item.jpg");
        writeImage(officialImage, "image", 10_000L);
        Path csv = writeCsv(
                temporaryDirectory.resolve("fingerprint.csv"),
                List.of(row("1", "item", "原标题")));

        CatalogSourceSnapshot original = factory.create(csv, images);

        Files.setLastModifiedTime(officialImage, FileTime.fromMillis(20_000L));
        CatalogSourceSnapshot metadataChanged = factory.create(csv, images);
        assertThat(metadataChanged.csvSha256()).isEqualTo(original.csvSha256());
        assertThat(metadataChanged.fingerprint()).isNotEqualTo(original.fingerprint());

        writeCsv(csv, List.of(row("1", "item", "修改后的标题")));
        CatalogSourceSnapshot csvChanged = factory.create(csv, images);
        assertThat(csvChanged.csvSha256()).isNotEqualTo(metadataChanged.csvSha256());
        assertThat(csvChanged.fingerprint()).isNotEqualTo(metadataChanged.fingerprint());
    }

    @Test
    void fingerprintIsIndependentOfDirectoryCreationOrder() throws IOException {
        Path firstImages = Files.createDirectories(temporaryDirectory.resolve("first-images"));
        writeImage(firstImages.resolve("b.jpeg"), "bb", 22_000L);
        writeImage(firstImages.resolve("a.jpg"), "a", 11_000L);

        Path secondImages = Files.createDirectories(temporaryDirectory.resolve("second-images"));
        writeImage(secondImages.resolve("a.jpg"), "a", 11_000L);
        writeImage(secondImages.resolve("b.jpeg"), "bb", 22_000L);

        Path firstCsv = writeCsv(
                temporaryDirectory.resolve("first/paintings.csv"),
                List.of(row("1", "a", "A"), row("2", "b.jpeg", "B")));
        Path secondCsv = writeCsv(
                temporaryDirectory.resolve("second/paintings.csv"),
                List.of(row("1", "a", "A"), row("2", "b.jpeg", "B")));

        CatalogSourceSnapshot first = factory.create(firstCsv, firstImages);
        CatalogSourceSnapshot second = factory.create(secondCsv, secondImages);

        assertThat(first.csvSha256()).isEqualTo(second.csvSha256());
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.orphanImageFileNames()).isEmpty();
        assertThat(second.orphanImageFileNames()).isEmpty();
    }

    @Test
    void rejectsDuplicateFrozenSourceKeysBeforeImport() throws IOException {
        Path images = Files.createDirectories(temporaryDirectory.resolve("duplicate-images"));
        Path csv = writeCsv(
                temporaryDirectory.resolve("duplicates.csv"),
                List.of(row("1", "same", "第一行"), row("2", "same", "第二行")));

        assertThatThrownBy(() -> factory.create(csv, images))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("duplicate source key");
    }

    private List<String> row(String sequence, String imageName, String title) {
        List<String> values = new ArrayList<>(
                Collections.nCopies(OfficialPaintingRecord.CSV_HEADERS.size(), ""));
        values.set(0, sequence);
        values.set(1, imageName);
        values.set(2, title);
        values.set(8, "明代");
        return values;
    }

    private Path writeCsv(Path path, List<List<String>> rows) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(csvLine(OfficialPaintingRecord.CSV_HEADERS)).append('\n');
        for (List<String> row : rows) {
            csv.append(csvLine(row)).append('\n');
        }
        Files.writeString(path, csv, StandardCharsets.UTF_8);
        return path;
    }

    private void writeImage(Path path, String content, long modifiedMillis) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(path, FileTime.fromMillis(modifiedMillis));
    }

    private String csvLine(List<String> values) {
        return values.stream().map(this::csvCell).collect(Collectors.joining(","));
    }

    private String csvCell(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
