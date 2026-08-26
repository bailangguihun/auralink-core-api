package com.auralink.catalog;

/** One deterministic, non-recursive catalog-directory manifest entry. */
public record ImageManifestEntry(String fileName, long size, long lastModifiedMillis) {

    public ImageManifestEntry {
        if (fileName == null || fileName.isBlank()
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || fileName.codePoints().anyMatch(Character::isISOControl)) {
            throw new CatalogSourceException("Catalog image manifest contains an invalid filename");
        }
        if (size < 0 || lastModifiedMillis < 0) {
            throw new CatalogSourceException("Catalog image manifest contains invalid metadata");
        }
    }
}
