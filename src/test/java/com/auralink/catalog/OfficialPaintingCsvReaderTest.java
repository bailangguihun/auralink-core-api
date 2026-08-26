package com.auralink.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialPaintingCsvReaderTest {

    @TempDir
    Path temporaryDirectory;

    private final OfficialPaintingCsvReader reader = new OfficialPaintingCsvReader();

    @Test
    void mapsEveryHeaderToItsExactTypedRecordComponent() throws IOException {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < OfficialPaintingRecord.CSV_HEADERS.size(); index++) {
            values.add("字段-" + index);
        }

        OfficialPaintingRecord record = reader.read(
                writeCsv(true, OfficialPaintingRecord.CSV_HEADERS, List.of(values))).get(0);

        assertThat(recordValues(record)).containsExactlyElementsOf(values);
    }

    @Test
    void readsUtf8BomAllTwentySevenHeadersAndQuotedCommaAndNewline() throws IOException {
        List<String> values = blankRow();
        values.set(0, " 17 ");
        values.set(1, " 17（2） ");
        values.set(2, "千里,江山图");
        values.set(3, "王希孟");
        values.set(8, "北宋");
        values.set(14, "青绿山水\n层峦叠嶂");
        values.set(24, "中文官方注释");
        values.set(25, "箫、古琴，与流水意象");

        Path csv = writeCsv(true, OfficialPaintingRecord.CSV_HEADERS, List.of(values));

        List<OfficialPaintingRecord> records = reader.read(csv);

        assertThat(records).hasSize(1);
        OfficialPaintingRecord record = records.get(0);
        assertThat(record.sourceSequence()).isEqualTo("17");
        assertThat(record.imageStorageName()).isEqualTo("17（2）");
        assertThat(record.title()).isEqualTo("千里,江山图");
        assertThat(record.authorName()).isEqualTo("王希孟");
        assertThat(record.style()).isEqualTo("青绿山水\n层峦叠嶂");
        assertThat(record.generatedText()).isEqualTo("中文官方注释");
        assertThat(record.musicSceneDescription()).isEqualTo("箫、古琴，与流水意象");
        assertThat(record.sourceKey()).isEqualTo("painting-dataset:17（2）");
    }

    @Test
    void mapsBlankOptionalCellsToNullAndPreservesLiteralZeroFromCsv() throws IOException {
        List<String> values = blankRow();
        values.set(0, "0");
        values.set(1, "zero-image");
        values.set(4, "0");
        values.set(23, "   ");

        OfficialPaintingRecord record = reader.read(
                writeCsv(false, OfficialPaintingRecord.CSV_HEADERS, List.of(values))).get(0);

        assertThat(record.sourceSequence()).isEqualTo("0");
        assertThat(record.authorBirthYear()).isEqualTo("0");
        assertThat(record.title()).isNull();
        assertThat(record.culturalSymbol()).isNull();
        assertThat(record.collectionPlatform()).isNull();
        assertThat(record.category()).isNull();
    }

    @Test
    void rejectsMissingReorderedOrAdditionalHeaders() throws IOException {
        List<String> missing = new ArrayList<>(OfficialPaintingRecord.CSV_HEADERS);
        missing.remove(missing.size() - 1);
        assertHeaderRejected(missing);

        List<String> reordered = new ArrayList<>(OfficialPaintingRecord.CSV_HEADERS);
        Collections.swap(reordered, 0, 1);
        assertHeaderRejected(reordered);

        List<String> additional = new ArrayList<>(OfficialPaintingRecord.CSV_HEADERS);
        additional.add("额外字段");
        assertHeaderRejected(additional);
    }

    @Test
    void rejectsRowsWithTooFewOrTooManyFields() throws IOException {
        List<String> tooFew = blankRow();
        tooFew.set(1, "short-row");
        tooFew.remove(tooFew.size() - 1);
        Path shortCsv = writeCsv(false, OfficialPaintingRecord.CSV_HEADERS, List.of(tooFew));

        assertThatThrownBy(() -> reader.read(shortCsv))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("exactly 27 fields");

        List<String> tooMany = blankRow();
        tooMany.set(1, "long-row");
        tooMany.add("extra");
        Path longCsv = temporaryDirectory.resolve("too-many.csv");
        Files.writeString(
                longCsv,
                csvLine(OfficialPaintingRecord.CSV_HEADERS) + "\n" + csvLine(tooMany) + "\n",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(longCsv))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("exactly 27 fields");
    }

    @Test
    void wrapsMalformedCsvQuotingAsControlledCatalogError() throws IOException {
        Path malformed = temporaryDirectory.resolve("malformed.csv");
        Files.writeString(
                malformed,
                csvLine(OfficialPaintingRecord.CSV_HEADERS) + "\n\"1\",\"unterminated",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(malformed))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageNotContaining(malformed.toString());
    }

    @Test
    void rejectsBlankImageStorageNameAndUnavailableSource() throws IOException {
        List<String> values = blankRow();
        values.set(0, "1");
        Path csv = writeCsv(false, OfficialPaintingRecord.CSV_HEADERS, List.of(values));

        assertThatThrownBy(() -> reader.read(csv))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("image storage name");
        assertThatThrownBy(() -> reader.read(temporaryDirectory.resolve("missing.csv")))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessage("Official painting CSV is unavailable")
                .hasMessageNotContaining(temporaryDirectory.toString());
    }

    private void assertHeaderRejected(List<String> headers) throws IOException {
        Path csv = writeCsv(false, headers, List.of());
        assertThatThrownBy(() -> reader.read(csv))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("27-field schema");
    }

    private Path writeCsv(boolean bom, List<String> headers, List<List<String>> rows) throws IOException {
        StringBuilder content = new StringBuilder();
        if (bom) {
            content.append('\uFEFF');
        }
        content.append(csvLine(headers)).append('\n');
        for (List<String> row : rows) {
            content.append(csvLine(row)).append('\n');
        }
        Path csv = temporaryDirectory.resolve("catalog-" + System.nanoTime() + ".csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }

    private List<String> blankRow() {
        return new ArrayList<>(Collections.nCopies(OfficialPaintingRecord.CSV_HEADERS.size(), ""));
    }

    private List<String> recordValues(OfficialPaintingRecord record) {
        return List.of(
                record.sourceSequence(),
                record.imageStorageName(),
                record.title(),
                record.authorName(),
                record.authorBirthYear(),
                record.authorBirthPlace(),
                record.authorSchool(),
                record.creationYear(),
                record.creationDynastyRaw(),
                record.actualSize(),
                record.collectionInstitution(),
                record.category(),
                record.subject(),
                record.paintingSchool(),
                record.style(),
                record.color(),
                record.composition(),
                record.artisticConception(),
                record.brushwork(),
                record.inkMethod(),
                record.paintingMaterial(),
                record.pigment(),
                record.seal(),
                record.culturalSymbol(),
                record.generatedText(),
                record.musicSceneDescription(),
                record.collectionPlatform());
    }

    private String csvLine(List<String> values) {
        return values.stream().map(this::csvCell).collect(Collectors.joining(","));
    }

    private String csvCell(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
