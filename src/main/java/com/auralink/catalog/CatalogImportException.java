package com.auralink.catalog;

/** Raised after a catalog import failure has been recorded in its audit row. */
public class CatalogImportException extends RuntimeException {

    public CatalogImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
