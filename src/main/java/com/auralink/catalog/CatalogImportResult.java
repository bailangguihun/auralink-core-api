package com.auralink.catalog;

import com.auralink.entity.CatalogImportRun;

/** Provider- and filesystem-neutral summary of one catalog synchronization. */
public record CatalogImportResult(
        String runId,
        String status,
        String sourceFingerprint,
        int totalRows,
        int insertedRows,
        int updatedRows,
        int unchangedRows,
        int matchedImages,
        int missingImages,
        int orphanImages) {

    public static CatalogImportResult from(CatalogImportRun run) {
        return new CatalogImportResult(
                run.getPublicId(),
                run.getStatus(),
                run.getSourceSha256(),
                run.getTotalRows(),
                run.getInsertedRows(),
                run.getUpdatedRows(),
                run.getUnchangedRows(),
                run.getMatchedImages(),
                run.getMissingImages(),
                run.getOrphanImages());
    }
}
