package com.auralink.catalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import com.auralink.config.properties.PaintingProperties;

class PaintingCatalogImportRunnerTest {

    private final ApplicationArguments arguments = mock(ApplicationArguments.class);

    @Test
    void disabledStartupImportDoesNotTouchImporter() {
        PaintingProperties properties = new PaintingProperties();
        properties.setImportEnabled(false);
        PaintingCatalogImporter importer = mock(PaintingCatalogImporter.class);
        PaintingCatalogImportRunner runner = new PaintingCatalogImportRunner(properties, importer);

        assertThatCode(() -> runner.run(arguments)).doesNotThrowAnyException();

        verify(importer, never()).importCatalog();
    }

    @Test
    void enabledStartupImportRunsExactlyOnce() {
        PaintingProperties properties = new PaintingProperties();
        properties.setImportEnabled(true);
        PaintingCatalogImporter importer = mock(PaintingCatalogImporter.class);
        when(importer.importCatalog()).thenReturn(successResult());
        PaintingCatalogImportRunner runner = new PaintingCatalogImportRunner(properties, importer);

        runner.run(arguments);

        verify(importer, times(1)).importCatalog();
    }

    @Test
    void enabledFailFastPolicyPropagatesOriginalImportFailureWithoutRetry() {
        PaintingProperties properties = new PaintingProperties();
        properties.setImportEnabled(true);
        properties.setImportFailOnError(true);
        PaintingCatalogImporter importer = mock(PaintingCatalogImporter.class);
        CatalogSourceException failure = new CatalogSourceException("synthetic failure");
        when(importer.importCatalog()).thenThrow(failure);
        PaintingCatalogImportRunner runner = new PaintingCatalogImportRunner(properties, importer);

        assertThatThrownBy(() -> runner.run(arguments)).isSameAs(failure);
        verify(importer, times(1)).importCatalog();
    }

    @Test
    void enabledTolerantPolicyRecordsFailureByImporterAndAllowsStartupToContinue() {
        PaintingProperties properties = new PaintingProperties();
        properties.setImportEnabled(true);
        properties.setImportFailOnError(false);
        PaintingCatalogImporter importer = mock(PaintingCatalogImporter.class);
        when(importer.importCatalog()).thenThrow(new CatalogImportException(
                "Catalog import failed", new IllegalStateException("synthetic")));
        PaintingCatalogImportRunner runner = new PaintingCatalogImportRunner(properties, importer);

        assertThatCode(() -> runner.run(arguments)).doesNotThrowAnyException();
        verify(importer, times(1)).importCatalog();
    }

    private CatalogImportResult successResult() {
        return new CatalogImportResult(
                "00000000-0000-0000-0000-000000000000",
                CatalogImportStatus.SUCCESS,
                "a".repeat(64),
                2,
                1,
                0,
                1,
                1,
                1,
                0);
    }
}
