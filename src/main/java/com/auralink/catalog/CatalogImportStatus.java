package com.auralink.catalog;

/** Persisted catalog synchronization audit states. */
public final class CatalogImportStatus {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    private CatalogImportStatus() {
    }
}
