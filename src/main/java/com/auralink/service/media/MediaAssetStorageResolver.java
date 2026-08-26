package com.auralink.service.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.exception.InvalidStoragePathException;
import com.auralink.media.MediaAssetValues;

import lombok.RequiredArgsConstructor;

/** Resolves hidden logical MediaAsset keys into one of two contained roots. */
@Component
@RequiredArgsConstructor
public class MediaAssetStorageResolver {

    public static final String CATALOG_PREFIX = "catalog/";
    public static final String MANAGED_PREFIX = "managed/";

    private final MediaAssetProperties mediaAssetProperties;
    private final PaintingProperties paintingProperties;

    public String toCatalogStorageKey(String relativeName) {
        Path relative = parseRelativePath(relativeName, "catalog resource name");
        String normalized = toLogicalPath(relative.normalize());
        if (normalized.isBlank() || normalized.equals(".")) {
            throw invalid("Catalog resource name must identify a file");
        }
        return CATALOG_PREFIX + normalized;
    }

    public Path resolveForRead(String sourceType, String storageKey) {
        String normalizedSource = MediaAssetValues.requireSupportedSourceType(sourceType);
        if (MediaAssetValues.SourceType.CATALOG_REFERENCE.equals(normalizedSource)) {
            return resolveCatalogForRead(storageKey);
        }
        if (MediaAssetValues.SourceType.USER_UPLOAD.equals(normalizedSource)
                || MediaAssetValues.SourceType.GENERATED.equals(normalizedSource)
                || MediaAssetValues.SourceType.LEGACY_IMPORT.equals(normalizedSource)) {
            return resolveManagedForRead(storageKey);
        }
        throw invalid("MediaAsset source type has no storage family");
    }

    public Path resolveManagedForWrite(String storageKey) {
        Path relative = relativePart(storageKey, MANAGED_PREFIX, "managed storage key");
        Path root = managedRoot();
        Path candidate = containedLexically(root, relative);
        verifyExistingAncestor(root, candidate);
        return candidate;
    }

    public Path resolveManagedForRead(String storageKey) {
        Path candidate = resolveManagedForWrite(storageKey);
        return requireCanonicalRegularFile(managedRoot(), candidate);
    }

    public Path resolveCatalogForRead(String storageKey) {
        Path relative = relativePart(storageKey, CATALOG_PREFIX, "catalog storage key");
        Path root = catalogRoot();
        Path candidate = containedLexically(root, relative);
        return requireCanonicalRegularFile(root, candidate);
    }

    public Path managedRoot() {
        return configuredRoot(mediaAssetProperties.getManagedDir(), "managed MediaAsset root");
    }

    public Path catalogRoot() {
        return configuredRoot(paintingProperties.getPictureDir(), "catalog picture root");
    }

    private Path relativePart(String storageKey, String requiredPrefix, String label) {
        if (storageKey == null || !storageKey.startsWith(requiredPrefix)) {
            throw invalid("MediaAsset storage key does not match its storage family");
        }
        String value = storageKey.substring(requiredPrefix.length());
        Path relative = parseRelativePath(value, label);
        if (relative.getNameCount() == 0 || toLogicalPath(relative).equals(".")) {
            throw invalid("MediaAsset storage key must identify a file");
        }
        return relative;
    }

    private Path parseRelativePath(String value, String label) {
        if (value == null || value.isBlank() || containsControl(value)) {
            throw invalid(label + " is empty or invalid");
        }
        if (value.indexOf('\\') >= 0
                || value.startsWith("/")
                || value.startsWith("//")
                || value.matches("^[A-Za-z]:.*")) {
            throw invalid(label + " must use a relative logical path");
        }

        final Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException exception) {
            throw new InvalidStoragePathException("MediaAsset storage key is invalid", exception);
        }
        if (path.isAbsolute()) {
            throw invalid(label + " must be relative");
        }
        for (Path segment : path) {
            String name = segment.toString();
            if (name.equals(".") || name.equals("..") || name.isBlank()) {
                throw invalid(label + " contains a forbidden path segment");
            }
        }
        return path;
    }

    private Path configuredRoot(String configured, String label) {
        if (configured == null || configured.isBlank() || containsControl(configured)) {
            throw invalid(label + " is not configured");
        }
        try {
            return Path.of(configured).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new InvalidStoragePathException(label + " is invalid", exception);
        }
    }

    private Path containedLexically(Path root, Path relative) {
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw invalid("MediaAsset storage key escapes its configured root");
        }
        return candidate;
    }

    private void verifyExistingAncestor(Path root, Path candidate) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Path realRoot = root.toRealPath();
            Path existing = candidate;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(realRoot)) {
                throw invalid("MediaAsset path escapes its root through a symbolic link");
            }
        } catch (InvalidStoragePathException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new InvalidStoragePathException("MediaAsset path containment could not be verified", exception);
        }
    }

    private Path requireCanonicalRegularFile(Path root, Path candidate) {
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("MediaAsset storage root is unavailable");
            }
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw invalid("MediaAsset path escapes its root through a symbolic link");
            }
            if (!Files.isRegularFile(realCandidate, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(realCandidate)) {
                throw invalid("MediaAsset resource is unavailable");
            }
            return realCandidate;
        } catch (InvalidStoragePathException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new InvalidStoragePathException("MediaAsset resource is unavailable", exception);
        }
    }

    private boolean containsControl(String value) {
        return value.codePoints().anyMatch(character -> Character.isISOControl(character));
    }

    private String toLogicalPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private InvalidStoragePathException invalid(String message) {
        return new InvalidStoragePathException(message);
    }
}
