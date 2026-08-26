package com.auralink.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.auralink.config.properties.StorageProperties;
import com.auralink.service.GenerationService;
import com.auralink.service.SafeRemoteResourceFetcher;
import com.auralink.service.StorageService;
import com.auralink.service.UploadSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ApiControllerFileServingTest {

    @TempDir
    Path storageRoot;

    private ApiController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setUploadDir(storageRoot.toString());
        StorageService storageService = new StorageService(
                properties,
                mock(SafeRemoteResourceFetcher.class));
        controller = new ApiController(
                storageService,
                mock(GenerationService.class),
                mock(UploadSessionService.class),
                properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void uploadKeepsLegacyFilepathAsPublicUrlWithoutDisclosingStoragePath() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "file", "painting.png", "image/png", new byte[] {1, 2, 3});

        MvcResult result = mockMvc.perform(multipart("/api/upload-image").file(image))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = new ObjectMapper()
                .readTree(result.getResponse().getContentAsByteArray())
                .path("data");
        String filepath = data.path("filepath").asText();
        String imageUrl = data.path("imageUrl").asText();
        String relativePath = data.path("relativePath").asText();
        String legacyAbsolutePath = data.path("absolutePath").asText();

        assertEquals(imageUrl, filepath);
        assertEquals("/api/files/" + relativePath, URI.create(filepath).getPath());
        assertTrue(filepath.startsWith("http://localhost/api/files/"));
        assertFalse(relativePath.isBlank());
        assertFalse(Path.of(relativePath).isAbsolute());
        assertFalse(Path.of(legacyAbsolutePath).isAbsolute());
        assertEquals(relativePath, legacyAbsolutePath);
        assertFalse(filepath.contains(storageRoot.toString()));
        assertFalse(legacyAbsolutePath.contains(storageRoot.toString()));
    }

    @Test
    void servesValidNestedLegacyPath() throws IOException {
        Path file = storageRoot.resolve("audio/2026-08/7/result.mp3");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "audio");
        MockHttpServletRequest request = requestFor("/api/files/audio/2026-08/7/result.mp3");

        assertEquals(200, controller.serveFile(request).getStatusCode().value());
    }

    @Test
    void returnsNotFoundForContainedMissingFile() {
        MockHttpServletRequest request = requestFor("/api/files/audio/2026-08/7/missing.mp3");

        assertEquals(404, controller.serveFile(request).getStatusCode().value());
    }

    @Test
    void rejectsEncodedTraversalBeforeResolution() {
        MockHttpServletRequest request = requestFor("/api/files/%2e%2e/private.txt");

        assertEquals(400, controller.serveFile(request).getStatusCode().value());
    }

    @Test
    void rejectsDoubleEncodedTraversalBeforeResolution() {
        MockHttpServletRequest request = requestFor("/api/files/%252e%252e%252fprivate.txt");

        assertEquals(400, controller.serveFile(request).getStatusCode().value());
    }

    @Test
    void rejectsEncodedCrLfBeforeItCanReachLogs() {
        assertEquals(400, controller.serveFile(
                requestFor("/api/files/missing%0dforged.txt")).getStatusCode().value());
        assertEquals(400, controller.serveFile(
                requestFor("/api/files/missing%0aforged.txt")).getStatusCode().value());
    }

    private MockHttpServletRequest requestFor(String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        request.setRequestURI(requestUri);
        return request;
    }
}
