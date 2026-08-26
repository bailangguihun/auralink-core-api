package com.auralink.service.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.exception.InvalidStoragePathException;
import com.auralink.exception.StorageException;

import lombok.RequiredArgsConstructor;

/**
 * Filesystem implementation for logical 2.0 MediaAsset keys.
 *
 * <p>Managed inputs are streamed to a same-filesystem staging directory with
 * a hard byte cap and SHA-256 digest, then moved into place without replacing
 * an existing resource. Catalog inspection never copies the referenced file.</p>
 */
@Service
@RequiredArgsConstructor
public class MediaAssetStorageService {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String STAGING_DIRECTORY = ".staging";

    private final MediaAssetProperties properties;
    private final MediaAssetStorageResolver resolver;

    /** Consumes and closes the supplied stream. */
    public StagedMediaFile stageUserUpload(InputStream input) {
        return stage(input, properties.getMaxUploadBytes());
    }

    /** Consumes and closes the supplied stream. */
    public StagedMediaFile stageGenerated(InputStream input) {
        return stage(input, properties.getMaxGeneratedBytes());
    }

    public StoredMediaFile commitManaged(StagedMediaFile staged, String storageKey) {
        requireOwnedStagedFile(staged);
        Path target = resolver.resolveManagedForWrite(storageKey);
        try {
            Files.createDirectories(target.getParent());
            target = resolver.resolveManagedForWrite(storageKey);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                throw new StorageException("Managed MediaAsset destination already exists");
            }

            try {
                Files.move(staged.path(), target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(staged.path(), target);
            }
            return new StoredMediaFile(storageKey, staged.size(), staged.sha256());
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException("Managed MediaAsset could not be committed", exception);
        }
    }

    /** Resolve and inspect one catalog image without copying it. */
    public CatalogMediaFile inspectCatalogReference(String relativeName) {
        String storageKey = resolver.toCatalogStorageKey(relativeName);
        Path path = resolver.resolveCatalogForRead(storageKey);
        try {
            return new CatalogMediaFile(
                    storageKey,
                    path,
                    new FileSystemResource(path),
                    Files.size(path),
                    sha256(path));
        } catch (IOException exception) {
            throw new StorageException("Catalog MediaAsset could not be inspected", exception);
        }
    }

    /** Resolve an entity according to both its source type and logical namespace. */
    public MediaAssetStoredResource resolve(MediaAsset asset) {
        if (asset == null) {
            throw new InvalidStoragePathException("MediaAsset is required");
        }
        Path path = resolver.resolveForRead(asset.getSourceType(), asset.getStorageKey());
        try {
            return new MediaAssetStoredResource(new FileSystemResource(path), Files.size(path));
        } catch (IOException exception) {
            throw new StorageException("MediaAsset resource could not be inspected", exception);
        }
    }

    /** Delete only one contained managed file; catalog resources are never accepted. */
    public void deleteManaged(String storageKey) {
        Path candidate = resolver.resolveManagedForWrite(storageKey);
        try {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)) {
                return;
            }
            if (Files.isSymbolicLink(candidate)) {
                throw new InvalidStoragePathException("Refusing to delete a symbolic-link MediaAsset");
            }
            Path canonical = resolver.resolveManagedForRead(storageKey);
            Files.deleteIfExists(canonical);
        } catch (InvalidStoragePathException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException("Managed MediaAsset cleanup failed", exception);
        }
    }

    /** Delete a staging file only when it is proven to belong to this staging root. */
    public void discardStaged(StagedMediaFile staged) {
        if (staged == null) {
            return;
        }
        requireOwnedStagedPath(staged.path(), false);
        try {
            Files.deleteIfExists(staged.path());
        } catch (IOException exception) {
            throw new StorageException("Staged MediaAsset cleanup failed", exception);
        }
    }

    private StagedMediaFile stage(InputStream input, long maxBytes) {
        if (input == null) {
            throw new IllegalArgumentException("MediaAsset input stream is required");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("MediaAsset byte limit is invalid");
        }

        Path temporary = null;
        try {
            Path stagingRoot = ensureStagingRoot();
            temporary = Files.createTempFile(stagingRoot, "asset-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0L;

            try (InputStream source = new DigestInputStream(input, digest);
                    OutputStream destination = Files.newOutputStream(
                            temporary,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    if (total > maxBytes - read) {
                        throw new MediaAssetSizeLimitException("MediaAsset exceeds the configured byte limit");
                    }
                    destination.write(buffer, 0, read);
                    total += read;
                }
            }

            return new StagedMediaFile(temporary, total, HexFormat.of().formatHex(digest.digest()));
        } catch (MediaAssetSizeLimitException exception) {
            deleteTemporaryQuietly(temporary);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteTemporaryQuietly(temporary);
            throw new StorageException("MediaAsset could not be staged", exception);
        } catch (RuntimeException exception) {
            deleteTemporaryQuietly(temporary);
            throw exception;
        }
    }

    private Path ensureStagingRoot() throws IOException {
        Path managedRoot = resolver.managedRoot();
        Files.createDirectories(managedRoot);
        Path realManagedRoot = managedRoot.toRealPath();
        Path stagingRoot = managedRoot.resolve(STAGING_DIRECTORY).normalize();
        Files.createDirectories(stagingRoot);
        Path realStagingRoot = stagingRoot.toRealPath();
        if (!realStagingRoot.startsWith(realManagedRoot)) {
            throw new InvalidStoragePathException("MediaAsset staging directory escapes its root");
        }
        return realStagingRoot;
    }

    private void requireOwnedStagedFile(StagedMediaFile staged) {
        if (staged == null || staged.path() == null || staged.size() < 0 || staged.sha256() == null) {
            throw new IllegalArgumentException("Valid staged MediaAsset metadata is required");
        }
        requireOwnedStagedPath(staged.path(), true);
        try {
            if (Files.size(staged.path()) != staged.size()) {
                throw new StorageException("Staged MediaAsset size changed before commit");
            }
        } catch (IOException exception) {
            throw new StorageException("Staged MediaAsset could not be verified", exception);
        }
    }

    private void requireOwnedStagedPath(Path path, boolean requireFile) {
        try {
            Path stagingRoot = ensureStagingRoot();
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(stagingRoot)
                    || normalized.getParent() == null
                    || !normalized.getParent().equals(stagingRoot)
                    || Files.isSymbolicLink(normalized)) {
                throw new InvalidStoragePathException("Staged MediaAsset does not belong to this storage root");
            }
            if (requireFile && !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new InvalidStoragePathException("Staged MediaAsset is unavailable");
            }
        } catch (InvalidStoragePathException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new InvalidStoragePathException("Staged MediaAsset containment could not be verified", exception);
        }
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new StorageException("MediaAsset digest could not be computed", exception);
        }
    }

    private void deleteTemporaryQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The primary validation/storage failure is more actionable. The path is
            // a random file under the contained staging directory, never user input.
        }
    }

    public record StagedMediaFile(Path path, long size, String sha256) {
    }

    public record StoredMediaFile(String storageKey, long size, String sha256) {
    }

    public record CatalogMediaFile(
            String storageKey,
            Path path,
            FileSystemResource resource,
            long size,
            String sha256) {
    }

    public record MediaAssetStoredResource(FileSystemResource resource, long contentLength) {
    }
}
