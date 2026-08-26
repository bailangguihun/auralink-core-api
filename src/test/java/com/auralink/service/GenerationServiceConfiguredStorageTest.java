package com.auralink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import com.auralink.config.properties.ServiceProperties;
import com.auralink.repository.GenerationLogRepository;

class GenerationServiceConfiguredStorageTest {

    @TempDir
    Path configuredStorageRoot;

    @Test
    void imageConversionResolvesLegacyPublicUrlThroughConfiguredStorageBoundary() throws Exception {
        byte[] imageBytes = new byte[] {10, 20, 30, 40};
        Path configuredImage = configuredStorageRoot.resolve("input.png");
        Files.write(configuredImage, imageBytes);

        StorageService storageService = mock(StorageService.class);
        when(storageService.resolveStoredFile("input.png")).thenReturn(configuredImage);
        GenerationService generationService = new GenerationService(
                mock(RestTemplate.class),
                new ServiceProperties(),
                mock(GenerationLogRepository.class),
                storageService);

        Method converter = GenerationService.class.getDeclaredMethod("convertImageToBase64", String.class);
        converter.setAccessible(true);
        String encoded = (String) converter.invoke(
                generationService,
                "https://host.example/api/files/input.png");

        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes), encoded);
        verify(storageService).resolveStoredFile("input.png");
    }
}
