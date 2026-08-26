package com.auralink.guide.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.auralink.guide.context.PaintingGuideContext;
import com.auralink.guide.knowledge.KnowledgeSelection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Produces the provider-independent cache key from canonical source material. */
@Component
public class GuideSourceHasher {

    private final ObjectMapper canonicalMapper;

    public GuideSourceHasher(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hash(
            String schemaVersion,
            PaintingGuideContext context,
            KnowledgeSelection selection
    ) {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("Guide schema version is required");
        }
        if (context == null || selection == null) {
            throw new IllegalArgumentException("Guide context and knowledge selection are required");
        }

        CanonicalSource source = new CanonicalSource(
                schemaVersion.strip(),
                context.withKnowledge(selection.items()),
                new TreeMap<>(selection.fingerprints())
        );
        try {
            byte[] canonicalJson = canonicalMapper.writeValueAsBytes(source);
            return HexFormat.of().formatHex(sha256().digest(canonicalJson));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Guide source could not be canonicalized", exception);
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record CanonicalSource(
            String schemaVersion,
            PaintingGuideContext context,
            Map<String, String> knowledgeFingerprints
    ) {
    }
}
