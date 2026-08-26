package com.auralink.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Safe implementation of the inherited painting filename convention.
 *
 * <p>The legacy four-candidate order is retained, including full-width
 * parenthesis normalization and the optional space before a parenthesis. A
 * candidate is returned only when it is an exact direct-child filename from a
 * pre-scanned manifest; no CSV value is resolved as a filesystem path.</p>
 */
@Component
public class PaintingImageMatcher {

    public CatalogImageManifest scan(Path pictureDirectory) {
        Path root = requirePictureDirectory(pictureDirectory);
        try (var files = Files.list(root)) {
            List<ImageManifestEntry> entries = files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isSupportedImageFileName(path.getFileName().toString()))
                    .map(this::manifestEntry)
                    .toList();
            return new CatalogImageManifest(entries);
        } catch (CatalogSourceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new CatalogSourceException("Catalog image manifest could not be created", exception);
        }
    }

    public Optional<String> match(String imageStorageName, CatalogImageManifest manifest) {
        if (manifest == null) {
            throw new CatalogSourceException("Catalog image manifest is required");
        }
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (String candidate : candidatesFor(imageStorageName)) {
            manifest.findIgnoringCase(candidate)
                    .map(ImageManifestEntry::fileName)
                    .ifPresent(matches::add);
        }
        if (matches.size() > 1) {
            throw new CatalogSourceException(
                    "Official painting image storage name resolves ambiguously");
        }
        return matches.stream().findFirst();
    }

    public List<String> candidatesFor(String rawName) {
        String sanitized = sanitizeInput(rawName);
        if (sanitized.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String withExtension : withSupportedExtensions(sanitized)) {
            String normalized = normalizeStorageName(withExtension);
            String withSpaceBeforeParenthesis = normalized.replaceAll("(?<=\\d)\\(", " (");
            String withoutSpaceBeforeParenthesis = normalized.replaceAll("\\s+\\(", "(");
            candidates.add(withExtension);
            candidates.add(normalized);
            candidates.add(withSpaceBeforeParenthesis);
            candidates.add(withoutSpaceBeforeParenthesis);
        }
        return List.copyOf(candidates);
    }

    private ImageManifestEntry manifestEntry(Path file) {
        try {
            String fileName = file.getFileName().toString();
            return new ImageManifestEntry(
                    fileName,
                    Files.size(file),
                    Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis());
        } catch (IOException exception) {
            throw new CatalogSourceException("Catalog image metadata could not be read", exception);
        }
    }

    private Path requirePictureDirectory(Path configured) {
        if (configured == null) {
            throw new CatalogSourceException("Catalog picture directory is required");
        }
        try {
            Path normalized = configured.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(normalized)) {
                throw new CatalogSourceException("Catalog picture directory is unavailable");
            }
            return normalized.toRealPath();
        } catch (CatalogSourceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CatalogSourceException("Catalog picture directory could not be resolved", exception);
        }
    }

    private List<String> withSupportedExtensions(String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return List.of(trimmed);
        }
        return List.of(trimmed + ".jpg", trimmed + ".jpeg");
    }

    private String sanitizeInput(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (containsControl(normalized)
                || normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0
                || normalized.matches("^[A-Za-z]:.*")
                || normalized.equals(".")
                || normalized.equals("..")) {
            throw new CatalogSourceException(
                    "Official painting image storage name must be a safe filename");
        }
        return normalized;
    }

    private String normalizeStorageName(String value) {
        return value
                .replace('（', '(')
                .replace('）', ')')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private boolean isSupportedImageFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }
}
