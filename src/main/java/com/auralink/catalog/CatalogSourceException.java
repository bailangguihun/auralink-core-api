package com.auralink.catalog;

/** Raised when the configured official painting source cannot be read safely. */
public class CatalogSourceException extends RuntimeException {

    public CatalogSourceException(String message) {
        super(message);
    }

    public CatalogSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
