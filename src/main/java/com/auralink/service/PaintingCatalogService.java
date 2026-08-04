package com.auralink.service;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.auralink.config.AppConfig.PaintingConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaintingCatalogService {

    private final PaintingConfig paintingConfig;
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    private volatile List<PaintingEntry> cachedEntries = List.of();
    private volatile Map<String, String> imageNameMapping = Map.of();

    public PaintingPage search(String query, String dynasty, Integer limit, Integer offset) {
        ensureLoaded();

        int safeLimit = normalizeLimit(limit);
        int safeOffset = Math.max(0, offset == null ? 0 : offset);
        String normalizedQuery = normalizeLower(query);
        String normalizedDynasty = normalizeLower(dynasty);

        List<PaintingEntry> filtered = cachedEntries.stream()
                .filter(entry -> matchQuery(entry, normalizedQuery))
                .filter(entry -> matchDynasty(entry, normalizedDynasty))
                .toList();

        int total = filtered.size();
        int start = Math.min(safeOffset, total);
        int end = Math.min(start + safeLimit, total);

        List<Map<String, Object>> items = filtered.subList(start, end).stream()
                .map(PaintingEntry::payload)
                .toList();

        return new PaintingPage(total, safeLimit, safeOffset, items);
    }

    public Optional<Path> resolveImagePath(String fileNameOrStorageName) {
        Path pictureDir = getPictureDirPath();
        if (!Files.isDirectory(pictureDir)) {
            return Optional.empty();
        }
        String input = sanitizeInput(fileNameOrStorageName);
        if (input.isBlank()) {
            return Optional.empty();
        }

        Map<String, String> mappingSnapshot = imageNameMapping;
        Set<String> candidates = new LinkedHashSet<>();
        String mappedByRaw = mappingSnapshot.get(input);
        if (StringUtils.hasText(mappedByRaw)) {
            candidates.add(mappedByRaw);
        }

        String mappedByNormalized = mappingSnapshot.get(normalizeStorageName(input));
        if (StringUtils.hasText(mappedByNormalized)) {
            candidates.add(mappedByNormalized);
        }

        candidates.addAll(buildFileNameCandidates(input));

        for (String candidate : candidates) {
            Path resolved = pictureDir.resolve(candidate).normalize();
            if (!resolved.startsWith(pictureDir)) {
                continue;
            }
            if (Files.isRegularFile(resolved)) {
                return Optional.of(resolved);
            }
        }
        return Optional.empty();
    }

    private void ensureLoaded() {
        if (loaded.get()) {
            return;
        }
        synchronized (loaded) {
            if (loaded.get()) {
                return;
            }
            reload();
            loaded.set(true);
        }
    }

    private void reload() {
        Path csvPath = getMetadataCsvPath();
        Path pictureDir = getPictureDirPath();

        if (!Files.isRegularFile(csvPath)) {
            throw new IllegalStateException("Paintings metadata CSV not found: " + csvPath.toAbsolutePath());
        }

        List<PaintingEntry> entries = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setQuote('"')
                .build();

        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
                CSVParser parser = format.parse(reader)) {
            List<String> headers = parser.getHeaderNames();
            for (CSVRecord record : parser) {
                LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
                for (String header : headers) {
                    payload.put(header, normalizeCell(record.get(header)));
                }

                String id = getCell(record, 0);
                String imageStorageName = getCell(record, 1);
                String title = getCell(record, 2);
                String author = getCell(record, 3);
                String dynasty = getCell(record, 8);
                String category = getCell(record, 11);

                String resolvedImageFileName = resolveExistingImageFileName(imageStorageName, pictureDir).orElse("");

                payload.put("id", id);
                payload.put("imageStorageName", imageStorageName);
                payload.put("imageFileName", resolvedImageFileName);
                payload.put("imageAvailable", !resolvedImageFileName.isBlank());
                payload.put("title", title);
                payload.put("author", author);
                payload.put("dynasty", dynasty);
                payload.put("category", category);

                if (!imageStorageName.isBlank() && !resolvedImageFileName.isBlank()) {
                    mapping.put(imageStorageName, resolvedImageFileName);
                    mapping.put(normalizeStorageName(imageStorageName), resolvedImageFileName);
                }
                if (!resolvedImageFileName.isBlank()) {
                    mapping.put(resolvedImageFileName, resolvedImageFileName);
                }

                String searchableText = String.join(" ",
                        title,
                        author,
                        dynasty,
                        category,
                        getCell(record, 12),
                        getCell(record, 13),
                        getCell(record, 14));

                entries.add(new PaintingEntry(payload, normalizeLower(searchableText), normalizeLower(dynasty)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load paintings metadata CSV: " + e.getMessage(), e);
        }

        this.cachedEntries = List.copyOf(entries);
        this.imageNameMapping = Map.copyOf(mapping);
        log.info("Loaded paintings metadata: {} records, {} image mappings", entries.size(), mapping.size());
    }

    private Optional<String> resolveExistingImageFileName(String imageStorageName, Path pictureDir) {
        if (!StringUtils.hasText(imageStorageName)) {
            return Optional.empty();
        }
        for (String candidate : buildFileNameCandidates(imageStorageName)) {
            Path resolved = pictureDir.resolve(candidate).normalize();
            if (!resolved.startsWith(pictureDir)) {
                continue;
            }
            if (Files.isRegularFile(resolved)) {
                return Optional.of(resolved.getFileName().toString());
            }
        }
        return Optional.empty();
    }

    private List<String> buildFileNameCandidates(String rawName) {
        String sanitized = sanitizeInput(rawName);
        if (sanitized.isBlank()) {
            return List.of();
        }

        String withExt = ensureJpgExtension(sanitized);
        String normalized = normalizeStorageName(withExt);
        String withSpaceBeforeParen = normalized.replaceAll("(?<=\\d)\\(", " (");
        String noSpaceBeforeParen = normalized.replaceAll("\\s+\\(", "(");

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(withExt);
        candidates.add(normalized);
        candidates.add(withSpaceBeforeParen);
        candidates.add(noSpaceBeforeParen);
        return List.copyOf(candidates);
    }

    private String ensureJpgExtension(String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return trimmed;
        }
        return trimmed + ".jpg";
    }

    private String sanitizeInput(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized.trim();
    }

    private String normalizeStorageName(String value) {
        return value
                .replace('（', '(')
                .replace('）', ')')
                .replace('\uFF08', '(')
                .replace('\uFF09', ')')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int normalizeLimit(Integer limit) {
        int defaultLimit = paintingConfig.getDefaultLimit() == null ? 500 : paintingConfig.getDefaultLimit();
        int maxLimit = paintingConfig.getMaxLimit() == null ? 2000 : paintingConfig.getMaxLimit();
        int chosen = limit == null ? defaultLimit : limit;
        if (chosen <= 0) {
            return defaultLimit;
        }
        return Math.min(chosen, maxLimit);
    }

    private boolean matchQuery(PaintingEntry entry, String normalizedQuery) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return true;
        }
        return entry.searchable().contains(normalizedQuery);
    }

    private boolean matchDynasty(PaintingEntry entry, String normalizedDynasty) {
        if (!StringUtils.hasText(normalizedDynasty)) {
            return true;
        }
        return entry.dynasty().contains(normalizedDynasty);
    }

    private String normalizeLower(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private String getCell(CSVRecord record, int index) {
        if (index < 0 || index >= record.size()) {
            return "";
        }
        return normalizeCell(record.get(index));
    }

    private Path getMetadataCsvPath() {
        String configured = paintingConfig.getMetadataCsvPath();
        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(configured)) {
            candidates.add(configured.trim());
        }

        // Fallbacks for different backend startup directories
        candidates.add("../app/assets/paintings.csv");
        candidates.add("app/assets/paintings.csv");
        candidates.add("./app/assets/paintings.csv");

        for (String candidate : candidates) {
            Path path = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }

        // Keep old behavior for clear startup error message
        return Paths.get(candidates.get(0)).toAbsolutePath().normalize();
    }

    private Path getPictureDirPath() {
        return Paths.get(paintingConfig.getPictureDir()).toAbsolutePath().normalize();
    }

    public record PaintingPage(int total, int limit, int offset, List<Map<String, Object>> items) {
    }

    private record PaintingEntry(Map<String, Object> payload, String searchable, String dynasty) {
    }
}
