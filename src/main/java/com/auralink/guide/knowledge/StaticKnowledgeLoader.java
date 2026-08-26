package com.auralink.guide.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.auralink.config.properties.GuideProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lazily loads the two inherited poetry knowledge files into a small immutable
 * in-memory representation. Source paths never come from an API request.
 */
@Component
public class StaticKnowledgeLoader {

    static final long MAX_GRAPH_BYTES = 1_048_576;
    static final long MAX_STATS_BYTES = 131_072;
    private static final int MAX_NODES = 10_000;
    private static final int MAX_LINKS = 20_000;

    private static final Set<String> GRAPH_FIELDS = Set.of("nodes", "links");
    private static final Set<String> NODE_FIELDS = Set.of(
            "id", "name", "category", "size", "description", "tooltip");
    private static final Set<String> LINK_FIELDS = Set.of(
            "source", "target", "value", "label", "summary", "detail");
    private static final Set<String> STATS_FIELDS = Set.of(
            "overview", "entityTypeDistribution", "relationTypeDistribution");
    private static final Set<String> OVERVIEW_FIELDS = Set.of(
            "entities", "relations", "poems", "poets");

    private final GuideProperties properties;
    private final ObjectMapper objectMapper;
    private final Path configurationBase;
    private final Path allowedKnowledgeRoot;
    private volatile LoadedKnowledge cached;

    @Autowired
    public StaticKnowledgeLoader(GuideProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, detectConfigurationBase(), detectKnowledgeRoot());
    }

    StaticKnowledgeLoader(
            GuideProperties properties,
            ObjectMapper objectMapper,
            Path configurationBase,
            Path allowedKnowledgeRoot
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.configurationBase = configurationBase.toAbsolutePath().normalize();
        this.allowedKnowledgeRoot = allowedKnowledgeRoot.toAbsolutePath().normalize();
    }

    LoadedKnowledge load() {
        LoadedKnowledge current = cached;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cached == null) {
                cached = loadFiles();
            }
            return cached;
        }
    }

    private LoadedKnowledge loadFiles() {
        try {
            Path graphPath = resolveKnowledgeFile(properties.getPoetryGraphPath());
            Path statsPath = resolveKnowledgeFile(properties.getPoetryStatsPath());
            byte[] graphBytes = readBounded(graphPath, MAX_GRAPH_BYTES);
            byte[] statsBytes = readBounded(statsPath, MAX_STATS_BYTES);

            JsonNode graph = objectMapper.readTree(graphBytes);
            JsonNode stats = objectMapper.readTree(statsBytes);
            List<KnowledgeNode> nodes = validateGraph(graph);
            validateStats(stats, nodes.size(), graph.path("links").size());

            Map<String, String> fingerprints = new LinkedHashMap<>();
            fingerprints.put("poetryGraphSha256", sha256(graphBytes));
            fingerprints.put("poetryStatsSha256", sha256(statsBytes));
            return new LoadedKnowledge(nodes, fingerprints);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof KnowledgeLoadingException knowledgeLoadingException) {
                throw knowledgeLoadingException;
            }
            throw new KnowledgeLoadingException("Configured guide knowledge could not be loaded", exception);
        }
    }

    private Path resolveKnowledgeFile(String configuredPath) throws IOException {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new KnowledgeLoadingException("Guide knowledge path is not configured");
        }

        Path rootReal = allowedKnowledgeRoot.toRealPath();
        Path raw = Path.of(configuredPath.strip());
        Path candidate = raw.isAbsolute()
                ? raw.normalize()
                : configurationBase.resolve(raw).normalize();

        if (!candidate.startsWith(allowedKnowledgeRoot)
                || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new KnowledgeLoadingException("Guide knowledge path is outside the approved data directory");
        }

        Path real = candidate.toRealPath();
        if (!real.startsWith(rootReal) || !Files.isRegularFile(real)) {
            throw new KnowledgeLoadingException("Guide knowledge path is outside the approved data directory");
        }
        return real;
    }

    private byte[] readBounded(Path path, long maximumBytes) throws IOException {
        if (Files.size(path) > maximumBytes) {
            throw new KnowledgeLoadingException("Guide knowledge file exceeds its size limit");
        }

        try (InputStream input = Files.newInputStream(path);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) {
                    throw new KnowledgeLoadingException("Guide knowledge file exceeds its size limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private List<KnowledgeNode> validateGraph(JsonNode graph) {
        requireObjectWithFields(graph, GRAPH_FIELDS, "poetry graph");
        JsonNode nodesNode = graph.get("nodes");
        JsonNode linksNode = graph.get("links");
        if (!nodesNode.isArray() || !linksNode.isArray()
                || nodesNode.size() > MAX_NODES || linksNode.size() > MAX_LINKS) {
            throw new KnowledgeLoadingException("Poetry graph has an invalid structure");
        }

        List<KnowledgeNode> nodes = new ArrayList<>(nodesNode.size());
        Set<String> nodeIds = new HashSet<>();
        for (JsonNode node : nodesNode) {
            requireObjectWithFields(node, NODE_FIELDS, "poetry graph node");
            String id = requiredText(node, "id");
            String name = requiredText(node, "name");
            String category = requiredText(node, "category");
            String description = requiredText(node, "description");
            requiredText(node, "tooltip");
            if (!node.get("size").isNumber() || !nodeIds.add(id)) {
                throw new KnowledgeLoadingException("Poetry graph contains an invalid node");
            }
            nodes.add(new KnowledgeNode(id, name, category, normalizeDescription(description)));
        }

        for (JsonNode link : linksNode) {
            requireObjectWithFields(link, LINK_FIELDS, "poetry graph link");
            String source = requiredText(link, "source");
            String target = requiredText(link, "target");
            requiredText(link, "label");
            requiredText(link, "summary");
            requiredText(link, "detail");
            if (!link.get("value").isNumber()
                    || !nodeIds.contains(source)
                    || !nodeIds.contains(target)) {
                throw new KnowledgeLoadingException("Poetry graph contains an invalid link");
            }
        }
        return List.copyOf(nodes);
    }

    private void validateStats(JsonNode stats, int nodeCount, int linkCount) {
        requireObjectWithFields(stats, STATS_FIELDS, "poetry statistics");
        JsonNode overview = stats.get("overview");
        requireObjectWithFields(overview, OVERVIEW_FIELDS, "poetry statistics overview");
        for (String field : OVERVIEW_FIELDS) {
            if (!overview.get(field).canConvertToInt() || overview.get(field).intValue() < 0) {
                throw new KnowledgeLoadingException("Poetry statistics overview is invalid");
            }
        }
        if (overview.get("entities").intValue() != nodeCount
                || overview.get("relations").intValue() != linkCount) {
            throw new KnowledgeLoadingException("Poetry statistics do not match the graph");
        }
        validateDistribution(stats.get("entityTypeDistribution"));
        validateDistribution(stats.get("relationTypeDistribution"));
    }

    private void validateDistribution(JsonNode distribution) {
        if (!distribution.isArray() || distribution.size() > MAX_LINKS) {
            throw new KnowledgeLoadingException("Poetry statistics distribution is invalid");
        }
        for (JsonNode entry : distribution) {
            if (!entry.isArray() || entry.size() != 2
                    || !entry.get(0).isTextual() || entry.get(0).textValue().isBlank()
                    || !entry.get(1).canConvertToInt() || entry.get(1).intValue() < 0) {
                throw new KnowledgeLoadingException("Poetry statistics distribution is invalid");
            }
        }
    }

    private void requireObjectWithFields(JsonNode node, Set<String> fields, String description) {
        if (node == null || !node.isObject()) {
            throw new KnowledgeLoadingException("Invalid " + description + " structure");
        }
        Set<String> actual = new HashSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(fields)) {
            throw new KnowledgeLoadingException("Invalid " + description + " structure");
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new KnowledgeLoadingException("Guide knowledge contains a missing text field");
        }
        return value.textValue().strip();
    }

    private String normalizeDescription(String description) {
        return description.replace("<SEP>", "\n").strip();
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Path detectConfigurationBase() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("backend"))
                && Files.isDirectory(workingDirectory.resolve("frontend/public/data"))) {
            return workingDirectory.resolve("backend");
        }
        return workingDirectory;
    }

    private static Path detectKnowledgeRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("frontend/public/data"))) {
            return workingDirectory.resolve("frontend/public/data");
        }
        Path parent = workingDirectory.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("frontend/public/data"))) {
            return parent.resolve("frontend/public/data");
        }
        // Kept non-existent deliberately: loading will fail safely only when Guide is used.
        return workingDirectory.resolve("frontend/public/data");
    }

    record LoadedKnowledge(List<KnowledgeNode> nodes, Map<String, String> fingerprints) {
        LoadedKnowledge {
            nodes = List.copyOf(nodes);
            fingerprints = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(fingerprints));
        }
    }

    record KnowledgeNode(String id, String name, String category, String description) {
    }

    public static class KnowledgeLoadingException extends IllegalStateException {
        public KnowledgeLoadingException(String message) {
            super(message);
        }

        public KnowledgeLoadingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
