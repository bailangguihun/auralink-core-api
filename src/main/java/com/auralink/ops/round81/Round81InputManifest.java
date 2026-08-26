package com.auralink.ops.round81;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import com.auralink.creation.provider.PaintingMetadataContext;
import com.auralink.provider.validation.StrictProviderJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict safe metadata accompanying the coordinator-created catalog input copy. */
record Round81InputManifest(
        String paintingId,
        String title,
        String author,
        String dynasty,
        String category,
        String subject,
        String paintingSchool,
        String style,
        String composition,
        String artisticConception,
        String generatedText,
        String musicSceneDescription,
        String mimeType,
        int width,
        int height,
        String sha256,
        String inputFile) {

    private static final Set<String> FIELDS = Set.of(
            "paintingId", "title", "author", "dynasty", "category", "subject",
            "paintingSchool", "style", "composition", "artisticConception",
            "generatedText", "musicSceneDescription", "mimeType", "width", "height",
            "sha256", "inputFile");

    static Round81InputManifest read(ObjectMapper mapper, java.nio.file.Path path) {
        final JsonNode root;
        try {
            root = StrictProviderJson.parse(mapper, java.nio.file.Files.readAllBytes(path));
        } catch (Exception exception) {
            throw new Round81ValidationException(
                    "INPUT_MANIFEST_INVALID", "Deterministic input metadata is invalid", exception);
        }
        if (root == null || !root.isObject()) {
            throw invalid();
        }
        Set<String> names = new HashSet<>();
        Iterator<String> iterator = root.fieldNames();
        iterator.forEachRemaining(names::add);
        if (!names.equals(FIELDS)) {
            throw invalid();
        }
        try {
            Round81InputManifest manifest = mapper.treeToValue(root, Round81InputManifest.class);
            manifest.validate();
            return manifest;
        } catch (Round81ValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new Round81ValidationException(
                    "INPUT_MANIFEST_INVALID", "Deterministic input metadata is invalid", exception);
        }
    }

    PaintingMetadataContext paintingMetadata() {
        return new PaintingMetadataContext(
                paintingId,
                title,
                author,
                dynasty,
                category,
                subject,
                paintingSchool,
                style,
                composition,
                artisticConception,
                generatedText,
                musicSceneDescription);
    }

    private void validate() {
        try {
            UUID.fromString(paintingId);
        } catch (RuntimeException exception) {
            throw invalid();
        }
        if (!("image/jpeg".equals(mimeType) || "image/png".equals(mimeType))
                || width < 1 || height < 1
                || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                || inputFile == null || !inputFile.matches("input-image\\.(?:jpg|png)")) {
            throw invalid();
        }
        for (String value : new String[] {
                title, author, dynasty, category, subject, paintingSchool, style,
                composition, artisticConception, generatedText, musicSceneDescription}) {
            if (value != null && (value.length() > 8_000
                    || value.chars().anyMatch(character -> character == 0))) {
                throw invalid();
            }
        }
    }

    private static Round81ValidationException invalid() {
        return new Round81ValidationException(
                "INPUT_MANIFEST_INVALID", "Deterministic input metadata is invalid");
    }
}
