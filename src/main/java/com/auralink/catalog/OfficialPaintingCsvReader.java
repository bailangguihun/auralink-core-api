package com.auralink.catalog;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/** Strict, BOM-safe reader for the inherited official painting CSV. */
@Component
public class OfficialPaintingCsvReader {

    private static final int EXPECTED_COLUMNS = OfficialPaintingRecord.CSV_HEADERS.size();

    public List<OfficialPaintingRecord> read(Path csvPath) {
        Path source = requireSource(csvPath);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setQuote('"')
                .setIgnoreEmptyLines(false)
                .build();

        try (Reader reader = openBomSafeReader(source);
                CSVParser parser = format.parse(reader)) {
            List<String> headers = parser.getHeaderNames();
            if (!OfficialPaintingRecord.CSV_HEADERS.equals(headers)) {
                throw new CatalogSourceException(
                        "Official painting CSV headers do not match the required 27-field schema");
            }

            List<OfficialPaintingRecord> records = new ArrayList<>();
            for (CSVRecord row : parser) {
                if (row.size() != EXPECTED_COLUMNS) {
                    throw new CatalogSourceException(
                            "Official painting CSV record " + row.getRecordNumber()
                                    + " does not contain exactly " + EXPECTED_COLUMNS + " fields");
                }
                records.add(toRecord(row));
            }
            return List.copyOf(records);
        } catch (CatalogSourceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new CatalogSourceException("Official painting CSV could not be read", exception);
        }
    }

    private OfficialPaintingRecord toRecord(CSVRecord row) {
        return new OfficialPaintingRecord(
                row.get(0),
                row.get(1),
                row.get(2),
                row.get(3),
                row.get(4),
                row.get(5),
                row.get(6),
                row.get(7),
                row.get(8),
                row.get(9),
                row.get(10),
                row.get(11),
                row.get(12),
                row.get(13),
                row.get(14),
                row.get(15),
                row.get(16),
                row.get(17),
                row.get(18),
                row.get(19),
                row.get(20),
                row.get(21),
                row.get(22),
                row.get(23),
                row.get(24),
                row.get(25),
                row.get(26));
    }

    private Reader openBomSafeReader(Path source) throws IOException {
        PushbackReader reader = new PushbackReader(
                Files.newBufferedReader(source, StandardCharsets.UTF_8), 1);
        int first = reader.read();
        if (first >= 0 && first != '\uFEFF') {
            reader.unread(first);
        }
        return reader;
    }

    private Path requireSource(Path csvPath) {
        if (csvPath == null) {
            throw new CatalogSourceException("Official painting CSV path is required");
        }
        Path normalized = csvPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(normalized)) {
            throw new CatalogSourceException("Official painting CSV is unavailable");
        }
        return normalized;
    }
}
