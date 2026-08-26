package com.auralink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.ArgumentMatchers.any;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.auralink.config.properties.StorageProperties;
import com.auralink.exception.InvalidStoragePathException;

class StorageServicePathSecurityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesValidTopLevelFile() throws IOException {
        StorageService service = serviceFor(temporaryDirectory);
        Path file = Files.writeString(temporaryDirectory.resolve("result.png"), "image");

        assertEquals(file.toRealPath(), service.resolveStoredFile("result.png").toRealPath());
    }

    @Test
    void resolvesValidNestedLegacyFile() throws IOException {
        StorageService service = serviceFor(temporaryDirectory);
        Path file = temporaryDirectory.resolve("audio/2026-08/7/result.mp3");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "audio");

        assertEquals(file.toRealPath(),
                service.resolveStoredFile("audio/2026-08/7/result.mp3").toRealPath());
    }

    @Test
    void returnsContainedCandidateForMissingFile() {
        StorageService service = serviceFor(temporaryDirectory);

        Path candidate = service.resolveStoredFile("image/2026-08/7/missing.png");

        assertTrue(candidate.startsWith(temporaryDirectory.toAbsolutePath().normalize()));
        assertFalse(Files.exists(candidate));
    }

    @Test
    void rejectsParentTraversalAndNestedTraversal() {
        StorageService service = serviceFor(temporaryDirectory);

        assertThrows(InvalidStoragePathException.class,
                () -> service.resolveStoredFile("../outside.txt"));
        assertThrows(InvalidStoragePathException.class,
                () -> service.resolveStoredFile("audio/../result.mp3"));
        assertThrows(InvalidStoragePathException.class,
                () -> service.resolveStoredFile("audio/../../sibling/result.mp3"));
    }

    @Test
    void rejectsAbsoluteAndWindowsStylePaths() {
        StorageService service = serviceFor(temporaryDirectory);

        assertThrows(InvalidStoragePathException.class,
                () -> service.resolveStoredFile(temporaryDirectory.resolve("outside").toString()));
        assertThrows(InvalidStoragePathException.class,
                () -> service.resolveStoredFile("C:\\private\\file.txt"));
        assertThrows(InvalidStoragePathException.class,
                () -> service.resolveStoredFile("\\\\server\\share\\file.txt"));
    }

    @Test
    void rejectsCrLfPathCharacters() {
        StorageService service = serviceFor(temporaryDirectory);

        assertThrows(InvalidStoragePathException.class,
                () -> service.resolveStoredFile("missing\rforged.txt"));
        assertThrows(InvalidStoragePathException.class,
                () -> service.resolveStoredFile("missing\nforged.txt"));
    }

    @Test
    void rejectsSiblingDirectoryEscape() {
        StorageService service = serviceFor(temporaryDirectory.resolve("storage"));

        assertThrows(InvalidStoragePathException.class,
                () -> service.resolveStoredFile("../storage-sibling/secret.txt"));
    }

    @Test
    void rejectsSymlinkEscape() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("storage"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path link = root.resolve("linked");
        Files.createSymbolicLink(link, outside);
        StorageService service = serviceFor(root);

        assertThrows(InvalidStoragePathException.class,
                () -> service.resolveStoredFile("linked/secret.txt"));
    }

    @Test
    void returnsCanonicalPathForExistingFileReachedThroughSafeSymlink() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("storage"));
        Path actualDirectory = Files.createDirectories(root.resolve("actual"));
        Path file = Files.writeString(actualDirectory.resolve("result.png"), "image");
        Files.createSymbolicLink(root.resolve("linked"), actualDirectory);
        StorageService service = serviceFor(root);

        assertEquals(file.toRealPath(), service.resolveStoredFile("linked/result.png"));
    }

    @Test
    void keepsBase64StorageContractWithRelativeResult() throws IOException {
        StorageService service = serviceFor(temporaryDirectory);
        byte[] content = "generated-image".getBytes();

        String relative = service.storeBase64File(
                Base64.getEncoder().encodeToString(content), "image", ".png", 7L);

        assertTrue(relative.matches("image/\\d{4}-\\d{2}/7/[0-9a-f-]+\\.png"));
        assertEquals("generated-image", Files.readString(service.resolveStoredFile(relative)));
    }

    @Test
    void delegatesRemoteStorageToSafeFetcherAndReturnsRelativeResult() throws IOException {
        StorageProperties properties = new StorageProperties();
        properties.setUploadDir(temporaryDirectory.toString());
        SafeRemoteResourceFetcher fetcher = mock(SafeRemoteResourceFetcher.class);
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "downloaded");
            return null;
        }).when(fetcher).fetchTo(eq("https://public.example/result.png"), any(Path.class));
        StorageService service = new StorageService(properties, fetcher);

        String relative = service.storeRemoteFile(
                "https://public.example/result.png", "image", ".png", 7L);

        assertTrue(relative.matches("image/\\d{4}-\\d{2}/7/[0-9a-f-]+\\.png"));
        assertEquals("downloaded", Files.readString(service.resolveStoredFile(relative)));
    }

    private StorageService serviceFor(Path root) {
        StorageProperties properties = new StorageProperties();
        properties.setUploadDir(root.toString());
        return new StorageService(properties, mock(SafeRemoteResourceFetcher.class));
    }
}
