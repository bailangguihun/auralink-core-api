package com.auralink.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Immutable input snapshot used for one restartable catalog synchronization. */
public record CatalogSourceSnapshot(
        String sourceName,
        String csvSha256,
        String fingerprint,
        List<CatalogSourceRow> rows,
        List<String> orphanImageFileNames) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public CatalogSourceSnapshot {
        sourceName = sourceName == null ? "" : sourceName.trim();
        if (sourceName.isBlank()) {
            throw new CatalogSourceException("Catalog snapshot source name is required");
        }
        if (csvSha256 == null || !SHA256.matcher(csvSha256).matches()
                || fingerprint == null || !SHA256.matcher(fingerprint).matches()) {
            throw new CatalogSourceException("Catalog snapshot fingerprints must be lowercase SHA-256 values");
        }
        if (rows == null || orphanImageFileNames == null) {
            throw new CatalogSourceException("Catalog snapshot rows and orphan manifest are required");
        }
        rows = List.copyOf(rows);
        List<String> sortedOrphans = new ArrayList<>(orphanImageFileNames);
        sortedOrphans.sort(String::compareTo);
        orphanImageFileNames = List.copyOf(sortedOrphans);
    }

    public int totalRows() {
        return rows.size();
    }

    public int matchedImages() {
        return (int) rows.stream().filter(CatalogSourceRow::imageAvailable).count();
    }

    public int missingImages() {
        return totalRows() - matchedImages();
    }

    public int orphanImages() {
        return orphanImageFileNames.size();
    }
}
