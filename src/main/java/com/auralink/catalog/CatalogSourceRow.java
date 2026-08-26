package com.auralink.catalog;

/** One official record paired with its deterministic catalog image match. */
public record CatalogSourceRow(OfficialPaintingRecord record, String imageFileName) {

    public CatalogSourceRow {
        if (record == null) {
            throw new CatalogSourceException("Catalog source row requires an official painting record");
        }
        if (imageFileName != null) {
            imageFileName = imageFileName.trim();
            if (imageFileName.isBlank()
                    || imageFileName.indexOf('/') >= 0
                    || imageFileName.indexOf('\\') >= 0
                    || imageFileName.codePoints().anyMatch(Character::isISOControl)) {
                throw new CatalogSourceException("Catalog source row contains an unsafe image filename");
            }
        }
    }

    public boolean imageAvailable() {
        return imageFileName != null;
    }
}
