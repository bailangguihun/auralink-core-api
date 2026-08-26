package com.auralink.catalog;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.auralink.config.properties.PaintingProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Optional startup hook; disabled unless explicitly enabled for a migrated DB. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaintingCatalogImportRunner implements ApplicationRunner {

    private final PaintingProperties properties;
    private final PaintingCatalogImporter importer;

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.isImportEnabled()) {
            log.info("Official painting catalog startup import is disabled");
            return;
        }

        try {
            CatalogImportResult result = importer.importCatalog();
            log.info(
                    "Official painting catalog import completed: status={}, total={}, inserted={}, updated={}, unchanged={}",
                    result.status(),
                    result.totalRows(),
                    result.insertedRows(),
                    result.updatedRows(),
                    result.unchangedRows());
        } catch (RuntimeException exception) {
            log.error("Official painting catalog import failed; type={}",
                    exception.getClass().getSimpleName());
            if (properties.isImportFailOnError()) {
                throw exception;
            }
        }
    }
}
