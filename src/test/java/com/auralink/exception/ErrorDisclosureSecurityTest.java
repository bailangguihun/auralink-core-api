package com.auralink.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.auralink.config.properties.PaintingProperties;
import com.auralink.dto.ApiResponse;
import com.auralink.service.PaintingCatalogService;

class ErrorDisclosureSecurityTest {

    @Test
    void globalUnexpectedErrorResponseDoesNotEchoInternalExceptionDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ApiResponse<Void> response = handler.handleException(
                new StorageException("failed at /srv/private/uploads/result.png"));

        assertEquals("服务器内部错误", response.getMessage());
        assertFalse(response.getMessage().contains("/srv/private"));
    }

    @Test
    void missingPaintingCatalogErrorDoesNotContainConfiguredAbsolutePath() {
        Path missingPath = Path.of("/srv/private/catalog/paintings.csv");
        PaintingProperties properties = new PaintingProperties();
        properties.setMetadataCsvPath(missingPath.toString());
        PaintingCatalogService service = new PaintingCatalogService(properties);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.search(null, null, 1, 0));

        assertFalse(exception.getMessage().contains(missingPath.toString()));
        assertEquals("Painting catalog metadata is unavailable", exception.getMessage());
    }
}
